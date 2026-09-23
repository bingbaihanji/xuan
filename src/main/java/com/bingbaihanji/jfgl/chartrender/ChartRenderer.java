package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.Axis;
import com.bingbaihanji.jfgl.chart.Chart;
import com.bingbaihanji.jfgl.chart.ChartType;
import com.bingbaihanji.jfgl.chart.Layer;
import com.bingbaihanji.jfgl.chart.Series;
import com.bingbaihanji.jfgl.chart.SeriesRenderer;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;
import com.bingbaihanji.jfgl.renderer.PickRegistry;
import com.bingbaihanji.jfgl.util.Disposable;
import com.bingbaihanji.jfgl.util.Rect;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 图表绘制入口：把 {@code chart/} 的装配结果画成 GL 实例化绘制。
 *
 * <h2>它只做装配，不做几何</h2>
 * <p>每个系列交给对应的 {@link SeriesRenderer}，本类负责把"这个系列该用哪个渲染器"
 * 与"它的 GPU 缓冲在哪"接起来。渲染器本身是无状态的纯函数——缓冲由本类持有、
 * 每次渲染前注入（见 {@link GLRenderContextImpl} 的说明）。
 *
 * <h2>它在 {@code RenderBatch.submit} 之外，当场就画</h2>
 * <p>数据系列不是攒进顶点缓冲、等 {@code endFrame} 一次性提交的图元，而是当场发
 * instanced draw call。因此"网格 → 数据 → 标注"这种夹心 z 序要靠 {@code Gc.flush()}：
 *
 * <pre>
 *   画网格
 *   gc.flush()                          // 网格落定
 *   gc.charts.draw(chart, plotRect, w, h)
 *   画刻度文字
 * </pre>
 *
 * <h2>它必须挂在 GL 资源的释放链上</h2>
 * <p>所有权链条是 {@code FXGLTransfer.onDispose → RenderBatch.dispose()}，
 * 而本类由 {@code Gc.charts} 懒创建、挂在这条链的下游。{@code RenderBatch} 不认识
 * 本类（它在更上层的包里），所以由 {@code FXGLTransfer} 经 {@code Gc} 转一手。
 *
 * <h2>拾取：每个系列一个 ID，而且用的是 {@code Gc} 的那本注册表</h2>
 * <p>ID 在<b>渲染层</b>分配（{@code Series} 上没有 {@code pickId()}），
 * 由渲染器的 ID pass 写进 {@code RenderBatch} 的拾取缓冲；命中之后
 * {@code PickHit.payload()} 直接就是那个 {@code Series} 对象。
 * 发号成本与点数无关——一条百万点的曲线也只注册一个 ID。
 *
 * <h2>不支持的图型明确报错</h2>
 * <p>热力图与瀑布图（{@code polylineFamily() == false}）本期没有渲染器，
 * 阶梯图、面积图与柱状图也还没有（它们会落到折线渲染器里、由它抛异常）。
 * 遇到这些<b>抛异常</b>——静默不画是本项目最典型的静默错误输出：
 * 画面里少一张图，与"这张图没数据"在视觉上完全一样。
 *
 * <p>本期已实现的图型只有三种：{@link ChartType#LINE} 与
 * {@link ChartType#LINE_AND_MARKERS}（折线渲染器，后者只画折线那半）以及
 * {@link ChartType#SCATTER}（散点渲染器）。
 */
public final class ChartRenderer implements Disposable {

    private final GLAbstraction gl;

    /** 每个系列的 GPU 常驻缓冲。用 IdentityHashMap：Series 没有值语义。 */
    private final Map<Series, SeriesBuffer> buffers = new IdentityHashMap<>();

    /**
     * 拾取 ID 注册表。
     *
     * <p><strong>必须是 {@code Gc} 的那一个，不能自己新建。</strong>
     * 拾取缓冲里只有一个 ID 空间：两套注册表各自从 1 发号的话，"1 号"既可能是一条曲线、
     * 也可能是一个按钮——点击会落在错误的对象上，而画面完全正常。
     */
    private final PickRegistry pickRegistry;

    /** 系列 → 拾取 ID。同一个 Series 只注册一次。 */
    private final Map<Series, Integer> pickIds = new IdentityHashMap<>();

    /** 折线族渲染器，全局一个（它自己不持有数据）。 */
    private final LineSeriesRenderer lineRenderer;

    /** 散点渲染器，全局一个（它自己不持有数据）。 */
    private final ScatterSeriesRenderer scatterRenderer;

    /**
     * 四个着色器程序：{@code {折线, 散点} × {绘制, 拾取}}。
     *
     * <p>两个维度各自正交——<b>顶点程序按"一个实例是什么"分</b>（线段 / 点），
     * <b>片段程序按"这一趟是画还是拾取"分</b>。于是四个程序、四份源码，
     * 每个组合各一份，不需要在着色器里塞分支。
     *
     * <p>{@link #scatterPickShader} <b>不能省</b>：拿 {@link #pickShader}（折线的顶点源码）
     * 去画散点的热区，会得到一条从数据值竖直拉到 0 的长条——点中哪里都命中，
     * 而画面完全正常。
     */
    private final ShaderProgram lineShader;

    private final ShaderProgram pickShader;

    private final ShaderProgram scatterShader;

    private final ShaderProgram scatterPickShader;

    /**
     * 拾取缓冲的借用入口，透传给 {@link GLRenderContextImpl}。
     *
     * <p><strong>必须是 {@code RenderBatch.withPickPass}，不能自己开一个 FBO。</strong>
     * 拾取缓冲与"本帧是否已清空""本帧是否有效"两个标志都归 {@code RenderBatch} 管，
     * 另起一套的话，图层拾取的顺序会与 {@code Gc} 图元的拾取顺序对不上——
     * 重叠处谁赢就错了，而画面完全正常。
     */
    private final Consumer<Runnable> pickPass;

    private boolean disposed = false;

    /**
     * 创建图表渲染器：编译两个着色器程序。
     *
     * <p>必须在 GL 线程（且 GL 上下文已 current）上调用。
     *
     * @param gl           GL 抽象层，应当就是 {@code RenderBatch} 用的那一个
     * @param pickRegistry 拾取 ID 注册表，应当是 {@code Gc.pickRegistry}（理由见字段说明）
     * @param pickPass     拾取缓冲的借用入口，应当是 {@code RenderBatch::withPickPass}
     *                     （理由见字段说明）
     */
    public ChartRenderer(GLAbstraction gl, PickRegistry pickRegistry,
                         Consumer<Runnable> pickPass) {
        this.gl = gl;
        this.pickRegistry = pickRegistry;
        this.pickPass = pickPass;
        this.lineShader = gl.createShader(SeriesShaders.LINE_VERTEX, SeriesShaders.LINE_FRAGMENT);
        this.pickShader = gl.createShader(SeriesShaders.LINE_VERTEX, SeriesShaders.PICK_FRAGMENT);
        this.scatterShader =
                gl.createShader(SeriesShaders.SCATTER_VERTEX, SeriesShaders.LINE_FRAGMENT);
        this.scatterPickShader =
                gl.createShader(SeriesShaders.SCATTER_VERTEX, SeriesShaders.PICK_FRAGMENT);
        this.lineRenderer = new LineSeriesRenderer(gl);
        this.scatterRenderer = new ScatterSeriesRenderer(gl);
    }

    /**
     * 画一张图。
     *
     * <p>调用方应当先用 {@code Gc} 画好网格、调用 {@code gc.flush()}，再调本方法，
     * 最后画刻度文字——这样 z 序是"网格 → 数据 → 标注"（见类文档）。
     *
     * @param chart          图表
     * @param plotRect       绘图区（数据区域）矩形，设备像素
     * @param viewportWidth  帧缓冲宽度（设备像素）
     * @param viewportHeight 帧缓冲高度（设备像素）
     * @throws IllegalStateException 已释放后调用
     */
    public void draw(Chart chart, Rect plotRect, int viewportWidth, int viewportHeight) {
        if (disposed) {
            throw new IllegalStateException("ChartRenderer 已释放");
        }
        // Chart.axes() 返回的是 List<Axis>，而 SeriesRenderer.render 要的是 Axis[]，
        // 这里转一次。x 轴的单位是数据下标，y 轴是数值——两份都在 ChartRenderLayout 里。
        Axis[] axes = chart.axes().toArray(new Axis[0]);
        if (axes.length < 2) {
            // 没有这道守卫的话，下一行会抛一个 ArrayIndexOutOfBounds，而"轴不够"
            // 与"索引算错了"看起来一模一样，排查方向会跑偏。
            throw new IllegalArgumentException(
                    "画图需要两根轴：0 号是数据下标（x），1 号是数值（y）。实际只有 "
                            + axes.length + " 根。");
        }
        ChartRenderLayout layout = new ChartRenderLayout(plotRect, axes[0], axes[1]);
        GLRenderContextImpl ctx = new GLRenderContextImpl(
                gl, lineShader, pickShader, scatterShader, scatterPickShader,
                pickPass, layout, viewportWidth, viewportHeight);

        for (Layer layer : chart.layers()) {
            for (Series series : layer.series()) {
                SeriesRenderer renderer = rendererFor(series.type());
                // 先设当前系列、再给它缓冲：bufferFor 会拿这个断言拦住
                // "渲染器自己缓存了跨系列缓冲引用"那类越界（见 GLRenderContextImpl）。
                ctx.setCurrentSeries(series);
                ctx.setCurrentBuffer(buffers.computeIfAbsent(series,
                        s -> new SeriesBuffer(gl, s.data())));
                // 拾取号在**渲染层**分配，不在图表框架里——所以 Series 上没有 pickId()。
                // 注册的是 Series 对象本身：命中之后 PickHit.payload() 直接就是那个 Series。
                //
                // 注意号分配走的是 Gc 的注册表（不能自己新建一份，理由见 pickRegistry 字段），
                // 而注册表对 payload 是强引用，因此 dispose 时必须注销。
                ctx.setPickId(pickIds.computeIfAbsent(series, pickRegistry::register));
                renderer.render(ctx, series.data(), series, axes);
            }
        }
    }

    /**
     * 取走"这个系列自上一次取走以来<b>真正上传到 GPU</b> 的字节数"，同时把它清零。
     *
     * <h2>它为什么存在</h2>
     * <p>② 的性能主张是「<b>每帧只上传新增的点，不是整个窗口</b>」。
     * 这条主张在画面上<b>没有任何痕迹</b>：增量上传与每帧全量重传画出来的图
     * <b>逐像素相同</b>，{@code ChartVerifier} 已有的十五条断言一条也分不开它们。
     * 没有这个入口，"只传新增"就只是注释里的一句承诺——与
     * {@code RenderBatch.pickPassCount()} 是同一类东西：<b>为一个断言而存在的观测口</b>，
     * 生产代码不该依赖它。
     *
     * <h2>为什么是"取走"而不是"读一眼"</h2>
     * <p>{@link SeriesBuffer#uploadedBytesThisFrame()} 只在有人调用
     * {@link SeriesBuffer#beginFrame()} 时才清零，而绘制路径<b>不调用</b>它
     * （往 {@link #draw} 里加一次清零属于改渲染路径，本任务明确不做）。
     * 所以"本帧"的语义就落在调用方身上：<b>取走并清零</b>——调用方每帧画完之后问一次，
     * 拿到的就是"这一帧传了多少字节"。
     *
     * <p>写成"读一眼、不清零"会更糟：返回的会是<b>自渲染器创建以来的累计值</b>，
     * 也就是一个看起来像"每帧字节数"、却随帧数线性增长的数——
     * 比没有这个入口更容易让人得出错误结论。
     *
     * <p>因此：同一帧里问两次，第二次得到 0；从没被 {@link #draw} 画过的系列也返回 0。
     * 两者都是这套语义的自然结果，不是缺陷。
     *
     * @param series 要问的系列，必须是 {@link #draw} 里用的<b>同一个对象</b>
     *               （{@code Series} 没有值语义，缓冲用 IdentityHashMap 索引）
     * @return 自上次取走以来上传的字节数；没画过时为 0
     */
    public int takeUploadedBytes(Series series) {
        SeriesBuffer buffer = buffers.get(series);
        if (buffer == null) {
            return 0;
        }
        int bytes = buffer.uploadedBytesThisFrame();
        buffer.beginFrame();
        return bytes;
    }

    /**
     * 图型 → 渲染器。不支持的图型明确抛异常，不静默不画。
     *
     * <p>查表规则与 {@link ChartType} 的属性组合一一对应：
     * 不在折线族里的（热力图、瀑布图）本期没有渲染器；
     * <b>只画标记点的（散点）归散点渲染器</b>；其余归折线渲染器，
     * 由它自己再守一道"这个图型我画不画得出来"（见 {@code requireSupported}）。
     *
     * <p><strong>{@link ChartType#LINE_AND_MARKERS} 不走散点这条分支</strong>——
     * 它的 {@code drawsMarkers()} 也为真，但 {@code connectsSamples()} 同时为真，
     * 而本期只画它的折线部分（标记点那半是写在 {@code LineSeriesRenderer.requireSupported}
     * 文档里的已知缺口）。多一个 {@code !connectsSamples()} 就是为了把它挡在门外：
     * 少了它会走散点渲染器，于是折线整条消失、只剩一串点——而"只有点"看起来
     * 像一种刻意的风格，不像缺陷。
     */
    private SeriesRenderer rendererFor(ChartType type) {
        if (!type.polylineFamily()) {
            throw new UnsupportedOperationException(
                    "图型 " + type + " 本期还没有渲染器（热力图与瀑布图的顶点不是"
                            + "\"每个样本一个点\"，要各自的独立渲染器）。明确报错而不是静默不画："
                            + "画面里少一张图，与\"这张图没数据\"在视觉上完全一样。");
        }
        if (type.drawsMarkers() && !type.connectsSamples()) {
            return scatterRenderer;
        }
        return lineRenderer;
    }

    /**
     * 释放本类创建的全部 GL 资源。
     *
     * <p>幂等。顺序是"先注销拾取 ID、再删 GL 资源"：注册表持有 payload 的强引用，
     * 不注销的话，被移除的 {@code Series} 会一直被引用着——而它在画面上早就没了。
     */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        pickIds.values().forEach(id -> pickRegistry.unregister(id));
        pickIds.clear();
        buffers.values().forEach(SeriesBuffer::dispose);
        buffers.clear();
        lineRenderer.dispose();
        scatterRenderer.dispose();
        lineShader.dispose();
        pickShader.dispose();
        scatterShader.dispose();
        scatterPickShader.dispose();
        disposed = true;
    }
}
