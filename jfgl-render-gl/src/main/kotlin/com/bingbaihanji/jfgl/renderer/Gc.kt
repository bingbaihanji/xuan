package com.bingbaihanji.jfgl.renderer

import com.bingbaihanji.jfgl.chart.ChartLayout
import com.bingbaihanji.jfgl.chartrender.ChartPainter
import com.bingbaihanji.jfgl.chartrender.ChartRenderer
import com.bingbaihanji.jfgl.geom.Flattener
import com.bingbaihanji.jfgl.geom.Path
import com.bingbaihanji.jfgl.geom.StrokeGenerator
import com.bingbaihanji.jfgl.geom.Tessellator
import com.bingbaihanji.jfgl.math.Mat3
import com.bingbaihanji.jfgl.text.GlyphSlot
import com.bingbaihanji.jfgl.text.TextLayout
import com.bingbaihanji.jfgl.util.Rect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 立即模式的 2D 绘制上下文，对外提供类似 Canvas 的 API。
 *
 * <p>所有绘制调用只往顶点缓冲追加数据，**不做任何 GL 调用**；真正提交发生在 [endFrame]。
 * 因此整个 `onRender { }` 回调期间没有任何一次 GL 状态切换，
 * 整帧的 draw call 数量取决于状态切换次数（见 [VertexWriter] 的合批规则）。
 *
 * <p>坐标系统为**像素、原点左上、y 向下**，与 HTML Canvas 一致：
 * `translate`/`scale`/`rotate` 的角度以度为单位，正值在屏幕上是顺时针。
 *
 * ## 一帧的生命周期
 * ```
 * gc.beginFrame(w, h)   // 重置变换/裁剪/样式栈与顶点写入器，设置视口高度
 * gc.fillRect(...)      // 尽可能多地记录图元
 * gc.endFrame()         // 一次性提交；缓冲区中途写满时会自动提交一次再继续
 * ```
 * [beginFrame] 与 [endFrame] 必须严格配对：重复 [beginFrame]、或未经 [beginFrame] 就 [endFrame]
 * 都会抛出消息说明问题的 [IllegalStateException]，而不是安静地画出错误结果。
 *
 * ## 线程
 * 本类**不是线程安全的**，且必须在 GL 线程上使用（[RenderBatch] 只允许在 GL 线程调用）。
 *
 * ## 坐标数学在哪里
 * 像素→NDC 的基础矩阵、变换栈、裁剪栈及全部单位换算都在 [ViewTransform] 里，
 * 那是一个不依赖 GL 的纯数学类，可以脱离窗口单测；本类只负责状态管理、几何准备（平坦化/三角化/描边）
 * 与逐图元发射。
 *
 * ## 几何在哪里
 * 曲线平坦化、填充三角化、描边轮廓生成都在 [com.bingbaihanji.jfgl.geom] 包里，
 * 那是另一个零 GL 依赖、可脱离窗口单测的包。本类只按当前变换的缩放因子
 * （见 [matrixScale]）告诉它们"一个用户单位等于多少设备像素"，然后把结果按 [currentMatrix]
 * 烘焙成 NDC 顶点。
 *
 * ## 一帧内的多次提交
 * 顶点缓冲写满时 [VertexWriter] 会置位 flush 请求，本类在**每个图元之前**检查并
 * 中途提交一次（见 [flushIfNeeded]）。因此单帧的 draw call 数仍是个位数级别，
 * 与图元数量无关。
 */
class Gc constructor(private val batch: RenderBatch) {

    /** 顶点收集器。整帧的绘制结果都在这里，[endFrame] 时才交给 [RenderBatch]。 */
    private val writer = VertexWriter(INITIAL_VERTEX_CAPACITY)

    /** 纯坐标状态：基础矩阵、变换栈、裁剪栈。 */
    private val state = ViewTransform()

    /** 当前填充样式（ARGB 打包）。 */
    var fill: Int = 0xFFFFFFFF.toInt()

    /** 当前描边样式（ARGB 打包）。 */
    var stroke: Int = 0xFF000000.toInt()

    /** 当前线宽（用户坐标单位）。 */
    var lineWidth: Float = 1f

    /** [dashPattern] 的后备字段。**内部读写一律走它**，理由见 [save] / [restore]。 */
    private var dashPatternValue: FloatArray? = null

    /**
     * 虚线模式：**偶数下标是实线长度、奇数下标是空白长度**（用户坐标单位），
     * `null` 表示实线（默认）。任意长度，`StrokeGenerator` 本来就支持。
     *
     * <pre>
     * gc.dashPattern = floatArrayOf(6f, 4f)          // 6 实 4 空
     * gc.dashPattern = floatArrayOf(8f, 3f, 2f, 3f)  // 长划-点
     * gc.dashPattern = null                          // 回到实线
     * </pre>
     *
     * <h2>它是状态字段，进 save/restore 栈</h2>
     * <p>与 [lineWidth] / [antialias] / [fontSize] 并列。**做成字段而不是给每个形状加一个
     * `strokeXxxDashed` 重载**，是因为本类所有描边入口
     * （[strokePath] / [strokePolyline] / [strokeRect] / [strokeCircle] / [strokeEllipse] /
     * [drawLine]）**都汇进同一个 `strokeOutline`** ⇒ 一个字段就让它们**全部**支持虚线，
     * 而重载要加六份、还会漏（漏掉的那个不会报错，只是一条实线）。
     * 这也与 HTML Canvas 的 `setLineDash` 同一个形状。
     *
     * <h2>setter 会**拷贝**，这不是防御性编程</h2>
     * <p>不拷的话，调用方在设置之后改自己的那个数组，会**静默改掉已经压进
     * save/restore 栈里的历史状态**——`restore()` 恢复出来的就不是当时那个模式了，
     * 而画面只是"虚线看起来不太对"。拷贝之后栈里那个数组再无写者。
     *
     * <h2>校验：非空、每项有限且 ≥ 0、总和 &gt; 0</h2>
     * <p>与 `AxisStyle` 同一条口径。全零模式在 `StrokeGenerator` 那里是"没有任何实线段"
     * ——画面上**什么都没画**，而那与"用户把描边关掉了"逐像素相同，属于本仓库最防的
     * 静默错误输出，所以在入口就拒掉而不是让它静默消失。
     *
     * @throws IllegalArgumentException 空数组、含 NaN/±Infinity、含负数、或总和为 0 时
     */
    var dashPattern: FloatArray?
        get() = dashPatternValue
        set(value) {
            if (value == null) {
                dashPatternValue = null
                return
            }
            if (value.isEmpty()) {
                throw IllegalArgumentException(
                    "虚线模式不能是空数组（要实线请设 null，要隐藏整条线请别画它）")
            }
            var total = 0f
            for (i in value.indices) {
                val v = value[i]
                if (!v.isFinite() || v < 0f) {
                    throw IllegalArgumentException(
                        "虚线模式的第 $i 项必须是有限的非负数（单位是用户坐标长度），实际为 $v。" +
                                "负数会让 StrokeGenerator 沿弧长往回走，" +
                                "而 NaN 会让整条虚线静默消失。")
                }
                total += v
            }
            if (!(total > 0f)) {
                throw IllegalArgumentException(
                    "虚线模式的总长必须 > 0：全零模式在渲染侧是「没有任何实线段」⇒ " +
                            "画面上什么都不出现，而那与「这条线不存在」逐像素相同。")
            }
            dashPatternValue = value.copyOf()
        }

    /**
     * 虚线模式的**起始相位**（弧长，用户坐标单位），默认 0。
     *
     * <p>它决定"从模式的第几个位置开始画"，用来做**流动的虚线**——每帧把它加上一点，
     * 看上去就是虚线在沿着线跑（这是它唯一的用途，也是它必须能被逐帧改动的原因）。
     * 值会自动归一到 `[0, 模式总长)`，所以负相位与超过一圈的相位都是合法的。
     *
     * <p>没有设 [dashPattern] 时它**完全不起作用**（实线没有相位可言），
     * 但那不是错误配置——先设相位再设模式是自然的写法。
     *
     * @throws IllegalArgumentException 非有限数时。**NaN 必须挡在入口**：
     *         它会一路传成 NaN 的顶点坐标（`consumed` 也被污染），
     *         而 NaN 顶点在光栅化阶段让整个图元**静默消失**——
     *         与"用户把这条线设成不画"在画面上逐像素相同。
     */
    var dashPhase: Float = 0f
        set(value) {
            if (!value.isFinite()) {
                throw IllegalArgumentException(
                    "虚线相位必须是有限数（NaN 会传成 NaN 顶点 ⇒ 整条线静默消失），实际为 $value")
            }
            field = value
        }

