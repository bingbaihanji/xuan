package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.Chart;
import com.bingbaihanji.jfgl.chart.ChartLayout;

/**
 * 画标题、图例与轴标题：布局已经算好了每一块在哪，这里只是照着画。
 *
 * <h2>它为什么与布局分开</h2>
 * <p>{@link ChartLayout}（① 纯计算）把外框切成几块，本类把切出来的块画上东西。
 * 分成两半的收益是：<b>布局可以脱离 GL 单测</b>（逐像素的期望值全部由它决定），
 * 而本类只剩"照着矩形画字和色块"这一点点逻辑。
 * 两半的接缝只有 {@link ChartLayout} 那几个 getter——没有第二份几何算术。
 * 连"轴标题要居中"都是布局算的（{@link ChartLayout.AxisTitle#x()}），
 * 这里不做任何坐标算术。
 *
 * <h2>文字颜色</h2>
 * <p>标题、图例文字与轴标题都用同一个灰色（{@link #TEXT_ARGB}）。<b>刻意不跟系列的颜色走</b>：
 * 那样"这句话是标题还是图例项"就看不出来了，而一份图例里每个标签都染成对应系列的颜色
 * 在深色背景上有一半读不清（本库的底色默认就很深）。要换颜色，
 * 请把整个装饰层当成普通图元自己画（{@code Gc.drawText}）。
 */
final class ChartDecorations {

    /**
     * 标题与图例文字的颜色（浅灰）。在 {@code 0x333333} 与 {@code 0x202040} 这类深色底上
     * 都有足够对比度——本库的默认底色就是这两种。
     */
    static final int TEXT_ARGB = 0xFFC8C8C8;

    private ChartDecorations() {
    }

    /**
     * 照着布局画标题与图例。<b>自己成对地 {@code begin/end}——每个带子一对。</b>
     *
     * <p>顺序是"标题 → 图例"，两者互不重叠（布局给了它们各自的带子），所以顺序无所谓；
     * 写死这个顺序只是为了让"同一份配置画出来的像素"是确定的。
     *
     * <h2>为什么每个带子各开一层</h2>
     * <p>因为 {@link ChartPainter#begin(com.bingbaihanji.jfgl.util.Rect)} 不但压栈，
     * 还<b>把绘制限制在带子之内</b>。两层不能合成一层：合成的那个矩形只能是"外框"，
     * 而外框挡不住"一项文字比带子还宽"画到隔壁去——那正是要拦的东西。
     *
     * <p>裁到带子这一条的取舍写在 {@code ChartLayout} 的类文档里：不折行、不省略
     * （那是排版决策，不该由布局替调用方做），但也不许画到界外；在带子边缘被切断
     * 是<b>看得见</b>的，而"画到别的地方"看不出来。
     */
    static void paint(ChartPainter painter, Chart chart, ChartLayout layout) {
        if (layout.titleRect() != null) {
            painter.begin(layout.titleRect());
            try {
                painter.drawText(chart.title(), layout.titleRect().x, layout.titleBaseline(),
                        chart.titleFontSize(), TEXT_ARGB);
            } finally {
                // finally 不能省：装饰画到一半抛异常时，状态栈会少弹一层，
                // 之后画的每一个图元都带着"标题那次压栈"的状态。
                painter.end();
            }
        }
        if (layout.legendRect() != null) {
            painter.begin(layout.legendRect());
            try {
                for (ChartLayout.LegendItem item : layout.legendItems()) {
                    if (item.swatch().width > 0f && item.swatch().height > 0f) {
                        painter.fillRect(item.swatch().x, item.swatch().y,
                                item.swatch().width, item.swatch().height, item.argb());
                    }
                    painter.drawText(item.label(), item.labelX(), item.labelBaseline(),
                            chart.legendFontSize(), TEXT_ARGB);
                }
            } finally {
                painter.end();
            }
        }
        // 轴标题：文字、带子、落点全部由 ChartLayout 算好（连"居中"都是它算的），
        // 这里只负责画。**它是数据带的名字与单位**（AxisRange 的 name / unit），
        // 不是刻度文字——刻度文字由调用方画（见 ChartLayout 的类文档）。
        paintAxisTitle(painter, layout.xAxisTitle(), chart.axisTitleFontSize());
        paintAxisTitle(painter, layout.yAxisTitle(), chart.axisTitleFontSize());
    }

    /** 画一条轴标题（没有时传 null）。 */
    private static void paintAxisTitle(ChartPainter painter, ChartLayout.AxisTitle title,
                                       float fontSize) {
        if (title == null) {
            return;
        }
        painter.begin(title.rect());
        try {
            painter.drawText(title.text(), title.x(), title.baseline(), fontSize, TEXT_ARGB);
        } finally {
            painter.end();
        }
    }
}
