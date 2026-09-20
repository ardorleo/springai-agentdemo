package io.github.javaside.springai.codetui.agent.media;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 视觉预算：每请求分来源配额 + 每回合累计上限。
 *
 * <p><b>为什么配额分来源</b>：用户贴的图与工具产的图不是同一种负载。用户一次贴 1–3 张，
 * 那是他这一轮的<b>全部意图</b>；截图循环一个回合能产几十张，且旧截图几乎没有价值。
 * 若一视同仁按「从新到旧」取，「照这张稿子改」的稿子会被随后 Read 的三张图挤掉 ——
 * 功能在最典型的用法上直接失效。故<b>用户图保底不淘汰</b>，工具图只在彼此间竞争取最新一张。
 *
 * <p><b>为什么还要每回合累计</b>：每请求上限只封住单次上下文，封不住循环。图片不进会话历史，
 * 故<b>每轮请求都要重发一次</b>——同一张图会随迭代次数线性累积。20 次迭代 × 3 张 ≈ 108k token
 * 就在一个回合里。回合额度是唯一能真正封住单回合花费的机制。
 *
 * <p><b>为什么用户图与工具图各自一个额度</b>：两者共享一个计数器时，工具循环会挤掉用户图的额度
 * ——实测 2 张用户图在第 7 轮就被掐掉（同一回合可能跑几十轮工具调用），而他贴的图正是这一轮的
 * 全部意图，中途消失会让「照这张图改」在回合后半段直接失效。拆开之后两边的天花板各自可算：
 * <ul>
 *   <li>工具图 12 × ~1.8k ≈ 21.6k token（只发最新一张，故上限即轮数）；</li>
 *   <li>用户图 36 × ~1.8k ≈ 65k token（2 张图可撑 18 轮、3 张可撑 12 轮）。</li>
 * </ul>
 * 两边都可经环境变量覆盖，见 {@link #TOOL_TURN_BUDGET_ENV} / {@link #USER_TURN_BUDGET_ENV}。
 *
 * <p><b>为什么按 turnKey 分桶</b>：{@code ChatModel} 实例被主 agent 与所有子 agent 共用，
 * 并发子 agent 若共用一个计数器会互相冲掉对方的额度。turnKey 由调用方从消息锚点算出
 * （见 {@code VisionMaterializer}），同一回合内所有迭代恒定，不同 agent/回合天然不同。
 */
public final class VisionBudget {

    /** 每请求：用户当轮贴图上限（保底，不参与淘汰）。 */
    public static final int MAX_USER_IMAGES = 3;
    /** 每请求：工具产图上限（取最新一张）。 */
    public static final int MAX_TOOL_IMAGES = 1;
    /** 每请求：视觉 token 硬上限。 */
    public static final long MAX_REQUEST_TOKENS = 6_000L;
    /** 每回合：工具图累计兑现次数（张·次）上限。工具图每请求只发最新一张，故上限即轮数。 */
    public static final int MAX_TOOL_TURN_DELIVERIES = 12;
    /**
     * 每回合：用户图累计兑现次数（张·次）上限。
     *
     * <p>比工具图宽：工具图每请求只发 1 张，而用户图最多 3 张同时在场，故用户图每轮消耗是工具图的
     * 1–3 倍。取 36（= 3 张 × 12 轮）让「贴满 3 张」也能撑过 12 轮工具迭代，与工具图口径对齐；
     * 2 张图则可撑 18 轮。
     *
     * <p>必须有界：图片每轮重发，无界则单回合成本随轮数线性上涨——实测 2 张图跑 30 轮
     * = 60 张·次 ≈ 112.8k token，正是本类注释点名要防的场景。
     */
    public static final int MAX_USER_TURN_DELIVERIES = 36;
    /** 计数表容量上限——超过即清空，防长会话里自身泄漏。 */
    public static final int MAX_TRACKED_TURNS = 8;

    /** 覆盖「工具图」回合额度的环境变量；用于截图循环密集时放宽。 */
    public static final String TOOL_TURN_BUDGET_ENV = "CODETUI_VISION_TURN_BUDGET";
    /** 覆盖「用户图」回合额度的环境变量；长回合里贴图多时放宽。 */
    public static final String USER_TURN_BUDGET_ENV = "CODETUI_VISION_USER_TURN_BUDGET";

    private final int toolTurnDeliveries;
    private final int userTurnDeliveries;
    private final Map<String, AtomicInteger> perTurn = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perTurnUser = new ConcurrentHashMap<>();
    /** 最近一次 {@link #open} 的回合 key——工具执行期据此判断「当前回合」额度，见 {@link #currentTurnExhausted}。 */
    private volatile String lastTurnKey;

    public VisionBudget() {
        this(resolveBudget(System.getenv(TOOL_TURN_BUDGET_ENV), MAX_TOOL_TURN_DELIVERIES),
                resolveBudget(System.getenv(USER_TURN_BUDGET_ENV), MAX_USER_TURN_DELIVERIES));
    }

    /** 单值构造：工具图与用户图同额（测试用，便于把两边一起调小）。 */
    public VisionBudget(int turnDeliveries) {
        this(turnDeliveries, turnDeliveries);
    }

    public VisionBudget(int toolTurnDeliveries, int userTurnDeliveries) {
        this.toolTurnDeliveries = toolTurnDeliveries < 0 ? MAX_TOOL_TURN_DELIVERIES : toolTurnDeliveries;
        this.userTurnDeliveries = userTurnDeliveries < 0 ? MAX_USER_TURN_DELIVERIES : userTurnDeliveries;
    }

    /**
     * 解析额度环境变量：正整数生效，其余（未设/空/非法/负数）一律回落默认值。
     *
     * <p>不抛异常：这是装配期取值，一个手滑的环境变量不该让整个 TUI 起不来。
     * 纯函数供单测——{@code System.getenv} 在进程内无法注入。
     */
    public static int resolveBudget(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int v = Integer.parseInt(raw.trim());
            return v >= 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 本实例的「工具图」回合额度。 */
    public int toolTurnDeliveries() {
        return toolTurnDeliveries;
    }

    /** 本实例的「用户图」回合额度。 */
    public int userTurnDeliveries() {
        return userTurnDeliveries;
    }

    /**
     * 该回合的工具图额度是否已用尽——供工具执行期判断「现在 Read 也拿不回图」。
     * 未跟踪过的回合（计数为 0）恒为 false。
     */
    public boolean exhaustedFor(String turnKey) {
        AtomicInteger c = turnKey == null ? null : perTurn.get(turnKey);
        return c != null && c.get() >= toolTurnDeliveries;
    }

    /** 开一次「本请求」的预算会话。<b>用户图与工具图各一套计数器</b>，互不挤占。 */
    public Session open(String turnKey) {
        lastTurnKey = turnKey;
        return new Session(counter(perTurn, turnKey), toolTurnDeliveries,
                counter(perTurnUser, turnKey), userTurnDeliveries);
    }

    /**
     * 当前回合的工具图额度是否已用尽——供<b>工具执行期</b>（而非出站兑现期）判断
     * 「现在 Read 也拿不回图」。
     *
     * <p>取的是最近一次 {@link #open}（即最近一次模型请求）的回合 key：工具调用总是紧接在
     * 一次模型请求之后执行，故此刻它就是「正在跑的那个回合」。按回合而非按工具调用去查，
     * 是因为工具执行时并不知道自己属于哪个 turnKey（那是出站侧从消息锚点算的），
     * 而同一回合内所有请求的 turnKey 恒定，用最近一次即可。
     *
     * <p>尚未有过请求（冷启动）→ false，行为与引入本方法之前一致。
     */
    public boolean currentTurnExhausted() {
        return exhaustedFor(lastTurnKey);
    }

    /** 当前跟踪的回合数（测试用）。 */
    public int trackedTurns() {
        return perTurn.size();
    }

    /** 取该回合在指定计数表里的计数器；表满则整体清空（回合强时序，老 key 不会再被访问）。 */
    private AtomicInteger counter(Map<String, AtomicInteger> table, String turnKey) {
        if (table.size() >= MAX_TRACKED_TURNS && !table.containsKey(turnKey)) {
            table.clear();
        }
        return table.computeIfAbsent(turnKey, k -> new AtomicInteger());
    }

    /** 单次请求内的预算账本。非线程安全——一次 materialize 只在一个线程里跑完。 */
    public static final class Session {

        private final AtomicInteger toolCounter;
        private final int toolLimit;
        private final AtomicInteger userCounter;
        private final int userLimit;
        private long requestTokens;

        private Session(AtomicInteger toolCounter, int toolLimit,
                        AtomicInteger userCounter, int userLimit) {
            this.toolCounter = toolCounter;
            this.toolLimit = toolLimit;
            this.userCounter = userCounter;
            this.userLimit = userLimit;
        }

        /** 本请求的 token 预算还容得下这张图吗？容得下则记账并返回 true。 */
        public boolean admit(long tokens) {
            if (requestTokens + tokens > MAX_REQUEST_TOKENS) {
                return false;
            }
            requestTokens += tokens;
            return true;
        }

        /**
         * 占用一个「工具图」回合额度；已用尽返回 false（调用方据此写 turn_budget_exhausted）。
         *
         * <p>用户图走 {@link #tryConsumeUserTurnSlot()}，两套计数器互不挤占。
         */
        public boolean tryConsumeTurnSlot() {
            return toolCounter.incrementAndGet() <= toolLimit;
        }

        /** 占用一个「用户图」回合额度；已用尽返回 false。 */
        public boolean tryConsumeUserTurnSlot() {
            return userCounter.incrementAndGet() <= userLimit;
        }

        /** 本回合「工具图」额度是否已用尽（读，不占额）。 */
        public boolean turnExhausted() {
            return toolCounter.get() >= toolLimit;
        }

        /** 本请求已计入的视觉 token（供 /context 统计）。 */
        public long tokensUsed() {
            return requestTokens;
        }
    }
}