    /**
     * 是否为后续**描边**开启解析式抗锯齿。**默认关。**
     *
     * <p>它进 [save] / [restore] 栈，与 [lineWidth] 同构 ⇒ 可以只给某一条线开，
     * 也可以 `save` 内开、`restore` 关。
     *
     * <p><strong>默认关是刻意的</strong>：开了之后描边边缘会多出半透明像素，
     * 而本仓库的像素校验器里有一批**精确到 ±0 的期望值**（如蓝圆角矩形描边 = 3084 px），
     * 它们花了整轮才立住。默认关 ⇒ 默认路径逐像素不变。
     *
     * <p><strong>★ 它有两个消费者，不是一个：</strong>
     * <ol>
     *   <li><b>本类自己的描边</b>（[strokeOutline] / [emitTriangles]）——
     *       <b>只影响描边</b>：`Gc` 自己的填充与文本不受影响（它们本来就写 `aEdge = (0,0)`）；</li>
     *   <li><b>图表系列</b>（Task 5 接上的一路：**六个**渲染器——折线 / 散点 / 阶梯 /
     *       面积 / 柱状 / 频谱，与第 1 条合计七路消费者）：经 [chartsLazy] 里那个
     *       supplier 传给 `ChartRenderer`，再由这六个各自作为 `uAntialias`
     *       传给片段着色器。**这一路里包含填充**（面积图的填充、柱状图的柱）
     *       与散点的标记点（`LINE_AND_MARKERS` 的那一半走的就是散点渲染器）
     *       ——所以"只影响描边"这句话**只对第 1 条成立**。</li>
     * </ol>
     * <p>于是一个应用想给**图表数据系列**开 AA，**设的就是这个属性**；
     * 不要另加一个"图表专用的 AA 开关"——同一件事摊成两处，迟早一处开一处没开，
     * 而画面上只表现为"某些图型没那么细腻"。
     *
     * <p>实现上第 1 条落在两处 CPU 侧：几何是否外扩 1 个设备像素（[strokeOutline]），
     * 以及顶点里写不写真实边距（[emitTriangles]）；**那条路径的片元那一侧没有 uniform**
     * ——见 [RenderBatch] 的 FRAGMENT_SHADER：填充/文本与描边靠 `fwidth == 0` 区分。
     * 第 2 条（图表）**恰好相反**：顶点几何完全不动，开关就是一个 per-draw 的
     * `uAntialias`（一个系列一条 draw call，不涉及合批）。两条路的实现选择不同，
     * 别把其中一条的说明套到另一条上。
     *
     * <p><strong>★ 一个必须写下来的副作用：开了 AA 之后，描边的可拾取范围比可见线宽
     * 大 1 个设备像素。</strong>ID pass 复用同一份顶点缓冲，而那份几何被横向加宽过
     * ⇒ 拾取如实跟着几何走。判定为**不是缺陷**，理由是它与本管线已声明的两条口径一致：
     * 「拾取只由几何决定，与颜色和透明度无关」以及「全透明图元照样能命中」。
     * 而且最外那一圈本来就半可见（覆盖率 0..0.5），所以"点在看得见的线外一点点也算命中"
     * 并不算凭空多出来的热区。
     *
     * <p><strong>不要去"修"它</strong>（例如让 ID pass 用未加宽的几何）：那会让**画面与拾取
     * 互相矛盾**——看得见的像素点不中、点得中的像素看不见，比现在这种"一起宽了 1 像素"
     * 糟得多。`PickVerifier` 跑的是 AA 关的路径，所以这条**目前没有任何断言盖着**，
     * 它是"已声明的行为"，不是"已验证的行为"。
     *
     * <p><strong>★ 已声明的降级（不是缺陷）：非等比缩放下，缩放较小的那条轴**真外缘之外**的
     * 羽化被切掉。</strong>
     * 外扩量 {@code px} 取的是 [matrixScale] —— 那是<strong>两个轴的平均</strong>
     * （两轴长度相加除以二），于是"1 个设备像素折成的局部单位数"对整个坐标系只有一个值。
     * `gc.scale(1f, 10f)` 下 {@code matrixScale() = 5.5} ⇒ {@code px = 2/11}：
     * <ul>
     *   <li><strong>缩放为 1 的那条轴</strong>：实际只外扩 `2/11 × 1 ≈ 0.18` 个设备像素
     *       ⇒ 真外缘之外那半个像素里没有片元 ⇒ 覆盖率推不到 0
     *       ⇒ **外缘之外那一档（覆盖率 0.25 那圈）被切掉**；</li>
     *   <li><strong>缩放为 10 的那条轴</strong>：`2/11 × 10 ≈ 1.8` 个设备像素
     *       ——多出来的几何自己淡出，无害。</li>
     * </ul>
     *
     * <p><strong>⚠️ 措辞要把住：这不叫"退化成硬边"。</strong>被切掉的只是**外缘之外**那一档；
     * 带内的羽化照常画出来——`gc.scale(1f, 10f)` 下竖线的实测剖面是
     * `98:#CDCDCD 99:#FFFFFF 100:#FFFFFF 101:#FFFFFF 102:#333333`，那个 `#CDCDCD`
     * 就是覆盖率 0.75 的过渡像素；而**真正硬边**的 AA 关对照在同位置是**全白**。
     * 把这两件事说成一件事，下一个人会照着"硬边"去查错方向。
     *
     * <p>方向是<strong>单向</strong>的：只会让某条轴的羽化**变弱**，切掉的量恰好是
     * `0.5 − px × 该轴缩放` 个设备像素的覆盖（`gc.scale(1f, 10f)` 的压缩轴上是
     * `0.5 − 2/11 ≈ 0.318`；等比缩放下 {@code px} 就等于该轴的一个设备像素 ⇒
     * 切掉量为 0，**没有降级**。<strong>上界是 0.5</strong>，即最多把整个外缘之外的斜坡切光）。
     * <strong>不会</strong>画错形状、也不会多画墨量。要真正修好得让
     * {@code px} 变成二维（每轴各一个），那是新特性，不在本期。
     *
     * <p><strong>由 `PipelineVerifier` 的非等比缩放探针钉住方向</strong>（帧号
     * {@code ANISO_FRAME}）：{@code gc.scale(1f, 10f)} 下画一竖一横两条线，
     * 断言<strong>压缩轴（x）真外缘之外一个片元都没有</strong>、而<strong>拉伸轴（y）真外缘
     * 之外有片元</strong>。两条互为对照——只看第一条的话，"AA 整条没生效"也满足它。
     * 与"旋转裁剪退化成包围盒"那条同类：都是"底层手段按定义只有一个标量/只能取轴对齐"
     * 带来的、**方向单向**的降级。
     *
     * <p><strong>照实说这条探针的证据强度</strong>：
     * <ul>
     *   <li>它只钉<strong>方向</strong>，**不钉数值**——没有断言"切掉了 0.318 个设备像素的覆盖"
     *       这类量（上面的 `0.5 − px × 该轴缩放`），因为它还取决于相位；</li>
     *   <li>它的两个相位是<strong>挑过的</strong>：覆盖率斜坡只有 1 个设备像素宽，而像素中心
     *       间距也是 1 ⇒ "真外缘之外有没有片元"是<strong>相位决定</strong>的，
     *       竖线取 `k + 0.25`（真外缘 2.0 之外的最近一档是 2.25，而几何止于 2.1818 ⇒ 没有片元），
     *       横线取 `k + 0.025`（乘 10 之后是 `k + 0.25`，真外缘 20.0 之外的最近一档是 20.25
     *       ⇒ 有片元）。**挪动线的位置就要重新核这两个相位**，否则判据会因为相位而不是
     *       因为实现失败；</li>
     *   <li>恒等变换下的路径（七个校验器的其余部分）与这条无关，各测各的。</li>
     * </ul>
     */
    var antialias: Boolean = false

    /** 全局不透明度（0-1），与样式颜色相乘。 */
    var globalAlpha: Float = 1f

    /**
     * 当前字号（像素），默认 16f。
     *
     * <p>它和 [fill]、[stroke] 一样属于绘制状态，会被 [save] / [restore] 存取；
     * 进的是样式栈的**浮点**部分。**改样式栈的每层宽度时必须同步改三处**，
     * 见 [save] 的说明。
     *
     * <p>字号只影响 [drawText] / [measureText]：字形本身是在固定的 em 尺寸下
     * 光栅化的（[com.bingbaihanji.jfgl.text.GlyphRasterizer.EM_SIZE]），
     * 字号只是发射顶点时的一个缩放因子，因此改字号不会触发任何重新光栅化。
     */
    var fontSize: Float = 16f

    /**
     * 当前拾取 ID。**0 表示不参与拾取**（默认）。
     *
     * <p>取值来自 [pickRegistry] 的分配结果，或由调用方自行指定的任意非零整数。
     * 与 [fill]、[stroke] 一样属于绘制状态，会被 [save] / [restore] 存取。
     */
    var pickId: Int = 0

    /**
     * 拾取 ID 的分配器与 `id → 对象` 映射表。
     *
     * <p>命中时 [pick] 会用它把 ID 解析回注册时给的对象，组件因此不必各自维护映射表。
     * 注册发生在**数据变化时**而非每帧；不再需要的对象必须 `unregister`，否则会一直
     * 被强引用着。
     *
     * <p>**`pickRegistry` 本身是线程安全的**（见 [PickRegistry] 的类文档），
     * 所以可以、也应当在 JavaFX 线程上随数据变化直接调 `register` / `unregister`，
     * 不必像 [pick] 那样跳线程——把注册也塞进 `onFrame` 是过度设计。
     * 注意 `Gc` 的**其余部分**仍然只能在 GL 线程用，这个 `val` 是刻意的例外。
     *
     * <p>由此产生的两个可见后果都是规格内的，**不是缺陷**，消费方别当 bug 去"修"：
     * 刚注册的对象当帧可能还没被画出来（差一帧）；刚注销的对象当帧可能仍被画着，
     * 于是命中 `PickHit(id, null, ...)`——这正是 [PickHit] 文档里
     * 「ID 已注册但载荷为 null，与 ID 未注册，都表现为 null」那一条。
     */
    val pickRegistry = PickRegistry()

    /**
     * 图表绘制入口，懒创建。
     *
     * <p>第一次访问时才建（它要编译两个着色器程序、建 VAO，不该让不用图表的应用
     * 白付这份开销）。生命周期与 [RenderBatch] 一致。
     *
     * <p>它持有 GL 资源，而 [RenderBatch] 不认识它（[ChartRenderer] 在更上层的包里），
     * 所以释放由 [disposeCharts] 转一手，调用方是 `FXGLTransfer` 的 `onDispose`。
     *
     * <p><b>不再画的系列会自己走。</b>[beginFrame] 每帧调一次
     * [ChartRenderer.releaseUnused]，把连续两帧没被画过的系列释放掉
     * （缓冲删掉、拾取号归还）。所以"每帧重建 `Chart`"（= 每帧 new 出新的 `Series`）
     * 是安全的写法——**没有 `retain` / `release` 这类要记得调的 API**，
     * 理由与逐步的算例见 `ChartRenderer.releaseUnused` 的文档。
     *
     * <p>两个依赖都是**刻意从这里传进去**的：图表的拾取因此与普通图元共用同一套
     * ——[pickRegistry]（ID 空间只有一本，两本会撞号）与 `batch::withPickPass`
     * （拾取缓冲只有一块）。于是 [pick] / [pickRect] 对数据系列同样有效，
     * 命中的 `payload` 就是那个 `Series` 对象。
     *
     * <p>典型用法（z 序：网格 → 数据 → 标注）：
     * ```
     * gc.beginFrame(w, h)
     *   画网格
     * gc.flush()                                  // 网格落定
     * gc.charts.draw(chart, plotRect, gc.width, gc.height)
     *   画刻度文字
     * gc.endFrame()
     * ```
     */
    private val chartsLazy = lazy {
        // 它还多收一个依赖（尾随 lambda）：数据系列的 AA 开关就是本类的 [antialias]。
        // 传 supplier 而不是当下的值——图表后端由本类懒创建、长期持有，
        // 存值会把"创建那一刻的开关"固化下来（见 ChartRenderer 的 antialias 字段）。
        ChartRenderer(batch.glAbstraction(), pickRegistry, batch::withPickPass,
            chartPainter) { antialias }
    }

    /** 图表绘制入口。见 [chartsLazy]。 */
    val charts: ChartRenderer by chartsLazy

