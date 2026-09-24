package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.ChartTextMetrics;

/**
 * 标题与图例的"一支笔"：量文字、画文字、填矩形。<strong>它同时是 {@link ChartTextMetrics}</strong>。
 *
 * <h2>为什么是一个接口，而不是直接用 {@code Gc}</h2>
 * <p>本包（② 渲染后端）在 {@code jfgl-render-gl} 里，而 {@code Gc} 也在同一个模块
 * ——所以技术上 {@code chartrender} 可以直接 new 一个 {@code Gc}。但没有那样做：
 * <ul>
 *   <li>{@code Gc} 是<b>给应用用的门面</b>（几十个方法、状态栈、拾取、点击队列）。
 *       它的方法签名会把"标题要么画在这里"变成"标题可以用任何一支笔画"，
 *       而后者没有约束、也就无法验证；</li>
 *   <li>本接口只有四个方法，而每一个都被 {@code ChartVerifier} 的像素断言钉着。
 *       要加第五个（比如旋转），得先回答"谁来验它"。</li>
 * </ul>
 * <p>{@code Gc} 那边有一个匿名实现（见 {@code Gc.chartPainter}），
 * 它把本接口转发到自己的 {@code save/restore}、{@code measureText}、
 * {@code drawText}、{@code fillRect} 上。
 *
 * <h2>实现必须遵守的三条</h2>
 * <ol>
 *   <li><b>{@link #begin()} / {@link #end()} 必须成对</b>，且实现要压/弹<b>全部</b>绘制
 *       状态（变换、裁剪、颜色、字号、拾取 ID）。标题与图例是"借"调用方的状态来画的，
 *       漏了还原的表现是"这张图之后画的图元全都变了样"，而那张图自己完全正常。</li>
 *   <li>期间<b>不参与拾取</b>（拾取 ID 为 0）：点在图例的色块上不该命中一个系列——
 *       图例是装饰，不是数据。它同时也避免了"色块恰好压在某个数据系列上"时
 *       把点击分给装饰。</li>
 *   <li>坐标是<b>设备像素、原点左上</b>（与整条管线一致），且 <b>{@link #begin()} 之后
 *       变换必须是单位阵</b>：布局算出来的矩形是设备像素，调用方进 {@code drawChart} 时
 *       若带着变换，标题会跟着变换跑，而图例的矩形不会。</li>
 * </ol>
 *
 * <h2>{@link #lineHeight(float)} 的口径</h2>
 * <p>它必须返回 {@code 字号 × ChartLayout.LINE_HEIGHT_FACTOR}，<b>不要</b>返回字体的
 * 真实行高——理由见 {@link ChartTextMetrics} 的类文档：布局要可被精确预测。
 */
public interface ChartPainter extends ChartTextMetrics {

    /**
     * 进入绘制：压栈，并把状态调成中性（变换为单位阵、拾取 ID 为 0）。
     *
     * <p>调用方必须保证与 {@link #end()} 成对。
     */
    void begin();

    /** 退出绘制：弹栈，把状态还原成 {@link #begin()} 之前的样子。 */
    void end();

    /**
     * 画一行文字。
     *
     * @param text     文本
     * @param x        起点 x（第一个字形的笔位置）
     * @param y        <b>基线</b>所在的像素行（不是文本框左上角，与 {@code Gc.drawText} 同一口径）
     * @param fontSize 字号（像素）
     * @param argb     颜色，{@code 0xAARRGGBB}
     */
    void drawText(String text, float x, float y, float fontSize, int argb);

    /**
     * 填一个矩形。只给图例的色块用。
     *
     * @param argb 颜色，{@code 0xAARRGGBB}
     */
    void fillRect(float x, float y, float width, float height, int argb);
}
