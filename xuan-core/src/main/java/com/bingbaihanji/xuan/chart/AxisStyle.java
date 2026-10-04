package com.bingbaihanji.xuan.chart;

/**
 * 坐标系（网格 / 轴线 / 箭头 / 刻度线 / 刻度文字）的外观配置。
 *
 * <p>不依赖 JavaFX 或 OpenGL，可以在应用线程创建并安全替换到 {@link Chart}。
 * 颜色采用项目统一的 {@code 0xAARRGGBB}。
 *
 * <h2>★ {@link #visible()} 默认 {@code false}——这是承重约束，不是偏好</h2>
 *
 * <p>打开它，绘图区就要让出带子（刻度文字要占地方）。而本仓库有一批**精确到 ±0 的
 * 像素期望**，它们全部建立在"不设标题 / 图例、外边距为 0 时绘图区与外框逐字段相等"
 * 这条不变式上（{@code ChartLayoutTest} 与 {@code ChartVerifier} 两头钉着）。
 * 默认关 ⇒ 既有画面**逐像素不变** ⇒ 这个特性是纯增量的。
 *
 * <p><strong>要改这个默认值，等于重立那批断言。</strong>那条不变式由
 * {@code ChartVerifier} 的一条断言与一次变异（把默认值改成 true、现有断言必须倒）
 * 反向钉着。
 *
 * <h2>尺寸量的校验口径</h2>
 *
 * <p>与 {@link ChartInteractionConfig} 同一条：**有限且为正**才收，否则抛
 * {@link IllegalArgumentException}。这里的四个尺寸量都不是"越界但有限也能明确处置"
 * 的那种——0 宽度的网格线是"什么都不画"，而那与"用户把网格关了"在画面上**逐像素相同**，
 * 属于本仓库最防的静默错误输出。
 */
