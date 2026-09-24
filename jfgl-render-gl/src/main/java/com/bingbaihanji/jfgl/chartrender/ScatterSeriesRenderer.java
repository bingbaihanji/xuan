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
 * 散点渲染器：{@link ChartType#SCATTER}。
 *
 * <p>结构与 {@link LineSeriesRenderer} 一致（构造时建 VAO + 单位四边形 VBO、
 * 每帧当场发 instanced draw call、颜色与 ID 两个 pass），
 * <b>只有三处必须不同</b>：
 *
 * <ol>
 *   <li><b>实例数是"点数"而不是"线段数"</b>——用
 *       {@link WindowRange#computePoints} 而不是 {@link WindowRange#compute}。
 *       折线的最后一个线段要右端已采到（{@code writeIndex - 2}），
 *       散点的每个点自己就是完整的（{@code writeIndex - 1}）。
 *       用错的表现是<b>最后一个点静默消失</b>（或反过来画出一个右端还没采到的点，
 *       那个槽位里是上一次绕环留下的旧值）。</li>
 *   <li><b>只配一个数据属性</b>：{@code aY}（location 1）。
 *       折线那种"同一个 VBO、偏移差 4 字节"的第二个属性这里没有——
 *       点是独立的，不需要第二端。多配一个属性的后果不是画面变坏，而是
 *       顶点着色器里那个 location 没人声明，属性被静默忽略。</li>
 *   <li><b>几何居中、用 {@code uMarkerSize}</b> 而不是 {@code uHalfWidth}。</li>
 * </ol>
 *
 * <h2>markerSize 退化（0 或负数）时不画任何东西</h2>
 * <p><b>处置方式与折线的零线宽完全一致：CPU 侧不分支，靠着色器里的
 * {@code max(uMarkerSize, uPickTolerance * 2.0)} 自然退化。</b>绘制时容差是 0，
 * 于是边长取到 {@code 2 × markerSize}——0 或负数让四个角重合成一个点、光栅化不出
 * 任何片段；拾取时容差非 0，热区照旧存在。
 *
 * <p>不在这里加一句 {@code if (markerSize <= 0) return;} 是有意的：
 * <ul>
 *   <li>那会让"零尺寸不画"这件事在<b>两条</b>代码路径上各有一份实现（CPU 一句、
 *       着色器一句），两份迟早分叉；</li>
 *   <li>更要紧的是它会<b>顺带关掉拾取</b>——而图表里"看不见的图元仍可拾取"
 *       是既有约定（与 {@code Gc} 那边"全透明图元仍可拾取"同源，
 *       {@code ChartVerifier} 有一条断言钉着：斜坡上压着的那条 {@code lineWidth = 0}
 *       的系列照样能被点到）。散点必须与折线表现一致。</li>
 * </ul>
 * <p>因此"零尺寸 = 一个像素都不画"这条由 {@code ChartVerifier} 用像素口径钉住
 * （与折线那条"线宽 0 的系列全画面一个像素都没有"是同一个做法）。
 *
 * <h2>{@code markerSize} 是<b>半径</b>，而 {@code uMarkerSize} 是<b>边长</b></h2>
 * <p>两者差一倍，换算发生在<b>这一处</b>（{@link #markerEdge}）：{@code chart/Series}
 * 声明的是半径（"标记点半径（用户坐标单位）"），而顶点着色器里那个 uniform 是
 * 四边形的边长——它只关心几何，不该知道"半径"这种语义。
 *
 * <p><strong>只有这一种解释，两边不许各持一半。</strong>渲染器不换算的话，
 * 用户设半径 5 拿到的是宽 5 的方块（本该宽 10）——<b>标记照画、位置准确、颜色也对，
 * 只是小了一半</b>，没有任何症状，用户只能靠"为什么我设 5 出来像 2.5"去猜。
 * 换算写成方法而不是在两个 setUniform 那里各写一次 {@code * 2f}：
 * 绘制与拾取**必须**用同一个尺寸，两处各写一遍迟早会分叉。
 *
 * <p>本期 {@code chart/} 不能改，所以"半径"这个语义由本类与
 * {@code ChartVerifier} 的期望值（点数 × (2 × 半径)²）共同钉住。
 *
 * <h2>它画两个 pass：颜色的，和 ID 的</h2>
 * <p>与折线同构：颜色画完之后就着同一份 VAO 与同一批实例再画一遍 ID pass，
 * 只换程序。ID 走 {@code uPickId} 这个 <b>int</b> uniform，按<b>系列</b>发号，
 * 发号成本与点数无关。绘制与拾取共用同一套 uniform、同一套实例区间——
 * 少设任何一个，拾取的位置就和画面不一致。
 *
 * <h2>GL 状态由它自己负责，因为它不在 {@code RenderBatch.submit} 里</h2>
 * <p>与 {@link LineSeriesRenderer} 同一份纪律：裁剪（{@code glScissor} 只在
 * {@code GL_SCISSOR_TEST} 启用时生效，而 submit 结束时是关着的）、混合
 * （片段着色器输出预乘色，因子必须是 {@code GL_ONE / GL_ONE_MINUS_SRC_ALPHA}）、
 * VAO 与着色器的解绑，全部由本类成对地做。<b>ID pass 用的也是同一个裁剪盒</b>，
 * 于是被裁掉的部分不可拾取，与画面一致。
 */
final class ScatterSeriesRenderer implements SeriesRenderer {

    /** 单位四边形的四个角，按 triangle strip 顺序。divisor = 0，所有实例共享。 */
    private static final float[] CORNERS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
    };

    /**
     * 拾取容差（<b>半宽</b>，设备像素）。
     *
     * <p>与 {@code LineSeriesRenderer.PICK_TOLERANCE_PX} 取同一个数：标记点只有几像素宽，
     * 要求用户精确点中不合理。<strong>这是刻意行为，不是 bug</strong>——
     * 与"全透明图元仍可拾取"同类，有测试钉着。它只影响 ID pass，<strong>不影响画面</strong>。
     *
     * <p>顶点着色器里的 {@code max(uMarkerSize, uPickTolerance * 2.0)} 让两个 pass 共用一个
     * 顶点程序：绘制时传 0，取到的就是真实边长；拾取时传它，标记被撑大成一个热区。
     */
    private static final float PICK_TOLERANCE_PX = 4f;

    private final GLAbstraction gl;

    private final int vao;

    private final int cornerVbo;

    ScatterSeriesRenderer(GLAbstraction gl) {
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
     * 系列的 {@code markerSize}（<b>半径</b>）→ 着色器的 {@code uMarkerSize}（<b>边长</b>）。
     *
     * <p>乘 2 就是这一处换算的全部内容，理由见类文档。绘制与拾取都走它——
     * 两处各写一遍 {@code * 2f} 的话，迟早有一处会漏（而"拾取的热区比标记小一半"
     * 是画面完全看不出来的那种缺陷）。
     */
    private static float markerEdge(Series series) {
        return series.markerSize() * 2f;
    }

    /**
     * 配置数据侧的属性指针。
     *
     * <p><b>只有一个属性</b>（location 1 = {@code aY}，步长 {@code 4} 字节，偏移 0）：
     * 一个实例就是一个点，点自己就是完整的。折线那边第二个属性
     * （{@code (2, 1, GL_FLOAT, false, Float.BYTES, Float.BYTES)}）在这里<b>必须不存在</b>——
     * 它是"线段两端"的表达方式，散点没有"第二端"。
     *
     * <p>步长同样是 {@code 4} 而不是 {@code 8}：由
     * {@code baseInstance}（见 {@link GLAbstraction#drawArraysInstancedBaseInstance}）
     * 把实例挪到环里正确的那一段上。写成 {@code 8} 会让相邻实例间隔一个样本，
     * 于是<b>只画出大约一半的点</b>——散点图看起来"就是稀一点"，最难查的那种。
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

        // 缓冲归 ChartRenderer 管，渲染器自己不持有状态
        // （SeriesRenderer 的类文档要求它是纯函数）。
        SeriesBuffer buffer = c.bufferFor(series);
        buffer.uploadNewSamples();

        // 可见窗口就是 x 轴的窗口（x 轴的单位是数据下标）。
        double windowStart = axes[0].windowMin();
        double windowEnd = axes[0].windowMax();
        // 写指针取 buffer.writeCount()（已上传数），不是 data.itemCount()——理由见 SeriesBuffer。
        //
        // **点数版本，不是线段版本**：这是本渲染器与折线渲染器唯一的数据侧差别
        // （见类文档第 1 条）。
        List<WindowRange.Segment> segments = WindowRange.computePoints(
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

        ShaderProgram shader = c.scatterShader();
        shader.use();
        shader.setUniform("uPlotRect", plot.x, plot.y, plot.width, plot.height);
        shader.setUniform("uViewport", (float) c.viewportWidth(), (float) c.viewportHeight());
        shader.setUniform("uValueRange", layout.yMin(), layout.yMax());
        shader.setUniform("uPxPerSample",
                (float) (plot.width / (windowEnd - windowStart)));
        // 半径 → 边长（见 markerEdge）。markerSize ≤ 0 时这里传下去的就是那个非正数，
        // 着色器里的 max() 会让四边形退化成一个点——不画任何像素（见类文档）。
        // CPU 侧不做分支。
        shader.setUniform("uMarkerSize", markerEdge(series));
        // 绘制时容差为 0，所以 max() 取到的就是真实边长
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
        // 而且按系列发号意味着发号成本与点数无关。
        //
        // 插在 shader.unuse() 与 bindVao(0) 之间是有意的（理由与折线渲染器那边相同）：
        // 此刻 VAO 与实例属性指针都还是绘制时那套，只换程序就够。
        int pickId = c.pickId();
        if (pickId != 0) {
            // **散点自己的一份拾取程序**（SCATTER_VERTEX + PICK_FRAGMENT），不能用折线那个：
            // 顶点程序决定实例的几何，用折线的顶点程序会画出一条"从数据值竖直拉到 0"的
            // 长条热区（第二个实例属性没人喂、恒为 0）——画面完全正常，只有点击落在错误的对象上。
            ShaderProgram pick = c.scatterPickShader();
            pick.use();
            // 这一套 uniform 必须与上面的绘制循环**逐个对齐**：少设任何一个，
            // 拾取的位置就和画面不一致——那是"点到的地方不是看到的地方"。
            // uColor 不设：拾取着色器里它被优化掉，设了是静默的无操作，不设更诚实。
            pick.setUniform("uPlotRect", plot.x, plot.y, plot.width, plot.height);
            pick.setUniform("uViewport", (float) c.viewportWidth(), (float) c.viewportHeight());
            pick.setUniform("uValueRange", layout.yMin(), layout.yMax());
            pick.setUniform("uPxPerSample", (float) (plot.width / (windowEnd - windowStart)));
            // 与绘制路径**同一个**尺寸（markerEdge），否则热区与标记会差一半。
            pick.setUniform("uMarkerSize", markerEdge(series));
            // 容差：绘制时是 0，拾取时放宽（理由见 PICK_TOLERANCE_PX）。
            pick.setUniform("uPickTolerance", PICK_TOLERANCE_PX);
            // 必须是 int 的那个 setUniform（glUniform1i）：对 uint uniform 用它报
            // GL_INVALID_OPERATION 且**值保持 0**，而 0 正是"什么都没命中"。
            pick.setUniform("uPickId", pickId);

            // 裁剪盒在进入本方法时就设好了，到这里还没还原，所以 withPickPass 那条
            // "调用方必须确保 GL_SCISSOR_TEST 已启用"的契约天然满足，
            // 被裁掉的部分因此不可拾取，与画面一致。
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
     * <p><strong>只接受 {@link ChartType#SCATTER}。</strong>不能写成"只要
     * {@code drawsMarkers()} 就放行"：{@link ChartType#LINE_AND_MARKERS} 同样
     * 落在那条判据里，而本渲染器会把它的<b>折线部分整个丢掉</b>——
     * 画面里只剩下一串孤立的点，看起来"就是一种散射图风格"，而不是"少了一半"。
     * 那半由 {@link LineSeriesRenderer} 负责（那里也写着同一个已知缺口）。
     *
     * <p>{@link ChartType#BAR} 也是"不连线"的，但它的顶点是矩形、不是居中的标记点，
     * 画出来会是一串方点而柱状图的高度含义整个消失。
     */
    private static void requireSupported(ChartType type) {
        if (type == ChartType.SCATTER) {
            return;
        }
        throw new IllegalArgumentException(
                "ScatterSeriesRenderer 不支持图型 " + type + "。明确报错而不是静默不画/画错："
                        + "LINE_AND_MARKERS 的标记点确实归散点渲染器，但它的折线不归——"
                        + "只画点会把折线整条丢掉，而\"只剩一串点\"看起来像刻意的风格；"
                        + "BAR 的顶点是矩形，当成标记点画会让柱高这个含义消失。");
    }

    /**
     * 把裁剪盒设到绘图区。
     *
     * <p>{@code glScissor} 的原点在帧缓冲<b>左下角</b>、y 向上，而本管线的用户空间是
     * "像素、原点左上、y 向下"，{@code plot} 记的是矩形<strong>上边缘</strong>。
     * 因此 GL 侧的下边 = {@code viewportHeight - plot.y - plot.height}。
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
