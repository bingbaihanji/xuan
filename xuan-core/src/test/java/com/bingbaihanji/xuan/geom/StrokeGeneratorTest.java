package com.bingbaihanji.xuan.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrokeGeneratorTest {

    /** 把顶点位置编成 "x,y"（一位小数、`Locale.ROOT`），供"顶点集合恰好是这几个"这类断言用。 */
    private static String at(float[] t, int v) {
        return String.format(java.util.Locale.ROOT, "%.1f,%.1f", t[v * 2], t[v * 2 + 1]);
    }

    /**
     * 统计横向边距为 0 的顶点，返回它们的位置集合。
     *
     * <p>判据取**顶点集合**而不是"取几个采样点看"：接头就是那几个顶点，
     * 逐点比对才不会漏掉"只改了一处"。
     */
    private static java.util.TreeSet<String> zeroCrossVertices(StrokeGenerator g) {
        float[] t = g.triangles();
        float[] e = g.rawEdges();
        java.util.TreeSet<String> zero = new java.util.TreeSet<>();
        for (int v = 0; v < g.triangleCount() * 3; v++) {
            if (e[v * 2] == 0f) {
                zero.add(at(t, v));
            }
        }
        return zero;
    }

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
        for (int i = 0; i < tris.length; i += 2) {
            m = Math.min(m, tris[i]);
        }
        return m;
    }

    private static float maxX(float[] tris) {
        float m = -Float.MAX_VALUE;
        for (int i = 0; i < tris.length; i += 2) {
            m = Math.max(m, tris[i]);
        }
        return m;
    }

    private static float minY(float[] tris) {
        float m = Float.MAX_VALUE;
        for (int i = 1; i < tris.length; i += 2) {
            m = Math.min(m, tris[i]);
        }
        return m;
    }

    private static float maxY(float[] tris) {
        float m = -Float.MAX_VALUE;
        for (int i = 1; i < tris.length; i += 2) {
            m = Math.max(m, tris[i]);
        }
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
            if (!(neg && pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 点 (px,py) 被**多少个**已发出的三角形覆盖（含边界，与 {@link #covers} 同口径）。
     *
     * <p>`covers` 只回答"有没有"，回答不了"几层"。而"同一个地方被画了两遍"这件事
     * **恰恰是层数问题**：不透明时毫无症状，半透明时会叠加两次颜色。所以判"重复覆盖"
     * 必须数层数，不能只数有无。
     */
    private static int coverageCount(float[] tris, float px, float py) {
        int n = 0;
        for (int i = 0; i + 5 < tris.length; i += 6) {
            float ax = tris[i], ay = tris[i + 1];
            float bx = tris[i + 2], by = tris[i + 3];
            float cx = tris[i + 4], cy = tris[i + 5];
            float d1 = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
            float d2 = (cx - bx) * (py - by) - (cy - by) * (px - bx);
            float d3 = (ax - cx) * (py - cy) - (ay - cy) * (px - cx);
            boolean neg = d1 < -1e-6f || d2 < -1e-6f || d3 < -1e-6f;
            boolean pos = d1 > 1e-6f || d2 > 1e-6f || d3 > 1e-6f;
            if (!(neg && pos)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 断言所有顶点坐标都是有限值。
     *
     * <p>NaN/Infinity 会一路带进 VBO 并污染整个批次，而且它在面积断言里往往表现为
     * 一个"不算大也不算小"的数，不会被察觉，所以必须逐个坐标显式检查。
     */
    private static void assertAllFinite(float[] tris) {
        for (int i = 0; i < tris.length; i++) {
            assertTrue(Float.isFinite(tris[i]),
                    "顶点坐标不应出现非有限值：第 " + (i / 2) + " 个顶点 分量 " + (i % 2) + " = " + tris[i]);
        }
    }

    /** 统计横向边距**恰好为 0** 的顶点个数。 */
    private static int countZeroCross(StrokeGenerator g) {
        float[] e = g.rawEdges();
        int n = 0;
        for (int v = 0; v < g.triangleCount() * 3; v++) {
            if (e[v * 2] == 0f) {
                n++;
            }
        }
        return n;
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

    /**
     * 末点与起点重复的折线，按闭合描边时应与「去掉重复末点后的同一条折线」等价。
     *
     * <p>这条不变量决定了 `Flattener` 的产物能否直接交给闭合描边：Flattener 处理 `CLOSE`
     * 时会把子路径起点追加为末点，于是末点与起点重复、最后一段长度为零。若零长度段没被跳过，
     * 闭合处会多出一个退化三角形；若跳过之后接头落点算错，闭合尖角外侧会缺一块。
     * 两者都逃得过面积断言——面积对「补在哪一侧」是盲的——所以这里查的是覆盖性。
     */
    @Test
    void 末点重复起点时闭合描边与去重后等价() {
        float[] dedup = {0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f};
        float[] withDup = {0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f, 0f, 0f};

        StrokeGenerator a = new StrokeGenerator();
        a.stroke(dedup, 4, true, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);

        StrokeGenerator b = new StrokeGenerator();
        b.stroke(withDup, 5, true, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);

        assertEquals(a.triangleCount(), b.triangleCount(),
                "重复的零长度段应被跳过，三角形数量必须与去重后一致");
        assertEquals(area(a.triangles()), area(b.triangles()), 1e-3f);
        assertAllFinite(b.triangles());

        // 闭合接头必须仍然补在 (0,0) 角的外侧：线宽 2 时 miter 尖角伸到 (-1,-1)。
        assertTrue(covers(b.triangles(), -0.9f, -0.9f),
                "末点重复起点时，闭合处的接头必须仍补在 (0,0) 角外侧");

        // 反证：同一条折线按**开放**描边时，(0,0) 角是收尾处，两端只有平头封口，
        // 外侧不会补上。这条断言证明上面的覆盖性检查确实有区分力，而不是恒真。
        StrokeGenerator openSame = new StrokeGenerator();
        openSame.stroke(withDup, 5, false, 2f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        assertFalse(covers(openSame.triangles(), -0.9f, -0.9f),
                "开放描边不应覆盖 (0,0) 角外侧——否则上一条断言是恒真的");
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
    void 左转圆角接头填住外侧拐角() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f}, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.ROUND, 4f, 16);
        // 拐点在 (10,0)，半线宽 2，凸侧在 -y 方向。取该拐角 bevel 三角形
        // (10,0)-(10,-2)-(12,0) 的内心 (10+2-√2, -(2-√2)) = (10.586,-0.586)：
        // 到三边距离均为 0.586，是 covers() 容差 1e-6 的约 58 万倍，余量充足。
        // 注意不要取 (11.2,-1.2) 这类恰好落在扇形三角剖分边上的点——那里余量
        // 只有浮点 epsilon，一次细分段数改动就会变成 flaky。
        // 该点也不在任何一段的四边形内，因此只有接头能覆盖它。
        assertTrue(covers(g.triangles(), 10.586f, -0.586f), "圆角接头应补在凸侧，填住外侧拐角");
    }

    @Test
    void 右转圆角接头填住外侧拐角() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f, 10f, -10f}, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.ROUND, 4f, 16);
        // 左转用例的镜像：凸侧翻到 +y，取值同为 bevel 三角形的内心 (10.586, 0.586)
        assertTrue(covers(g.triangles(), 10.586f, 0.586f), "右转时接头同样应补在凸侧");
    }

    @Test
    void miter超限回退斜接且阈值内保留尖角() {        // 转向约 170 度，miter 长度比 1/sin(5°) ≈ 11.5
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

    /**
     * MITER 接头必须把"顶点与接头底边之间"那一片也覆盖上——它由**两个**三角形拼成：
     * 底边之外的尖角三角形，以及顶点到底边之间的那个三角形（后者正是 BEVEL 发的那个）。
     *
     * <p>曾经只发前者：直角处顶点内侧缺一块三角形（面积 = 半线宽²/2），线宽越大越明显
     * （线宽 20 的直角方块，四角各缺 50 px²，总面积实测 15800 而理想是 16000）。
     * 细线宽下几乎看不出来，所以这个缺陷活了很久——面积断言也拦不住它吗？
     * 拦得住，但容差把它盖过去了（差 1.25%）。
     *
     * <p>折线 `(-10,0) → (0,0) → (0,10)`、线宽 10（半线宽 5）：接头处理想描边是一个
     * 风筝形四边形 `(0,0)-(0,-5)-(5,-5)-(5,0)`。
     */
    @Test
    void 直角接头覆盖顶点与接头底边之间那一片() {
        float[] pts = {-10f, 0f, 0f, 0f, 0f, 10f};
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(pts, 3, false, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        float[] tris = g.triangles();

        // 顶点到底边之间那一块：到顶点 (0,0) 的距离分别是 √2、√8、√0.5，都小于半线宽 5，
        // 因此**理想描边必然覆盖**它们，与接头怎么实现无关。
        assertTrue(covers(tris, 1f, -1f), "顶点与接头底边之间 (1,-1) 必须被覆盖");
        assertTrue(covers(tris, 2f, -2f), "顶点与接头底边之间 (2,-2) 必须被覆盖");
        assertTrue(covers(tris, 0.5f, -0.5f), "顶点附近 (0.5,-0.5) 必须被覆盖");

        // 反证：越出接头尖角 (5,-5) 的点不该被覆盖，带外的点也不该——否则上面几条是恒真的。
        assertFalse(covers(tris, 6f, -6f), "接头尖角之外的 (6,-6) 不该被覆盖");
        assertFalse(covers(tris, -6f, 6f), "远离折线的 (-6,6) 不该被覆盖");

        // 与 BEVEL 的覆盖做对照：MITER 的覆盖必须**包含** BEVEL 的覆盖（还多出尖角）。
        // 这条不变量是"接头补全了"的完整表述，不依赖选点；逐点扫过接头附近即可。
        // 两种接头用的是同一条折线与同样的线宽，除了接头本身，其余几何逐位相同。
        StrokeGenerator bevel = new StrokeGenerator();
        bevel.stroke(pts, 3, false, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.BEVEL, 4f);
        float[] bevelTris = bevel.triangles();
        for (float px = -6f; px <= 6f; px += 0.25f) {
            for (float py = -6f; py <= 6f; py += 0.25f) {
                if (covers(bevelTris, px, py)) {
                    assertTrue(covers(tris, px, py),
                            "BEVEL 覆盖的点 MITER 必须也覆盖：" + px + "," + py);
                }
            }
        }
        // 反证（另一半）：尖角是 MITER 独有的，BEVEL 不该有——否则上面那圈包含关系
        // 对"两个都退化成了同一个东西"同样成立。
        assertTrue(covers(tris, 4f, -4f), "MITER 的尖角 (4,-4) 应被覆盖");
        assertFalse(covers(bevelTris, 4f, -4f), "BEVEL 不该有尖角 (4,-4)");
    }

    /**
     * MITER 超过限值回退为 BEVEL 时，**不能把底边那个三角形画两遍**。
     *
     * <p>这条是给"补发"式修法上的保险：修法是在 MITER 分支里补发底边三角形，
     * 而退化分支**已经**发过它了。重复的三角形面积不变、画面（不透明时）也不变，
     * 只有**半透明描边**会在那里叠加两次颜色——所以判据取三角形**个数**，
     * 而不是面积。
     *
     * <p>阈值取 1（小于直角的 miter 长度比 √2）即可稳定落到退化分支。
     */
    @Test
    void miter超限回退斜接时不会把底边三角形画两遍() {
        float[] pts = {-10f, 0f, 0f, 0f, 0f, 10f};
        StrokeGenerator miter = new StrokeGenerator();
        miter.stroke(pts, 3, false, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 1f);

        StrokeGenerator bevel = new StrokeGenerator();
        bevel.stroke(pts, 3, false, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.BEVEL, 4f);

        assertEquals(bevel.triangleCount(), miter.triangleCount(),
                "回退为斜接后应与 BEVEL 逐项相同（多一个就说明底边三角形画了两遍）");
        assertEquals(area(bevel.triangles()), area(miter.triangles()), 1e-3f);
    }

    /**
     * 上一条数的是三角形**个数**（多一个就说明画了两遍）；这一条数**覆盖层数**
     * ——它才是"重复覆盖"的本体，也是半透明描边唯一看得见的后果。
     *
     * <p>直角处底边三角形 `(0,0)-(0,-5)-(5,0)` 的内部只可能被接头自己覆盖：
     * 两个描边四边形都以"过 (0,0) 的横断面"收边，够不到这个三角形里面。
     * 所以逐点扫过去，覆盖层数必须**恒为 1**。
     */
    @Test
    void miter超限回退斜接时底边三角形的内部只被覆盖一次() {
        float[] pts = {-10f, 0f, 0f, 0f, 0f, 10f};
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(pts, 3, false, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 1f);
        float[] tris = g.triangles();

        // 采点与三条边都留 0.25 的余量，避开展开边界（边界上的点算不算覆盖取决于口径）
        int probed = 0;
        for (float px = 0.25f; px <= 4.5f; px += 0.25f) {
            for (float py = -4.5f; py <= -0.25f; py += 0.25f) {
                if (px - py > 4.5f) {
                    continue; // 底边之外：退化成斜接后那里不该有任何三角形
                }
                probed++;
                assertEquals(1, coverageCount(tris, px, py),
                        "(" + px + "," + py + ") 的覆盖层数必须是 1（2 就是画了两遍）");
            }
        }
        assertTrue(probed > 100, "采样点太少，这条断言会失去判别力：" + probed);
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

    @Test
    void 折回处圆角尖端朝外且四个方向一致() {
        // 180° 折回时 cross = ±0.0，而 leftTurn = cross > 0f 对 +0.0 与 -0.0 都是 false，
        // 于是左右转的判定退化为"由零的符号决定"，atan2(±0.0, -1) 会把半圆画到内侧
        // （实测 +x 与 -y 两个方向曾被两段重合的四边形完全盖住，尖端看不见）。
        // 四个行进方向都必须断言，只测一个方向会漏掉另外两个。
        float[][] dirs = {{1f, 0f}, {-1f, 0f}, {0f, 1f}, {0f, -1f}};
        for (float[] d : dirs) {
            StrokeGenerator g = new StrokeGenerator();
            g.stroke(new float[]{0f, 0f, d[0] * 10f, d[1] * 10f, 0f, 0f}, 3, false, 4f,
                    StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.ROUND, 4f, 16);
            float[] tris = g.triangles();
            // 投影到行进方向：两段重合的四边形最多到 10，尖端半圆应再伸出半个线宽到 12
            float maxAlong = -Float.MAX_VALUE;
            for (int i = 0; i < tris.length; i += 2) {
                maxAlong = Math.max(maxAlong, tris[i] * d[0] + tris[i + 1] * d[1]);
            }
            assertEquals(12f, maxAlong, 1e-2f,
                    "行进方向 (" + d[0] + "," + d[1] + ") 的圆角尖端应伸出半个线宽，实际=" + maxAlong);
            assertAllFinite(tris);
        }
    }

    @Test
    void 折回180度不产生非有限顶点() {
        // (0,0)→(10,0)→(0,0)：反向共线，两段方向相反。
        // 若在共线反向时仍去求 miter 交点，cross=0 会让 t=-Infinity，
        // 而 0 * -Infinity = NaN，miterLength 变成 NaN 后
        // "NaN > miterLimit*half" 为 false，污染顶点就会被当成尖角发出去。
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f, 0f, 0f}, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        assertAllFinite(g.triangles());
    }

    @Test
    void 部分折回不产生非有限顶点() {
        // (0,0)→(10,0)→(5,0)：部分折回，同样反向共线
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f, 5f, 0f}, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        assertAllFinite(g.triangles());
    }

    @Test
    void rawTriangles返回内部数组而不是副本() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);

        float[] raw = g.rawTriangles();
        assertSame(raw, g.rawTriangles(), "不得每次分配新数组，否则热路径省不掉复制");
        assertTrue(raw.length >= g.triangleCount() * 6, "内部数组长度只保证不小于有效数据长度");

        float[] copy = g.triangles();
        assertEquals(copy.length, g.triangleCount() * 6);
        for (int i = 0; i < copy.length; i++) {
            assertEquals(copy[i], raw[i], 0f, "第 " + i + " 个 float 应与副本一致");
        }
    }

    @Test
    void rawEdges返回内部数组而不是副本() {
        // 与上面 rawTriangles 那条同形，理由也相同、但代价更大：
        // Task 3 起 Gc.emitTriangles 会**每帧**读 rawEdges()，所以"返回副本"不是多一次
        // 可省可不省的复制，而是每帧一次静默分配——而画面上毫无症状。
        // 实测：把 rawEdges() 改成 `return Arrays.copyOf(edges, edges.length);` 之后，
        // 本类其余断言**全绿**、倒的只有这一条（本类规模 25 / 27 / 30 / 31 条时各复跑过一次，
        // 每次都是同一结论）——所以这一条是唯一守住该契约的地方。
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);

        float[] raw = g.rawEdges();
        // 第二次取同一个引用（同一状态下的重复调用）
        assertSame(raw, g.rawEdges(), "不得每次分配新数组，否则热路径省不掉复制");
        // 反复调用 stroke() 之后仍应是同一块缓冲：几何不增长就不该换数组。
        // 这条是**独立**于上一句的——它走的是"reset + 重新生成"这条路径，
        // 而那正是热路径每帧都在做的事（每帧一次 stroke 调用）。
        g.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 4f);
        assertSame(raw, g.rawEdges(), "几何没变大就不该换缓冲——实例要能在热路径上反复复用");

        // 与位置数组同一套口径：rawXxx 是内部缓冲且不复制，只有带括号的复制版才复制
        float[] rawTris = g.rawTriangles();
        assertSame(rawTris, g.rawTriangles(), "位置数组那条口径不变");
        assertEquals(rawTris.length, raw.length,
                "两个数组逐字等长：每三角形各占 6 个 float，一起扩容");
        assertEquals(g.triangleCount() * 6, g.triangles().length,
                "复制版给出的是有效长度，不是数组长度");
        assertTrue(g.triangles().length < rawTris.length,
                "本用例的有效数据必须短于容量，否则上面那条断言分不出'复制版'与'raw 版'");
    }

    @Test
    void 走扩容路径后边距数组仍与三角形数组等长且无NaN() {
        StrokeGenerator g = new StrokeGenerator();
        // 10 点锯齿折线：9 段 × 2 + 8 个 miter 接头 × 2 = 34 个三角形 = 204 个 float，
        // 而初始容量只有 3*6*4 = 72（12 个三角形），所以至少要翻两次倍
        // （12 个→144、24 个→288）。**这条几何的全部意义就是撑破初始容量**：
        // 不给它扩容，"两个数组是否仍对齐"根本没被验到（原来的用例只出 6 个三角形）。
        float[] pts = new float[20];
        for (int i = 0; i < 10; i++) {
            pts[i * 2] = i * 10f;
            pts[i * 2 + 1] = (i % 2) * 10f;
        }
        g.stroke(pts, 10, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8);
        assertTrue(g.triangleCount() > 12,
                "这条几何必须撑破初始容量（12 个三角形），否则扩容路径没被跑到，实测 "
                        + g.triangleCount());

        float[] t = g.rawTriangles();
        float[] e = g.rawEdges();
        assertEquals(t.length, e.length,
                "扩容后两个数组仍必须逐字等长（它们是按同一个判据一起翻倍的）");

        int valid = g.triangleCount() * 6;
        assertTrue(t.length >= valid, "容量不得小于有效数据");
        for (int i = 0; i < valid; i++) {
            assertTrue(Float.isFinite(t[i]), "第 " + i + " 个位置应为有限值，实测 " + t[i]);
            assertTrue(Float.isFinite(e[i]), "第 " + i + " 个边距应为有限值，实测 " + e[i]);
        }
    }

    @Test
    void 水平直线段的横向边距是正负一() {
        StrokeGenerator g = new StrokeGenerator();
        // 一条从 (0,0) 到 (100,0) 的线，线宽 10 ⇒ 半线宽 5
        g.stroke(new float[]{0f, 0f, 100f, 0f}, 2, false, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8);
        float[] e = g.rawEdges();
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = 0; i < g.triangleCount() * 3; i++) {
            float c = e[i * 2];
            min = Math.min(min, c);
            max = Math.max(max, c);
        }
        assertEquals(-1f, min, 1e-5f, "一条长边的横向边距应为 -1");
        assertEquals(1f, max, 1e-5f, "另一条长边的横向边距应为 +1");

        // ★ 上面那对 min/max **抓不住"两侧写反"**：把 +n 侧写成 -1、-n 侧写成 +1 之后，
        //   顶点集合仍然是 {+1, -1}，min 与 max 逐位不变——实测那条变异在这个方法上
        //   25 条全绿。所以符号必须与**顶点位置**绑在一起断言：
        //   本段方向是 +x，n = (-dy/len, dx/len)*half = (0,5)，于是 y > 0 的那一排
        //   （+n 侧）横向必须是 +1、y < 0 的那一排必须是 -1。
        //   这里没有"y 落在 0 附近"的顶点（生成的全是 ±5 的角点），所以这条断言不需要容差。
        float[] t = g.triangles();
        int probed = 0;
        for (int v = 0; v < g.triangleCount() * 3; v++) {
            float y = t[v * 2 + 1];
            float c = e[v * 2];
            if (y > 0f) {
                assertEquals(1f, c, 0f, "y=" + y + " 的顶点在 +n 侧，横向应为 +1");
                probed++;
            } else if (y < 0f) {
                assertEquals(-1f, c, 0f, "y=" + y + " 的顶点在 -n 侧，横向应为 -1");
                probed++;
            }
        }
        assertEquals(6, probed, "两个四边形共 6 个顶点，都该落在某一侧上");
    }

    @Test
    void 开放路径的沿向边距两端为零且中段最大() {
        StrokeGenerator g = new StrokeGenerator();
        // ★ 必须是**三**点，不能是两点：两点折线只有两端，两端的沿向都是 0
        //   （它们各自就在一条端帽线上），于是 max 也等于 0——而本条断言要求正数。
        //   名字里的"中段"要有顶点才存在：实测两点版本在正确实现下是
        //   `expected: <10.0> but was: <0.0>`。
        // ★ 而且两段**必须不等长**：(0,0)→(50,0)→(100,0) 这种等长两段下，
        //   `arc += len` 写成 `arc += 0f` 之后段 2 的 aEnd 恰好与原值重合，min/max
        //   一字不变（那条变异因此存活）。取 30/70 之后，中段那个顶点的沿向 = 30/5 = 6，
        //   与"远端复用近端沿向"（会给出 0）和"弧长不推进"（段 2 的近端会给出 0）
        //   都能分开。
        // 两点共线，MITER 接头在共线同向处直接返回，所以中间那个顶点只经两个四边形过路。
        float[] pts = {0f, 0f, 30f, 0f, 100f, 0f};
        g.stroke(pts, 3, false, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8);
        float[] e = g.rawEdges();
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = 0; i < g.triangleCount() * 3; i++) {
            min = Math.min(min, e[i * 2 + 1]);
            max = Math.max(max, e[i * 2 + 1]);
        }
        assertEquals(0f, min, 1e-5f, "起点那一排的沿向边距应为 0（它就在端帽线上）");
        // ★ 这一条不可省：`min(arc, L-arc)` 与 `arc` 在**两端给出相同的值**
        //   （0 与 L），只有**中段**才分得开。少了它，"沿向算成到远端端帽的距离"
        //   这个变异会存活。
        assertEquals(6f, max, 1e-4f, "中段的沿向应为 30/5 = 6（弧长 30、半线宽 5）");

        // ★★ 上面那对 min/max 仍然只是极值口径——把沿向与**顶点位置**绑起来才抓得住
        //   两个"局部写错"的变异（实测它们在这对极值下存活）：
        //     M13 段四边形远端的 alongAt(arc+len,…) 写成 alongAt(arc,…)：每个三角形内
        //         沿向恒定 ⇒ wa = 0 ⇒ 着色器走"完全覆盖"⇒ 沿向 AA 全灭；
        //     M14 `arc += len` 写成 `arc += 0f`：段 2 的近端会拿到 0，而它本该是 6。
        //   本用例的顶点位置只有三排（x=0、30、100），沿向必须分别是 0、6、0。
        float[] t = g.triangles();
        int probed = 0;
        for (int v = 0; v < g.triangleCount() * 3; v++) {
            float x = t[v * 2];
            float a = e[v * 2 + 1];
            if (Math.abs(x) < 1e-5f) {
                assertEquals(0f, a, 1e-5f, "x=0 那一排就在端线上，沿向应为 0");
                probed++;
            } else if (Math.abs(x - 30f) < 1e-5f) {
                assertEquals(6f, a, 1e-5f, "x=30 那一排（弧长 30、半线宽 5）沿向应为 6");
                probed++;
            } else if (Math.abs(x - 100f) < 1e-5f) {
                assertEquals(0f, a, 1e-5f, "x=100 那一排是另一端线，沿向应为 0");
                probed++;
            }
        }
        // 两个四边形 × 2 个三角形 × 3 个顶点 = 12 次顶点出现（不是几何上的 8 个角：
        // 每个四边形拆成两个三角形，共享的两个角各出现两次）
        assertEquals(12, probed, "两个四边形的全部顶点出现都该落在某一排上");
    }

    @Test
    void capExtension外扩使端帽沿向出现负值() {
        // 沿向的 0 等值线落在端帽线上，而端帽**外侧**本该有一条渐隐带。
        // 外扩四边形就是那条带：从端线（沿向 0）向外铺 capExtension，
        // 外缘沿向 = -capExtension/half。本用例：半线宽 5、外扩 2 ⇒ 外缘 -0.4。
        StrokeGenerator ext = new StrokeGenerator();
        ext.stroke(new float[]{0f, 0f, 100f, 0f}, 2, false, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8, 2f);
        float[] e = ext.rawEdges();
        float min = Float.MAX_VALUE;
        for (int i = 0; i < ext.triangleCount() * 3; i++) {
            min = Math.min(min, e[i * 2 + 1]);
        }
        assertTrue(min < 0f, "外扩后必须有负的沿向（否则带外那条渐隐带不存在），实测 " + min);
        assertEquals(-2f / 5f, min, 1e-6f, "外缘沿向应为 -capExtension/半线宽 = -2/5");

        // 光有负值还不够：外扩四边形必须真的**铺出去**了。少了这一条，
        // "把四个顶点都发在端线上"那种写法也能让上面的断言通过。
        // ★ 而且**两端必须各自计数**：一个 sawOuter 布尔量只要任意一端铺了就是真，
        //   于是"只有起点外扩"或"只有终点外扩"都能全绿——而 C1 的可见后果恰恰是
        //   **两端各长出一像素**，只钉住一端等于一半没钉。实测这两个变异（起点/终点
        //   各删一处）在合并成布尔量时都是 30/30 全绿，拆开之后各自定向倒下。
        float[] t = ext.triangles();
        int startOuter = 0, endOuter = 0;
        for (int v = 0; v < ext.triangleCount() * 3; v++) {
            float x = t[v * 2];
            float a = e[v * 2 + 1];
            if (x < -1e-5f) {
                assertEquals(-2f, x, 1e-4f, "起点外缘应铺到端线外 capExtension 处");
                assertEquals(-2f / 5f, a, 1e-6f, "起点外缘沿向应为 -0.4");
                startOuter++;
            } else if (x > 100f + 1e-5f) {
                assertEquals(102f, x, 1e-4f, "终点外缘应铺到端线外 capExtension 处");
                assertEquals(-2f / 5f, a, 1e-6f, "终点外缘沿向应为 -0.4");
                endOuter++;
            }
        }
        assertTrue(startOuter > 0, "**起点**必须有外扩出来的顶点，实测 " + startOuter);
        assertTrue(endOuter > 0, "**终点**必须有外扩出来的顶点，实测 " + endOuter);

        // 既有行为不变：capExtension 缺省（0）时一个负值都不该有，
        // 而且位置与不传时逐位相同
        StrokeGenerator noExt = new StrokeGenerator();
        noExt.stroke(new float[]{0f, 0f, 100f, 0f}, 2, false, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8);
        StrokeGenerator zeroExt = new StrokeGenerator();
        zeroExt.stroke(new float[]{0f, 0f, 100f, 0f}, 2, false, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8, 0f);
        for (int i = 0; i < noExt.triangleCount() * 3; i++) {
            assertTrue(noExt.rawEdges()[i * 2 + 1] >= 0f,
                    "不外扩时沿向不该有负值（端帽外侧本来就没有几何）");
        }
        assertEquals(noExt.triangleCount(), zeroExt.triangleCount(),
                "capExtension = 0 必须与不传这个参数完全等价");
        assertArrayEquals(noExt.triangles(), zeroExt.triangles(), 0f,
                "capExtension = 0 的位置必须逐位相同");
    }

    @Test
    void 虚线每一格也各自外扩() {
        // 虚线那半边（strokeDashed 的 capExtension）此前零调用点、零断言，
        // 把它悄悄改成 0f 时 35 条实线+虚线用例全绿。而它**是新的公开 API 面**，
        // 与"生产里永远到不了 ⇒ 永久不可验"同族，所以必须按值钉住。
        //
        // 期望值：每格实线都是两点开放折线，两个端点各是一条端线，端帽各自外扩
        // capExtension ⇒ 外缘沿向 = -capExtension/half。本用例：
        //   折线 (0,0)→(40,0)、w=4 ⇒ half=2、capExtension=2 ⇒ 外缘沿向 -2/2 = -1。
        // dash 模式 {10,10}、相位 0、全长 40 ⇒ 实线格是 [0,10] 与 [20,30]：
        //   第 1 格起点 x=0 外扩到 x=-2；第 2 格终点 x=30 外扩到 x=32。
        StrokeGenerator g = new StrokeGenerator();
        g.strokeDashed(new float[]{0f, 0f, 40f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f,
                new float[]{10f, 10f}, 0f, 8, 2f);
        float[] t = g.triangles();
        float[] e = g.rawEdges();

        float min = Float.MAX_VALUE;
        for (int i = 0; i < g.triangleCount() * 3; i++) {
            min = Math.min(min, e[i * 2 + 1]);
        }
        assertEquals(-1f, min, 1e-6f,
                "虚线格的外缘沿向应为 -capExtension/半线宽 = -2/2");

        // 位置同样要钉：外缘必须真的在格子两端之外，而且是**首格起点**与**末格终点**
        // 各一处（否则"只有中间某一格外扩"也能满足上面的极值）
        int firstDashOuter = 0, lastDashOuter = 0;
        for (int v = 0; v < g.triangleCount() * 3; v++) {
            float x = t[v * 2];
            float a = e[v * 2 + 1];
            if (Math.abs(x + 2f) < 1e-4f) {
                assertEquals(-1f, a, 1e-6f, "首格起点外缘的沿向应为 -1");
                firstDashOuter++;
            } else if (Math.abs(x - 32f) < 1e-4f) {
                assertEquals(-1f, a, 1e-6f, "末格终点外缘的沿向应为 -1");
                lastDashOuter++;
            }
        }
        assertTrue(firstDashOuter > 0, "首格起点应有外扩顶点，实测 " + firstDashOuter);
        assertTrue(lastDashOuter > 0, "末格终点应有外扩顶点，实测 " + lastDashOuter);

        // 反面：同一支虚线不传 capExtension 时，一个负值都不该有
        StrokeGenerator noExt = new StrokeGenerator();
        noExt.strokeDashed(new float[]{0f, 0f, 40f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f,
                new float[]{10f, 10f}, 0f, 8);
        for (int i = 0; i < noExt.triangleCount() * 3; i++) {
            assertTrue(noExt.rawEdges()[i * 2 + 1] >= 0f,
                    "不外扩的虚线不该有负沿向");
        }
        // 外扩只增加几何，且增量可数：2 格实线 × 每格 2 个端点 = 4 个外扩四边形
        // = 8 个三角形。（**不是**"整表前缀不变"：起点封口是在本段四边形**之前**发的，
        // 所以起点那圈外扩会把这格后面的顶点整体往后挪——顺序不是契约，别断言它。）
        assertEquals(8, g.triangleCount() - noExt.triangleCount(),
                "4 个外扩四边形应恰好带来 8 个三角形，实测差值 "
                        + (g.triangleCount() - noExt.triangleCount()));
    }

    @Test
    void 端帽的边距取值() {
        // 期望值自己从几何推，推导写在下面（不照抄任何文档）。
        // 直线 (0,0)→(10,0)，w=4 ⇒ 半线宽 2。
        //
        // 【SQUARE 端帽】起点封口：emitCap(px=0, py=0, dx=-1, dy=0) ⇒
        //   u = (-1,0)（向外 = -x），n = (-uy, ux)*half = (0,-1)*2 = (0,-2)。
        //   四边形 = (0,-2) → (-2,-2) → (-2,2) → (0,2)：
        //   端线（x=0）沿向 0、外缘（x=-2）沿向 -1（向外半个线宽 ⇒ -half/half）；
        //   横向 +n 侧（y=-2）为 +1、-n 侧（y=+2）为 -1，外缘同理。
        StrokeGenerator sq = new StrokeGenerator();
        sq.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.SQUARE, StrokeGenerator.Join.MITER, 8f, 8);
        float[] t = sq.triangles();
        float[] e = sq.rawEdges();
        int endLine = 0;
        boolean outerPlusSide = false, outerMinusSide = false;
        for (int v = 0; v < sq.triangleCount() * 3; v++) {
            float x = t[v * 2], y = t[v * 2 + 1];
            float c = e[v * 2], a = e[v * 2 + 1];
            if (Math.abs(x + 2f) < 1e-5f) {
                // 起点端帽的外缘（只有起点封口能产生 x = -2 的顶点）。
                // 注意这里遍历的是**顶点出现次数**（四边形拆成两个三角形，v0/v2 各出现两次），
                // 所以下面按"两条边都见过"断言，而不是几何上更自然的"4 个角"。
                assertEquals(-1f, a, 1e-5f, "SQUARE 外边沿向应为 -1（向外半个线宽）");
                assertEquals(y < 0f ? 1f : -1f, c, 1e-5f, "外边的横向按 ±n 侧取 ±1");
                if (y < 0f) {
                    outerPlusSide = true;
                } else {
                    outerMinusSide = true;
                }
            } else if (Math.abs(x) < 1e-5f) {
                assertEquals(0f, a, 1e-5f, "端线沿向应为 0（它就是沿向的零点）");
                // ★ 这一排**不能**断言符号：端帽的内边与段四边形的端点边是同一条线，
                //   而端帽传进来的 (dx,dy) 是"向外"方向 ⇒ 它的 n 与段四边形的 n 反向，
                //   于是同一个几何侧从两者拿到相反的符号（见 emitCap 的 KDoc）。
                //   两边一致的是**大小**与沿向，那才是能断言的东西。
                assertEquals(1f, Math.abs(c), 1e-5f, "端线的横向应为 ±1（两条外缘）");
                endLine++;
            }
        }
        assertTrue(outerPlusSide && outerMinusSide, "起点端帽外缘的两条边（y=±2）都该有顶点");
        assertTrue(endLine >= 3, "端线上应有顶点（端帽内边 3 次出现 + 段四边形端点边），实测 " + endLine);

        // 【ROUND 端帽】同一条线。圆心 = 端点 (0,0)（在端线上）⇒ 横向 0、沿向 0；
        //   圆周上 dir = (cos a, sin a)，横向 = dir·n（n = (0,-1)）、沿向 = -dir·u（u = (-1,0)）。
        //   ⇒ 尖端 dir = u = (-1,0)：位置 (-2,0)、横向 0、沿向 -1；
        //      圆周与端线相交的两点 dir = ±(0,1)：位置 (0,±2)、横向 ∓1、沿向 0。
        StrokeGenerator rd = new StrokeGenerator();
        rd.stroke(new float[]{0f, 0f, 10f, 0f}, 2, false, 4f,
                StrokeGenerator.Cap.ROUND, StrokeGenerator.Join.MITER, 8f, 8);
        float[] rt = rd.triangles();
        float[] re = rd.rawEdges();
        float minAlong = Float.MAX_VALUE;
        int tip = 0, center = 0, rimEnd = 0;
        for (int v = 0; v < rd.triangleCount() * 3; v++) {
            float x = rt[v * 2], y = rt[v * 2 + 1];
            float c = re[v * 2], a = re[v * 2 + 1];
            minAlong = Math.min(minAlong, a);
            if (Math.abs(x + 2f) < 1e-4f && Math.abs(y) < 1e-4f) {
                // 尖端：起点端帽最外那一点（半圆的中点）
                assertEquals(-1f, a, 1e-5f, "ROUND 尖端沿向应为 -1（向外半个线宽）");
                assertEquals(0f, c, 1e-5f, "尖端落在中心线的延长线上，横向应为 0");
                tip++;
            }
            if (Math.abs(x) < 1e-5f && Math.abs(y) < 1e-5f) {
                // 圆心：端点本身，在端线上
                assertEquals(0f, c, 1e-5f, "圆心的横向应为 0（它在中心线上）");
                assertEquals(0f, a, 1e-5f, "圆心在端线上，沿向应为 0");
                center++;
            } else if (Math.abs(x) < 1e-5f && Math.abs(y) > 1e-5f) {
                // 圆周与端线相交的两点：(0,±2)，正是圆弧的两个端点
                assertEquals(0f, a, 1e-5f, "圆周与端线相交处沿向应为 0");
                assertEquals(1f, Math.abs(c), 1e-5f, "那里横向应为 ±1（两条外缘）");
                rimEnd++;
            }
        }
        assertEquals(-1f, minAlong, 1e-5f, "ROUND 端帽的沿向最小值为 -1");
        // 计数按**顶点出现次数**（三角扇的相邻三角形共享圆周顶点）：
        // roundSegments=8 ⇒ 8 个三角形 ⇒ 圆心出现 8 次（每个三角形各一次）；
        // 尖端落在 k=4，被第 4、5 两个三角形共用 ⇒ 出现 2 次。
        assertEquals(8, center, "圆心应由 8 个扇形三角形各发一次");
        assertEquals(2, tip, "尖端被相邻两个扇形三角形共用，应出现 2 次");
        assertTrue(rimEnd >= 2, "圆周与端线相交处应有 2 个顶点（k=0 与 k=8，各出现一次），实测 " + rimEnd);
    }

    @Test
    void 接头的边距取值() {
        // 直角折线 (0,0)→(10,0)→(10,10)，w=4 ⇒ half=2，全长 20，拐点弧长 10。
        // 拐点处的沿向 = alongAt(10, 20, 2, false) = min(10,10)/2 = 5。
        // u1 = (1,0)、u2 = (0,1) ⇒ cross = 1 > 0（左转）⇒ s = -1，
        // 偏移点 o1 = (-u1y*half*s, u1x*half*s) = (0,-2) ⇒ p+o1 = (10,-2)；
        // 偏移点 o2 = (-u2y*half*s, u2x*half*s) = (2,0) ⇒ p+o2 = (12,0)。
        //
        // 【MITER 尖角】limit 8 ⇒ 阈值 16，miter 长度 = half*√2 ≈ 2.83 < 16 ⇒ 走完整风筝形。
        //   两条偏移线的交点 m：t = ((o2x-o1x)*u2y - (o2y-o1y)*u2x)/cross = 2 ⇒
        //   m = p + o1 + u1*t = (12,-2)。它只可能由尖角那个三角形产生
        //   （x=12 落在两段四边形的范围之外），沿向与拐点相同 = 5。
        //
        //   横向**必须取 0**——不是它到中心线的真实距离 √2，也不是凸侧符号 s。
        //   完整理由见 `emitJoin` 的注释，摘要：`|x| = 1` 处覆盖率公式给的是 0.5、
        //   而 `Gc` 开抗锯齿时会把所有顶点的横向按 `几何半宽/真实半宽` 放大
        //   ⇒ 取 ±1 会变成 ±1.5 ⇒ 覆盖率 0 ⇒ **每个拐角被啃掉一块**。
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f}, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8);
        float[] t = g.triangles();
        float[] e = g.rawEdges();
        int tip = 0;
        for (int v = 0; v < g.triangleCount() * 3; v++) {
            float x = t[v * 2], y = t[v * 2 + 1];
            if (Math.abs(x - 12f) < 1e-5f && Math.abs(y + 2f) < 1e-5f) {
                assertEquals(0f, e[v * 2], 1e-5f,
                        "miter 尖角横向应取 0（走'完全覆盖'分支；取 ±1 会在 AA 下把拐角啃掉一块）");
                assertEquals(5f, e[v * 2 + 1], 1e-5f, "尖角沿向应与拐点相同");
                tip++;
            }
        }
        assertEquals(1, tip, "尖角 (12,-2) 只应有 1 个顶点");

        // 【圆角接头】同一折线换成 Join.ROUND。圆弧以拐点 p 为心、半径 half=2，
        //   从 o1 方向扫过转向角 π/2（o1 = (0,-2) 相对 p ⇒ 起始角 -90°，扫到 0°）。
        //   取扫过一半处 dir = (cos(-45°), sin(-45°)) = (√2/2, -√2/2)：
        //   位置 = (10 + √2, -√2) ≈ (11.4142, -1.4142)——这点两段四边形都盖不到
        //   （段 1 的 x ≤ 10、段 2 的 y ≥ 0），所以只可能来自圆角盘。
        //   横向 = dir·(入段左法线 (-u1y,u1x) = (0,1)) = -√2/2；
        //   沿向 = alongBase（整盘恒定）= 5。
        StrokeGenerator r = new StrokeGenerator();
        // ⚠ 这里必须用 8 参重载。写成 `…, 8f, 8, 16` 会绑到 9 参那个（第 9 个参数是
        //   float capExtension，int 字面量 16 加宽成 float 正好合法），于是
        //   roundSegments 停在 8 而 capExtension 变成 16——编译通过、断言全绿，错的是意图。
        r.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f}, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.ROUND, 8f, 16);
        float[] rt = r.triangles();
        float[] re = r.rawEdges();
        float px = 10f + (float) Math.sqrt(2), py = -(float) Math.sqrt(2);
        int mid = 0;
        for (int v = 0; v < r.triangleCount() * 3; v++) {
            if (Math.abs(rt[v * 2] - px) < 1e-3f && Math.abs(rt[v * 2 + 1] - py) < 1e-3f) {
                assertEquals(-(float) (Math.sqrt(2) / 2), re[v * 2], 1e-3f,
                        "圆角盘上的横向应是到入段中心线的有符号垂直距离（-√2/2）");
                assertEquals(5f, re[v * 2 + 1], 1e-5f,
                        "圆角盘整个落在拐点这一个弧长位置上，沿向应恒为 5");
                mid++;
            }
        }
        assertTrue(mid > 0, "圆角盘扫过一半处应有顶点（只可能来自圆角盘）");
    }

    /**
     * 接头三角形的横向必须**一律取 0**——`emitJoin` 里四处发射点（BEVEL/ROUND 那个、
     * miter 超限回退那个、MITER 底边那个、MITER 尖角那个）都算。
     *
     * <p><strong>为什么必须是 0</strong>：覆盖率公式在 `|x| = 1` 处给的是 **0.5** 而不是 1
     * ——"取 s 让尖角完全覆盖"这个本意从来没有实现过；而 `Gc` 开抗锯齿时会按
     * `几何半宽 / 真实半宽` 缩放**所有**顶点的横向（描边带被外扩过 1 个像素），
     * 于是 ±1 变成 ±1.5 ⇒ 覆盖率 **0** ⇒ <strong>每个拐角被啃掉一块</strong>。
     * 取 0 让 `fwidth(cross) == 0`、片元走"完全覆盖"分支，
     * 而且它是唯一一个在"外扩/不外扩"两种几何下给同一个覆盖率的取值。
     *
     * <p>判据取**顶点集合**（而不是"挑几个采样点看"）：接头就是那几个顶点，
     * 逐点比对才拦得住"只改了一处"。
     */
    @Test
    void 接头三角形的横向一律为零() {
        // 直角折线 (0,0)→(10,0)→(10,10)，线宽 4 ⇒ 半线宽 2。接头风筝形是 [10,12]×[-2,0]：
        //   拐点 p=(10,0)、两个偏移点 p+o1=(10,-2) 与 p+o2=(12,0)、尖角 m=(12,-2)。
        // ⚠ (10,-2) 与 (12,0) 同时也是**相邻段四边形**的角点（横向 ±1），
        //   所以"接头那几个顶点"不能按位置认——要按**横向为 0 的那一批**认，
        //   再断言那一批恰好是这四个位置。
        //
        //   端帽是 BUTT 且 capExtension=0 ⇒ 端帽不发射任何几何；两段四边形全是 ±1。
        //   于是横向为 0 的顶点**只可能**来自接头。
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f}, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8);
        // 完整风筝形 = 底边三角形 (p, p+o1, p+o2) + 尖角三角形 (p+o1, m, p+o2)
        // ⇒ 6 个顶点、4 个不同位置（底边两端各被两个三角形共用一次）。
        assertEquals("[10.0,-2.0, 10.0,0.0, 12.0,-2.0, 12.0,0.0]",
                zeroCrossVertices(g).toString(),
                "横向为 0 的顶点必须恰好是接头风筝形的四个角，实测 " + zeroCrossVertices(g));
        assertEquals(6, countZeroCross(g), "尖角那一半也要覆盖：底边 3 个 + 尖角 3 个 = 6 个顶点");

        // 反证一：miter 超限回退那条分支（只有底边三角形，3 个顶点）。
        StrokeGenerator bevelled = new StrokeGenerator();
        bevelled.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f}, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 1f, 8);
        assertEquals(3, countZeroCross(bevelled),
                "超限回退为斜接后只有底边三角形，横向为 0 的顶点应为 3 个");
        assertEquals("[10.0,-2.0, 10.0,0.0, 12.0,0.0]", zeroCrossVertices(bevelled).toString(),
                "回退分支的底边三角形与 MITER 那条是同一个，顶点位置应完全一致");

        // 反证二：BEVEL 分支（与 ROUND 共用同一个发射点）。
        StrokeGenerator bevel = new StrokeGenerator();
        bevel.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f}, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.BEVEL, 8f, 8);
        assertEquals("[10.0,-2.0, 10.0,0.0, 12.0,0.0]", zeroCrossVertices(bevel).toString(),
                "BEVEL 接头同样是横向 0，且只有底边三个顶点");
    }

    /**
     * 接头半宽（{@code joinHalf}）**只**作用于接头：段四边形与端帽仍按 {@code width} 生成。
     *
     * <p>存在的理由：调用方（`Gc`）为了给长边留外侧片元会把线宽加宽 1 个设备像素，
     * 而接头的横向恒为 0、**不会被羽化**——加宽量落在它身上只会让拐角实打实地多画一圈。
     * 于是接头要拿**真实**半线宽生成。这条断言把"只有接头用了另一个半宽"钉死：
     * 若 {@code joinHalf} 被误传给段四边形，第二组读数会从 ±4 掉到 ±3。
     */
    @Test
    void 接头半宽只作用于接头() {
        // 直角折线 (0,0)→(10,0)→(10,10)。线宽 8 ⇒ 段四边形与端帽的半宽是 4；
        // 接头半宽显式传 3 ⇒ 风筝形按 3 生成：偏移点 (10,-3) 与 (13,0)、尖角 (13,-3)。
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 0f, 10f, 10f}, 3, false, 8f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8, 0f, 3f);
        float[] t = g.triangles();
        int tip = 0;
        int quadAtFour = 0;
        int quadAtThree = 0;
        for (int v = 0; v < g.triangleCount() * 3; v++) {
            float x = t[v * 2], y = t[v * 2 + 1];
            if (Math.abs(x - 13f) < 1e-4f && Math.abs(y + 3f) < 1e-4f) {
                tip++;
            }
            if (Math.abs(x) < 1e-4f) {
                if (Math.abs(Math.abs(y) - 4f) < 1e-4f) {
                    quadAtFour++;
                }
                if (Math.abs(Math.abs(y) - 3f) < 1e-4f) {
                    quadAtThree++;
                }
            }
        }
        assertEquals(1, tip, "接头尖角应按 joinHalf=3 落在 (13,-3)，实测 " + tip + " 个");
        // 第一段四边形的角点在 x = 0 处是 (0,4) 与 (0,-4)；前者出现在该四边形的两个三角形里
        // （`emitQuad` 的第二个三角形从 v0 起算）⇒ 共 3 个顶点落在 x=0 且 |y|=4 上。
        assertEquals(3, quadAtFour,
                "段四边形的角点必须留在 width/2 = 4 上（(0,4) 被两个三角形共用所以是 3 个），实测 " + quadAtFour + " 个");
        assertEquals(0, quadAtThree, "joinHalf=3 不该漏给段四边形——x=0 处不该出现 |y|=3 的角点");
    }

    @Test
    void 闭合路径的沿向边距远离零() {
        StrokeGenerator g = new StrokeGenerator();
        // 一个 100x100 的方框，周长 400，半线宽 5 ⇒ 沿向应当恒 ≥ 400/5 = 80
        g.stroke(new float[]{0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f}, 4, true, 10f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8);
        float[] e = g.rawEdges();
        for (int i = 0; i < g.triangleCount() * 3; i++) {
            assertTrue(e[i * 2 + 1] >= 80f,
                    "闭合路径不该有靠近 0 的沿向边距（会在起点凭空造出羽化边），实测 " + e[i * 2 + 1]);
        }
    }

    @Test
    void 边距数组长度与三角形对齐且不含NaN() {
        StrokeGenerator g = new StrokeGenerator();
        g.stroke(new float[]{0f, 0f, 10f, 10f, 20f, 0f}, 3, false, 4f,
                StrokeGenerator.Cap.BUTT, StrokeGenerator.Join.MITER, 8f, 8);
        float[] e = g.rawEdges();
        assertTrue(e.length >= g.triangleCount() * 6, "边距数组至少要有 三角形数*6 个 float");
        // ★ 名字里的"与三角形对齐"必须按**逐字等长**断言，不能只写 `>= 有效数据`：
        //   `>=` 对"每三角形只写 3 个 float""扩容时两个数组用不同判据"这类错法**恒真**
        //   （只要容量够大就成立）。两个数组每三角形各占 6 个 float，就该一样长。
        assertEquals(g.rawTriangles().length, e.length,
                "边距数组与位置数组必须逐字等长（每三角形各占 6 个 float，一起扩容）");
        for (int i = 0; i < g.triangleCount() * 6; i++) {
            assertTrue(Float.isFinite(e[i]), "边距不该是 NaN/Infinity，实测 " + e[i]);
        }
    }
}