    /**
     * 图表装饰（标题、图例）的那支笔：把 [ChartPainter] 转发到本类的方法上。
     *
     * <p>四个方法没有一个有自己的算术——宽度问 [measureText]、文字问 [drawText]、
     * 色块问 [fillRect]、状态交给 [save] / [restore]。**这样安排的目的是让
     * "装饰画在哪"完全由 `ChartLayout`（纯计算）决定**，这里只剩转发；
     * 一旦这里也开始算坐标，那份算术就没有单测能覆盖了。
     *
     * <p>三条实现细节：
     * - [ChartPainter.begin] 里除了 [save] 还把 [pickId] 置 0：图例是装饰不是数据，
     *   点在色块上不该命中一个系列（`save/restore` 会把调用方原来的 ID 还回去）。
     * - [ChartPainter.lineHeight] 返回 `字号 × ChartLayout.LINE_HEIGHT_FACTOR`，
     *   **不是**字体的真实行高——布局要可被精确预测，理由见 `ChartTextMetrics`。
     * - [ChartPainter.begin] 之后变换仍是调用方当前的那个。布局算的是设备像素，
     *   所以调用 `drawChart` 时不该带着变换（这一点写在 `ChartPainter` 的类文档里）。
     */
    private val chartPainter = object : ChartPainter {
        override fun begin(band: Rect) {
            // 契约（ChartPainter 的类文档第 4 条）在这里被真正强制：布局算出来的是
            // **设备像素**，带着变换去调 drawChart 会让装饰落到布局没算过的位置上。
            // 之前这条只写在文档里——而"装饰偏了几像素"看起来只是字号或间距的问题，
            // 没人会去读那份文档。判据是「有没有被动过」，不是「矩阵等不等于基础矩阵」
            // （见 ViewTransform.isBaseTransform 的说明）。
            check(state.isBaseTransform()) {
                "图表装饰的布局算在**设备像素**空间，调用 drawChart 时不能带着变换：" +
                    "带着 translate/scale/rotate 的话，装饰会落到 ChartLayout 没算过的位置上" +
                    "（标题带、图例色块与绘图区的相对位置全错），而画面看起来只是" +
                    "\"间距不太对\"。请在调用前 restore() 回到基础变换，" +
                    "或者改用低层的 draw(chart, plotRect, w, h) 自己排布绘图区。"
            }
            this@Gc.save()
            pickId = 0
            // 带子就是这一层的边界（契约第 2 条）：一项文字比带子宽时，
            // 裁掉之后是"在带子边缘被切断"，而不是画到隔壁去。clipRect 与调用方
            // 原本的裁剪**求交**，所以"图表画在一块被裁过的区域里"仍然成立。
            // 它会自动吸附到整像素，见 ViewTransform.clipRect。
            this@Gc.clipRect(band.x, band.y, band.width, band.height)
        }

        override fun end() {
            this@Gc.restore()
        }

        override fun width(text: String, fontSize: Float): Float {
            // measureText 用的是**当前字号**，所以临时改一下再量；调用方保证已经
            // 在 begin/end 之间，这里不必自己压栈（量的过程不发顶点、不改画面）。
            val saved = this@Gc.fontSize
            this@Gc.fontSize = fontSize
            val w = this@Gc.measureText(text)
            this@Gc.fontSize = saved
            return w
        }

        override fun lineHeight(fontSize: Float): Float =
            fontSize * ChartLayout.LINE_HEIGHT_FACTOR

        override fun drawText(text: String, x: Float, y: Float, fontSize: Float, argb: Int) {
            val savedColor = this@Gc.fill
            val savedSize = this@Gc.fontSize
            this@Gc.fontSize = fontSize
            this@Gc.fill = argb
            this@Gc.drawText(text, x, y)
            this@Gc.fontSize = savedSize
            this@Gc.fill = savedColor
        }

        override fun fillRect(x: Float, y: Float, width: Float, height: Float, argb: Int) {
            val saved = this@Gc.fill
            this@Gc.fill = argb
            this@Gc.fillRect(x, y, width, height)
            this@Gc.fill = saved
        }

        override fun strokeLine(x1: Float, y1: Float, x2: Float, y2: Float,
                                width: Float, argb: Int) {
            val savedColor = this@Gc.stroke
            val savedWidth = this@Gc.lineWidth
            this@Gc.stroke = argb
            this@Gc.lineWidth = width
            this@Gc.drawLine(x1, y1, x2, y2)
            this@Gc.lineWidth = savedWidth
            this@Gc.stroke = savedColor
        }

        override fun strokeDashedLine(x1: Float, y1: Float, x2: Float, y2: Float,
                                      width: Float, dashLength: Float, argb: Int) {
            val dx = x2 - x1
            val dy = y2 - y1
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            if (length <= 0f) return
            // ★ 这个守卫是**防死循环**的，不是防"画得难看"：
            // `dashLength <= 0f` 时下面 `distance + dashLength` 恒等于 `distance`，
            // `while (distance < length)` **永不退出** ⇒ GL 线程整个卡死、帧不再提交、
            // 画布永久冻结，而且没有任何报错（GL 线程上的异常在本项目本来就是没人接的）。
            // `NaN` 同样挡得住：`NaN <= 0f` 为 false，所以判据写成 `!(dashLength > 0f)`
            // 而不是 `dashLength <= 0f`——后者漏掉 NaN，而 NaN 会让循环同样退不出去。
            //
            // 今天走不到这里：唯一的调用方 `ChartRenderer.drawInteraction` 传的是
            // `ChartInteractionConfig.dashLength()`，而它的紧凑构造器强制了"有限且 > 0"。
            // 但本方法是**公开接口** `ChartPainter` 上的一个成员，`Gc` 这一侧没有任何守卫
            // ——下一个实现/调用方踩到就是整个画布挂死，代价与预防成本差得太远。
            if (!(dashLength > 0f)) return
            val ux = dx / length
            val uy = dy / length
            var distance = 0f
            var draw = true
            val savedColor = this@Gc.stroke
            val savedWidth = this@Gc.lineWidth
            this@Gc.stroke = argb
            this@Gc.lineWidth = width
            while (distance < length) {
                val next = minOf(distance + dashLength, length)
                if (draw) {
                    this@Gc.drawLine(x1 + ux * distance, y1 + uy * distance,
                        x1 + ux * next, y1 + uy * next)
                }
                distance = next
                draw = !draw
            }
            this@Gc.lineWidth = savedWidth
            this@Gc.stroke = savedColor
        }

        override fun strokeRect(x: Float, y: Float, width: Float, height: Float,
                                lineWidth: Float, argb: Int) {
            val savedColor = this@Gc.stroke
            val savedWidth = this@Gc.lineWidth
            this@Gc.stroke = argb
            this@Gc.lineWidth = lineWidth
            this@Gc.strokeRect(x, y, width, height)
            this@Gc.lineWidth = savedWidth
            this@Gc.stroke = savedColor
        }
    }

    /**
     * 释放图表后端持有的 GL 资源；从未用过图表时什么都不做。
     *
     * <p>**不在 [RenderBatch.dispose] 里调用**：那是下层，不认识上层的 [ChartRenderer]。
     * 由 `FXGLTransfer.onDispose` 在 `renderBatch.dispose()` **之前**调用。
     * 顺序反了不会立刻炸（两边的 GL 资源互不引用），但"下游先释放"是这条链唯一
     * 说得通的次序，没有理由写成反的。
     */
    fun disposeCharts() {
        if (chartsLazy.isInitialized()) {
            chartsLazy.value.dispose()
        }
    }

    /** 样式栈的整数部分：每层 [INTS_PER_STYLE_LEVEL] 个值（fill、stroke、pickId、antialias）。 */
    private var styleInts = IntArray(INITIAL_STACK_LEVELS * INTS_PER_STYLE_LEVEL)

    /** 样式栈的浮点部分：每层 [FLOATS_PER_STYLE_LEVEL] 个值
     * （lineWidth、globalAlpha、fontSize、dashPhase）。 */
    private var styleFloats = FloatArray(INITIAL_STACK_LEVELS * FLOATS_PER_STYLE_LEVEL)

    /**
     * 样式栈里**引用**那一部分：每层一个虚线模式（`null` = 实线）。
     *
     * <p><strong>为什么它不进 `styleFloats`</strong>：模式是**变长**的
     * （`[实, 空]` 两格、`[实, 空, 实, 空]` 四格…），塞不进定长槽位。
     * 而把它**摊平成固定长度**（比如只支持两格）会砍掉这个能力的一半——
     * `StrokeGenerator` 本来就支持任意长度。
     *
     * <p>压栈压的是**引用**，不是拷贝：模式对象在 [dashPattern] 的 setter 里就已经
     * 拷贝过一次（那是必需的，见那里的说明），此后不再有人能改到它。
     * 于是 [save] 在稳态下**依旧零分配**——只多一次引用写。
     */
    private var styleDashPatterns = arrayOfNulls<FloatArray>(INITIAL_STACK_LEVELS)

    /**
     * 样式栈层数。
     *
     * <p>必须与 [ViewTransform.stackDepth] 始终相等——[save] 与 [restore] 是唯一改动它们的地方，
     * 每次都是成对压入/弹出。之所以不像变换与裁剪那样塞进 [ViewTransform]：
     * 那些是坐标数学，样式不是；而拆成两个类以后，保持同步的成本仅仅是在同一对方法里各动一次。
     */
    private var styleDepth = 0

    /** 本帧是否处于 [beginFrame] 与 [endFrame] 之间。 */
    private var frameActive = false

    // ------------------------------------------------------------------
    // 帧
    // ------------------------------------------------------------------

    /**
     * 开始一帧：设定视口尺寸、把变换重置为像素→NDC 的基础矩阵、清空全部状态栈与顶点缓冲，
     * 并让图表后端回收上一帧起就没再画过的系列。
     *
     * <h3>图表资源的自动回收挂在这里，而且只有这里</h3>
     * <p>[ChartRenderer.releaseUnused] 需要一个"帧首"时机，而本方法是全项目**唯一**
     * 每帧恰好一次的位置（`FXGLTransfer` 每帧配对调 `beginFrame`/`endFrame`）。
     * 挂在这里意味着调用方不必学 `retain`/`release` 那套 API：
     * **不再画的系列自己会被释放**（详见 `ChartRenderer` 的类文档）。
     *
     * <p>`isInitialized()` 那一层判断是**承重的**，不是省一次调用的微优化：
     * 少了它，每一个不用图表的应用都会在这一行把 [ChartRenderer] 建出来
     * ——那要编译 10 个着色器程序并建 VAO，而 `charts` 本来是懒创建的，
     * 那份开销正是懒创建要避免的。
     *
     * @param width  帧缓冲宽度（像素），必须为正
     * @param height 帧缓冲高度（像素），必须为正
     * @throws IllegalStateException    上一帧还没有 [endFrame] 时
     * @throws IllegalArgumentException 宽或高不为正时
     */
    fun beginFrame(width: Int, height: Int) {
        check(!frameActive) { "beginFrame 已调用过：beginFrame 与 endFrame 必须配对，不能嵌套" }
        // 放在最前面：它要删 VBO，而这一行之前没有任何本帧的 GL 状态被设置过。
        if (chartsLazy.isInitialized()) {
            chartsLazy.value.releaseUnused()
        }
        state.beginFrame(width, height)
        styleDepth = 0
        writer.reset()
        batch.beginFrame(width, height)
        frameActive = true
    }

    /**
     * 结束一帧并把本帧收集到的全部顶点一次性提交。
     *
     * <p>提交后 GL 状态回到中性（见 [RenderBatch.submit]），因此可以在下一帧直接继续画。
     *
     * <p>若本帧有 [save] 没有配对的 [restore]，本方法仍然会先提交这一帧（用户能看到画面），
     * 随后清空状态栈并抛出 [IllegalStateException]。选择抛而不是安静地兜底，
     * 是因为漏掉 [restore] 会让栈无限增长并污染后续每一帧，属于必须暴露的缺陷。
     *
     * @throws IllegalStateException 未经 [beginFrame] 就调用，或本帧 [save]/[restore] 不配对
     */
    fun endFrame() {
        check(frameActive) { "endFrame 在 beginFrame 之前调用：beginFrame 与 endFrame 必须配对" }
        var submitFailure: Throwable? = null
        var unbalanced = 0
        try {
            batch.submit(writer)
        } catch (failure: Throwable) {
            submitFailure = failure
        } finally {
            frameActive = false
            writer.reset()
            unbalanced = state.clearStack()
            styleDepth = 0
        }
        submitFailure?.let { throw it }
        check(unbalanced == 0) { "save() 与 restore() 不配对：本帧结束时仍残留 $unbalanced 层 save()" }
    }

    /** 放弃当前帧并恢复到可继续渲染的状态，供 JavaFX 桥接层处理回调异常。 */
    fun abortFrame() {
        if (!frameActive) return
        frameActive = false
        writer.reset()
        state.clearStack()
        styleDepth = 0
    }

    /**
     * 帧内提交点：把到目前为止积累的顶点立刻提交掉。
     *
     * <p><strong>它是为 z 序存在的。</strong>本类的图元默认攒到 [endFrame] 才一次性提交，
     * 而图表的数据系列是<strong>当场就画</strong>的。没有这个方法，数据系列就只能整个
     * 画在 `Gc` 内容之上或之下，"网格 → 数据 → 标注"这种夹心顺序做不到。
     *
     * <pre>
     * gc.beginFrame(w, h)
     *   画网格
     * gc.flush()                                      // 网格落定
     * gc.charts.draw(chart, plotRect, gc.width, gc.height)  // 数据系列画在网格之上
     *   画刻度文字                                       // 标注画在数据之上
     * gc.endFrame()
     * </pre>
     *
     * <p><strong>调用方不需要在之后重新设状态。</strong>[VertexWriter.reset] 会把写入器
     * 带回"尚未设置状态"，而下一个图元经由 [syncState] 重新 `setState`——
     * 这条路径与缓冲区写满时的自动提交（[flushIfNeeded]）完全一致，已经跑了很多年。
     *
     * <p><strong>它会让 ID pass 多跑几趟。</strong>[RenderBatch.submit] 在本批有可拾取顶点时
     * 每次都跑一趟 ID pass，分批提交就意味着分趟拾取。这是设计使然，不是缺陷：
     * 拾取结果与画面一致（被后画的挡住的部分，拾取到的是后画的那个），
     * 与不做 flush 时的"只返回最上层"语义相同。
     *
     * <p><strong>可以在 [save] 块内调用。</strong>顶点在发射时就把变换烘焙好了、
     * 裁剪在每次 [syncState] 重新读，所以"clip 住绘图区 → 画网格 → flush → 画数据系列"
     * 这种写法是合法的，本方法不检查 save/restore 是否平衡——帧内平衡由 [endFrame] 守。
     * （曾有一道 `stackDepth > 0` 的检查，它会把这个合法写法误判成状态损坏，已删除。）
     *
     * @throws IllegalStateException 未经 [beginFrame] 就调用
     */
    fun flush() {
        check(frameActive) { "flush 在 beginFrame 之前调用：beginFrame 与 endFrame 必须配对" }
        batch.submit(writer)
        writer.reset()
    }

