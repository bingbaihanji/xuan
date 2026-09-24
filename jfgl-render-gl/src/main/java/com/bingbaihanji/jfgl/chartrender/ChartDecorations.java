package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.Chart;
import com.bingbaihanji.jfgl.chart.ChartLayout;

/**
 * 画标题与图例：布局已经算好了每一块在哪，这里只是照着画。
 *
 * <h2>它为什么与布局分开</h2>
 * <p>{@link ChartLayout}（① 纯计算）把外框切成几块，本类把切出来的块画上东西。
 * 分成两半的收益是：<b>布局可以脱离 GL 单测</b>（逐像素的期望值全部由它决定），
 * 而本类只剩"照着矩形画字和色块"这一点点逻辑。
 * 两半的接缝只有 {@link ChartLayout} 那几个 getter——没有第二份几何算术。
 *
 * <h2>文字颜色</h2>
 * <p>标题与图例文字都用同一个灰色（{@link #TEXT_ARGB}）。<b>刻意不跟系列的颜色走</b>：
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
     * 照着布局画标题与图例。调用方必须已经 {@link ChartPainter#begin()}。
     *
     * <p>顺序是"标题 → 图例"，两者互不重叠（布局给了它们各自的带子），所以顺序无所谓；
     * 写死这个顺序只是为了让"同一份配置画出来的像素"是确定的。
     */
    static void paint(ChartPainter painter, Chart chart, ChartLayout layout) {
        if (layout.titleRect() != null) {
            painter.drawText(chart.title(), layout.titleRect().x, layout.titleBaseline(),
                    chart.titleFontSize(), TEXT_ARGB);
        }
        for (ChartLayout.LegendItem item : layout.legendItems()) {
            if (item.swatch().width > 0f && item.swatch().height > 0f) {
                painter.fillRect(item.swatch().x, item.swatch().y,
                        item.swatch().width, item.swatch().height, item.argb());
            }
            painter.drawText(item.label(), item.labelX(), item.labelBaseline(),
                    chart.legendFontSize(), TEXT_ARGB);
        }
    }
}
