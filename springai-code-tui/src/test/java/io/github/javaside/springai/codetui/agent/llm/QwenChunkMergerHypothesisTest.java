package io.github.javaside.springai.codetui.agent.llm;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 千问（DashScope 兼容模式）流式 tool_calls 分片<b>按原样</b>合并（不做任何 HTTP 层归一化）。
 *
 * <p><b>背景</b>：千问的后续增量片带 {@code "id":""}（OpenAI 真身不带 id 字段）。Spring AI 2.0.0 的
 * {@code ChunkMerger} 以「分片带 id = 新工具调用」判定，把它误判成新调用、合并出无 name 残片并在
 * {@code Optional.get()} 处抛 {@code NoSuchElementException}；当时项目用 HTTP 层 SSE 归一化装饰器
 * （QwenSseNormalizingHttpClient，已随本次升级删除）删掉空串 id 绕过。
 * 2.0.1 改为<b>按 index 合并</b>（id/name 取首个非空、arguments 拼接），
 * 原样分片即可正确合并——故本用例钉住「上游行为」，一旦回退即失败。
 *
 * <p>分片 JSON 为 2026-07-16 对 qwen3.7-max 真实抓包原文（tool_calls 相关两片）。
 */
class QwenChunkMergerHypothesisTest {

    /** 真实抓包：首片带完整 id+name。 */
    private static final String CHUNK_FIRST = """
            {"model":"qwen3.7-max","id":"chatcmpl-fb43fd4a-43a6-9a4f-a82a-8f2860670a59","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_ead3ae98e739438eb40c8c31","type":"function","function":{"name":"get_weather","arguments":""}}],"content":"","reasoning_content":""},"index":0,"finish_reason":null,"logprobs":null}],"created":1784138795,"object":"chat.completion.chunk","usage":null}""";

    /** 真实抓包：后续增量片，id 是空字符串（2.0.0 崩溃的触发形状）。 */
    private static final String CHUNK_DELTA_EMPTY_ID = """
            {"model":"qwen3.7-max","id":"chatcmpl-fb43fd4a-43a6-9a4f-a82a-8f2860670a59","choices":[{"delta":{"tool_calls":[{"index":0,"id":"","type":"function","function":{"arguments":"{\\"city\\": \\"北京\\"}"}}],"content":"","reasoning_content":""},"index":0,"finish_reason":null,"logprobs":null}],"created":1784138795,"object":"chat.completion.chunk","usage":null}""";

    @Test
    void rawQwenChunks_mergeIntoOneCompleteToolCall() throws Exception {
        Object merged = mergeChunks(parse(CHUNK_FIRST), parse(CHUNK_DELTA_EMPTY_ID));

        ChatCompletion completion = (ChatCompletion) chunkToChatCompletion(merged);
        var toolCall = completion.choices().get(0).message().toolCalls().orElseThrow().get(0);
        // 4.49.0 起 tool call 是联合类型（function/custom），取 function 分支
        var functionCall = toolCall.function().orElseThrow();

        assertEquals("call_ead3ae98e739438eb40c8c31", functionCall.id(),
                "空串 id 不得覆盖首片真 id");
        assertEquals("get_weather", functionCall.function().name(),
                "残片会丢 name——这正是 2.0.0 崩溃的形状");
        assertEquals("{\"city\": \"北京\"}", functionCall.function().arguments(),
                "arguments 增量必须拼接完整");
    }

    private static ChatCompletionChunk parse(String json) throws Exception {
        return com.openai.core.ObjectMappers.jsonMapper().readValue(json, ChatCompletionChunk.class);
    }

    /** 反射进 Spring AI 私有 ChunkMerger（框架无公开入口，单测只能这么够到合并逻辑）。 */
    private static Object mergeChunks(ChatCompletionChunk... chunks) throws Exception {
        Method m = chunkMergerMethod("mergeChunks", List.class);
        return m.invoke(null, List.of(chunks));
    }

    private static Object chunkToChatCompletion(Object chunk) throws Exception {
        Method m = chunkMergerMethod("chunkToChatCompletion", ChatCompletionChunk.class);
        return m.invoke(null, chunk);
    }

    private static Method chunkMergerMethod(String name, Class<?> param) throws Exception {
        Class<?> merger = Class.forName("org.springframework.ai.openai.OpenAiChatModel$ChunkMerger");
        Method m = merger.getDeclaredMethod(name, param);
        m.setAccessible(true);
        return m;
    }
}
