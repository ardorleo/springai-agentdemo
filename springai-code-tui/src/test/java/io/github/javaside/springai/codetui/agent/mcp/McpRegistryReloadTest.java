package io.github.javaside.springai.codetui.agent.mcp;

import io.github.javaside.springai.codetui.agent.AgentTools;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行期 reload {@code mcp.json} 的 diff 语义与守卫。
 *
 * <p><b>为什么必须测</b>：reload 是「第二次 init」——但它发生在一个<b>已经有连接、有在飞连接、
 * 可能刚被用户 toggle 过</b>的活 registry 上。启动期连接的两道丢弃守卫（close / 禁用）防的竞态，
 * reload 各自又开了一遍：替换条目时在飞的旧连接写回，就是第三个同类竞态（写回目标 Entry 已出表）。
 *
 * <p><b>接缝</b>：连接走 {@code initWithConnector} 的 connector（与
 * {@code McpRegistryBackgroundConnectTest} 同款——{@code McpSyncClient} 造不出假的，
 * 而风险全在写回时机）。reload 用显式列表重载，不读真实两层文件——生产版 {@code reload()}
 * 只是「{@code loadAll(root)} → 本重载」的薄胶水，读文件本身归 {@code McpConfigLoaderTest}
 * （且单测读真实 {@code user.home} 会把本机 {@code ~/.codetui/mcp.json} 混进来）。
 *
 * <p><b>已知边界</b>：①替换丢弃时「当场关掉 client」这一半与 close 守卫同边界——需要真
 * {@code McpSyncClient} 才观测得到，造不出来。本类断的是「没写回 / 没复活」这一半。
 * ②面板状态字 CONNECTED 需要真 client，接缝路径恒显示 FAILED——所以断「工具落地」
 * （{@code activeTools}/{@code toolCount}）而不是断状态字。
 */
class McpRegistryReloadTest {

