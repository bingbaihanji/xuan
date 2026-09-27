package com.bingbaihanji.jfgl.chart;

/** 图表交互提示框的数值格式化策略。 */
@FunctionalInterface
public interface ChartValueFormatter {

    /**
     * 格式化一个轴值。
     *
     * @param value 数值
     * @param range 轴的名称与单位
     * @return 不含轴名称和单位的文本
     */
    String format(double value, AxisRange range);

    /** 默认格式：最多六位小数，去掉无意义的尾零。 */
    ChartValueFormatter DEFAULT = (value, ignored) -> {
        if (!Double.isFinite(value)) {
            return "NaN";
        }
        String text = String.format(java.util.Locale.ROOT, "%.6f", value);
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && text.charAt(end - 1) == '.') {
            end--;
        }
        return text.substring(0, end);
    };
}
