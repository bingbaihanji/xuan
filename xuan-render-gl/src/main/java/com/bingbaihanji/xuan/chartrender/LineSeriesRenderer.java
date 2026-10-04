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

import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;

/**
 * 折线族渲染器：{@link ChartType#LINE} 与 {@link ChartType#LINE_AND_MARKERS}。
 *
 * <p>每个线段一个实例，几何在顶点着色器里生成。CPU 每帧只做两件事：
 * 把新增的点传上去、算一下可见窗口对应的实例区间。
 *
 * <h2>每个点只存一次，却让每个实例拿到两端</h2>
 * <p>数据侧的实例属性是<b>同一个 VBO、偏移差 4 个字节</b>（见
 * {@link #configureDataAttributes}）：偏移 0 拿 {@code y[k]}、偏移 4 拿 {@code y[k+1]}。
 * 于是每个样本只占 4 字节，而"线段的两端"这件事完全由属性的偏移表达，
 * 不需要在缓冲里把每个点写两遍。
 *
 * <h2>★ 平滑曲线（{@code Series.smooth()}）：多两个属性、多两个 uniform</h2>
 * <p>Catmull-Rom 的一段需要 <b>四个</b>控制点 {@code y[k-1], y[k], y[k+1], y[k+2]}，
 * 于是属性多两个：{@code aYm1}（比 {@code aY0} 少 4 字节）与 {@code aY2}
 * （比 {@code aY1} 多 4 字节）。<b>每个样本仍然只存一次</b>——四个属性是同一块 VBO 上
 * 四个相差 4 字节的字节偏移，{@code baseInstance} 照样把它们滑到环里正确的那一段。
 * 真正需要动的是缓冲本身：{@code y[k-1]} 要求"槽位 0 前面还有一个 float"，
 * 而负偏移在 OpenGL 里不存在（见 {@link SeriesLayout}）。
 *
 * <p>顶点数也从 4 变成 {@code 2 × (K+1)}（K = 2/4/8/16，见 {@link SmoothCurve}），
 * {@code aCorner.x} 从"哪一端"变成"站位参数"。非平滑时仍然是 4 个顶点、
 * 参数只取 0 与 1——<b>几何逐位不变</b>。
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
 *
 * <h2>{@link ChartType#LINE_AND_MARKERS} 的标记点那一半</h2>
 * <p>它<b>不再是缺口</b>：本类持有一个 {@link ScatterSeriesRenderer}
 * （由 {@code ChartRenderer} 注入），折线画完之后调它的
 * {@link ScatterSeriesRenderer#renderMarkers} 把标记点画上去。
 *
 * <p><b>复用而不是各写一份</b>：标记点的几何（居中四边形、{@code markerSize} 半径→边长
 * 的换算、{@code max(uMarkerSize, uPickTolerance*2)} 的退化处置、拾取热区）
 * 与散点渲染器<b>逐项相同</b>，而这些恰恰是"画面上看不出来"的那一类缺陷
 * （热区比标记小一半、标记比声明的小一半都只是"看起来有点小"）。
 * 抄一份的话两处迟早分叉；而"哪一半归谁"这件事仍然只有 {@code rendererFor} 一处判断。
 *
 * <p>调的是 {@link ScatterSeriesRenderer#renderMarkers}（无守卫版本），
 * 不是它的 {@code render}——那个方法会<b>正确地</b>拒绝 {@code LINE_AND_MARKERS}
 * （理由见 {@link ScatterSeriesRenderer#requireSupported}），
 * 与面积图渲染器调 {@link #renderPolyline} 是同一个道理。
 */
final class LineSeriesRenderer implements SeriesRenderer {

