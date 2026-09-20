package com.bingbaihanji.jfgl.text;

/**
 * 一个字形光栅化之后的位图：尺寸、相对于基线的偏移、推进宽度，以及像素本身。
 *
 * <h2>坐标约定</h2>
 * <p>{@code offsetX}/{@code offsetY} 是<strong>位图左上角相对于笔位置（基线起点）的偏移</strong>，
 * 单位是 em 像素，y 向下（与本项目其余部分一致）。
 * 因此 {@code offsetY} <strong>通常是负数</strong>——字形画在基线<em>之上</em>，
 * 而 y 向下意味着"之上"是更小的 y。
 * 这一条是最容易写反的地方，写反的表现是字形整体跑到基线下面去，而画面"看起来只是位置偏了"。
 *
 * <h2>为什么带 SPREAD 的那一圈</h2>
 * <p>{@code pixels} 是距离场，尺寸为「字形包围盒 + 每边 {@link SdfGenerator#SPREAD} 像素」。
 * 外扩那一圈的作用是把双线性过滤限制在自己的槽位内，同时给平滑边缘留出距离信息。
 *
 * @param width   位图宽度（像素），含两侧外扩
 * @param height  位图高度（像素），含上下外扩
 * @param offsetX 位图左边缘相对笔位置的 x 偏移（em 像素）
 * @param offsetY 位图<strong>上</strong>边缘相对基线的 y 偏移（em 像素），通常为负数
 * @param advance 推进宽度（em 像素）：画完这个字形之后笔要往右挪多少
 * @param pixels  距离场像素，长度必须为 {@code width * height}，按行存储
 */
public record GlyphBitmap(int width, int height, int offsetX, int offsetY,
                          float advance, byte[] pixels) {

    public GlyphBitmap {
        if (pixels.length != width * height) {
            throw new IllegalArgumentException(
                    "像素数量与尺寸不符：期望 " + (width * height) + "，实际 " + pixels.length);
        }
    }
}
