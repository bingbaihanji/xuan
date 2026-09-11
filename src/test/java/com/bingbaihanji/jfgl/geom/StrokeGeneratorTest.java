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

    /**
     * 判断点 (px,py) 是否落在至少一个三角形内部（含边界）。
     *
     * <p>面积断言对三角形的位置和朝向是盲的——接头补到凹侧、圆端点画向内侧，
     * 面积都不会变。凡是"补的地方对不对"的问题，必须用覆盖性来判断。
     */
    private static boolean covers(float[] tris, float px, float py) {
        for (int i = 0; i < tris.length; i += 6) {
            float ax = tris[i], ay = tris[i + 1];
            float bx = tris[i + 2], by = tris[i + 3];
            float cx = tris[i + 4], cy = tris[i + 5];
            float d1 = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
            float d2 = (cx - bx) * (py - by) - (cy - by) * (px - bx);
            float d3 = (ax - cx) * (py - cy) - (ay - cy) * (px - cx);
            boolean neg = d1 < -1e-6f || d2 < -1e-6f || d3 < -1e-6f;
            boolean pos = d1 > 1e-6f || d2 > 1e-6f || d3 > 1e-6f;
            if (!(neg && pos)) return true;
        }
        return false;
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

    @Test
    void 圆角接头填住外侧拐角() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f}, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.ROUND, 4f, 16);
        // 拐点在 (10,0)，半线宽 2，凸侧在 -y 方向：(11.2,-1.2) 距拐点约 1.70，
        // 落在半径 2 的外侧圆弧内（内接多边形在 45 度处的边界约 1.96），
        // 但不在任何一段的四边形内。接头若补到凹侧，此点必然无覆盖。
        assertTrue(covers(g.triangles(), 11.2f, -1.2f), "圆角接头应补在凸侧，填住外侧拐角");
    }

    @Test
    void miter超限回退斜接且阈值内保留尖角() {
        // 转向约 170 度，miter 长度比 1/sin(5°) ≈ 11.5
        double a = Math.toRadians(170);
        float ux = (float) Math.cos(a), uy = (float) Math.sin(a);
        float[] turn = {0f, 0f, 10f, 0f, 10f + 10f * ux, 10f * uy};

        StrokeGenerator bevel = new StrokeGenerator();
        bevel.stroke(turn, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        // 阈值 4 下回退为斜接：最右只到拐点偏移 o2 的 x ≈ 10.35
        assertTrue(maxX(bevel.triangles()) < 12f,
                "超过 miter limit 应回退为斜接，实际 maxX=" + maxX(bevel.triangles()));

        StrokeGenerator miter = new StrokeGenerator();
        miter.stroke(turn, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 20f);
        // 阈值 20 下保留尖角：尖端在 x ≈ 32.9
        assertTrue(maxX(miter.triangles()) > 20f,
                "阈值内应保留 miter 尖端，实际 maxX=" + maxX(miter.triangles()));
    }

    @Test
    void 圆端点向两端各伸出半个线宽() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.ROUND, StrokeGenerator.Join.MITER, 4f, 16);
        // 半圆向外：x 方向应超出线段本身各 2；若半圆画向内侧则退化为 [0,10]
        assertEquals(-2f, minX(g.triangles()), 1e-2f, "圆端点应向左伸出半个线宽");
        assertEquals(12f, maxX(g.triangles()), 1e-2f, "圆端点应向右伸出半个线宽");
    }

    @Test
    void 重复顶点等价于去掉重复点() {
        StrokeGenerator dup = new StrokeGenerator();
        dup.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 0f, 10f, 10f}, 4, false, 4f,
                StrokeGenerator.Cap.ROUND, StrokeGenerator.Join.MITER, 4f, 16);
        StrokeGenerator clean = new StrokeGenerator();
        clean.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f}, 3, false, 4f,
                StrokeGenerator.Cap.ROUND, StrokeGenerator.Join.MITER, 4f, 16);
        // 重复顶点既不能留下缺口（接头丢失），也不能丢掉端点封口（面积随之变小）
        assertEquals(area(clean.triangles()), area(dup.triangles()), 1e-3f,
                "重复顶点应与去掉重复点的结果等价");
    }
}