    private static ToolCallback fakeTool(String name) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
            }
            @Override public String call(String toolInput) { return "ok"; }
        };
    }

    /** command 参与 equals：同名字段变 = reload 要替换的「参数变更」场景。 */
    private static McpConfigLoader.LoadedServer server(Path root, String name, String command, boolean enabled) {
        return new McpConfigLoader.LoadedServer(
                new McpServerConfig.StdioServerConfig(name, enabled, Duration.ofSeconds(2),
                        command, List.of(), Map.of()),
                McpConfigLoader.ConfigSource.PROJECT, root.resolve("mcp.json"));
    }

    /** 立即成功的 connector 结果（client 为 null——造不出真的）。 */
    private static McpRegistry.Connected done(String toolName) {
        return new McpRegistry.Connected(null, List.of(fakeTool(toolName)), null);
    }

    /** 连接卡在闸门上，放行后成功。 */
    private static McpRegistry.Connected blocked(CountDownLatch gate, String toolName) {
        try {
            assertTrue(gate.await(5, TimeUnit.SECONDS), "闸门没被放行，测试自身超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return done(toolName);
    }

    /** stdio 条目的 command（接口只有 name/enabled/timeoutMs，command 在分型上）。 */
    private static String stdioCommand(McpConfigLoader.LoadedServer l) {
        return ((McpServerConfig.StdioServerConfig) l.config()).command();
    }

    /** 等后台连接全部结束（计数归零再让写回落定）。 */
    private static void awaitIdle(McpRegistry reg) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (reg.connectingCount() == 0) {
                Thread.sleep(30);
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("后台连接没有在 5s 内结束");
    }

    @Test
    @DisplayName("新增 enabled 条目：出现在面板、后台连上、工具可见")
    void reloadPicksUpAndConnectsNewEnabledEntry(@TempDir Path root) throws Exception {
        McpRegistry reg = McpRegistry.initWithConnector(root, new ConversationState(),
                List.of(), AgentTools.testEngine(root),
                l -> done("mcp__" + l.config().name() + "__t"));

        McpRegistry.ReloadResult r = reg.reload(List.of(server(root, "s2", "echo", true)));

        assertEquals(1, r.added());
        awaitIdle(reg);
        assertEquals(1, reg.activeTools().size(),
                "改完文件 reload 后，新 server 的工具要能被下一回合快照到（无需重启）");
    }

    @Test
    @DisplayName("删除条目：从面板消失、工具即时摘除")
    void reloadDropsDeletedEntryAndItsTools(@TempDir Path root) throws Exception {
        McpRegistry reg = McpRegistry.initWithConnector(root, new ConversationState(),
                List.of(server(root, "s1", "echo", true), server(root, "s2", "echo", true)),
                AgentTools.testEngine(root),
                l -> done("mcp__" + l.config().name() + "__t"));
        awaitIdle(reg);
        assertEquals(2, reg.activeTools().size());

        McpRegistry.ReloadResult r = reg.reload(List.of(server(root, "s2", "echo", true)));

        assertEquals(1, r.removed());
        assertEquals(1, reg.servers().size(), "被删的条目不该再出现在面板上");
        assertTrue(reg.activeTools().stream().noneMatch(t -> t.getToolDefinition().name().startsWith("mcp__s1__")),
                "被删 server 的工具必须即时摘除——留着就是下一回合的幽灵调用");
    }

    @Test
    @DisplayName("参数变更：旧连接换新配置重连，旧工具被新工具替换")
    void reloadReplacesChangedConfigAndReconnects(@TempDir Path root) throws Exception {
        McpRegistry reg = McpRegistry.initWithConnector(root, new ConversationState(),
                List.of(server(root, "s1", "echo", true)), AgentTools.testEngine(root),
                l -> done("mcp__s1__" + stdioCommand(l) + "_tool"));
        awaitIdle(reg);
        assertEquals(1, reg.activeTools().size());

        McpRegistry.ReloadResult r = reg.reload(List.of(server(root, "s1", "echo2", true)));

        assertEquals(1, r.replaced());
        awaitIdle(reg);
        List<String> names = reg.activeTools().stream().map(t -> t.getToolDefinition().name()).toList();
        assertEquals(List.of("mcp__s1__echo2_tool"), names,
                "改 command 后必须按新配置重连——旧连接的工具不能赖着不走");
    }

    @Test
    @DisplayName("未变条目：连接保留不闪断、也不起多余连接")
    void reloadKeepsUnchangedEntryConnectedWithoutReconnect(@TempDir Path root) throws Exception {
        McpRegistry reg = McpRegistry.initWithConnector(root, new ConversationState(),
                List.of(server(root, "s1", "echo", true)), AgentTools.testEngine(root),
                l -> done("mcp__s1__t"));
        awaitIdle(reg);
        assertEquals(0, reg.connectingCount());

        McpRegistry.ReloadResult r = reg.reload(List.of(server(root, "s1", "echo", true)));

        assertEquals(1, r.unchanged());
        assertEquals(0, reg.connectingCount(), "没变的条目不该起新连接——断开重连是闪断，不是刷新");
        assertEquals(1, reg.servers().get(0).toolCount(), "已连接的工具必须原样保留（不闪断）");
    }

    @Test
    @DisplayName("文件里 enabled 翻成 false：按文件意图禁用、摘工具")
    void reloadDisablesEntryWhenFileDisablesIt(@TempDir Path root) throws Exception {
        McpRegistry reg = McpRegistry.initWithConnector(root, new ConversationState(),
                List.of(server(root, "s1", "echo", true)), AgentTools.testEngine(root),
                l -> done("mcp__s1__t"));
        awaitIdle(reg);

        McpRegistry.ReloadResult r = reg.reload(List.of(server(root, "s1", "echo", false)));

        assertEquals(1, r.replaced(), "enabled 字段参与 equals：翻 false 走替换路径");
        assertEquals(McpRegistry.Status.DISABLED, reg.servers().get(0).status(),
                "手改 enabled:false 后 reload，意图以文件为准");
        assertTrue(reg.activeTools().isEmpty());
    }

    @Test
    @DisplayName("文件里 enabled 翻成 true：连接启用")
    void reloadEnablesEntryWhenFileEnablesIt(@TempDir Path root) throws Exception {
        McpRegistry reg = McpRegistry.initWithConnector(root, new ConversationState(),
                List.of(server(root, "s1", "echo", false)), AgentTools.testEngine(root),
                l -> done("mcp__s1__t"));
        assertEquals(McpRegistry.Status.DISABLED, reg.servers().get(0).status());

        reg.reload(List.of(server(root, "s1", "echo", true)));
        awaitIdle(reg);

        assertEquals(1, reg.activeTools().size(), "enabled 翻 true 后 reload：连接结果要落地");
    }

    /**
     * 替换条目与在飞旧连接的竞态（第三道守卫）：旧 Entry 的连接结果写回时，该名字对应的
     * 表内 Entry 已是替换后的新对象——写回必须被丢弃，不得污染新 Entry。
     *
     * <p>杀的变异：写回若实现成「按 name 查表写入」（而非只写闭包 Entry 且比对引用），
     * 旧连接的 {@code mcp__s1__echo_tool} 会覆盖/混进新 Entry 的结果，下面的精确断言即红。
     */
    @Test
    @DisplayName("替换时在飞的旧连接：迟到的结果不得写进替换后的新条目")
    void inFlightResultForReplacedEntryDoesNotOverwriteItsSuccessor(@TempDir Path root) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        McpRegistry reg = McpRegistry.initWithConnector(root, new ConversationState(),
                List.of(server(root, "s1", "echo", true)), AgentTools.testEngine(root),
                l -> blocked(gate, "mcp__s1__" + stdioCommand(l) + "_tool"));
        // 连接①在飞（v1: command=echo）。reload 替换 → 连接②在飞（v2: command=echo2）。
        reg.reload(List.of(server(root, "s1", "echo2", true)));

        gate.countDown();    // 两个连接一起放行：①写回必须被丢弃，②照常落地
        awaitIdle(reg);

        List<String> names = reg.activeTools().stream().map(t -> t.getToolDefinition().name()).toList();
        assertEquals(List.of("mcp__s1__echo2_tool"), names,
                "旧连接的迟到结果写进了新条目——替换后必须只剩新配置连接的产物");
    }
}
