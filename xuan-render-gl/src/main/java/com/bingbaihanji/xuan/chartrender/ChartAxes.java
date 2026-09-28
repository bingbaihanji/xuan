package com.bingbaihanji.xuan.chartrender;

import com.bingbaihanji.xuan.chart.Axis;
import com.bingbaihanji.xuan.chart.AxisStyle;
import com.bingbaihanji.xuan.chart.Chart;
import com.bingbaihanji.xuan.chart.ChartLayout;
import com.bingbaihanji.xuan.chart.Tick;
import com.bingbaihanji.xuan.util.Rect;

/**
 * 画网格、坐标轴、箭头、刻度线与刻度文字：布局已经算好了绘图区在哪，这里只照着画。
 *
 * <h2>它为什么与布局分开</h2>
 * <p>与 {@link ChartDecorations} 同一条理由：{@link ChartLayout}（① 纯计算）决定每一块在哪，
 * 本类只剩"照着矩形画线和字"。分成两半的收益是<b>布局可以脱离 GL 单测</b>。
 *
 * <h2>★ 它只开一层 {@code begin(frame)}，且画在数据系列之前</h2>
 *
 * <p>装饰那支笔是"一个带子开一层"（标题一层、图例一层），因为它们各自有互不重叠的矩形。
 * 这里不行：轴与刻度<b>跨越绘图区边界</b>、文字在绘图区之外，没有"一个带子装得下"的矩形。
 * 裁到整块外框已经足够拦住"画到外框之外"。
 *
 * <p><b>刻度文字也能一起画在数据之前</b>，理由是数据系列被 {@code glScissor}
 * <b>裁在绘图区之内</b>，而刻度文字在绘图区<b>之外</b>——数据不可能盖住它。
 * 所以不需要像应用层那样分两趟画、也不需要中间插一次 {@code Gc.flush()}。
 * （这条核对过渲染器的裁剪，不是想当然。）
 *
 * <h2>坐标映射与数据系列一致，且不依赖调用方守约</h2>
 *
 * <p>本类用 {@code tick.position() / displayLength × 绘图区边长}，而<b>不是</b>
 * 直接把 {@code position} 当像素加（应用层的常见写法）。
 * {@code Tick.position()} 的单位是"显示单位"，只有当调用方把
 * {@code setDisplayLength} 设成绘图区尺寸时它才恰好等于像素——那是
 * {@code ChartRenderLayout} 的类文档写明的一条<b>调用方义务</b>，而本类换一种算法
 * 就能不依赖它：{@code ChartRenderLayout} 自己用的也是"比例 × 绘图区边长"。
 * 两条路算出来的是同一个映射，所以<b>轴与数据不会因为调用方漏设而错位</b>。
 */
final class ChartAxes {

    private ChartAxes() {
    }

    /**
     * 画整套坐标系。<b>调用方负责在数据系列之前调用它。</b>
     *
     * <p>{@link AxisStyle#visible()} 为 false 时什么都不做（默认如此）——
     * 这是既有画面逐像素不变的前提。
     */
    static void paint(ChartPainter painter, Chart chart, ChartLayout layout) {
        AxisStyle style = chart.axisStyle();
        if (!style.visible()) {
            return;
        }
        Rect plot = layout.plotRect();
        // 绘图区被挤成 0（外框太小 / 外边距吃掉了）时没有任何东西可画。
        if (plot == null || !(plot.width > 0f) || !(plot.height > 0f)) {
            return;
        }
        // 网格与轴线按 x/y 两根轴画。只有一根轴时没有"坐标系"可谈——
        // 那不是错误配置（Chart 允许只有一根轴），只是本层不画。
        if (chart.axes().size() < 2) {
            return;
        }
        Axis xAxis = chart.axis(0);
        Axis yAxis = chart.axis(1);
        double xLen = xAxis.displayLength();
        double yLen = yAxis.displayLength();
        if (!(xLen > 0.0) || !(yLen > 0.0)) {
            // 没设过显示长度（或设成 0）时比例无从谈起。不抛异常：
            // 那是"还没量过尺寸"的正常中间状态，与绘图区为 0 同类处置。
            return;
        }
        Tick[] xTicks = xAxis.ticks();
        Tick[] yTicks = yAxis.ticks();

        painter.begin(layout.frame());
        try {
            if (style.gridVisible()) {
                paintGrid(painter, style, plot, xTicks, yTicks, xLen, yLen);
            }
            if (style.axisVisible()) {
                paintAxisLines(painter, style, plot);
            }
            if (style.arrowsVisible()) {
                paintArrows(painter, style, plot);
            }
            if (style.tickMarksVisible()) {
                paintTickMarks(painter, style, plot, xTicks, yTicks, xLen, yLen);
            }
            if (style.tickLabelsVisible()) {
                paintTickLabels(painter, style, plot, xTicks, yTicks, xLen, yLen);
            }
        } finally {
            // finally 不能省：画到一半抛异常时状态栈会少弹一层，
            // 之后画的每一个图元都带着这一层压栈时的状态。
            painter.end();
        }
    }

    // ------------------------------------------------------------------
    // 映射
    // ------------------------------------------------------------------

    /** x 轴上某个显示位置（{@code Tick.position()} 的单位）对应的屏幕 x。 */
    private static float screenX(Rect plot, double position, double displayLength) {
        return (float) (plot.x + position / displayLength * plot.width);
    }

    /** y 轴上某个显示位置对应的屏幕 y。<b>值越大越靠上</b>，所以要翻。 */
    private static float screenY(Rect plot, double position, double displayLength) {
        return (float) (plot.y + plot.height - position / displayLength * plot.height);
    }

