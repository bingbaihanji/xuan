package com.bingbaihanji.xuan.chartrender;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SmoothCurve} 的单元测试：细分档位、角点布局、以及"这一段实例里哪几段能画成曲线"
 * 的算术。
 *
 * <h2>为什么这些算术值得单测</h2>
 * <p>它们都是<b>纯算术</b>，而算错的表现全都落在"画面看起来正常"那一类：
 * <ul>
 *   <li>档位选错 → 曲线要么有棱角、要么白付几倍顶点开销，<b>两条路画面都像曲线</b>；</li>
 *   <li>角点布局错位 → 带子被拧成麻花，那看起来像"数据的形状"；</li>
 *   <li>可平滑区间算错 → 首末两段用了<b>陈旧数据</b>当控制点（环里那个位置不是 NaN），
 *       曲线<b>弯向垃圾值</b>而画面只是一条形状略有出入的曲线。</li>
 * </ul>
 * <p>最后一条另有像素口径的断言（{@code ChartVerifier} 的平滑曲线一节），
 * 但它要跑真 GL 上下文；这里的单测是它在 CPU 侧的那一半。
 */
class SmoothCurveTest {

    @Test
    void 档位随每样本像素数走且只取2的幂() {
        // 判据：站位之间的间距不超过 4px，档位不超出 [2, 16]。
        assertEquals(2, SmoothCurve.subdivisionFor(0.5), "很密：最小档");
        assertEquals(2, SmoothCurve.subdivisionFor(8.0), "8px/样本正好是 2×4，仍是 2 档");
        assertEquals(4, SmoothCurve.subdivisionFor(8.1));
        assertEquals(4, SmoothCurve.subdivisionFor(16.0));
        assertEquals(8, SmoothCurve.subdivisionFor(32.0));
        assertEquals(16, SmoothCurve.subdivisionFor(1000.0), "放得很大：封顶在 16");
    }

    @Test
    void 档位永不退化成不细分() {
        // K = 1 时中间一个站位都没有，曲线会退化成弦——那是"开关设了但什么都不做"，
        // 而画面与不平滑**逐像素相同**，是本项目最不想看到的那种静默。
        for (double px : new double[]{0, 0.1, 1, 4, 8, 100, 1e9}) {
            assertTrue(SmoothCurve.subdivisionFor(px) >= SmoothCurve.K_MIN,
                    "pxPerSample=" + px + " 的档位不能小于 " + SmoothCurve.K_MIN);
        }
    }

    @Test
    void 非法像素数不产生NaN档位() {
        // 窗口退化时渲染器本来就不会走到这里（实例段为空），但这条路径不能把 NaN
        // 带进循环——NaN 与任何数比较都是 false，会静默地"选到最小档"。
        assertEquals(SmoothCurve.K_MIN, SmoothCurve.subdivisionFor(Double.NaN));
        assertEquals(SmoothCurve.K_MIN, SmoothCurve.subdivisionFor(0));
        assertEquals(SmoothCurve.K_MIN, SmoothCurve.subdivisionFor(-5));
    }

    @Test
    void 角点数据前四个是调用方给的普通四角() {
        float[] plain = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
        float[] data = SmoothCurve.cornerData(plain);
        assertArrayEquals(plain, java.util.Arrays.copyOf(data, plain.length),
                "前四个顶点必须原样保留：非平滑路径画的正是它们，几何要逐位不变");
        assertEquals(0f, data[0]);
    }

    @Test
    void 角点数据里每一档的站位参数都从0到1() {
        float[] data = SmoothCurve.cornerData(new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f});
        for (int k : new int[]{2, 4, 8, 16}) {
            int first = SmoothCurve.firstVertex(k);
            int count = SmoothCurve.vertexCount(k);
            // 每档 2×(K+1) 个顶点，按 (t, 侧) 成对、t 递增：0, 1/K, 2/K ... 1
            assertEquals(2 * (k + 1), count);
            float prev = -1f;
            for (int i = 0; i <= k; i++) {
                float t = data[(first + 2 * i) * 2];
                assertEquals((float) i / k, t, 0f, "档位 " + k + " 的第 " + i + " 个站位");
                assertTrue(t > prev, "站位必须严格递增");
                assertEquals(1f, data[(first + 2 * i + 1) * 2 + 1], "第二个顶点是另一侧");
                prev = t;
            }
            assertEquals(0f, data[first * 2], "起点 t = 0");
            assertEquals(1f, data[(first + count - 2) * 2], "终点 t = 1");
        }
    }

    @Test
    void 各档站位区间首尾相接且不重叠() {
        // 区间必须覆盖整块角点数据：漏一段会让那档画出别的档的顶点（一条完全错的带子），
        // 重叠则会让 first/count 指到不属于自己的站位。
        List<int[]> ranges = new ArrayList<>();
        for (int k : new int[]{2, 4, 8, 16}) {
            ranges.add(new int[]{SmoothCurve.firstVertex(k), SmoothCurve.vertexCount(k)});
        }
        int expected = 4;
        for (int[] r : ranges) {
            assertEquals(expected, r[0], "区间必须紧挨着上一个");
            expected += r[1];
        }
    }

    @Test
    void 档位不合法时明确报错() {
        assertThrows(IllegalArgumentException.class, () -> SmoothCurve.firstVertex(3));
        assertThrows(IllegalArgumentException.class, () -> SmoothCurve.vertexCount(1));
        assertThrows(IllegalArgumentException.class, () -> SmoothCurve.cornerData(new float[6]));
    }

    @Test
    void 段内可平滑区间被夹在本段实例数内() {
        // 静态数据 7 个点、窗口 [0, 6]：一段 6 个实例（数据下标 0..5），
        // 可平滑区间是 [1, 5) ⇒ 第一个与最后一个实例退回直线。
        List<WindowRange.Segment> segments = WindowRange.compute(0, 6, 7, 8);
        assertEquals(1, segments.size(), "前提：这一段不跨环绕");
        WindowRange.Segment seg = segments.get(0);
        assertEquals(6, seg.instanceCount());

        long smoothFirst = 1;
        long smoothEnd = 5;
        assertEquals(1f, SmoothCurve.smoothFromInSegment(
                seg.firstDataIndex(), seg.instanceCount(), smoothFirst));
        assertEquals(5f, SmoothCurve.smoothToInSegment(
                seg.firstDataIndex(), seg.instanceCount(), smoothEnd));
        // ⇒ 实例 0 不满足 idx ≥ 1，实例 5 不满足 idx < 5：首末两段都走直线。
    }

    @Test
    void 段完全落在可平滑区间之外时一个实例都不许平滑() {
        // 窗口滑到最右边（数据只到 7 个点）：这一段整个落在"末段之后"，必须全是直线。
        assertEquals(0f, SmoothCurve.smoothFromInSegment(10, 4, 1));
        assertEquals(0f, SmoothCurve.smoothToInSegment(10, 4, 5));
    }

    @Test
    void 段完全落在可平滑区间之内时全部实例都可平滑() {
        assertEquals(0f, SmoothCurve.smoothFromInSegment(3, 4, 1));
        assertEquals(4f, SmoothCurve.smoothToInSegment(3, 4, 100));
    }

    @Test
    void 段跨在可平滑区间的左边界上() {
        // 环滑动之后：可平滑区间从绝对号 3 开始，而这一段从绝对号 0 开始 ⇒ 前 3 个直线。
        assertEquals(3f, SmoothCurve.smoothFromInSegment(0, 8, 3));
    }
}
