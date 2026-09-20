package com.bingbaihanji.jfgl.text;

/**
 * 把 {@link FontFile} 与 {@link GlyphRasterizer} 串成一个 {@link GlyphSource}，
 * 外加两个 {@code Gc} 直接要用的度量入口。
 *
 * <p><strong>key 就是字形索引。</strong>本期只有一个 em 尺寸桶
 * （{@link GlyphRasterizer#EM_SIZE}），因此图集按字形缓存即可；
 * 将来若加"小字号走独立位图桶"，key 才需要变成 (字形, 桶)。
 *
 * <p>为什么要有这个类、而不是让 {@code Gc} 自己把两者串起来：
 * <ul>
 *   <li>让 {@code Gc} 只认识一个对象，而不是三个；</li>
 *   <li>{@link #advancePixels} 走的是<strong>字体度量</strong>而不是图集，
 *       因此 {@code measureText} 不必为了量一个宽度而生成字形——
 *       这是"测量不该有副作用"这条老规矩，也是 {@code measureText} 能用于布局的原因。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * <p>非线程安全，只在 GL 线程上使用。
 */
public final class FontGlyphSource implements GlyphSource {

    /** 字体。 */
    private final FontFile font;

    /** 光栅化器。 */
    private final GlyphRasterizer rasterizer;

    /**
     * 构造。
     *
     * @param font       字体
     * @param rasterizer 光栅化器，必须与 {@code font} 是同一份字体
     * @throws IllegalArgumentException 任一参数为 {@code null} 时
     */
    public FontGlyphSource(FontFile font, GlyphRasterizer rasterizer) {
        if (font == null) {
            throw new IllegalArgumentException("font 不能为 null");
        }
        if (rasterizer == null) {
            throw new IllegalArgumentException("rasterizer 不能为 null");
        }
        this.font = font;
        this.rasterizer = rasterizer;
    }

    @Override
    public GlyphBitmap pixelsFor(int key) {
        return rasterizer.rasterize(key);
    }

    /**
     * 返回字体本身，供调用方查字形索引。
     *
     * @return 字体
     */
    public FontFile font() {
        return font;
    }

    /**
     * 返回码点对应的字形索引。
     *
     * @param codepoint Unicode 码点
     * @return 字形索引；0 表示 .notdef（字体里没有这个码点），照常画它，不要跳过
     */
    public int glyphIndex(int codepoint) {
        return font.glyphIndex(codepoint);
    }

    /**
     * 返回某字号下字形的推进宽度（像素）。
     *
     * <p>直接从字体度量算，<strong>不生成字形、不碰图集</strong>。
     *
     * @param glyphIndex 字形索引
     * @param fontSize   字号（像素）
     * @return 推进宽度（像素）
     */
    public float advancePixels(int glyphIndex, float fontSize) {
        return font.advance(glyphIndex) * font.scaleForPixelHeight(fontSize);
    }

    /**
     * 返回光栅化时使用的 em 高度（像素）。
     *
     * <p>发射顶点时用它把字号换算成缩放：{@code scale = fontSize / rasterizer.pixelHeight()}。
     *
     * @return em 高度（像素）
     */
    public int pixelHeight() {
        return rasterizer.pixelHeight();
    }
}
