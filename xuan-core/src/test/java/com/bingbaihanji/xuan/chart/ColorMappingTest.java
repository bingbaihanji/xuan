package com.bingbaihanji.xuan.chart;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ColorMapping} 的单测：**纯计算**。
 *
 * <p>断言落在三样东西上：LUT 的端点、单调性、越界钳位。
 * 通道顺序（RGBA 还是 ARGB）错了不会报错，只会让整张热力图红蓝互换——
 * 那种"看起来像是配色选错了"的 bug 最难往"通道序写反"上想。
 */
class ColorMappingTest {

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int RED = 0xFFFF0000;

    private static int channel(byte[] lut, int index, int offset) {
        return lut[index * 4 + offset] & 0xFF;
    }

    @Test
    void LUT长度是1024字节且按RGBA排列() {
        byte[] lut = ColorMapping.GRAYSCALE.toLut();
        assertEquals(ColorMapping.LUT_SIZE * 4, lut.length);
        assertEquals(1024, lut.length, "1×256 的 RGBA 就是 1024 个字节");
        // 灰度配色的两端：下标 0 是黑、下标 255 是白（两者都是不透明的）
        assertEquals(0, channel(lut, 0, 0), "第 0 个纹素的 R");
        assertEquals(255, channel(lut, 0, 3), "第 0 个纹素的 A");
        assertEquals(255, channel(lut, 255, 0));
        assertEquals(255, channel(lut, 255, 3));
    }

    @Test
    void LUT的端点与首末色标一致() {
        ColorMapping mapping = ColorMapping.of(
                new ColorMapping.Stop(0.0, BLACK), new ColorMapping.Stop(1.0, RED));
        byte[] lut = mapping.toLut();
        assertEquals(0x00, channel(lut, 0, 0), "起点应当是黑色");
        assertEquals(0xFF, channel(lut, 255, 0), "终点应当是红色");
        assertEquals(0x00, channel(lut, 255, 1), "通道序写反的话这里会变成 255——"
                + "画面看起来只是「配色不对」，很难往通道序上想");
    }

    @Test
    void 灰度配色的通道单调不减() {
        byte[] lut = ColorMapping.GRAYSCALE.toLut();
        for (int i = 1; i < ColorMapping.LUT_SIZE; i++) {
            assertTrue(channel(lut, i, 0) >= channel(lut, i - 1, 0),
                    "灰度在纹素 " + i + " 处回退了：" + channel(lut, i - 1, 0)
                            + " → " + channel(lut, i, 0));
            assertEquals(channel(lut, i, 0), channel(lut, i, 1), "灰度配色的 R/G/B 必须相等");
            assertEquals(channel(lut, i, 0), channel(lut, i, 2));
            assertEquals(255, channel(lut, i, 3), "灰度配色的 alpha 恒为不透明");
        }
    }

    @Test
    void 越界输入钳位到端点颜色() {
        ColorMapping mapping = ColorMapping.of(
                new ColorMapping.Stop(0.2, BLACK), new ColorMapping.Stop(0.8, WHITE));
        assertEquals(BLACK, mapping.colorAt(-1000.0), "低于 0 必须钳到首个色标");
        assertEquals(BLACK, mapping.colorAt(0.0));
        assertEquals(WHITE, mapping.colorAt(1.0));
        assertEquals(WHITE, mapping.colorAt(1.0000000000000002),
                "数据侧的四舍五入很容易给出略大于 1 的值，钳位是唯一挡住它的东西");
        assertEquals(WHITE, mapping.colorAt(Double.MAX_VALUE));
    }

    @Test
    void 中点等于两个色标的线性插值() {
        ColorMapping mapping = ColorMapping.of(
                new ColorMapping.Stop(0.0, 0xFF000000), new ColorMapping.Stop(1.0, 0xFFFFFFFF));
        int middle = mapping.colorAt(0.5);
        assertEquals(128, (middle >>> 16) & 0xFF, 0.5,
                "0 到 255 的中点是 127.5，四舍五入到 128");
        assertEquals(128, middle & 0xFF);
        assertEquals(255, (middle >>> 24) & 0xFF);

        // 三个色标、不等距：位置 0.5 落在第二段
        ColorMapping three = ColorMapping.of(
                new ColorMapping.Stop(0.0, 0xFF000000),
                new ColorMapping.Stop(0.5, 0xFF808080),
                new ColorMapping.Stop(1.0, 0xFFFFFFFF));
        assertEquals(0x80, (three.colorAt(0.5) >>> 16) & 0xFF, 0.5, "色标点上的值必须精确");
        assertEquals(0xC0, (three.colorAt(0.75) >>> 16) & 0xFF, 0.5,
                "0.5 到 1.0 的中点是 0x80 与 0xFF 的平均");
    }

    @Test
    void 色标位置必须非递减且落在0到1() {
        assertThrows(IllegalArgumentException.class, () -> ColorMapping.of(
                new ColorMapping.Stop(0.8, BLACK), new ColorMapping.Stop(0.2, WHITE)),
                "位置倒序必须构造时就拒绝：插值时区间长度为负，结果是一段乱跳的颜色");
        assertThrows(IllegalArgumentException.class,
                () -> ColorMapping.of(new ColorMapping.Stop(-0.1, BLACK)));
        assertThrows(IllegalArgumentException.class,
                () -> ColorMapping.of(new ColorMapping.Stop(1.1, BLACK)));
        assertThrows(IllegalArgumentException.class, () -> ColorMapping.of(),
                "一个色标都没有，整张 LUT 无从谈起");
    }

    @Test
    void 只有一个色标时整张LUT是那个颜色() {
        ColorMapping mapping = ColorMapping.of(new ColorMapping.Stop(0.5, RED));
        byte[] lut = mapping.toLut();
        for (int i = 0; i < ColorMapping.LUT_SIZE; i++) {
            assertEquals(0xFF, channel(lut, i, 0));
            assertEquals(0x00, channel(lut, i, 1));
            assertEquals(0x00, channel(lut, i, 2));
            assertEquals(0xFF, channel(lut, i, 3));
        }
        assertEquals(RED, mapping.colorAt(0.0));
        assertEquals(RED, mapping.colorAt(1.0));
    }
}
