package com.bingbaihanji.xuan.chartrender;

import com.bingbaihanji.xuan.chart.ArrayChartData;
import com.bingbaihanji.xuan.chart.AxisRange;

/** 测试用的数据装配。 */
final class ChartDataFixtures {

    private ChartDataFixtures() {
    }

    /**
     * 造一个二维静态数据，第一维是下标 0..n-1，第二维取给定值。
     *
     * <p><strong>必须是二维</strong>：{@link SeriesBuffer} 取的是 <b>1 号维度</b>（y），
     * 只有一维的数据在取数时会抛 {@code IndexOutOfBoundsException}。
     */
    static ArrayChartData arrayOf(double... values) {
        double[] xs = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            xs[i] = i;
        }
        return new ArrayChartData(
                new AxisRange[]{AxisRange.of(0, Math.max(1, values.length)),
                        AxisRange.of(-1, 1)},
                new double[][]{xs, values});
    }
}
