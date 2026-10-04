package com.bingbaihanji.xuan.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TessellatorTest {

    /** 用鞋带公式计算三角形列表的总面积（取绝对值）。 */
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

    @Test
    void 三角形输入产生一个三角形() {
        Tessellator t = new Tessellator();
        t.tessellate(new float[]{0f, 0f, 10f, 0f, 0f, 10f}, 3);
        assertEquals(1, t.triangleCount());
        assertEquals(50f, area(t.triangles()), 1e-3f);
    }

    @Test
    void 凸四边形面积守恒() {
        Tessellator t = new Tessellator();
        t.tessellate(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4);
        assertEquals(100f, area(t.triangles()), 1e-3f);
    }

    @Test
    void 凹多边形面积守恒() {
        // L 形：外框 10x10 减去右上角 5x5
        Tessellator t = new Tessellator();
        float[] poly = {0f, 0f, 10f, 0f, 10f, 5f, 5f, 5f, 5f, 10f, 0f, 10f};
        t.tessellate(poly, 6);
        assertEquals(75f, area(t.triangles()), 1e-3f);
    }

    @Test
    void 凹多边形的三角形数不超过N减2() {
        Tessellator t = new Tessellator();
        float[] poly = {0f, 0f, 10f, 0f, 10f, 5f, 5f, 5f, 5f, 10f, 0f, 10f};
        t.tessellate(poly, 6);
        assertTrue(t.triangleCount() <= 4, "6 边形最多 4 个三角形，实际 " + t.triangleCount());
    }

    @Test
    void 顺时针与逆时针输入结果一致() {
        Tessellator a = new Tessellator();
        a.tessellate(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4);

        Tessellator b = new Tessellator();
        b.tessellate(new float[]{0f, 0f, 0f, 10f, 10f, 10f, 10f, 0f}, 4);

        assertEquals(a.triangleCount(), b.triangleCount());
        assertEquals(area(a.triangles()), area(b.triangles()), 1e-3f);
    }

    @Test
    void 退化输入不抛异常且产生零面积() {
        Tessellator t = new Tessellator();
        t.tessellate(new float[]{0f, 0f, 1f, 1f, 2f, 2f}, 3);
        assertEquals(0f, area(t.triangles()), 1e-3f);
    }

    @Test
    void 顶点数不足时不产生三角形() {
        Tessellator t = new Tessellator();
        t.tessellate(new float[]{0f, 0f, 1f, 1f}, 2);
        assertEquals(0, t.triangleCount());
    }

    @Test
    void rawTriangles返回内部数组而不是副本() {
        Tessellator t = new Tessellator();
        t.tessellate(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4);

        float[] raw = t.rawTriangles();
        assertSame(raw, t.rawTriangles(), "不得每次分配新数组，否则热路径省不掉复制");
        assertTrue(raw.length >= t.triangleCount() * 6,
                "内部数组长度只保证不小于有效数据长度");

        // 有效前缀与复制版逐位一致，供直接写顶点用
        float[] copy = t.triangles();
        assertEquals(copy.length, t.triangleCount() * 6);
        for (int i = 0; i < copy.length; i++) {
            assertEquals(copy[i], raw[i], 0f, "第 " + i + " 个 float 应与副本一致");
        }
    }

    @Test
    void rawTriangles在下次三角化后失效() {
        Tessellator t = new Tessellator();
        t.tessellate(new float[]{0f, 0f, 10f, 0f, 0f, 10f}, 3);
        int first = t.triangleCount();

        t.tessellate(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4);
        assertEquals(first, 1, "第一次应为单个三角形");
        assertEquals(2, t.triangleCount(), "第二次调用应清空上一次结果");
    }
}