    // ------------------------------------------------------------------
    // 拾取
    // ------------------------------------------------------------------

    /**
     * 在给定的拾取 ID 下执行一段绘制。
     *
     * <p>等价于 `save(); pickId = id; block(); restore()`，因此**块内的变换与裁剪改动
     * 也会在块结束时回滚**——与 `save/restore` 的语义完全一致，块是自包含的。
     *
     * <p>相比手工设 [pickId]，本方法不会因为忘记复位而让后续图元错误地继承 ID——
     * 那是这类 API 最常见的 bug。
     *
     * @param id    本块内所有图元的拾取 ID，0 表示不参与拾取
     * @param block 绘制块
     */
    fun pickable(id: Int, block: () -> Unit) {
        save()
        pickId = id
        try {
            block()
        } finally {
            restore()
        }
    }

    /**
     * 查询某个点上最上层的可拾取图元。
     *
     * <p>命中的是**像素**而不是包围盒：判定用的是 GPU 实际光栅化的结果，
     * 与画面所见完全一致。
     *
     * <p>**必须在 GL 线程上调用。** 组件响应 JavaFX 鼠标事件时请用
     * `FXGLTransfer.pickAsync`，它会把请求调度到 GL 线程并把结果送回 JavaFX 线程。
     *
     * <p>拾取只由几何决定，**与颜色和透明度无关**——`globalAlpha = 0` 的图元照样能命中。
     * 图表的「隐形热区」正是靠这个行为实现的。
     *
     * @param x 查询点 x（用户坐标，y 向下）
     * @param y 查询点 y（用户坐标，y 向下）
     * @return 命中结果；未命中、坐标越界、或本帧没有任何可拾取图元时返回 null
     */
    fun pick(x: Float, y: Float): PickHit? {
        val id = batch.readPickPixel(x.toInt(), y.toInt())
        if (id == 0) {
            return null
        }
        return PickHit(id, pickRegistry.resolve(id), x, y)
    }

    /**
     * 为 JavaFX 桥接层提交一条非阻塞 PBO 拾取请求。
     *
     * <p>这是内部桥接 API；应用代码仍应使用 `FXGLTransfer.pickAsync`。同步 [pick]
     * 保留给确实需要当前帧即时结果的 GL 线程代码。
     */
    fun enqueueAsyncPick(x: Int, y: Int, token: Long): PickBuffer.AsyncReadStatus =
        batch.enqueueAsyncPickPixel(x, y, token)

    /** 取回一条已完成的 PBO 拾取结果；未完成时不等待并返回 null。 */
    fun pollAsyncPick(): PickBuffer.AsyncReadResult? = batch.pollAsyncPickPixel()

    /**
     * 查询一个矩形区域内出现过的全部可拾取图元，按 ID 升序去重返回。
     *
     * <p>区域会与绘制区求交；完全在绘制区之外返回空列表（不抛异常——
     * 刷选拖到窗口外是正常操作）。代价与区域面积成正比（`w×h×4` 字节的读回）。
     *
     * @param x 区域左边缘（用户坐标）
     * @param y 区域上边缘（用户坐标，y 向下）
     * @param w 区域宽度
     * @param h 区域高度
     * @return 命中的结果列表，按 ID 升序；每个元素的 x/y 是该 ID 在区域内按行扫描
     *         **首次出现的像素坐标**，不是图元的几何代表点
     */
    fun pickRect(x: Float, y: Float, w: Float, h: Float): List<PickHit> {
        val pixels = batch.readPickRect(x.toInt(), y.toInt(), w.toInt(), h.toInt())
        if (pixels.isEmpty()) {
            return emptyList()
        }
        val result = ArrayList<PickHit>(pixels.size)
        for (pixel in pixels) {
            result.add(
                PickHit(pixel.id(), pickRegistry.resolve(pixel.id()),
                    pixel.x().toFloat(), pixel.y().toFloat())
            )
        }
        return result
    }

    // ------------------------------------------------------------------
    // 文本
    // ------------------------------------------------------------------

    /**
     * 绘制一行文本。
     *
     * **`(x, y)` 是基线的起点，不是文本框的左上角。**
     * `y` 是文字**基线**所在的像素行，`x` 是第一个字形的笔位置。
     * 选基线而不是左上角，是因为只有基线是排版的稳定参照——图表的刻度文字要沿轴线
     * 对齐时基线对齐才是想要的；而"左上角对齐"会让不同高度的字符视觉上跳来跳去。
     * **这条是最容易猜错、且猜错后"看起来只是位置偏了一点"的那类约定。**
     *
     * <p>文本是一类普通图元：它走**现有的**批处理管线，所以裁剪、z 序、合批、
     * GPU 拾取全部自动成立。连续的一段文本通常合并成一条 draw call。
     *
     * <p>词法上按**码点**处理（用 [Character.codePointAt]，一次跳过整个代理对），
     * 因此增补平面上的字符不会被拆成两个豆腐块。
     *
     * <p>字体里没有的码点会画成 `.notdef`（通常是个方框），**不会静默跳过**——
     * 静默跳过的表现是"这段文字少了几个字"，用户会以为是排版 bug。
     *
     * <p>空格这类没有轮廓的字形只推进笔，不发顶点，这是正常的。
     *
     * <p>**限制**：单次调用发射的顶点数（每字形 6 个）必须放得进当前顶点缓冲的剩余容量。
     * 在硬上限（[VertexWriter.MAX_VERTEX_CAPACITY] = 1<<20 顶点，约 17 万个字形）以下
     * [VertexWriter] 会自动扩容，因此实际不可达；真要画超长文本，分多次调用即可。
     *
     * @param text 文本，可以包含任意 Unicode 码点
     * @param x    起点 x（用户坐标）：第一个字形的笔位置
     * @param y    起点 y（用户坐标）：**基线**所在的像素行
     * @return 推进宽度（像素），即笔最终走到 `x + 返回值`
     */
    fun drawText(text: String, x: Float, y: Float): Float {
        if (text.isEmpty()) {
            return 0f
        }
        val glyphSource = batch.glyphSource()
        val atlas = batch.glyphAtlas()
        // 槽位里的偏移/尺寸/推进全是 em 像素，这里换成当前字号下的缩放。
        // 字号因此只是发射顶点时的一个乘法，不会触发任何重新光栅化。
        val scale = fontSize / glyphSource.pixelHeight()

        val slots = ArrayList<GlyphSlot>(text.codePointCount(0, text.length))
        var index = 0
        while (index < text.length) {
            val codepoint = text.codePointAt(index)
            // 码点不在字体里时 glyphIndex 返回 0（.notdef）——照常画它，不要跳过。
            slots.add(atlas.acquire(glyphSource.glyphIndex(codepoint)))
            // 必须一次跳过整个代理对：中文有增补平面字符，
            // 用 charAt 逐 char 走会把一个字符拆成两个豆腐块。
            index += Character.charCount(codepoint)
        }

        // 顺序与 emitTriangles 一致：先 flushIfNeeded 再 syncState——
        // reset() 会把写入器带回"尚未设置状态"，必须在它之后重新设上。
        flushIfNeeded()
        syncState(atlas.textureId(), Material.SDF_TEXT)
        return TextLayout.layout(slots, x, y, scale, packColor(fill), pickId, writer(), state)
    }

    /**
     * 量出一行文本的推进宽度（像素）。**不绘制任何东西，也不生成字形。**
     *
     * 走的是字体度量而不是图集，因此可以放心地用于布局计算——测量没有副作用，
     * 也不会因为"量了一下"就把图集填满。
     *
     * <p>与 [drawText] 的返回值**在数学上相等**（两者都是
     * `字体单位 * scaleForPixelHeight(字号)` 的累加），只差浮点舍入。
     *
     * @param text 文本
     * @return 推进宽度（像素）
     */
    fun measureText(text: String): Float {
        if (text.isEmpty()) {
            return 0f
        }
        val glyphSource = batch.glyphSource()
        var width = 0f
        var index = 0
        while (index < text.length) {
            val codepoint = text.codePointAt(index)
            width += glyphSource.advancePixels(glyphSource.glyphIndex(codepoint), fontSize)
            index += Character.charCount(codepoint)
        }
        return width
    }

    /**
     * 当前帧的绘制区宽度，单位是**设备像素**。
     *
     * <p>取值来自 [beginFrame] 收到的帧缓冲宽度，因此**可能大于**创建窗口时声明的逻辑宽度：
     * 在高 DPI 显示器上帧缓冲按缩放系数放大（125% 缩放下，800 逻辑像素对应 988 设备像素）。
     * 用户坐标与设备像素是 1:1 的，所以"铺满整屏"应当画到 `width`×`height`，
     * 而不是创建窗口时写的那个逻辑尺寸。
     *
     * <p>[beginFrame] 之前为 0。
     */
    val width: Int get() = state.viewportWidth

    /**
     * 当前帧的绘制区高度，单位是**设备像素**。含义与注意事项见 [width]。
     *
     * <p>[beginFrame] 之前为 0。
     */
    val height: Int get() = state.viewportHeight

    // ------------------------------------------------------------------
    // 状态栈
    // ------------------------------------------------------------------

    /**
     * 压入当前的**全部绘制状态**：变换、裁剪、填充色、描边色、线宽、全局不透明度、拾取 ID、字号、
     * [antialias]。
     *
     * <p>与 HTML Canvas / JavaFX 的 `save()` 语义一致——用户改完样式再 [restore] 就能回到原样，
     * 不必手工记下每一个字段。每次 [save] 在稳态下不产生任何分配：
     * 变换压入的是引用，裁剪与样式压入的是预先分配的数组，裁剪的"无裁剪"用整个帧缓冲表示，
     * 不需要哨兵对象。
     */
    fun save() {
        state.save()
        ensureStyleCapacity(styleDepth + 1)
        val intBase = styleDepth * INTS_PER_STYLE_LEVEL
        styleInts[intBase] = fill
        styleInts[intBase + 1] = stroke
        styleInts[intBase + 2] = pickId
        // antialias 用 int 存（1/0）：整数部分放的是"打包成 int 的样式"，
        // 一个布尔不值得为它单开一个数组，而多开一个数组就多一处会忘记扩容的地方。
        styleInts[intBase + 3] = if (antialias) 1 else 0
        val floatBase = styleDepth * FLOATS_PER_STYLE_LEVEL
        styleFloats[floatBase] = lineWidth
        styleFloats[floatBase + 1] = globalAlpha
        styleFloats[floatBase + 2] = fontSize
        styleFloats[floatBase + 3] = dashPhase
        styleDashPatterns[styleDepth] = dashPatternValue
        styleDepth++
    }

