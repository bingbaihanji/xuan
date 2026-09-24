package com.bingbaihanji.jfgl.chart;

/**
 * 一个数据系列：数据 + 样式 + 图型标签。
 *
 * <h2>系列不持有轴</h2>
 * <p>轴由 {@link Chart} 按维度下标持有，{@link SeriesRenderer#render} 收到的
 * {@code axes[]} 与数据维度一一对应。多 Y 轴因此只是"多给几根 {@code Axis}"——
 * 本期不做系列级的轴选择，但它不影响签名（按维度下标对齐已经够表达多轴了）。
 *
 * <h2>样式是可变字段 + 链式 setter</h2>
 * <p>样式会被交互（改颜色、换配色）随时改动，做成不可变对象只会让调用方写一堆
 * "复制一份、改一个字段、再塞回去"。这些 setter 不校验范围之外的语义
 * （比如线宽为负），因为渲染侧对它们的处理是明确的（负线宽 = 不画线），
 * 而不是一个静默的错值。
 */
public final class Series {

    private final String name;

    private final ChartData data;

    private final ChartType type;

    private int color = 0xFFFFFFFF;

    private float lineWidth = 1f;

    private float markerSize = 3f;

    /**
     * 面积图与柱状图的下沿所在的<strong>数值</strong>（不是像素、不是窗口下界）。
     *
     * <p>JavaFX 里对应 {@code ValueAxis.setForceZeroInRange(true)}：AreaChart 与 BarChart
     * 默认把下沿钉在 0 上，于是"轴从 -0.5 起"与"柱子从 0 长出来"两件事互不干扰。
     * 那边是一个布尔量，这边是一个值——因为"下沿在 0"只是最常见的一种，
     * 柱状图从某个参考线（比如均值）长出来同样是常见需求，而值能表达全部。
     *
     * <p><strong>它必须是一个值，不能是"绘图区下边缘"。</strong>写成下边缘的话，
     * 用户在 y 轴上放大（窗口 [0.4, 1.0]）时柱子的高度会<b>跟着轴走</b>——
     * 同一个数值在放大后变成一根高得离谱的柱子，而画面看起来完全正常。
     *
     * <p>退化处理：值落在窗口之外时柱/填充会延伸出绘图区，由裁剪盒截掉
     * （与"轴不做裁剪"是同一条约定）。要"看不见就看不见"，请自己设窗口。
     */
    private float baseline = 0f;

    /**
     * 面积图填充的不透明度（0..1）。
     *
     * <p>默认 0.5，与 JavaFX 的 {@code chart-series-area-fill} 一致（那边是
     * {@code CHART_COLOR_n_TRANSPARENT}，即同一个色相降一半不透明度）。
     * 之所以要有这个默认值而不是"填充也用主色"：面积图的轮廓线与填充同色时，
     * <b>轮廓线在画面上看不出存在过</b>——而"轮廓没画"与"轮廓画了"在那种配置下
     * 逐像素相同，于是那条路径就没有视觉证据了。
     *
     * <p>用 1.0 可以得到实心填充（柱状图固定用主色的不透明度，不受本字段影响）。
     */
    private float fillAlpha = 0.5f;

    /**
     * 柱状图的<strong>类别间距</strong>，单位是"一格宽度的比例"（不是像素）。默认 0.2。
     *
     * <p>一格 = 一个样本在屏幕上占的宽度（{@code 绘图区宽 / 可见样本数}）。
     * 0.2 表示每一格留 20% 的空隙、柱子群占 80%。
     *
     * <p><strong>为什么是比例而不是 JavaFX 那样的像素</strong>：JavaFX 的类别轴每格
     * 宽度基本固定，所以 {@code setCategoryGap(10)} 的像素语义是稳的；而这里的横轴是
     * <b>可缩放的数据下标轴</b>——窗口一拉宽，每格只剩一两像素，像素间距会把柱子挤成
     * 零宽（<b>整张柱状图凭空消失，而"数据没来"也是这个样子</b>）。比例语义下
     * 柱子随格子一起缩小，形状始终成立。
     *
     * <p>与 {@link #barGap} 一样，它是<b>本系列</b>的样式；同一层里并排的多个系列
     * 必须用同一组值，否则柱子会大小不一（这一条由 {@code ChartRenderer} 显式拦住）。
     */
    private float categoryGap = 0.2f;

