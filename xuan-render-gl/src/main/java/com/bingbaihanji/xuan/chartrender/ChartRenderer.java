package com.bingbaihanji.xuan.chartrender;

import com.bingbaihanji.xuan.chart.Axis;
import com.bingbaihanji.xuan.chart.Chart;
import com.bingbaihanji.xuan.chart.ChartLayout;
import com.bingbaihanji.xuan.chart.ChartType;
import com.bingbaihanji.xuan.chart.Layer;
import com.bingbaihanji.xuan.chart.Series;
import com.bingbaihanji.xuan.chart.SeriesRenderer;
import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.gl.ShaderProgram;
import com.bingbaihanji.xuan.renderer.PickRegistry;
import com.bingbaihanji.xuan.util.Disposable;
import com.bingbaihanji.xuan.util.Rect;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BooleanSupplier;
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
 * <h2>资源跟着使用走：不再画的系列会被自动回收</h2>
 * <p>{@link #buffers} 与 {@link #pickIds} 都按 {@code Series} 的<b>对象身份</b>缓存，
 * 而 {@code Chart} 看起来是个纯计算对象——于是"每帧重建 {@code Chart}"（每帧 new 出新的
 * {@code Series}）是最自然的写法。以前那种写法<b>每帧泄漏一块缓冲并消耗两个拾取号</b>，
 * 症状是"前几百帧完全正常，然后图表忽然不画了，没有任何报错"。
 *
 * <p>现在有了一条回收路径：{@link #releaseUnused} 由帧的所有者（{@code Gc.beginFrame}）
 * 每帧调一次，把<b>连续两帧没有被 {@link #draw} 画过</b>的系列释放掉。
 * 调用方<b>不需要学任何新 API</b>——不用的东西自己会走。取舍与逐步的算例见
 * {@link #releaseUnused} 与 {@link #GRACE_GENERATIONS} 的文档。
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
 * {@link ChartType#LINE_AND_MARKERS}（折线渲染器；后者是折线 + 标记点，
 * 标记点那一半复用散点渲染器的几何）、
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

    /**
     * 回收的宽限代数：一个系列<b>连续这么多代（= 这么多帧）没被画过</b>才回收。
     *
     * <h2>为什么不是 1（"上一帧没画就收"）</h2>
     * <p>取 1 的话，"<b>每隔一帧画一次</b>"这种用法会<b>每画一次就销毁并重建一次缓冲</b>
     * ——而重建意味着把整个环<b>重传</b>（1M 点就是 4 MB）。它不是错误用法，
     * 只是帧率与数据节奏对不齐，<b>症状还完全看不出来</b>：画面逐像素相同，
     * 只有 GPU 上传量悄悄翻了几百倍。取 2 之后，那种用法的最大间隔恰好是 1 代
     * （见 {@link #releaseUnused} 的算例），于是它一次都不回收；
     * 而"真的不再画了"仍然在<b>连续两帧</b>之内释放。
     *
     * <p>取 3 或更大没有好处：宽限越长，"不再画的系列"占着显存与拾取号的时间越久，
     * 而它换来的只是让间隔更长的用法也不抖——那种用法的间隔是任意的，
     * 加多少都不够。
     */
    private static final long GRACE_GENERATIONS = 2;

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

    /**
     * 画标题与图例的那支笔；可以为 null（表示"不需要装饰"）。
     *
     * <p>只有 {@link #drawChart} 用它。<b>为 null 时 {@code drawChart} 抛异常</b>而不是
     * 静默地不画装饰——静默不画正是本项目最典型的错误输出：一张图少了标题，
     * 与"标题本来就是空的"在画面上完全一样。
     */
    private final ChartPainter painter;

    /**
     * "数据系列是否做解析式抗锯齿"的取值入口。
     *
     * <h2>为什么是一个 supplier 而不是一个 boolean</h2>
     * <p>本类是<b>一次创建、长期使用</b>的（由 {@code Gc.charts} 懒创建），
     * 而 AA 开关是<b>每次 draw 都可能不同</b>的绘制状态——存成 boolean 就会把
     * "创建那一刻的开关"永久固化下来。存 supplier 之后，每次 {@link #draw} 的入口
     * 取一次快照，于是"这一帧开没开"这件事永远跟着调用方走。
     *
     * <p><strong>{@code Gc} 传的是 {@code { antialias }}（它自己那个属性）。</strong>
     * 图表后端在更下层，看不见 {@code Gc}——依赖方向只允许向上，
     * 所以这个值必须由装配方（{@code Gc}）显式注入，与 {@code pickRegistry} /
     * {@code withPickPass} / {@code chartPainter} 三个依赖同一条纪律。
     *
     * <p>默认（不注入时）恒为 {@code false}：<b>AA 关是"什么都不发生"的那一侧</b>，
     * 默认值必须落在它上面（否则既有像素期望会集体移动）。
     *
     * <p>⚠️ <b>"不注入时恒为 false"目前没有任何断言盖着</b>（照实说）：真正走那条路的
     * 只有下面那个 3 参构造，而它**在仓库里一个调用点都没有**——{@code Gc} 走的是 5 参那个
     * （传它自己的 {@code antialias}）。{@code ChartVerifier} 的 AA 一节证的是"注入之后
     * 开关真的生效"，证不到"不注入时是关的"。真要盖住它得建一个不注入的渲染器画一帧，
     * 与本 Task 的判据无关，暂不补。
     */
    private final BooleanSupplier antialias;

    /**
     * 系列 → 它最后一次被 {@link #draw} 画到的那一代。
     *
     * <p>同样用 {@link IdentityHashMap}：{@code Series} 没有值语义，
     * 与 {@link #buffers} / {@link #pickIds} 必须<b>用同一种身份口径</b>索引，
     * 否则"画过的那个系列"与"有缓冲的那个系列"是两个不同的键，
     * 回收会去删一个不存在的条目（而 {@code IdentityHashMap} 的 {@code remove}
     * 对不存在的键是静默的）。
     *
     * <p><b>它同时也是"本渲染器认识哪些系列"的那份名单</b>：三张表的键集在
     * {@link #draw} 里同步增删，所以遍历它一处就能覆盖另外两处。
     */
    private final Map<Series, Long> lastSeen = new IdentityHashMap<>();

    /**
     * 当前的"代"：每调用一次 {@link #releaseUnused()} 加一，也就是<b>一帧一代</b>。
     *
     * <p>{@link #draw} 把每个画到的系列标记成<b>当前代</b>（见 {@link #lastSeen}），
     * 回收则按"距今几代"判断。用代而不是帧号是因为本类<b>不知道帧号</b>——
     * 它只在 {@code Gc.beginFrame} 与 {@code draw} 两个时机被碰到，
     * 中间隔着多少帧对它没有意义。
     */
    private long generation = 0;

    private boolean disposed = false;

    /**
     * 创建一个<b>画不了装饰、也不做系列抗锯齿</b>的图表渲染器。
     *
     * <p>{@link #drawChart} 在这种实例上会抛异常，见 {@link #painter}；
     * 数据系列则恒按"AA 关"画（理由见 {@link #antialias}）。
     */
    public ChartRenderer(GLAbstraction gl, PickRegistry pickRegistry,
                         Consumer<Runnable> pickPass) {
        this(gl, pickRegistry, pickPass, null, () -> false);
    }

    /**
     * 创建图表渲染器：编译着色器程序。
     *
     * <p>必须在 GL 线程（且 GL 上下文已 current）上调用。
     *
     * @param gl           GL 抽象层，应当就是 {@code RenderBatch} 用的那一个
     * @param pickRegistry 拾取 ID 注册表，应当是 {@code Gc.pickRegistry}（理由见字段说明）
     * @param pickPass     拾取缓冲的借用入口，应当是 {@code RenderBatch::withPickPass}
     *                     （理由见字段说明）
     * @param painter      标题与图例的绘制入口；{@code Gc} 传它自己的那个，
     *                     传 null 表示这个实例不支持 {@link #drawChart}
     * @param antialias    数据系列是否做解析式 AA 的取值入口；{@code Gc} 传它自己的
     *                     {@code antialias} 属性（理由见 {@link #antialias}）
     */
    public ChartRenderer(GLAbstraction gl, PickRegistry pickRegistry,
                         Consumer<Runnable> pickPass, ChartPainter painter,
                         BooleanSupplier antialias) {
        this.gl = gl;
        this.pickRegistry = pickRegistry;
        this.pickPass = pickPass;
        this.painter = painter;
        this.antialias = antialias;
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
        // 散点渲染器先建：折线渲染器要拿它画 LINE_AND_MARKERS 的标记点那一半
        // （注入的必须是**同一个实例**，否则两边各建一套 VAO，画的还是同一批像素）。
        this.scatterRenderer = new ScatterSeriesRenderer(gl);
        this.lineRenderer = new LineSeriesRenderer(gl, scatterRenderer);
        // 面积图的轮廓线由折线路径画，所以它要拿到那一个渲染器（两者都是无状态的纯函数）。
        this.areaRenderer = new AreaSeriesRenderer(gl, lineRenderer);
        this.stepRenderer = new StepSeriesRenderer(gl);
        this.barRenderer = new BarSeriesRenderer(gl);
        this.spectrumRenderer = new SpectrumSeriesRenderer(gl);
    }

    private static void drawTooltip(ChartPainter painter,
                                    com.bingbaihanji.xuan.chart.ChartHover hover,
                                    Rect plot,
                                    com.bingbaihanji.xuan.chart.ChartInteractionConfig config) {
        float width = 0f;
        float lineHeight = config.tooltipFontSize() * 1.4f;
        for (var line : hover.lines()) {
            width = Math.max(width, painter.width(line.label() + ": " + line.value(),
                    config.tooltipFontSize()));
        }
        width += config.tooltipPadding() * 2f;
        float height = hover.lines().size() * lineHeight + config.tooltipPadding() * 2f;
        float x = hover.screenX() + config.tooltipOffset();
        float y = hover.screenY() - height - config.tooltipOffset();
        if (x + width > plot.x + plot.width) {
            x = hover.screenX() - width - config.tooltipOffset();
        }
        if (y < plot.y) {
            y = hover.screenY() + config.tooltipOffset();
        }
        x = Math.max(plot.x, Math.min(x, plot.x + plot.width - width));
        y = Math.max(plot.y, Math.min(y, plot.y + plot.height - height));

        painter.fillRect(x, y, width, height, config.tooltipBackground());
        painter.strokeRect(x, y, width, height, 1f, config.tooltipBorder());
        float baseline = y + config.tooltipPadding() + config.tooltipFontSize();
        for (var line : hover.lines()) {
            painter.drawText(line.label() + ": " + line.value(),
                    x + config.tooltipPadding(), baseline,
                    config.tooltipFontSize(), config.tooltipText());
            baseline += lineHeight;
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
     * 这个系列要不要<b>平滑布局</b>的缓冲（= 它的折线会不会被画成曲线）。
     *
     * <p>判据是"系列开了平滑"<b>且</b>图型真的会画线：{@link ChartType#LINE}、
     * {@link ChartType#LINE_AND_MARKERS}、{@link ChartType#AREA}。
     * 其余图型（阶梯 / 散点 / 柱状 / 频谱）忽略这个开关——理由见
     * {@code Series.smooth()} 的文档；在这里判一次，是为了让它们的缓冲
     * <b>连布局都不变</b>（白多两个镜像的写入没有任何意义）。
     *
     * <p>它与渲染器侧的判据是同一件事的两半：渲染器读的是<b>缓冲的布局</b>
     * （{@code SeriesBuffer.smoothLayout()}），而本方法保证布局与
     * {@code series.smooth()} 一致。两者必须成对——只改一处的话，
     * 属性偏移与 {@code uSmooth} 会各说各话。
     */
    private static boolean wantsSmoothLayout(Series series) {
        if (!series.smooth()) {
            return false;
        }
        ChartType type = series.type();
        return type == ChartType.LINE || type == ChartType.LINE_AND_MARKERS
                || type == ChartType.AREA;
    }

    /**
     * 画一张图的<b>全部</b>：先按 {@code chart} 的装配配置（标题、图例、外边距）
     * 把 {@code frame} 切成几块，再在算出来的绘图区里画数据系列。
     *
     * <h2>与 {@link #draw} 的分工</h2>
     * <p>{@link #draw} 要的是<b>已经算好的绘图区</b>——它是"我不管你外面有什么"的低层入口，
     * 刻度、网格、坐标轴都由调用方自己安排（见 README 的图表一节）。
     * 本方法要的是<b>整块外框</b>，装饰的排布由 {@link ChartLayout} 负责。
     * 两条路径画数据系列的代码是同一段（本方法最后调的就是 {@link #draw}），
     * 所以<b>不设标题、不设图例、外边距为 0 时，两条路径逐像素相同</b>——
     * 这一条被 {@code ChartVerifier} 直接按像素钉着（同一张图两条路径各画一帧再比）。
     *
     * <h2>z 序</h2>
     * <p>装饰（标题、图例）在数据系列<b>之前</b>画，但它们占的是绘图区之外的带子，
     * 两者在几何上不重叠（{@link ChartLayout} 保证这一点）。与 {@link #draw} 一样，
     * 调用方应当先画网格再调它。
     *
     * <h2>两条入口检查</h2>
     * <ul>
     *   <li><b>不能带着变换</b>（{@code translate/scale/rotate}）：{@link ChartLayout}
     *       算出来的矩形是设备像素，带着变换会让装饰落到布局没算过的位置上。
     *       这条由 {@link ChartPainter#begin} 强制（{@code Gc} 的实现会检查），
     *       而且<b>没有装饰时也会发生</b>——绘图区同样算在设备像素里。</li>
     *   <li>装饰被<b>裁到各自的带子里</b>：一项文字比带子宽时，后面的部分会在带子
     *       边缘被切断，而不是越过边界画到别处（取舍见 {@link ChartLayout} 的类文档）。
     *       调用方原本设的裁剪仍然有效（求交）。</li>
     * </ul>
     *
     * @param chart          图表
     * @param frame          整块外框（设备像素）
     * @param viewportWidth  帧缓冲宽度（设备像素）
     * @param viewportHeight 帧缓冲高度（设备像素）
     * @throws IllegalStateException 已释放后调用，或当前带着变换时（见上）
     * @throws NullPointerException  没有注入 {@link ChartPainter} 时（构造时传了 null）
     */
    public void drawChart(Chart chart, Rect frame, int viewportWidth, int viewportHeight) {
        if (disposed) {
            throw new IllegalStateException("ChartRenderer 已释放");
        }
        if (painter == null) {
            throw new NullPointerException(
                    "没有注入 ChartPainter：标题与图例无笔画。"
                            + "要么给 ChartRenderer 传一个绘制入口（Gc 会传它自己的），"
                            + "要么改用 draw(chart, plotRect, w, h) 自己排布绘图区。");
        }
        ChartLayout layout = ChartLayout.compute(chart, frame, painter);
        // 装饰借调用方的状态来画，begin/end 成对（见 ChartPainter 的文档）。
        //
        // 这一层的带子是**整块外框**，它只做三件事：压栈、关掉拾取、
        // 以及**校验"没有带着变换"**——那条校验必须在**没有装饰时也发生**
        // （绘图区同样算在设备像素空间里，带着变换一样会画错地方），
        // 所以它不能藏在 ChartDecorations 里那些"有标题/图例才开"的层里。
        // 各带子的裁剪由 ChartDecorations 再各开一层（可以嵌套）。
        painter.begin(frame);
        try {
            ChartDecorations.paint(painter, chart, layout);
            // ★ 坐标系（网格 → 轴线 → 箭头 → 刻度线 → 刻度文字），画在**数据系列之前**：
            //   网格要在数据之下；而刻度文字虽在绘图区之外，数据被裁在绘图区里、盖不到它
            //   （理由见 ChartAxes 的类文档）。`AxisStyle.visible()` 默认 false ⇒ 这行是空操作。
            ChartAxes.paint(painter, chart, layout);
        } finally {
            // finally 不能省：装饰画到一半抛异常时，状态栈会少弹一层，
            // 之后画的每一个图元都带着"标题那次压栈"的状态。
            painter.end();
        }
        draw(chart, layout.plotRect(), viewportWidth, viewportHeight);
        drawInteraction(chart, layout.plotRect());
    }

    /** 绘制当前 hover 的十字线、命中点和可配置提示框。 */
    private void drawInteraction(Chart chart, Rect plot) {
        if (painter == null || !chart.interaction().config().enabled()) {
            return;
        }
        com.bingbaihanji.xuan.chart.ChartHover hover = chart.interaction().probe(chart, plot);
        if (hover == null) {
            return;
        }
        var config = chart.interaction().config();
        painter.begin(plot);
        try {
            if (config.crosshairVisible()) {
                painter.strokeDashedLine(plot.x, hover.screenY(), plot.x + plot.width,
                        hover.screenY(), config.crosshairWidth(), config.dashLength(),
                        config.crosshairColor());
                painter.strokeDashedLine(hover.screenX(), plot.y, hover.screenX(),
                        plot.y + plot.height, config.crosshairWidth(), config.dashLength(),
                        config.crosshairColor());
            }
            painter.fillRect(hover.screenX() - 3f, hover.screenY() - 3f, 6f, 6f,
                    config.crosshairColor());
            if (config.tooltipVisible()) {
                drawTooltip(painter, hover, plot, config);
            }
        } finally {
            painter.end();
        }
    }

    /**
     * 在给定的绘图区里画数据系列（不管外面的标题与图例）。
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
        // AA 开关在这里**取一次快照**：一次 draw 里所有系列、所有 pass 用同一个值。
        // 图表的后端看不见 Gc，这个值由装配方注入（见 antialias 字段）。
        GLRenderContextImpl ctx = new GLRenderContextImpl(
                gl, lineShader, pickShader, scatterShader, scatterPickShader,
                stepShader, stepPickShader, areaShader, areaPickShader,
                barShader, barPickShader, pickPass, layout, viewportWidth, viewportHeight,
                antialias.getAsBoolean());

        for (Layer layer : chart.layers()) {
            int barCount = requireSameBarGaps(layer);
            int barSlot = 0;
            for (Series series : layer.series()) {
                SeriesRenderer renderer = rendererFor(series.type());
                // 先设当前系列、再给它缓冲：bufferFor 会拿这个断言拦住
                // "渲染器自己缓存了跨系列缓冲引用"那类越界（见 GLRenderContextImpl）。
                ctx.setCurrentSeries(series);
                ctx.setCurrentBuffer(bufferFor(series));
                // 拾取号在**渲染层**分配，不在图表框架里——所以 Series 上没有 pickId()。
                // 注册的是 Series 对象本身：命中之后 PickHit.payload() 直接就是那个 Series。
                //
                // 注意号分配走的是 Gc 的注册表（不能自己新建一份，理由见 pickRegistry 字段），
                // 而注册表对 payload 是强引用，因此 dispose 时必须注销。
                ctx.setPickId(pickIds.computeIfAbsent(series, pickRegistry::register));
                // 记下"这个系列这一代活着"。回收（releaseUnused）只看这一个标记，
                // 所以它必须与 buffers / pickIds 两处的增删在同一个循环里发生
                // ——放在 renderer.render 之前：render 抛异常时这一帧的资源
                // 仍然已经被登记过（下一帧按"没被画过"回收掉，不会漏）。
                lastSeen.put(series, generation);
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
     * 取（必要时创建、必要时<b>重建</b>）某个系列的 GPU 常驻缓冲。
     *
     * <h2>为什么平滑开关变了要重建，而不是下一帧换个 uniform</h2>
     * <p>缓冲的<b>物理布局</b>（{@link SeriesLayout}：前面有没有留一个 float）
     * 决定了四个实例属性的字节偏移。布局与 {@code uSmooth} 不匹配时，
     * {@code aYm1} / {@code aY2} 会读到<b>别的样本</b>——曲线弯向一个垃圾值，
     * 而画面"只是一条形状略有出入的曲线"，正是本项目最警惕的那种缺陷。
     *
     * <p><b>为什么不抛异常。</b>GL 线程上的异常在本项目是<b>静默吞掉</b>的
     * （openglfx 的原生回调里没人接），画布会就此冻结、一行报告都没有——
     * 那比画错还难查。
     *
     * <p><b>所以：重建。</b>代价是这一帧把环里的样本重传一次
     * （最多 {@code 容量 × 4} 字节，一次性），收益是"开关随时可以改，而且立刻正确"。
     * 只在开关<b>真的变了</b>的那一帧发生。
     */
    private SeriesBuffer bufferFor(Series series) {
        SeriesBuffer buffer = buffers.get(series);
        boolean wantSmooth = wantsSmoothLayout(series);
        if (buffer != null && buffer.smoothLayout() != wantSmooth) {
            buffer.dispose();
            buffers.remove(series);
            buffer = null;
        }
        if (buffer == null) {
            buffer = new SeriesBuffer(gl, series.data(), wantSmooth);
            buffers.put(series, buffer);
        }
        return buffer;
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
     * <p><b>第三种返回 0 的情况是"已经被回收"</b>（见 {@link #releaseUnused}）：
     * 那说明这个系列<b>连续两帧没有被画过</b>了，缓冲已经删掉。这与前两种是不同的事，
     * 但返回值一样是 0——调用方若想知道"它还在不在"，看的是自己的绘制循环，
     * 不是这个入口。</p>
     *
     * @param series 要问的系列，必须是 {@link #draw} 里用的<b>同一个对象</b>
     *               （{@code Series} 没有值语义，缓冲用 IdentityHashMap 索引）
     * @return 自上次取走以来上传的字节数；没画过、或已被回收时为 0
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
     * 帧首回收：把<b>连续 {@link #GRACE_GENERATIONS} 帧没有被 {@link #draw} 画过</b>
     * 的系列释放掉（缓冲删掉、拾取号注销、三张表里一并移除），然后把代加一。
     *
     * <h2>为什么是自动的，而不是一对 retain / release 方法</h2>
     * <p>本类按 {@code Series} 的<b>对象身份</b>缓存 GPU 缓冲与拾取号（见 {@link #buffers}），
     * 而 {@code Chart} 看起来是个纯计算对象——于是"每帧重建 {@code Chart}"
     * （= 每帧 new 出新的 {@code Series}）是最自然的写法。在自动回收之前，那种写法
     * <b>每帧泄漏一块 {@code SeriesBuffer}（显存）并消耗两个拾取号</b>，
     * 号耗尽时 {@link PickRegistry#register} 抛异常，而 GL 线程上的异常在本项目是
     * <b>静默吞掉</b>的 ⇒ 症状是「前几百帧完全正常，然后图表忽然不画了，没有任何报错」。
     *
     * <p>做成显式的 {@code retain} / {@code release} 没有解决它：要记得调的 API
     * 仍然会被忘记，而<b>忘记的症状是静默的</b>——正是这条缺陷本身的形态。
     * 所以做成"<b>资源跟着使用走</b>"：谁这一帧被画过，谁就活着；不再出现的自动释放。
     * 调用方不需要学任何新 API。
     *
     * <h2>代的算术（"第几帧做什么"，GRACE = 2）</h2>
     * <p>设第 {@code n} 帧的帧首调用本方法时 {@code generation == n}，{@link #draw}
     * 把系列标记成 {@code n + 1}。判据是 {@code generation - lastSeen >= 2}，
     * 即 {@code n - (k + 1) >= 2} ⇒ 在第 {@code k + 3} 帧的帧首释放。
     * 三个算例（都按这个实现推的，不是想当然）：
     *
     * <table border="1">
     *   <caption>回收与宽限</caption>
     *   <tr><th>用法</th><th>帧</th><th>结果</th></tr>
     *   <tr>
     *     <td>每帧都画</td>
     *     <td>每一帧都被标记为当前代 ⇒ 差值恒 0</td>
     *     <td><b>永不回收</b>，缓冲与拾取号恒定</td>
     *   </tr>
     *   <tr>
     *     <td>每隔一帧画一次<br>（第 k、k+2、k+4… 帧画）</td>
     *     <td>第 k+1 帧（没画）差值 1、第 k+2 帧（画之前）差值 1</td>
     *     <td><b>永不回收</b>——最大差值就是 1，够不到 2。
     *         这正是 {@link #GRACE_GENERATIONS} 取 2 的全部理由</td>
     *   </tr>
     *   <tr>
     *     <td>第 k 帧之后不再画</td>
     *     <td>第 k+1、k+2 两帧的帧首都还没到 2；第 k+3 帧的帧首到 2</td>
     *     <td><b>在第 k+3 帧的帧首释放</b>：确确实实是"连续两帧没被画过"
     *         （第 k+1、k+2 两帧都不是它）</td>
     *   </tr>
     * </table>
     *
     * <h2>落点：由帧的所有者每帧调一次，本类不自己找时机</h2>
     * <p>本类<b>没有</b>"帧边界"这个概念（它只被 {@code draw} 与 {@code Gc.beginFrame}
     * 碰到）。所以时机由帧的所有者给：{@code Gc.beginFrame} 每帧恰好一次，且它
     * 已经懒持有本类，于是在那里调一次。本方法是 public 的<b>只因包边界</b>
     * （{@code Gc} 在 {@code renderer} 包，本类在 {@code chartrender} 包），
     * <b>不是</b>给调用方学的新 API；不要自己找地方调它，重复调只会把代推快。
     *
     * <p><b>没建过图表的应用一行都不受影响</b>：{@code Gc.charts} 是懒创建的，
     * 没被访问过时 {@code beginFrame} 里那一句根本不会执行（见 {@code Gc} 的实现），
     * 于是连本方法都不会被调到，更不会白编译那 10 个着色器程序。
     *
     * <p><b>已经释放后调用是无副作用的空操作</b>：那时三张表都空了，
     * "没有东西可回收"就是正确答案。这里<b>不抛</b>——本方法会被帧循环调用，
     * 而 GL 线程上的异常在本项目是静默吞掉的，一个为"资源已释放"而抛的异常
     * 只会把一次干净的关停变成一次没有报告的冻结。
     *
     * <h2>★ 一条没有验证过的后果：在途的异步拾取会解析成 null</h2>
     * <p>回收会 {@code pickRegistry.unregister(id)}，而在途的异步拾取
     * （{@code pickAsync} / {@code clickAsync}：双 PBO + fence，请求提交之后要到
     * <b>后续帧</b>才从 PBO 读回并经 {@code Platform.runLater} 回调）若正好落在
     * <b>释放的那一两帧内</b>，回调拿到的 {@code PickHit} 会是 {@code payload == null}。
     *
     * <p>这条路本身是自洽的——{@code PickHit} 的文档里已经写明
     * "ID 已注册但载荷为 null 与未注册，都表现为 null"——但用户看到的是
     * <b>"点了一下没反应"</b>。
     *
     * <p><b>★ 照实说：这一条没有验证过</b>（没有断言、也没有实测）。它的窗口很小
     * （要么正好在释放的那一帧提交、要么在下一帧读回），而且回收的前提本身就是
     * "<b>这个系列连续两帧没被画过</b>"——也就是说用户<b>已经不在看它</b>了。
     * 写在这里是为了下一个人不必重新推一遍；真要验它，需要构造出"停止画某系列"与
     * "点它"在两帧内先后发生，而在现有校验器的几何上不好表达。
     */
    public void releaseUnused() {
        if (disposed) {
            return;
        }
        // 遍历 lastSeen：它与 buffers / pickIds 的键集在 draw 里同步增删，
        // 所以这一处遍历就覆盖了另外两处。两种 remove 都做 null 检查——
        // 三个键集虽然同步，但"同步"是靠纪律维持的，而漏删一个键的症状
        // 是回收之后仍有一个活着的缓冲/号悬在那里，只能靠别的实验发现。
        for (Iterator<Map.Entry<Series, Long>> it = lastSeen.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<Series, Long> entry = it.next();
            if (generation - entry.getValue() < GRACE_GENERATIONS) {
                continue;
            }
            Series series = entry.getKey();
            it.remove();
            SeriesBuffer buffer = buffers.remove(series);
            if (buffer != null) {
                buffer.dispose();
            }
            Integer id = pickIds.remove(series);
            if (id != null) {
                // 注销是**承重**的，不是顺手清理：注册表对 payload 是强引用，
                // 不注销的话被移除的 Series 会一直被引用着——而它早就不在画面上了。
                pickRegistry.unregister(id);
            }
        }
        generation++;
    }

    /**
     * 返回当前缓存的 GPU 常驻缓冲数（= 被本渲染器认作"画过且还没回收"的系列数）。
     *
     * <h2>它为什么存在</h2>
     * <p>{@link #releaseUnused} 的成效在画面上<b>没有任何痕迹</b>——回收只发生在
     * "不再画了"之后，而那时画面上本来就没有它。于是"回收真的发生了"与
     * "每帧泄漏一块缓冲"这两件事的<b>画面逐像素相同</b>，任何像素断言都分不开它们。
     * 没有这个入口，那条断言只能退化成"跑完没崩"（而不回收在号耗尽之前也不会崩）。
     *
     * <p>与 {@link #takeUploadedBytes}、{@code RenderBatch.pickPassCount()} 是同一类东西：
     * <b>为一个断言而存在的观测口</b>，生产代码不该依赖它。它只是
     * {@code buffers.size()} 的透传，不含任何算术。
     *
     * @return 当前持有的缓冲数；从没画过任何系列时为 0
     */
    public int cachedBufferCount() {
        return buffers.size();
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
     *       {@code drawsMarkers()} 也为真，但 {@code connectsSamples()} 同时为真。
     *       它归折线渲染器（那边画完折线再调一次散点渲染器的"无守卫"入口画标记点），
     *       少了这条判断它会走散点渲染器，于是折线整条消失、只剩一串点——
     *       而"只有点"看起来像一种刻意的风格。</li>
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
     *
     * <p>{@link #lastSeen} 也一并清空：它虽然不持 GL 资源，但它对本类的
     * {@code Series} 是<b>强引用</b>，留着会让"已经 dispose 的渲染器"继续把
     * 那批对象钉在堆上。清空之后 {@link #releaseUnused} 就没有名单可遍历了
     * （它本来也已经是空操作，见 {@link #disposed} 那一层）。
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
        lastSeen.clear();
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
