package com.bingbaihanji.xuan.chartrender;

import com.bingbaihanji.xuan.chart.Axis;
import com.bingbaihanji.xuan.chart.ChartData;
import com.bingbaihanji.xuan.chart.ChartType;
import com.bingbaihanji.xuan.chart.RenderContext;
import com.bingbaihanji.xuan.chart.Series;
import com.bingbaihanji.xuan.chart.SeriesRenderer;
import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.gl.ShaderProgram;
import com.bingbaihanji.xuan.util.Rect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;

/**
 * 阶梯线渲染器：{@link ChartType#STEP}。
 *
 * <p>与 {@link LineSeriesRenderer} 同构（构造时建 VAO + 角点 VBO、当场发 instanced draw call、
 * 颜色与 ID 两个 pass、自己负责进出时的 GL 状态），<b>只有两处必须不同</b>：
 *
 * <ol>
 *   <li><b>角点有六个、不是一个四边形</b>：{@code aCorner.x} 是点序号 {@code 0/1/2}，
 *       六个角组成两个共用拐角的四边形（理由见 {@link SeriesShaders#STEP_VERTEX}）。</li>
 *   <li><b>用的是 {@link GLRenderContext#stepShader()} 而不是折线程序</b>——
 *       拿折线的顶点程序（四个角）画阶梯，中间那两个点根本没地方放，
 *       结果是<b>一条普通斜线</b>：阶梯图形被拉直，而画面完全正常。</li>
 * </ol>
 *
 * <p>数据侧与折线<b>逐项相同</b>：每个实例两个 float（{@code y[i]} 与 {@code y[i+1]}，
 * 同一个 VBO、偏移差 4 字节），实例数是线段数（{@link WindowRange#compute}），
 * NaN 让整个实例消失。所以缓冲、增量上传、环绕切分那一整套都照用。
 *
 * <h2>GL 状态由它自己负责，因为它不在 {@code RenderBatch.submit} 里</h2>
 * <p>与 {@link LineSeriesRenderer} 逐条相同（{@code glScissor} 的启用与还原、
 * 预乘混合因子、VAO 与程序的解绑），理由见那个类的类文档。ID pass 用的也是同一个
 * 裁剪盒，于是被裁掉的部分不可拾取，与画面一致。
 */
final class StepSeriesRenderer implements SeriesRenderer {

    /**
     * 六个角，按 triangle strip 顺序：{@code (点序号, 法向侧)} =
     * {@code (0,0) (0,1) (1,0) (1,1) (2,0) (2,1)}。
     *
     * <p>顺序不能换：前四个角组成水平段的粗四边形，后四个组成竖直段的粗四边形，
     * 中间的 {@code (1,*)} 那一对是两个四边形<b>共用</b>的拐角顶点——换掉顺序
     * 会让两个四边形各画各的，拐角处豁口（见 {@link SeriesShaders#STEP_VERTEX}）。
     */
    private static final float[] CORNERS = {
            0f, 0f,
            0f, 1f,
            1f, 0f,
            1f, 1f,
            2f, 0f,
            2f, 1f,
    };

    /** 一个实例画几个顶点（六个角，两个四边形）。 */
    private static final int CORNER_VERTICES = CORNERS.length / 2;

    /**
     * 拾取容差（半宽，设备像素）。与 {@code LineSeriesRenderer.PICK_TOLERANCE_PX} 同值同理由：
     * 画出来的线只有 1~2px 宽，要求用户精确点中不合理。它只影响 ID pass，<b>不影响画面</b>。
     */
    private static final float PICK_TOLERANCE_PX = 4f;

    private final GLAbstraction gl;

    private final int vao;

    private final int cornerVbo;

    StepSeriesRenderer(GLAbstraction gl) {
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

        // location 0：六个角，每顶点取一次
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        gl.setVertexAttribDivisor(0, 0);

        gl.bindVbo(0);
        gl.bindVao(0);
    }

