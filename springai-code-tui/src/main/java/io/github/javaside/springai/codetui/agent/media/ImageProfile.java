package io.github.javaside.springai.codetui.agent.media;

/**
 * 一家 provider 的图像投递规格：<b>出站长边上限</b> + <b>视觉 token 估算口径</b>。
 *
 * <p><b>为什么要分家</b>：原实现用 Anthropic 的 {@code 宽×高/750} 估算<b>所有</b> provider。
 * 对 DeepSeek 实测高估 3.6 倍（同一张 2442×1146 截图：旧公式 3731、真机约 1000），于是
 * {@code MAX_REQUEST_TOKENS} 实际只放得下 3 张图而本可放 6 张。高估看似安全，实际是
 * <b>白白限制功能</b>——用户会感到「额度太少」，而这与真实成本无关。
 *
 * <p>长边上限同理：各家官方能接受的分辨率差别很大（DeepSeek 约 1300 自行缩放、Anthropic
 * 标准档 1568、OpenAI high detail 2048、通义 8K）。统一按 1568 缩放，对前者是白缩、
 * 对后两者是净丢细节（截图小字会糊）。
 *
 * <p><b>数据可信度分级</b>（改数字前先看这里）：
 * <ul>
 *   <li>{@link #DEEPSEEK} —— 本机真机实测（2026-09-21，deepseek-v4-flash-vision-exp）；</li>
 *   <li>{@link #ANTHROPIC} / {@link #OPENAI} / {@link #QWEN} —— 官方文档公式，<b>未实测</b>；</li>
 *   <li>{@link #CONSERVATIVE} —— 口径未知者的兜底（智谱官方未公布图像 token 规则、
 *       opencode-go 等网关后面挂的模型混杂），沿用旧公式并保持 1568。</li>
 * </ul>
 *
 * <p><b>不是安全边界</b>：本类只用于「估算成本、决定发几张」，真正的硬约束在
 * {@link ImagePreparer#MAX_BYTES}（字节）与 {@link ImagePreparer#MAX_PIXELS}（防解码 OOM）。
 * 估算口径变化不应影响图片能否发出的判定。
 */
public enum ImageProfile {

    /**
     * DeepSeek 视觉（真机实测）。
     *
     * <p>实测数据（同宽高比，仅改分辨率）：
     * <pre>
     *   100x80    → 226 token（极小图被放大到下限）
     *   400x300   → 226
     *   800x600   → 349
     *   1200x900  → 694
     *   1600x1200 → 1033
     *   2442x1146 → 1000（封顶量级）
     * </pre>
     * 即：小于约 544×544 放大到下限，超过约 1300×1300 后不再涨，单图封顶约 1024。
     * 长边取 1300——<b>与官方缩放目标一致，多发像素不会换来更多 token 上涨空间</b>，
     * 但保留到 1300 比 1568 更贴近 provider 实际处理尺寸（减少一次有损重编码）。
     */
    DEEPSEEK(1300) {
        private static final long FLOOR_TOKENS = 226L;
        private static final long CEIL_TOKENS = 1024L;
        /** 实测拟合：token 与「缩放后像素数」近似成正比，比例约 1/1000（800×600=480k → 349+226）。 */
        private static final long PIXELS_PER_TOKEN = 2_400L;

        @Override
        public long estimateTokens(int width, int height) {
            long pixels = (long) width * height;
            // 极小图：provider 会放大到下限，成本恒为下限
            if (width < 544 && height < 544) return FLOOR_TOKENS;
            long byArea = FLOOR_TOKENS + pixels / PIXELS_PER_TOKEN;
            return Math.min(CEIL_TOKENS, byArea);
        }
    },

    /**
     * Anthropic Claude：官方公式 {@code ⌈w/28⌉ × ⌈h/28⌉}，标准档长边 1568、单图上限 1568 token。
     * （高分辨率档 4.7+ 为 2576 / 4784，本项目未接入该档，故不启用。）
     */
    ANTHROPIC(1568) {
        private static final long CAP = 1568L;

        @Override
        public long estimateTokens(int width, int height) {
            long patches = (long) ceilDiv(width, 28) * ceilDiv(height, 28);
            return Math.min(CAP, patches);
        }
    },

