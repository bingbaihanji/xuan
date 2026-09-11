package com.bingbaihanji.jfgl.renderer;

/**
 * 单条绘制命令，描述一段连续顶点及其所需的 GL 状态。
 * <p>
 * 命令按提交顺序执行，顺序即 2D 的 z 序，不可重排。
 *
 * @param textureId    要绑定到 0 号纹理单元的纹理 ID
 * @param firstVertex  起始顶点索引
 * @param vertexCount  顶点数量
 * @param scissorX     裁剪矩形左下角 x（帧缓冲像素坐标）
 * @param scissorY     裁剪矩形左下角 y
 * @param scissorWidth 裁剪矩形宽度
 * @param scissorHeight 裁剪矩形高度
 */
public record DrawCommand(int textureId, int firstVertex, int vertexCount,
                          int scissorX, int scissorY, int scissorWidth, int scissorHeight) {
}
