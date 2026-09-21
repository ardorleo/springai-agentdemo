package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.media.FileReferenceParser;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.media.VisionModels;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.toolkit.element.Element;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片占位符（{@code [IMAGE1]}）：输入框显示、提交展开、边界行为。
 *
 * <p><b>证据边界</b>：粘贴 → 占位符的接线在 {@link AttachmentLineTest}；本类钉住
 * <b>展开</b>——提交出去的文本必须还原成真实路径，否则模型永远不知道 [IMAGE1] 是什么，
 * 这是占位符方案唯一不能坏的一环。
 */
class ImagePlaceholderTest {

    // ── 纯函数：展开 ─────────────────────────────────────────

    @Test
    void placeholderExpandsToRegisteredPath() {
        // 双引号包裹：路径常含空格，识别器按空白切词，引号是 tokenize 认的转义形态
        String out = CodeTuiView.expandImagePlaceholders(
                "看 [IMAGE1]", Map.of(1, "/tmp/a.png"));
        assertEquals("看 \"/tmp/a.png\"", out);
    }

    @Test
    void multiplePlaceholdersExpandInPlace() {
        String out = CodeTuiView.expandImagePlaceholders(
                "对比 [IMAGE1] 和 [IMAGE2]",
                Map.of(1, "/tmp/a.png", 2, "/tmp/b.png"));
        assertEquals("对比 \"/tmp/a.png\" 和 \"/tmp/b.png\"", out);
    }

    /** 未登记的编号必须原样保留——用户手打 [IMAGE9] 是普通文本，不是图片引用。 */
    @Test
    void unregisteredNumberStaysAsLiteralText() {
        String out = CodeTuiView.expandImagePlaceholders(
                "参考 [IMAGE9] 的样式", Map.of(1, "/tmp/a.png"));
        assertEquals("参考 [IMAGE9] 的样式", out);
    }

    /** 残缺标记（编辑到一半）不当占位符——最可预测的行为。 */
    @Test
    void partialMarkersStayAsLiteralText() {
        Map<Integer, String> map = Map.of(1, "/tmp/a.png");
        assertEquals("看 [IMAGE 和 IMAGE1] 的区别",
                CodeTuiView.expandImagePlaceholders("看 [IMAGE 和 IMAGE1] 的区别", map));
        assertEquals("[IMAGE1", CodeTuiView.expandImagePlaceholders("[IMAGE1", map));
    }

    // ── 提交链路：展开必须发生在兑现之前 ─────────────────────

    /** 丢弃型 scrollback 接缝：本类只关心提交出去的文本，scrollback 内容不参与。 */
    private static final ScrollbackPrinter.Sink NULL_SINK = new ScrollbackPrinter.Sink() {
        @Override public void println(Text line)   { }
        @Override public void println(String line) { }
    };

    private static Path png(Path dir, String rel) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent() == null ? dir : p.getParent());
        ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
        return p;
    }

    /** 捕获提交文本的真实 SubmitHandler。 */
    private record CapturingHandler(List<String> submitted) implements SubmitHandler {
        @Override public reactor.core.Disposable submit(String text) {
            submitted.add(text);
            return null;
        }
        @Override public String currentModel() { return "claude-sonnet-5"; }
        @Override public ModelCapabilities currentModelCapabilities() {
            return new ModelCapabilities(VisionModels.supportsImage("claude-sonnet-5"), false);
        }
    }

    /**
     * ★ 端到端：拖图占位符 → 提交 → 出去的文本含真实路径的引用块，解析器认得回来。
     * 占位符若漏展开，模型收到的就只有字面 [IMAGE1]，图静默丢失。
     */
    @Test
    @DisplayName("拖图占位符提交后，模型收到的是真实路径的引用块")
    void placeholderExpandsIntoFileReferenceOnSubmit(@TempDir Path root) throws Exception {
        Assumptions.assumeTrue(VisionModels.enabled(), "CODETUI_VISION=off，视觉链路整体停用");
        Path image = png(root, "docs/design.png");
        List<String> submitted = new ArrayList<>();
        CodeTuiView v = new CodeTuiView(new ConversationState(),
                new CapturingHandler(submitted), root, NULL_SINK);

        var type = Class.forName(CodeTuiView.class.getName() + "$InputBox");
        var constructor = type.getDeclaredConstructor(CodeTuiView.class);
        constructor.setAccessible(true);
        Element input = (Element) constructor.newInstance(v);
        input.handlePasteEvent(new PasteEvent(image.toString()));
        assertEquals("[IMAGE1]", v.inputTextForTest());
        v.feedKeyForTest(KeyEvent.ofChar('看'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));    // 提交

        assertEquals(1, submitted.size(), "提交没发生：" + submitted);
        String out = submitted.get(0);
        assertTrue(out.startsWith("[IMAGE1]看"),
                "用户原文应以占位符形态保留在正文里（引用块附在后面）：" + out);
        var refs = FileReferenceParser.parse(out, root);
        assertEquals(1, refs.size(), "占位符没展开成引用块，图会静默丢失：\n" + out);
        assertEquals("design.png", refs.get(0).name());
    }

    /** 提交清空输入框后，编号必须从头计——上一条的 [IMAGE1] 不该把下一条挤成 [IMAGE2]。 */
    @Test
    @DisplayName("提交后占位符编号复位：新消息又从 [IMAGE1] 开始")
    void numberingResetsAfterSubmit(@TempDir Path root) throws Exception {
        Path first = png(root, "one.png");
        Path second = png(root, "two.png");
        CodeTuiView v = new CodeTuiView(new ConversationState(), new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
            @Override public String currentModel() { return "claude-sonnet-5"; }
            @Override public ModelCapabilities currentModelCapabilities() {
                return new ModelCapabilities(VisionModels.supportsImage("claude-sonnet-5"), false);
            }
        }, root, NULL_SINK);

        var type = Class.forName(CodeTuiView.class.getName() + "$InputBox");
        var constructor = type.getDeclaredConstructor(CodeTuiView.class);
        constructor.setAccessible(true);
        Element input = (Element) constructor.newInstance(v);
        input.handlePasteEvent(new PasteEvent(first.toString()));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));    // 提交第一条

        input.handlePasteEvent(new PasteEvent(second.toString()));
        assertEquals("[IMAGE1]", v.inputTextForTest(),
                "提交后编号没复位，占位符对不上号会让用户找不到图");
    }
}
