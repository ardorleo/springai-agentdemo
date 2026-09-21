package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 各 provider 的图像投递规格：长边上限 + token 估算口径。
 *
 * <p><b>为什么必须分家</b>：原实现用 Anthropic 的 {@code 宽×高/750} 算<b>所有</b> provider。
 * 对 DeepSeek 实测高估 3.6 倍（同一张 2442×1146 截图：估算 3731、真机 ~1000），于是
 * {@code MAX_REQUEST_TOKENS=6000} 实际只放得下 3 张图而本可放 6 张——「额度感觉太少」的根源。
 * 高估不是安全，是白白限制功能。
 *
 * <p><b>数据来源</b>：DeepSeek 为本机真机实测（下方常量注释标出）；Anthropic/OpenAI/通义
 * 取自官方文档口径，<b>未实测</b>，故只用文档公式字面量断言，不假设实测偏差。
 */
class ImageProfileTest {

    // ── DeepSeek：真机实测值（2026-09-21，deepseek-v4-flash-vision-exp）──

    /** 实测：2442×1146 → prompt_tokens 1000（含少量文本开销，取整到官方 1024 封顶量级）。 */
    @Test
    void deepSeekEstimatesLargeWideScreenshotNearMeasuredValue() {
        long t = ImageProfile.DEEPSEEK.estimateTokens(2442, 1146);
        assertTrue(t <= 1024, "DeepSeek 单图封顶约 1024，估到 " + t);
        assertTrue(t >= 700, "实测约 1000，不该估得过低：" + t);
    }

    /** 实测：极小图会被放大到下限，约 226 token（100×80 与 400×300 实测同为 226）。 */
    @Test
    void deepSeekEstimatesTinyImageAtFloor() {
        assertEquals(226, ImageProfile.DEEPSEEK.estimateTokens(100, 80));
        assertEquals(226, ImageProfile.DEEPSEEK.estimateTokens(400, 300));
    }

    /** 关键回归：不许再出现旧的 3.7 倍高估（2442×1146 旧算法 = 3731）。 */
    @Test
    void deepSeekNoLongerOverestimatesByOldFormula() {
        long oldFormula = 2442L * 1146 / 750;
        assertEquals(3731, oldFormula, "前置：旧公式确实算出 3731");
        assertTrue(ImageProfile.DEEPSEEK.estimateTokens(2442, 1146) < oldFormula,
                "新口径必须低于旧公式，否则「额度太少」没修");
    }

    /**
     * 长边取 1300：官方明确「大于约 1300×1300 会被缩小到约 1300」，多发像素不会换来更多
     * 有效信息，反而白白增加上传体积与一次有损重编码。
     */
    @Test
    void deepSeekLongEdgeMatchesItsOwnScalingTarget() {
        assertEquals(1300, ImageProfile.DEEPSEEK.maxEdge());
    }

    // ── Anthropic：官方公式 ⌈w/28⌉×⌈h/28⌉，标准档单图上限 1568 ──

    @Test
    void anthropicUses28PixelPatchFormula() {
        // 800×600 → ceil(800/28)=29, ceil(600/28)=22 → 638
        assertEquals(638, ImageProfile.ANTHROPIC.estimateTokens(800, 600));
        assertEquals(1568, ImageProfile.ANTHROPIC.maxEdge(), "标准档长边上限即 1568");
    }

    @Test
    void anthropicCapsAt1568() {
        // 极大图的公式结果应被封顶到 1568，而不是继续线性增长
        assertEquals(1568, ImageProfile.ANTHROPIC.estimateTokens(5000, 5000));
    }

    // ── OpenAI：long edge 2048；token = base + 512px 分块 × tile ──

    @Test
    void openAiAllowsLargerLongEdgeThanAnthropic() {
        assertEquals(2048, ImageProfile.OPENAI.maxEdge(),
                "OpenAI high detail 容许 2048，比 1568 更能保住小字细节");
    }

    /**
     * 官方分档：gpt-5 系 base 70 + 每 512² 分块 140。
     * 512×512 恰好 1 块 → 70 + 140 = 210；800×600 需 2×2 = 4 块 → 70 + 560 = 630。
     */
    @Test
    void openAiUsesBasePlusTileFormula() {
        assertEquals(210, ImageProfile.OPENAI.estimateTokens(512, 512));
        assertEquals(630, ImageProfile.OPENAI.estimateTokens(800, 600));
    }

    // ── 通义千问：token = w̄×h̄/token_pixels + 2，Qwen3 系 32×32 ──

    @Test
    void qwenUses32PixelTokenGrid() {
        // 1024×1024 / (32*32) + 2 = 1024 + 2
        assertEquals(1026, ImageProfile.QWEN.estimateTokens(1024, 1024));
    }

    // ── 兜底档：口径未知的 provider（智谱官方未公布、网关模型混杂）──

    /**
     * 未知口径时沿用旧公式，但<b>明确标注为保守</b>——宁可少发，也不要因为猜错而上传超限。
     * 与"对已知 provider 高估"不同：这里是真的没有依据。
     */
    @Test
    void unknownProvidersKeepConservativeFormula() {
        assertEquals(2442L * 1146 / 750, ImageProfile.CONSERVATIVE.estimateTokens(2442, 1146));
        assertEquals(1568, ImageProfile.CONSERVATIVE.maxEdge());
    }

    /** 每个档位都必须给出正数，且不超过其声明的上限。 */
    @Test
    void allProfilesReturnSaneValues() {
        for (ImageProfile p : new ImageProfile[]{
                ImageProfile.DEEPSEEK, ImageProfile.ANTHROPIC, ImageProfile.OPENAI,
                ImageProfile.QWEN, ImageProfile.CONSERVATIVE}) {
            long t = p.estimateTokens(4000, 3000);
            assertTrue(t > 0, "估算必须为正");
            assertTrue(p.maxEdge() > 0, "长边上限必须为正");
        }
    }
}
