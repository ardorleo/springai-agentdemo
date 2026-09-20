package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回合额度的边界（用户图与工具图各自独立）。
 *
 * <p><b>为什么用户图必须豁免</b>：额度按「张·次」计，而图片每轮请求都要重发，故张数越多耗尽越快。
 * 实测 2 张图在第 7 轮、1 张图在第 13 轮就被掐掉；而用户贴的 1–3 张图是他这一轮的<b>全部意图</b>，
 * 中途静默消失会让「照这张图改」在回合后半段直接失效（模型只收到 turn_budget_exhausted，
 * 连 Read 也拿不回来——额度按回合分桶，Read 仍在同一桶里）。
 *
 * <p>额度原本要封的是<b>工具截图循环</b>（见 {@link VisionBudget} 类注释：20 次迭代 × 3 张 ≈ 108k
 * token）。故本类钉住：用户图不参与回合额度，工具图照旧参与。
 */
class VisionTurnBudgetTest {

    @TempDir Path root;

    private void png(String rel) throws Exception {
        Path p = root.resolve(rel);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        ImageIO.write(new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
    }

    private String ref(String name, String rel) {
        return "[file reference]\n"
                + "id: sha256:" + Integer.toHexString(rel.hashCode()) + "\n"
                + "kind: image\n"
                + "mime_type: image/png\n"
                + "size_bytes: 10\n"
                + "name: " + name + "\n"
                + "path: " + rel + "\n"
                + "delivery: not_in_view\n"
                + "reason: x\n"
                + "[/file reference]";
    }

    private static void addTool(List<Message> msgs) {
        msgs.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c" + msgs.size(), "Bash", "ok")))
                .build());
    }

    private long mediaCount(Prompt p) {
        long n = 0;
        for (Message m : p.getInstructions()) {
            if (m instanceof UserMessage u && u.getMedia() != null) n += u.getMedia().size();
        }
        return n;
    }

    /**
     * 用户图有<b>独立</b>回合额度，且比工具图宽：跑满 {@code MAX_TOOL_TURN_DELIVERIES} 轮工具循环后，
     * 用户图仍在。
     *
     * <p>钉住两个决定：① 用户图不再与工具图共享一个计数器（共享时 2 张图第 7 轮就被工具循环挤掉，
     * 正是线上「贴了图却说看不见」的成因）；② 用户图也不是无界（见
     * {@link #userImagesStopAfterTheirOwnTurnBudget}）。
     */
    @Test
    void userImagesSurviveWholeTurnDespiteToolLoop() throws Exception {
        png("a.png");
        png("b.png");
        VisionMaterializer m = new VisionMaterializer(root, new ImagePreparer(), new VisionBudget());

        List<Message> msgs = userTurn(
                "看看这两张图\n" + ref("a.png", "a.png") + "\n" + ref("b.png", "b.png"));

        // 工具循环轮数超出「工具图」额度，但仍在「用户图」额度内
        int iterations = VisionBudget.MAX_TOOL_TURN_DELIVERIES + 5;
        assertTrue(iterations * 2 <= VisionBudget.MAX_USER_TURN_DELIVERIES,
                "前提：这段时间必须落在用户图自己的额度内");
        for (int i = 1; i <= iterations; i++) {
            Prompt out = m.materialize(new Prompt(msgs), true);
            assertEquals(2, mediaCount(out),
                    "第 " + i + " 轮用户图不该消失（用户图有独立额度）");
            addToolResult(msgs);
        }
    }

    /**
     * 用户图额度有界：用一个回合跑满后必须停止投递，并写 {@code turn_budget_exhausted}。
     *
     * <p>没有这条，图就会随轮数<b>无界重传</b>——实测 2 张图跑 30 轮 = 60 张·次 ≈ 112.8k token，
     * 正是 {@link VisionBudget} 类注释点名要防的单回合成本失控（原文举例 108k）。
     */
    @Test
    void userImagesStopAfterTheirOwnTurnBudget() throws Exception {
        png("a.png");
        png("b.png");
        VisionMaterializer m = new VisionMaterializer(root, new ImagePreparer(), new VisionBudget());

        List<Message> msgs = userTurn(
                "看看这两张图\n" + ref("a.png", "a.png") + "\n" + ref("b.png", "b.png"));

        // 2 张/轮的消耗下，跑到超出用户图额度的那一轮
        int rounds = VisionBudget.MAX_USER_TURN_DELIVERIES / 2 + 2;
        boolean sawExhausted = false;
        for (int i = 1; i <= rounds; i++) {
            Prompt out = m.materialize(new Prompt(msgs), true);
            String delivery = VisionBudgetProbeDelivery(out);
            if (delivery.contains(FileReference.DELIVERY_TURN_EXHAUSTED)) {
                sawExhausted = true;
                assertEquals(0, mediaCount(out), "额度用尽后不该再投递用户图");
                break;
            }
            addToolResult(msgs);
        }
        assertTrue(sawExhausted,
                "用户图必须有自己的封顶（否则单回合成本无界）；跑了 " + rounds + " 轮仍未耗尽");
    }

