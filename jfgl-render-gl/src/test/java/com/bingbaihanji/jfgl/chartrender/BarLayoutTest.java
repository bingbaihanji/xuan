package com.bingbaihanji.jfgl.chartrender;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BarLayout} 的单元测试：柱宽与柱心偏移。
 *
 * <p><b>这里钉的是"并排分组"的算术本身</b>，因为它在画面上几乎没有症状：
 * 柱子宽度差一点、整组柱子偏了半根柱宽，看起来都像"这个库就是这么画的"。
 * 像素口径的那几条在 {@code ChartVerifier} 里（它们只能证明"某一张图对"，
 * 证明不了公式在别的 {@code (n, gap)} 下也对）。
 *
 * <p>核心的不变量有两条，都是<b>用"柱群的左右边缘"表达的</b>而不是用柱宽表达的：
 * <ol>
 *   <li>第一根柱的左边缘恰好是 {@code 柱群左端 = -群宽/2}；</li>
 *   <li>最后一根柱的右边缘恰好是 {@code +群宽/2}。</li>
 * </ol>
 * 这两条一旦成立，柱群就"正好占满留出来的那一段"——而柱宽公式里漏掉
 * {@code (n-1)·barGap} 那一项时，它们会同时失败（柱群比留出来的那段宽）。
 */
class BarLayoutTest {

    /** 一格宽 20px、类别间距 0.2 → 群宽 16px。够小，手算得出对应的断言值。 */
    private static final float CELL = 20f;

    private static final float CATEGORY_GAP = 0.2f;

    /** 第一根柱左边缘相对样本中心的偏移。 */
    private static float leftEdge(float barGap, int count, int slot) {
        return BarLayout.offset(CELL, CATEGORY_GAP, barGap, count, slot)
                - BarLayout.halfWidth(CELL, CATEGORY_GAP, barGap, count);
    }

    /** 最后一根柱右边缘相对样本中心的偏移。 */
    private static float rightEdge(float barGap, int count) {
        return BarLayout.offset(CELL, CATEGORY_GAP, barGap, count, count - 1)
                + BarLayout.halfWidth(CELL, CATEGORY_GAP, barGap, count);
    }

    @Test
    void 单个系列时柱子占满整格减类别间距且barGap不起作用() {
        // 群宽 = 20 × (1 - 0.2) = 16
        assertEquals(16f, BarLayout.barWidth(CELL, CATEGORY_GAP, 0.2f, 1), 1e-4f);
        assertEquals(16f, BarLayout.barWidth(CELL, CATEGORY_GAP, 0f, 1), 1e-4f);
        assertEquals(16f, BarLayout.barWidth(CELL, CATEGORY_GAP, 5f, 1), 1e-4f);

        // 柱心就在样本上（偏移 0）——这一点决定了"窗口取 [0, N-1] 时首尾两根柱
        // 各有一半在绘图区外"，是刻意的（另一种口径会让最后一根柱永远看不见）。
        assertEquals(0f, BarLayout.offset(CELL, CATEGORY_GAP, 0.2f, 1, 0), 1e-4f);
    }

    @Test
    void 两根柱时间距把群宽分成三份而不是额外加宽() {
        // barGap = 0.5 → 分母 2 + 1×0.5 = 2.5 → 柱宽 16/2.5 = 6.4
        assertEquals(6.4f, BarLayout.barWidth(CELL, CATEGORY_GAP, 0.5f, 2), 1e-4f);
        // 偏移：0×6.4×1.5 + 3.2 - 8 = -4.8；1×6.4×1.5 + 3.2 - 8 = +4.8
        assertEquals(-4.8f, BarLayout.offset(CELL, CATEGORY_GAP, 0.5f, 2, 0), 1e-4f);
        assertEquals(4.8f, BarLayout.offset(CELL, CATEGORY_GAP, 0.5f, 2, 1), 1e-4f);
        // 于是第一根柱占 [-8, -1.6]、第二根占 [1.6, 8]，中间的空隙正好是 0.5 根柱宽
        assertEquals(-8f, leftEdge(0.5f, 2, 0), 1e-4f);
        assertEquals(8f, rightEdge(0.5f, 2), 1e-4f);
    }

    @Test
    void 柱群正好占满留出来的那一段任何间距与系列数都成立() {
        // 这是本类的核心不变量，也是"柱宽公式里有没有漏掉 (n-1)·barGap"唯一的判别式：
        // 分母写成 n 时柱宽会偏大，柱群左右边缘会超出 -群宽/2 .. +群宽/2。
        float[] gaps = {0f, 0.2f, 1f, 3f};
        for (int count = 1; count <= 4; count++) {
            for (float gap : gaps) {
                float groupHalf = CELL * (1f - CATEGORY_GAP) * 0.5f;
                assertEquals(-groupHalf, leftEdge(gap, count, 0), 1e-4f,
                        "count=" + count + " barGap=" + gap + " 时第一根柱没贴住柱群左端");
                assertEquals(groupHalf, rightEdge(gap, count), 1e-4f,
                        "count=" + count + " barGap=" + gap + " 时最后一根柱没贴住柱群右端");

                // 相邻两根柱之间恰好隔着 barGap 根柱宽（没有重叠、也没有多余的空隙）
                if (count >= 2) {
                    float barWidth = BarLayout.barWidth(CELL, CATEGORY_GAP, gap, count);
                    float a = BarLayout.offset(CELL, CATEGORY_GAP, gap, count, 0);
                    float b = BarLayout.offset(CELL, CATEGORY_GAP, gap, count, 1);
                    assertEquals(barWidth * (1f + gap), b - a, 1e-4f,
                            "count=" + count + " barGap=" + gap + " 时相邻柱心间距不对");
                }
            }
        }
    }

    @Test
    void 类别间距吃掉整格时柱子退化成零宽而不是抛异常() {
        // 与"线宽为负 = 不画线"同一条处置：退化的样式值不是错误配置，
        // 而"整张柱状图一个像素都没有"与"数据没来"在画面上确实分不开——
        // 所以这一条由 ChartVerifier 里那条"柱子存在"的反证断言兜着。
        assertEquals(0f, BarLayout.barWidth(CELL, 1f, 0.2f, 1), 1e-4f);
        assertTrue(BarLayout.barWidth(CELL, 1.5f, 0.2f, 1) < 0f, "间距吃掉整格时柱宽为负");
        assertEquals(0f, BarLayout.halfWidth(CELL, 1f, 0.2f, 1), 1e-4f);
    }

    @Test
    void 非法的系列数与槽位会抛异常() {
        // 系列数 0 会让分母算出一根无限宽的柱子——而"无限宽"在屏幕上只是一块铺满的色块。
        assertThrows(IllegalArgumentException.class,
                () -> BarLayout.barWidth(CELL, CATEGORY_GAP, 0.2f, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BarLayout.halfWidth(CELL, CATEGORY_GAP, 0.2f, 0));
        // 槽位越界意味着"本层第 3 根，一共 2 根"，那个偏移落在柱群之外。
        assertThrows(IllegalArgumentException.class,
                () -> BarLayout.offset(CELL, CATEGORY_GAP, 0.2f, 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> BarLayout.offset(CELL, CATEGORY_GAP, 0.2f, 2, -1));
    }
}
