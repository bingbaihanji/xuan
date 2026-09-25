package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.Axis;
import com.bingbaihanji.jfgl.chart.ChartData;
import com.bingbaihanji.jfgl.chart.ChartType;
import com.bingbaihanji.jfgl.chart.RenderContext;
import com.bingbaihanji.jfgl.chart.Series;
import com.bingbaihanji.jfgl.chart.SeriesRenderer;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;
import com.bingbaihanji.jfgl.util.Rect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;

/**
 * 柱状图渲染器：{@link ChartType#BAR}。
 *
 * <p>每个样本一根柱：在 {@code (样本值, 基线)} 之间的矩形，柱心落在样本的屏幕 x 上。
 * 与折线/散点同构（构造时建 VAO + 角点 VBO、当场发 instanced draw call、颜色与 ID 两个 pass、
 * 自己负责进出时的 GL 状态），只有三处不同：
 *
 * <ol>
 *   <li><b>实例数是点数</b>（{@link WindowRange#computePoints}）——与散点同类，
 *       一根柱自己就是完整的，没有"第二端"，所以只配一个实例属性 {@code aY}。
 *       用线段版本（{@code writeIndex - 1}）会让<b>最后一根柱静默消失</b>。</li>
 *   <li><b>柱宽与柱心位置来自 {@link BarLayout}</b>（纯算术，可单测），
 *       着色器只认两个数：半宽与偏移。</li>
 *   <li><b>下沿是 {@code Series.baseline()} 那个数值</b>，不是绘图区下边缘——
 *       理由见 {@code chart/Series} 的字段说明（轴一放大柱子高度就会跟着轴走，
 *       而画面完全正常）。</li>
 * </ol>
 *
 * <h2>并排（分组）的信息从哪来</h2>
 * <p>{@code SeriesRenderer.render} 的签名里只有自己那一个系列，<b>看不到兄弟系列</b>。
 * "我是本层第几根、一共几根"由 {@link ChartRenderer} 按层算好、经
 * {@link GLRenderContext#barSlot()} / {@link GLRenderContext#barCount()} 注入。
 * 少了这一步（恒当第 0 根、总数 1）的后果是同层多个柱状系列<b>完全重叠</b>：
 * 画面上只剩最后画的那一个，看起来就是一张正常的单系列柱状图。
 *
 * <h2>GL 状态由它自己负责，因为它不在 {@code RenderBatch.submit} 里</h2>
 * <p>与 {@link LineSeriesRenderer} 逐条相同（{@code glScissor} 的启用与还原、
 * 预乘混合因子、VAO 与程序的解绑）。ID pass 用的也是同一个裁剪盒，
 * 于是被裁掉的部分不可拾取，与画面一致；柱很窄时热区被
 * {@code max(uBarHalfWidth, uPickTolerance)} 撑宽——只影响拾取，不影响画面。
 */
final class BarSeriesRenderer implements SeriesRenderer {

