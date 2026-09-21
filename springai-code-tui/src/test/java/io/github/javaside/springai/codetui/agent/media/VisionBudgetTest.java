package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionBudgetTest {

    @Test
    void userImagesAreCappedAtTen() {
        // 10 = 各模型每请求张数口径里最紧的一档（Qwen-VL 硬顶 10），依据见常量注释
        assertEquals(10, VisionBudget.MAX_USER_IMAGES);
        assertEquals(1, VisionBudget.MAX_TOOL_IMAGES);
    }

    /**
     * ★ token 上限不能成为张数配额的隐形瓶颈：默认上限必须容得下「10 张满档用户图」。
     * 最贵档是 OpenAI 聚合网关（实测公式无单图封顶，2048×1152 ≈ 2.8k/张）；
     * 上限 16k 的话第 6 张就拒，10 张配额对它名存实亡。
     */
    @Test
    void defaultTokenCapAdmitsFullQuotaOfWorstCaseImages() {
        VisionBudget.Session s = new VisionBudget().open("t");
        long worstCaseImage = 2_800;
        for (int i = 0; i < VisionBudget.MAX_USER_IMAGES; i++) {
            assertTrue(s.admit(worstCaseImage),
                    "第 " + (i + 1) + " 张满档图被默认 token 上限挡下（张数配额名存实亡）");
        }
        // 仍必须有界：10 张之后继续加图要拒得住（28k + 5.6k = 33.6k > 32k）
        assertFalse(s.admit(worstCaseImage * 2), "超过上限后应拒住，不能无界放行");
    }

    @Test
    void tokenCapStopsAdmittingFurtherImages() {
        VisionBudget b = new VisionBudget();
        VisionBudget.Session s = b.open("turn-1");
        long cap = VisionBudget.MAX_REQUEST_TOKENS;
        assertTrue(s.admit(cap - 1_000), "接近上限仍应放行");
        // 超出上限的那张被拒，但不应把额度扣掉——否则一张大图会连带废掉后面所有小图
        assertFalse(s.admit(2_000), "越过上限应被拒");
        assertTrue(s.admit(500), "被拒后剩余额度仍可用");
    }

    @Test
    void turnBudgetIsExhaustedAfterTwelveDeliveries() {
        VisionBudget b = new VisionBudget();
        for (int i = 0; i < 12; i++) {
            assertTrue(b.open("turn-1").tryConsumeTurnSlot(), "第 " + (i + 1) + " 次");
        }
        assertFalse(b.open("turn-1").tryConsumeTurnSlot());
    }

    /** 不同回合互不影响——并发子 agent 共用同一个装饰器实例，不隔离会互相冲掉计数。 */
    @Test
    void turnsAreIsolatedFromEachOther() {
        VisionBudget b = new VisionBudget();
        for (int i = 0; i < 12; i++) b.open("turn-1").tryConsumeTurnSlot();
        assertFalse(b.open("turn-1").tryConsumeTurnSlot());
        assertTrue(b.open("turn-2").tryConsumeTurnSlot());
    }

    /** 额度可由环境变量覆盖：截图循环密集的用户需要放宽，不该只能改代码。 */
    @Test
    void turnBudgetIsOverridableByEnvValue() {
        assertEquals(40, VisionBudget.resolveBudget("40", VisionBudget.MAX_TOOL_TURN_DELIVERIES));
        assertEquals(40, VisionBudget.resolveBudget("  40  ", VisionBudget.MAX_TOOL_TURN_DELIVERIES));
        // 未设 / 空 / 非法 / 负数一律回落默认，绝不让一个手滑的环境变量把 TUI 带崩
        assertEquals(VisionBudget.MAX_TOOL_TURN_DELIVERIES, VisionBudget.resolveBudget(null, VisionBudget.MAX_TOOL_TURN_DELIVERIES));
        assertEquals(VisionBudget.MAX_TOOL_TURN_DELIVERIES, VisionBudget.resolveBudget("", VisionBudget.MAX_TOOL_TURN_DELIVERIES));
        assertEquals(VisionBudget.MAX_TOOL_TURN_DELIVERIES, VisionBudget.resolveBudget("abc", VisionBudget.MAX_TOOL_TURN_DELIVERIES));
        assertEquals(VisionBudget.MAX_TOOL_TURN_DELIVERIES, VisionBudget.resolveBudget("-5", VisionBudget.MAX_TOOL_TURN_DELIVERIES));
    }

    /** 覆盖值真的作用到会话：传 2 时第 3 次就没了。 */
    @Test
    void overriddenBudgetGovernsSession() {
        VisionBudget b = new VisionBudget(2);
        assertEquals(2, b.toolTurnDeliveries());
        assertTrue(b.open("t").tryConsumeTurnSlot());
        assertTrue(b.open("t").tryConsumeTurnSlot());
        assertFalse(b.open("t").tryConsumeTurnSlot());
    }

    /**
     * 工具执行期据此判断「现在 Read 也拿不回图」：取最近一次 open 的回合——工具调用总紧跟一次模型请求。
     * 未跟踪过的回合恒 false（冷启动不该平白改掉 Read 的表示）。
     */
    @Test
    void currentTurnExhaustedTracksMostRecentOpenedTurn() {
        VisionBudget b = new VisionBudget(1);
        assertFalse(b.currentTurnExhausted(), "还没开过回合 → 不该判定耗尽");
        b.open("t1").tryConsumeTurnSlot();
        assertTrue(b.currentTurnExhausted(), "t1 额度已尽");
        b.open("t2");
        assertFalse(b.currentTurnExhausted(), "换到 t2（额度未动）后不该沿用 t1 的结论");
    }

    /** 计数表必须有界，否则长会话里它自己会变成泄漏。 */
    @Test
    void counterTableIsBounded() {
        VisionBudget b = new VisionBudget();
        for (int i = 0; i < 50; i++) b.open("turn-" + i).tryConsumeTurnSlot();
        assertTrue(b.trackedTurns() <= VisionBudget.MAX_TRACKED_TURNS,
                "跟踪的回合数 " + b.trackedTurns() + " 超过上限 " + VisionBudget.MAX_TRACKED_TURNS);
    }
}