    /**
     * OpenAI：{@code detail:high} 口径——缩到 2048 见方内，短边超 768 则缩到 768，
     * 再按 512px 分块计费；{@code 总 = base + 分块数 × tile}。gpt-5 系 base 70 / tile 140。
     */
    OPENAI(2048) {
        private static final long BASE = 70L;
        private static final long TILE = 140L;

        @Override
        public long estimateTokens(int width, int height) {
            int longest = Math.max(width, height);
            if (longest > 2048) {
                double k = 2048.0 / longest;
                width = Math.max(1, (int) Math.round(width * k));
                height = Math.max(1, (int) Math.round(height * k));
            }
            int shortest = Math.min(width, height);
            if (shortest > 768) {
                double k = 768.0 / shortest;
                width = Math.max(1, (int) Math.floor(width * k));
                height = Math.max(1, (int) Math.floor(height * k));
            }
            long tiles = ceilDiv(width, 512) * ceilDiv(height, 512);
            return BASE + tiles * TILE;
        }
    },

    /**
     * 通义千问 VL：官方公式 {@code w̄×h̄ / token_pixels + 2}，Qwen3 系 token_pixels = 32×32。
     *
     * <p>长边取 2048 而非官方推荐的 8K：provider 会先把图缩到 {@code max_pixels}
     * （Qwen3 系默认 2,621,440）再计费，<b>发超过该像素数的部分不会带来任何额外信息</b>，
     * 只会白增上传体积。2048×1152 ≈ 2.36M 已接近该默认上限。
     */
    QWEN(2048) {
        private static final long TOKEN_PIXELS = 32L * 32L;
        /** Qwen3 系默认 max_pixels：超过即由 provider 等比缩小，故估算须先做同样缩放。 */
        private static final long DEFAULT_MAX_PIXELS = 2_621_440L;

        @Override
        public long estimateTokens(int width, int height) {
            long pixels = (long) width * height;
            if (pixels > DEFAULT_MAX_PIXELS) {
                double k = Math.sqrt((double) DEFAULT_MAX_PIXELS / pixels);
                pixels = (long) (width * k) * (long) (height * k);
            }
            return pixels / TOKEN_PIXELS + 2;
        }
    },

    /**
     * 口径未知的兜底（智谱官方未公布图像 token 规则；网关模型混杂、真实后端不确定）。
     *
     * <p>沿用旧公式 {@code 宽×高/750} 并保持 1568——这里是<b>真的没有依据</b>，
     * 宁可少发也不冒「上传后被拒/超限」的风险。与「对已知 provider 高估」性质不同：
     * 那是有依据却算错，这是没依据只能保守。
     */
    CONSERVATIVE(1568) {
        @Override
        public long estimateTokens(int width, int height) {
            return Math.max(1L, (long) width * height / 750L);
        }
    };

    private final int maxEdge;

    ImageProfile(int maxEdge) {
        this.maxEdge = maxEdge;
    }

    /** 出站长边上限：超过即等比缩小（{@link ImagePreparer#fit}）。 */
    public int maxEdge() {
        return maxEdge;
    }

    /**
     * 估算该尺寸图的视觉 token。<b>仅用于预算与统计，不是能否发出的判定。</b>
     *
     * @param width  缩放后的实际出站宽
     * @param height 缩放后的实际出站高
     * @return 估算 token（恒为正）
     */
    public long estimateTokens(int width, int height) {
        throw new UnsupportedOperationException("每个档位都必须覆写");
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    /**
     * 按 provider id 选档。<b>未知一律 {@link #CONSERVATIVE}</b>——与 {@code VisionModels}
     * 的「未知判不支持」同一取舍方向：宁可保守，也不要因猜错而多发。
     *
     * @param providerId provider 稳定 id（见 {@code LlmProvider#id()}）；可空
     */
    public static ImageProfile forProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) return CONSERVATIVE;
        return switch (providerId.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "deepseek" -> DEEPSEEK;
            case "anthropic" -> ANTHROPIC;
            case "openai" -> OPENAI;
            case "qwen" -> QWEN;
            default -> CONSERVATIVE;
        };
    }
}
