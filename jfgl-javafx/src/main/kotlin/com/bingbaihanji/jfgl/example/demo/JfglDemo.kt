package com.bingbaihanji.jfgl.example.demo

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.renderer.PickHit
import com.bingbaihanji.jfgl.util.Rect
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.system.exitProcess

/** 窗口的**逻辑**尺寸。绘制区是设备像素，比它大（125% 缩放下 900 → 1113）。 */
private const val SCENE_W = 900.0
private const val SCENE_H = 700.0

/** 背景色。**不能**用 0xFF333333：`FXGLTransfer` 的 `glClearColor` 就是 (0.2,0.2,0.2)。 */
private const val BG = 0xFF23262B.toInt()

/** 选中高亮色。 */
private const val HIGHLIGHT = 0xFFFFEB3B.toInt()

/** 小于这个位移（设备像素）的一次按下-抬起算**单击**，否则算拖拽。 */
private const val CLICK_SLOP = 4.0

/** 八色预设色板。 */
private val PALETTE = intArrayOf(
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF00ACC1.toInt(), 0xFF3949AB.toInt(), 0xFF8E24AA.toInt(), 0xFFEC407A.toInt()
)

/** 文本模式的字号档位。 */
private val FONT_SIZES = floatArrayOf(14f, 22f, 32f, 48f)

/** 文本模式的样本串（按落字序号轮换）。不随机——画面要可复现。 */
private val TEXT_SAMPLES = arrayOf(
    "JFGL immediate-mode text",
    "中文文本，基线对齐",
    "Mixed 中英混排 123",
    "这是一行用来观察推进宽度的较长文本"
)

private enum class Mode(val label: String) { DRAW("绘图"), TEXT("文本"), CHART("图表") }

/**
 * 一个图形 + 它的拾取 ID。
 *
 * <p>**ID 为什么不放进 [Shape]**：`Shape` 是**不可变快照**，而拾取 ID 是在图形被创建
 * **之后**由 `pickRegistry` 分配的。给 `Shape` 加一个可变字段会破坏"整表替换"这条
 * 跨线程契约（GL 线程读到的列表就不再是自洽的了）。所以 ID 放在外面这一层。
 *
 * @param pickId 0 表示尚未注册（此时不参与拾取，但仍照常画）
 */
data class Placed(val shape: Shape, val pickId: Int)

class JfglDemoApp : Application() {

    private var transfer: FXGLTransfer? = null
    private lateinit var canvas: Node
    private val status = Label("拖动鼠标画一个矩形").apply {
        padding = Insets(6.0, 10.0, 6.0, 10.0)
        style = "-fx-font-size: 13px; -fx-text-fill: #1b1b1b;"
    }

    // ---- 跨线程状态：JavaFX 线程写，GL 线程读。全部是不可变快照。 ----

    /** 已画完的图形。整表替换，不做原地改。 */
    private val shapes = AtomicReference<List<Placed>>(emptyList())

    /** 选中集（pickId）。 */
    private val selection = AtomicReference<Set<Int>>(emptySet())

    /** 当前模式 / 图形种类 / 样式 / 颜色 / 字号。 */
    @Volatile private var mode = Mode.DRAW
    @Volatile private var kind = ShapeKind.RECT
    @Volatile private var style = ShapeStyle.FILL_AND_STROKE
    @Volatile private var color = PALETTE[0]
    @Volatile private var lineWidth = 2f
    @Volatile private var fontSize = FONT_SIZES[1]

    /**
     * 三个"当前模式是什么"的 JavaFX 属性，**只为菜单的启用/禁用服务**。
     *
     * <p>为什么不直接绑到 [mode] 那个 `@Volatile` 字段：JavaFX 的 `bind` 要的是
     * `ObservableValue`，而 `@Volatile` 的语义是"跨线程可见"而不是"可观察"。
     * 两者服务的目的不同，所以各留一份、在 [modeItem] 的动作里同步设置。
     * 这一份**只在 JavaFX 线程上读写**，因此不需要 `@Volatile`。
     */
    private val drawModeActive = SimpleBooleanProperty(true)
    private val textModeActive = SimpleBooleanProperty(false)
    private val chartModeActive = SimpleBooleanProperty(false)

    /** 当前图形是不是直线（直线恒描边，样式菜单对它没有意义）。**只在 JavaFX 线程读写。** */
    private val styleIsLine = SimpleBooleanProperty(false)

