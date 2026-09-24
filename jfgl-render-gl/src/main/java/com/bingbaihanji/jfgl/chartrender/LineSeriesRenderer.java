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
 * 折线族渲染器：{@link ChartType#LINE} 与 {@link ChartType#LINE_AND_MARKERS}。
 *
 * <p>每个线段一个实例，几何在顶点着色器里生成。CPU 每帧只做两件事：
 * 把新增的点传上去、算一下可见窗口对应的实例区间。
 *
 * <h2>每个点只存一次，却让每个实例拿到两端</h2>
 * <p>数据侧的两个实例属性是<b>同一个 VBO、偏移差 4 个字节</b>（见
 * {@link #configureDataAttributes}）：偏移 0 拿 {@code y[k]}、偏移 4 拿 {@code y[k+1]}。
 * 于是每个样本只占 4 字节，而"线段的两端"这件事完全由属性的偏移表达，
 * 不需要在缓冲里把每个点写两遍。
 *
 * <h2>它画两个 pass：颜色的，和 ID 的</h2>
 * <p>颜色画完之后就着同一份 VAO 与同一批实例再画一遍 ID pass，只换程序
 * （{@code SeriesShaders.LINE_VERTEX} 两处共用）。ID 走 {@code uPickId} 这个
 * <b>int</b> uniform，按<b>系列</b>发号——发号成本与点数无关。
 *
 * <p>ID pass 把线撑粗到 {@link #PICK_TOLERANCE_PX}：画的线只有 1~2px，
 * 要求用户精确点中不合理。它只影响 ID pass，不影响画面。
 *
 * <h2>GL 状态由它自己负责，因为它不在 {@code RenderBatch.submit} 里</h2>
 * <p>它是在批处理之外<b>当场就画</b>的（见 {@code Gc.flush} 的说明），因此
 * {@code submit} 里那套"进中性状态、出来还原"的收尾它得自己做：
 * <ul>
 *   <li><b>裁剪</b>：{@code glScissor} 只在 {@code GL_SCISSOR_TEST} 启用时生效，
 *       而 submit 结束时是关着的。本类自己按进入时的状态启用/还原。
 *       <b>ID pass 用的也是这个裁剪盒</b>（{@code withPickPass} 的契约要求
 *       {@code GL_SCISSOR_TEST} 已启用），于是被裁掉的部分不可拾取，与画面一致。</li>
 *   <li><b>混合</b>：片段着色器输出的是<b>预乘色</b>，因此混合因子必须是
 *       {@code GL_ONE / GL_ONE_MINUS_SRC_ALPHA}。不启用混合的话，
 *       半透明系列会直接拿 {@code rgb * a} 覆盖掉背景——压在网格上的那一条
 *       会比预期暗一大截，而画面看起来"只是颜色有点深"。</li>
 *   <li><b>VAO 与着色器</b>：用完解绑。</li>
 * </ul>
 */
final class LineSeriesRenderer implements SeriesRenderer {

    /** 单位四边形的四个角，按 triangle strip 顺序。divisor = 0，所有实例共享。 */
    private static final float[] CORNERS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
    };

    /**
     * 拾取容差（半宽，设备像素）。
     *
     * <p>画出来的线只有 1~2px 宽，要求用户精确点中是不合理的。
     * <strong>这是刻意行为，不是 bug</strong>——与"全透明图元仍可拾取"
     * "文本的可拾取范围比墨迹大一圈"同类，有测试钉着。
     * 它只影响 ID pass，<strong>不影响画面</strong>。
     *
     * <p>顶点着色器里的 {@code max(uHalfWidth, uPickTolerance)} 让两个 pass 共用一个
     * 顶点程序：绘制时传 0，取到的就是真实线宽；拾取时传它，线被撑粗成一条"热区"。
     */
    private static final float PICK_TOLERANCE_PX = 4f;

    private final GLAbstraction gl;

    private final int vao;

    private final int cornerVbo;

    LineSeriesRenderer(GLAbstraction gl) {
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

        // location 0：单位四边形，每顶点取一次
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        gl.setVertexAttribDivisor(0, 0);

        gl.bindVbo(0);
        gl.bindVao(0);
    }

    /**
     * 配置数据侧的属性指针。
     *
     * <p><strong>同一个 VBO 绑两次、只差 4 个字节的偏移</strong>——这是"每点只存一次
     * 却能让每个实例拿到两端"的关键。偏移 0 拿 {@code y[k]}，偏移 4 拿 {@code y[k+1]}。
     *
     * <p>步长是 {@code 4} 而不是 {@code 8}：两个属性各自是"每实例一个 float"，
     * 由 {@code baseInstance}（见 {@link GLAbstraction#drawArraysInstancedBaseInstance}）
     * 把它们挪到环里正确的那一段上。写成 {@code 8} 会让相邻实例间隔一个样本，
     * 画出来的波形<b>正好少一半的点</b>，而线条看起来仍然连贯——最难查的那种。
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
        ChartType type = series.type();
        requireSupported(type);

        // 缓冲归 ChartRenderer 管，渲染器自己不持有状态
        // （SeriesRenderer 的类文档要求它是纯函数）。
        SeriesBuffer buffer = c.bufferFor(series);
        buffer.uploadNewSamples();

        // 可见窗口就是 x 轴的窗口（x 轴的单位是数据下标）。
        double windowStart = axes[0].windowMin();
        double windowEnd = axes[0].windowMax();
        // 写指针取 buffer.writeCount()（已上传数），**不是** data.itemCount()：
        // 后者在环写满之后停在容量上不动，拿它当写指针会让画面定格在第一屏。
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
        // enableBlend() 会顺手把混合因子设成**非预乘**的那一组，所以这一行必须在它之后。
        // 与 RenderBatch.submit 里那两行是同一个理由、同一个顺序：那边混合的是 CPU 侧
        // 预乘好的顶点色，这边混合的是片段着色器当场预乘的常量色，约定一致。
        gl.setBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        gl.bindVao(vao);
        configureDataAttributes(buffer.vboId());

        ShaderProgram shader = c.lineShader();
        shader.use();
        shader.setUniform("uPlotRect", plot.x, plot.y, plot.width, plot.height);
        shader.setUniform("uViewport", (float) c.viewportWidth(), (float) c.viewportHeight());
        shader.setUniform("uValueRange", layout.yMin(), layout.yMax());
        shader.setUniform("uPxPerSample",
                (float) (plot.width / (windowEnd - windowStart)));
        shader.setUniform("uHalfWidth", series.lineWidth() * 0.5f);
        // 绘制时容差为 0，所以 max() 取到的就是真实线宽
        shader.setUniform("uPickTolerance", 0f);
        // Series.color() 返回 ARGB 整数，按 0xAARRGGBB 拆分量。
        int argb = series.color();
        shader.setUniform("uColor",
                ((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f,
                ((argb >>> 24) & 0xFF) / 255f);
        shader.setUniform("uPickId", 0);

        // 横轴的锚点。绝对下标是 long、着色器里用不了，所以传"本段第一个实例相对
        // 可见窗口左端的小数偏移"；窗口左端不是整数时（亚像素滚动）这个小数的整数部分
        // 不能丢，故减去 floor 再减窗口左端的小数部分。
        double windowFloor = Math.floor(windowStart);
        for (WindowRange.Segment seg : segments) {
            shader.setUniform("uFirstRelIndex",
                    (float) (seg.firstDataIndex() - windowFloor - (windowStart - windowFloor)));
            gl.drawArraysInstancedBaseInstance(
                    GL_TRIANGLE_STRIP, 0, 4, seg.instanceCount(), seg.firstInstance());
        }

        shader.unuse();

        // ID pass：同一份 VAO、同一批实例，只换程序。
        // 拾取 ID 走 uniform 而不是顶点属性——图表的数据布局里没有 id 字段，
        // 而且按系列发号意味着发号成本与点数无关（一条百万点的曲线只注册一个 ID）。
        //
        // 插在 shader.unuse() 与 bindVao(0) 之间是有意的：此刻 VAO 与两个实例属性指针
        // 都还是绘制时那套，只换程序就够；搬到 bindVao(0) 之后就得把 configureDataAttributes
        // 再走一遍，那两份配置迟早会分叉，而分叉的表现是"拾取的位置和画面不一致"。
        int pickId = c.pickId();
        if (pickId != 0) {
            ShaderProgram pick = c.pickShader();
            pick.use();
            // 这一套 uniform 必须与上面的绘制循环**逐个对齐**：少设任何一个，
            // 拾取的位置就和画面不一致——那是"点到的地方不是看到的地方"，
            // 正是本项目最怕的静默缺陷。uColor 不设：拾取着色器里它被优化掉，
            // 设了是静默的无操作，不设更诚实。
            pick.setUniform("uPlotRect", plot.x, plot.y, plot.width, plot.height);
            pick.setUniform("uViewport", (float) c.viewportWidth(), (float) c.viewportHeight());
            pick.setUniform("uValueRange", layout.yMin(), layout.yMax());
            pick.setUniform("uPxPerSample", (float) (plot.width / (windowEnd - windowStart)));
            pick.setUniform("uHalfWidth", series.lineWidth() * 0.5f);
            // 容差：绘制时是 0，拾取时放宽（理由见 PICK_TOLERANCE_PX）。
            pick.setUniform("uPickTolerance", PICK_TOLERANCE_PX);
            // 必须是 int 的那个 setUniform（glUniform1i）：对 uint uniform 用它报
            // GL_INVALID_OPERATION 且**值保持 0**，而 0 正是"什么都没命中"。
            pick.setUniform("uPickId", pickId);

            // 裁剪盒在进入本方法时就设好了（见上面那三行），到这里还没还原，
            // 所以 withPickPass 那条"调用方必须确保 GL_SCISSOR_TEST 已启用"的契约天然满足，
            // 被裁掉的部分因此不可拾取，与画面一致。
            //
            // 这一次显式的 setScissorTo 是**冗余**的（绘制路径刚设过同一个矩形，
            // 实测把它删掉一条断言都不会变），留着是因为契约要求 ID pass 自己确保 scissor：
            // 一个显式调用比"依赖上面那一行还生效"更难被后人改坏。
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
     * 本渲染器只画它真正画得出来的图型，其余明确报错。
     *
     * <p><strong>只接受 {@link ChartType#LINE} 与 {@link ChartType#LINE_AND_MARKERS}。</strong>
     * 不能写成"只要 {@code connectsSamples()} 或 {@code drawsMarkers()} 就放行"：
     * {@link ChartType#STEP}（先横后竖）与 {@link ChartType#AREA}（线下填充）同样
     * 落在那两个判据里，而本渲染器会把它们画成<b>普通折线</b>——
     * 阶梯图形被拉成斜线、面积图整个填充消失，画面却完全正常。
     * 这与"按线性去画对数轴"是同一种错误：<b>形状是错的，而不报错</b>。
     * 本期这两者都还没有渲染器（见计划 Task 15 的"未实现"清单）。
     *
     * <p>同理，{@link ChartType#LINE_AND_MARKERS} 的标记点由散点渲染器负责（Task 14），
     * 本期只画它的折线部分——这一点是<b>写在文档里的已知缺口</b>，不是"顺手少画一半"。
     */
    private static void requireSupported(ChartType type) {
        if (type == ChartType.LINE || type == ChartType.LINE_AND_MARKERS) {
            return;
        }
        throw new IllegalArgumentException(
                "LineSeriesRenderer 不支持图型 " + type + "。明确报错而不是静默不画/画错："
                        + "画面里少一条曲线，与\"这条曲线没数据\"在视觉上完全一样；"
                        + "而把一个阶梯图或面积图画成普通折线更糟——形状是错的，画面却正常。");
    }

    /**
     * 把裁剪盒设到绘图区。
     *
     * <p>{@code glScissor} 的原点在帧缓冲<b>左下角</b>、y 向上，而本管线的用户空间是
     * "像素、原点左上、y 向下"，{@code plot} 记的是矩形<strong>上边缘</strong>。
     * 因此 GL 侧的下边 = {@code viewportHeight - plot.y - plot.height}。
     * 漏掉这一步的后果是裁剪区上下镜像——数据在绘图区下半部分被裁掉、上半部分却画到
     * 了绘图区外面，而顶点本身是对的。
     */
    private static void setScissorTo(Rect plot, int viewportHeight) {
        int y = viewportHeight - (int) plot.y - (int) plot.height;
        glScissor((int) plot.x, y, (int) plot.width, (int) plot.height);
    }

    /** 释放本类持有的 GL 资源（VAO 与单位四边形 VBO）。 */
    void dispose() {
        gl.deleteVbo(cornerVbo);
        gl.deleteVao(vao);
    }
}
