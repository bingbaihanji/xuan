package com.bingbaihanji.xuan.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    /** 把若干轮廓首尾拼成一块扁平数组：[轮廓 0 的顶点，轮廓 1 的顶点，...]。 */
    private static float[] concat(float[]... contours) {
        int total = 0;
        for (float[] c : contours) {
            total += c.length;
        }
        float[] all = new float[total];
        int at = 0;
        for (float[] c : contours) {
            System.arraycopy(c, 0, all, at, c.length);
            at += c.length;
        }
        return all;
    }

    // ------------------------------------------------------------------
    // tessellateContours：按包含关系分类（Gc.fillPath 走的入口）
    // ------------------------------------------------------------------

    /** 每条轮廓的起点顶点下标（顶点而不是 float）：每个都是 4 个顶点。 */
    private static int[] offsetsOf(int... contourCounts) {
        int[] offsets = new int[contourCounts.length];
        int at = 0;
        for (int i = 0; i < contourCounts.length; i++) {
            offsets[i] = at;
            at += contourCounts[i];
        }
        return offsets;
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
    void 按包含关系把内轮廓判成洞() {
        // 内正方形整个落在外正方形里 ⇒ 它是洞，不是又一块填充
        float[] outer = {0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f};
        float[] inner = {5f, 5f, 15f, 5f, 15f, 15f, 5f, 15f};

        Tessellator t = new Tessellator();
        t.tessellateContours(concat(outer, inner), offsetsOf(4, 4), new int[]{4, 4}, 2);

        assertEquals(400f - 100f, area(t.triangles()), 1e-2f);
    }

    @Test
    void 子路径的先后顺序与绕向都不影响分类() {
        // 洞写在**前面**（位置约定那一套会在这里看走眼），并且与外轮廓反向
        float[] outer = {0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f};
        float[] innerReversed = {5f, 5f, 5f, 15f, 15f, 15f, 15f, 5f};

        Tessellator t = new Tessellator();
        t.tessellateContours(concat(innerReversed, outer), offsetsOf(4, 4), new int[]{4, 4}, 2);

        assertEquals(400f - 100f, area(t.triangles()), 1e-2f);
    }

    @Test
    void 两个互不相交的外轮廓都被画出来() {
        // 谁也没包含谁 ⇒ 两块都是外轮廓。
        // 按位置约定分类（"第一个作外轮廓、其余作洞"）会把第二块当成洞，而它落在
        // 外轮廓之外——那属于 tessellateWithHoles 契约里的病态输入，会静默丢掉一块面积。
        float[] a = {0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f};
        float[] b = {50f, 50f, 60f, 50f, 60f, 60f, 50f, 60f};

        Tessellator t = new Tessellator();
        t.tessellateContours(concat(a, b), offsetsOf(4, 4), new int[]{4, 4}, 2);

        assertEquals(100f + 100f, area(t.triangles()), 1e-2f);
    }

    @Test
    void 洞里的岛是独立的外轮廓() {
        // 40x40 外轮廓、30x30 的洞、洞里 10x10 的岛（嵌套深度 0/1/2）
        float[] outer = {0f, 0f, 40f, 0f, 40f, 40f, 0f, 40f};
        float[] hole = {5f, 5f, 35f, 5f, 35f, 35f, 5f, 35f};
        float[] island = {15f, 15f, 25f, 15f, 25f, 25f, 15f, 25f};

        Tessellator t = new Tessellator();
        t.tessellateContours(concat(outer, hole, island), offsetsOf(4, 4, 4), new int[]{4, 4, 4}, 3);

        assertEquals(1600f - 900f + 100f, area(t.triangles()), 1e-2f);
    }

    @Test
    void 一个洞配多个外轮廓时洞只挖在包含它的那一块上() {
        // 左边一块 20x20 带一个洞，右边另有一块 10x10 的实心块
        float[] outer = {0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f};
        float[] hole = {5f, 5f, 15f, 5f, 15f, 15f, 5f, 15f};
        float[] solid = {50f, 0f, 60f, 0f, 60f, 10f, 50f, 10f};

        Tessellator t = new Tessellator();
        t.tessellateContours(concat(outer, hole, solid), offsetsOf(4, 4, 4), new int[]{4, 4, 4}, 3);

        assertEquals(400f - 100f + 100f, area(t.triangles()), 1e-2f);
    }

    @Test
    void 单轮廓与普通三角化等价() {
        float[] square = {0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f};

        Tessellator a = new Tessellator();
        a.tessellate(square, 4);

        Tessellator b = new Tessellator();
        b.tessellateContours(square, new int[]{0}, new int[]{4}, 1);

        assertEquals(area(a.triangles()), area(b.triangles()), 1e-3f);
    }

    @Test
    void 顶点数不足的轮廓被跳过() {
        // 一条 2 点的"轮廓"既不该被画出来，也不该影响外轮廓的判定
        float[] outer = {0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f};
        float[] degenerate = {5f, 5f, 10f, 5f};

        Tessellator t = new Tessellator();
        t.tessellateContours(concat(outer, degenerate), offsetsOf(4, 2), new int[]{4, 2}, 2);

        assertEquals(400f, area(t.triangles()), 1e-2f);
    }

    @Test
    void 无洞时与普通三角化等价() {
        Tessellator a = new Tessellator();
        a.tessellate(new float[]{0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f}, 4);

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
