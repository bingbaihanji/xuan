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

import java.util.List;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;

/**
 * 面积图渲染器：{@link ChartType#AREA}。
 *
 * <p>面积图是<b>两样东西</b>：曲线下方的一块填充，加上曲线本身那条轮廓线
 * （JavaFX 的 AreaChart 也是这样——{@code chart-series-area-fill} 与
 * {@code chart-series-area-line} 两份样式）。
 *
 * <h2>填充走自己的程序，轮廓线交给折线路径</h2>
 * <p>填充的顶点是"线段两端各向基线垂下来"的梯形，与折线的"沿法向撑开"不是一回事，
 * 所以有自己的顶点程序（{@link SeriesShaders#AREA_VERTEX}）。
 * 而轮廓线就是一条<b>普通折线</b>，一份代码都不该再抄——所以这里
 * 显式调用 {@link LineSeriesRenderer#renderPolyline}。
 *
 * <p><b>为什么不直接调 {@code lineRenderer.render()}。</b>那个方法的第一句是
 * {@code requireSupported(AREA)}，而它<b>正确地</b>拒绝 AREA（"面积图交给折线渲染器
 * 画，整个填充会消失，而画面完全正常"）。那条守卫是刻意的，不该为了复用而拆掉；
 * 于是这里调的是它的"无守卫"版本，而守卫留在 {@code ChartRenderer} 的查表 + 各
 * {@code render()} 的入口上——即"哪些图型归哪个渲染器"这件事只有一处判断。
 *
 * <h2>填充的不透明度是必须的，不是装饰</h2>
 * <p>填充与轮廓线同色同不透明度时，<b>轮廓线在画面上看不出存在过</b>——
 * "轮廓画了"与"轮廓没画"逐像素相同，这条路径就没有视觉证据了。
 * 所以 {@code Series.fillAlpha()} 默认 0.5（与 JavaFX 的
 * {@code CHART_COLOR_n_TRANSPARENT} 一致），填充是半透明的、轮廓线是实心的。
 *
 * <p>转换只发生在 {@link #fillColor} 一处：它同时把 {@code fillAlpha} 钳到 [0,1]
 * 并与主色自带的不透明度相乘。分成两处写的话，"半透明的系列 + 半透明的填充"
 * 迟早会一处乘一处不乘，而两者的差别在画面上只是"颜色深一点"。
 *
 * <h2>轮廓线画在填充之上</h2>
 * <p>先填充、后轮廓，所以曲线的上边缘是实心色。这个顺序不是随意的：
 * 反过来（先线后填充）半透明填充会把线压暗，那看起来只是"颜色不对"。
 *
 * <h2>GL 状态由它自己负责，因为它不在 {@code RenderBatch.submit} 里</h2>
 * <p>与 {@link LineSeriesRenderer} 逐条相同：裁剪（{@code glScissor} 只在
 * {@code GL_SCISSOR_TEST} 启用时生效，而 submit 结束时是关着的）、混合
 * （片段着色器输出预乘色，因子必须是 {@code GL_ONE / GL_ONE_MINUS_SRC_ALPHA}）、
 * VAO 与程序的解绑。填充与轮廓<b>各自成对地</b>做，之间不共享状态——
 * 委托给折线路径时本类已经还原干净了。
 */
final class AreaSeriesRenderer implements SeriesRenderer {