    /**
     * 弹出并恢复最近一次 [save] 的全部绘制状态。
     *
     * @throws IllegalStateException 没有配对的 [save] 时（此时状态不变）
     */
    fun restore() {
        check(styleDepth > 0) { "restore() 与 save() 不配对：当前栈为空，没有可恢复的状态" }
        state.restore()
        styleDepth--
        val intBase = styleDepth * INTS_PER_STYLE_LEVEL
        fill = styleInts[intBase]
        stroke = styleInts[intBase + 1]
        pickId = styleInts[intBase + 2]
        antialias = styleInts[intBase + 3] != 0
        val floatBase = styleDepth * FLOATS_PER_STYLE_LEVEL
        lineWidth = styleFloats[floatBase]
        globalAlpha = styleFloats[floatBase + 1]
        fontSize = styleFloats[floatBase + 2]
        dashPhase = styleFloats[floatBase + 3]
        // ★ **直接写后备字段，不走 `dashPattern` 的 setter**：那个 setter 会
        //   `copyOf()` + 重新校验，而本函数每帧被调用的次数与 `save` 一样多
        //   ——走 setter 就等于把"save/restore 稳态零分配"这条承诺作废。
        //   栈里存的就是已经拷贝并校验过的那个数组，直接交回引用即可（没有人能改到它：
        //   唯一的写入口就是那个 setter，而它每次都拷）。
        dashPatternValue = styleDashPatterns[styleDepth]
    }

    // ------------------------------------------------------------------
    // 变换
    // ------------------------------------------------------------------

    /**
     * 在当前变换之后追加平移（用户坐标单位）。
     *
     * @param tx 沿 x 轴的平移量
     * @param ty 沿 y 轴的平移量（y 向下）
     */
    fun translate(tx: Float, ty: Float) {
        state.translate(tx, ty)
    }

    /**
     * 在当前变换之后追加缩放。
     *
     * @param sx 沿 x 轴的缩放因子
     * @param sy 沿 y 轴的缩放因子
     */
    fun scale(sx: Float, sy: Float) {
        state.scale(sx, sy)
    }

    /**
     * 在当前变换之后追加旋转。**正值为屏幕上的顺时针**（与 HTML Canvas、JavaFX 一致）。
     *
     * @param degrees 旋转角度（度），正值为屏幕上的顺时针
     */
    fun rotate(degrees: Float) {
        state.rotate(degrees)
    }

    // ------------------------------------------------------------------
    // 裁剪
    // ------------------------------------------------------------------

    /**
     * 用矩形裁剪后续绘制，矩形按**用户坐标**给出，与已有裁剪**求交**。
     *
     * <p>**已声明的降级：当前变换含旋转时，裁剪区是旋转后矩形的轴对齐包围盒**，
     * 不是旋转矩形本身。底层只有 `glScissor` 这一种裁剪手段，而它按定义只能是轴对齐矩形
     * （旋转裁剪要模板缓冲或着色器里的遮罩，本项目两者都没有）。
     *
     * <p>后果是**裁少了**：落在旋转矩形之外、但在它包围盒之内的内容照样画出来，
     * 也照样能被拾取——拾取用的是同一个裁剪矩形，所以两者不会互相矛盾
     * （见 `ViewTransform` 的「裁剪矩形的表示」）。
     *
     * <p>降级的方向是**单向**的：包围盒恒包含旋转后的矩形，因此它只会多画，
     * **不会**有任何本该显示的内容被吞掉。数值由
     * `ViewTransformTest.rotatedClipRectBecomesAxisAlignedBoundingBox` 钉着，
     * "只多不少"这个方向由 `ViewTransformTest.旋转裁剪的包围盒只多不少` 钉着。
     *
     * <p>要精确裁剪一块旋转过的区域，请自己在用户坐标里算出想要的轴对齐范围再传进来
     * （或者在旋转**之前**设好裁剪，让裁剪留在未旋转的坐标系里）——
     * [clipRect] 不会替你旋转裁剪区。
     *
     * @param x 矩形左上角 x（用户坐标）
     * @param y 矩形左上角 y（用户坐标，y 向下）
     * @param w 矩形宽度
     * @param h 矩形高度
     */
    fun clipRect(x: Float, y: Float, w: Float, h: Float) {
        state.clipRect(x, y, w, h)
    }

    // ------------------------------------------------------------------
    // 形状
    // ------------------------------------------------------------------

    /**
     * 填充矩形，可选圆角。
     *
     * @param x      矩形左上角 x（用户坐标）
     * @param y      矩形左上角 y（用户坐标，y 向下）
     * @param w      矩形宽度
     * @param h      矩形高度
     * @param radius 圆角半径；0 表示直角，超过宽高一半时按一半截断
     */
    @JvmOverloads
    fun fillRect(x: Float, y: Float, w: Float, h: Float, radius: Float = 0f) {
        emitShape(rectOutline(x, y, w, h, radius), fill)
    }

    /**
     * 描边矩形，可选圆角。线宽取当前 [lineWidth]，颜色取当前 [stroke]。
     *
     * @param x      矩形左上角 x（用户坐标）
     * @param y      矩形左上角 y（用户坐标，y 向下）
     * @param w      矩形宽度
     * @param h      矩形高度
     * @param radius 圆角半径；0 表示直角，超过宽高一半时按一半截断
     */
    @JvmOverloads
    fun strokeRect(x: Float, y: Float, w: Float, h: Float, radius: Float = 0f) {
        strokeClosedOutline(rectOutline(x, y, w, h, radius))
    }

