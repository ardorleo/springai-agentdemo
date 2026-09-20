// ToolResultMediaHandler.java
package io.github.javaside.springai.codetui.agent.media;

/** 表示策略扩展位：给定媒体产物与能力，决定「能否真投递」与「在工具结果里的表示」。 */
public interface ToolResultMediaHandler {
    /** 当前能力下能否把该类媒体真投递给模型（= 模型支持该类输入 {@code &&} 本链路已接注入器）。 */
    boolean canDeliver(MediaKind kind, ModelCapabilities caps);

    /** 产出该媒体在工具结果里的表示（本期恒引用；视觉分支属 Path B）。 */
    String represent(MediaArtifact media, ModelCapabilities caps);

    /**
     * 产出表示，并告知「本回合的视觉额度是否已用尽」。
     *
     * <p><b>为什么需要这个重载</b>：额度按回合分桶、Read 出来的图仍落在同一桶里，故额度耗尽后
     * 模型再 Read 也拿不回图。此时若照旧写 {@code not_in_view} + 「Read this path to bring it
     * into view」，就是在对模型说假话——它按提示反复 Read、继续看不到，最后只能答「我看不见」。
     * 传 {@code true} 时改写为 {@link FileReference#DELIVERY_TURN_EXHAUSTED}，让模型知道
     * 「本回合读不回来了，结束这轮再说」，而不是继续空转。
     *
     * @param turnExhausted 本回合工具图额度是否已耗尽（无预算来源时传 false，行为与两参重载一致）
     */
    default String represent(MediaArtifact media, ModelCapabilities caps, boolean turnExhausted) {
        return represent(media, caps);
    }
}
