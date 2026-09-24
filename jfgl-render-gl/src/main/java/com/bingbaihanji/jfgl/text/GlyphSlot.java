package com.bingbaihanji.jfgl.text;

/**
 * 一个字形在图集里占据的槽位：uv 矩形 + 相对基线的偏移 + 尺寸 + 推进宽度。
 *
 * <h2>单位</h2>
 * <p><strong>除了 uv，其余全部是 {@code GlyphRasterizer.EM_SIZE} 尺寸下的像素，
 * 不预乘字号缩放。</strong>
 * 字号缩放由 {@code TextLayout} 在发射顶点时乘上去。放在这里预乘是错的：
 * 同一个槽位会被不同字号复用，图集是按字形而不是按 (字形, 字号) 缓存的。
 *
 * <h2>uv 的半点偏移</h2>
 * <p>{@code u0}/{@code v0} 取的是槽位<strong>第一个纹素的中心</strong>，
 * {@code u1}/{@code v1} 取的是<strong>最后一个纹素的中心</strong>，
 * 即 {@code (x + 0.5) / SIZE} 与 {@code (x + w - 0.5) / SIZE}。
 * 直接写 {@code x / SIZE} 会采到相邻纹素，表现为边缘发虚或字形轻微错位——
 * 这是"看起来只是有点糊"的那类错误，很难靠肉眼定位。
 *
 * @param u0      纹理坐标左边界（首个纹素中心）
 * @param v0      纹理坐标上边界（首个纹素中心）
 * @param u1      纹理坐标右边界（末个纹素中心）
 * @param v1      纹理坐标下边界（末个纹素中心）
 * @param offsetX 位图左边缘相对笔位置的 x 偏移（em 像素）
 * @param offsetY 位图上边缘相对基线的 y 偏移（em 像素），通常为负数
 * @param width   位图宽度（像素），为 0 表示空字形
 * @param height  位图高度（像素），为 0 表示空字形
 * @param advance 推进宽度（em 像素）
 */
public record GlyphSlot(float u0, float v0, float u1, float v1,
                        int offsetX, int offsetY, int width, int height, float advance) {

    /**
     * 是否为空字形（宽或高为 0）。
     *
     * <p>空字形（空格、以及度量上宽度为 0 的字形）只让笔前进，
     * <strong>不发顶点、也不上传</strong>。这是正常情况而不是错误。
     *
     * @return 空字形时为 true
     */
    public boolean isEmpty() {
        return width == 0 || height == 0;
    }
}
