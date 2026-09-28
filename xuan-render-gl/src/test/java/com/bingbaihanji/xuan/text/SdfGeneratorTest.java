package com.bingbaihanji.xuan.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SdfGeneratorTest {

    /** 造一张 width×height 的覆盖度位图，inside 为 true 的像素取 255，其余 0。 */
    private static byte[] coverage(int width, int height, boolean[][] inside) {
        byte[] c = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (inside[y][x]) {
                    c[y * width + x] = (byte) 255;
                }
            }
        }
        return c;
    }

    /**
     * 把「到边界的欧氏距离（外部为正）」换算成期望的编码值。
     * 与实现里的编码式子是同一个，但测试里独立写一遍——这样实现改了式子会被发现。
     */
    private static int expectedOutside(double distance) {
        float t = 0.5f - (float) distance / (2f * SdfGenerator.SPREAD);
        return Math.round(Math.max(0f, Math.min(1f, t)) * 255f);
    }

    private static int expectedInside(double distance) {
        float t = 0.5f + (float) distance / (2f * SdfGenerator.SPREAD);
        return Math.round(Math.max(0f, Math.min(1f, t)) * 255f);
    }

    private static int at(byte[] sdf, int stride, int x, int y) {
        return sdf[y * stride + x] & 0xFF;
    }

    @Test
    void 输出尺寸比输入每边多一个spread() {
        boolean[][] inside = new boolean[4][4];
        inside[1][1] = true;
        byte[] sdf = SdfGenerator.generate(coverage(4, 4, inside), 4, 4);
        int stride = 4 + 2 * SdfGenerator.SPREAD;
        assertEquals(stride * stride, sdf.length);
    }

    @Test
    void 边界相邻的像素编码接近一半() {
        boolean[][] inside = new boolean[4][4];
        inside[1][1] = true;
        byte[] sdf = SdfGenerator.generate(coverage(4, 4, inside), 4, 4);
        int stride = 4 + 2 * SdfGenerator.SPREAD;

        // (1,1) 是内部像素，它自己到内部的距离是 0、到外部的距离是 1
        assertEquals(expectedInside(1.0), at(sdf, stride, 1 + SdfGenerator.SPREAD, 1 + SdfGenerator.SPREAD));
        // 紧邻它左侧的像素在外部，到边界的距离是 1
        assertEquals(expectedOutside(1.0), at(sdf, stride, 0 + SdfGenerator.SPREAD, 1 + SdfGenerator.SPREAD));
    }

    @Test
    void 对角方向用的是欧氏距离而不是切比雪夫() {
        boolean[][] inside = new boolean[4][4];
        inside[1][1] = true;
        byte[] sdf = SdfGenerator.generate(coverage(4, 4, inside), 4, 4);
        int stride = 4 + 2 * SdfGenerator.SPREAD;

        // 斜对角 (2,2) 在外部，到最近内部像素 (1,1) 的欧氏距离是 sqrt(2)≈1.4142
        int diagonal = at(sdf, stride, 2 + SdfGenerator.SPREAD, 2 + SdfGenerator.SPREAD);
        assertEquals(expectedOutside(Math.sqrt(2)), diagonal);

        // 切比雪夫距离会把 1.4142 当成 1，得到下面这个值。两者必须不同，
        // 否则这条断言就区分不了「真欧氏距离」与「八邻域距离变换」。
        assertNotEquals(expectedOutside(1.0), diagonal,
                "斜对角的值与正交方向相同，说明用的是切比雪夫距离而不是欧氏距离");
    }

    @Test
    void 内部深处饱和到255() {
        boolean[][] inside = new boolean[20][20];
        for (boolean[] row : inside) {
            java.util.Arrays.fill(row, true);
        }
        byte[] sdf = SdfGenerator.generate(coverage(20, 20, inside), 20, 20);
        int stride = 20 + 2 * SdfGenerator.SPREAD;
        // 中心点离边界远超过 SPREAD
        assertEquals(255, at(sdf, stride, 10 + SdfGenerator.SPREAD, 10 + SdfGenerator.SPREAD));
    }

    @Test
    void 外部远处饱和到0() {
        boolean[][] inside = new boolean[20][20];
        inside[10][10] = true;
        byte[] sdf = SdfGenerator.generate(coverage(20, 20, inside), 20, 20);
        int stride = 20 + 2 * SdfGenerator.SPREAD;
        assertEquals(0, at(sdf, stride, 0, 0), "外扩出来的边角应当饱和到 0");
    }

    @Test
    void 全空输入得到全零() {
        byte[] sdf = SdfGenerator.generate(new byte[4 * 4], 4, 4);
        for (byte b : sdf) {
            assertEquals(0, b & 0xFF);
        }
    }

    @Test
    void 全满输入得到全255() {
        // 外扩的那一圈仍在字形外，只有内部饱和到 255；检查正中心即可。
        //
        // 边长必须够大才谈得上「饱和」：正中心到外扩圈的距离是 size/2，
        // 而编码在 SPREAD 像素外才饱和，因此 size 至少要 2*SPREAD。
        // 用 4×4 的话这个距离只有 2，远小于 SPREAD，中心根本到不了 255。
        //
        // 这里取 4*SPREAD 而不是刚好 2*SPREAD：后者中心恰好落在饱和临界点上
        // （t = 0.5 + SPREAD/(2*SPREAD) = 1.0），断言会变成"刚好饱和"，
        // 编码尺度稍有偏差仍然能过。留出一整个 SPREAD 的余量，
        // 断言才真正钉住"深处必然饱和"。
        int size = 4 * SdfGenerator.SPREAD;
        byte[] cover = new byte[size * size];
        java.util.Arrays.fill(cover, (byte) 255);
        byte[] sdf = SdfGenerator.generate(cover, size, size);
        int stride = size + 2 * SdfGenerator.SPREAD;
        int center = size / 2 + SdfGenerator.SPREAD;
        assertEquals(255, at(sdf, stride, center, center));
    }
}
