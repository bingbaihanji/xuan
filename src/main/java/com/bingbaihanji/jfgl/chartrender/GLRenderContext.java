package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.RenderContext;
import com.bingbaihanji.jfgl.chart.Series;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;

/**
 * 渲染一个系列时需要的全部东西。
 *
 * <p><strong>它必须是 {@link RenderContext} 的子接口，不能在 {@code RenderContext}
 * 原地加成员。</strong>{@code ChartPackageIsolationTest} 断言后者是空接口
 * （0 方法 / 0 字段 / 0 嵌套类型）——在原地加东西等于把 ② 的概念漏进 ①。
 *
 * <p>{@code SeriesRenderer} 的实现<b>第一行就向下转型</b>取它。
 *
 * <h2>这里只声明已经有人用的东西</h2>
 * <p>本接口只有折线渲染器一个消费者，所以这里只有它真正用到的那几项。
 * 拾取相关的成员（{@code pickShader} / {@code pickId} / {@code withPickPass}）
 * 是在 Task 13 <b>接拾取</b>时随消费者一起加进来的——
 * 预先声明一个没人实现也没人调用的方法，
 * 只会让"它到底有没有被验证过"变成一个说不清的问题。
 */
public interface GLRenderContext extends RenderContext {

    /** GL 抽象层。 */
    GLAbstraction gl();

    /** 绘制用的着色器程序。 */
    ShaderProgram lineShader();

    /** 拾取用的着色器程序。绘制路径不设它，只有 ID pass 用。 */
    ShaderProgram pickShader();

    /** 绘图区与映射。 */
    ChartRenderLayout layout();

    /** 帧缓冲宽度（设备像素）。 */
    int viewportWidth();

    /** 帧缓冲高度（设备像素）。 */
    int viewportHeight();

    /**
     * 取某系列的 GPU 常驻缓冲。
     *
     * <p>渲染器<b>不该自己持有它</b>——缓冲归 {@code ChartRenderer} 管，
     * 渲染器是无状态的纯函数（见 {@code SeriesRenderer} 的类文档）。
     *
     * @param series 当前正在渲染的系列
     * @return 该系列的常驻缓冲
     * @throws IllegalStateException 传进来的不是"当前系列"时（说明渲染器自己缓存了
     *                               跨系列的缓冲引用——那会画错，而且不报错）
     */
    SeriesBuffer bufferFor(Series series);

    /**
     * 当前系列的拾取 ID；<b>0 表示不参与拾取</b>（0 也是"什么都没命中"的返回值）。
     *
     * <p>ID 由 {@code ChartRenderer} 从 <b>{@code Gc} 的注册表</b>里取——
     * 不能另起一套，否则两套号会撞车，点击落在错误的对象上而画面正常。
     */
    int pickId();

    /**
     * 在拾取缓冲上执行一段绘制。转发给 {@code RenderBatch.withPickPass}——
     * 拾取缓冲与它那两个"本帧是否已清空/是否有效"的标志都归 {@code RenderBatch} 管，
     * 图表路径只是借用。
     *
     * @param body 要执行的绘制
     */
    void withPickPass(Runnable body);
}
