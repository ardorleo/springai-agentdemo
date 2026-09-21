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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片投递策略：<b>首次推一次，之后靠模型 Read</b>。
 *
 * <p><b>为什么不是"每轮都推"</b>：图片不进会话历史，所以只要在当轮范围内就会每轮重发，
 * 直到把回合额度吃光。实测第 1 轮产出的截图在此后 11 轮（模型早已转向别的工具调用）仍被重发，
 * 额度因此耗尽，导致模型<b>真正需要看图时 Read 也拿不到</b>——推送把拉取饿死了。
 *
 * <p>判据是「本轮新出现的消息」：按消息下标的高水位线，只有上一轮之后追加的消息里的图才推。
 * 这样用户图只在回合首轮推、工具图在产出的那轮推；而模型后来 Read 老图时，那个工具结果是<b>新</b>的，
 * 所以照样能拿到图（否则按需就成了"永远拿不到"）。
 */
class VisionPushOnceTest {

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

    private static ToolResponseMessage toolResult(String tool, String body) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "c" + tool + body.hashCode(), tool, body)))
                .build();
    }

    private static ToolResponseMessage plainText(String body) {
        return toolResult("Bash", body);
    }

    private long mediaCount(Prompt p) {
        long n = 0;
        for (Message m : p.getInstructions()) {
            if (m instanceof UserMessage u && u.getMedia() != null) n += u.getMedia().size();
        }
        return n;
    }

    private VisionMaterializer materializer() {
        return new VisionMaterializer(root, new ImagePreparer(), new VisionBudget());
    }

    /**
     * 用户图只在回合首轮推；之后几轮（工具在跑）不再重复推，且状态如实写 not_in_view
     * （此时它确实不在本轮视野里，模型可 Read 取回）。
     */
    @Test
    void userImagesArePushedOnlyInFirstRoundOfTurn() throws Exception {
        png("a.png");
        VisionMaterializer m = materializer();
        List<Message> msgs = new ArrayList<>();
        msgs.add(UserMessage.builder().text("看看这张\n" + ref("a.png", "a.png")).build());

        Prompt first = m.materialize(new Prompt(new ArrayList<>(msgs)), true);
        assertEquals(1, mediaCount(first), "回合首轮必须推一次（否则模型没理由去 Read）");

        for (int round = 2; round <= 5; round++) {
            msgs.add(plainText("编译通过 " + round));
            Prompt later = m.materialize(new Prompt(new ArrayList<>(msgs)), true);
            assertEquals(0, mediaCount(later),
                    "第 " + round + " 轮不该再推：模型没要，重复推只会吃掉额度");
            assertTrue(textOf(later).contains("delivery: " + FileReference.DELIVERY_NOT_IN_VIEW),
                    "不推时状态应如实写 not_in_view（Read 可取回），实际：\n" + textOf(later));
        }
    }

    /**
     * 工具图在<b>产出它的那一轮</b>推一次，下一轮不再重复推。
     */
    @Test
    void toolImageIsPushedInTheRoundItAppearsThenStops() throws Exception {
        png("shot.png");
        VisionMaterializer m = materializer();
        List<Message> msgs = new ArrayList<>();
        msgs.add(UserMessage.builder().text("看看这个").build());

        msgs.add(toolResult("Read", ref("shot.png", "shot.png")));
        Prompt appeared = m.materialize(new Prompt(new ArrayList<>(msgs)), true);
        assertEquals(1, mediaCount(appeared), "产出那一轮应推一次");

        msgs.add(plainText("下一步"));
        Prompt next = m.materialize(new Prompt(new ArrayList<>(msgs)), true);
        assertEquals(0, mediaCount(next), "下一轮不该重复推同一张");
    }

    /**
     * 推送不再吃光额度：模型真正需要看图时，Read 仍拿得到。
     *
     * <p>这正是原设计被诟病之处——第 1 轮的截图陪跑 11 轮把额度耗光，
     * 模型随后 Read 一张它<b>真正需要</b>的图却拿不到。
     */
    @Test
    void modelCanStillReadAnImageAfterManyToolRounds() throws Exception {
        png("old.png");
        png("needed.png");
        VisionBudget budget = new VisionBudget();
        VisionMaterializer m = new VisionMaterializer(root, new ImagePreparer(), budget);
        List<Message> msgs = new ArrayList<>();
        msgs.add(UserMessage.builder().text("开始").build());

        // 第 1 轮产出一张图，之后若干轮只有文本
        msgs.add(toolResult("Read", ref("old.png", "old.png")));
        m.materialize(new Prompt(new ArrayList<>(msgs)), true);
        for (int i = 0; i < VisionBudget.MAX_TOOL_TURN_DELIVERIES + 3; i++) {
            msgs.add(plainText("轮 " + i));
            m.materialize(new Prompt(new ArrayList<>(msgs)), true);
        }
        assertTrue(budget.toolTurnDeliveries() - 0 > 0, "额度上限应大于 0");

        // 模型现在真正需要一张图
        msgs.add(toolResult("Read", ref("needed.png", "needed.png")));
        Prompt got = m.materialize(new Prompt(new ArrayList<>(msgs)), true);
        assertEquals(1, mediaCount(got),
                "推送不该把额度吃光：模型 Read 的图必须还能拿到");
    }

    /** 聚合全部消息文本（用户消息 + 工具结果），delivery 行可能在这两处的任一处。 */
    private String textOf(Prompt p) {
        StringBuilder sb = new StringBuilder();
        for (Message m : p.getInstructions()) {
            if (m instanceof UserMessage u && u.getText() != null) sb.append(u.getText()).append('\n');
            if (m instanceof ToolResponseMessage t) {
                for (ToolResponseMessage.ToolResponse r : t.getResponses()) {
                    sb.append(r.responseData()).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
