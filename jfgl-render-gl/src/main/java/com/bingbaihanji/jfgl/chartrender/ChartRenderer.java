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
 * <p>热力图与瀑布图（{@code polylineFamily() == false}）本期没有渲染器。
 * 遇到这些<b>抛异常</b>——静默不画是本项目最典型的静默错误输出：
 * 画面里少一张图，与"这张图没数据"在视觉上完全一样。
 *
 * <p>本期已实现的图型有七种：{@link ChartType#LINE} 与
 * {@link ChartType#LINE_AND_MARKERS}（折线渲染器，后者只画折线那半）、
 * {@link ChartType#SCATTER}（散点渲染器）、{@link ChartType#STEP}（阶梯）、
 * {@link ChartType#AREA}（面积：填充 + 轮廓线）、{@link ChartType#BAR}（柱状）
 * 以及 {@link ChartType#SPECTRUM}（{@link SpectrumSeriesRenderer}——它的顶点由
 * GPU 上的 FFT 算出，因此不是折线族，见 {@link #rendererFor} 里那条必须走在前面的例外）。
 *
 * <h2>柱状图的并排分组在这里算</h2>
 * <p>{@code SeriesRenderer.render} 只拿得到自己那一个系列，<b>看不到兄弟系列</b>，
 * 而"同一层里并排的柱子各占哪一段"必须知道一共有几根。所以层内计数与发槽位在
 * {@link #draw} 里做（见 {@link #setBarSlots}），渲染器只管读数。
 *
 * <p>同一层里并排的柱状系列还必须用<b>同一组</b>间距：柱宽公式里带上了
 * {@code (系列数 - 1) × barGap}，各个系列自己一套间距的话，同格里的柱子会宽窄不一——
 * 而"柱子有点胖瘦"看起来像数据本身的差别，不像配置冲突。因此这里显式拦住。
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

    /** 阶梯线渲染器，全局一个（它自己不持有数据）。 */
    private final StepSeriesRenderer stepRenderer;

    /**
     * 面积图渲染器，全局一个。
     *
     * <p>它持有 {@link #lineRenderer}：面积图的轮廓线就是一条普通折线，
     * 由那条路径画（理由见 {@link AreaSeriesRenderer} 的类文档）。
     * 它<b>不</b>持有数据或缓冲。
     */
    private final AreaSeriesRenderer areaRenderer;

    /** 柱状图渲染器，全局一个（它自己不持有数据）。 */
    private final BarSeriesRenderer barRenderer;

    /**
     * 频谱渲染器，全局一个。
     *
     * <p>它是唯一一个<b>持有 GL 资源以外的东西</b>的渲染器：FFT 核按源环容量各存一个
     * （见 {@link SpectrumSeriesRenderer} 的字段说明），因为核的环容量在构造时就定死了。
     */
    private final SpectrumSeriesRenderer spectrumRenderer;

    /**
     * 着色器程序：{@code {折线, 散点, 阶梯, 面积, 柱状} × {绘制, 拾取}}。
     *
     * <p>两个维度各自正交——<b>顶点程序按"一个实例是什么"分</b>
     * （线段 / 点 / 阶梯段 / 梯形 / 柱），<b>片段程序按"这一趟是画还是拾取"分</b>。
     * 于是程序数 = 顶点程序数 × 2，每个组合各一份，不需要在着色器里塞分支。
     *
     * <p>每一个拾取程序都<b>不能省</b>：拿错顶点源码的后果是热区形状与画面不一致
     * （例如用 {@link #pickShader} 去画散点的热区，会得到一条从数据值竖直拉到 0 的
     * 长条——点中哪里都命中），而那种缺陷在画面上<b>看不出来</b>。
     */
    private final ShaderProgram lineShader;

    private final ShaderProgram pickShader;

    private final ShaderProgram scatterShader;

    private final ShaderProgram scatterPickShader;

    private final ShaderProgram stepShader;

    private final ShaderProgram stepPickShader;

    private final ShaderProgram areaShader;

    private final ShaderProgram areaPickShader;

    private final ShaderProgram barShader;

    private final ShaderProgram barPickShader;

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
        this.stepShader = gl.createShader(SeriesShaders.STEP_VERTEX, SeriesShaders.LINE_FRAGMENT);
        this.stepPickShader =
                gl.createShader(SeriesShaders.STEP_VERTEX, SeriesShaders.PICK_FRAGMENT);
        this.areaShader = gl.createShader(SeriesShaders.AREA_VERTEX, SeriesShaders.LINE_FRAGMENT);
        this.areaPickShader =
                gl.createShader(SeriesShaders.AREA_VERTEX, SeriesShaders.PICK_FRAGMENT);
        this.barShader = gl.createShader(SeriesShaders.BAR_VERTEX, SeriesShaders.LINE_FRAGMENT);
        this.barPickShader =
                gl.createShader(SeriesShaders.BAR_VERTEX, SeriesShaders.PICK_FRAGMENT);
        this.lineRenderer = new LineSeriesRenderer(gl);
        this.scatterRenderer = new ScatterSeriesRenderer(gl);
        // 面积图的轮廓线由折线路径画，所以它要拿到那一个渲染器（两者都是无状态的纯函数）。
        this.areaRenderer = new AreaSeriesRenderer(gl, lineRenderer);
        this.stepRenderer = new StepSeriesRenderer(gl);
        this.barRenderer = new BarSeriesRenderer(gl);
        this.spectrumRenderer = new SpectrumSeriesRenderer(gl);
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
                stepShader, stepPickShader, areaShader, areaPickShader,
                barShader, barPickShader, pickPass, layout, viewportWidth, viewportHeight);

        for (Layer layer : chart.layers()) {
            int barCount = requireSameBarGaps(layer);
            int barSlot = 0;
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
                // 柱状系列的并排槽位（渲染器看不到兄弟系列，见 setBarSlots 的说明）。
                // "一共几根"在这里就用掉了，所以只需再数一次递增的那个序号。
                if (series.type().drawsBars()) {
                    ctx.setBarSlot(barSlot++, barCount);
                } else {
                    // 上下文是复用的：不重置的话，柱状系列之后那个折线系列会带着
                    // 上一根的槽位跑。它不读这两个数，所以画面上完全一样——
                    // 直到某天有人给折线也加上柱宽逻辑。重置是零成本的。
                    ctx.setBarSlot(0, 1);
                }
                renderer.render(ctx, series.data(), series, axes);
            }
        }
    }

    /**
     * 数出本层里有几个柱状系列，并检查它们的间距配置一致。
     *
     * <h2>为什么"一共几根"必须在这里算</h2>
     * <p>{@link SeriesRenderer#render} 的签名里只有自己那一个系列，<b>看不到兄弟系列</b>，
     * 而柱宽的分母（{@code n + (n-1)·barGap}）与柱心的偏移都要用到"一共几根"。
     * 让渲染器去猜（恒当第 0 根、总数 1）的后果是同层多个柱状系列<b>完全重叠</b>：
     * 画面上只剩最后画的那一个，而它看起来就是一张正常的单系列柱状图。
     *
     * <h2>为什么间距必须一致</h2>
     * <p>柱宽同时取决于 {@code categoryGap}、{@code barGap} 与系列数，而这三样是
     * <b>逐系列</b>配置的。同层里各配一套的话，并排的柱子会宽窄不一——
     * 那看起来像数据本身的差别，不像配置冲突。<b>不静默容忍</b>（与"不支持的图型
     * 抛异常"同一条纪律），也不自作主张取第一个系列的值（那会让另一些系列的配置
     * 静默失效）。
     *
     * @return 本层里柱状系列的个数（0 表示这一层没有柱状图）
     * @throws IllegalArgumentException 同层里并排的柱状系列间距配置不一致时
     */
    private static int requireSameBarGaps(Layer layer) {
        Series first = null;
        int count = 0;
        for (Series series : layer.series()) {
            if (!series.type().drawsBars()) {
                continue;
            }
            count++;
            if (first == null) {
                first = series;
                continue;
            }
            // 用 Float.compare 而不是 !=：NaN != NaN 会让"两个系列都配了 NaN"报成一个
            // 与真正原因（间距算不出柱宽）无关的错。
            if (Float.compare(first.categoryGap(), series.categoryGap()) != 0
                    || Float.compare(first.barGap(), series.barGap()) != 0) {
                throw new IllegalArgumentException(
                        "同一层里并排的柱状系列必须用同一组 categoryGap/barGap："
                                + "「" + first.name() + "」是 (" + first.categoryGap() + ", "
                                + first.barGap() + ")，「" + series.name() + "」是 ("
                                + series.categoryGap() + ", " + series.barGap() + ")。"
                                + "柱宽同时取决于这两项与系列数，各自一套的话同格里的柱子会"
                                + "宽窄不一，而\"柱子有点胖瘦\"看起来像数据本身的差别。"
                                + "确实想要不同的柱宽，请把它们放进不同的层。");
            }
        }
        return count;
    }

    /**
     * 取走"这个系列自上一次取走以来<b>真正上传到 GPU</b> 的字节数"，同时把它清零。
     *
     * <h2>它为什么存在</h2>
     * <p>② 的性能主张是「<b>每帧只上传新增的点，不是整个窗口</b>」。
     * 这条主张在画面上<b>没有任何痕迹</b>：增量上传与每帧全量重传画出来的图
     * <b>逐像素相同</b>，{@code ChartVerifier} 里所有那些**像素**断言一条也分不开它们
     * （写这段时它有 15 条，现在 65 条——多出来的正是为此加的）。
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
     * <h2>它是"哪些图型归谁"的唯一判断处</h2>
     * <p>各渲染器自己也有一道 {@code requireSupported}，但那道守的是另一个问题：
     * "如果有人把别的图型路由到我这儿，我要炸"。两张表都对同一件事下判断是刻意的冗余——
     * 只有这里判断的话，一个调用错了渲染器的分支会静默画错；只有那里判断的话，
     * 每加一个图型就要改五处。两边都是<b>白名单</b>（列出自己能画的，其余抛异常），
     * 所以两者一致时不会互相打架。
     *
     * <h2>为什么是逐图型的显式枚举，不是按属性组合推</h2>
     * <p>按 {@code connectsSamples() / drawsMarkers() / drawsBars()} 这些标记推出来的话，
     * {@link ChartType#STEP}（先横后竖）与 {@link ChartType#AREA}（线下填充）都会落进
     * "折线"那一格——而折线渲染器会把阶梯拉成斜线、把面积图的填充整个丢掉，
     * 两者的画面都"看起来正常"。{@link ChartType#BAR} 更明显：它的顶点是矩形。
     * 所以这里逐条写清楚，{@link ChartType} 那边只保留"顶点从哪来"这一个判断
     * （{@code polylineFamily()}）。
     *
     * <p><b>两条例外必须走在最前面</b>：
     * <ol>
     *   <li>{@link ChartType#SPECTRUM} 的 {@code polylineFamily()} 是 {@code false}
     *       （它的顶点不是"每个样本一个点"，而是 FFT 算出来的 bin），却<b>已经有渲染器</b>。
     *       放在 {@code polylineFamily()} 那道守卫之后的话，它先被"本期还没有渲染器"
     *       抛掉，频谱永远画不出来。</li>
     *   <li>{@link ChartType#LINE_AND_MARKERS} <b>不走散点这条分支</b>：它的
     *       {@code drawsMarkers()} 也为真，但 {@code connectsSamples()} 同时为真，
     *       而本期只画它的折线部分（标记点那半是写在 {@code LineSeriesRenderer.requireSupported}
     *       文档里的已知缺口）。少了 {@code !connectsSamples()} 它会走散点渲染器，
     *       于是折线整条消失、只剩一串点——而"只有点"看起来像一种刻意的风格。</li>
     * </ol>
     */
    private SeriesRenderer rendererFor(ChartType type) {
        if (type == ChartType.LINE || type == ChartType.LINE_AND_MARKERS) {
            return lineRenderer;
        }
        if (type == ChartType.SCATTER) {
            return scatterRenderer;
        }
        if (type == ChartType.STEP) {
            return stepRenderer;
        }
        if (type == ChartType.AREA) {
            return areaRenderer;
        }
        if (type == ChartType.BAR) {
            return barRenderer;
        }
        if (type == ChartType.SPECTRUM) {
            return spectrumRenderer;
        }
        // 剩下的是热力图与瀑布图：它们的顶点不是"每个样本一个点"，而是
        // "每个像素列一个直方图"之类，需要各自的独立渲染器（见 ChartType 的类文档）。
        throw new UnsupportedOperationException(
                "图型 " + type + " 本期还没有渲染器（它的顶点不是\"每个样本一个点\"，"
                        + "要一个独立的渲染器）。明确报错而不是静默不画："
                        + "画面里少一张图，与\"这张图没数据\"在视觉上完全一样。");
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
        stepRenderer.dispose();
        areaRenderer.dispose();
        barRenderer.dispose();
        spectrumRenderer.dispose();
        lineShader.dispose();
        pickShader.dispose();
        scatterShader.dispose();
        scatterPickShader.dispose();
        stepShader.dispose();
        stepPickShader.dispose();
        areaShader.dispose();
        areaPickShader.dispose();
        barShader.dispose();
        barPickShader.dispose();
        disposed = true;
    }
}
