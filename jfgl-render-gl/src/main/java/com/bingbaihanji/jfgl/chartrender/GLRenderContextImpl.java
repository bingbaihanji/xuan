package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.Series;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;

import java.util.function.Consumer;

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

    /**
     * 折线族拾取用的程序（{@code LINE_VERTEX} + {@code PICK_FRAGMENT}）。
     *
     * <p>散点有<b>自己</b>的一份拾取程序（见 {@link #scatterPickShader}）：顶点程序决定
     * 实例的几何，拿折线的顶点程序去画散点的热区会得到一个错误形状的拾取面。
     */
    private final ShaderProgram pickShader;

    private final ShaderProgram scatterShader;

    private final ShaderProgram scatterPickShader;

    /** 阶梯线、面积填充、柱状图的绘制与拾取程序（见 {@link GLRenderContext} 的说明）。 */
    private final ShaderProgram stepShader;

    private final ShaderProgram stepPickShader;

    private final ShaderProgram areaShader;

    private final ShaderProgram areaPickShader;

    private final ShaderProgram barShader;

    private final ShaderProgram barPickShader;

    /**
     * 拾取缓冲的借用入口，由 {@code ChartRenderer} 收进来的
     * {@code RenderBatch.withPickPass}。图表路径只转发，不自己绑 FBO 也不自己清缓冲。
     */
    private final Consumer<Runnable> pickPass;

    private final ChartRenderLayout layout;

    private final int viewportWidth;

    private final int viewportHeight;

    /** 本帧的系列是否做解析式 AA；由 {@code ChartRenderer} 在 draw 入口取一次快照。 */
    private final boolean antialias;

    /** 当前正在渲染的系列。 */
    private Series currentSeries;

    /** 当前系列的常驻缓冲，由 {@code ChartRenderer} 每换一个系列注入一次。 */
    private SeriesBuffer currentBuffer;

    /** 当前系列的拾取 ID；0 表示不参与拾取。由 {@code ChartRenderer} 每换一个系列注入一次。 */
    private int pickId;

    /** 当前柱状系列在本层并排柱里的序号与总数。由 {@code ChartRenderer} 每换一个系列注入一次。 */
    private int barSlot;

    private int barCount = 1;

    GLRenderContextImpl(GLAbstraction gl, ShaderProgram lineShader, ShaderProgram pickShader,
                        ShaderProgram scatterShader, ShaderProgram scatterPickShader,
                        ShaderProgram stepShader, ShaderProgram stepPickShader,
                        ShaderProgram areaShader, ShaderProgram areaPickShader,
                        ShaderProgram barShader, ShaderProgram barPickShader,
                        Consumer<Runnable> pickPass, ChartRenderLayout layout,
                        int viewportWidth, int viewportHeight, boolean antialias) {
        this.gl = gl;
        this.lineShader = lineShader;
        this.pickShader = pickShader;
        this.scatterShader = scatterShader;
        this.scatterPickShader = scatterPickShader;
        this.stepShader = stepShader;
        this.stepPickShader = stepPickShader;
        this.areaShader = areaShader;
        this.areaPickShader = areaPickShader;
        this.barShader = barShader;
        this.barPickShader = barPickShader;
        this.pickPass = pickPass;
        this.layout = layout;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.antialias = antialias;
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

    /**
     * 设置当前柱状系列在本层并排柱里的序号与总数。
     *
     * <p>{@code count} 必须 &ge; 1：它是柱宽公式的分母里那一项，0 会让柱子宽到铺满屏幕。
     * 这道校验放在这里而不是等 {@code BarLayout} 抛，是因为"渲染器拿到 0 再算"与
     * "装配方传了 0"是两件事，前者会把排查方向引到渲染器上。
     */
    void setBarSlot(int slot, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("并排的柱状系列数必须至少为 1，实际 " + count);
        }
        this.barSlot = slot;
        this.barCount = count;
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
    public ShaderProgram scatterShader() {
        return scatterShader;
    }

    @Override
    public ShaderProgram pickShader() {
        return pickShader;
    }

    @Override
    public ShaderProgram scatterPickShader() {
        return scatterPickShader;
    }

    @Override
    public ShaderProgram stepShader() {
        return stepShader;
    }

    @Override
    public ShaderProgram stepPickShader() {
        return stepPickShader;
    }

    @Override
    public ShaderProgram areaShader() {
        return areaShader;
    }

    @Override
    public ShaderProgram areaPickShader() {
        return areaPickShader;
    }

    @Override
    public ShaderProgram barShader() {
        return barShader;
    }

    @Override
    public ShaderProgram barPickShader() {
        return barPickShader;
    }

    @Override
    public int barSlot() {
        return barSlot;
    }

    /**
     * {@inheritDoc}
     *
     * <p>存的是**值**不是"能读回 Gc 的东西"：一个 draw 的入口取一次快照，
     * 于是同一个 draw 里所有系列、两个 pass 用的是同一个值。
     */
    @Override
    public boolean antialias() {
        return antialias;
    }

    @Override
    public int barCount() {
        return barCount;
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

    /** {@inheritDoc} */
    @Override
    public int pickId() {
        return pickId;
    }

    /**
     * {@inheritDoc}
     *
     * <p>一行转发，不做任何判断：<b>进 ID pass 之前该开的东西由渲染器自己负责</b>
     * （尤其是 {@code GL_SCISSOR_TEST}——图表路径不在 {@code RenderBatch.submit} 里，
     * 而那边是 {@code submit} 替它开的）。本类这里只做"借用"这一个动作。
     */
    @Override
    public void withPickPass(Runnable body) {
        pickPass.accept(body);
    }
}
