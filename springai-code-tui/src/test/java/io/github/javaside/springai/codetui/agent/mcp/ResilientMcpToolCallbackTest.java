package io.github.javaside.springai.codetui.agent.mcp;

import io.modelcontextprotocol.spec.McpError;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResilientMcpToolCallback} 行为验证：MCP 工具抛出的协议错误
 * （如 {@link McpError}——server 返回 JSON-RPC error -32603）必须转成<b>信息完整</b>的错误文本
 * 作为 tool 结果返回，<b>绝不 rethrow</b>——否则异常穿透 Spring AI 的
 * {@code DefaultToolCallingManager}（它只把 {@code ToolExecutionException} 交给 exception processor，
 * {@code McpError} 不是该类型）和 {@code ResilientToolCallingManager}（只兜工具名解析失败），
 * 最终炸掉整个回合。
 *
 * <p><b>事故背景</b>（2026-09-21，session 20260921T051342）：Pencil 桌面 App 重启后未打开文档，
 * 所有 pencil 工具调用（read-skill / get-app-state）返回
 * {@code Failed to access file "..." A file needs to be open in the editor}；
 * {@code SyncMcpToolCallback} 打完 ERROR 日志后裸抛 {@code McpError}，回合 3、4 连续报废，
 * 用户只看到「⚠ 出错」，模型与用户都没机会看到「在 Pencil 中打开文件」这条本可自行消解的错误。
 */
class ResilientMcpToolCallbackTest {

    /** 事故原文（截取）：足够让模型判断「需要在 Pencil 编辑器里打开文件」。 */
    private static final String PENCIL_ERROR =
            "Failed to access file \"\". A file needs to be open in the editor to perform this action.";

    /** SDK 2.0.0 无 String 构造器，用 builder 造协议错误（code=-32603 internal error，事故同款）。 */
    private static McpError mcpError(String message) {
        return McpError.builder(-32603).message(message).build();
    }

    private static ToolCallback tool(String name, java.util.function.Function<String, String> behavior) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name(name).description("fake")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String toolInput) { return behavior.apply(toolInput); }
        };
    }

    @Test
    void mcpProtocolErrorBecomesErrorText_notRethrown() {
        ToolCallback raw = tool("mcp__pencil__read_skill", in -> { throw mcpError(PENCIL_ERROR); });

        String out = new ResilientMcpToolCallback(raw).call("{}");

        assertTrue(out.contains("mcp__pencil__read_skill"), "应点名工具，实际=" + out);
        assertTrue(out.contains("McpError"), "应含异常类型，实际=" + out);
        assertTrue(out.contains("A file needs to be open in the editor"),
                "应含原始错误消息（模型靠它对症处理），实际=" + out);
    }

    @Test
    void successPassesThroughUntouched() {
        ToolCallback raw = tool("mcp__pencil__get_app_state", in -> "ok");

        assertEquals("ok", new ResilientMcpToolCallback(raw).call("{}"));
    }

    @Test
    void causeChainIncluded() {
        Throwable root = new IllegalStateException("socket 已断开");
        ToolCallback raw = tool("mcp__s__t", in -> { throw new IllegalStateException("调用失败", root); });

        String out = new ResilientMcpToolCallback(raw).call("{}");

        assertTrue(out.contains("IllegalStateException"), "应含异常类型，实际=" + out);
        assertTrue(out.contains("调用失败"), "应含外层消息，实际=" + out);
        assertTrue(out.contains("socket 已断开"), "应含最内层根因消息，实际=" + out);
    }

    @Test
    void errorPassesThroughNotSwallowed() {
        // OOM 等致命 Error 不属于「工具执行失败」，吞掉会掩盖 JVM 级故障，必须穿透。
        ToolCallback raw = tool("mcp__s__t", in -> { throw new OutOfMemoryError("boom"); });

        assertThrows(OutOfMemoryError.class, () -> new ResilientMcpToolCallback(raw).call("{}"));
    }

    @Test
    void singleLineWithoutStack() {
        // MCP 协议错误的本地堆栈全是 reactor/SDK 传输帧（McpClientSession.sendRequest → FluxHandle…），
        // 对模型零信息量，只烧 token——不像本地工具那样附前 6 帧。
        ToolCallback raw = tool("mcp__pencil__read_skill", in -> { throw mcpError(PENCIL_ERROR); });

        String out = new ResilientMcpToolCallback(raw).call("{}");

        assertFalse(out.contains("\n"), "应为单行文本，实际=" + out);
        assertFalse(out.contains("堆栈"), "MCP 协议错误不应附本地堆栈，实际=" + out);
    }

    /**
     * 端到端契约：复现事故的框架路径——装饰后的 MCP 工具经
     * {@code DefaultToolCallingManager.executeToolCalls} 执行（正是生产堆栈里炸掉的那层），
     * 协议错误必须成为 ToolResponseMessage 回给模型，而不是异常上抛毁掉回合。
     */
    @Test
    void endToEnd_errorTextBecomesToolResponse_turnSurvives() {
        ToolCallback boom = new ResilientMcpToolCallback(
                tool("mcp__pencil__read_skill", in -> { throw mcpError(PENCIL_ERROR); }));

        ToolExecutionResult result = DefaultToolCallingManager.builder().build().executeToolCalls(
                new Prompt(List.of(),
                        ToolCallingChatOptions.builder().toolCallbacks(List.of(boom)).build()),
                ChatResponse.builder().generations(List.of(new Generation(
                        AssistantMessage.builder()
                                .toolCalls(List.of(new AssistantMessage.ToolCall(
                                        "call-1", "function", "mcp__pencil__read_skill", "{}")))
                                .build()))).build());

        ToolResponseMessage last = (ToolResponseMessage) result.conversationHistory()
                .get(result.conversationHistory().size() - 1);
        ToolResponseMessage.ToolResponse resp = last.getResponses().get(0);
        assertEquals("mcp__pencil__read_skill", resp.name());
        assertTrue(resp.responseData().contains("McpError"),
                "错误文本应作为 tool 结果回给模型，实际=" + resp.responseData());
        assertTrue(resp.responseData().contains("A file needs to be open in the editor"),
                "应含可自愈的错误原因，实际=" + resp.responseData());
    }
}
