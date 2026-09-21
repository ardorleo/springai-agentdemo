package io.github.javaside.springai.codetui.agent.tools;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * 主 agent 与子 agent 共用的 {@link ToolCallingManager} 接线：在 Spring AI 默认执行器上
 * 叠加本工程的韧性与回合内限流策略。两处调用点（{@code AgentTools} 主循环、
 * {@code SubagentRunner.execute}）必须<b>同源</b>取自这里——否则主/子 agent 的工具行为
 * 各自漂移，而「子 agent 行为和主 agent 不一样」是最难排查的那类缺陷。
 *
 * <p><b>为什么要管限流</b>：Spring AI 2.0.1 起 {@code DefaultToolCallingManager} 自带
 * 回合内限流，默认每工具 40 次、总量 150 次、撞限 {@code THROW}。THROW 抛出的
 * {@code ToolCallLimitExceededException} 是 RuntimeException——不是
 * {@code ToolExecutionException}，绕过 {@link ResilientToolExecutionExceptionProcessor}；
 * 又从 advisor 层直接冒出，{@link ResilientToolCallingManager} 也兜不住：一次撞限即杀整回合。
 *
 * <p><b>本工程策略：默认不限，配了也不杀回合</b>：
 * <ul>
 *   <li><b>默认不限</b>——agent 自主性优先，大任务不该被拍脑袋的数字拦腰截断；跑飞风险由
 *       既有机制兜底（上下文自动压缩、后台任务连续失败熔断、用户可随时取消）；</li>
 *   <li>需要熔断时可配环境变量：{@code CODETUI_MAX_CALLS_PER_TOOL}（回合内单工具上限）、
 *       {@code CODETUI_MAX_TOTAL_TOOL_CALLS}（回合内总量上限），缺失/非法/非正数一律视为不限
 *       （绝不因配置手误让启动崩掉或行为漂移）；</li>
 *   <li>配了限额后行为是 {@link ToolCallLimitBehavior#RETURN_ERROR_RESPONSE}：撞限不抛异常，
 *       错误文本作为该次调用的 tool 结果回给模型，由其自行收敛——与 Resilient 系列同一哲学：
 *       <b>错误是模型的输入，不是回合的死刑</b>。</li>
 * </ul>
 */
public final class TurnToolLimitWiring {

    /** 回合内单工具调用上限的环境变量名。 */
    static final String ENV_MAX_CALLS_PER_TOOL = "CODETUI_MAX_CALLS_PER_TOOL";

    /** 回合内工具调用总量上限的环境变量名。 */
    static final String ENV_MAX_TOTAL_TOOL_CALLS = "CODETUI_MAX_TOTAL_TOOL_CALLS";

    private TurnToolLimitWiring() {
    }

    /**
     * 产出主/子 agent 通用的工具执行器：Resilient 容错包装（工具名解析失败、工具执行异常
     * 均转错误文本回模型）+ 上述限流策略（默认不限，env 可配）。
     * observationRegistry 用 NOOP——工具活动的可观测性走 {@link ToolEventCallback} 事件上报，
     * 不走 micrometer。
     */
    public static ToolCallingManager create() {
        return create(parseLimit(System.getenv(ENV_MAX_CALLS_PER_TOOL)),
                parseLimit(System.getenv(ENV_MAX_TOTAL_TOOL_CALLS)));
    }

    /**
     * 注入重载：守卫测试直接传限额值，绕开进程 env 无法在测试里安全修改的问题
     * （形状照 {@code AgentTools.clampConcurrency} 的分层）。{@code null} = 不限。
     */
    static ToolCallingManager create(Integer maxCallsPerTool, Integer maxTotalToolCalls) {
        DefaultToolCallingManager.Builder builder = DefaultToolCallingManager.builder()
                .observationRegistry(ObservationRegistry.NOOP)
                // 工具执行异常不终止回合：转成错误文本回给模型让它继续（见该类 javadoc）。
                .toolExecutionExceptionProcessor(new ResilientToolExecutionExceptionProcessor())
                // 撞限回错误文本（模型自纠），绝不抛异常杀回合——这是本接线存在的核心理由。
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE);
        if (maxCallsPerTool != null) {
            builder.maxCallsPerTool(maxCallsPerTool);
        } else {
            builder.unlimitedCallsPerTool();
        }
        if (maxTotalToolCalls != null) {
            builder.maxTotalToolCalls(maxTotalToolCalls);
        } else {
            builder.unlimitedTotalToolCalls();
        }
        return new ResilientToolCallingManager(builder.build());
    }

    /**
     * 纯函数：解析限额 env 值。缺失/空白/非法/非正数 → {@code null}（= 不限）。
     * 抽成纯函数是为了能被单测直接盯住：「用户在 env 里写了个 abc」恰恰是最该被钉住的一条。
     */
    static Integer parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
