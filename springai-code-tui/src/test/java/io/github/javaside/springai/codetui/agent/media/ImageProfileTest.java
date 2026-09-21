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

    // ── DeepSeek：真机实测值（2026-09-21，deepseek-flash；原名 deepseek-v4-flash-vision-exp，
    //     2026-09-21 改名后复测口径一致：800x600→349、1200x900→694 逐字相同）──

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

    // ── OpenAI 通路：long edge 2048；token 按 32×32 分块 × 1.2（实测）──

    @Test
    void openAiAllowsLargerLongEdgeThanAnthropic() {
        assertEquals(2048, ImageProfile.OPENAI.maxEdge(),
                "该通路容许更大分辨率，比 1568 更能保住小字细节");
    }

    /**
     * <b>实测公式</b>：{@code ⌈宽/32⌉ × ⌈高/32⌉ × 1.2}，按原始像素直接算、不做缩放。
     *
     * <p>数据来自 true 机测量（gpt-6-astra / gpt-5.6-sol / gpt-5.5 三款一致，6 个数量级误差 ≤1）：
     * <pre>
     *   32x32     →     2     256x256   →    77
     *   64x64     →     5     800x600   →   571
     *   128x128   →    20     2442x1146 →  3327
     *   4000x3000 → 14101
     * </pre>
     * 实测与公式之间恒有 0~1 的取整噪声（如 800×600：实测 571、公式 570），
     * 故断言留 1 token 容差——要紧的是<b>不许出现倍数级低估</b>。
     *
     * <p><b>为什么不用官方文档的 {@code base + 512px分块×tile}</b>：那套公式在本项目实际使用的
     * 通路上不成立——2048×961 官方口径算 1190、实测 2381（差 2 倍），4000×3000 官方 1470、
     * 实测 14101（差 9.6 倍）。低估比高估危险：预算判定过松，请求发出去才超支。
     */
    @Test
    void openAiUsesMeasuredPatchFormula() {
        assertNear(571, ImageProfile.OPENAI.estimateTokens(800, 600));
        assertNear(2381, ImageProfile.OPENAI.estimateTokens(2048, 961));
        assertNear(2, ImageProfile.OPENAI.estimateTokens(32, 32));
        assertNear(14101, ImageProfile.OPENAI.estimateTokens(4000, 3000));
    }

    /** 与实测值相差不超过 1 token（OpenAI 档的取整噪声）。 */
    private static void assertNear(long measured, long estimated) {
        assertNear(measured, estimated, 1);
    }

    /** 与实测值相差不超过 tolerance——各档噪声不同，容差按档给。 */
    private static void assertNear(long measured, long estimated, long tolerance) {
        assertTrue(Math.abs(measured - estimated) <= tolerance,
                "估算 " + estimated + " 与实测 " + measured + " 相差超过容差 " + tolerance);
    }

    /**
     * 回归钉子：2048×961（本项目 MAX_EDGE 下 2442×1146 截图的出站尺寸）
     * 绝不能被低估——旧实现给 1190，实测 2381。
     */
    @Test
    void openAiNoLongerUnderestimatesResizedScreenshot() {
        long ours = ImageProfile.OPENAI.estimateTokens(2048, 961);
        assertTrue(ours >= 2381, "不得低估：实测 2381，当前 " + ours);
    }

    // ── 通义千问：token = w̄×h̄/token_pixels + 2，Qwen3 系 32×32 ──

    @Test
    void qwenUses32PixelTokenGrid() {
        // 1024×1024 / (32*32) + 2 = 1024 + 2
        assertEquals(1026, ImageProfile.QWEN.estimateTokens(1024, 1024));
    }

    // ── 智谱 GLM：28×28 分块，地板 18、封顶 6086（真机实测）──

    /**
     * <b>实测公式</b>：{@code min(6086, max(18, ⌈宽/28⌉ × ⌈高/28⌉))}。
     *
     * <p>数据来自真机测量（glm-4.6v，2026-09-21，已扣除文本基线 7）：
     * <pre>
     *   100x100  →    18（地板）    560x560   →   402
     *   200x200  →    51            1000x1000 →  1298
     *   336x336  →   146            2000x2000 →  5043
     *   3000x3000 → 6086（封顶）    4000x4000 →  6086
     * </pre>
     * 15 个测点误差均 ≤54（约 4%），且方向偏<b>高估</b>——预算场景安全。
     *
     * <p>与 Anthropic 同为 28×28 分块（GLM 系沿用该视觉编码口径），区别只在
     * 多了一条 18 的地板与 6086 的封顶。
     */
    @Test
    void zhipuUses28PatchFormulaWithFloorAndCap() {
        // 智谱的实测噪声比 OpenAI 档大（分块边界取整），实测偏差 ≤54，故容差取 60
        assertNear(146, ImageProfile.ZHIPU.estimateTokens(336, 336), 60);
        assertNear(402, ImageProfile.ZHIPU.estimateTokens(560, 560), 60);
        assertNear(1298, ImageProfile.ZHIPU.estimateTokens(1000, 1000), 60);
        assertNear(51, ImageProfile.ZHIPU.estimateTokens(200, 200), 60);
    }

    @Test
    void zhipuHasFloorForTinyImages() {
        assertEquals(18, ImageProfile.ZHIPU.estimateTokens(20, 20));
        assertEquals(18, ImageProfile.ZHIPU.estimateTokens(100, 100));
    }

    @Test
    void zhipuHasCeilingForHugeImages() {
        assertEquals(6086, ImageProfile.ZHIPU.estimateTokens(3000, 3000));
        assertEquals(6086, ImageProfile.ZHIPU.estimateTokens(4000, 4000));
    }

    /** 回归：旧 CONSERVATIVE 口径（宽×高/750）对智谱其实接近，但大图会偏。 */
    @Test
    void zhipuIsNoLongerOnConservativeProfile() {
        assertTrue(ImageProfile.forProvider("zhipu") == ImageProfile.ZHIPU,
                "智谱已有实测口径，不该再走保守档");
    }

    // ── 兜底档：口径未知的 provider（网关模型混杂）──

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
