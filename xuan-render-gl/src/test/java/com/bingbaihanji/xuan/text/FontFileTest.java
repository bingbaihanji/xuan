package com.bingbaihanji.xuan.text;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FontFile} 的单测。
 *
 * <p><strong>本类会加载 stb 的本地库。</strong>加载不了的话整个类都会报错——
 * 那是有意的：与其假装测过，不如让它在最显眼的地方失败，然后按实施计划里
 * Step 1 的处置办法把 stb 调用隔离到薄接口后面。
 */
class FontFileTest {

    private static FontFile font;

    @BeforeAll
    static void 加载字体() {
        font = FontFile.loadClasspath(FontFile.DEFAULT_RESOURCE);
    }

    @AfterAll
    static void 释放字体() {
        if (font != null) {
            font.dispose();
        }
    }

    @Test
    void stb原生库能在surefire环境加载并解析字体() {
        // 单独再加载一份并释放：既证明"能加载"，也证明"能释放"，而不动共享实例。
        FontFile probe = assertDoesNotThrow(
                () -> FontFile.loadClasspath(FontFile.DEFAULT_RESOURCE),
                "stb 的本地库在 surefire 里没能加载，或字体资源解析失败——"
                        + "见实施计划 Task 4 Step 1 的处置办法");
        assertTrue(probe.scaleForPixelHeight(48f) > 0f, "能算出度量才算真的加载成功");

        probe.dispose();
        probe.dispose();     // 幂等：重复释放不该重复 memFree
    }

    @Test
    void 已知字符的字形索引非零() {
        // 0 恒定是 .notdef。汉字与拉丁字母都必须拿到真实字形。
        assertTrue(font.glyphIndex('A') > 0, "拉丁字母 A 的字形索引");
        assertTrue(font.glyphIndex('中') > 0, "汉字「中」的字形索引");
        assertTrue(font.glyphIndex('0') > 0, "数字 0 的字形索引");
    }

    @Test
    void 字体里没有的码点回落到notdef() {
        // 规格 §5.6：拿到 0 不代表"跳过"，而是"照常画 .notdef（豆腐块）"。
        // 静默跳过的表现是"这段文字少了几个字"，用户会以为是排版 bug。
        assertEquals(0, font.glyphIndex(0x10FFFF),
                "非字符码点不在 simhei 里，必须回落成 0，调用方据此画 .notdef");
    }

    @Test
    void 推进宽度随像素高度线性放大() {
        int glyph = font.glyphIndex('中');
        float at48 = font.advance(glyph) * font.scaleForPixelHeight(48f);
        float at96 = font.advance(glyph) * font.scaleForPixelHeight(96f);

        assertTrue(at48 > 0f, "全角汉字的推进宽度必须为正");
        // 字号翻倍，推进宽度也翻倍——这正是"字号缩放靠乘法"这条设计的前提。
        assertEquals(2f, at96 / at48, 1e-3f,
                "scaleForPixelHeight 必须与像素高度成正比，否则字号与实际尺寸会对不上");
    }

    @Test
    void 释放之后再使用会抛异常() {
        FontFile temp = FontFile.loadClasspath(FontFile.DEFAULT_RESOURCE);
        temp.dispose();

        // 释放后字体字节已经被 memFree，再交给 stb 就是 use-after-free：
        // 它不会报错，只会读到垃圾或者直接崩进程。必须在 Java 侧挡住。
        assertThrows(IllegalStateException.class, () -> temp.glyphIndex('A'));
        assertThrows(IllegalStateException.class, () -> temp.scaleForPixelHeight(48f));
        assertThrows(IllegalStateException.class, () -> temp.advance(1));
    }
}