    /** 模式切换后同步那三个属性。**只在 JavaFX 线程调用。** */
    private fun syncModeProperties() {
        drawModeActive.set(mode == Mode.DRAW)
        textModeActive.set(mode == Mode.TEXT)
        chartModeActive.set(mode == Mode.CHART)
    }

    /** 拖拽预览：起点（设备像素）。NaN 表示没有正在进行的拖拽。 */
    @Volatile private var dragStartX = Float.NaN
    @Volatile private var dragStartY = Float.NaN

    /**
     * 拖拽轨迹点：**每次追加都新建数组**（`@Volatile` 只保证引用可见，不保证数组内容，
     * 原地 append 会让 GL 线程读到"长度已改、元素没写完"的半截数组）。
     *
     * <p><strong>取舍记录</strong>：这条写法的代价是 O(n²)——每个拖拽事件一次
     * `copyOf(size + 2)`，一笔 n 个事件的拖拽累计约 n² 次 float 拷贝。
     * 量级上**可忽略**：10000 个事件（十秒连续涂鸦）累计约 2×10⁸ 次拷贝 ≈ 400 MB 的
     * `arraycopy`，摊在那十秒里约 40 ms。
     *
     * <p>**不用"预分配缓冲 + 计数"去换掉它**，虽然那是 O(1) 摊还。真正的代价**不是** JMM
     * （几何级数扩容仍可保持"每次整体替换、不共享可变状态"），而是它引入了
     * **"数组长度 ≠ 点数"** 这个新状态：一旦两者不同步，产出的就是**几何被静默截断
     * 或多读一段**——正是本项目最防的那类"画面看起来正常"。用显而易见的正确换 1% 的
     * 病态路径开销，这个交易对 demo 划算。
     *
     * <p>真要做实时长轨迹，那时该做的是**降采样而不是换发布协议**。
     */
    @Volatile private var trajectory = FloatArray(0)

    /** 待框选的矩形（左,上,宽,高，设备像素）。NaN 宽度表示没有。 */
    @Volatile private var marqueeX = Float.NaN
    @Volatile private var marqueeY = 0f
    @Volatile private var marqueeW = Float.NaN
    @Volatile private var marqueeH = 0f

    /** 待处理的框选请求（GL 线程消费）。 */
    private val marqueePending = AtomicReference<Rect?>(null)

    /** 下一个要用的拾取 ID（只在 GL 线程递增）。 */
    private var nextPickId = 1

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
        bridge.onInit {
            bridge.gc()?.let { gc ->
                // ★ 启动自检：纯计算部分在这里被验证，失败以非 0 退出。
                //   放在这里是因为它不需要 GL，但需要一个"确定跑过一次"的时机。
                val failures = selfCheckShapeMath()
                if (failures != 0) {
                    System.err.println("[自检] 失败 $failures 项，demo 不可信，退出")
                    // ★ 退出**交给 JavaFX 线程**，不在这里（GL 线程）直接 exitProcess。
                    //   仓库既有的两个自检都是从 JavaFX 线程退出的（`ClickDslExample.kt:161`
                    //   与 `:182` 后者在 `Platform.runLater` 内）。GL 回调里直接 `System.exit`
                    //   会触发 JavaFX 的关闭钩子、而此刻本线程正卡在 openglfx 的原生回调里——
                    //   那是**没人测过**的一条路径，没必要为省一次 `runLater` 去赌它。
                    Platform.runLater { exitProcess(1) }
                    return@let
                }
                println("[自检] 全部通过")
                // 注册发生在**数据变化时**，不是每帧。此刻画布是空的，所以这里什么都不注册；
                // 新图形在 [commitShape] 里注册（Task 6）。
                // 反复注册会耗尽 ID 空间——PickRegistry 会抛异常，不会静默。
                nextPickId = 1
            }
        }
        bridge.onFrame { gc -> drawScene(gc) }
        // ★ 框选的拾取读回挂在这里，**不是** onFrame 里。
        //   理由见 consumeMarquee 的文档：`onFrame` 跑在 `endFrame()` 之前，
        //   那时 `pickBufferValid` 是 false（`beginFrame` 刻意置的），
        //   `gc.pickRect` 会恒返回空列表——框选会**永远选不中任何东西且不报错**。
        //   `onRender` 跑在 `endFrame()` **之后**，此刻本帧的 ID pass 刚渲染完。
        //   它没有 gc 参数，但同一线程上 `bridge.gc()` 拿得到，且我们确实在 GL 线程。
        bridge.onRender { transfer?.gc()?.let { gc -> consumeMarquee(gc) } }
        transfer = bridge

