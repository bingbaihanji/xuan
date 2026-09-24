package com.bingbaihanji.jfgl.chart;

/**
 * 四边之一。标题与图例各自选一边放。
 *
 * <p>与 JavaFX 的 {@code Side}（以及 {@code setTitleSide} / {@code setLegendSide}）
 * 一一对应。刻度文字不在其中——本库的刻度文字由<b>调用方</b>画
 * （见 {@code Axis.ticks()} 与 README 的图表一节），图表层不认识它。
 *
 * <h2>标题只支持上下</h2>
 * <p>左右放的标题要把文字转 90°，而转换这件事需要一条"带变换的文字路径"；
 * 图例的上下左右都是普通的横排文字，所以四种都支持。
 * {@code Chart.titleSide(LEFT/RIGHT)} 会<b>明确抛异常</b>而不是画成横的——
 * 横着放的标题在画面上看起来只是"标题位置怪"，不像缺了个功能。
 */
public enum ChartSide {

    /** 上。 */
    TOP,

    /** 下。 */
    BOTTOM,

    /** 左。 */
    LEFT,

    /** 右。 */
    RIGHT
}
