package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.renderer.PickHit
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import java.util.concurrent.atomic.AtomicReference

/** 窗口的**逻辑**尺寸。绘制区的设备像素尺寸由系统缩放决定，不要拿它当绘制区尺寸。 */
private const val SCENE_W = 860.0
private const val SCENE_H = 680.0

/** 背景色。**不能**用 0xFF333333：`FXGLTransfer` 的 `glClearColor` 就是 (0.2,0.2,0.2)。 */
private const val BG = 0xFF23262B.toInt()

/** 选中高亮的颜色。 */
private const val HIGHLIGHT = 0xFFFFEB3B.toInt()

/**
 * 「鼠标点击 → GPU 拾取 → 拿回对象 → 更新界面」的完整闭环示例。
 *
 * <h2>它演示什么</h2>
 *
 * <p>画一堆常见的图形（填充的、纯描边的、圆角矩形、圆、椭圆、凹多边形、贝塞尔路径）
 * 与三段不同字号/颜色的文本，每一个都注册进 `pickRegistry`，然后接上鼠标：
 * 点中谁，回调里拿到的 `payload` 就是谁，界面底部的标签立刻显示它的名字与样式
 * （外加被选中对象上的黄色高亮框）。
 *
 * <h2>闭环一共三处 API</h2>
 *
 * <pre>
 * // ① 注册（数据变化时一次，不是每帧）：payload 就是命中时想拿回的对象
 * item.pickId = gc.pickRegistry.register(item)
 *
 * // ② 绘制时打标（作用域版：块内的变换与裁剪改动也会回滚）
 * gc.pickable(item.pickId) { item.draw(gc, w, h) }
 *
 * // ③ 鼠标事件 → 异步拾取（回调在 JavaFX 应用线程上）
 * view.addEventHandler(MouseEvent.MOUSE_CLICKED) { e ->
 *     bridge.clickAsyncAtNode(view, e.x, e.y) { hit -> ... }
 * }
 * </pre>
 *
 * <p><strong>坐标换算在库里面做完了</strong>：鼠标事件给的是节点的**逻辑**局部坐标，
 * 而拾取要的是**设备像素**；`clickAsyncAtNode` / `onClick` 负责乘上窗口输出缩放系数
 * （见它们的 Javadoc）。自己调 `pickAsync` 就必须自己乘——漏乘的表现是"点 A 命中 B"，
 * 而画面完全正常。
 *
 * <p>这里用 `clickAsyncAtNode` 而不是更短的 `bridge.onClick(view) { }`：前者能同时拿到
 * 事件的原始坐标，标签里因此可以把"局部 → 设备"的对应关系显示出来（这个示例的一半
 * 价值就是让人看清这两个坐标系）。最短写法见 `ClickDslExample.kt`。
 *
 * <p>布局全部按**帧缓冲尺寸**（`gc.width` / `gc.height`）的比例算，因此换一台机器、
 * 换一个系统缩放都不需要改常数——写死逻辑坐标的话，125% 缩放下画面只覆盖约 81% 宽度。
 *
 * <p>**运行方式**：见 `CLAUDE.md`——必须用 `exec:exec` 另起 JVM，`exec:java` 必崩。
 *
 * ```
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.ClickExampleKt"
 * ```
 */
class ClickExampleApp : Application() {

    /** 桥接对象。GL 初始化回调里会创建 [Gc]，这里只是持有引用。 */
    private var transfer: FXGLTransfer? = null

    /** 画布节点。鼠标事件挂在它上面。 */
    private lateinit var canvas: Node

    /** 底部状态标签：点击结果的可见反馈。 */
    private val status = Label("在画面里的图形或文字上点一下").apply {
        isWrapText = false
        padding = Insets(6.0, 10.0, 6.0, 10.0)
        style = "-fx-font-size: 14px; -fx-text-fill: #1b1b1b;"
    }

    /**
     * 当前被选中的对象。
     *
     * <p>**由 JavaFX 线程写、GL 线程读**（拾取回调在 JavaFX 线程上，高亮在渲染时画），
     * 所以是 `AtomicReference` 而不是普通字段——普通字段在这种跨线程读写下的可见性
     * 没有保证，表现是"点了没反应，偶尔又有反应"。
     */
    private val selected = AtomicReference<ClickItem?>(null)

