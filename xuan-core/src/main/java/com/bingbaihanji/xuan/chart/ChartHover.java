package com.bingbaihanji.xuan.chart;

import java.util.List;

/** 一次 hover 命中的数据点及其提示框内容。 */
public record ChartHover(
        Series series,
        int index,
        double xValue,
        double yValue,
        float screenX,
        float screenY,
        List<Line> lines
) {
    public ChartHover {
        if (series == null || index < 0 || lines == null) {
            throw new IllegalArgumentException("hover 参数非法");
        }
        lines = List.copyOf(lines);
    }

    /** 提示框中的一行标签和值。 */
    public record Line(String label, String value) {
    }
}