    /**
     * 非平滑的四角，按 triangle strip 顺序：{@code (哪一端, 哪一侧)}。
     * divisor = 0，所有实例共享。
     *
     * <p>它的后面还接着四档"平滑站位"（见 {@link SmoothCurve#cornerData}）：
     * 开平滑时 {@code aCorner.x} 不再是 0/1，而是<b>站位参数 t</b>。
     */
    private static final float[] CORNERS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
    };

    /** 非平滑时的顶点数：那一个四边形。 */
    private static final int PLAIN_VERTICES = CORNERS.length / 2;

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

    /** 画 {@code LINE_AND_MARKERS} 标记点那一半的渲染器（不持有数据，全局共享）。 */
    private final ScatterSeriesRenderer markerRenderer;

    private final int vao;

    private final int cornerVbo;

    /**
     * @param markerRenderer 标记点那一半的绘制入口；由 {@code ChartRenderer} 注入
     *                       （它必须与 {@code ChartRenderer} 自己用的那一个是同一个实例，
     *                       否则两处会各自建一套 VAO 与着色器，画的还是同一批像素）
     */
    LineSeriesRenderer(GLAbstraction gl, ScatterSeriesRenderer markerRenderer) {
        this.gl = gl;
        this.markerRenderer = markerRenderer;
        this.vao = gl.createVao();
        this.cornerVbo = SmoothCurve.createCornerVbo(gl, CORNERS);

        gl.bindVao(vao);
        gl.bindVbo(cornerVbo);
        // location 0：角点，每顶点取一次。它的前半段是那一个四边形，
        // 后半段是四档平滑站位（见 SmoothCurve）——每帧用 (first, count) 选用哪一段。
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        gl.setVertexAttribDivisor(0, 0);

        gl.bindVbo(0);
        gl.bindVao(0);
    }

    /**
     * 给一段实例设置"哪几段可以画成曲线"（{@code uSmoothFrom} / {@code uSmoothTo}）。
     *
     * <p>区间是<b>本段内的实例下标</b>，不是数据下标——理由见
     * {@link SmoothCurve#smoothFromInSegment}（绝对下标进着色器只能走 float，
     * 而 1e6 上的 float 精度足以让边界附近的一条实例读到一个陈旧的控制点）。
     *
     * <p>不开平滑时两个都传 0（"一段都不许平滑"）——那是个真话，
     * 也免得让人以为这两个值在那种情况下还参与判定。
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
     * <p>{@link ChartType#LINE_AND_MARKERS} <b>整个都归本渲染器</b>（折线 + 标记点，
     * 后半由 {@link ScatterSeriesRenderer#renderMarkers} 画，见类文档）——
     * 这条不再是缺口，所以那个图型列在白名单里是对的。
     * 光看 {@code drawsMarkers()} 为真就把它路由到散点渲染器仍是错的：
     * 那样折线整条消失、只剩一串点，而"只有点"看起来像一种刻意的风格。
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

    /**
     * 配置数据侧的属性指针（四个控制点：{@code aY0 / aY1 / aYm1 / aY2}）。
     *
     * <p><strong>同一个 VBO 绑四次、两两相差 4 个字节</strong>——这是"每点只存一次
     * 却能让每个实例拿到四个控制点"的关键。偏移怎么由布局算出来、普通布局为什么
     * 有两处指向同一个样本，都在 {@link SmoothCurve#configureDataAttributes} 里
     * （折线族与面积图共用同一份，免得两处迟早分叉）。
     *
     * <p>调用方必须已经绑定本类的 VAO 与系列的 VBO。
     */
    private void configureDataAttributes(int seriesVbo, int biasBytes) {
        SmoothCurve.configureDataAttributes(gl, seriesVbo, biasBytes);
    }

    @Override
    public void render(RenderContext ctx, ChartData data, Series series, Axis[] axes) {
        // 守卫留在入口：**哪些图型归哪个渲染器**只有这一处判断（外加 ChartRenderer 的查表，
        // 那边决定"谁来画"，这里决定"画不画得了"）。
        requireSupported(series.type());
        renderPolyline(ctx, data, series, axes);
        // 标记点画在折线**之上**（后画的在上）。顺序反过来倒也不会看不见，
        // 但"点被线压在下面"是让人以为标记没画的那种画面，没有必要。
        //
        // 只有 LINE_AND_MARKERS 有这一半：把标记点也画给 LINE，是**无声地多画了东西**，
        // 而 ChartType 那边 LINE 与 LINE_AND_MARKERS 是两个不同的图型，用户的选择必须被尊重。
        if (series.type() == ChartType.LINE_AND_MARKERS) {
            markerRenderer.renderMarkers(ctx, data, series, axes);
        }
    }

    /**
     * 把数据画成一条有粗细的折线，<b>不做图型守卫</b>。
     *
     * <p>它是"折线"这个几何本身：{@link #render} = 守卫 + 它，
     * 而面积图渲染器用它来画面积图的轮廓线（那条线确实是一条普通折线，
     * 值得一份代码画两遍）。面积图<b>不能</b>改调 {@link #render}：那个方法会
     * 正确地拒绝 {@code AREA}（拒绝的理由见 {@link #requireSupported}），
     * 而那条守卫不该为了复用而拆掉——拆掉之后"面积图交给折线渲染器"就会静默地
     * 少画一整块填充。
     *
     * <p>调用方必须保证"这个图型用折线几何画是对的"（目前只有
     * {@code LINE} / {@code LINE_AND_MARKERS} / {@code AREA} 的轮廓线三种情况）。
     *
     * <p><b>它不画标记点。</b>{@code LINE_AND_MARKERS} 的标记点由 {@link #render} 在
     * 折线之后补上——走 {@link #renderPolyline} 的那几个调用方（面积图的轮廓线）
     * 要的就是一条纯折线。
     */
    void renderPolyline(RenderContext ctx, ChartData data, Series series, Axis[] axes) {
        GLRenderContext c = (GLRenderContext) ctx;

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
        float pxPerSample = (float) (plot.width / (windowEnd - windowStart));
        // 走不走曲线由**缓冲的布局**决定，而不是再去问一遍 series.smooth()：
        // 布局决定了四个实例属性的字节偏移，两者必须是同一处决定（见 SeriesLayout）。
        // ChartRenderer 保证布局与 Series.smooth() 一致（不一致就重建缓冲）。
        boolean smooth = buffer.smoothLayout();
        int subdivision = smooth ? SmoothCurve.subdivisionFor(pxPerSample) : 0;
        // 开平滑时画的是"四档站位"里被选中的那一档；否则画那一个四边形（顶点数与偏移都
        // 与改动前逐字相同）。
        int firstVertex = smooth ? SmoothCurve.firstVertex(subdivision) : 0;
        int vertexCount = smooth ? SmoothCurve.vertexCount(subdivision) : PLAIN_VERTICES;

        boolean scissorWasOn = gl.isScissorEnabled();
        gl.setScissorEnabled(true);
        setScissorTo(plot, c.viewportHeight());

        gl.enableBlend();
        // enableBlend() 会顺手把混合因子设成**非预乘**的那一组，所以这一行必须在它之后。
        // 与 RenderBatch.submit 里那两行是同一个理由、同一个顺序：那边混合的是 CPU 侧
        // 预乘好的顶点色，这边混合的是片段着色器当场预乘的常量色，约定一致。
        gl.setBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        gl.bindVao(vao);
        configureDataAttributes(buffer.vboId(), buffer.layout().byteOffsetOfSlot(0));

        ShaderProgram shader = c.lineShader();
        shader.use();
        shader.setUniform("uPlotRect", plot.x, plot.y, plot.width, plot.height);
        shader.setUniform("uViewport", (float) c.viewportWidth(), (float) c.viewportHeight());
        shader.setUniform("uValueRange", layout.yMin(), layout.yMax());
        shader.setUniform("uPxPerSample", pxPerSample);
        shader.setUniform("uHalfWidth", series.lineWidth() * 0.5f);
        // 绘制时容差为 0，所以 max() 取到的就是真实线宽
        shader.setUniform("uPickTolerance", 0f);
        // 系列是否开 AA 由调用方决定（Gc.antialias 透传下来）。
        // 这里用 uniform 是安全的：一个系列一条 draw call，不涉及 VertexWriter 的合批。
        // 只设在**绘制**这一趟：ID pass 的片段程序里没有 uAntialias（ID 不能有半透明）。
        shader.setUniform("uAntialias", c.antialias() ? 1f : 0f);
        // Series.color() 返回 ARGB 整数，按 0xAARRGGBB 拆分量。
        int argb = series.color();
        shader.setUniform("uColor",
                ((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f,
                ((argb >>> 24) & 0xFF) / 255f);
        shader.setUniform("uPickId", 0);
        shader.setUniform("uSmooth", smooth ? 1f : 0f);

        // 横轴的锚点。绝对下标是 long、着色器里用不了，所以传"本段第一个实例相对
        // 可见窗口左端的小数偏移"；窗口左端不是整数时（亚像素滚动）这个小数的整数部分
        // 不能丢，故减去 floor 再减窗口左端的小数部分。
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

        // ID pass：同一份 VAO、同一批实例，只换程序。
        // 拾取 ID 走 uniform 而不是顶点属性——图表的数据布局里没有 id 字段，
        // 而且按系列发号意味着发号成本与点数无关（一条百万点的曲线只注册一个 ID）。
        //
        // 插在 shader.unuse() 与 bindVao(0) 之间是有意的：此刻 VAO 与四个实例属性指针
        // 都还是绘制时那套，只换程序就够；搬到 bindVao(0) 之后就得把 configureDataAttributes
        // 再走一遍，那两份配置迟早会分叉，而分叉的表现是"拾取的位置和画面不一致"。
        //
        // ★ **热区跟着曲线走**：ID pass 用的是同一个顶点程序、同一批 uSmooth 与
        // uSmoothFrom/To，所以开了平滑之后可拾取的位置就是那条曲线——
        // 这正是想要的（"点到的地方"和"看到的地方"必须是同一处）。
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
            pick.setUniform("uPxPerSample", pxPerSample);
            pick.setUniform("uHalfWidth", series.lineWidth() * 0.5f);
            // 容差：绘制时是 0，拾取时放宽（理由见 PICK_TOLERANCE_PX）。
            pick.setUniform("uPickTolerance", PICK_TOLERANCE_PX);
            // 必须是 int 的那个 setUniform（glUniform1i）：对 uint uniform 用它报
            // GL_INVALID_OPERATION 且**值保持 0**，而 0 正是"什么都没命中"。
            pick.setUniform("uPickId", pickId);
            pick.setUniform("uSmooth", smooth ? 1f : 0f);

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
                    setSmoothRange(pick, buffer, seg, smooth);
                    pick.setUniform("uFirstRelIndex",
                            (float) (seg.firstDataIndex() - windowFloor - (windowStart - windowFloor)));
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

    /** 释放本类持有的 GL 资源（VAO 与单位四边形 VBO）。 */
    void dispose() {
        gl.deleteVbo(cornerVbo);
        gl.deleteVao(vao);
    }
}
