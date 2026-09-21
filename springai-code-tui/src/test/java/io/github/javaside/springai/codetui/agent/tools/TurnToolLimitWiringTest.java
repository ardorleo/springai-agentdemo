package io.github.javaside.springai.codetui.agent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnToolLimitWiring} 行为验证：Spring AI 2.0.1 起 {@code DefaultToolCallingManager}
 * 自带每工具/总量限流，默认 40/150 且 {@code THROW}——{@code ToolCallLimitExceededException}
 * 是 RuntimeException，绕过本工程两道 Resilient 防线直接杀整回合。本接线的契约：
 * <b>默认不限</b>（agent 自主性优先），限额仅经 {@code CODETUI_MAX_CALLS_PER_TOOL} /
 * {@code CODETUI_MAX_TOTAL_TOOL_CALLS} 显式配置；<b>配了之后撞限也只回错误文本</b>，
 * 工具不执行、回合存活。
 *
 * <p>对照用例 {@link #frameworkDefaults_breachThrows()} 钉住框架默认行为（升级 Spring AI
 * 时若默认值漂移，该用例先红，提醒复核这里的策略）。
 */
class TurnToolLimitWiringTest {

    /** 记录调用次数的桩工具；name 决定它命中哪条限额。 */
    private static ToolCallback recordingTool(String name, AtomicInteger calls) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("d")
                        .inputSchema("{\"type\":\"object\"}").build();
            }
            @Override public String call(String toolInput) {
                calls.incrementAndGet();
                return "ok-" + name;
            }
        };
    }

    /** 当前回合的消息历史：一条 UserMessage + n 条同名工具的历史响应（限流按此计数）。 */
    private static List<Message> turnWithPriorToolResponses(String toolName, int n) {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("任务"));
        for (int i = 0; i < n; i++) {
            history.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("prior-" + i, toolName, "ok")))
                    .build());
        }
        return history;
    }

    private static Prompt prompt(List<Message> history, ToolCallback tool) {
        return new Prompt(history,
                ToolCallingChatOptions.builder().toolCallbacks(List.of(tool)).build());
    }

    private static ChatResponse genWithToolCall(String toolName) {
        return ChatResponse.builder().generations(List.of(new Generation(
                AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, "{}")))
                        .build()))).build();
    }

    private static ToolResponseMessage.ToolResponse lastToolResponse(ToolExecutionResult result) {
        ToolResponseMessage last = (ToolResponseMessage) result.conversationHistory()
                .get(result.conversationHistory().size() - 1);
        return last.getResponses().get(0);
    }

    @Test
    void noLimitConfigured_arbitraryDepthStillExecutes() {
        // 默认契约：不限。250 次先例远超框架默认 40 / 我们曾经的 200——必须照样执行。
        ToolCallingManager mgr = TurnToolLimitWiring.create(null, null);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback edit = recordingTool("Edit", calls);

        ToolExecutionResult result = mgr.executeToolCalls(
                prompt(turnWithPriorToolResponses("Edit", 250), edit), genWithToolCall("Edit"));

        assertEquals(1, calls.get(), "未配置限额时任何深度都应正常执行");
        assertEquals("ok-Edit", lastToolResponse(result).responseData());
    }

    @Test
    void perToolLimitConfigured_breachReturnsErrorText_toolNotExecuted_noException() {
        ToolCallingManager mgr = TurnToolLimitWiring.create(3, null);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback edit = recordingTool("Edit", calls);

        // 3 次先例 + 本次 = 4 > 3：撞限。
        ToolExecutionResult result = mgr.executeToolCalls(
                prompt(turnWithPriorToolResponses("Edit", 3), edit), genWithToolCall("Edit"));

        ToolResponseMessage.ToolResponse resp = lastToolResponse(result);
        assertEquals("Edit", resp.name());
        assertTrue(resp.responseData().contains("Tool call limit"),
                "撞限的错误文本应作为 tool 结果回给模型，实际=" + resp.responseData());
        assertEquals(0, calls.get(), "被限流拦下的调用不得真正执行");
    }

    @Test
    void perToolLimitConfigured_justUnderLimit_executesNormally() {
        ToolCallingManager mgr = TurnToolLimitWiring.create(3, null);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback edit = recordingTool("Edit", calls);

        // 2 次先例 + 本次 = 3，恰好在上限内（含），必须正常执行。
        ToolExecutionResult result = mgr.executeToolCalls(
                prompt(turnWithPriorToolResponses("Edit", 2), edit), genWithToolCall("Edit"));

        assertEquals(1, calls.get(), "上限内的调用应正常执行");
        assertEquals("ok-Edit", lastToolResponse(result).responseData());
    }

    @Test
    void totalLimitConfigured_breachReturnsErrorText() {
        // 总量限不限单工具：3 次先例 < 单工具不限，但总量 5 次先例 + 本次 = 6 > 5 → 撞总量。
        ToolCallingManager mgr = TurnToolLimitWiring.create(null, 5);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback edit = recordingTool("Edit", calls);

        ToolExecutionResult result = mgr.executeToolCalls(
                prompt(turnWithPriorToolResponses("Edit", 5), edit), genWithToolCall("Edit"));

        assertTrue(lastToolResponse(result).responseData().contains("tool call limit"),
                "撞总量限的错误文本应作为 tool 结果回给模型，实际=" + lastToolResponse(result).responseData());
        assertEquals(0, calls.get(), "被总量限拦下的调用不得真正执行");
    }

    @Test
    void parseLimit_validMissingInvalidAndNonPositive() {
        assertEquals(42, TurnToolLimitWiring.parseLimit("42"));
        assertEquals(7, TurnToolLimitWiring.parseLimit(" 7 "));
        assertNull(TurnToolLimitWiring.parseLimit(null), "缺失 = 不限");
        assertNull(TurnToolLimitWiring.parseLimit(""), "空串 = 不限");
        assertNull(TurnToolLimitWiring.parseLimit("  "), "空白 = 不限");
        assertNull(TurnToolLimitWiring.parseLimit("abc"), "非法值必须回落不限，而不是启动崩掉");
        assertNull(TurnToolLimitWiring.parseLimit("0"), "0 视为不限");
        assertNull(TurnToolLimitWiring.parseLimit("-3"), "负数视为不限");
    }

    /**
     * 对照组（characterization）：框架默认 40/THROW 的裸行为。它测的是 Spring AI 而非本工程，
     * 不会因本接线变化而变红——它存在的意义是钉住「我们正在逃离什么」，并在升级框架
     * 默认值漂移时率先报警。
     */
    @Test
    void frameworkDefaults_breachThrows() {
        ToolCallback edit = recordingTool("Edit", new AtomicInteger());

        // 40 次先例 + 本次 = 41 > 默认 40，默认行为 THROW。
        assertThrows(ToolCallLimitExceededException.class, () ->
                DefaultToolCallingManager.builder().build().executeToolCalls(
                        prompt(turnWithPriorToolResponses("Edit", 40), edit), genWithToolCall("Edit")));
    }
}
