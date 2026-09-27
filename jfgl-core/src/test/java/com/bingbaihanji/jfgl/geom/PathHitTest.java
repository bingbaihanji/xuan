package com.bingbaihanji.jfgl.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PathHit} 的单元测试。
 *
 * <p>它是**纯计算**，所以这一层不碰 GL、不碰 {@code Path}——喂进去的就是扁平数组，
 * 判据也全部是"某个点该不该命中"这种能一眼算出来的量。
 *
 * <p>⚠️ 注意本类**不测边界上的点**：那正是 {@link PathHit} 的类文档里声明"不保证"的
 * 东西（浮点比较在边界上没有稳定答案）。要用在边界上时调用方自己留余量。
 */
class PathHitTest {

    /** 一个三角形的扁平数组。 */
    private static float[] tri(float x0, float y0, float x1, float y1,
                               float x2, float y2) {
        return new float[]{x0, y0, x1, y1, x2, y2};
    }

    /** 一条轮廓的偏移表与顶点数表（只有一条）。 */
    private static final int[] ONE = {0};
    private static final int[] ONE_COUNT = {4};

    // ------------------------------------------------------------------
    // isPointInTriangles
    // ------------------------------------------------------------------

    @Test
    void 三角形内部命中外部不命中() {
        float[] t = tri(0f, 0f, 10f, 0f, 0f, 10f);
        assertTrue(PathHit.isPointInTriangles(t, 6, 2f, 2f), "(2,2) 在直角三角形内部");
        assertFalse(PathHit.isPointInTriangles(t, 6, 8f, 8f), "(8,8) 在斜边外侧");
    }

    @Test
    void 多个三角形取并集而不是覆盖层数() {
        // 两块**重叠**的三角形。(4,1) 同时落在两块里 ⇒ 覆盖层数为 2（偶数），
        // 若判据误写成"层数奇偶"就会判否。描边的三角形天然互相重叠，
        // 而墨迹覆盖的区域是**并集**——这条钉的就是这一点。
        float[] t = new float[]{
                0f, 0f, 10f, 0f, 0f, 10f,
                0f, 0f, 10f, 0f, 10f, 10f,
        };
        assertTrue(PathHit.isPointInTriangles(t, 12, 4f, 1f),
                "(4,1) 落在两块的交叠处，覆盖层数是 2——但它在**并集**里");
        assertTrue(PathHit.isPointInTriangles(t, 12, 2f, 2f), "只在第一块里");
        assertTrue(PathHit.isPointInTriangles(t, 12, 8f, 8f), "只在第二块里");
        assertFalse(PathHit.isPointInTriangles(t, 12, 20f, 20f), "两块之外");
    }

    /**
     * ★ **三点重合的退化三角形必须被跳过**。
     *
     * <p>它在半平面判据下会命中**任意一点**：三个有符号面积全是 0 ⇒
     * {@code !(有正 && 有负)} 为真。而画面上那个三角形的面积是 0，本来就什么都没画。
     * 生成器确实会产出退化的三角形（零长度的描边段、超限回退的接头）。
     */
    @Test
    void 三点重合的退化三角形不命中任何点() {
        float[] t = tri(5f, 5f, 5f, 5f, 5f, 5f);
        assertFalse(PathHit.isPointInTriangles(t, 6, 100f, 100f),
                "退化成一点的三角形面积是 0，不该命中 (100,100)");
        assertFalse(PathHit.isPointInTriangles(t, 6, 5f, 5f),
                "连它自己那个点上也不该命中——那块本来就什么都没画");
    }

    /**
     * ★ **三点共线（零面积）的三角形也必须被跳过**。
     *
     * <p>它与上一条是**两个不同的退化**，而且这一条的半平面判据**会反过来**：
     * 共线时三个有符号面积同号（点在同侧）⇒ 判"在内部"。实测 (5,1) 相对
     * y=0 那条线上的三角形 (0,0)(5,0)(10,0) 正是这种情况。
     */
    @Test
    void 三点共线的零面积三角形不命中() {
        float[] t = tri(0f, 0f, 5f, 0f, 10f, 0f);
        assertFalse(PathHit.isPointInTriangles(t, 6, 5f, 1f),
                "(5,1) 在那条线段上方——面积是 0，不该命中");
        assertFalse(PathHit.isPointInTriangles(t, 6, 5f, -1f), "下方同样不该命中");
    }

    @Test
    void 三角形判定的空输入与非有限查询点() {
        assertFalse(PathHit.isPointInTriangles(null, 6, 0f, 0f));
        assertFalse(PathHit.isPointInTriangles(tri(0f, 0f, 10f, 0f, 0f, 10f), 0, 1f, 1f),
                "floatCount 为 0 ⇒ 一个三角形都没有");
        float[] t = tri(0f, 0f, 10f, 0f, 0f, 10f);
        assertFalse(PathHit.isPointInTriangles(t, 6, Float.NaN, 2f), "NaN 查询点");
        assertFalse(PathHit.isPointInTriangles(t, 6, 2f, Float.POSITIVE_INFINITY), "+Inf 查询点");
    }

