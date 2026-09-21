package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.Series;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;

/**
 * {@link GLRenderContext} 的实现。
 *
 * <p>由 {@link ChartRenderer} 建一次、每换一个系列改一次"当前系列"与"当前缓冲"。
 * 一帧里它被复用多次，因此<b>不是线程安全的</b>——与 {@code Gc} 同一条纪律，
 * 只在 GL 线程上使用。
 *
 * <h2>为什么"当前系列"要在构造函数之外单独设</h2>
 * <p>因为 {@link #bufferFor} 要在<b>取缓冲的那一刻</b>就发现自己拿错了系列。
 * 渲染器是无状态的纯函数，它要的缓冲由装配方（{@code ChartRenderer}）注入；
 * 一旦渲染器自己把缓冲存下来跨系列复用，画出来的就是<b>另一个系列的波形</b>——
 * 画面正常、不报错，只是数据对不上（本项目最警惕的那类缺陷）。
 * 让它在越界的那一刻炸，比让它悄悄画错便宜得多。
 */
final class GLRenderContextImpl implements GLRenderContext {

    private final GLAbstraction gl;

    private final ShaderProgram lineShader;

    private final ChartRenderLayout layout;

    private final int viewportWidth;

    private final int viewportHeight;

    /** 当前正在渲染的系列。 */
    private Series currentSeries;

    /** 当前系列的常驻缓冲，由 {@code ChartRenderer} 每换一个系列注入一次。 */
    private SeriesBuffer currentBuffer;

    /**
     * 当前系列的拾取 ID；0 表示不参与拾取。
     *
     * <p>本期（Task 10/11）还没有拾取 pass，所以它暂时没有读取方；
     * ID 的分配与注销都已经落在 {@link ChartRenderer} 里，Task 13 只需要读这一项。
     */
    private int pickId;

    GLRenderContextImpl(GLAbstraction gl, ShaderProgram lineShader, ChartRenderLayout layout,
                        int viewportWidth, int viewportHeight) {
        this.gl = gl;
        this.lineShader = lineShader;
        this.layout = layout;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
    }

    /** 设置当前正在渲染的系列。必须在 {@link #setCurrentBuffer} 之前调用。 */
    void setCurrentSeries(Series series) {
        this.currentSeries = series;
    }

    /** 设置当前系列的常驻缓冲。 */
    void setCurrentBuffer(SeriesBuffer buffer) {
        this.currentBuffer = buffer;
    }

    /** 设置当前系列的拾取 ID。 */
    void setPickId(int pickId) {
        this.pickId = pickId;
    }

    @Override
    public GLAbstraction gl() {
        return gl;
    }

    @Override
    public ShaderProgram lineShader() {
        return lineShader;
    }

    @Override
    public ChartRenderLayout layout() {
        return layout;
    }

    @Override
    public int viewportWidth() {
        return viewportWidth;
    }

    @Override
    public int viewportHeight() {
        return viewportHeight;
    }

    /**
     * {@inheritDoc}
     *
     * <p>只认"当前系列"，其余一律抛异常：渲染器若自己缓存了缓冲引用，就会在这里暴露。
     */
    @Override
    public SeriesBuffer bufferFor(Series series) {
        if (series != currentSeries) {
            throw new IllegalStateException(
                    "bufferFor 收到的系列不是当前正在渲染的那个："
                            + "传入「" + series.name() + "」，当前是「"
                            + (currentSeries == null ? "null" : currentSeries.name()) + "」。"
                            + "渲染器不该缓存跨系列的缓冲引用——那会画出**另一个系列的波形**，"
                            + "而画面完全正常、不报错。");
        }
        return currentBuffer;
    }
}
