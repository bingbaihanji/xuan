package com.bingbaihanji.xuan.chart;

/**
 * 量一行文字要多宽、一行要多高。<strong>一个纯粹的度量接口，不含任何绘制。</strong>
 *
 * <h2>它为什么必须由外面传进来</h2>
 * <p>{@link ChartLayout} 要算"标题带子该留多高、图例里这一项该占多宽"，
 * 而字体在 {@code text/} 里（要 stb、要 GL 上下文）。① 是零 GL 依赖的纯计算层，
 * 一旦自己去量字，{@code ChartPackageIsolationTest} 会立刻失败——
 * 那条边界是机器守着的，不是一句约定。
 *
 * <p>所以布局只<b>问</b>宽度，不自己算：② 那边有一个实现了本接口的绘制入口
 * （{@code ChartPainter extends ChartTextMetrics}），布局拿到它就能在纯计算的
 * 前提下算出全部矩形；而"算出来的矩形"因此可以脱离 GL 单测（见
 * {@code ChartLayoutTest}）。
 *
 * <h2>行高为什么不问度量</h2>
 * <p>{@link #lineHeight(float)} 只有字号这一个参数，看起来是个多余的抽象。
 * <b>它是有意的</b>：行高一旦来自字体的真实 ascent/descent，布局就再也无法被
 * 精确预测（同一个 {@code frame} 在不同字体下会给出不同的绘图区），
 * 而那正是"绘图区位置写不出来"的根源——校验器里那些逐像素的期望值全部要靠它。
 * 实现应当返回 {@code 字号 × 一个固定系数}（见 {@link ChartLayout#LINE_HEIGHT_FACTOR}），
 * <b>不要</b>返回字体的真实行高。
 */
public interface ChartTextMetrics {

    /** 一行文字在当前字号下的推进宽度（像素）。不绘制任何东西。 */
    float width(String text, float fontSize);

    /**
     * 这类字号下"一行文字该占的高度"（像素）。
     *
     * <p>它必须是一个与字体无关的<b>上限式</b>取值（见类文档）。
     */
    float lineHeight(float fontSize);
}
