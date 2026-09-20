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
}
