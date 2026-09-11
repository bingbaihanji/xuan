package com.bingbaihanji.jfgl.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Tessellator} 的几何回归测试。
 *
 * <p>{@code TessellatorTest} / {@code TessellatorHoleTest} 只数三角形个数、只在小坐标的
 * 单个洞上比面积，抓不住"数量对、面积错"（三角形重叠、被挖掉的洞又长回来、
 * 耳切中途放弃）这类错误。这里一律用<b>测试自带的鞋带公式</b>从输入多边形独立算出
 * 期望面积，再和三角化结果的总面积比对，容差取相对 {@value #TOLERANCE}。
 *
 * <p>每个用例都对应一个真实踩过的坑，改动实现时如果把它踩回去，对应用例必须变红：
 * <ul>
 *   <li>{@link #矩形挖去方形洞与圆形洞后面积守恒} —— 洞的合并顺序：圆形洞当初
 *       找不到可见桥接点，必须等方形洞先合并进来再重试（贪心多轮桥接）。</li>
 *   <li>{@link #三个洞横排时每个洞都被挖去} —— 桥接必然制造共线顶点，
 *       耳切若拒绝零面积的"退化耳"就会卡死并丢掉大片面积。</li>
 *   <li>{@link #六百顶点星形外轮廓带三个洞时面积守恒} —— 桥只"擦到"另一个洞的顶点
 *       （交叉点正好落在顶点上，不算真正相交）同样会让合并结果退化，必须一并避开。</li>
 *   <li>{@link #桥擦到另一个洞的顶点时仍能正确挖去} —— 上一条的最小复现。</li>
 *   <li>{@link #洞与外轮廓同向时仍然被挖去} —— 洞的绕向归一化（外轮廓逆时针则洞顺时针）。</li>
 *   <li>{@link #多洞输出的三角形绕向一致为逆时针} —— 多洞输入下输出绕向的一致性。</li>
 * </ul>
 */
class TessellatorRegressionTest {

    /** 面积相对容差。 */
    private static final double TOLERANCE = 1e-3;

    /**
     * 鞋带公式：多边形有符号面积的绝对值。
     *
     * <p>这是测试自带的独立口径，不调用被测代码，避免"实现错、期望值跟着错"。
     *
     * @param pts   扁平顶点数组 {@code [x0,y0, x1,y1, ...]}
     * @param count 顶点个数
     * @return 多边形面积（恒为非负）
     */
    private static double shoelace(float[] pts, int count) {
        double sum = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            sum += (double) pts[i * 2] * pts[j * 2 + 1] - (double) pts[j * 2] * pts[i * 2 + 1];
        }
        return Math.abs(sum * 0.5);
    }

    /** 期望面积 = 外轮廓面积 − 每个洞的面积。 */
    private static double expectedArea(float[] outer, int outerCount,
                                       float[][] holes, int[] holeCounts) {
        double expected = shoelace(outer, outerCount);
        for (int h = 0; h < holes.length; h++) {
            expected -= shoelace(holes[h], holeCounts[h]);
        }
        return expected;
    }

    /** 三角形列表的总面积（每个三角形面积取绝对值）。 */
    private static double totalArea(float[] triangles) {
        double sum = 0;
        for (int i = 0; i < triangles.length; i += 6) {
            double x0 = triangles[i], y0 = triangles[i + 1];
            double x1 = triangles[i + 2], y1 = triangles[i + 3];
            double x2 = triangles[i + 4], y2 = triangles[i + 5];
            sum += Math.abs((x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)) * 0.5;
        }
        return sum;
    }

    /** 断言三角化结果的总面积等于期望面积。 */
    private static void assertAreaConserved(double expected, Tessellator t) {
        assertEquals(expected, totalArea(t.triangles()), Math.abs(expected) * TOLERANCE,
                "面积不守恒：期望 " + expected + "，实际 " + totalArea(t.triangles())
                        + "（相差 " + (totalArea(t.triangles()) - expected) + "）");
    }

    /**
     * 构建矩形顶点。
     *
     * @param ccw 为 {@code true} 时逆时针（左下 → 右下 → 右上 → 左上）
     */
    private static float[] rect(float x0, float y0, float x1, float y1, boolean ccw) {
        if (ccw) {
            return new float[]{x0, y0, x1, y0, x1, y1, x0, y1};
        }
        return new float[]{x0, y0, x0, y1, x1, y1, x1, y0};
    }

    /**
     * 构建正多边形的顶点（用于近似圆）。
     *
     * @param ccw 为 {@code true} 时逆时针
     */
    private static float[] circle(float cx, float cy, float r, int segments, boolean ccw) {
        float[] pts = new float[segments * 2];
        for (int i = 0; i < segments; i++) {
            double a = 2 * Math.PI * i / segments * (ccw ? 1 : -1);
            pts[i * 2] = (float) (cx + r * Math.cos(a));
            pts[i * 2 + 1] = (float) (cy + r * Math.sin(a));
        }
        return pts;
    }

    /**
     * 构建星形（尖角朝外的凹多边形）顶点：半径在 {@code rOuter} / {@code rInner} 间交替。
     */
    private static float[] star(int segments, float rOuter, float rInner) {
        float[] pts = new float[segments * 2];
        for (int i = 0; i < segments; i++) {
            double a = Math.PI * i / segments * 2;
            double r = (i % 2 == 0) ? rOuter : rInner;
            pts[i * 2] = (float) (r * Math.cos(a));
            pts[i * 2 + 1] = (float) (r * Math.sin(a));
        }
        return pts;
    }

    @Test
    void 矩形挖去方形洞与圆形洞后面积守恒() {
        // 圆形洞的最右顶点 (37,30) 到外轮廓任意顶点的连线，要么穿过圆形洞自身，
        // 要么撞进方形洞 (60,15)-(85,45)——只有先把方形洞合并进来，
        // 圆形洞才有可见的桥接点（贪心多轮桥接的必要性就在这里）
        Tessellator t = new Tessellator();
        float[] outer = rect(0f, 0f, 100f, 60f, true);
        float[][] holes = {circle(25f, 30f, 12f, 16, false), rect(60f, 15f, 85f, 45f, false)};
        int[] holeCounts = {16, 4};

        t.tessellateWithHoles(outer, 4, holes, holeCounts);

        assertAreaConserved(expectedArea(outer, 4, holes, holeCounts), t);
    }

    @Test
    void 三个洞横排时每个洞都被挖去() {
        // 桥接点、洞顶点大量共线：耳切必须允许零面积的退化耳，否则中途卡死
        Tessellator t = new Tessellator();
        float[] outer = rect(0f, 0f, 120f, 40f, true);
        float[][] holes = {rect(10f, 10f, 30f, 30f, false), rect(50f, 10f, 70f, 30f, false),
                rect(90f, 10f, 110f, 30f, false)};
        int[] holeCounts = {4, 4, 4};

        t.tessellateWithHoles(outer, 4, holes, holeCounts);

        assertAreaConserved(expectedArea(outer, 4, holes, holeCounts), t);
    }

    @Test
    void 六百顶点星形外轮廓带三个洞时面积守恒() {
        // 三个洞的圆心都在 x 轴上，桥的连线正好擦过相邻洞的顶点
        // （交叉点落在顶点上，按"真正相交"判定不算相交，必须另外排除）
        Tessellator t = new Tessellator();
        float[] outer = star(600, 1000f, 600f);
        float[][] holes = {circle(-200f, 0f, 50f, 20, false), circle(0f, 0f, 50f, 20, false),
                circle(200f, 0f, 50f, 20, false)};
        int[] holeCounts = {20, 20, 20};

        t.tessellateWithHoles(outer, 600, holes, holeCounts);

        assertAreaConserved(expectedArea(outer, 600, holes, holeCounts), t);
    }

    @Test
    void 桥擦到另一个洞的顶点时仍能正确挖去() {
        // 三个洞的圆心共线在 x 轴上：中间洞的最右顶点 (50,0) 到外轮廓最右顶点 (300,0)
        // 的连线，正好从右边那个洞的顶点 (100,0)、(200,0) 上穿过去
        Tessellator t = new Tessellator();
        float[] outer = circle(0f, 0f, 300f, 32, true);
        float[][] holes = {circle(-150f, 0f, 50f, 20, false), circle(0f, 0f, 50f, 20, false),
                circle(150f, 0f, 50f, 20, false)};
        int[] holeCounts = {20, 20, 20};

        t.tessellateWithHoles(outer, 32, holes, holeCounts);

        assertAreaConserved(expectedArea(outer, 32, holes, holeCounts), t);
    }

    @Test
    void 洞与外轮廓同向时仍然被挖去() {
        // 洞按逆时针（与外轮廓同向）给出，也必须被"挖去"而不是"长回来"
        Tessellator t = new Tessellator();
        float[] outer = rect(0f, 0f, 20f, 20f, true);
        float[][] holes = {rect(5f, 5f, 15f, 15f, true)};
        int[] holeCounts = {4};

        t.tessellateWithHoles(outer, 4, holes, holeCounts);

        assertAreaConserved(expectedArea(outer, 4, holes, holeCounts), t);
    }

    @Test
    void 多洞输出的三角形绕向一致为逆时针() {
        // 绕向必须穿过"桥接 + 洞反向"这一整套保持住：外轮廓逆时针，
        // 洞被归一化为顺时针，合并结果仍应为逆时针。
        // 桥接会在共线的桥端点处产生零面积三角形，故下界取 -1e-6 而不是 0。
        Tessellator t = new Tessellator();
        float[] outer = rect(0f, 0f, 120f, 40f, true);
        float[][] holes = {rect(10f, 10f, 30f, 30f, false), rect(50f, 10f, 70f, 30f, false),
                rect(90f, 10f, 110f, 30f, false)};
        t.tessellateWithHoles(outer, 4, holes, new int[]{4, 4, 4});

        float[] triangles = t.triangles();
        assertTrue(triangles.length > 0, "应当产出三角形");
        for (int i = 0; i < triangles.length; i += 6) {
            float x0 = triangles[i], y0 = triangles[i + 1];
            float x1 = triangles[i + 2], y1 = triangles[i + 3];
            float x2 = triangles[i + 4], y2 = triangles[i + 5];
            float signed = ((x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)) * 0.5f;
            assertTrue(signed >= -1e-6f,
                    "第 " + (i / 6) + " 个三角形绕向为顺时针（有符号面积 " + signed + "）");
        }
    }
}
