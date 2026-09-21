package io.github.javaside.springai.codetui.agent.mcp;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具容错装饰器：把 delegate 抛出的 {@link RuntimeException} 转成<b>信息完整</b>的错误文本
 * 作为 tool 结果返回，<b>绝不 rethrow 终止回合</b>。
 *
 * <p><b>为什么需要它</b>（2026-09-21 事故，session 20260921T051342）：MCP server 返回
 * JSON-RPC error 时，Spring AI 的 {@code SyncMcpToolCallback} 打完 ERROR 日志后<b>裸抛</b>
 * {@code McpError}——不包装成 {@code ToolExecutionException}。于是两道既有防线全部绕过：
 * {@code DefaultToolCallingManager} 只把 {@code ToolExecutionException} 交给
 * {@code ResilientToolExecutionExceptionProcessor}；{@code ResilientToolCallingManager} 只兜
 * 「No ToolCallback found」的 {@code IllegalStateException}。异常一路上传，
 * {@code CodingAgent.handleError} 把整回合标成失败——Pencil 桌面 App 重启后未打开文档这类
 * <b>本可由模型自行消解</b>的错误（「A file needs to be open in the editor」），用户只看到
 * 「⚠ 出错」，模型和用户都没机会看到错误原因。这与 v1.20.1 修过的超时事故同构：
 * 防线按异常类型白名单兜底，每种新异常类型就是一个新洞；本装饰器把「MCP 工具执行」这个
 * <b>整类来源</b>在离异常最近的地方焊死，不再依赖下游白名单。
 *
 * <p><b>为什么 catch {@code RuntimeException} 而不是只 catch {@code McpError}</b>：
 * MCP 调用链上的意外异常不止协议错误一种（SDK 版本升级换异常类型、传输层超时包装、
 * 内层媒体外置装饰器的意外失败……），按类型列举就是重蹈白名单覆辙；而 {@code Error}
 * （OOM 等 JVM 级故障）不属于「工具执行失败」，吞掉会掩盖致命问题，必须穿透。
 *
 * <p><b>装配位置（见 {@code McpRegistry.decorate}）</b>：{@code ToolEventCallback} 之外、
 * {@code PermissionCallback} 之内。异常先穿过内层 {@code ToolEventCallback}——它把这次失败
 * 记成 {@code ok=false}（TUI 显示 ✗，不误报成功）——再被本装饰器转成文本；权限拒绝路径
 * 在外层、根本不经过这里，语义不受影响。
 *
 * <p><b>错误文本格式</b>：与 {@code ResilientToolExecutionExceptionProcessor} 同款
 * （点名工具 + 类型 + 消息 + cause 链，链尾根因优先），但<b>不附堆栈</b>——MCP 协议错误的
 * 本地堆栈全是 reactor/SDK 传输帧（{@code McpClientSession.sendRequest → FluxHandle…}），
 * 对模型零信息量，只烧 token；本地工具附前 6 帧是因为帧里有业务代码，MCP 没有。
 * <b>不附加任何引导词</b>：server 的错误消息（如 Pencil 的「A file needs to be open...」）
 * 本身就是信息，模型自会判断下一步。
 */
public final class ResilientMcpToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    public ResilientMcpToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public String call(String toolInput) { return call(toolInput, null); }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            return toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
        } catch (RuntimeException ex) {
            return errorText(ex);
        }
    }

    /** 拼错误文本：工具名 + 「执行出错」+ cause 链（链尾根因优先，逐层「；引发于」）。单行，无堆栈。 */
    private String errorText(RuntimeException ex) {
        String toolName = delegate.getToolDefinition() == null
                || delegate.getToolDefinition().name() == null
                ? "未知工具"
                : delegate.getToolDefinition().name();
        StringBuilder sb = new StringBuilder();
        sb.append("工具 ").append(toolName).append(" 执行出错");

        // cause 链收集（去重防自引用），与 ResilientToolExecutionExceptionProcessor 同款。
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = ex; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        Throwable root = chain.get(chain.size() - 1);
        appendThrowable(sb, root);
        for (int i = chain.size() - 2; i >= 0; i--) {
            sb.append("；引发于");
            appendThrowable(sb, chain.get(i));
        }
        return sb.toString();
    }

    private static void appendThrowable(StringBuilder sb, Throwable t) {
        sb.append("：").append(t.getClass().getSimpleName());
        String msg = t.getMessage();
        if (msg != null && !msg.isBlank()) {
            sb.append(": ").append(msg);
        }
    }
}