    /**
     * 柱状图的<strong>同类别内柱间距</strong>，单位是"一根柱宽度的比例"。默认 0.2。
     *
     * <p>与 JavaFX 的 {@code setBarGap} 同一个意思（那边是像素），比例的理由见
     * {@link #categoryGap}。它只影响"同一格里并排的多个系列"——只有一个系列时
     * 分母里的 {@code (系列数 - 1)} 为 0，本字段<strong>完全不起作用</strong>。
     */
    private float barGap = 0.2f;

    /** 热力图/密度图用的配色；折线族不需要，为 null。 */
    private ColorMapping colorMapping;

    /**
     * 构造。
     *
     * @param name 系列名（图例用），不能为空
     * @param data 数据，不能为 null
     * @param type 图型标签，不能为 null
     * @throws IllegalArgumentException 参数非法时
     */
    public Series(String name, ChartData data, ChartType type) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("系列名不能为空");
        }
        if (data == null) {
            throw new IllegalArgumentException("系列的数据不能为 null");
        }
        if (type == null) {
            throw new IllegalArgumentException("图型标签不能为 null");
        }
        this.name = name;
        this.data = data;
        this.type = type;
    }

    /** 系列名。 */
    public String name() {
        return name;
    }

    /** 数据。 */
    public ChartData data() {
        return data;
    }

    /** 图型标签。 */
    public ChartType type() {
        return type;
    }

    /** 主色，ARGB。 */
    public int color() {
        return color;
    }

    /** 设置主色。 */
    public Series color(int argb) {
        this.color = argb;
        return this;
    }

    /** 线宽（用户坐标单位）。 */
    public float lineWidth() {
        return lineWidth;
    }

    /** 设置线宽。 */
    public Series lineWidth(float width) {
        this.lineWidth = width;
        return this;
    }

    /** 标记点半径（用户坐标单位）。 */
    public float markerSize() {
        return markerSize;
    }

    /** 设置标记点半径。 */
    public Series markerSize(float size) {
        this.markerSize = size;
        return this;
    }

    /** 配色；未设置时为 null。 */
    public ColorMapping colorMapping() {
        return colorMapping;
    }

    /** 设置配色（热力图/密度图用）。 */
    public Series colorMapping(ColorMapping mapping) {
        this.colorMapping = mapping;
        return this;
    }

    /** 面积与柱状图的下沿数值。默认 0。 */
    public float baseline() {
        return baseline;
    }

    /** 设置面积/柱状图的下沿数值（见字段说明）。 */
    public Series baseline(float value) {
        this.baseline = value;
        return this;
    }

    /** 面积填充的不透明度。默认 0.5。 */
    public float fillAlpha() {
        return fillAlpha;
    }

    /**
     * 设置面积填充的不透明度。
     *
     * @param alpha 0..1；越界<strong>不抛异常</strong>，由面积渲染器钳到 [0,1]
     *              （&gt;1 即实心、&le;0 即不画填充）。不抛的理由与线宽为负相同：
     *              "把它钳住"是一个明确、可预期的处置，而一个因为样式值越界
     *              就抛异常的图表，用户只能靠读栈去猜是哪个字段
     * @return 自身，便于链式调用
     */
    public Series fillAlpha(float alpha) {
        this.fillAlpha = alpha;
        return this;
    }

    /** 柱状图的类别间距（占一格宽度的比例）。默认 0.2。 */
    public float categoryGap() {
        return categoryGap;
    }

    /**
     * 设置柱状图的类别间距。
     *
     * @param gap 占一格宽度的比例；&ge;1 会让柱子退化成零宽（不画，与线宽为负同类处置）
     * @return 自身，便于链式调用
     */
    public Series categoryGap(float gap) {
        this.categoryGap = gap;
        return this;
    }

    /** 柱状图的同类别内柱间距（占一根柱宽度的比例）。默认 0.2。 */
    public float barGap() {
        return barGap;
    }

    /**
     * 设置同类别内的柱间距。
     *
     * @param gap 占一根柱宽度的比例；0 表示并排的柱子紧挨着
     * @return 自身，便于链式调用
     */
    public Series barGap(float gap) {
        this.barGap = gap;
        return this;
    }
}