public record AxisStyle(
        boolean visible,
        boolean gridVisible,
        int gridColor,
        float gridWidth,
        boolean axisVisible,
        int axisColor,
        float axisWidth,
        boolean arrowsVisible,
        float arrowSize,
        boolean tickMarksVisible,
        float tickLength,
        boolean tickLabelsVisible,
        float tickLabelFontSize,
        int tickLabelColor
) {

    public AxisStyle {
        requirePositiveFinite(gridWidth, "网格线宽");
        requirePositiveFinite(axisWidth, "轴线宽");
        requirePositiveFinite(arrowSize, "箭头大小");
        requirePositiveFinite(tickLength, "刻度线长");
        requirePositiveFinite(tickLabelFontSize, "刻度文字字号");
    }

    /**
     * 默认值：<strong>整体关着</strong>，而各项的值按"打开之后立刻能用"给。
     *
     * <p>这样 {@code axisStyle(AxisStyle.defaults().visible(true))} 就是
     * "按库的默认样式打开"，不必逐字段配一遍。
     */
    public static AxisStyle defaults() {
        return new AxisStyle(
                false,                          // visible：见类文档，默认关是承重的
                true, 0xFF394452, 1f,           // 网格
                true, 0xFFE3E8EF, 1.5f,         // 轴线
                true, 8f,                       // 箭头
                true, 5f,                       // 刻度线
                true, 12f, 0xFFC7D0DB);         // 刻度文字
    }

    // ------------------------------------------------------------------
    // 逐字段 wither：record 不可变，改一个字段要新建一个。
    // 与 ChartInteractionConfig 同一写法（含那个 14 参数的私有 copy）。
    // ------------------------------------------------------------------

    /**
     * 拒绝 NaN / ±Infinity，也拒绝 ≤ 0。
     *
     * <p>与 {@code Series.requireFinite} 的差别是这里**连"越界但有限"也不收**：
     * 那边收下负线宽，是因为渲染侧对它有一个明确处置（不画线）；
     * 而这里的尺寸量取 ≤ 0 只会让那个元素**静默消失**——看不见的网格线
     * 与"把网格关了"在画面上完全一样。
     */
    private static void requirePositiveFinite(float value, String what) {
        if (!(value > 0f) || !Float.isFinite(value)) {
            throw new IllegalArgumentException(
                    what + "必须是有限正数（0 会让那个元素静默消失，与关掉它无法区分），实际为 " + value);
        }
    }

    public AxisStyle visible(boolean v) {
        return new AxisStyle(v, gridVisible, gridColor, gridWidth, axisVisible, axisColor,
                axisWidth, arrowsVisible, arrowSize, tickMarksVisible, tickLength,
                tickLabelsVisible, tickLabelFontSize, tickLabelColor);
    }

    public AxisStyle gridVisible(boolean v) {
        return new AxisStyle(visible, v, gridColor, gridWidth, axisVisible, axisColor,
                axisWidth, arrowsVisible, arrowSize, tickMarksVisible, tickLength,
                tickLabelsVisible, tickLabelFontSize, tickLabelColor);
    }

    public AxisStyle gridColor(int v) {
        return new AxisStyle(visible, gridVisible, v, gridWidth, axisVisible, axisColor,
                axisWidth, arrowsVisible, arrowSize, tickMarksVisible, tickLength,
                tickLabelsVisible, tickLabelFontSize, tickLabelColor);
    }

    public AxisStyle gridWidth(float v) {
        return new AxisStyle(visible, gridVisible, gridColor, v, axisVisible, axisColor,
                axisWidth, arrowsVisible, arrowSize, tickMarksVisible, tickLength,
                tickLabelsVisible, tickLabelFontSize, tickLabelColor);
    }

    public AxisStyle axisVisible(boolean v) {
        return new AxisStyle(visible, gridVisible, gridColor, gridWidth, v, axisColor,
                axisWidth, arrowsVisible, arrowSize, tickMarksVisible, tickLength,
                tickLabelsVisible, tickLabelFontSize, tickLabelColor);
    }

    public AxisStyle axisColor(int v) {
        return new AxisStyle(visible, gridVisible, gridColor, gridWidth, axisVisible, v,
                axisWidth, arrowsVisible, arrowSize, tickMarksVisible, tickLength,
                tickLabelsVisible, tickLabelFontSize, tickLabelColor);
    }

    public AxisStyle axisWidth(float v) {
        return new AxisStyle(visible, gridVisible, gridColor, gridWidth, axisVisible, axisColor,
                v, arrowsVisible, arrowSize, tickMarksVisible, tickLength,
                tickLabelsVisible, tickLabelFontSize, tickLabelColor);
    }

    public AxisStyle arrowsVisible(boolean v) {
        return new AxisStyle(visible, gridVisible, gridColor, gridWidth, axisVisible, axisColor,
                axisWidth, v, arrowSize, tickMarksVisible, tickLength,
                tickLabelsVisible, tickLabelFontSize, tickLabelColor);
    }

    public AxisStyle arrowSize(float v) {
        return new AxisStyle(visible, gridVisible, gridColor, gridWidth, axisVisible, axisColor,
                axisWidth, arrowsVisible, v, tickMarksVisible, tickLength,
                tickLabelsVisible, tickLabelFontSize, tickLabelColor);
    }

    public AxisStyle tickMarksVisible(boolean v) {
        return new AxisStyle(visible, gridVisible, gridColor, gridWidth, axisVisible, axisColor,
                axisWidth, arrowsVisible, arrowSize, v, tickLength,
                tickLabelsVisible, tickLabelFontSize, tickLabelColor);
    }

    public AxisStyle tickLength(float v) {
        return new AxisStyle(visible, gridVisible, gridColor, gridWidth, axisVisible, axisColor,
                axisWidth, arrowsVisible, arrowSize, tickMarksVisible, v,
                tickLabelsVisible, tickLabelFontSize, tickLabelColor);
    }

    public AxisStyle tickLabelsVisible(boolean v) {
        return new AxisStyle(visible, gridVisible, gridColor, gridWidth, axisVisible, axisColor,
                axisWidth, arrowsVisible, arrowSize, tickMarksVisible, tickLength,
                v, tickLabelFontSize, tickLabelColor);
    }

    public AxisStyle tickLabelFontSize(float v) {
        return new AxisStyle(visible, gridVisible, gridColor, gridWidth, axisVisible, axisColor,
                axisWidth, arrowsVisible, arrowSize, tickMarksVisible, tickLength,
                tickLabelsVisible, v, tickLabelColor);
    }

    public AxisStyle tickLabelColor(int v) {
        return new AxisStyle(visible, gridVisible, gridColor, gridWidth, axisVisible, axisColor,
                axisWidth, arrowsVisible, arrowSize, tickMarksVisible, tickLength,
                tickLabelsVisible, tickLabelFontSize, v);
    }
}
