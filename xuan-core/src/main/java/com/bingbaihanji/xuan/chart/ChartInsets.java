package com.bingbaihanji.xuan.chart;

/**
 * 四边不等的外边距：上、右、下、左。
 *
 * <p>对应 JavaFX 的 {@code Region.setPadding(Insets)}：{@link ChartLayout} 先把它从
 * 外框里扣掉，再在剩下的地方摆标题、图例与绘图区。名字里带 {@code Chart} 是为了
 * 与 {@code javafx.geometry.Insets} 区分——应用层两个都 import 的话，
 * 一个裸 {@code Insets} 会让两边都编译不过（或者更糟：只编译过一边）。
 *
 * <p><b>默认是全 0</b>（{@link #NONE}），不是 JavaFX 那种 5px：本库的坐标是用户自己算的，
 * 默认值一旦非零，所有既有画面都会在"升级之后"整体偏移几个像素——
 * 而"整体偏了几个像素"看起来只是没对齐。
 *
 * @param top    上边距，不能为负
 * @param right  右边距，不能为负
 * @param bottom 下边距，不能为负
 * @param left   左边距，不能为负
 */
public record ChartInsets(float top, float right, float bottom, float left) {

    /** 全 0 的外边距。 */
    public static final ChartInsets NONE = new ChartInsets(0f, 0f, 0f, 0f);

    public ChartInsets {
        requireNonNegative(top, "上");
        requireNonNegative(right, "右");
        requireNonNegative(bottom, "下");
        requireNonNegative(left, "左");
    }

    private static void requireNonNegative(float value, String which) {
        if (!(value >= 0f)) {
            // 负边距会让"扣掉之后剩下的地方"比外框还大，于是绘图区越过外框画到别的图上——
            // 而画出去这件事在画面上没有任何提示（多出来的部分是另一张图的地盘）。
            throw new IllegalArgumentException(which + "边距不能为负（也不会是 NaN），实际为 " + value);
        }
    }

    /** 四边同值的外边距。 */
    public static ChartInsets uniform(float value) {
        return new ChartInsets(value, value, value, value);
    }

    /** 上下同值、左右同值的边距（对标 CSS 的两值写法）。 */
    public static ChartInsets symmetric(float vertical, float horizontal) {
        return new ChartInsets(vertical, horizontal, vertical, horizontal);
    }

    /** 水平方向占用的总宽度（左 + 右）。 */
    public float horizontal() {
        return left + right;
    }

    /** 垂直方向占用的总高度（上 + 下）。 */
    public float vertical() {
        return top + bottom;
    }
}