    /**
     * 两个 pass 共用的那一套 uniform。
     *
     * <p><b>写成方法而不是在两个地方各写一遍</b>：绘制与拾取必须用同一套几何
     * （只差容差与 ID 两个量），两处各写一遍迟早会分叉，而分叉的表现是
     * "点到的地方不是看到的地方"——那是本类最怕的静默缺陷。
     *
     * @param tolerance 绘制时传 0（取真实线宽），ID pass 传 {@link #PICK_TOLERANCE_PX}
     * @param pickId    绘制时传 0，ID pass 传真实号
     */
    private static void setCommonUniforms(ShaderProgram shader, GLRenderContext c,
                                          ChartRenderLayout layout, Rect plot, Series series,
                                          double windowStart, double windowEnd,
                                          float tolerance, int pickId) {
        shader.setUniform("uPlotRect", plot.x, plot.y, plot.width, plot.height);
        shader.setUniform("uViewport", (float) c.viewportWidth(), (float) c.viewportHeight());
        shader.setUniform("uValueRange", layout.yMin(), layout.yMax());
        shader.setUniform("uPxPerSample",
                (float) (plot.width / (windowEnd - windowStart)));
        shader.setUniform("uHalfWidth", series.lineWidth() * 0.5f);
        shader.setUniform("uPickTolerance", tolerance);
        // Series.color() 返回 ARGB 整数，按 0xAARRGGBB 拆分量。
        int argb = series.color();
        shader.setUniform("uColor",
                ((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f,
                ((argb >>> 24) & 0xFF) / 255f);
        // 必须是 int 的那个 setUniform（glUniform1i）：对 uint uniform 用它报
        // GL_INVALID_OPERATION 且值保持 0，而 0 正是"什么都没命中"。
        shader.setUniform("uPickId", pickId);
    }

    /**
     * 本渲染器只画它真正画得出来的图型，其余明确报错。
     *
     * <p><strong>只接受 {@link ChartType#STEP}。</strong>不能写成"只要
     * {@code connectsSamples()} 就放行"：{@link ChartType#LINE} 与
     * {@link ChartType#LINE_AND_MARKERS} 同样落在那个判据里，而本渲染器会把它们
     * <b>每一段都画成"先横后竖"</b>——一条平滑的曲线变成锯齿台阶。
     * 锯齿看起来像"采样率不够"，不像缺陷。
     */
    private static void requireSupported(ChartType type) {
        if (type == ChartType.STEP) {
            return;
        }
        throw new IllegalArgumentException(
                "StepSeriesRenderer 不支持图型 " + type + "。明确报错而不是静默画错："
                        + "把折线按阶梯画，平滑的曲线会变成一级级台阶，而那看起来像采样率不够；"
                        + "把柱状图按阶梯画，柱子的高度含义整个消失。");
    }

    /**
     * 把裁剪盒设到绘图区。
     *
     * <p>{@code glScissor} 的原点在帧缓冲左下角、y 向上，而本管线是"原点左上、y 向下"，
     * 所以 GL 侧的下边 = {@code viewportHeight - plot.y - plot.height}。漏掉这一步的后果是
     * 裁剪区上下镜像——数据在绘图区下半部分被裁掉、上半部分却画到了外面。
     */
    private static void setScissorTo(Rect plot, int viewportHeight) {
        int y = viewportHeight - (int) plot.y - (int) plot.height;
        glScissor((int) plot.x, y, (int) plot.width, (int) plot.height);
    }

    /**
     * 配置数据侧的属性指针。
     *
     * <p>与 {@link LineSeriesRenderer} 逐字相同：同一个 VBO 绑两次、偏移差 4 个字节
     * （偏移 0 拿 {@code y[i]}，偏移 4 拿 {@code y[i+1]}），步长 4 由 {@code baseInstance}
     * 挪到环里正确那一段。写成 8 会让相邻实例间隔一个样本——阶梯的主题宽度<strong>整体翻倍</strong>，
     * 而台阶看起来仍然是一级一级的，最难查的那种。
     *
     * <p>调用方必须已经绑定本类的 VAO 与系列的 VBO。
     */
    private void configureDataAttributes(int seriesVbo) {
        gl.bindVbo(seriesVbo);
        glVertexAttribPointer(1, 1, GL_FLOAT, false, Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        gl.setVertexAttribDivisor(1, 1);

        glVertexAttribPointer(2, 1, GL_FLOAT, false, Float.BYTES, Float.BYTES);
        glEnableVertexAttribArray(2);
        gl.setVertexAttribDivisor(2, 1);

        gl.bindVbo(0);
    }

    @Override
    public void render(RenderContext ctx, ChartData data, Series series, Axis[] axes) {
        GLRenderContext c = (GLRenderContext) ctx;
        requireSupported(series.type());

        SeriesBuffer buffer = c.bufferFor(series);
        buffer.uploadNewSamples();

        // 可见窗口就是 x 轴的窗口（x 轴的单位是数据下标）；写指针取已上传数，理由见 SeriesBuffer。
        double windowStart = axes[0].windowMin();
        double windowEnd = axes[0].windowMax();
        List<WindowRange.Segment> segments = WindowRange.compute(
                windowStart, windowEnd, buffer.writeCount(), buffer.capacity());
        if (segments.isEmpty()) {
            return;
        }

        ChartRenderLayout layout = c.layout();
        Rect plot = layout.plotRect();

        boolean scissorWasOn = gl.isScissorEnabled();
        gl.setScissorEnabled(true);
        setScissorTo(plot, c.viewportHeight());

        gl.enableBlend();
        // 必须在 enableBlend() 之后：它会顺手把混合因子设成非预乘的那一组。
        gl.setBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        gl.bindVao(vao);
        configureDataAttributes(buffer.vboId());

        ShaderProgram shader = c.stepShader();
        shader.use();
        setCommonUniforms(shader, c, layout, plot, series, windowStart, windowEnd, 0f, 0);
        // 系列是否开 AA 由调用方决定（Gc.antialias 透传下来）。
        // 这里用 uniform 是安全的：一个系列一条 draw call，不涉及 VertexWriter 的合批。
        // 放在 setCommonUniforms **之外**而不是里面：那一套是绘制与 ID pass 共用的，
        // 而 uAntialias 只属于绘制那一趟（ID pass 的片段程序里压根没有它）。
        shader.setUniform("uAntialias", c.antialias() ? 1f : 0f);

        // 横轴的锚点：绝对下标是 long，着色器里用不了，所以传"本段第一个实例相对窗口左端的偏移"。
        double windowFloor = Math.floor(windowStart);
        for (WindowRange.Segment seg : segments) {
            shader.setUniform("uFirstRelIndex",
                    (float) (seg.firstDataIndex() - windowFloor - (windowStart - windowFloor)));
            gl.drawArraysInstancedBaseInstance(
                    GL_TRIANGLE_STRIP, 0, CORNER_VERTICES, seg.instanceCount(), seg.firstInstance());
        }

        shader.unuse();

        // ID pass：同一份 VAO、同一批实例，只换程序（插在 unuse 与 bindVao(0) 之间是有意的，
        // 此刻实例属性指针还是绘制时那套）。容差放宽成热区，只影响拾取。
        int pickId = c.pickId();
        if (pickId != 0) {
            ShaderProgram pick = c.stepPickShader();
            pick.use();
            // 与绘制那套 uniform 逐个对齐：少设任何一个，拾取的位置就和画面不一致。
            setCommonUniforms(pick, c, layout, plot, series, windowStart, windowEnd,
                    PICK_TOLERANCE_PX, pickId);

            // 裁剪盒此刻还生效，于是 withPickPass 那条"调用方必须确保 scissor 已启用"的契约
            // 天然满足：被裁掉的部分不可拾取，与画面一致。
            setScissorTo(plot, c.viewportHeight());
            c.withPickPass(() -> {
                for (WindowRange.Segment seg : segments) {
                    pick.setUniform("uFirstRelIndex",
                            (float) (seg.firstDataIndex() - windowFloor - (windowStart - windowFloor)));
                    gl.drawArraysInstancedBaseInstance(
                            GL_TRIANGLE_STRIP, 0, CORNER_VERTICES,
                            seg.instanceCount(), seg.firstInstance());
                }
            });
            pick.unuse();
        }

        gl.bindVao(0);
        gl.disableBlend();
        gl.setScissorEnabled(scissorWasOn);
    }

    /** 释放本类持有的 GL 资源（VAO 与角点 VBO）。 */
    void dispose() {
        gl.deleteVbo(cornerVbo);
        gl.deleteVao(vao);
    }
}