    /**
     * 场景里全部可点击的对象。只建一次，之后不再增删。
     *
     * <p>用 `lazy` 而不是字段初始化：建它要调 [createItems]，而那里面用到本类的成员
     * 函数（[cell] / [textItem]），在构造期就跑会踩到"还没构造完"的坑。
     * 首次访问发生在 `onInit` 里（GL 线程），之后 `drawScene` 每帧只读。
     */
    private val items: List<ClickItem> by lazy { createItems() }

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
        bridge.onInit {
            bridge.gc()?.let { gc ->
                // 注册发生在数据变化时而不是每帧。这里场景是静态的，所以只注册一次；
                // 反复注册会耗尽 ID 空间（PickRegistry 会抛异常，不会静默）。
                for (item in items) {
                    item.pickId = gc.pickRegistry.register(item)
                }
            }
        }
        bridge.onFrame { gc -> drawScene(gc) }
        transfer = bridge

        val view = bridge.createGlFXView()
        canvas = view
        view.addEventHandler(MouseEvent.MOUSE_CLICKED) { event -> onClick(event) }

        val mainView = MainView().apply {
            center = view
            bottom = status
        }
        stage.title = "JFGL 点击事件示例"
        stage.scene = Scene(mainView.createMainView(), SCENE_W, SCENE_H)
        stage.show()
    }

    /**
     * 鼠标点击的处理：把**节点局部坐标**交给异步拾取。
     *
     * <p>`clickAsyncAtNode` 内部乘上窗口输出缩放系数，把局部坐标换算成设备像素
     * （见它的 Javadoc：这一乘漏掉的话，在 100% 缩放的机器上看不出任何问题）。
     * 这里用点击语义（有界 FIFO、按序交付），**不是** hover 的"最新覆盖旧的"。
     *
     * <p>**注意回调不在本方法里执行**：请求交给 GL 线程，结果经 `Platform.runLater`
     * 送回 JavaFX 线程，通常晚一帧。所以状态更新写在回调里，而不是写在下一行。
     */
    private fun onClick(event: MouseEvent) {
        val bridge = transfer ?: return
        bridge.clickAsyncAtNode(canvas, event.x, event.y) { hit -> onHit(hit, event.x, event.y) }
    }

    /**
     * 拾取结果回调。**在 JavaFX 应用线程上被调用**，可以安全地改界面。
     *
     * @param hit    命中结果；未命中时为 null
     * @param localX 事件的节点局部坐标 x（用来对照显示设备像素）
     * @param localY 事件的节点局部坐标 y
     */
    private fun onHit(hit: PickHit?, localX: Double, localY: Double) {
        val item = hit?.payload() as? ClickItem
        selected.set(item)
        // PickHit 带回的查询点已经是**设备像素**（就是交给 pickAsync 的那个点），
        // 所以"局部 → 设备"的换算结果可以直接显示出来。
        val where = if (hit != null) {
            "局部 ${localX.toInt()}, ${localY.toInt()} → 设备 ${hit.x().toInt()}, ${hit.y().toInt()}"
        } else {
            "局部 ${localX.toInt()}, ${localY.toInt()}"
        }
        status.text = if (item == null) {
            "未命中（$where）：那里没有可拾取的对象"
        } else {
            "命中：${item.name} —— ${item.kind}，${item.style}（$where）"
        }
    }

    /**
     * 画一帧。
     *
     * <p>本方法在 **GL 线程**上执行，因此不能碰任何 JavaFX 控件——界面更新一律在
     * [onHit] 里做。[selected] 是从 JavaFX 线程读来的，用 `AtomicReference` 保证可见性。
     */
    private fun drawScene(gc: Gc) {
        val w = gc.width.toFloat()
        val h = gc.height.toFloat()

        // 背景不属于任何可拾取对象：pickId 保持 0。
        gc.pickId = 0
        gc.fill = BG
        gc.fillRect(0f, 0f, w, h)

        for (item in items) {
            // pickable 是 save/pickId/restore 的作用域版本：块内改的字号、颜色、变换
            // 在块结束时全部回滚，因此"忘了复位 pickId"这类错误不可能发生。
            gc.pickable(item.pickId) { item.draw(gc, w, h) }
        }

        // 选中高亮。画在所有对象之后，此时 pickId 已被 pickable 复原成 0。
        selected.get()?.let { sel ->
            if (sel.pickId != 0) {
                gc.stroke = HIGHLIGHT
                gc.lineWidth = 3f
                gc.strokeRect(sel.bx - 6f, sel.by - 6f, sel.bw + 12f, sel.bh + 12f)
            }
        }
    }

    override fun stop() {
        transfer?.dispose()
    }

    /**
     * 场景描述：每个对象的名字、类别、样式文字，以及它怎么画。
     *
     * <p>用匿名子类而不是 `(Gc, Float, Float) -> Unit` 的 lambda：绘制时需要把
     * **自己的**热区写回去（选中高亮要用），lambda 在构造期拿不到 `this`。
     */
    private abstract inner class ClickItem(
        val name: String,
        val kind: String,
        val style: String
    ) {

        /** 拾取 ID，由 `onInit` 里注册得到。0 表示尚未注册（此时不参与拾取）。 */
        var pickId: Int = 0

        /**
         * 本对象的点击热区（设备像素）。**由 [draw] 每帧写入**，只给 GL 线程画高亮用。
         *
         * <p>不是从外部传入的常量：文字的热区要靠 `measureText` 现量，而布局又依赖
         * 帧缓冲尺寸，让 `draw` 顺手写回来是唯一不会写岔的做法（两处各算一遍迟早对不上，
         * 而高亮框偏了不会有任何报错）。
         */
        var bx = 0f
        var by = 0f
        var bw = 0f
        var bh = 0f

        /** 由帧缓冲尺寸与 [Gc] 算出本对象的矩形，记进 [bx]/[by]/[bw]/[bh]。 */
        protected fun box(x: Float, y: Float, w: Float, h: Float) {
            bx = x
            by = y
            bw = w
            bh = h
        }

        /** 画自己。坐标一律是**设备像素**，尺寸取自已开好的帧。 */
        abstract fun draw(gc: Gc, w: Float, h: Float)
    }

    /**
     * 图形区第 `index` 个格子的矩形（设备像素）。
     *
     * <p>4 列 2 行，占上半部分；文字区在下面。
     */
    private fun cell(index: Int, w: Float, h: Float): FloatArray {
        val cols = 4
        val gridH = h * 0.56f
        val cw = w / cols
        val ch = gridH / 2f
        val col = index % cols
        val row = index / cols
        val pad = minOf(cw, ch) * 0.18f
        return floatArrayOf(col * cw + pad, row * ch + pad, cw - 2f * pad, ch - 2f * pad)
    }

    /** 建出全部对象。形状 8 个（索引 0..7），文本 3 段。 */
    private fun createItems(): List<ClickItem> {
        val list = ArrayList<ClickItem>()

        // 0 只填充、没有边框
        list.add(object : ClickItem("填充矩形", "矩形", "只填充 · 填充色 0xFFE53935") {
            override fun draw(gc: Gc, w: Float, h: Float) {
                val r = cell(0, w, h)
                box(r[0], r[1], r[2], r[3])
                gc.fill = 0xFFE53935.toInt()
                gc.fillRect(r[0], r[1], r[2], r[3])
            }
        })

        // 1 只有描边、不填充：热区只有那圈线
        list.add(object : ClickItem("描边矩形", "矩形", "只描边不填充 · 边框色 0xFF42A5F5 · 线宽 4") {
            override fun draw(gc: Gc, w: Float, h: Float) {
                val r = cell(1, w, h)
                box(r[0], r[1], r[2], r[3])
                gc.stroke = 0xFF42A5F5.toInt()
                gc.lineWidth = 4f
                gc.strokeRect(r[0], r[1], r[2], r[3])
            }
        })

        // 2 填充 + 边框（边框色与填充色不同）
        list.add(
            object : ClickItem("圆角矩形", "圆角矩形", "填充 0xFFFFA726 + 边框 0xFF6D4C41 · 圆角 5") {
                override fun draw(gc: Gc, w: Float, h: Float) {
                    val r = cell(2, w, h)
                    box(r[0], r[1], r[2], r[3])
                    val radius = minOf(r[2], r[3]) * 0.22f
                    gc.fill = 0xFFFFA726.toInt()
                    gc.fillRect(r[0], r[1], r[2], r[3], radius)
                    gc.stroke = 0xFF6D4C41.toInt()
                    gc.lineWidth = 5f
                    gc.strokeRect(r[0], r[1], r[2], r[3], radius)
                }
            })

        // 3 填充圆
        list.add(object : ClickItem("实心圆", "圆", "填充 0xFF66BB6A") {
            override fun draw(gc: Gc, w: Float, h: Float) {
                val r = cell(3, w, h)
                box(r[0], r[1], r[2], r[3])
                gc.fill = 0xFF66BB6A.toInt()
                gc.fillCircle(r[0] + r[2] / 2f, r[1] + r[3] / 2f, minOf(r[2], r[3]) / 2f)
            }
        })

        // 4 描边椭圆
        list.add(object : ClickItem("椭圆", "椭圆", "只描边不填充 · 边框色 0xFFAB47BC · 线宽 5") {
            override fun draw(gc: Gc, w: Float, h: Float) {
                val r = cell(4, w, h)
                box(r[0], r[1], r[2], r[3])
                gc.stroke = 0xFFAB47BC.toInt()
                gc.lineWidth = 5f
                gc.strokeEllipse(r[0] + r[2] / 2f, r[1] + r[3] / 2f, r[2] / 2f, r[3] / 2f)
            }
        })

        // 5 凹多边形（耳切三角化），填充
        list.add(object : ClickItem("凹多边形", "多边形", "填充 0xFF26A69A") {
            override fun draw(gc: Gc, w: Float, h: Float) {
                val r = cell(5, w, h)
                box(r[0], r[1], r[2], r[3])
                gc.fill = 0xFF26A69A.toInt()
                gc.fillPolygon(
                    floatArrayOf(
                        r[0], r[1],
                        r[0] + r[2], r[1],
                        r[0] + r[2], r[1] + r[3],
                        r[0] + r[2] * 0.62f, r[1] + r[3] * 0.45f,
                        r[0], r[1] + r[3]
                    )
                )
            }
        })

        // 6 贝塞尔路径描边
        list.add(object : ClickItem("贝塞尔曲线", "路径", "只描边不填充 · 边框色 0xFFFF7043 · 线宽 5") {
            override fun draw(gc: Gc, w: Float, h: Float) {
                val r = cell(6, w, h)
                // 热区取曲线的外接框（曲线本身是弯的，包围盒更好点）
                box(r[0], r[1] + r[3] * 0.2f, r[2], r[3] * 0.7f)
                gc.stroke = 0xFFFF7043.toInt()
                gc.lineWidth = 5f
                gc.beginPath()
                gc.moveTo(r[0], r[1] + r[3] * 0.85f)
                gc.bezierCurveTo(
                    r[0] + r[2] * 0.3f, r[1] + r[3] * 0.1f,
                    r[0] + r[2] * 0.7f, r[1] + r[3] * 1.1f,
                    r[0] + r[2], r[1] + r[3] * 0.35f
                )
                gc.strokePath()
            }
        })

        // 7 粗描边圆（边框色再换一种）
        list.add(object : ClickItem("圆环", "圆", "只描边不填充 · 边框色 0xFFEF5350 · 线宽 9") {
            override fun draw(gc: Gc, w: Float, h: Float) {
                val r = cell(7, w, h)
                box(r[0], r[1], r[2], r[3])
                gc.stroke = 0xFFEF5350.toInt()
                gc.lineWidth = 9f
                gc.strokeCircle(r[0] + r[2] / 2f, r[1] + r[3] / 2f, minOf(r[2], r[3]) / 2f - 4f)
            }
        })

        // 8/9/10 三段不同字号、不同颜色的文本。
        // (x, y) 里 y 是**基线**，不是文本框左上角——见 drawText 的说明。
        list.add(textItem("标题文本（32px）", "文本 32px", 0xFFFFD54F.toInt(), 32f, "点中这一段 32 像素的标题", 0.70f))
        list.add(textItem("正文文本（22px）", "文本 22px", 0xFF80DEEA.toInt(), 22f, "点中这一段 22 像素的正文", 0.80f))
        list.add(textItem("注释文本（14px）", "文本 14px", 0xFFB0BEC5.toInt(), 14f, "点中这一段 14 像素的注释", 0.89f))

        return list
    }

    /**
     * 造一个文本对象。
     *
     * @param name     显示名
     * @param size     字号（像素）
     * @param color    ARGB 填充色
     * @param content  文本内容
     * @param baseline 基线的 y（占帧缓冲高度的比例）
     */
    private fun textItem(
        name: String,
        kind: String,
        color: Int,
        size: Float,
        content: String,
        baseline: Float
    ): ClickItem = object : ClickItem(name, kind, "字号 $size · 颜色 0x${hex(color)}") {
        override fun draw(gc: Gc, w: Float, h: Float) {
            val x = w * 0.05f
            val y = h * baseline
            gc.fontSize = size
            gc.fill = color
            val advance = gc.drawText(content, x, y)
            // 热区：从基线往上一个字号、往下 0.3 个字号，宽度取实际推进宽度。
            // 文本的**可拾取**范围其实比这还大一圈（整个 SDF 位图矩形，含四周外扩），
            // 这里只用来画选中高亮，所以用文本自身的包围盒更贴切。
            box(x, y - size, advance, size * 1.3f)
        }
    }

    companion object {
        /** 把 ARGB 整数格式化成 `AARRGGBB`，只用于界面上的文字说明。 */
        private fun hex(argb: Int): String = String.format("%08X", argb)
    }
}

/**
 * 示例入口。
 *
 * <p>函数名不叫 `main`：同包已有一个顶层 `main()`（[PipelineExample]），
 * 两个同名顶层函数会让 `import ...example.main` 报重载歧义。用 `@JvmName("main")`
 * 把 JVM 方法名钉回 `main`，文档里的命令行因此照常可用。
 */
@JvmName("main")
fun clickExampleMain() {
    Application.launch(ClickExampleApp::class.java)
}
