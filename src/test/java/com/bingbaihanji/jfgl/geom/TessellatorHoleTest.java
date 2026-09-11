package com.bingbaihanji.jfgl.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TessellatorHoleTest {

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
    void 带方形洞的方形面积为差集() {
        Tessellator t = new Tessellator();
        float[] outer = {0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f};
        float[] inner = {5f, 5f, 5f, 15f, 15f, 15f, 15f, 5f};

        t.tessellateWithHoles(outer, 4, new float[][]{inner}, new int[]{4});

        assertEquals(400f - 100f, area(t.triangles()), 1e-2f);
    }

    @Test
    void 无洞时与普通三角化等价() {
        Tessellator a = new Tessellator();
        a.tessellate(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4, false);

        Tessellator b = new Tessellator();
        b.tessellateWithHoles(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4,
                new float[0][], new int[0]);

        assertEquals(area(a.triangles()), area(b.triangles()), 1e-3f);
    }

    @Test
    void 洞完全在内部时被正确挖去() {
        Tessellator t = new Tessellator();
        float[] outer = {0f, 0f, 30f, 0f, 30f, 30f, 0f, 30f};
        float[] inner = {10f, 10f, 10f, 20f, 20f, 20f, 20f, 10f};

        t.tessellateWithHoles(outer, 4, new float[][]{inner}, new int[]{4});

        assertEquals(900f - 100f, area(t.triangles()), 1e-2f);
    }

    @Test
    void 两个洞都被正确挖去() {
        Tessellator t = new Tessellator();
        float[] outer = {0f, 0f, 40f, 0f, 40f, 20f, 0f, 20f};
        float[] h1 = {5f, 5f, 5f, 15f, 15f, 15f, 15f, 5f};
        float[] h2 = {25f, 5f, 25f, 15f, 35f, 15f, 35f, 5f};

        t.tessellateWithHoles(outer, 4, new float[][]{h1, h2}, new int[]{4, 4});

        assertEquals(800f - 100f - 100f, area(t.triangles()), 1e-2f);
    }
}
