package io.github.javaside.springai.codetui.agent.llm;

import io.github.javaside.springai.codetui.agent.media.VisionModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekProviderVisionTest {

    // ---- VisionModels 名单：flash 系支持视觉，pro 不支持 ----

    @Test
    void currentFlash_isSupported() {
        assertTrue(VisionModels.supportsImage("deepseek-flash"),
                "deepseek-flash 是现役视觉模型（官方模型表「图像理解 = 支持」）");
    }

    /**
     * 两个旧名也<b>确实接受图片</b>：2026-09-21 官方把 flash 与 flash-vision 合并，
     * 旧名请求由同一后端服务。真机实测三名字带图返回同一 token 数（349），故都放行。
     */
    @Test
    void legacyFlashNames_areAlsoSupported() {
        assertTrue(VisionModels.supportsImage("deepseek-v4-flash"),
                "旧名 deepseek-v4-flash 现在也路由到同一视觉模型");
        assertTrue(VisionModels.supportsImage("deepseek-v4-flash-vision-exp"),
                "旧视觉名仍被接受，不该被拦");
    }

    @Test
    void pro_isNotSupported() {
        assertFalse(VisionModels.supportsImage("deepseek-v4-pro"), "pro 不得误判为视觉");
        assertFalse(VisionModels.supportsImage("deepseek-v4"), "前缀必须精确：不在名单则不支持");
    }

    @Test
    void globalKillSwitch_stillApplies() {
        assertFalse(VisionModels.enabledFor("off"), "CODETUI_VISION=off 时全关");
        assertTrue(VisionModels.enabledFor(null), "未配置默认开启");
    }

    // ---- 内置清单：视觉模型可选项存在，默认模型不变 ----

    /** 清单已收敛为 2 项：pro（文本/强推理）+ flash（快/便宜/支持图片）。 */
    @Test
    void builtinModels_includeFlash_butDefaultStaysPro() {
        DeepSeekProvider provider = new DeepSeekProvider("sk-test");
        List<ModelOption> models = provider.models();
        assertEquals("deepseek-v4-pro", provider.defaultModel(), "默认模型必须仍是 deepseek-v4-pro");
        assertEquals(2, models.size(), "改名后 flash 与 flash-vision 合并，清单应只剩 2 项");
        assertTrue(models.stream().anyMatch(m -> m.id().equals("deepseek-flash")),
                "内置清单应含现役 deepseek-flash");
    }

    @Test
    void capabilities_followModelId() {
        DeepSeekProvider provider = new DeepSeekProvider("sk-test");
        assertTrue(provider.capabilities("deepseek-flash").supportsImageInput());
        assertFalse(provider.capabilities("deepseek-v4-pro").supportsImageInput());
    }

    // ---- 传输开关纯函数（Task 5 复用）----

    @Test
    void transport_parsing() {
        assertEquals(DeepSeekProvider.VisionTransport.FILES,
                DeepSeekProvider.visionTransportFor("files"));
        assertEquals(DeepSeekProvider.VisionTransport.INLINE,
                DeepSeekProvider.visionTransportFor("inline"));
        assertEquals(DeepSeekProvider.VisionTransport.INLINE,
                DeepSeekProvider.visionTransportFor(null));
        assertEquals(DeepSeekProvider.VisionTransport.INLINE,
                DeepSeekProvider.visionTransportFor("  FILES  "));
        assertEquals(DeepSeekProvider.VisionTransport.INLINE,
                DeepSeekProvider.visionTransportFor("garbage"));
    }
}