    /**
     * 四个角，按 triangle strip 顺序：（线段的哪一端 × 曲线上还是基线上）。
     *
     * <p>与折线的四边形<b>角点值相同、含义不同</b>：折线的 {@code aCorner.y} 是
     * "法向的哪一侧"，这里的是"取数据值还是取基线"。两个含义互换会让填充
     * 变成"沿法向撑开的一条带"——一条跟着曲线起伏的粗线，看起来像加粗的折线。
     *
     * <p>它的后面还接着四档"平滑站位"（见 {@link SmoothCurve#cornerData}），
     * 与折线用的是同一份：{@code aCorner.y} 的含义不变（0 = 曲线、1 = 基线），
     * {@code aCorner.x} 变成站位参数 t。
     */
    private static final float[] CORNERS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
    };

    /** 非平滑时的顶点数：那一个梯形。 */
    private static final int PLAIN_VERTICES = CORNERS.length / 2;

    private final GLAbstraction gl;

    private final LineSeriesRenderer lineRenderer;

    private final int vao;

    private final int cornerVbo;

    /**
     * @param lineRenderer 绘制轮廓线的折线路径（见类文档；由 {@code ChartRenderer} 注入）
     */
    AreaSeriesRenderer(GLAbstraction gl, LineSeriesRenderer lineRenderer) {
        this.gl = gl;
        this.lineRenderer = lineRenderer;
        this.vao = gl.createVao();
        this.cornerVbo = SmoothCurve.createCornerVbo(gl, CORNERS);

        gl.bindVao(vao);
        gl.bindVbo(cornerVbo);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        gl.setVertexAttribDivisor(0, 0);

        gl.bindVbo(0);
        gl.bindVao(0);
    }

    /**
     * 填充色：主色 × 钳到 [0,1] 的 {@link Series#fillAlpha()}。
     *
     * <p>返回 ARGB。乘上主色自带的不透明度是有意的：系列整体半透明（{@code 0x80FF0000}）
     * 时，填充不该反过来变得比线更实。
     */
    private static int fillColor(Series series) {
        int argb = series.color();
        float alpha = series.fillAlpha();
        if (alpha < 0f) {
            alpha = 0f;
        } else if (alpha > 1f) {
            alpha = 1f;
        }
        int a = Math.round(((argb >>> 24) & 0xFF) * alpha);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * 配置数据侧的属性指针：与折线逐字相同（四个控制点、同一个 VBO、偏移差 4 字节），
     * 因为面积图的实例同样是"一个线段"——两端各向基线垂下来。
     *
     * <p>共用的是 {@link SmoothCurve#configureDataAttributes} 那一份：
     * 填充的顶边与轮廓线必须是<b>同一条曲线</b>，两处各写一份的话迟早分叉，
     * 而分叉的表现是沿曲线露出一条背景色的细缝。
     */
    private void configureDataAttributes(int seriesVbo, int biasBytes) {
        SmoothCurve.configureDataAttributes(gl, seriesVbo, biasBytes);
    }

    @Override
    public void render(RenderContext ctx, ChartData data, Series series, Axis[] axes) {
        GLRenderContext c = (GLRenderContext) ctx;
        requireSupported(series.type());

        // 缓冲归 ChartRenderer 管（SeriesRenderer 是纯函数）。
        // 注意：下面委托给折线路径时它还会再取一次缓冲并再问一次"有没有新样本"——
        // 那一趟传 0 字节（计数已经走到位），所以不会重复上传。
        SeriesBuffer buffer = c.bufferFor(series);
        buffer.uploadNewSamples();

        double windowStart = axes[0].windowMin();
        double windowEnd = axes[0].windowMax();
        List<WindowRange.Segment> segments = WindowRange.compute(
                windowStart, windowEnd, buffer.writeCount(), buffer.capacity());

        // 走不走曲线由缓冲的布局决定（理由见 LineSeriesRenderer 里的同一段说明）。
        boolean smooth = buffer.smoothLayout();
        float pxPerSample = (float) (plotWidth(c) / (windowEnd - windowStart));
        int subdivision = smooth ? SmoothCurve.subdivisionFor(pxPerSample) : 0;
        int firstVertex = smooth ? SmoothCurve.firstVertex(subdivision) : 0;
        int vertexCount = smooth ? SmoothCurve.vertexCount(subdivision) : PLAIN_VERTICES;

        if (!segments.isEmpty()) {
            ChartRenderLayout layout = c.layout();
            Rect plot = layout.plotRect();

            boolean scissorWasOn = gl.isScissorEnabled();
            gl.setScissorEnabled(true);
            setScissorTo(plot, c.viewportHeight());

            gl.enableBlend();
            // 必须在 enableBlend() 之后：它会顺手把混合因子设成非预乘的那一组。
            gl.setBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

            gl.bindVao(vao);
            configureDataAttributes(buffer.vboId(), buffer.layout().byteOffsetOfSlot(0));

            ShaderProgram shader = c.areaShader();
            shader.use();
            setCommonUniforms(shader, c, layout, plot, series, windowStart, windowEnd, 0);
            // 系列是否开 AA 由调用方决定（Gc.antialias 透传下来）。
            // 这里用 uniform 是安全的：一个系列一条 draw call，不涉及 VertexWriter 的合批。
            //
            // ★ 它只作用于**填充**那一趟：下面那条轮廓线走折线路径，由
            // LineSeriesRenderer 自己设同一个 uniform（两处都是同一个 Gc.antialias，
            // 所以一条面积图的两半不会一个开一个关）。这一行漏掉的表现是
            // "填充的上下边缘是硬的、轮廓线是羽化的"——而那看起来像线比填充清楚一点。
            shader.setUniform("uAntialias", c.antialias() ? 1f : 0f);
            shader.setUniform("uSmooth", smooth ? 1f : 0f);

            double windowFloor = Math.floor(windowStart);
            for (WindowRange.Segment seg : segments) {
                setSmoothRange(shader, buffer, seg, smooth);
                shader.setUniform("uFirstRelIndex",
                        (float) (seg.firstDataIndex() - windowFloor - (windowStart - windowFloor)));
                gl.drawArraysInstancedBaseInstance(
                        GL_TRIANGLE_STRIP, firstVertex, vertexCount,
                        seg.instanceCount(), seg.firstInstance());
            }
            shader.unuse();

            // ID pass：填充自己也是一块可点中的区域（面积图"点在填充里"与"点在线上"
            // 都该命中同一个系列）。容差对填充没有意义（它本来就有一片面积），传 0。
            //
            // uSmooth 与 uSmoothFrom/To 在这里与绘制那一趟一致，所以填充的热区也跟着
            // 曲线的顶边走——"点到的地方"和"看到的地方"必须是同一处。
            int pickId = c.pickId();
            if (pickId != 0) {
                ShaderProgram pick = c.areaPickShader();
                pick.use();
                setCommonUniforms(pick, c, layout, plot, series, windowStart, windowEnd, pickId);
                pick.setUniform("uSmooth", smooth ? 1f : 0f);
                setScissorTo(plot, c.viewportHeight());
                c.withPickPass(() -> {
                    for (WindowRange.Segment seg : segments) {
                        setSmoothRange(pick, buffer, seg, smooth);
                        pick.setUniform("uFirstRelIndex",
                                (float) (seg.firstDataIndex() - windowFloor
                                        - (windowStart - windowFloor)));
                        gl.drawArraysInstancedBaseInstance(
                                GL_TRIANGLE_STRIP, firstVertex, vertexCount,
                                seg.instanceCount(), seg.firstInstance());
                    }
                });
                pick.unuse();
            }

            gl.bindVao(0);
            gl.disableBlend();
            gl.setScissorEnabled(scissorWasOn);
        }

        // 轮廓线：一条普通折线，走折线路径自己的状态管理与两个 pass。
        // 它必须排在填充之后——压在上面的那条线才是实心的（见类文档）。
        // ★ 它也会**跟着同一个开关**平滑：填充的顶边与这条轮廓线必须是同一条曲线，
        //   否则沿曲线会露出一条背景色的细缝（两边各自用 SmoothCurve 的同一份算术与
        //   同一个细分档位，所以它们逐像素重合）。
        lineRenderer.renderPolyline(ctx, data, series, axes);
    }

    /** 绘图区宽度：{@code uPxPerSample} 的分子（与折线路径同一个口径）。 */
    private static float plotWidth(GLRenderContext c) {
        return c.layout().plotRect().width;
    }

    /**
     * 给一段实例设置"哪几段可以画成曲线"（与折线路径逐字相同的两个 uniform）。
     *
     * <p>不开平滑时两个都传 0（一段都不许平滑），理由见
     * {@code LineSeriesRenderer.setSmoothRange}。
     */
    private static void setSmoothRange(ShaderProgram shader, SeriesBuffer buffer,
                                       WindowRange.Segment seg, boolean smooth) {
        if (!smooth) {
            shader.setUniform("uSmoothFrom", 0f);
            shader.setUniform("uSmoothTo", 0f);
            return;
        }
        shader.setUniform("uSmoothFrom", SmoothCurve.smoothFromInSegment(
                seg.firstDataIndex(), seg.instanceCount(), buffer.smoothableFirst()));
        shader.setUniform("uSmoothTo", SmoothCurve.smoothToInSegment(
                seg.firstDataIndex(), seg.instanceCount(), buffer.smoothableEnd()));
    }

    /**
     * 填充两个 pass 共用的那一套 uniform。
     *
     * @param pickId 绘制时传 0（拾取着色器里 uColor 被优化掉，但仍要对齐 uniform 名单）
     */
    private static void setCommonUniforms(ShaderProgram shader, GLRenderContext c,
                                          ChartRenderLayout layout, Rect plot, Series series,
                                          double windowStart, double windowEnd, int pickId) {
        shader.setUniform("uPlotRect", plot.x, plot.y, plot.width, plot.height);
        shader.setUniform("uViewport", (float) c.viewportWidth(), (float) c.viewportHeight());
        shader.setUniform("uValueRange", layout.yMin(), layout.yMax());
        shader.setUniform("uPxPerSample",
                (float) (plot.width / (windowEnd - windowStart)));
        // 下沿是一个**数值**（默认 0），不是绘图区下边缘——写成下边缘的话，
        // 轴一放大柱子/填充的高度就会跟着轴走，而画面完全正常。
        shader.setUniform("uBaseline", series.baseline());
        int argb = fillColor(series);
        shader.setUniform("uColor",
                ((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f,
                ((argb >>> 24) & 0xFF) / 255f);
        // 必须是 int 的那个 setUniform（glUniform1i），理由见 SeriesShaders。
        shader.setUniform("uPickId", pickId);
    }

    /**
     * 本渲染器只画它真正画得出来的图型，其余明确报错。
     *
     * <p><strong>只接受 {@link ChartType#AREA}。</strong>不能写成"只要
     * {@code fillsUnderCurve()} 就放行"，也不能接受折线：本渲染器会把折线的
     * <b>下半部分整块涂满</b>——一条曲线变成一片色块，而画面看起来"就是这种风格"。
     */
    private static void requireSupported(ChartType type) {
        if (type == ChartType.AREA) {
            return;
        }
        throw new IllegalArgumentException(
                "AreaSeriesRenderer 不支持图型 " + type + "。明确报错而不是静默画错："
                        + "把折线按面积画，曲线下方会被涂满，那条线的形状也就无从读起；"
                        + "把散点按面积画，点全都淹在填充里。");
    }

    /**
     * 把裁剪盒设到绘图区（GL 侧的原点在左下、y 向上，所以要翻一次，
     * 理由见 {@code LineSeriesRenderer} 的同一方法）。
     */
    private static void setScissorTo(Rect plot, int viewportHeight) {
        int y = viewportHeight - (int) plot.y - (int) plot.height;
        glScissor((int) plot.x, y, (int) plot.width, (int) plot.height);
    }

    /** 释放本类持有的 GL 资源（VAO 与角点 VBO）；折线渲染器是共享的，不由本类释放。 */
    void dispose() {
        gl.deleteVbo(cornerVbo);
        gl.deleteVao(vao);
    }
}
