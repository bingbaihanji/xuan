package com.bingbaihanji.jfgl.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrokeGeneratorTest {

    private static float area(float[] tris) {
        float sum = 0f;
        for (int i = 0; i < tris.length; i += 6) {
            float x0 = tris[i], y0 = tris[i + 1];
            float x1 = tris[i + 2], y1 = tris[i + 3];
            float x2 = tris[i + 4], y2 = tris[i + 5];
            sum += Math.abs((x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)) * 0.5f;
        }
        return sum;
    }

    private static float minX(float[] tris) {
        float m = Float.MAX_VALUE;
        for (int i = 0; i < tris.length; i += 2) m = Math.min(m, tris[i]);
        return m;
    }

    private static float maxX(float[] tris) {
        float m = -Float.MAX_VALUE;
        for (int i = 0; i < tris.length; i += 2) m = Math.max(m, tris[i]);
        return m;
    }

    private static float minY(float[] tris) {
        float m = Float.MAX_VALUE;
        for (int i = 1; i < tris.length; i += 2) m = Math.min(m, tris[i]);
        return m;
    }

    private static float maxY(float[] tris) {
        float m = -Float.MAX_VALUE;
        for (int i = 1; i < tris.length; i += 2) m = Math.max(m, tris[i]);
        return m;
    }

    @Test
    void 水平线段描边面积为长乘宽() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        assertEquals(20f, area(g.triangles()), 1e-2f);
    }

    @Test
    void 线宽体现在垂直方向的包围盒上() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        // BUTT 端点不向外延伸，故 x 方向恰好是线段本身
        assertEquals(0f, minX(g.triangles()), 1e-2f);
        assertEquals(10f, maxX(g.triangles()), 1e-2f);
        // 宽度 4 意味着上下各偏 2
        assertEquals(-2f, minY(g.triangles()), 1e-2f);
        assertEquals(2f, maxY(g.triangles()), 1e-2f);
    }

    @Test
    void 方形端点向外延伸半个线宽() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.SQUARE, StrokeGenerator.Join.MITER, 4f);
        assertEquals(-2f, minX(g.triangles()), 1e-2f, "方端点应向左延伸半个线宽");
        assertEquals(12f, maxX(g.triangles()), 1e-2f, "方端点应向右延伸半个线宽");
    }

    @Test
    void 圆形端点比平端点面积更大() {
        StrokeGenerator butt = new StrokeGenerator();
        butt.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);

        StrokeGenerator round = new StrokeGenerator();
        round.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.ROUND, StrokeGenerator.Join.MITER, 4f, 16);

        // 圆端点在两端各加一个半径 2 的半圆，面积约 +4π ≈ +12.6
        assertEquals(area(butt.triangles()) + 4f * (float) Math.PI,
                area(round.triangles()), 0.5f);
    }

    @Test
    void 折线拐角产生连续覆盖() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f}, 3, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        assertTrue(area(g.triangles()) > 20f, "折线应产生大于单段的面积");
    }

    @Test
    void 闭合路径无端点封口() {
        StrokeGenerator open = new StrokeGenerator();
        open.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);

        StrokeGenerator closed = new StrokeGenerator();
        closed.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4, true, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);

        assertTrue(area(closed.triangles()) > area(open.triangles()));
    }

    @Test
    void 单点或空输入不产生三角形() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{1f, 1f}, 1, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        assertEquals(0, g.triangleCount());
    }

    @Test
    void 零宽描边不产生三角形() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 0f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        assertEquals(0, g.triangleCount());
    }
}
