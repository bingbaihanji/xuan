package com.bingbaihanji.xuan.chart;

import com.bingbaihanji.xuan.util.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChartInteractionTest {

    @Test
    void 命中最近点并生成轴名称单位和系列名称() {
        ArrayChartData data = new ArrayChartData(
                new AxisRange[]{new AxisRange(0, 2, "时间", "s"),
                        new AxisRange(0, 20, "数量", "件")},
                new double[][]{{0, 1, 2}, {2, 10, 18}});
        Axis x = new Axis(AxisType.LINEAR, data.axisRange(0)).setDisplayLength(200);
        Axis y = new Axis(AxisType.LINEAR, data.axisRange(1)).setDisplayLength(100);
        Chart chart = new Chart(x, y);
        Series series = new Series("销售", data, ChartType.LINE);
        chart.addLayer("数据").add(series);

        chart.interaction().updatePointer(110, 70);
        ChartHover hover = chart.interaction().probe(chart, new Rect(10, 20, 200, 100));

        assertNotNull(hover);
        assertSame(series, hover.series());
        assertEquals(1, hover.index());
        assertEquals(2, hover.lines().size());
        assertEquals("时间 (s)", hover.lines().get(0).label());
        assertEquals("1", hover.lines().get(0).value());
        assertEquals("销售 / 数量 (件)", hover.lines().get(1).label());
        assertEquals("10", hover.lines().get(1).value());
    }

    @Test
    void 指针离开绘图区或遇到NaN时不命中() {
        ArrayChartData data = new ArrayChartData(
                new AxisRange[]{AxisRange.of(0, 2), AxisRange.of(0, 2)},
                new double[][]{{0, 1, 2}, {0, Double.NaN, 2}});
        Chart chart = new Chart(
                new Axis(AxisType.LINEAR, data.axisRange(0)).setDisplayLength(100),
                new Axis(AxisType.LINEAR, data.axisRange(1)).setDisplayLength(100));
        chart.addLayer("数据").add(new Series("s", data, ChartType.LINE));

        chart.interaction().updatePointer(50, 50);
        assertNull(chart.interaction().probe(chart, new Rect(0, 0, 100, 100)));
        chart.interaction().updatePointer(200, 200);
        assertNull(chart.interaction().probe(chart, new Rect(0, 0, 100, 100)));
    }

    /**
     * 造一张 12 点、x 值 = 0..11、y 值全为 10 的图。
     *
     * <p>y 全取同一个值是为了让每一点的屏幕 y 都相同（{@code sy = 200}）——
     * 于是"谁离指针更近"完全由 x 决定，断言里那几个距离可以手算。
     * y 窗口 [0, 20]、绘图区高 400 ⇒ {@code dataToDisplay(10) = 200}、{@code sy = 200}。
     */
    private static Chart twelvePointChart() {
        double[] xs = new double[12];
        double[] ys = new double[12];
        for (int i = 0; i < 12; i++) {
            xs[i] = i;
            ys[i] = 10;
        }
        ArrayChartData data = new ArrayChartData(
                new AxisRange[]{AxisRange.of(0, 11), AxisRange.of(0, 20)},
                new double[][]{xs, ys});
        Chart chart = new Chart(
                new Axis(AxisType.LINEAR, data.axisRange(0)).setDisplayLength(600),
                new Axis(AxisType.LINEAR, data.axisRange(1)).setDisplayLength(400));
        chart.addLayer("数据").add(new Series("s", data, ChartType.LINE));
        return chart;
    }

    /**
     * ★ 窗口外的样本**不可命中**——哪怕它比任何可见样本都更靠近指针。
     *
     * <p>它是这条缺陷的原始复现：x 窗口取 {@code [5.05, 15.05]}（起点不是采样间隔的
     * 整数倍，流式滚动图正是如此），于是 {@code x = 5} 这个样本被映射到
     * {@code sx = (5-5.05)/10 * 600 = -3}——在绘图区左侧之外 3 像素。
     * 指针放在绘图区内的 {@code (10, 200)}：到它的距离是 <strong>13</strong>，
     * 而到最近的可见样本（{@code x = 6}，{@code sx = 57}）是 <strong>47</strong>，
     * 两者都在默认吸附半径 18 的判定之外/之内正好相反 ⇒ 修复前命中 index 5。
     *
     * <p>命中它的后果是**三重静默**：纵向十字线画在 {@code x = -3} 被裁掉、
     * 6×6 命中点画在绘图区外被裁掉、提示框被夹回绘图区内照常显示一个看不见的样本的读数。
     *
     * <p>修复后：可见样本里没有一个落在半径内 ⇒ 返回 {@code null}。
     * 断言写成"要么为空、要么索引必须落在窗口内"是**不够的**——
     * 那会让"窗口外那个被命中了"通过（它的索引 5 恰好也 ≥ 5）。这里的判据是
     * **空闲帧什么都不该命中**。
     */
    @Test
    void 窗口外的样本不可命中_哪怕它比可见样本更靠近指针() {
        Chart chart = twelvePointChart();
        chart.axis(0).setWindow(5.05, 15.05);

        chart.interaction().updatePointer(10, 200);

        assertNull(chart.interaction().probe(chart, new Rect(0, 0, 600, 400)),
                "指针在绘图区内、附近只有一个窗口外的样本时，应当什么都不命中");
    }

    /**
     * 窗口**边界上**的样本仍然可见、仍然可命中（判据是闭区间）。
     *
     * <p>它是上一条的反面：把窗口起点正好落在 {@code x = 5} 上，于是
     * {@code sx = 0}——正好是绘图区左边缘，渲染器会把它画出来（{@code fraction = 0}）。
     * 如果窗口判据写成开区间、或者写成 {@code > plot.x} 之类，这条会倒。
     */
    @Test
    void 窗口边界上的样本仍可命中() {
        Chart chart = twelvePointChart();
        chart.axis(0).setWindow(5.0, 15.0);

        chart.interaction().updatePointer(2, 200);
        ChartHover hover = chart.interaction().probe(chart, new Rect(0, 0, 600, 400));

        assertNotNull(hover, "x = 5 落在窗口起点上，它可见，应当能命中");
        assertEquals(5, hover.index());
        assertEquals(0f, hover.screenX(), 1e-6, "边界样本的屏幕 x 就是绘图区左边缘");
    }

    /**
     * 提示框文本**只构造一次**，不是每个"更近的候选"都构造一次。
     *
     * <p>判据用 formatter 的**调用次数**：它是最贵的那部分（建 {@code ArrayList} +
     * 每个值一次格式化），而计数是唯一能把"每个候选都构造"与"只在最后构造"分开的量
     * ——两者的**返回值完全相同**，任何只看结果的断言都抓不住它。
     *
     * <p>把吸附半径放到 1000，让 20 个样本**每一个都比前一个更近**（指针放在最右端），
     * 于是它们全都是候选：修复前 formatter 被调 {@code 20 × 2 = 40} 次，
     * 修复后是 {@code 1 × 2 = 2} 次（两条提示框行各一次）。
     * 顺便钉住另一半：{@code tooltipVisible == false} 时它照样会被调用
     * （{@code probe} 不看那个开关，那是 {@code drawInteraction} 的事）——
     * 所以这条断言同时在守"文本构造被推迟到了循环之外"。
     */
    @Test
    void 提示框文本只构造一次而不是每个候选都构造() {
        double[] xs = new double[20];
        double[] ys = new double[20];
        for (int i = 0; i < 20; i++) {
            xs[i] = i;
            ys[i] = 10;
        }
        ArrayChartData data = new ArrayChartData(
                new AxisRange[]{AxisRange.of(0, 19), AxisRange.of(0, 20)},
                new double[][]{xs, ys});
        Chart chart = new Chart(
                new Axis(AxisType.LINEAR, data.axisRange(0)).setDisplayLength(600),
                new Axis(AxisType.LINEAR, data.axisRange(1)).setDisplayLength(400));
        chart.addLayer("数据").add(new Series("s", data, ChartType.LINE));

        int[] formatterCalls = {0};
        chart.interaction().setConfig(ChartInteractionConfig.defaults()
                .snapRadius(1000f)
                .formatter((value, range) -> {
                    formatterCalls[0]++;
                    return "v";
                }));

        // 指针放在最右端：样本越靠右越近，于是 20 个候选每一个都刷新 bestDistance。
        chart.interaction().updatePointer(600, 200);
        ChartHover hover = chart.interaction().probe(chart, new Rect(0, 0, 600, 400));

        assertNotNull(hover);
        assertEquals(19, hover.index(), "最右端命中的应当是最后一个样本");
        assertEquals(2, formatterCalls[0],
                "两条提示框行各构造一次；每个候选都构造的话这里是 40");
    }
}