    // ------------------------------------------------------------------
    // isPointInContours
    // ------------------------------------------------------------------

    /** 一个边长 10 的正方形轮廓（顺时针或逆时针都一样——奇偶规则不看方向）。 */
    private static final float[] SQUARE = {0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f};

    @Test
    void 正方形轮廓的内部命中外部不命中() {
        assertTrue(PathHit.isPointInContours(SQUARE, ONE, ONE_COUNT, 1, 5f, 5f));
        assertFalse(PathHit.isPointInContours(SQUARE, ONE, ONE_COUNT, 1, 20f, 5f), "右侧外面");
        assertFalse(PathHit.isPointInContours(SQUARE, ONE, ONE_COUNT, 1, -1f, 5f), "左侧外面");
    }

    /**
     * ★ **环：洞里必须判否**。
     *
     * <p>外方 (0,0)–(10,10)、内方 (3,3)–(7,7)。(5,5) 落在大方内部、小方内部
     * ⇒ 向右的射线穿过**两条**轮廓 ⇒ 交叉次数 2（偶数）⇒ 判否。
     *
     * <p>这条是"奇偶规则 == 包含关系"那一条等价的直接体现：JFGL 的填充按嵌套深度
     * 把内方判成**洞**，而洞里是不填色的——两套语义在这里必须一致。
     */
    @Test
    void 环的洞里判否() {
        float[] ring = {
                0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f,     // 外轮廓（顶点 0..3）
                3f, 3f, 7f, 3f, 7f, 7f, 3f, 7f,          // 洞（顶点 4..7）
        };
        int[] offsets = {0, 4};
        int[] counts = {4, 4};

        assertFalse(PathHit.isPointInContours(ring, offsets, counts, 2, 5f, 5f),
                "(5,5) 在洞里 ⇒ 不填充");
        assertTrue(PathHit.isPointInContours(ring, offsets, counts, 2, 1f, 5f),
                "(1,5) 在环带上 ⇒ 填充");
        assertTrue(PathHit.isPointInContours(ring, offsets, counts, 2, 5f, 1f),
                "(5,1) 在环带上 ⇒ 填充");
        assertFalse(PathHit.isPointInContours(ring, offsets, counts, 2, -1f, 5f), "环外");
    }

    /**
     * 两条**互不相交**的轮廓：它们之间的空隙必须判否。
     *
     * <p>这条钉的是"按位置约定分类"（第一个是外轮廓、其余是洞）那种实现——它在
     * 这种输入下会把第二块当成洞，于是两块之间的空隙判**真**。JFGL 的填充明确
     * 按包含关系分类，所以命中判定也必须一致。
     */
    @Test
    void 两条不相交轮廓之间的空隙判否() {
        float[] two = {
                0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f,         // 左块
                10f, 0f, 14f, 0f, 14f, 4f, 10f, 4f,     // 右块
        };
        int[] offsets = {0, 4};
        int[] counts = {4, 4};

        assertTrue(PathHit.isPointInContours(two, offsets, counts, 2, 2f, 2f), "左块里");
        assertTrue(PathHit.isPointInContours(two, offsets, counts, 2, 12f, 2f), "右块里");
        assertFalse(PathHit.isPointInContours(two, offsets, counts, 2, 7f, 2f),
                "两块之间的空隙——它落在任何轮廓之外");
    }

    @Test
    void 轮廓判定的空输入与退化轮廓() {
        assertFalse(PathHit.isPointInContours(null, ONE, ONE_COUNT, 1, 5f, 5f));
        assertFalse(PathHit.isPointInContours(SQUARE, null, ONE_COUNT, 1, 5f, 5f));
        assertFalse(PathHit.isPointInContours(SQUARE, ONE, ONE_COUNT, 0, 5f, 5f),
                "轮廓条数为 0");
        // 一条只有两个点的"轮廓"围不出面积
        int[] twoCount = {2};
        assertFalse(PathHit.isPointInContours(SQUARE, ONE, twoCount, 1, 5f, 5f));
        assertFalse(PathHit.isPointInContours(SQUARE, ONE, ONE_COUNT, 1, Float.NaN, 5f));
    }

    /**
     * 射线正好压在一个顶点上时要**只数一次**（半开区间判据）。
     *
     * <p>正方形 (0,0)(10,0)(10,10)(0,10)，查询点取 y = 0 —— 正好穿过下面那条边的
     * 两个端点。闭区间判据会把它们各数一次（共两次，偶数 ⇒ 判否），而正确答案是
     * "在边界上"（本类不保证），关键是**不能因为多了一次而整体反过来**。
     * 这里用一个 y 更小的点做对照，确认 0 这一行没有把奇偶翻掉。
     */
    @Test
    void 射线压在顶点上不会把奇偶翻反() {
        assertTrue(PathHit.isPointInContours(SQUARE, ONE, ONE_COUNT, 1, 5f, 1f),
                "略高于下边缘 ⇒ 内部");
        assertFalse(PathHit.isPointInContours(SQUARE, ONE, ONE_COUNT, 1, 5f, -1f),
                "略低于下边缘 ⇒ 外部");
    }
}