    /**
     * 生成矩形的闭合轮廓（方向为屏幕上的顺时针，首尾不重复）。
     *
     * <p>圆角用每角 [CORNER_SEGMENTS] 段的折线圆弧逼近，半径超过宽高一半时按一半截断，
     * 因此 `radius` 传得再大也只会得到胶囊形，不会自交。
     *
     * @param x      左上角 x
     * @param y      左上角 y
     * @param w      宽度
     * @param h      高度
     * @param radius 圆角半径
     * @return 扁平顶点数组 `[x0,y0, x1,y1, ...]`
     */
    private fun rectOutline(x: Float, y: Float, w: Float, h: Float, radius: Float): FloatArray {
        if (radius <= 0f) {
            return floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h)
        }
        val r = minOf(radius, w * 0.5f, h * 0.5f)
        val pts = ArrayList<Float>(4 * (CORNER_SEGMENTS + 1) * 2)
        // 从每个角的圆心出发，扫过 90°；四段拼接起来正好是顺时针一圈的圆角矩形轮廓
        fun arc(cx: Float, cy: Float, startDeg: Float) {
            for (i in 0..CORNER_SEGMENTS) {
                val a = Math.toRadians((startDeg + 90.0 * i / CORNER_SEGMENTS)).toFloat()
                pts.add(cx + r * cos(a))
                pts.add(cy + r * sin(a))
            }
        }
        arc(x + w - r, y + r, -90f)
        arc(x + w - r, y + h - r, 0f)
        arc(x + r, y + h - r, 90f)
        arc(x + r, y + r, 180f)
        return pts.toFloatArray()
    }

    /**
     * 填充圆。
     *
     * @param cx       圆心 x（用户坐标）
     * @param cy       圆心 y（用户坐标，y 向下）
     * @param radius   半径
     * @param segments 细分段数；0 表示按 [circleSegments] 依半径与当前缩放自适应
     */
    @JvmOverloads
    fun fillCircle(cx: Float, cy: Float, radius: Float, segments: Int = 0) {
        emitShape(circleOutline(cx, cy, radius, segments), fill)
    }

    /**
     * 描边圆。线宽取当前 [lineWidth]，颜色取当前 [stroke]。
     *
     * @param cx       圆心 x（用户坐标）
     * @param cy       圆心 y（用户坐标，y 向下）
     * @param radius   半径
     * @param segments 细分段数；0 表示按 [circleSegments] 依半径与当前缩放自适应
     */
    @JvmOverloads
    fun strokeCircle(cx: Float, cy: Float, radius: Float, segments: Int = 0) {
        strokeClosedOutline(circleOutline(cx, cy, radius, segments))
    }

    /**
     * 生成圆的闭合轮廓。
     *
     * @param cx       圆心 x
     * @param cy       圆心 y
     * @param radius   半径
     * @param segments 细分段数；为 0 时按半径自适应
     * @return 扁平顶点数组 `[x0,y0, x1,y1, ...]`
     */
    private fun circleOutline(cx: Float, cy: Float, radius: Float, segments: Int): FloatArray {
        val seg = if (segments > 0) segments else circleSegments(radius)
        val pts = FloatArray(seg * 2)
        for (i in 0 until seg) {
            val a = (2.0 * Math.PI * i / seg).toFloat()
            pts[i * 2] = cx + radius * cos(a)
            pts[i * 2 + 1] = cy + radius * sin(a)
        }
        return pts
    }

    /**
     * 按半径自适应细分段数：既保证视觉平滑，又不至于在小圆上浪费顶点。
     *
     * <p>半径先经 [matrixScale] 换算成设备像素——放大 10 倍画出来的圆需要更多段，
     * 否则多边形的棱角会在屏幕上被肉眼看见。
     *
     * @param radius 半径（用户坐标）
     * @return 细分段数，限定在 `[MIN_CIRCLE_SEGMENTS, MAX_CIRCLE_SEGMENTS]` 内
     */
    private fun circleSegments(radius: Float): Int {
        val deviceRadius = abs(radius * matrixScale())
        return (deviceRadius * 0.7f).toInt().coerceIn(MIN_CIRCLE_SEGMENTS, MAX_CIRCLE_SEGMENTS)
    }

    /**
     * 填充椭圆。
     *
     * @param cx       中心 x（用户坐标）
     * @param cy       中心 y（用户坐标，y 向下）
     * @param rx       x 方向半径
     * @param ry       y 方向半径
     * @param segments 细分段数；0 表示按较大半径自适应
     */
    @JvmOverloads
    fun fillEllipse(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int = 0) {
        emitShape(ellipseOutline(cx, cy, rx, ry, segments), fill)
    }

    /**
     * 描边椭圆。线宽取当前 [lineWidth]，颜色取当前 [stroke]。
     *
     * @param cx       中心 x（用户坐标）
     * @param cy       中心 y（用户坐标，y 向下）
     * @param rx       x 方向半径
     * @param ry       y 方向半径
     * @param segments 细分段数；0 表示按较大半径自适应
     */
    @JvmOverloads
    fun strokeEllipse(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int = 0) {
        strokeClosedOutline(ellipseOutline(cx, cy, rx, ry, segments))
    }

    /**
     * 生成椭圆的闭合轮廓。
     *
     * @param cx       中心 x
     * @param cy       中心 y
     * @param rx       x 方向半径
     * @param ry       y 方向半径
     * @param segments 细分段数；为 0 时按较大半径自适应
     * @return 扁平顶点数组 `[x0,y0, x1,y1, ...]`
     */
    private fun ellipseOutline(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int): FloatArray {
        val seg = if (segments > 0) segments else circleSegments(maxOf(rx, ry))
        val pts = FloatArray(seg * 2)
        for (i in 0 until seg) {
            val a = (2.0 * Math.PI * i / seg).toFloat()
            pts[i * 2] = cx + rx * cos(a)
            pts[i * 2 + 1] = cy + ry * sin(a)
        }
        return pts
    }

    /**
     * 绘制直线段。线宽取当前 [lineWidth]，颜色取当前 [stroke]。
     *
     * <p>与描边折线共用同一套轮廓生成，因此线宽同样按用户坐标单位解释，
     * 且端点样式为平头（BUTT）——与 Canvas 的 `lineCap="butt"` 一致。
     *
     * @param x1 起点 x（用户坐标）
     * @param y1 起点 y（用户坐标，y 向下）
     * @param x2 终点 x（用户坐标）
     * @param y2 终点 y（用户坐标，y 向下）
     */
    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        // 复用 scratchPoints，避免每帧为一条线段现搭数组
        ensureScratch(4)
        scratchPoints[0] = x1
        scratchPoints[1] = y1
        scratchPoints[2] = x2
        scratchPoints[3] = y2
        strokeOpenOutline(scratchPoints, 2)
    }

    /**
     * 填充多边形。顶点按给定顺序首尾相连，内部用耳切法三角化，因此**凹多边形也正确**。
     *
     * @param points 扁平顶点数组 `[x0,y0, x1,y1, ...]`，至少 3 个点
     */
    fun fillPolygon(points: FloatArray) {
        emitShape(points, fill)
    }

    /**
     * 描边折线。线宽取当前 [lineWidth]，颜色取当前 [stroke]。
     *
     * @param points 扁平顶点数组 `[x0,y0, x1,y1, ...]`，至少 2 个点
     * @param closed 是否闭合（首尾之间补一段并加接头）
     */
    @JvmOverloads
    fun strokePolyline(points: FloatArray, closed: Boolean = false) {
        if (closed) strokeClosedOutline(points) else strokeOpenOutline(points)
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /** 当前路径。由 [beginPath] 复位，供 [fillPath] / [strokePath] 消费。 */
    private val path = Path()

    /** 路径平坦化器（曲线 → 折线）。跨帧复用，稳态零分配。 */
    private val flattener = Flattener()

    /** 填充三角化器。跨帧复用，稳态零分配。 */
    private val tessellator = Tessellator()

    /** 描边轮廓生成器。跨帧复用，稳态零分配。 */
    private val strokeGenerator = StrokeGenerator()

    /**
     * 平坦化与形状轮廓共用的浮动顶点缓冲，由 [ensureScratch] 按需增长。
     *
     * <p>之所以在 [Gc] 这一层留一块共用缓冲：`Flattener.copyPointsTo` 要求调用方提供目标数组，
     * 而每个形状每帧现搭一个数组正是这条管线要避免的分配。
     */
    private var scratchPoints = FloatArray(INITIAL_SCRATCH_FLOATS)

    /**
     * 多子路径描边时**单个子路径**的临时缓冲，与 [scratchPoints] 分开。
     *
     * <p>分开是必须的：子路径的点就住在 [scratchPoints] 里，就地覆盖会把本次循环
     * 之后那些子路径的输入**提前抹掉**——而表现是"后半截形状画错"，不报错。
     *
     * <p>只在 [strokePath] 遇到多个子路径时才用到（单子路径走零拷贝那条）。
     */
    private var subScratchPoints = FloatArray(INITIAL_SCRATCH_FLOATS)

    /**
     * [fillPath] 交给 [Tessellator.tessellateContours] 的子路径表：每条子路径第一个顶点
     * 在 [scratchPoints] 中的**顶点下标**，与它的顶点数 [contourCounts]。
     *
     * <p>做成 Gc 的字段而不是每次调用现搭两个数组：填充在每帧的热路径上。
     */
    private var contourOffsets = IntArray(INITIAL_CONTOURS)

    /** 与 [contourOffsets] 配套的顶点数表。 */
    private var contourCounts = IntArray(INITIAL_CONTOURS)

    /** 开始一条新路径，丢弃之前累积的全部子路径。 */
    fun beginPath() {
        path.reset()
    }

    /**
     * 把当前点移动到给定坐标并开启一条新子路径。
     *
     * @param x 目标 x（用户坐标）
     * @param y 目标 y（用户坐标，y 向下）
     */
    fun moveTo(x: Float, y: Float) {
        path.moveTo(x, y)
    }

    /**
     * 从当前点向给定坐标追加一条直线段。
     *
     * @param x 目标 x（用户坐标）
     * @param y 目标 y（用户坐标，y 向下）
     */
    fun lineTo(x: Float, y: Float) {
        path.lineTo(x, y)
    }

    /**
     * 追加一条二次贝塞尔曲线。
     *
     * @param cx 控制点 x（用户坐标）
     * @param cy 控制点 y（用户坐标）
     * @param x  终点 x（用户坐标）
     * @param y  终点 y（用户坐标）
     */
    fun quadraticCurveTo(cx: Float, cy: Float, x: Float, y: Float) {
        path.quadraticCurveTo(cx, cy, x, y)
    }

    /**
     * 追加一条三次贝塞尔曲线。
     *
     * @param c1x 第一个控制点 x（用户坐标）
     * @param c1y 第一个控制点 y（用户坐标）
     * @param c2x 第二个控制点 x（用户坐标）
     * @param c2y 第二个控制点 y（用户坐标）
     * @param x   终点 x（用户坐标）
     * @param y   终点 y（用户坐标）
     */
    fun bezierCurveTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) {
        path.bezierCurveTo(c1x, c1y, c2x, c2y, x, y)
    }

    /** 用一条直线回到当前子路径的起点，闭合该子路径。 */
    fun closePath() {
        path.close()
    }

    /**
     * 用当前 [fill] 填充当前路径。
     *
     * <p>曲线按 [matrixScale] 换算出的设备像素容差平坦化（默认 0.25 设备像素），
     * 因此缩放越大、曲线越平滑。
     *
     * <p>**每个子路径都是独立的一条轮廓**，谁是外轮廓、谁是洞由
     * [Tessellator.tessellateContours] 按**包含关系**判断（不看子路径的先后顺序）。
     * 于是"外轮廓 + 内挖空"（环图、饼图的空心）与"一条路径里画好几块"
     * （多个互不相交的外轮廓）同时成立。
     *
     * <p>曾经的行为是把所有子路径平坦化后当成**单个**多边形：环图会被填成实心，
     * 而多块图形里排在后面的块会整块消失（子路径首尾相连成的自相交多边形在耳切法下
     * 没有确定的结果）——两者都不报错，画面上只是"多了/少了一块颜色"。
     */
    fun fillPath() {
        if (path.isEmpty) {
            return
        }
        flattener.flatten(path, matrixScale())
        if (flattener.pointCount() < 3) {
            return
        }
        val count = flattenToScratch()
        val contours = fillContourTable(count)
        tessellator.tessellateContours(scratchPoints, contourOffsets, contourCounts, contours)
        emitTriangles(tessellator.rawTriangles(), tessellator.triangleCount() * 6, fill)
    }

    /**
     * 把当前路径的子路径表填进 [contourOffsets] / [contourCounts]，返回轮廓条数。
     *
     * <p>子路径起点来自 [Flattener.subPathStart]，与 [Flattener] 的编号一致
     * （两者都在每条 `MOVE_TO` 处开一条新子路径）。一条 `MOVE_TO` 都没有的退化路径
     * （例如直接 `lineTo`）没有子路径起点可用，此时整条路径按**一条轮廓**处理。
     *
     * <p>顶点数少于 3 的子路径照样进表，由 [Tessellator] 判为退化轮廓跳过——
     * 在这里过滤会让"表里的下标与 [Flattener] 的子路径下标对齐"这条不变量变复杂，
     * 而那边本来就要判一次。
     *
     * @param pointCount 平坦化后的顶点总数
     * @return 轮廓条数（≥ 1）
     */
    private fun fillContourTable(pointCount: Int): Int {
        val subPaths = flattener.subPathCount()
        if (subPaths == 0) {
            ensureContourCapacity(1)
            contourOffsets[0] = 0
            contourCounts[0] = pointCount
            return 1
        }
        ensureContourCapacity(subPaths)
        for (i in 0 until subPaths) {
            contourOffsets[i] = flattener.subPathStart(i)
            val end = if (i + 1 < subPaths) flattener.subPathStart(i + 1) else pointCount
            contourCounts[i] = end - contourOffsets[i]
        }
        return subPaths
    }

    /**
     * 保证子路径表能容纳 `count` 条轮廓。只在扩容时分配，稳态下 [fillPath] 零分配。
     *
     * @param count 需要的轮廓条数
     */
    private fun ensureContourCapacity(count: Int) {
        if (contourOffsets.size >= count) {
            return
        }
        var size = contourOffsets.size
        while (size < count) {
            size *= 2
        }
        contourOffsets = IntArray(size)
        contourCounts = IntArray(size)
    }

    /**
     * 用当前 [stroke] 与 [lineWidth] 描边当前路径。
     *
     * <p><strong>每个子路径独立描边，且按它自己的闭合状态收尾。</strong>
     * 「闭合」的判据是**该子路径的末条命令是否为 `CLOSE`**（[lastCommandIsClose] /
     * [subPathClosedFlags]），不是看点集——平铺后的点集里，`CLOSE` 追加的起点与
     * 「用户自己 `lineTo` 回到起点」产生的末点**逐位相同**，光看点分不出来。
     *
     * <p>两条曾经的行为差异因此消失：
     *
     * - 闭合子路径的收尾<strong>接头</strong>以前缺（只落平头封口，尖角外侧留小缺口）；
     * - 多条子路径以前被当成**一条**折线，子路径之间会多出一段**并不存在的连线**。
     *
     * <p>判据只影响**轮廓的收尾方式与分段**，不改变用户主动画的任何一条线段：
     * 开放子路径照旧两端平头，闭合子路径补上首尾接头。
     *
     * <p>性能上单子路径是**零拷贝**的（绝大多数调用），多子路径才会经一次
     * [copySubPath] 搬进 [subScratchPoints]。
     */
    fun strokePath() {
        if (path.isEmpty) {
            return
        }
        flattener.flatten(path, matrixScale())
        if (flattener.pointCount() < 2) {
            return
        }
        val count = flattenToScratch()
        val subPaths = flattener.subPathCount()

        // 退化路径（一条 MOVE_TO 都没有）与"单子路径且正好从 0 开始"都走这条：
        // 与改动前完全一致的点集，只有"收尾方式"这一处是新的。
        if (subPaths == 0 || (subPaths == 1 && flattener.subPathStart(0) == 0)) {
            strokeOutline(scratchPoints, count, closed = lastCommandIsClose())
            return
        }

        val closed = subPathClosedFlags(subPaths)
        for (i in 0 until subPaths) {
            val from = flattener.subPathStart(i)
            val to = if (i + 1 < subPaths) flattener.subPathStart(i + 1) else count
            val n = to - from
            // 少于两点的子路径描不出东西（StrokeGenerator 会直接返回），跳过即可。
            if (n < 2) {
                continue
            }
            strokeOutline(copySubPath(from, n), n, closed = closed[i])
        }
    }

    /**
     * 判断「唯一那个子路径」是否以 `CLOSE` 结束。
     *
     * <p>从命令表末尾往前找，遇到第一条 `MOVE_TO` 就说明该子路径已经到头——
     * 那之前没有 `CLOSE`，就是开放的。中间的 `LINE_TO`/曲线命令一律跳过。
     *
     * @return 末条属于该子路径的命令是 `CLOSE` 时为 true
     */
    private fun lastCommandIsClose(): Boolean {
        for (i in path.commandCount() - 1 downTo 0) {
            when (path.commandType(i)) {
                Path.Type.CLOSE -> return true
                Path.Type.MOVE_TO -> return false
                else -> {}
            }
        }
        return false
    }

    /**
     * 逐个判断第 i 个子路径是否以 `CLOSE` 结束。
     *
     * <p>子路径的下标与 [Flattener.subPathStart] 对齐：[Flattener] 在每条 `MOVE_TO`
     * 处开一条新子路径，两者按同一顺序编号。
     *
     * <p>实现上让每条非 `CLOSE` 命令把当前位置的标志**清成 false**，`CLOSE` 置 true——
     * 于是循环结束时留下的就是「**末条**命令是不是 `CLOSE`」。这在
     * `close()` 之后又继续画线（同一条子路径里出现两个 `CLOSE` 之间还有命令）时，
     * 判的是最后那一段，而不是"曾经闭合过"。
     *
     * @param subPaths 子路径条数
     * @return 长度等于 `subPaths` 的闭合标志
     */
    private fun subPathClosedFlags(subPaths: Int): BooleanArray {
        val flags = BooleanArray(subPaths)
        var current = -1
        for (i in 0 until path.commandCount()) {
            val type = path.commandType(i)
            if (type == Path.Type.MOVE_TO) {
                current++
                continue
            }
            if (current !in 0..<subPaths) {
                continue
            }
            flags[current] = type == Path.Type.CLOSE
        }
        return flags
    }

    /**
     * 把 [scratchPoints] 里从第 `from` 个顶点起的一段搬进 [subScratchPoints]。
     *
     * @param from       源起始**顶点**下标（不是 float 下标）
     * @param pointCount 顶点个数
     * @return [subScratchPoints]（长度可能大于所需，调用方另传 `pointCount`）
     */
    private fun copySubPath(from: Int, pointCount: Int): FloatArray {
        val need = pointCount * 2
        if (subScratchPoints.size < need) {
            var size = subScratchPoints.size
            while (size < need) {
                size *= 2
            }
            subScratchPoints = FloatArray(size)
        }
        System.arraycopy(scratchPoints, from * 2, subScratchPoints, 0, need)
        return subScratchPoints
    }

    // ------------------------------------------------------------------
    // 逐图元发射
    // ------------------------------------------------------------------

    /**
     * 把平坦化结果复制进 [scratchPoints]，返回顶点个数。
     *
     * @return [Flattener.pointCount]
     */
    private fun flattenToScratch(): Int {
        val n = flattener.pointCount()
        ensureScratch(n * 2)
        flattener.copyPointsTo(scratchPoints)
        return n
    }

    /**
     * 保证 [scratchPoints] 至少能容纳 `capacity` 个 float。只在需要时分配。
     *
     * @param capacity 需要的 float 个数
     */
    private fun ensureScratch(capacity: Int) {
        if (scratchPoints.size >= capacity) {
            return
        }
        var size = scratchPoints.size
        while (size < capacity) {
            size *= 2
        }
        scratchPoints = FloatArray(size)
    }

    /**
     * 三角化一个闭合多边形并发射。
     *
     * @param points 扁平顶点数组 `[x0,y0, x1,y1, ...]`
     * @param color  ARGB 颜色
     */
    private fun emitShape(points: FloatArray, color: Int) {
        val count = points.size / 2
        if (count < 3) {
            return
        }
        tessellator.tessellate(points, count)
        emitTriangles(tessellator.rawTriangles(), tessellator.triangleCount() * 6, color)
    }

    /**
     * 生成折线的描边轮廓并发射。
     *
     * <p>轮廓在**局部（用户）空间**生成，随后由 [emitTriangles] 按当前变换烘焙——
     * 因此非均匀缩放下线宽会随之变形，与 Canvas 的 `stroke` 一致。
     *
     * <p>调用方必须经 [strokeOpenOutline] 或 [strokeClosedOutline] 进入，不要直接调用本方法：
     * 这个 `closed` 布尔量没有默认值，正是为了避免"闭合轮廓忘了传 true"这类静默错误——
     * 轮廓数组首尾不重复时，漏传会让**整条闭合边凭空消失**（矩形少一条边、圆缺一个楔形），
     * 而单元测试与编译都不会报错。
     *
     * @param points 扁平顶点数组 `[x0,y0, x1,y1, ...]`
     * @param count  顶点个数
     * @param closed 是否闭合
     */
    private fun strokeOutline(points: FloatArray, count: Int, closed: Boolean) {
        // ★ 线宽 ≤ 0 也在这里返回，不能只挡 `count < 2`：开了 AA 之后
        //   `width = lineWidth + 2px` 是**正的**，于是生成器不再返回空，
        //   一条本该"什么都不画"的描边会变成一条淡淡的带子；`edgeScale` 还要除以
        //   真实半线宽 0。加这一行之后两个开关下行为一致（都不画），
        //   而它对 AA 关的路径是**逐位无操作**的：那时生成器反正一个三角形都不发射，
        //   连 `flushIfNeeded` / `syncState` 都走不到。
        //   ⚠️ **未验证**：七个校验器都没有 `lineWidth <= 0` 的场景，所以这一行
        //   目前只有"读代码推出来"这一层保障。它是**覆盖缺口**，不是"已验证的行为"。
        if (count < 2 || lineWidth <= 0f) {
            return
        }
        // ★ 开 AA 时描边带要**两个方向都外扩**：
        //   横向外扩让两条长边有外侧片元；沿向外扩让端帽的端边有外侧片元。
        //   少了任何一个，那一条边就推不到 0.5 以下 ⇒ 边缘从"全亮"直接跳到"没有"，
        //   AA 只做了一半。
        //   ⚠️ "把折线两端延长一点"**不是**可行做法——延长点会成为折线自己的弧长端点，
        //   沿向在那里恒为 0（实测两点线两端各延长 1 局部单位后沿向集合是 {0.0, 0.2}，
        //   一个负值都没有）。所以沿向靠 capExtension 参数，由 StrokeGenerator 自己铺。
        //
        //   `px` 是"1 个设备像素折成的局部单位数"。方向由 `matrixScale()` 的定义定死：
        //   它返回的是"1 个用户单位 = 多少设备像素"（`Flattener.flatten(path, matrixScale())`
        //   正是这么用的——容差按设备像素给），所以一个设备像素 = `1 / matrixScale()` 局部单位。
        //
        //   ⚠️ 算反了的表现是**外扩量随画布变换的缩放跑偏**，而画面只是"边缘略厚"、极难发现。
        //   **不要把这里读成"随窗口 DPI / 帧缓冲尺寸跑偏"**——那条路上症状恒为"完全正常"：
        //   基础矩阵按"1 用户单位 = 1 设备像素"给，所以**恒等变换下 `matrixScale()` 对任何
        //   帧缓冲尺寸都返回 1**，`1/matrixScale()` 与 `matrixScale()` 都是 1。
        //   真正会出问题的是 `gc.scale(2f)` 这种画布变换：正确值是 0.5 局部单位
        //   （= 1 个设备像素），写成 `matrixScale()` 则是 2 局部单位（= 4 个设备像素）。
        //   ★ 方向这一条**已实测**：恒等变换下两种写法都得 1，所以七个校验器里
        //     **没有一条**盖得住它——那正是它以前只有"读定义 + 读另一个消费者（Flattener）"
        //     这一层保障的原因。现在 `PipelineVerifier` 的非等比缩放探针走到了**非恒等变换**
        //     （`gc.scale(1f, 10f)`，见 `antialias` 的 KDoc），实测把这一行写成
        //     `1f * matrixScale()` ⇒ 压缩轴那条断言倒（真外缘之外多出 1 个片元，x=102）。
        //     注意它盖住的是**这个缩放下的方向**，不是"任何变换下都算对"。
        //
        //   ★ 两个方向的外扩是**正交**的，不要混在一个判据里：
        //     · **横向**外扩（加宽描边带）对**闭合路径同样需要**——矩形/圆/椭圆的
        //       长边也要有外侧片元，否则它们的真实外缘之外那半个像素没有片元，
        //       覆盖率只能从 0.5 起步（`0.5 → 1` 而不是 `0 → 1`）。
        //     · **沿向**外扩（`capExtension`）只对**开放路径**有意义——闭合路径没有端帽，
        //       传了也是空操作（`generateOutline` 只在 `!closed` 时补端帽）。
        //   混在一起（用同一个 `!closed` 挡掉两者）的后果是闭合描边的长边只拿到
        //   半边羽化，而这**不是**任何人的意图，只是两个判据恰好长得一样。
        //   ★ 而**接头**要用**真实**半线宽生成（`joinHalf`），不能跟着外扩：
        //     接头的横向恒为 0（见 StrokeGenerator.emitJoin）⇒ 它**不会被羽化**，
        //     加宽量落在它身上只会让拐角实打实地向外多画一圈（实测：真外缘之外
        //     0.25 像素处，直边给 0.25、拐角给 1.0；线宽 8 时就是每个拐角胖 1 像素）。
        //     接头不被外扩、相邻段四边形被外扩，两者的并集恰好是"真实墨迹 + 该有的 fringe"，
        //     交界处不会留缝。AA 关时 `half == realHalf`，这个参数是空操作。
        val realHalf = lineWidth * 0.5f
        val px = if (antialias) 1f / matrixScale().coerceAtLeast(1e-6f) else 0f
        val capExt = if (closed) 0f else px
        // ★ 虚线走同一套参数、只多两个（模式与相位），**其余逐项相同**：
        //   `StrokeGenerator.strokeDashed` 内部就是"按弧长把折线切成若干段两点折线，
        //   每段调一次 stroke"——所以外扩量、接头样式、边距归一化全部照旧，
        //   下面那段 `edgeScale` 的后处理一个字都不用改。
        //
        //   ⚠️ `capExt` **必须一起传**：虚线每一格都是**独立的开放两点折线**，
        //   它的两个端点各自就是端线 ⇒ 沿向在整格上是常量 0 ⇒ 不开 AA 时无所谓，
        //   但开了 AA 之后，哪一段都拿不到"端帽之外"的沿向羽化，端头会退化成硬边。
        //   实线那条路靠的是同名的 `capExtension`，两条路这里是**同一个参数**。
        val pattern = dashPatternValue
        if (pattern == null) {
            strokeGenerator.stroke(
                points, count, closed, lineWidth + 2f * px,
                StrokeGenerator.Cap.BUTT,
                StrokeGenerator.Join.MITER,
                MITER_LIMIT,
                ROUND_SEGMENTS,
                capExt,                          // ← capExtension：只对开放路径有效
                realHalf                         // ← joinHalf：接头不参与外扩
            )
        } else {
            strokeGenerator.strokeDashed(
                points, count, closed, lineWidth + 2f * px,
                StrokeGenerator.Cap.BUTT,
                StrokeGenerator.Join.MITER,
                MITER_LIMIT,
                pattern,
                dashPhase,
                ROUND_SEGMENTS,
                capExt
            )
        }
        val n = strokeGenerator.triangleCount() * 6
        // 生成器是按**它收到的那条线宽**的一半归一化边距的，而那条线宽已经被外扩过
        // ⇒ 这里要把横向分量换算回"真实半线宽"这个分母（推理与实测见 emitTriangles 的
        // `edgeScale`）。上面那道 `lineWidth <= 0f` 的守卫已经保证了这里不会除以 0。
        val edgeScale = (realHalf + px) / realHalf
        // ★ 关着的时候必须传 null（= 全写 0），**不能**只把外扩量置 0 就算了：
        //   生成器给的边距本身非零，写进顶点就会让 fwidth ≠ 0 ⇒ 片元走羽化分支
        //   ⇒ 一条本该逐像素不变的描边会自己长出半透明边缘、还会多出几种新颜色。
        //   实测：这一处漏掉时 PipelineVerifier 以 1 退出，倒的正是
        //   「蓝圆角矩形描边恰好 3084 px」与「画面只有 7 种颜色」两条。
        emitTriangles(strokeGenerator.rawTriangles(), n, stroke,
            if (antialias) strokeGenerator.rawEdges() else null, edgeScale)
    }

    /**
     * 描边一条**开放**折线：首尾之间不补线段，两端按平头（BUTT）收尾。
     *
     * @param points 扁平顶点数组 `[x0,y0, x1,y1, ...]`
     * @param count  顶点个数；默认取整个数组
     */
    private fun strokeOpenOutline(points: FloatArray, count: Int = points.size / 2) {
        strokeOutline(points, count, closed = false)
    }

    /**
     * 描边一条**闭合**轮廓：首尾之间自动补一段并加上接头。
     *
     * <p>**约定**：传入的轮廓数组<strong>首尾不重复</strong>（最后一点不等于第一点）。
     * [rectOutline] / [circleOutline] / [ellipseOutline] 都是这个约定，
     * 否则闭合处会多出一段零长度线段。
     *
     * @param points 扁平顶点数组 `[x0,y0, x1,y1, ...]`
     * @param count  顶点个数；默认取整个数组
     */
    private fun strokeClosedOutline(points: FloatArray, count: Int = points.size / 2) {
        strokeOutline(points, count, closed = true)
    }

    /**
     * 若缓冲区已满则在图元边界处执行帧中途 flush。
     *
     * <p>必须**在每个图元写入之前**调用：`VertexWriter` 触发 flush 请求时只预留了一个最大图元的余量，
     * 不检查就会越界。flush 后 `reset()` 会把状态清回"未设置"，
     * 因此下一个图元会经由 [syncState] 重新 `setState`，无需在此额外处理。
     */
    private fun flushIfNeeded() {
        if (writer.isFlushRequested) {
            batch.submit(writer)
            writer.reset()
        }
    }

    /**
     * 把一组三角形（局部坐标）按当前变换烘焙成顶点并追加进 [VertexWriter]。
     *
     * <p>顺序很重要：先 [flushIfNeeded] 再 [syncState]——`reset()` 会把写入器带回
     * "尚未设置状态"，必须在它之后重新设状态，否则第一个顶点就会抛异常。
     *
     * <p>纯色绘制绑定 1×1 白色纹理，于是片段着色器的 `texture(uTex, vUV) * vColor`
     * 恰好退化成顶点色本身。
     *
     * <p>这是**全部绘制路径的唯一出口**：填充走 [emitShape]、描边走 [strokeOutline]、
     * 路径填充直接调用本方法。因此拾取 ID 只需在这里传下去，就覆盖了每一个图元。
     *
     * @param triangles 扁平三角形数组，每 6 个 float 一个三角形
     * @param floatCount 有效 float 个数（**不是**数组长度：`rawTriangles()` 的数组通常更长）
     * @param argb       ARGB 颜色（会先乘以 [globalAlpha] 再预乘）
     * @param edges      与 [triangles] **逐顶点平行**的边距数组（每顶点 2 个 float）；
     *                   `null` 表示全写 0（填充、文本、以及 [antialias] 关时的描边都走这条）。
     *                   **不得保留**：它是 [StrokeGenerator] 的内部缓冲。
     * @param edgeScale  [edges] 的**横向**分量从"生成器口径"换算到"着色器口径"的比例。
     *
     *                   生成器按**它所收到的线宽**的一半归一化横向分量（`±1` = 它铺出来的
     *                   那两条外缘），而着色器要的是按**真实**半线宽归一化
     *                   （见 [VertexFormat.OFFSET_EDGE]：`±1` = 两条**真实**外缘）。
     *                   开了 AA 时几何被加宽过 1 个设备像素，两个分母不再相等，
     *                   于是这里要乘上 `几何半宽 / 真实半宽`。
     *                   **不做这一步的症状**：真实外缘落在 `|x| < 1` 处，覆盖率公式
     *                   `0.5 - (|x|-1)/wc` 恒为正 ⇒ 整个外扩带全亮 ⇒ 描边比线宽宽出
     *                   约 1 个像素，而边缘依旧是硬的（"AA 只做了一半"的另一种样子）。
     *
     *                   **沿向不需要换算**：那里的公式是 `0.5 + y/fwidth(y)`，
     *                   分子分母同比例缩放会相消，所以生成器给的值直接可用——
     *                   再除一次反而会把端帽的羽化推歪。
     *
     *                   **接头的横向是 0**（见 `StrokeGenerator.emitJoin`），
     *                   0 乘任何倍率还是 0 ⇒ 接头永远走"完全覆盖"分支，
     *                   这一步的倍率对它**没有影响**——这正是它取 0 的理由。
     */
    private fun emitTriangles(triangles: FloatArray, floatCount: Int, argb: Int,
                              edges: FloatArray? = null, edgeScale: Float = 1f) {
        if (floatCount < 6) {
            return
        }
        flushIfNeeded()
        syncState(batch.whiteTextureId())
        val packed = packColor(argb)
        var i = 0
        while (i + 5 < floatCount) {
            var k = 0
            while (k < 3) {
                val wx = triangles[i]
                val wy = triangles[i + 1]
                // 横向要换算（生成器的归一化分母是"它收到的线宽/2"，那里含外扩量）；
                // 沿向已经是可用的口径，**不要再动它**。
                val ec = if (edges != null) edges[i] * edgeScale else 0f
                val ea = if (edges != null) edges[i + 1] else 0f
                // 直接走 ViewTransform 的标量变换，避免每个顶点分配一个 Vec2
                writer().vertex(
                    state.transformX(wx, wy), state.transformY(wx, wy),
                    0f, 0f, packed, pickId, ec, ea
                )
                i += 2
                k++
            }
        }
    }

    // ------------------------------------------------------------------
    // 诊断（只给校验器用）
    // ------------------------------------------------------------------

    /**
     * 打开/关闭**边距调试探针**：让片段着色器把 `aEdge` 直接当颜色输出
     * （红 = `(横向 + 1) / 2`、绿 = `(沿向 + 1) / 2`），而不是画描边本身。
     *
     * <p>存在的唯一理由是让"`layout(location = 4)` 那条属性指针到底读到了什么"
     * 能被**回读像素直接看到**：GL 允许"启用了、却没有着色器声明"的属性，
     * 于是那条指针把偏移写成 0 或 20（读到拾取 ID）时画面**逐像素不变**，
     * 只有抗锯齿行为莫名其妙。判据与读数见 `PipelineVerifier` 的边距探针。
     *
     * <p><strong>用完必须设回 `false`</strong>：它是**程序对象**上的状态，
     * 开着的时候之后画的一切都变成边距彩色图。
     *
     * @param enabled 是否进入探针模式
     */
    fun setEdgeProbe(enabled: Boolean) {
        batch.setEdgeProbe(enabled)
    }

    // ------------------------------------------------------------------
    // 供后续任务使用
    // ------------------------------------------------------------------

    /** 返回内部顶点写入器，供绘制方法追加顶点。 */
    internal fun writer(): VertexWriter = writer

    /** 当前变换（含基础矩阵）。逐顶点发射时用它把用户坐标烘焙到 NDC。 */
    internal val currentMatrix: Mat3
        get() = state.matrix

    /**
     * 把当前变换、裁剪状态与材质同步给顶点写入器。
     *
     * <p>**裁剪 y 传的是顶边**：[ViewTransform.clipDeviceY] 已是 y 向下的设备像素顶边，
     * 与 [DrawCommand] 的 `scissorY` 约定一致；翻成 `glScissor` 的 y 向上坐标是
     * [RenderBatch.applyScissor] 的职责，这里<strong>不能再翻一次</strong>。
     *
     * <p>[VertexWriter.setState] 在状态未变时是空操作，所以每个图元调用一次没有额外开销；
     * 而帧中途 flush 之后 [VertexWriter.reset] 会把写入器带回"未设置状态"，
     * 下一次调用本方法正好重新设上——见 [VertexWriter.isFlushRequested] 的消费方契约。
     *
     * @param textureId 要绑定的纹理 ID
     * @param material  材质，默认 [Material.COLOR]（纯色绘制的全部调用点都用默认值）
     */
    internal fun syncState(textureId: Int, material: Material = Material.COLOR) {
        writer.setState(
            material,
            textureId,
            state.clipDeviceX,
            state.clipDeviceY,
            state.clipDeviceWidth,
            state.clipDeviceHeight
        )
    }

    /** 返回当前变换把用户单位换算成设备像素的平均缩放因子，供平坦化容差与分段数使用。 */
    internal fun matrixScale(): Float = state.matrixScale()

    /**
     * 应用全局不透明度后把 ARGB 颜色打包成顶点格式要求的预乘整数。
     *
     * @param argb 原始 ARGB 颜色
     * @return 预乘 alpha 后的 RGBA 整数（内存字节序 R,G,B,A）
     */
    internal fun packColor(argb: Int): Int {
        val a = ((argb ushr 24) and 0xFF) / 255f * globalAlpha
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return VertexFormat.packPremultiplied(r, g, b, a)
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 确保样式栈能容纳给定的层数。
     *
     * <p>两个数组按**各自的每层宽度**独立扩容：整数部分每层 4 个（fill、stroke、pickId、
     * antialias），浮点部分每层 3 个（lineWidth、globalAlpha、fontSize）。此前两者都是 2，
     * 所以共用了一个 `capacityLevels * 2` 的算法——`pickId` 进来以后那个算法对整数部分就是错的，
     * 会让栈在深层 [save] 时越界。
     *
     * @param capacityLevels 需要的层数
     */
    private fun ensureStyleCapacity(capacityLevels: Int) {
        val neededInts = capacityLevels * INTS_PER_STYLE_LEVEL
        if (neededInts > styleInts.size) {
            var size = styleInts.size * 2
            while (size < neededInts) {
                size *= 2
            }
            styleInts = styleInts.copyOf(size)
        }
        val neededFloats = capacityLevels * FLOATS_PER_STYLE_LEVEL
        if (neededFloats > styleFloats.size) {
            var size = styleFloats.size * 2
            while (size < neededFloats) {
                size *= 2
            }
            styleFloats = styleFloats.copyOf(size)
        }
        // 第三个数组也要跟着扩容——**漏掉它不会立刻出错**（只是 save 到第 8 层之后
        // 越界抛异常），所以它最容易在改动里被忘记。加这一个数组时同步加这一句。
        if (capacityLevels > styleDashPatterns.size) {
            var size = styleDashPatterns.size * 2
            while (size < capacityLevels) {
                size *= 2
            }
            styleDashPatterns = styleDashPatterns.copyOf(size)
        }
    }

    companion object {
        /** 初始顶点容量，约 2.0 MiB（65536 个顶点 * 32 字节）。 */
        private const val INITIAL_VERTEX_CAPACITY = 65536

        /** 圆角矩形每个角用多少段折线逼近 90° 圆弧。 */
        private const val CORNER_SEGMENTS = 6

        /** 圆/椭圆的最小细分段数：再小的圆也至少要视觉上是圆的。 */
        private const val MIN_CIRCLE_SEGMENTS = 12

        /**
         * 圆/椭圆的最大细分段数。
         *
         * <p>半径为 1 的用户单位在放大到整屏时也不该超过这个数——再多就是纯粹的顶点浪费了。
         */
        private const val MAX_CIRCLE_SEGMENTS = 256

        /** 描边 miter 接头超过该倍数（相对于半线宽）时回退为斜接。 */
        private const val MITER_LIMIT = 4f

        /** 描边的圆端点/圆角接头细分段数。 */
        private const val ROUND_SEGMENTS = 8

        /** [scratchPoints] 的初始长度（float 个数）。 */
        private const val INITIAL_SCRATCH_FLOATS = 256

        /** 子路径表的初始条数。按需翻倍，常见的路径远达不到。 */
        private const val INITIAL_CONTOURS = 4

        /**
         * 样式栈的初始层数。
         *
         * <p>与 [ViewTransform] 的裁剪栈互相独立，各自按需翻倍扩容，取值相同只是巧合而非约束。
         */
        private const val INITIAL_STACK_LEVELS = 8

        /** 样式栈每层占用的 int 个数：fill、stroke、pickId、antialias。 */
        private const val INTS_PER_STYLE_LEVEL = 4

        /** 样式栈每层占用的 float 个数：lineWidth、globalAlpha、fontSize。 */
        private const val FLOATS_PER_STYLE_LEVEL = 4
    }
}