    /**
     * 这个刻度该不该画（网格线与刻度线共用这一条）。
     *
     * <p>判据是<b>严格落在绘图区内部</b>：两个端点上的刻度与坐标轴本身重合，
     * 再画一遍只是把轴线加粗一档。
     *
     * <p>⚠️ 这里**不是**"跳过 {@code value == 0}"（应用层常见的那种写法）——
     * 那样会把"y 窗口跨过 0 时中间那条零线"也一起吃掉，而那条线是有意义的。
     */
    private static boolean isInside(Tick tick, double displayLength) {
        return tick.isMajor() && tick.position() > 0.0 && tick.position() < displayLength;
    }

    // ------------------------------------------------------------------
    // 五个部件
    // ------------------------------------------------------------------

    private static void paintGrid(ChartPainter painter, AxisStyle style, Rect plot,
                                  Tick[] xTicks, Tick[] yTicks,
                                  double xLen, double yLen) {
        for (Tick tick : yTicks) {
            if (!isInside(tick, yLen)) {
                continue;
            }
            float sy = screenY(plot, tick.position(), yLen);
            painter.strokeLine(plot.x, sy, plot.x + plot.width, sy,
                    style.gridWidth(), style.gridColor());
        }
        for (Tick tick : xTicks) {
            if (!isInside(tick, xLen)) {
                continue;
            }
            float sx = screenX(plot, tick.position(), xLen);
            painter.strokeLine(sx, plot.y, sx, plot.y + plot.height,
                    style.gridWidth(), style.gridColor());
        }
    }

    private static void paintAxisLines(ChartPainter painter, AxisStyle style, Rect plot) {
        float bottom = plot.y + plot.height;
        float right = plot.x + plot.width;
        // x 轴：绘图区下边缘，从左向右
        painter.strokeLine(plot.x, bottom, right, bottom, style.axisWidth(), style.axisColor());
        // y 轴：绘图区左边缘，从下向上
        painter.strokeLine(plot.x, bottom, plot.x, plot.y, style.axisWidth(), style.axisColor());
    }

    /**
     * 两条轴末端的箭头（x 轴右端、y 轴上端）。
     *
     * <p>箭头由两条短线组成，<b>画在绘图区内部</b>（从轴端往回折），所以它不会越出外框。
     * 尺寸取 {@code arrowSize}：沿轴方向那么长、横向各 {@code arrowSize / 2}。
     */
    private static void paintArrows(ChartPainter painter, AxisStyle style, Rect plot) {
        float size = style.arrowSize();
        float half = size * 0.5f;
        float bottom = plot.y + plot.height;
        float right = plot.x + plot.width;
        int color = style.axisColor();
        float w = style.axisWidth();

        // x 轴右端：指向右
        painter.strokeLine(right, bottom, right - size, bottom - half, w, color);
        painter.strokeLine(right, bottom, right - size, bottom + half, w, color);
        // y 轴上端：指向上
        painter.strokeLine(plot.x, plot.y, plot.x - half, plot.y + size, w, color);
        painter.strokeLine(plot.x, plot.y, plot.x + half, plot.y + size, w, color);
    }

    private static void paintTickMarks(ChartPainter painter, AxisStyle style, Rect plot,
                                       Tick[] xTicks, Tick[] yTicks,
                                       double xLen, double yLen) {
        float len = style.tickLength();
        float bottom = plot.y + plot.height;
        int color = style.axisColor();
        float w = style.axisWidth();

        for (Tick tick : xTicks) {
            if (!tick.isMajor()) {
                continue;
            }
            float sx = screenX(plot, tick.position(), xLen);
            painter.strokeLine(sx, bottom, sx, bottom + len, w, color);
        }
        for (Tick tick : yTicks) {
            if (!tick.isMajor()) {
                continue;
            }
            float sy = screenY(plot, tick.position(), yLen);
            painter.strokeLine(plot.x - len, sy, plot.x, sy, w, color);
        }
    }

    /**
     * 刻度文字。x 轴的在下方**居中**，y 轴的在左侧**右对齐**。
     *
     * <p>垂直居中用 {@link ChartLayout#CENTER_BASELINE_FACTOR}——与轴标题居中同一个常量，
     * 不另立一个"差不多"的数（两处各持一半口径的话，看起来只是"文字有点偏"）。
     *
     * <p>空标签跳过（{@code Tick.label()} 允许为空，那是"这个刻度不配文字"的意思）。
     */
    private static void paintTickLabels(ChartPainter painter, AxisStyle style, Rect plot,
                                        Tick[] xTicks, Tick[] yTicks,
                                        double xLen, double yLen) {
        float fontSize = style.tickLabelFontSize();
        int color = style.tickLabelColor();
        float bottom = plot.y + plot.height;

        for (Tick tick : xTicks) {
            if (!tick.isMajor() || tick.label().isEmpty()) {
                continue;
            }
            float sx = screenX(plot, tick.position(), xLen);
            float textWidth = painter.width(tick.label(), fontSize);
            // 基线在预留带内：刻度线之下一个字号的行高处
            painter.drawText(tick.label(), sx - textWidth * 0.5f,
                    bottom + style.tickLength() + fontSize, fontSize, color);
        }
        for (Tick tick : yTicks) {
            if (!tick.isMajor() || tick.label().isEmpty()) {
                continue;
            }
            float sy = screenY(plot, tick.position(), yLen);
            float textWidth = painter.width(tick.label(), fontSize);
            painter.drawText(tick.label(), plot.x - style.tickLength() - textWidth,
                    sy + fontSize * ChartLayout.CENTER_BASELINE_FACTOR, fontSize, color);
        }
    }
}
