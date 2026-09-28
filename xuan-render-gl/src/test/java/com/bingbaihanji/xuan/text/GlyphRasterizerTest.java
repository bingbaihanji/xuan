package com.bingbaihanji.xuan.text;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GlyphRasterizer} 的单测：字号 → 覆盖度 → 距离场的完整链路。
 *
 * <p>重点在<strong>几何换算</strong>：包围盒、外扩、偏移。这些错了都不会报错，
 * 只会让字形画歪、被裁、或者跑到基线下面去。
 *
 * <p>与 {@code FontFileTest} 一样，本类会加载 stb 的本地库。
 */
class GlyphRasterizerTest {

    private static FontFile font;
    private static GlyphRasterizer rasterizer;

    @BeforeAll
    static void 准备() {
        font = FontFile.loadClasspath(FontFile.DEFAULT_RESOURCE);
        rasterizer = new GlyphRasterizer(font);
    }

    @AfterAll
    static void 收尾() {
        if (font != null) {
            font.dispose();
        }
    }

    /** 取字形在光栅化尺寸下的像素包围盒。 */
    private static int[] boxOf(int glyphIndex) {
        int[] box = new int[4];
        font.glyphPixelBox(glyphIndex, font.scaleForPixelHeight(GlyphRasterizer.EM_SIZE), box);
        return box;
    }

    @Test
    void 位图尺寸等于包围盒加两倍spread() {
        int glyph = font.glyphIndex('国');
        int[] box = boxOf(glyph);
        int expectedWidth = box[2] - box[0] + 2 * SdfGenerator.SPREAD;
        int expectedHeight = box[3] - box[1] + 2 * SdfGenerator.SPREAD;

        GlyphBitmap bitmap = rasterizer.rasterize(glyph);

        assertEquals(expectedWidth, bitmap.width(), "宽度少了外扩的那一圈，笔画会被裁掉");
        assertEquals(expectedHeight, bitmap.height(), "高度少了外扩的那一圈，笔画会被裁掉");
        assertEquals(bitmap.width() * bitmap.height(), bitmap.pixels().length);
    }

    @Test
    void 偏移量减去了spread且纵偏移为负() {
        int glyph = font.glyphIndex('国');
        int[] box = boxOf(glyph);

        GlyphBitmap bitmap = rasterizer.rasterize(glyph);

        // 距离场在位图四周各外扩 SPREAD 像素，所以"位图左上角"相对包围盒向左上移动了
        assertEquals(box[0] - SdfGenerator.SPREAD, bitmap.offsetX());
        assertEquals(box[1] - SdfGenerator.SPREAD, bitmap.offsetY());
        assertTrue(bitmap.offsetY() < 0,
                "汉字画在基线之上，而本项目的 y 向下——offsetY 必须是负数");
    }

    @Test
    void 空字形返回零尺寸位图() {
        // 空格：有推进宽度，但没有轮廓。这是正常情况而不是错误。
        int space = font.glyphIndex(' ');
        assertNotEquals(0, space,
                "simhei 里空格没有字形（回落成了 .notdef）——那说明这条测试选错了输入，"
                        + "换成 U+3000 全角空格再跑，并把结论报上来");

        GlyphBitmap bitmap = rasterizer.rasterize(space);

        assertEquals(0, bitmap.width());
        assertEquals(0, bitmap.height());
        assertEquals(0, bitmap.pixels().length);
        assertTrue(bitmap.advance() > 0f, "空格仍然要让笔前进");
    }

    @Test
    void 汉字的推进宽度约等于一个字号() {
        GlyphBitmap bitmap = rasterizer.rasterize(font.glyphIndex('国'));

        // 全角汉字的推进宽度就是 1 em，而 em 高度被光栅化成了 EM_SIZE 像素。
        // 容差取 10%：不同字体的全角宽度可能略有出入，但绝不可能差 20 倍
        // （漏乘 scale 时会得到字体单位的 ~1000，那时这条必然失败）。
        assertEquals(GlyphRasterizer.EM_SIZE, bitmap.advance(), GlyphRasterizer.EM_SIZE * 0.1f,
                "推进宽度约为一个字号——漏乘 scale 会让它变成字体单位（约 1000）");
    }
}
