package com.bingbaihanji.xuan.text;

/**
 * 把字形光栅化成覆盖度位图，再交给 {@link SdfGenerator} 转成距离场。
 *
 * <h2>两个常量一起决定单字形的最大边长</h2>
 * <p>{@link #EM_SIZE}（em 高度，像素）与 {@link SdfGenerator#SPREAD}（外扩像素数）
 * 决定单字形位图的最大边长 = {@code EM_SIZE + 2 * SPREAD}（默认 48 + 16 = 64）。
 * <strong>{@code GlyphAtlas.SIZE} 必须不小于这个值</strong>，否则一个字形的槽位
 * 会放不进一行，货架分配会退化。
 *
 * <p>本期只有<strong>一个</strong> em 尺寸桶（{@link #EM_SIZE}）：字形在此尺寸下光栅化一次，
 * 之后靠 SDF 在任意字号下重新求出锐利边缘。这是 SDF 相对位图拉伸的全部价值所在，
 * 也是驱动场景（图表刻度、图例）能只付一次生成代价的原因。
 *
 * <h2>线程</h2>
 * <p>非线程安全。与 {@link GlyphAtlas} 一样，只在 GL 线程上使用。
 */
public final class GlyphRasterizer {

    /**
     * 光栅化时的 em 高度（像素）。
     *
     * <p>取 48 是质量与代价的折中：更小会丢掉小字号的笔画细节，更大则位图变宽、
     * 图集容量下降，而 SDF 的质量在大尺寸下并不会有明显提升。
     */
    public static final int EM_SIZE = 48;

    /** 字体。 */
    private final FontFile font;

    /** 光栅化时的 em 高度（像素）。 */
    private final int pixelHeight;

    /** 包围盒查询的复用暂存。 */
    private final int[] boxScratch = new int[4];

    /** 用默认的 {@link #EM_SIZE} 构造。 */
    public GlyphRasterizer(FontFile font) {
        this(font, EM_SIZE);
    }

    /**
     * 指定 em 高度构造。
     *
     * @param font        字体
     * @param pixelHeight 光栅化时的 em 高度（像素），必须为正
     * @throws IllegalArgumentException 参数非法时
     */
    public GlyphRasterizer(FontFile font, int pixelHeight) {
        if (font == null) {
            throw new IllegalArgumentException("font 不能为 null");
        }
        if (pixelHeight <= 0) {
            throw new IllegalArgumentException("pixelHeight 必须为正，实际为 " + pixelHeight);
        }
        this.font = font;
        this.pixelHeight = pixelHeight;
    }

    /**
     * 返回光栅化时使用的 em 高度（像素）。
     *
     * <p>{@code Gc} 用它把字号换算成"相对本尺寸的缩放"：
     * {@code scale = fontSize / rasterizer.pixelHeight()}。
     *
     * @return em 高度（像素）
     */
    public int pixelHeight() {
        return pixelHeight;
    }

    /**
     * 光栅化一个字形。
     *
     * @param glyphIndex 字形索引（来自 {@link FontFile#glyphIndex(int)}）
     * @return 距离场位图；字形没有轮廓（空格等）时返回宽高为 0 的空位图
     */
    public GlyphBitmap rasterize(int glyphIndex) {
        float scale = font.scaleForPixelHeight(pixelHeight);
        font.glyphPixelBox(glyphIndex, scale, boxScratch);
        int x0 = boxScratch[0];
        int y0 = boxScratch[1];
        int x1 = boxScratch[2];
        int y1 = boxScratch[3];

        // 推进宽度也在这里算：它是 em 像素，与字号无关（字号缩放在发射顶点时才乘）。
        // 放在 GlyphSlot 里预乘字号是错的——同一个槽位会被不同字号复用。
        float advance = font.advance(glyphIndex) * scale;

        int width = x1 - x0;
        int height = y1 - y0;
        if (width <= 0 || height <= 0) {
            // 空字形（空格、以及度量上退化的字形）：只让笔前进，不产生位图。
            // 这是正常情况而不是错误。
            return new GlyphBitmap(0, 0, 0, 0, advance, new byte[0]);
        }

        byte[] coverage = new byte[width * height];
        font.makeGlyphBitmap(glyphIndex, scale, width, height, coverage);
        byte[] sdf = SdfGenerator.generate(coverage, width, height);

        int paddedWidth = width + 2 * SdfGenerator.SPREAD;
        int paddedHeight = height + 2 * SdfGenerator.SPREAD;
        // 距离场在位图四周各外扩了 SPREAD 像素，所以相对基线的偏移要各减去 SPREAD。
        // 纵偏移减去之后只会更负——字形在基线之上，而本项目的 y 向下。
        // 漏减的表现是字形整体往右下挪 8 个 em 像素，且边缘被裁——"看起来只是位置偏了"。
        return new GlyphBitmap(paddedWidth, paddedHeight,
                x0 - SdfGenerator.SPREAD,
                y0 - SdfGenerator.SPREAD,
                advance, sdf);
    }
}