    private static String VisionBudgetProbeDelivery(Prompt p) {
        StringBuilder sb = new StringBuilder();
        for (Message m : p.getInstructions()) {
            if (m instanceof UserMessage u && u.getText() != null) {
                sb.append(u.getText());
            }
        }
        return sb.toString();
    }

    /**
     * 工具图仍受回合额度约束——额度这层闸门不能因为用户图豁免而被整体拆掉。
     */
    @Test
    void toolImagesStillBoundedByTurnBudget() throws Exception {
        png("shot.png");
        VisionMaterializer m = new VisionMaterializer(root, new ImagePreparer(), new VisionBudget());

        List<Message> msgs = userTurn("改一下");
        int delivered = 0;
        for (int i = 1; i <= VisionBudget.MAX_TOOL_TURN_DELIVERIES + 3; i++) {
            msgs.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "r" + i, "Read", ref("shot.png", "shot.png"))))
                    .build());
            Prompt out = m.materialize(new Prompt(msgs), true);
            if (mediaCount(out) > 0) delivered++;
        }
        assertEquals(VisionBudget.MAX_TOOL_TURN_DELIVERIES, delivered,
                "工具图累计兑现次数应恰好等于回合额度");
    }

    /**
     * 额度耗尽后，模型再 Read 图片时工具结果必须说实话——不能再说「Read 一次就能看」。
     *
     * <p>这是模型答「我看不见」的直接来源：额度已尽时 Read 拿不回图，但那句话仍写着
     * not_in_view，模型只会按提示反复 Read、继续看不到。
     */
    @Test
    void readResultStopsClaimingItCanBeMadeVisibleOnceBudgetExhausted() throws Exception {
        png("shot.png");
        VisionBudget budget = new VisionBudget();
        VisionMaterializer m = new VisionMaterializer(root, new ImagePreparer(), budget);

        // 用工具图把额度耗尽
        List<Message> msgs = userTurn("改一下");
        for (int i = 1; i <= VisionBudget.MAX_TOOL_TURN_DELIVERIES; i++) {
            msgs.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "r" + i, "Read", ref("shot.png", "shot.png"))))
                    .build());
            m.materialize(new Prompt(msgs), true);
        }
        assertTrue(budget.currentTurnExhausted(), "前提：额度应已耗尽");

        ToolResultMediaHandler handler = new TextReferenceMediaHandler();
        String block = handler.represent(artifact(), new ModelCapabilities(true, false), true);

        assertTrue(block.contains(FileReference.DELIVERY_TURN_EXHAUSTED),
                "额度已尽时引用应写 turn_budget_exhausted，实际：\n" + block);
        assertFalse(block.contains("Read this path to bring it into view"),
                "不能继续对模型说「Read 一次就能看」：\n" + block);

        // 对照组：额度未尽时仍写 not_in_view（否则上面那条断言可能只是碰巧成立）
        String normal = handler.represent(artifact(), new ModelCapabilities(true, false), false);
        assertTrue(normal.contains(FileReference.DELIVERY_NOT_IN_VIEW),
                "额度未尽时不该改写成 turn_budget_exhausted：\n" + normal);
    }

    /** 用户锚点消息：文本 + 引用块。 */
    private static java.util.ArrayList<Message> userTurn(String text) {
        return new java.util.ArrayList<>(List.of(UserMessage.builder().text(text).build()));
    }

    /** 追加一条工具结果（模拟工具循环的一轮）。 */
    private static void addToolResult(List<Message> msgs) {
        msgs.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c" + msgs.size(), "Bash", "ok")))
                .build());
    }

    private MediaArtifact artifact() {
        return new MediaArtifact("b".repeat(64), root.resolve("shot.png"), "shot.png",
                "image/png", "image/png", MediaKind.IMAGE, 100L, 8, 8, null,
                ArtifactSource.EXISTING_FILE, false, "shot.png");
    }
}
