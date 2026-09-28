package com.bingbaihanji.xuan.chart;

/**
 * 一个维度的范围 + 名称 + 单位。
 *
 * <h2>范围在数据侧，不在轴侧</h2>
 * <p>这条抄 chart-fx：数据自己声明自己的范围与名称单位，<strong>轴只是显示窗口</strong>，
 * 不去遍历数据。好处有两个：轴与数据解耦；同一个范围能被多个轴共享，
 * 于是<strong>多 Y 轴是自然结果而不是特例</strong>。
 *
 * <p><strong>名字撞车，引用 chart-fx 时别接错</strong>：本类对应 chart-fx 的
 * {@code AxisDescription}（按维度给出的 name/unit/min/max，
 * 由 {@code DataSet.getAxisDescriptions()} 返回），
 * <strong>不是</strong>它的 {@code AxisRange}——chart-fx 的 {@code AxisRange} 是
 * <strong>轴的窗口</strong>（min/max + axisLength + scale + tickUnit），
 * 与它对应的是本项目 {@link Axis} 里那个显示窗口
 * （{@code windowMin}/{@code windowMax} + {@code displayLength}）。
 *
 * <h2>这一个类里有两处"稳定化"，它们是全项目唯一的一份</h2>
 * <ul>
 *   <li>{@link #withMinimumSpan()}：跨度为 0 或负时扩成一个最小可视跨度。
 *       规格 §10 要求"轴范围退化（min == max）→ 自动扩成最小可视跨度，不除零"，
 *       实现就在这里。换算里除以零会得到 NaN 或 Infinity，而 NaN 进入顶点缓冲后
 *       整条曲线（乃至同批次别的图元）都会消失——不报错，只是什么都没了。</li>
 *   <li>{@link #withPositiveMin()}：对数轴上出现 ≤ 0 的值时钳到正下限。
 *       规格 §10 要求"明确处理，不产生 NaN"。</li>
 * </ul>
 * <p><strong>下游（{@link TickGenerator}、{@link Axis}）必须调用这两个方法，
 * 不许另抄一份等价逻辑。</strong>抄一份的后果是这两处逻辑有一份没有测试。
 *
 * @param min  下界（含）
 * @param max  上界（含）；允许等于 {@code min}（退化范围由 {@link #withMinimumSpan()} 处理）
 * @param name 维度名，如 {@code "电压"}，不能为 null；为空串表示不显示名字
 * @param unit 单位，如 {@code "V"}，不能为 null（没有单位就传空串）
 */
public record AxisRange(double min, double max, String name, String unit) {

    /** 线性轴跨度为 0 时扩成的最小可视跨度。 */
    public static final double MIN_SPAN = 1.0;

    public AxisRange {
        if (Double.isNaN(min) || Double.isNaN(max)) {
            throw new IllegalArgumentException("范围不能是 NaN：" + min + ".." + max);
        }
        if (Double.isInfinite(min) || Double.isInfinite(max)) {
            throw new IllegalArgumentException("范围必须是有限数：" + min + ".." + max);
        }
        if (min > max) {
            throw new IllegalArgumentException("min 不能大于 max：" + min + ".." + max);
        }
        if (name == null) {
            throw new IllegalArgumentException("name 不能为 null（不显示名字就传空串）");
        }
        if (unit == null) {
            throw new IllegalArgumentException("unit 不能为 null（没有单位就传空串）");
        }
    }

    /** 只要范围、不带名字单位的构造。 */
    public static AxisRange of(double min, double max) {
        return new AxisRange(min, max, "", "");
    }

    /** 跨度。退化范围返回 0（把它当分母前先过 {@link #withMinimumSpan()}）。 */
    public double span() {
        return max - min;
    }

    /** 是否退化（跨度为 0）。 */
    public boolean isDegenerate() {
        return !(max > min);
    }

    /**
     * 保证跨度不小于 {@link #MIN_SPAN}：以原范围的中心为心向两侧扩。
     *
     * @return 跨度合法的范围；本来合法时返回自身
     */
    public AxisRange withMinimumSpan() {
        if (max - min >= MIN_SPAN) {
            return this;
        }
        double center = (min + max) / 2.0;
        return new AxisRange(center - MIN_SPAN / 2.0, center + MIN_SPAN / 2.0, name, unit);
    }

    /**
     * 对数轴的合法范围：{@code min > 0} 且 {@code max > min}。
     *
     * <p>原来就合法时返回自身；{@code min ≤ 0} 但 {@code max > 0} 时把 min 钳到
     * {@code max / 1000}（三个数量级的下限，够看得见）；整段都 ≤ 0 时给一个
     * {@code [1, 10]} 的占位范围。
     *
     * <p><strong>宁可显示一条空轴，也不产生 NaN</strong>：NaN 会顺着顶点缓冲毁掉
     * 同一批次里别的图元，而空轴只是这一条曲线看不见——前者不可观测且波及无辜，
     * 后者一眼就能看出是数据的问题。
     *
     * @return 对数轴可用的范围
     */
    public AxisRange withPositiveMin() {
        if (min > 0 && max > min) {
            return this;
        }
        if (max > 0) {
            return new AxisRange(max / 1000.0, max, name, unit);
        }
        return new AxisRange(1.0, 10.0, name, unit);
    }
}