    /** 四个角，按 triangle strip 顺序：（左/右边缘 × 数值/基线）。 */
    private static final float[] CORNERS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
    };

    /**
     * 拾取容差（半宽，设备像素）。与折线/散点同值同理由：一格很窄时柱子可能只有一两像素宽，
     * 要求用户精确点中不合理。它只影响 ID pass，<b>不影响画面</b>。
     */
    private static final float PICK_TOLERANCE_PX = 4f;

    private final GLAbstraction gl;

    private final int vao;

    private final int cornerVbo;

    BarSeriesRenderer(GLAbstraction gl) {
        this.gl = gl;
        this.vao = gl.createVao();
        this.cornerVbo = gl.createVbo();

        gl.bindVao(vao);
        gl.bindVbo(cornerVbo);
        ByteBuffer corners = ByteBuffer.allocateDirect(CORNERS.length * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (float c : CORNERS) {
            corners.putFloat(c);
        }
        corners.flip();
        gl.uploadVboBytes(corners);

        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        gl.setVertexAttribDivisor(0, 0);

        gl.bindVbo(0);
        gl.bindVao(0);
    }

    /**
     * 配置数据侧的属性指针。
     *
     * <p><b>只有一个属性</b>（location 1 = {@code aY}，步长 4 字节、偏移 0）——
     * 与散点同类：一根柱自己就是完整的。折线那种"第二个属性"在这里<b>必须不存在</b>：
     * 多配一个没人声明的 location 不会报错也不会画错，只是被静默忽略。
     *
     * <p>调用方必须已经绑定本类的 VAO 与系列的 VBO。
     */
    private void configureDataAttributes(int seriesVbo) {
        gl.bindVbo(seriesVbo);
        glVertexAttribPointer(1, 1, GL_FLOAT, false, Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        gl.setVertexAttribDivisor(1, 1);

        gl.bindVbo(0);
    }

    @Override
    public void render(RenderContext ctx, ChartData data, Series series, Axis[] axes) {
        GLRenderContext c = (GLRenderContext) ctx;
        requireSupported(series.type());

        SeriesBuffer buffer = c.bufferFor(series);
        buffer.uploadNewSamples();

        double windowStart = axes[0].windowMin();
        double windowEnd = axes[0].windowMax();
        // **点数版本**：一根柱只需要自己的值。
        List<WindowRange.Segment> segments = WindowRange.computePoints(
                windowStart, windowEnd, buffer.writeCount(), buffer.capacity());
        if (segments.isEmpty()) {
            return;
        }

        ChartRenderLayout layout = c.layout();
        Rect plot = layout.plotRect();
        float pxPerSample = (float) (plot.width / (windowEnd - windowStart));

        // 柱宽与柱心偏移：纯算术在 BarLayout 里（可单测），这里只把结论交给着色器。
        // count/slot 由 ChartRenderer 按层注入（见类文档）。
        int count = c.barCount();
        int slot = c.barSlot();
        float halfWidth = BarLayout.halfWidth(
                pxPerSample, series.categoryGap(), series.barGap(), count);
        float barOffset = BarLayout.offset(
                pxPerSample, series.categoryGap(), series.barGap(), count, slot);

        boolean scissorWasOn = gl.isScissorEnabled();
        gl.setScissorEnabled(true);
        setScissorTo(plot, c.viewportHeight());

        gl.enableBlend();
        // 必须在 enableBlend() 之后：它会顺手把混合因子设成非预乘的那一组。
        gl.setBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        gl.bindVao(vao);
        configureDataAttributes(buffer.vboId());

        ShaderProgram shader = c.barShader();
        shader.use();
        setCommonUniforms(shader, c, layout, plot, series,
                pxPerSample, halfWidth, barOffset, 0f, 0);
        // 系列是否开 AA 由调用方决定（Gc.antialias 透传下来）。
        // 这里用 uniform 是安全的：一个系列一条 draw call，不涉及 VertexWriter 的合批。
        // 放在 setCommonUniforms **之外**：那一套两个 pass 共用，而 uAntialias 只属于绘制。
        shader.setUniform("uAntialias", c.antialias() ? 1f : 0f);

        double windowFloor = Math.floor(windowStart);
        for (WindowRange.Segment seg : segments) {
            shader.setUniform("uFirstRelIndex",
                    (float) (seg.firstDataIndex() - windowFloor - (windowStart - windowFloor)));
            gl.drawArraysInstancedBaseInstance(
                    GL_TRIANGLE_STRIP, 0, 4, seg.instanceCount(), seg.firstInstance());
        }

        shader.unuse();

        // ID pass：同一份 VAO、同一批实例，只换程序。
        int pickId = c.pickId();
        if (pickId != 0) {
            ShaderProgram pick = c.barPickShader();
            pick.use();
            // 与绘制那套 uniform 逐个对齐（含柱宽与偏移），少设任何一个都会让
            // "点到的地方不是看到的地方"。
            setCommonUniforms(pick, c, layout, plot, series,
                    pxPerSample, halfWidth, barOffset, PICK_TOLERANCE_PX, pickId);

            setScissorTo(plot, c.viewportHeight());
            c.withPickPass(() -> {
                for (WindowRange.Segment seg : segments) {
                    pick.setUniform("uFirstRelIndex",
                            (float) (seg.firstDataIndex() - windowFloor - (windowStart - windowFloor)));
                    gl.drawArraysInstancedBaseInstance(
                            GL_TRIANGLE_STRIP, 0, 4, seg.instanceCount(), seg.firstInstance());
                }
            });
            pick.unuse();
        }

        gl.bindVao(0);
        gl.disableBlend();
        gl.setScissorEnabled(scissorWasOn);
    }

    /**
     * 两个 pass 共用的那一套 uniform（写成方法而不是各写一遍，理由见
     * {@code StepSeriesRenderer.setCommonUniforms}）。
     *
     * @param tolerance 绘制时传 0，ID pass 传 {@link #PICK_TOLERANCE_PX}
     */
    private static void setCommonUniforms(ShaderProgram shader, GLRenderContext c,
                                          ChartRenderLayout layout, Rect plot, Series series,
                                          float pxPerSample, float halfWidth, float barOffset,
                                          float tolerance, int pickId) {
        shader.setUniform("uPlotRect", plot.x, plot.y, plot.width, plot.height);
        shader.setUniform("uViewport", (float) c.viewportWidth(), (float) c.viewportHeight());
        shader.setUniform("uValueRange", layout.yMin(), layout.yMax());
        shader.setUniform("uPxPerSample", pxPerSample);
        shader.setUniform("uBarHalfWidth", halfWidth);
        shader.setUniform("uBarOffset", barOffset);
        // 下沿是一个**数值**（默认 0），不是绘图区下边缘。
        shader.setUniform("uBaseline", series.baseline());
        shader.setUniform("uPickTolerance", tolerance);
        int argb = series.color();
        shader.setUniform("uColor",
                ((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f,
                ((argb >>> 24) & 0xFF) / 255f);
        shader.setUniform("uPickId", pickId);
    }

    /**
     * 本渲染器只画它真正画得出来的图型，其余明确报错。
     *
     * <p><strong>只接受 {@link ChartType#BAR}。</strong>不能写成"只要
     * {@code drawsBars()} 就放行"（目前只有它一个为真，但那是一条会随枚举增长的判据），
     * 也不能接受散点：柱子的顶点是"数值与基线之间的矩形"，当成居中的标记点画会得到
     * 一串方点——<b>柱高这个含义整个消失</b>，而画面看起来像一张散点图。
     */
    private static void requireSupported(ChartType type) {
        if (type == ChartType.BAR) {
            return;
        }
        throw new IllegalArgumentException(
                "BarSeriesRenderer 不支持图型 " + type + "。明确报错而不是静默画错："
                        + "折线/散点/面积当成柱画，形状与含义全都变了，而画面看起来正常。");
    }

    /**
     * 把裁剪盒设到绘图区（GL 侧的原点在左下、y 向上，所以要翻一次，
     * 理由见 {@code LineSeriesRenderer} 的同一方法）。
     */
    private static void setScissorTo(Rect plot, int viewportHeight) {
        int y = viewportHeight - (int) plot.y - (int) plot.height;
        glScissor((int) plot.x, y, (int) plot.width, (int) plot.height);
    }

    /** 释放本类持有的 GL 资源（VAO 与角点 VBO）。 */
    void dispose() {
        gl.deleteVbo(cornerVbo);
        gl.deleteVao(vao);
    }
}
