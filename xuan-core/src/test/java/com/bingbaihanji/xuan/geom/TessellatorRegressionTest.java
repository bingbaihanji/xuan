package com.bingbaihanji.xuan.geom;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 *   <li>{@link #矩形挖两个小方洞面积守恒} —— 两次桥接落在同一个外轮廓顶点上时，
 *       那里会变成被访问三次的"掐点"，必须避免重复使用桥接点。</li>
 *   <li>{@link #面积丢失时发出告警并报出两个数字} —— 面积自查必须能抓到
 *       "静默少画"，并报出期望/实际两个数字。</li>
 *   <li>{@link #正常多洞输入不发告警} —— 自查不能变成永远在响的噪音。</li>
 * </ul>
 *
 * <p>另有两个已知缺陷用例（{@code 两个洞贴着同一条竖直边线时面积守恒}、
 * {@code 方洞与扁洞上下排列时面积守恒}）暂时 {@code @Disabled}，
 * 它们记录的失败数字是实测值，修好后应去掉 {@code @Disabled}。
 *
 * <p><b>注意</b>：{@link #矩形挖去方形洞与圆形洞后面积守恒} 当初是为了钉住
 * "贪心多轮桥接"（洞找不到可见桥接点就延后到下一轮）而写的，但现在它<b>钉不住</b>
 * 了——后来的"在轮廓边上找兜底桥接点"已经覆盖了同一个场景，把贪心多轮关掉该用例
 * 照样通过。贪心多轮仍然保留，理由是实测有效而不是有单测兜底：同一批 2944 个结构化
 * 用例，关掉它失败率从 6.4% 升到 11.8%。改动桥接代码时别指望这条用例替你挡住它。
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

    /**
     * 把 logback 的收集器挂到 {@link Tessellator} 的日志上执行动作，返回收集到的告警文本。
     *
     * @param action 要执行的动作
     * @return 期间产生的日志文本（每条一行）
     */
    private static String captureWarnings(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(Tessellator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
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
    void 矩形挖两个小方洞面积守恒() {
        // 两个洞都离同一个外轮廓顶点最近：若两次桥接落在同一个顶点上，
        // 那里会变成被访问三次的"掐点"，耳切在掐点上找不到合法的耳
        Tessellator t = new Tessellator();
        float[] outer = rect(0f, 0f, 200f, 240f, false);
        float[][] holes = {rect(10f, 10f, 20f, 20f, false), rect(160f, 10f, 180f, 20f, false)};
        int[] holeCounts = {4, 4};

        t.tessellateWithHoles(outer, 4, holes, holeCounts);

        assertAreaConserved(expectedArea(outer, 4, holes, holeCounts), t);
    }

    /**
     * 已知缺陷（暂不通过，故禁用）：桥接点在个别布局下仍然选不出合法的桥。
     *
     * <p>实测：本用例的面积应为 71700，当前实现给 68600，凭空丢掉 3100。
     * 原因是第一个洞的桥横穿图形，把第二个洞所有可见的桥接顶点和边上可见点
     * 都挡住了，最后只能退回"最近顶点"连出一条穿过边界的桥。
     * 二洞布局下的实测失败率约 0.4%（4051 个随机合法用例中 5 个），
     * 三洞约 0.8%，四洞以上显著升高。修好之后请去掉 {@code @Disabled}。
     */
    @Disabled("已知缺陷：两洞贴同一条竖直边线时桥接点选不出，面积丢失 3100")
    @Test
    void 两个洞贴着同一条竖直边线时面积守恒() {
        // 两个洞的左边线同为 x=10：第二个洞连向外轮廓的桥会被第一个洞的桥挡住，
        // 只能退到"在轮廓边上找可见点"，直接连最近顶点会连出一条穿过边界的桥
        Tessellator t = new Tessellator();
        float[] outer = rect(0f, 0f, 280f, 260f, false);
        float[][] holes = {rect(10f, 10f, 40f, 40f, true), rect(10f, 96f, 20f, 116f, true)};
        int[] holeCounts = {4, 4};

        t.tessellateWithHoles(outer, 4, holes, holeCounts);

        assertAreaConserved(expectedArea(outer, 4, holes, holeCounts), t);
    }

    /**
     * 已知缺陷（暂不通过，故禁用）：与上一个用例同源。
     *
     * <p>实测：面积应为 31400，当前实现给 28500，丢掉 2900。
     */
    @Disabled("已知缺陷：第二个洞桥接到第一个洞的环上后，耳切仍会中途放弃，面积丢失 2900")
    @Test
    void 方洞与扁洞上下排列时面积守恒() {
        Tessellator t = new Tessellator();
        float[] outer = rect(0f, 0f, 180f, 180f, false);
        float[][] holes = {rect(10f, 10f, 40f, 40f, false), rect(70f, 10f, 80f, 20f, true)};
        int[] holeCounts = {4, 4};

        t.tessellateWithHoles(outer, 4, holes, holeCounts);

        assertAreaConserved(expectedArea(outer, 4, holes, holeCounts), t);
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
    void 面积丢失时发出告警并报出两个数字() {
        // 用的就是下面 @Disabled 的那个"两个洞贴着同一条竖直边线"用例：
        // 面积自查必须在这种退化上触发，并把少画了多少面积说清楚
        String warnings = captureWarnings(() -> {
            Tessellator t = new Tessellator();
            float[] outer = rect(0f, 0f, 280f, 260f, false);
            float[][] holes = {rect(10f, 10f, 40f, 40f, true), rect(10f, 96f, 20f, 116f, true)};
            t.tessellateWithHoles(outer, 4, holes, new int[]{4, 4});
        });

        assertTrue(warnings.contains("71700"), "告警里应报出期望面积 71700，实际告警：" + warnings);
        assertTrue(warnings.contains("68600"), "告警里应报出实际面积 68600，实际告警：" + warnings);
    }

    @Test
    void 正常多洞输入不发告警() {
        String warnings = captureWarnings(() -> {
            Tessellator t = new Tessellator();
            float[] outer = rect(0f, 0f, 120f, 40f, true);
            float[][] holes = {rect(10f, 10f, 30f, 30f, false), rect(50f, 10f, 70f, 30f, false),
                    rect(90f, 10f, 110f, 30f, false)};
            t.tessellateWithHoles(outer, 4, holes, new int[]{4, 4, 4});
        });

        assertEquals("", warnings, "面积守恒的输入不该告警");
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
