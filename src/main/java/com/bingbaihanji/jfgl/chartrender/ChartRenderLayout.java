package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.Axis;
import com.bingbaihanji.jfgl.chart.AxisType;
import com.bingbaihanji.jfgl.util.Rect;

/**
 * 数值/序号 ↔ 屏幕像素的映射。<strong>纯算术，零 GL 依赖。</strong>
 *
 * <h2>它是两份实现里的 CPU 那一份</h2>
 * <p>同一个映射在顶点着色器里还有一份（用 {@code uValueMin} / {@code uValueMax} /
 * {@code uPlotY} / {@code uPlotH} 四个 uniform 算）。两份必须逐点一致，否则
 * <b>刻度线与数据点会错开</b>——那看起来"只是没对齐"，排查方向会跑偏。
 * 一致性由 {@code ChartRenderLayoutTest.着色器uniform与CPU映射等价} 钉住。
 *
 * <h2>y 的方向是反的</h2>
 * <p>{@link Axis#dataToDisplay(double)} 给出的是"0 在窗口最小值处、越大越往后"的位置；
 * 而绘图区是数学惯例——<b>值越大越靠上</b>。所以 y 要翻：
 * {@code screenY(v) == plotY + plotH - axis.dataToDisplay(v)}。
 * 这要求调用方把 y 轴的 {@code displayLength} 设成绘图区高度（x 轴设成宽度），
 * 该契约由上面那条一致性断言强制。
 *
 * <h2>本期只支持线性换算</h2>
 * <p>{@link AxisType#LINEAR} 与 {@link AxisType#TIME} 都是线性的（时间轴的值是纪元秒），
 * 两者都支持。{@link AxisType#LOGARITHMIC} 与 {@link AxisType#TEXT} 会
 * <b>明确抛异常</b>：按线性去画对数轴，曲线的形状是错的，而画面看起来完全正常——
 * 这正是本项目最警惕的静默错误输出。
 */
public final class ChartRenderLayout {

    private final Rect plotRect;

    private final float xMin;

    private final float xMax;

    private final float yMin;

    private final float yMax;

    /**
     * @param plotRect 绘图区（数据区域）矩形，设备像素
     * @param xAxis    x 轴，其窗口是可见的数据下标范围
     * @param yAxis    y 轴，其窗口是可见的数值范围
     * @throws UnsupportedOperationException 轴类型不是线性换算（见类文档）
     */
    public ChartRenderLayout(Rect plotRect, Axis xAxis, Axis yAxis) {
        this.plotRect = plotRect;
        requireLinear(xAxis, "x");
        requireLinear(yAxis, "y");
        this.xMin = (float) xAxis.windowMin();
        this.xMax = (float) xAxis.windowMax();
        this.yMin = (float) yAxis.windowMin();
        this.yMax = (float) yAxis.windowMax();
    }

    private static void requireLinear(Axis axis, String which) {
        AxisType type = axis.type();
        if (type != AxisType.LINEAR && type != AxisType.TIME) {
            throw new UnsupportedOperationException(
                    which + " 轴的类型是 " + type + "，本期 GPU 绘制路径只支持线性换算"
                            + "（LINEAR / TIME）。按线性去画非线性轴，曲线形状是错的而画面正常，"
                            + "因此这里明确报错而不是静默画错。");
        }
    }

    private static double fraction(double v, float min, float max) {
        double span = (double) max - min;
        // 退化范围在 ① 的 Axis 构造里已被 withMinimumSpan() 稳定化，不会走到这里；
        // 这里再兜一次，免得除零产生 NaN 后一路传到顶点位置上。
        if (span == 0.0) {
            return 0.0;
        }
        return (v - min) / span;
    }

    /** 绘图区矩形。 */
    public Rect plotRect() {
        return plotRect;
    }

    /** 着色器 uniform {@code uValueMin} 的 x 分量来源。 */
    public float xMin() {
        return xMin;
    }

    /** 着色器 uniform {@code uValueMax} 的 x 分量来源。 */
    public float xMax() {
        return xMax;
    }

    /** 着色器 uniform {@code uValueMin} 的 y 分量来源。 */
    public float yMin() {
        return yMin;
    }

    /** 着色器 uniform {@code uValueMax} 的 y 分量来源。 */
    public float yMax() {
        return yMax;
    }

    /**
     * 数据下标 → 屏幕 x。
     *
     * @param index 数据下标（可以是小数，支持亚像素滚动）
     * @return 屏幕 x（设备像素），窗口外照样线性外推
     */
    public float screenX(double index) {
        return (float) (plotRect.x + fraction(index, xMin, xMax) * plotRect.width);
    }

    /**
     * 数值 → 屏幕 y。<strong>已按"值越大越靠上"翻转。</strong>
     *
     * @param value 数据值
     * @return 屏幕 y（设备像素），窗口外照样线性外推
     */
    public float screenY(double value) {
        return (float) (plotRect.y + (1.0 - fraction(value, yMin, yMax)) * plotRect.height);
    }
}
