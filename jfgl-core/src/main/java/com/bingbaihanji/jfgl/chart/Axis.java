package com.bingbaihanji.jfgl.chart;

/**
 * 一根轴：一个显示窗口，加一对互逆的换算。
 *
 * <h2>轴不持有数据</h2>
 * <p>这条抄 chart-fx：范围由数据自己声明（{@link ChartData#axisRange(int)}），
 * 轴只持有 {@link AxisRange}（用来取名字、单位、以及"回到数据自己的范围"）与
 * <strong>当前显示窗口</strong>。轴因此可以脱离数据存在，同一个范围也能被多个轴共享——
 * 多 Y 轴因此是自然结果而不是特例。
 *
 * <h2>窗口的稳定化在这个类的构造与 setWindow 里</h2>
 * <p>窗口起点与终点的差永远为正：退化时走 {@link AxisRange#withMinimumSpan()}，
 * 对数轴上走 {@link AxisRange#withPositiveMin()}。
 * <strong>这是"不除零"的唯一防线</strong>——换算里除以零得到的 NaN 会进入顶点缓冲，
 * 结果是整条曲线连同同批次别的图元一起消失，不报错、只是没了。
 *
 * <h2>轴不做裁剪</h2>
 * <p>窗口之外的值照样线性外推（对数轴按对数外推）。裁剪是渲染侧 {@code glScissor} 的事：
 * 轴要是自己钳位，症状是"曲线在边界上被压平"，比画出去更难查。
 *
 * <h2>线程</h2>
 * <p>非线程安全：显示窗口会被交互（拖动、缩放）改动，而那发生在 UI 线程。
 * 本期约定轴只在单一线程上使用（② 的渲染线程）。
 */
public final class Axis {

    private final AxisType type;

    /** 数据自己声明的范围（名字与单位的来源，也是"回到默认窗口"的目标）。 */
    private final AxisRange range;

    /** 当前显示窗口的下界。 */
    private double windowMin;

    /** 当前显示窗口的上界。 */
    private double windowMax;

    /** 轴的像素长度，决定映射的取值区间。 */
    private double displayLength = 1.0;

    /**
     * 构造一根轴，显示窗口就是数据自己声明的范围。
     *
     * @param type  轴类型
     * @param range 数据声明的范围
     * @throws IllegalArgumentException 任一参数为 null 时
     */
    public Axis(AxisType type, AxisRange range) {
        if (type == null) {
            throw new IllegalArgumentException("type 不能为 null");
        }
        if (range == null) {
            throw new IllegalArgumentException("range 不能为 null");
        }
        this.type = type;
        this.range = range;
        AxisRange normalized = normalize(type, range);
        this.windowMin = normalized.min();
        this.windowMax = normalized.max();
    }

    /**
     * 把范围稳定化成一根轴能用的窗口：对数轴钳到正下限，其余扩成最小可视跨度。
     *
     * <p>规格 §10 的两条"不除零/不产生 NaN"就落在这里，而且只有这一处。
     */
    private static AxisRange normalize(AxisType type, AxisRange range) {
        return type == AxisType.LOGARITHMIC ? range.withPositiveMin() : range.withMinimumSpan();
    }

    /** 轴类型。 */
    public AxisType type() {
        return type;
    }

    /** 数据声明的范围（不是当前窗口）。 */
    public AxisRange range() {
        return range;
    }

    /** 当前显示窗口的下界。 */
    public double windowMin() {
        return windowMin;
    }

    /** 当前显示窗口的上界。 */
    public double windowMax() {
        return windowMax;
    }

    /** 轴的像素长度。 */
    public double displayLength() {
        return displayLength;
    }

    /**
     * 设置轴的像素长度。它决定 {@link #dataToDisplay(double)} 的取值区间。
     *
     * @param px 像素长度，必须为正的有限数
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException 非正或非有限时
     */
    public Axis setDisplayLength(double px) {
        if (!Double.isFinite(px) || px <= 0) {
            throw new IllegalArgumentException("轴的像素长度必须为正的有限数，实际为 " + px);
        }
        this.displayLength = px;
        return this;
    }

    /**
     * 设置显示窗口。
     *
     * <p>上下界写反时<strong>自动交换</strong>：调用方把 min/max 传反是常见笔误，
     * 交换比抛异常友好，而且不会像"照原样存下来"那样把整根轴的映射翻成镜像。
     *
     * @param min 窗口下界
     * @param max 窗口上界
     * @return 自身，便于链式调用
     */
    public Axis setWindow(double min, double max) {
        AxisRange normalized = normalize(type,
                new AxisRange(Math.min(min, max), Math.max(min, max), range.name(), range.unit()));
        this.windowMin = normalized.min();
        this.windowMax = normalized.max();
        return this;
    }

    /**
     * 把显示窗口恢复成数据自己声明的范围。
     *
     * @return 自身，便于链式调用
     */
    public Axis resetWindow() {
        return setWindow(range.min(), range.max());
    }

    /**
     * 数据值 → 显示位置（0 到 {@link #displayLength()}）。
     *
     * @param value 数据值；对数轴上 ≤0 的值钳到窗口下限
     * @return 显示位置
     */
    public double dataToDisplay(double value) {
        return fractionOf(value) * displayLength;
    }

    /**
     * 显示位置 → 数据值。与 {@link #dataToDisplay(double)} 互逆。
     *
     * @param px 显示位置；可以越出 {@code [0, displayLength]}（照样外推）
     * @return 数据值
     */
    public double displayToData(double px) {
        double fraction = px / displayLength;
        if (type == AxisType.LOGARITHMIC) {
            double logMin = Math.log10(windowMin);
            double logMax = Math.log10(windowMax);
            return Math.pow(10, logMin + fraction * (logMax - logMin));
        }
        return windowMin + fraction * (windowMax - windowMin);
    }

    /**
     * 生成这根轴当前窗口下的三级刻度，位置已按本轴的映射装配好。
     *
     * @return 按值升序的刻度
     */
    public Tick[] ticks() {
        return TickGenerator.generate(type, windowRange(), displayLength, this::dataToDisplay);
    }

    /** 把窗口包成一个 AxisRange，借它做稳定化并转交给刻度生成器。 */
    private AxisRange windowRange() {
        return new AxisRange(windowMin, windowMax, range.name(), range.unit());
    }

    /** 值 → {@code [0,1]} 的比例。窗口的稳定化保证了分母不为零。 */
    private double fractionOf(double value) {
        if (type == AxisType.LOGARITHMIC) {
            double logMin = Math.log10(windowMin);
            double logMax = Math.log10(windowMax);
            double logValue = value > 0 ? Math.log10(value) : logMin;
            return (logValue - logMin) / (logMax - logMin);
        }
        return (value - windowMin) / (windowMax - windowMin);
    }
}
