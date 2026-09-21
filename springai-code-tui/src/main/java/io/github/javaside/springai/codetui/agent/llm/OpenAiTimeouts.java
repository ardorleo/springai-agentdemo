package io.github.javaside.springai.codetui.agent.llm;

import com.openai.core.Timeout;

import java.time.Duration;

/**
 * 构造 OpenAI-SDK 家族（OpenAI / 智谱，共用官方 openai-java SDK）的完整 {@link Timeout}。
 *
 * <p><b>为何必须是完整 Timeout、且设在 client 而非 http-client customizer</b>：spring-ai 的
 * {@code SpringAiOpenAiHttpClient.newCall()} <b>每次请求</b>都从 SDK {@code RequestOptions.getTimeout()}
 * （源自 {@code ClientOptions.timeout}）重建 OkHttp 的 connect/read/write/<b>callTimeout</b>——会覆盖掉
 * 任何设在「基础 OkHttpClient」上的超时。故超时必须设在 SDK {@code ClientOptions.timeout} 上
 * （经 {@code OpenAIOkHttpClient.builder().timeout(Timeout)}），才能真正作用于每一次请求。
 *
 * <p>{@code request=}{@link Duration#ZERO} → OkHttp {@code callTimeout(0)} = 禁用「整个调用总时长超时」
 * （流式响应不能被总时长砍断）。read=配置值（取代 SDK 默认 60s 过短之祸）、connect=固定 30s、write 复用 read。
 */
final class OpenAiTimeouts {

    private OpenAiTimeouts() {}

    /**
     * spring-ai {@code OpenAiChatOptions} 的<b>每请求</b> timeout 必须显式取此值（ZERO）。
     *
     * <p><b>为什么不能不设</b>：spring-ai 2.0.1 的 {@code OpenAiChatOptions.builder()} 默认
     * {@code timeout=PT1M} 且 {@code timeout((Duration) null)} 被静默忽略（无法清回 null）。
     * {@code OpenAiChatModel.buildRequestOptions} 见非 null 即写入 {@code RequestOptions.timeout}，
     * openai-java SDK 在<b>每次</b> {@code newCall} 用它重建 OkHttp 超时：{@code callTimeout=60s}，
     * 同时把 base client 上经 {@link #of} 配好的 read/connect/write 四元组顶成 SDK 默认——
     * 总时长超 60s 的流式回合（大上下文预填充 + thinking + 长输出）被 watchdog cancel →
     * {@code OpenAIIoException: Stream failed}，重试同限必挂（2026-09-21 生产事故根因）。
     *
     * <p><b>ZERO 的语义</b>：spring-ai 把该 Duration 映射为 {@code Timeout.request}，
     * {@code request=0} 在 OkHttp 即 {@code callTimeout(0)}=禁用总时长——与本类对 base client
     * 的 {@code request=ZERO} 同一策略（流式不被墙钟砍断）。空闲兜底由 code-tui 自己的
     * {@code StreamIdleTimeoutChatModel}（默认 300s）负责。
     */
    static final java.time.Duration CHAT_OPTIONS_TOTAL_TIMEOUT = java.time.Duration.ZERO;

    static Timeout of(LlmTimeouts timeouts) {
        return Timeout.builder()
                .connect(timeouts.connectTimeout())
                .read(timeouts.readTimeout())
                .write(timeouts.readTimeout())
                .request(Duration.ZERO)   // 禁用 callTimeout
                .build();
    }
}