        val view = bridge.createGlFXView()
        canvas = view
        wireMouse(bridge, view)

        val mainView = MainView().apply {
            menu = buildMenuBar()
            center = view
            bottom = status
        }
        stage.title = "JFGL 交互式 Demo"
        stage.scene = Scene(mainView.createMainView(), SCENE_W, SCENE_H)
        stage.scene.setOnKeyPressed { e ->
            if (e.code == KeyCode.DELETE || e.code == KeyCode.BACK_SPACE) deleteSelected()
        }
        stage.show()
    }

    // ---- 鼠标接线（JavaFX 线程） ----

    /**
     * 接上按下 / 拖拽 / 抬起。
     *
     * <p>**每一次都要乘 [FXGLTransfer.deviceScale]**：鼠标事件给的是节点的**逻辑**局部坐标，
     * 而 [Gc] 要的是**设备像素**。漏乘的表现是"图形画在别的地方"或"点 A 命中 B"，
     * 而画面本身完全正常——在 100% 缩放的机器上还一切正常。
     */
    private fun wireMouse(bridge: FXGLTransfer, node: Node) {
        node.addEventHandler(MouseEvent.MOUSE_PRESSED) { e -> onPress(bridge, node, e) }
        node.addEventHandler(MouseEvent.MOUSE_DRAGGED) { e -> onDrag(bridge, node, e) }
        node.addEventHandler(MouseEvent.MOUSE_RELEASED) { e -> onRelease(bridge, node, e) }
    }

    private fun onPress(bridge: FXGLTransfer, node: Node, e: MouseEvent) {
        val s = bridge.deviceScale(node)
        val dx = (e.x * s).toFloat()
        val dy = (e.y * s).toFloat()
        if (e.button == MouseButton.SECONDARY) {
            // 右键：框选。**只在绘图模式**——文本/图表模式下图形没有被画出来，
            // ID pass 里也没有它们，走一趟框选必然是空结果，白付一次同步 glReadPixels
            // （那是 GPU 停等）。这里挡住，[onRelease] 那边靠 marqueeW 仍为 NaN 自然短路。
            if (mode != Mode.DRAW) return
            marqueeX = dx; marqueeY = dy; marqueeW = 0f; marqueeH = 0f
            return
        }
        if (e.button != MouseButton.PRIMARY) return
        when (mode) {
            Mode.DRAW -> {
                dragStartX = dx; dragStartY = dy
                trajectory = floatArrayOf(dx, dy)
            }
            Mode.TEXT -> Unit          // 文本在抬起时落字
            Mode.CHART -> Unit         // 图表模式不吃鼠标
        }
    }

    /**
     * 拖拽中。
     *
     * <p><strong>★ 这里必须用 `isPrimaryButtonDown` / `isSecondaryButtonDown`，
     * 不能用 `e.button`。</strong>
     *
     * <p>契约上的理由：`MouseEvent.getButton()` 的文档写的是
     * <em>"which, if any, of the mouse buttons has <b>changed state</b>"</em>——
     * 它回答的是"哪个按键刚变了状态"，**不是**"当前按着哪个"。而拖拽期间没有任何按键
     * 改变状态（键在 `MOUSE_PRESSED` 那一刻就已按下），所以按契约它就**不该**被依赖。
     * 判断"当前按着哪个"是 `isXxxButtonDown()` 的职责，文档对这两个方法的分工是明确的。
     *
     * <p>用 `e.button == SECONDARY` 之类的判断，一旦它对拖拽事件返回 `NONE`，
     * 两个分支就永远不成立，**整个绘制功能会静默失效**：轨迹一个点都收不到、
     * 图形画不出来、框选永远不出现，**而且不报任何错**——画面看起来就是"拖了没反应"。
     *
     * <p>⚠️ <strong>这条是按契约推的，不是实测结论</strong>：我读了本机 JDK 里的
     * `javafx/scene/Scene.java`，它把 `e.getButton()` 从 glass 层**原样透传**
     * （`Scene.java` 里两处 `new MouseEvent(..., e.getButton(), ...)`），
     * 所以拖拽时那个值到底是不是 `NONE` 取决于 Windows 平台层，**我没有测到**。
     * 换成 `isXxxButtonDown()` 在两种情况下都正确，所以不必先知道答案。
     * （同一个文件里"两轴都用 outputScaleY"那条也是这么标注的：推断与实测分开写。）
     *
     * <p>`MOUSE_PRESSED` / `MOUSE_RELEASED` 上 `getButton()` 是可靠的——那两刻确实
     * 有按键改变了状态，正是它契约所指的情形。所以 [onPress] / [onRelease] 照旧用它。
     */
    private fun onDrag(bridge: FXGLTransfer, node: Node, e: MouseEvent) {
        val s = bridge.deviceScale(node)
        val dx = (e.x * s).toFloat()
        val dy = (e.y * s).toFloat()
        if (!marqueeW.isNaN() && e.isSecondaryButtonDown) {
            marqueeW = dx - marqueeX; marqueeH = dy - marqueeY
            return
        }
        if (!e.isPrimaryButtonDown) return
        if (mode != Mode.DRAW || dragStartX.isNaN()) return

        // 轨迹点：新建数组再赋值（见 trajectory 字段的说明）
        val old = trajectory
        val next = old.copyOf(old.size + 2)
        next[old.size] = dx; next[old.size + 1] = dy
        trajectory = next
    }

    private fun onRelease(bridge: FXGLTransfer, node: Node, e: MouseEvent) {
        if (e.button == MouseButton.SECONDARY) {
            if (!marqueeW.isNaN()) {
                val r = Rect(minOf(marqueeX, marqueeX + marqueeW), minOf(marqueeY, marqueeY + marqueeH),
                    abs(marqueeW), abs(marqueeH))
                marqueePending.set(r)          // GL 线程在下一帧消费
                marqueeW = Float.NaN
            }
            return
        }
        if (e.button != MouseButton.PRIMARY) return

        val s = bridge.deviceScale(node)
        val dx = (e.x * s).toFloat()
        val dy = (e.y * s).toFloat()
        val moved = if (dragStartX.isNaN()) 0.0 else hypot((dx - dragStartX).toDouble(), (dy - dragStartY).toDouble())

        when (mode) {
            Mode.DRAW -> {
                if (moved < CLICK_SLOP) {
                    // 单击：拾取选中
                    dragStartX = Float.NaN
                    bridge.clickAsyncAtNode(node, e.x, e.y) { hit -> onPick(hit) }
                } else {
                    commitShape(dx, dy)
                    dragStartX = Float.NaN
                    dragStartY = Float.NaN
                    trajectory = FloatArray(0)
                }
            }
            // 文本模式**在 Task 8 接上**：`commitText` 与 `Shape.TextShape` 都是那边的交付物。
            // 这里先什么都不做——**不放假占位**（占位会让"这个分支有没有实现"无从判断）。
            Mode.TEXT -> Unit
            Mode.CHART -> Unit
        }
    }

    /** 把当前拖拽的起止点 / 轨迹变成一个图形，追加进 [shapes]。 */
    private fun commitShape(endX: Float, endY: Float) {
        val sx = dragStartX
        val sy = dragStartY
        if (sx.isNaN()) return
        val w = endX - sx
        val h = endY - sy

        val s: Shape? = if (kind.isTrajectory) {
            val pts = if (kind == ShapeKind.POLYGON) ShapeMath.polygonFrom(trajectory)
            else ShapeMath.bezierFrom(trajectory)
            // 点数不够就丢弃，**不产生退化图形**（理由见 ShapeMath.polygonFrom 的说明）
            if (pts == null) null
            else if (kind == ShapeKind.POLYGON) Shape.PolygonShape(pts, color, style, lineWidth)
            else Shape.BezierShape(pts, color, style, lineWidth)
        } else when (kind) {
            ShapeKind.RECT -> Shape.RectShape(minOf(sx, endX), minOf(sy, endY), abs(w), abs(h), color, style, lineWidth)
            ShapeKind.CIRCLE -> {
                val r = hypot(w.toDouble(), h.toDouble()).toFloat() / 2f
                Shape.CircleShape(sx + w / 2f, sy + h / 2f, r, color, style, lineWidth)
            }
            ShapeKind.ELLIPSE -> Shape.EllipseShape(sx + w / 2f, sy + h / 2f, abs(w) / 2f, abs(h) / 2f, color, style, lineWidth)
            ShapeKind.LINE -> Shape.LineShape(sx, sy, endX, endY, color, style, lineWidth)
            else -> null
        }
        if (s == null) {
            // **用常量拼提示，不要写死数字**：写死的话，改了 ShapeMath 的阈值、
            // 这句提示说的数就与实际判据不一致——而它恰恰是**用户唯一能看到**的那句话。
            status.text = "拖得太短，没有形成图形" +
                "（多边形至少 ${ShapeMath.MIN_POLYGON_POINTS} 个点、" +
                "曲线至少 ${ShapeMath.MIN_CURVE_POINTS} 个点）"
            return
        }
        shapes.set(shapes.get() + Placed(s, 0))     // pickId 在 Task 6 接上
        status.text = "已画：${s.describe()} · 共 ${shapes.get().size} 个"
    }

    /** 拾取结果回调。**在 JavaFX 线程上执行**。 */
    private fun onPick(hit: PickHit?) {
        val item = hit?.payload() as? Shape
        val id = hit?.id() ?: 0
        selection.set(if (item == null) emptySet() else setOf(id))
        status.text = if (item == null) {
            "未命中（设备像素 ${hit?.x()?.toInt() ?: 0},${hit?.y()?.toInt() ?: 0}）"
        } else {
            "命中：${item.describe()}"
        }
    }

    private fun deleteSelected() {
        val sel = selection.get()
        if (sel.isEmpty()) { status.text = "没有选中任何图形"; return }
        // 所有权：选定集必须 unregister，否则 pickRegistry 会一直强引用着它们
        transfer?.gc()?.let { gc -> sel.forEach { gc.pickRegistry.unregister(it) } }
        shapes.set(shapes.get().filter { it.pickId !in sel })
        selection.set(emptySet())
        status.text = "已删除 ${sel.size} 个图形 · 剩 ${shapes.get().size} 个"
    }
    /** 画一帧。**在 GL 线程上执行**，不要在这里碰任何 JavaFX 控件。 */
    private fun drawScene(gc: Gc) {
        val w = gc.width.toFloat()
        val h = gc.height.toFloat()
        gc.pickId = 0
        gc.fill = BG
        gc.fillRect(0f, 0f, w, h)

        if (mode == Mode.CHART) {
            // Task 9 接上
            return
        }

        for (p in shapes.get()) p.shape.draw(gc)
        drawDragPreview(gc)
        drawMarquee(gc)
        // ★ 这里**不能**调 consumeMarquee（框选读回）——见它的文档：
        //   `onFrame` 跑在 `endFrame()` 之前，那时本帧的 ID pass 还没渲染，
        //   `gc.pickRect` 会**恒返回空列表**。它在 [start] 里挂到 onRender 上。
    }

    /** 拖拽预览：两点定义的那四种画一个临时图形；轨迹定义的那两种画原始轨迹。 */
    private fun drawDragPreview(gc: Gc) {
        if (mode != Mode.DRAW) return
        val sx = dragStartX
        val sy = dragStartY
        if (sx.isNaN()) return
        val pts = trajectory
        gc.save()
        gc.stroke = HIGHLIGHT
        gc.lineWidth = 1f
        if (kind.isTrajectory) {
            if (pts.size >= 4) gc.strokePolyline(pts, closed = false)
        } else {
            val last = if (pts.size >= 2) Pair(pts[pts.size - 2], pts[pts.size - 1]) else Pair(sx, sy)
            gc.strokeRect(minOf(sx, last.first), minOf(sy, last.second),
                abs(last.first - sx), abs(last.second - sy))
        }
        gc.restore()
    }

    /** 框选矩形：半透明填充 + 描边。pickId 为 0，不参与拾取。 */
    private fun drawMarquee(gc: Gc) {
        val w0 = marqueeW
        if (w0.isNaN()) return
        gc.save()
        gc.globalAlpha = 0.25f
        gc.fill = HIGHLIGHT
        gc.fillRect(minOf(marqueeX, marqueeX + w0), minOf(marqueeY, marqueeY + marqueeH), abs(w0), abs(marqueeH))
        gc.globalAlpha = 1f
        gc.stroke = HIGHLIGHT
        gc.lineWidth = 1f
        gc.strokeRect(minOf(marqueeX, marqueeX + w0), minOf(marqueeY, marqueeY + marqueeH), abs(w0), abs(marqueeH))
        gc.restore()
    }

    /**
     * 消费待处理的框选请求。**只由 `onRender` 调用（跑在 `endFrame()` 之后）。**
     *
     * <p><strong>★ 为什么不能在 `onFrame` 里做</strong>：`RenderBatch.beginFrame` 会**刻意**
     * 把 `pickBufferValid` 置为 `false`，注释原文是"本帧还没渲染 ID pass 之前，缓冲里装的是
     * 上一帧的结果。标为无效，这样上层的拾取查询会诚实地返回「没命中」，而不是拿陈旧的 ID
     * 去注册表里查——那会拾取到早已消失的对象，而画面完全正常"。
     * 而它只在 `withPickPass`（`endFrame` → `submit` 时）里被置回 `true`。
     *
     * <p>于是 `onFrame` 里调 `gc.pickRect` / `gc.pick` 会**恒返回空**——
     * **框选会永远选不中任何东西，而且不报任何错**。这不是"读到上一帧的陈旧数据"
     * （那样至少静态场景下是对的），而是库**明确拒绝**给你答案。
     *
     * <p>`onRender` 跑在 `endFrame()` **之后**，此刻本帧的 ID pass 刚渲染完、
     * `pickBufferValid` 是 `true`，读回的是**本帧**的拾取结果。它没有 `gc` 参数，
     * 但同一线程上 `bridge.gc()` 拿得到。
     *
     * <p>（顺带一条给文档的口子：`jfgl { }` 的 DSL 只暴露 `onRender` 给不了 `Gc`、
     * 也没把 `onRender` 这一层暴露出来，所以**走 DSL 的应用拿不到"帧末拾取"这个时机**。
     * 单像素拾取有 `pickAsync` / `clickAsync` 兜着，区域拾取没有。）
     */
    private fun consumeMarquee(gc: Gc) {
        val r = marqueePending.getAndSet(null) ?: return
        val hits = gc.pickRect(r.x, r.y, r.width, r.height)
        val ids = hits.map { it.id() }.toSet()
        // 回 JavaFX 线程更新选中集与状态栏（GL 线程不能碰控件）
        Platform.runLater {
            selection.set(ids)
            status.text = "框选到 ${ids.size} 个图形"
        }
    }
    private fun buildMenuBar(): MenuBar {
        val modeGroup = ToggleGroup()
        fun modeItem(m: Mode) = RadioMenuItem(m.label).apply {
            toggleGroup = modeGroup
            isSelected = (m == Mode.DRAW)
            setOnAction { mode = m; status.text = "模式：${m.label}"; syncModeProperties() }
        }
        val modeMenu = Menu("模式").apply { items.addAll(Mode.entries.map { modeItem(it) }) }

        val kindGroup = ToggleGroup()
        val kindItems = ShapeKind.entries.map { k ->
            RadioMenuItem(k.label).apply {
                toggleGroup = kindGroup
                isSelected = (k == ShapeKind.RECT)
                setOnAction {
                    kind = k
                    status.text = "当前图形：${k.label}"
                    // 直线没有"填充"这回事（[Shape.LineShape] 恒走描边），
                    // 所以选中直线时把样式菜单灰掉。不灰的话用户选"只填充"再拖一条线，
                    // 会得到一条**实心描边**的线而界面毫无反馈——那是"设了但没用"的静默失效。
                    styleIsLine.set(k == ShapeKind.LINE)
                }
            }
        }
        val kindMenu = Menu("图形").apply { items.addAll(kindItems) }

        val styleGroup = ToggleGroup()
        val styleItems = listOf(
            ShapeStyle.FILL to "只填充", ShapeStyle.STROKE to "只描边", ShapeStyle.FILL_AND_STROKE to "填充+描边"
        ).map { (s, label) ->
            RadioMenuItem(label).apply {
                toggleGroup = styleGroup
                isSelected = (s == ShapeStyle.FILL_AND_STROKE)
                // ★ 必须写 `this@JfglDemoApp.style`，**不能**只写 `style`：
                //   `MenuItem` 自己有一个 `style` 属性（String，CSS），
                //   而在 `apply { }` 的接收者作用域里，内层那个会**遮蔽**外层的字段。
                //   只写 `style = s` 会去赋菜单项的 CSS 字符串，编译报
                //   "Assignment type mismatch: actual type is 'ShapeStyle', but 'String!' was expected"。
                //   往 CSS 里塞 "FILL"/"STROKE" 是无效 CSS，所以这里没有第二种读法。
                setOnAction { this@JfglDemoApp.style = s }
            }
        }
        val widthGroup = ToggleGroup()
        val widthItems = listOf(1f, 2f, 4f, 8f).map { lw ->
            RadioMenuItem("线宽 ${lw.toInt()}").apply {
                toggleGroup = widthGroup
                isSelected = (lw == 2f)
                setOnAction { lineWidth = lw }
            }
        }
        val styleMenu = Menu("样式").apply { items.addAll(styleItems + SeparatorMenuItem() + widthItems) }

        val colorGroup = ToggleGroup()
        val colorItems = PALETTE.mapIndexed { i, c ->
            RadioMenuItem("颜色 %02X".format((c shr 16) and 0xFF)).apply {
                toggleGroup = colorGroup
                isSelected = (i == 0)
                setOnAction { color = c }
            }
        }
        val colorMenu = Menu("颜色").apply { items.addAll(colorItems) }

        val fontGroup = ToggleGroup()
        val fontItems = FONT_SIZES.mapIndexed { i, fs ->
            RadioMenuItem("${fs.toInt()} px").apply {
                toggleGroup = fontGroup
                isSelected = (i == 1)
                setOnAction { fontSize = fs }
            }
        }
        val fontMenu = Menu("字号").apply { items.addAll(fontItems) }

        val chartGroup = ToggleGroup()
        val chartItems = DemoChart.KINDS.mapIndexed { i, (label, _) ->
            RadioMenuItem(label).apply {
                toggleGroup = chartGroup
                isSelected = (i == 0)
                setOnAction { DemoChart.selectedKind = i }
            }
        }
        val chartMenu = Menu("图型").apply { items.addAll(chartItems) }

        val fileMenu = Menu("文件").apply {
            items.addAll(
                MenuItem("清空画布").apply { setOnAction { clearAll() } },
                MenuItem("删除选中").apply { setOnAction { deleteSelected() } },
                SeparatorMenuItem(),
                // 只 exit，不在这里 dispose：GL 资源由 [stop] 释放。
                // 在事件处理器里 dispose 会撞上"上下文可能已失效"那条（见 CLAUDE.md 的线程模型）。
                MenuItem("退出").apply { setOnAction { Platform.exit() } }
            )
        }

        // 按模式启用/禁用：画图时才选图形与样式，文本时才选字号，图表时才选图型。
        // 绑定的是上面那三个 SimpleBooleanProperty，**不是** @Volatile 的 mode——
        // 后者是"跨线程可见"，前者是"可观察"，两件事。
        kindMenu.disableProperty().bind(drawModeActive.not())
        // 样式菜单：非绘图模式灰掉，**且**选中直线时也灰掉（直线恒描边，理由见 kindItems）。
        styleMenu.disableProperty().bind(drawModeActive.not().or(styleIsLine))
        fontMenu.disableProperty().bind(textModeActive.not())
        chartMenu.disableProperty().bind(chartModeActive.not())
        // 颜色三种模式都可用（文本也用颜色）

        return MenuBar().apply {
            menus.addAll(fileMenu, modeMenu, kindMenu, styleMenu, colorMenu, fontMenu, chartMenu)
        }
    }

    /** 清空画布：全部 unregister 后清列表。**所有权要求注销**，否则 pickRegistry 一直强引用。 */
    private fun clearAll() {
        transfer?.gc()?.let { gc -> shapes.get().forEach { gc.pickRegistry.unregister(it.pickId) } }
        shapes.set(emptyList())
        selection.set(emptySet())
        status.text = "已清空"
    }

    /** 窗口关闭时释放 GL 资源。**不要在 stop 之外的地方 dispose**。 */
    override fun stop() {
        transfer?.dispose()
    }
}

/**
 * 应用入口。
 *
 * <p>**没有它，Step 4 那条运行命令跑不起来**——命令打的是 `JfglDemoKt`，
 * 而那个类里得有一个顶层 `main()` 才谈得上入口。缺了它的报错是
 * `在类 com.bingbaihanji.jfgl.example.demo.JfglDemoKt 中找不到 main 方法`，
 * **不是编译错**（编译能过），所以特别容易漏。
 *
 * <p>`@JvmName("main")` 在这里**不需要**：`ClickExample.kt` 之所以要它，是因为
 * `example` 包里已经有一个顶层 `main()`（`PipelineExample.kt`）会造成重载歧义；
 * 而本文件在 `example.demo` 这个**新包**里，没有同包冲突。
 */
fun main() {
    Application.launch(JfglDemoApp::class.java)
}
