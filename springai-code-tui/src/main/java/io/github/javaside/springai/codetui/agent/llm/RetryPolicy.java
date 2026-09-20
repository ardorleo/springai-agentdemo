package io.github.javaside.springai.codetui.agent.llm;

import com.openai.errors.OpenAIServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;

/**
 * LLM 重试策略的<b>唯一真相源</b>：瞬态判据（{@link #shouldRetry}）、指数退避（{@link #backoffMsAfter}）
 * 与失败文案的根因推导（{@link #firstNonBlankMessage}）的全部逻辑都在这里。{@link RetryingChatModel}
 * 与 RetryingStreamChatModel <b>共用</b>本类，各自只保留同名静态方法做纯委托——判据改动只允许发生在此处。
 *
 * <p><b>红线（4xx/中断/取消）不动</b>：401/403（欠费、密钥错——重试只会更慢更花钱）、其余 4xx
 * （请求本身有病）、中断/取消（Esc 回合取消的伴生，绝不重试）一律否决。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class RetryPolicy {

    /** 首次重试前的退避毫秒数；之后指数翻倍（1s、2s、4s、8s、16s、30s），封顶见 {@link #CAP_BACKOFF_MS}。 */
    static final long BACKOFF_MS = 1000;
    /** 单次退避封顶（30s，贴 Anthropic 官方 SDK 上限）：网关坏窗口以十秒计，给最后几跳留足等待余量。 */
    static final long CAP_BACKOFF_MS = 30_000;
    /**
     * Retry-After 头采信的封顶（60s）：429/529 时服务端偶尔回很大的值，无上限会让 TUI 干等几分钟像卡死。
     * 与 {@link #CAP_BACKOFF_MS} 分开——指数退避封 30s，服务端明示的等待可到 60s（它更懂何时恢复）。
     */
    static final long RETRY_AFTER_CAP_MS = 60_000;

    /**
     * 测试钩子：仅作用于 {@link #backoffRetry} 里 {@code Mono.delay} 的<b>实际</b>睡眠毫秒（默认恒等）。
     * 生产从不改它；单测经 {@link #setDelayScaleForTest}/{@link #resetDelayScaleForTest} 换成压缩函数
     * （如封顶 1ms），令耗尽用例秒过——上报给 UI 的 {@code backoffMs}（{@link #nextDelayMs} 现算值）
     * 不受影响，退避序列断言仍打真实值。volatile 保证跨线程可见（delay 求值在 reactor parallel 调度器）。
     */
    private static volatile java.util.function.LongUnaryOperator delayMsForTest = ms -> ms;

    /** 测试专用：安装实际睡眠的压缩函数（仅影响 {@link #backoffRetry} 的真实等待，不改上报 backoffMs）。 */
    public static void setDelayScaleForTest(java.util.function.LongUnaryOperator scale) {
        delayMsForTest = scale == null ? ms -> ms : scale;
    }

    /** 测试专用：恢复恒等睡眠（{@code @AfterEach} 必调，防钩子泄漏到其他测试）。 */
    public static void resetDelayScaleForTest() {
        delayMsForTest = ms -> ms;
    }

    /**
     * 把退避毫秒经测试钩子换算为<b>实际</b>睡眠毫秒（生产恒等）。{@link #backoffRetry} 的 reactive
     * {@code Mono.delay} 与 {@link RetryingChatModel} 的阻塞 {@code Thread.sleep} 两条退避路径<b>共用</b>
     * 本换算——单一钩子即可压缩两处真实等待（如 {@code setDelayScaleForTest(ms -> min(ms,1))}），
     * 而上报给 UI 的 {@code backoffMs} 仍是 {@link #nextDelayMs} 真值。
     */
    public static long scaledDelayMsForTest(long backoffMs) {
        return delayMsForTest.applyAsLong(backoffMs);
    }

    private RetryPolicy() {
    }

    /**
     * 计算第 attempt 次失败后的退避毫秒数：BACKOFF_MS × 2^(attempt-1)，封顶 CAP_BACKOFF_MS。纯函数。
     *
     * <p>{@code attempt} 为 <b>1 基尝试序号</b>（第 n 次尝试失败后的退避，{@code backoffMsAfter(1)=1000}）——
     * 与 RetryReporter 的 attempt（即将进行的尝试 2..7）差 1，勿混。序列 1s·2s·4s·8s·16s·30s。
     */
    public static long backoffMsAfter(int attempt) {
        long ms = BACKOFF_MS;
        for (int i = 1; i < attempt && ms < CAP_BACKOFF_MS; i++) {
            ms *= 2;
        }
        return Math.min(ms, CAP_BACKOFF_MS);
    }

    /**
     * 第 attempt 次失败后的<b>实际</b>退避（纯函数，唯一真相源）：{@code max(指数退避, Retry-After 头值)} 再按
     * {@link #RETRY_AFTER_CAP_MS} 封顶。正常瞬态走指数退避；429/529 服务端在 {@code Retry-After} 里明示等待时
     * 听服务端的（它更懂何时恢复），但仍受封顶约束防畸形大值。
     *
     * <p>{@code attempt} 同 {@link #backoffMsAfter} 为 1 基尝试序号。{@code failure} 为该次失败异常（用于抽头）。
     * 无 Retry-After 头时退化为 {@link #backoffMsAfter}（不变式：无头 ⇒ 严格等于纯指数）。
     */
    public static long nextDelayMs(int attempt, Throwable failure) {
        long exp = backoffMsAfter(attempt);
        long retryAfter = retryAfterMs(failure);
        if (retryAfter < 0) {
            return exp;                       // 无头/畸形：纯指数
        }
        return Math.min(Math.max(exp, retryAfter), RETRY_AFTER_CAP_MS);
    }

    /**
     * 从异常链抽取 {@code Retry-After} 头并归一为毫秒（纯函数，fail-open）：无头/畸形/负值一律返回 {@code -1}
     * （调用方据此退化为纯指数退避）。两条异常链都覆盖——{@link WebClientResponseException}（Spring 侧 429/5xx）
     * 与 {@link OpenAIServiceException}（openai-java SDK 系 429/5xx）；两种头格式都认——delta-seconds（如 {@code "3"}）
     * 与 HTTP-date（如 {@code "Wed, 21 Oct 2026 07:28:00 GMT"}）。
     */
    public static long retryAfterMs(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String raw = null;
            if (t instanceof WebClientResponseException wcre) {
                raw = wcre.getHeaders().getFirst("Retry-After");
            } else if (t instanceof OpenAIServiceException svc) {
                List<String> vals = svc.headers().values("Retry-After");
                if (!vals.isEmpty()) {
                    raw = vals.get(0);
                }
            }
            long ms = parseRetryAfter(raw);
            if (ms >= 0) {
                return ms;
            }
        }
        return -1;
    }

    /** 解析单个 Retry-After 头值为毫秒：delta-seconds 优先，其次 HTTP-date（相对 now）；空/畸形/负返回 {@code -1}。 */
    private static long parseRetryAfter(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        String v = raw.trim();
        try {
            long seconds = Long.parseLong(v);           // delta-seconds
            return seconds >= 0 ? seconds * 1000L : -1;
        } catch (NumberFormatException ignore) {
            // 非纯数字：试 HTTP-date（RFC 1123）
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            long ms = Duration.between(ZonedDateTime.now(when.getZone()), when).toMillis();
            return ms >= 0 ? ms : -1;                   // 过去时刻当无效
        } catch (Exception ignore) {
            return -1;                                  // fail-open
        }
    }

    /**
     * 构造 L1/L2 共用的 reactive {@link Retry} 策略（唯一真相源，替代分散的 {@code Retry.backoff}）：延迟按
     * {@link #nextDelayMs} 现算（含 Retry-After），jitter 显式关闭（单用户 TUI 无并发，关闭换退避可预测 + 单测好写）。
     *
     * @param maxRetries 最大重试次数（不含首次尝试）
     * @param filter     该次失败是否参与重试（各层白名单：L1 = {@code emitted==0 && shouldRetry}；L2 = SII）
     * @param onRetry    退避 delay 前同步回调（UI ↻ 行；入参 attempt 为即将进行的尝试序号 2..、backoffMs、failure）
     */
    public static Retry backoffRetry(long maxRetries, Predicate<Throwable> filter, RetryHook onRetry) {
        return Retry.from(companion -> companion.concatMap(sig -> {
            Throwable failure = sig.failure();
            if (sig.totalRetries() >= maxRetries || !filter.test(failure)) {
                return Mono.error(failure);              // 耗尽/不匹配：终态（与 Retry.backoff 语义一致）
            }
            long totalRetries = sig.totalRetries();      // 0 基已完成重试数（本次是第 totalRetries+1 次重试）
            long backoffMs = nextDelayMs((int) totalRetries + 1, failure);
            if (onRetry != null) {
                onRetry.accept(totalRetries, backoffMs, failure);   // 上报真实退避（UI ↻ 行）
            }
            // 实际睡眠经测试钩子（生产恒等）：耗尽用例可压缩真实等待，而上报值仍是 nextDelayMs 真值。
            long sleepMs = delayMsForTest.applyAsLong(backoffMs);
            return Mono.delay(Duration.ofMillis(sleepMs)).thenReturn(totalRetries);
        }));
    }

    /**
     * {@link #backoffRetry} 的退避前回调（退避 delay 前同步执行）。{@code totalRetries} 为 <b>0 基</b>已完成重试数：
     * 本次即将进行第 {@code totalRetries+1} 次重试。各层显示口径不同——L1/子 agent 报 {@code totalRetries+2}
     * （即将进行的尝试序号，首重试=2），L2 报 {@code totalRetries+1}（续跑序号 1..）——故只传原始 totalRetries，
     * 由调用方各自格式化。
     */
    @FunctionalInterface
    public interface RetryHook {
        void accept(long totalRetries, long backoffMs, Throwable failure);
    }

    /**
     * 是否值得重试：cause 链逐层判断，取消/中断优先短路（Esc 回合取消的伴生，绝不重试）。
     *
     * <p>瞬态判据（2026-08-17 生产日志实测的四类故障 + 流式场景，spec：
     * {@code docs/superpowers/specs/2026-08-18-subagent-retry-transient-expansion-design.md}）：
     * <ul>
     *   <li>「2xx + 坏 body」解析失败：*InvalidDataException 类名后缀（openai/anthropic 同名后缀，
     *       按类名匹配保持 provider 中立）或 Jackson 的 "No content to map"；
     *   <li>网络断连：{@link IOException} 家族（EOF/SocketTimeout/Connect 均子类），或类名以
     *       IoException 结尾（openai-java 的 OpenAIIoException，同法不引新依赖）；
     *   <li>流中途断开：message 含 "EOF reached while reading"（WebClientResponseException 把
     *       EOFException 摊平进顶层 message、cause 链上只剩自身的场景）；
     *   <li>限流：message 含 "rate limit"（大小写不敏感；覆盖 200-wrapped 的 SseException 与 429 文案）
     *       或 {@link WebClientResponseException} 状态 429——429 虽是 4xx，但它是唯一的
     *       「请求没病、服务端在节流」4xx（Retry-After 语义），spec §5 L1 行「零下发 429 →
     *       重试成功」点名（Task 2 补，见该任务报告的偏差记录）；
     *   <li>网关 5xx：cause 链上的 WebClientResponseException 且 is5xxServerError，<b>或</b>
     *       openai-java SDK 系的 {@link OpenAIServiceException} 且 statusCode≥500（智谱/Qwen 等
     *       SDK 栈 provider 的 5xx face；2026-09-06 生产事故补——旧判据对 InternalServerException
     *       全落空，网关 503 直接杀回合）；
     *   <li>流式专属：{@link StreamIdleTimeoutException}（空闲超时）与 {@link EmptyStreamException}
     *       （空流）——网关坏窗口在流式路径上的两副面孔。
     * </ul>
     *
     * <p><b>红线不重试</b>：401/403（欠费、密钥错——重试只会更慢更花钱）、其余 4xx（除 429 限流；
     * 请求本身有病）、中断/取消——含 {@link StreamInterruptedException}（L1 的 mid-stream 出口
     * 包装类型：它<b>本身携带</b>「已下发 chunk」语义，重试等于向下游重放已见内容；且它必须
     * 原样穿透 L1 的 retryWhen 才能命中 L2 白名单，spec §3.2 类型穿透要求）。把 IOException
     * 全家族视为瞬态有理论误伤面（证书错误等），但误伤代价只是几次快速失败，漏掉代价是
     * 整个子 agent 报废重跑。纯函数，便于单测。
     */
    public static boolean shouldRetry(Throwable ex) {
        boolean transientFailure = false;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof CancellationException
                    || t instanceof StreamInterruptedException) {
                return false;
            }
            String cls = t.getClass().getSimpleName();
            if (t instanceof StreamIdleTimeoutException || t instanceof EmptyStreamException
                    || cls.endsWith("InvalidDataException") || cls.endsWith("IoException")
                    || t instanceof IOException) {
                transientFailure = true;
            }
            if (t instanceof WebClientResponseException wcre) {
                if (wcre.getStatusCode().is5xxServerError()
                        || wcre.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    transientFailure = true;
                } else if (wcre.getStatusCode().is4xxClientError()) {
                    // 其余 4xx（401/403/400…）是确定态：重试无意义且欠费场景下更花钱。
                    // 2xx 不在此列——「200 OK 但 body 坏」正是网关坏窗口的形态，交给 EOF/解析特征判定。
                    return false;
                }
            }
            // SDK 系（openai-java：智谱/Qwen/OpenAI/opencode-go 全走它，OkHttp 栈）的 HTTP 状态
            // 异常——与 WCRE 分支镜像（2026-09-06 生产事故补：智谱网关 503 抛
            // InternalServerException，非 IOException、非 WCRE、message 无关键词，旧判据全落空，
            // 60 次直接杀回合）。SDK 把 500..599 全区间映射 InternalServerException（源码核对），
            // 4xx 各有具名子类（400/401/403/404/422）或 UnexpectedStatusCodeException。
            // OpenAIRetryableException 不在此列：SDK 内部 RetryingHttpClient 已带 2 次重试，不越权。
            if (t instanceof OpenAIServiceException svc) {
                int code = svc.statusCode();
                if (code >= 500 || code == 429) {
                    transientFailure = true;          // 网关 5xx / 限流：与 WCRE 同语义
                } else if (code >= 400) {
                    return false;                     // 其余 4xx：红线，同 WCRE 口径
                }                                    // 3xx 等：落空（既非瞬态也非红线，最终否决）
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("no content to map") || lower.contains("eof reached while reading")
                        || lower.contains("rate limit")) {
                    transientFailure = true;
                }
            }
        }
        return transientFailure;
    }

    /**
     * 失败文案的根因推导（唯一真相源，防多处复制漂移）：沿 cause 链取首个非空 message
     * （与 UI {@code formatError} 口径一致），全链无（含 {@code ex == null}）则返回 {@code fallback}
     * （调用方一般传 {@code ex.getClass().getSimpleName()} 类名兜底）。纯函数。
     *
     * <p><b>消费方</b>：{@link RetryingStreamChatModel#reasonOf}（在此基础上做显示宽截 60）与
     * CodingAgent 的 rootCauseText（不截断）都委托这里。
     */
    public static String firstNonBlankMessage(Throwable ex, String fallback) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                return t.getMessage();
            }
        }
        return fallback;
    }
}
