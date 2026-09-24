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
            if (!(neg && pos)) n++;
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
}
