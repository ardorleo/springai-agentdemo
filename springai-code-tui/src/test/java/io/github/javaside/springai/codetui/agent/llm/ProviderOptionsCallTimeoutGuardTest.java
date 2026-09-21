package io.github.javaside.springai.codetui.agent.llm;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「每请求总时长超时」守卫：OpenAI 系四家（zhipu/qwen/openai/opencode-go）的 options 必须显式
 * {@code timeout=ZERO}（禁用 callTimeout）。
 *
 * <p><b>为什么必须显式 ZERO</b>：spring-ai 2.0.1 的 {@code OpenAiChatOptions.builder()} 默认
 * {@code timeout=PT1M} 且 {@code timeout(null)} 被静默忽略——不显式设置就每请求携带 60 秒。
 * {@code OpenAiChatModel.buildRequestOptions} 见非 null 即写入 {@code RequestOptions.timeout}，
 * openai-java SDK 在 <b>每次</b> {@code newCall} 用它重建 OkHttp 超时：{@code callTimeout=60s}
 * （同时把 base client 上经 {@link OpenAiTimeouts} 配好的 read/connect/write 四元组顶成 SDK 默认）。
 * 任何总时长超 60s 的流式回合（大上下文预填充 + thinking + 长输出）被 watchdog cancel →
 * {@code OpenAIIoException: Stream failed}（{@code StreamResetException: CANCEL}），且重试同限必挂
 * ——2026-09-21 生产事故（interview-drill 全量断流）的根因。
 *
 * <p><b>ZERO 的语义</b>：spring-ai 把该 Duration 映射为 {@code Timeout.request}，
 * {@code request=0} 在 OkHttp 即 {@code callTimeout(0)}=禁用总时长（流式不被墙钟砍断，
 * 与 {@link OpenAiTimeouts} 的 {@code request=ZERO} 同一策略）；空闲兜底由 code-tui 自己的
 * {@code StreamIdleTimeoutChatModel}（默认 300s）负责。
 *
 * <p>DeepSeek / Anthropic 不在此列：{@code DeepSeekChatOptions} 无 timeout 字段、
 * {@code AnthropicChatOptions} 默认 null（2026-09-21 实测），不触发该覆盖路径。
 */
class ProviderOptionsCallTimeoutGuardTest {

    /** 每家跑两个重载（options(model) / options(model, thinking)）+ chatModel 的 defaultOptions。 */
    private void assertZeroTimeout(String who, LlmProvider provider, String modelId) {
        assertEquals(Duration.ZERO, ((OpenAiChatOptions) provider.options(modelId)).getTimeout(),
                who + ".options(modelId) 不得携带默认 60s 总时长超时（spring-ai PT1M → callTimeout 60s）");
        assertEquals(Duration.ZERO, ((OpenAiChatOptions) provider.options(modelId, ThinkingConfig.defaults())).getTimeout(),
                who + ".options(modelId, thinking) 不得携带默认 60s 总时长超时");
        ChatModel chatModel = provider.chatModel();
        assertEquals(Duration.ZERO, ((OpenAiChatOptions) chatModel.getDefaultOptions()).getTimeout(),
                who + ".chatModel() 默认 options 不得携带默认 60s 总时长超时");
    }

    @Test
    void openAiFamilyProvidersDisablePerRequestTotalTimeout() {
        // apiKey 非空即可构造；不发真实请求（chatModel() 仅惰性 build client）
        List.of(
                        new Object[]{"zhipu", new ZhipuProvider("k"), "glm-5.3"},
                        new Object[]{"qwen", new QwenProvider("k"), "qwen3.7-max"},
                        new Object[]{"openai", new OpenAiProvider("k"), "gpt-5.6-sol"},
                        new Object[]{"opencode-go", new OpencodeGoProvider("k"), "deepseek-v4-pro"})
                .forEach(row -> assertZeroTimeout((String) row[0], (LlmProvider) row[1], (String) row[2]));
    }
}
