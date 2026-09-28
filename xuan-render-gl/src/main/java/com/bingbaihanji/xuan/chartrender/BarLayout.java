package com.bingbaihanji.xuan.chartrender;

/**
 * 柱状图的柱宽与柱心偏移。<strong>纯算术，零 GL 依赖。</strong>
 *
 * <h2>为什么单独成类</h2>
 * <p>"一格宽度 + 类别间距 + 柱间距 + 本系列是第几根" → "柱的半宽与柱心偏移"这段算术
 * 是柱状图唯一的几何来源，而它<b>算错时的表现是画面完全正常</b>：
 * 柱子宽度差一点、整组柱子偏了半根柱宽，看起来都像"这个库就是这么画的"。
 * 抽成纯函数之后它可以被穷举测试，而不必靠肉眼看窗口（与 {@link WindowRange} 同一条理由）。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li><b>类别</b>：一个样本占的屏幕宽度，即 {@code 绘图区宽 / 可见样本数}。
 *       柱状图的横轴仍是数据下标轴（不是 JavaFX 那种类别轴），一格就是相邻两个样本
 *       之间的间隔。</li>
 *   <li><b>柱心在样本上，不在格子中心</b>。样本 {@code i} 的屏幕 x 由
 *       {@code ChartRenderLayout.screenX(i)} 给出，柱以它为中心左右撑开。
 *       于是窗口取 {@code [0, N-1]} 时最左与最右两根柱各有一半在绘图区之外、被裁掉——
 *       <b>这是刻意的</b>：另一种口径（柱占满 {@code [x_i, x_{i+1}]}）会让<b>最后一根柱
 *       整个落在绘图区右侧、永远看不见</b>，而"少了一根柱"与"数据少一个点"在视觉上
 *       完全一样。要让每根柱都完整，把 x 轴窗口左右各放半格即可
 *       （{@code setWindow(-0.5, N-0.5)}，JavaFX 的类别轴本质上就是这么取的）。</li>
 *   <li><b>并排（分组）</b>：同一层里的多个柱状系列每格并排。<b>不做堆叠</b>——
 *       堆叠需要知道"上一根的顶在哪里"，那是一个跨实例的状态，本期的实例化布局
 *       （每实例两个 float 的数值）表达不了。</li>
 * </ul>
 *
 * <h2>公式</h2>
 * <pre>
 *   群宽 groupW = 格宽 × (1 - categoryGap)
 *   柱宽 barW   = groupW / (n + (n-1) × barGap)          // n = 并排的系列数
 *   第 k 根的柱心偏移 = k × barW × (1 + barGap) + barW/2 - groupW/2
 * </pre>
 * <p>{@code n = 1} 时 {@code barW = groupW}、偏移为 0——柱心地就在样本上，
 * 且 {@code barGap} <b>完全不起作用</b>（{@code n-1 = 0}）。这一条有测试钉着：
 * "只有一个系列时 barGap 不影响任何东西"是用户最容易误解的地方。
 */
public final class BarLayout {

    private BarLayout() {
    }

    /**
     * 一根柱的半宽（设备像素）。
     *
     * @param pxPerSample 一格宽度（设备像素）
     * @param categoryGap 类别间距，占一格宽度的比例
     * @param barGap      同类别内柱间距，占一根柱宽度的比例
     * @param count       同一层里并排的柱状系列数，必须 &ge; 1
     * @return 半宽；退化配置（{@code categoryGap ≥ 1} 等）下 ≤ 0，即"不画"
     * @throws IllegalArgumentException {@code count < 1} 时
     */
    public static float halfWidth(float pxPerSample, float categoryGap, float barGap, int count) {
        return barWidth(pxPerSample, categoryGap, barGap, count) * 0.5f;
    }

    /**
     * 柱宽（设备像素）。见类文档的公式。
     *
     * <p><strong>分母 {@code n + (n-1)·barGap} 不能省成 {@code n}</strong>：
     * 省掉之后柱间距会从"群宽的一部分"变成"额外加出来的宽度"，于是并排的柱子
     * 整体超出自己那一格、压到邻居的格子上——而每根柱本身看起来都很正常。
     * {@code n = 1} 时分母就是 1，这一项自动消失。
     *
     * @throws IllegalArgumentException {@code count < 1} 时
     */
    public static float barWidth(float pxPerSample, float categoryGap, float barGap, int count) {
        if (count < 1) {
            throw new IllegalArgumentException(
                    "并排的柱状系列数必须至少为 1，实际 " + count
                            + "。传 0 会让下面的分母（n + (n-1)·barGap）算出一根无限宽的柱子，"
                            + "而无限宽在屏幕上看起来只是一根铺满的色块。");
        }
        float groupWidth = pxPerSample * (1f - categoryGap);
        float denominator = count + (count - 1) * barGap;
        return groupWidth / denominator;
    }

    /**
     * 第 {@code slot} 根柱的柱心相对样本中心的偏移（设备像素，可为负）。
     *
     * @param slot 本系列在本层柱状系列里的序号，{@code 0 ≤ slot < count}
     * @throws IllegalArgumentException {@code count < 1} 或 {@code slot} 越界时
     */
    public static float offset(float pxPerSample, float categoryGap, float barGap,
                               int count, int slot) {
        if (slot < 0 || slot >= count) {
            throw new IllegalArgumentException(
                    "柱的序号越界：" + slot + "，本层共有 " + count + " 根并排");
        }
        float barWidth = barWidth(pxPerSample, categoryGap, barGap, count);
        float groupWidth = pxPerSample * (1f - categoryGap);
        return slot * barWidth * (1f + barGap) + barWidth * 0.5f - groupWidth * 0.5f;
    }
}
