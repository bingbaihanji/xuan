package com.bingbaihanji.jfgl.renderer;

/**
 * 单条绘制命令，描述一段连续顶点及其所需的 GL 状态。
 * <p>
 * 命令按提交顺序执行，顺序即 2D 的 z 序，不可重排。
 *
 * @param textureId    要绑定到 0 号纹理单元的纹理 ID
 * @param material     用哪个片段着色器，见 {@link Material}
 * @param firstVertex  起始顶点索引
 * @param vertexCount  顶点数量
 * @param scissorX     裁剪矩形左边缘 x（帧缓冲像素坐标）
 * @param scissorY     裁剪矩形<strong>上边缘</strong> y（y 向下，与 Gc 的用户坐标一致）
 * @param scissorWidth 裁剪矩形宽度
 * @param scissorHeight 裁剪矩形高度
 *
 * <p><strong>注意 y 方向</strong>：这里的 {@code scissorY} 是矩形<strong>上边缘</strong>，
 * 因为 {@code Gc} 使用"原点左上、y 向下"的像素坐标。而 {@code glScissor} 的原点在帧缓冲
 * <strong>左下角</strong>、y 向上，因此 {@code RenderBatch} 在提交时必须换算：
 * {@code glY = viewportHeight - scissorY - scissorHeight}。两处约定不同，改动任一侧都要同步另一侧。
 *
 * <p><strong>注意 {@code material} 的位置</strong>：它紧跟 {@code textureId}，
 * 与 {@code VertexWriter} 里"状态变了就切命令"的判据顺序一致。
 * 加组件会让所有构造点失效——编译器会全部指出来，这是好事，别绕过它。
 */
public record DrawCommand(int textureId, Material material, int firstVertex, int vertexCount,
                          int scissorX, int scissorY, int scissorWidth, int scissorHeight) {
}
