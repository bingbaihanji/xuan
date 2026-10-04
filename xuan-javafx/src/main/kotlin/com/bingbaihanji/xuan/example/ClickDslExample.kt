package com.bingbaihanji.xuan.example

import com.bingbaihanji.xuan.dsl.xuan
import com.bingbaihanji.xuan.example.DslScene.status
import com.bingbaihanji.xuan.renderer.Gc
import com.bingbaihanji.xuan.renderer.PickHit
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import kotlin.system.exitProcess

/**
 * 用 **`xuan { }` DSL** 走一遍完整的点击闭环。
 *
 * <p>存在的理由：`ClickExample` 是拿 `FXGLTransfer` 自己搭窗口的（要看坐标换算的细节），
 * 而 README 的快速开始用的是 `xuan { }`。**从文档宣称的入口必须也能接上"点击"**，
 * 否则那个功能对按文档入门的用户等于不存在。这个文件就是那条路的证明：
 * 整个应用只有 `onInit` / `onRender` / `onClick` 三块，没有一处手工搭 `Scene`/`Stage`，
 * 也没有一处手写坐标换算。
 *
 * <p>与 `ClickExample` 的三点差别：
 * - **反馈同时画在画布上和 JavaFX 控件里**：命中结果既用 `drawText` 画在顶部
 *   （画布上的反馈，选中项另加一圈黄色高亮），也写进叠加层里的一个 `Label`
 *   （`xuan { onScene { overlay.children.add(...) } }`）。后者是这一版新加的：
 *   在此之前 DSL **不暴露任何容器**，用户按 README 的快速开始起步时加不了控件；
 * - 每次点击额外 `println` 一行，好让"真实鼠标点下去确实有反应"这件事**可以被自动核对**
 *   （一个只画在 GL 画布上的示例，截图是抓不到的——见 `CLAUDE.md` 里那条）；
 * - 启动时做一次**结构自检**：`Label` 真的在场景图里、叠加层真的在画布之上。
 *   自检失败就打印一行到 stderr 并以非 0 退出——"窗口看起来正常"与"控件没接上"
 *   在截图里分不出来（控件本来就是空的），所以不能只靠肉眼看。
 *
 * <p>**运行方式**同其它示例：必须 `exec:exec` 另起 JVM（`exec:java` 必崩）：
 * ```
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.xuan.example.ClickDslExampleKt"
 * ```
 */
@JvmName("main")
fun clickDslMain() {
    xuan {
        title = "Xuan 点击事件（DSL 版）"
        // 字体不再自带（2026-09-28）：从 -Dxuan.text.font=<路径> 取；没设则文本会抛异常
        font = textFontFile()
        width = 760.0
        height = 560.0

        // 注册发生在数据变化时，不是每帧：注册表持有 payload 的强引用，
        // 每帧重注册会耗尽 ID 空间（PickRegistry 会抛异常，不会静默）。
        onInit { gc -> DslScene.register(gc) }

        onRender { DslScene.draw(this) }

        // 一行接上鼠标。坐标换算、事件注册、点击队列都在里面（见 Xuan.onClick 的说明）。
        onClick { hit -> DslScene.onHit(hit) }

        // JavaFX 线程、窗口显示之前调一次：这里是**唯一**能安全往场景图里加控件的时机
        // （onInit / onRender 都在 GL 线程上，在那里碰场景图会崩得毫无规律）。
        onScene { scene -> DslScene.attachLabel(scene, overlay) }
    }
}

/**
 * 一个可点击对象。`payload` 就是它本身，所以回调里拿到的东西**带着它的样式**，
 * 而不只是一个 ID。
 */
private class DslItem(val name: String, val kind: String, val color: Int) {
    /** 拾取 ID，注册时分配。0 表示尚未注册（此时不参与拾取）。 */
    var id: Int = 0
}

/**
 * 示例的共享状态。
 *
 * <p>**跨线程**：`onClick` 在 JavaFX 应用线程上被调用，`onRender` 在 GL 线程上，
 * 所以这两个字段是 `@Volatile`——普通字段在这种读写下的可见性没有保证，
 * 表现是"点了没反应，偶尔又有反应"。这是接点击时最容易踩的一脚。
 */
private object DslScene {

    /** 场景里的对象。顺序即绘制顺序（也就是 z 序）。 */
    private val items = listOf(
        DslItem("填充矩形", "矩形（只填充）", 0xFFE53935.toInt()),
        DslItem("描边矩形", "矩形（只描边）", 0xFF42A5F5.toInt()),
        DslItem("圆角矩形", "圆角矩形（填充+边框）", 0xFFFFA726.toInt()),
        DslItem("实心圆", "圆", 0xFF66BB6A.toInt()),
        DslItem("椭圆", "椭圆（只描边）", 0xFFAB47BC.toInt()),
        DslItem("多边形", "凹多边形", 0xFF26A69A.toInt())
    )

    /** 当前选中项（由点击写入）。 */
    @Volatile
    private var selected: DslItem? = null

    /** 顶部那行状态文字。 */
    @Volatile
    private var status: String = "在图形上点一下"

    /**
     * 叠加层里的那个 `Label`。
     *
     * <p>`@Volatile` 的理由与 [status] 相同：它在 JavaFX 线程上被创建与改写，
     * 而 GL 线程上的每帧绘制也会读它（自检那一行）。普通字段在这种读写下的可见性
     * 没有保证，表现是"点了没反应，偶尔又有反应"。
     */
    @Volatile
    private var label: Label? = null

    /** 绘制区尺寸，只在第一帧打印一次（自动化核对点击位置时要用）。 */
    @Volatile
    private var sizeReported = false

    /** 在 `onInit` 里注册全部对象。 */
    fun register(gc: Gc) {
        for (item in items) {
            item.id = gc.pickRegistry.register(item)
        }
    }

    /** 点击回调。在 JavaFX 应用线程上执行。 */
    fun onHit(hit: PickHit?) {
        val item = hit?.payload() as? DslItem
        selected = item
        val where = if (hit != null) "设备像素 ${hit.x().toInt()}, ${hit.y().toInt()}" else "空白处"
        status = if (item == null) "未命中（$where）" else "命中：${item.name} —— ${item.kind}（$where）"
        // 这里在 JavaFX 应用线程上，所以**可以直接改控件**——这正是叠加层存在的意义。
        label?.text = status
        // 再打一行日志：GL 画布上的内容截图抓不到，这行 stdout 是这条链路唯一的
        // 可自动核对的出口。
        println("[点击] $status")
    }

    /**
     * 在 JavaFX 线程上把 `Label` 放进叠加层，并做一次**结构自检**。
     *
     * <p>自检为什么不是可选的：一个没接上的控件在截图上就是"画面里什么都没有"，
     * 而那与"控件是空的"完全一样。这里断言两件事——`Label` 真的进了场景图
     * （`scene != null`），以及叠加层在画布**之后**（StackPane 的 children 顺序就是 z 序，
     * 也就是"浮在画面上"）。任一条不成立就打印到 stderr 并以非 0 退出，
     * 好让"这个示例跑通了"有据可查。
     */
    fun attachLabel(scene: Scene, overlay: Pane) {
        val l = Label("在图形上点一下（这一行是 JavaFX 控件，浮在画布之上）").apply {
            style = "-fx-text-fill: #FFE082; -fx-background-color: rgba(21,24,28,0.75);" +
                    " -fx-padding: 4 8 4 8;"
            layoutX = 12.0
            layoutY = 12.0
        }
        overlay.children.add(l)
        label = l

        val stack = overlay.parent as? StackPane
        val canvas = stack?.children?.firstOrNull()
        val canvasBelow = canvas != null && canvas !== overlay &&
                stack.children.indexOf(canvas) < stack.children.indexOf(overlay)
        val attached = l.scene != null && overlay.scene != null && canvasBelow
        println(
            "[叠加层] 控件在场景图里=${l.scene != null}，叠加层在画布之上=$canvasBelow，" +
                    "根节点=${scene.root.javaClass.simpleName}，控件数=${overlay.children.size}"
        )
        if (!attached) {
            System.err.println("[叠加层] 自检失败：Label 没有接上场景图，或叠加层不在画布之上")
            exitProcess(1)
        }

        // 第二条自检要等到**布局跑过一次之后**（此刻窗口还没 show，控件的宽高都是 0）。
        // 它问的是"叠加层会不会把画布上的点击吃掉"：`isPickOnBounds = false` 且没有背景的
        // 容器**不认领任何点**，所以画布照旧收到点击（`onClick` 不会因为这个容器而失灵）。
        // 点在 Label 自己那块地方上时会被它吃掉——那是对的，点在控件上不该同时命中画布。
        Platform.runLater {
            val cx = overlay.width / 2
            val cy = overlay.height / 2
            val passThrough = overlay.width > 0 && overlay.height > 0 &&
                    !overlay.contains(cx, cy)
            println(
                "[叠加层] 布局后 ${overlay.width.toInt()}x${overlay.height.toInt()}，" +
                        "容器不吃中心点的鼠标事件=$passThrough"
            )
            if (!passThrough) {
                System.err.println(
                    "[叠加层] 自检失败：叠加层认领了它自己的整块区域，会吃掉画布上的点击" +
                            "（isPickOnBounds 应为 false，且容器不该有背景）"
                )
                exitProcess(1)
            }
        }
    }

    /** 画一帧。在 GL 线程上执行——不要在这里碰 JavaFX 控件。 */
    fun draw(gc: Gc) {
        val w = gc.width.toFloat()
        val h = gc.height.toFloat()
        if (!sizeReported) {
            sizeReported = true
            println("[绘制区] ${gc.width}x${gc.height} 设备像素")
        }

        gc.pickId = 0
        gc.fill = 0xFF15181C.toInt()
        gc.fillRect(0f, 0f, w, h)

        // 顶部两行文字：标题 + 命中结果。它们**不参与拾取**（pickId 为 0）。
        gc.fill = 0xFF90A4AE.toInt()
        gc.fontSize = 14f
        gc.drawText("点一下下面的图形，看这里显示什么", w * 0.04f, h * 0.045f)
        gc.fill = 0xFFFFE082.toInt()
        gc.fontSize = 18f
        gc.drawText(status, w * 0.04f, h * 0.10f)

        // 3 列 2 行的网格。全部按帧缓冲尺寸的比例算：换机器、换缩放都不用改常数。
        val cols = 3
        val top = h * 0.16f
        val cellW = w / cols
        val cellH = (h * 0.94f - top) / 2f
        val pad = minOf(cellW, cellH) * 0.18f

        for ((index, item) in items.withIndex()) {
            val cx = (index % cols) * cellW
            val cy = top + (index / cols) * cellH
            val x = cx + pad
            val y = cy + pad
            val cw = cellW - 2f * pad
            val ch = cellH - 2f * pad
            // pickable 是 save/pickId/restore 的作用域版本：块内改的字号、颜色、
            // 变换在块结束时全部回滚，因此"忘了复位 pickId"这类错误不可能发生。
            gc.pickable(item.id) { drawItem(gc, item, x, y, cw, ch) }
        }

        // 选中高亮。画在所有对象之后，此时 pickId 已被 pickable 复原成 0。
        selected?.let { item ->
            val index = items.indexOf(item)
            if (index >= 0) {
                val cx = (index % cols) * cellW
                val cy = top + (index / cols) * cellH
                gc.stroke = 0xFFFFEB3B.toInt()
                gc.lineWidth = 3f
                gc.strokeRect(cx + pad - 6f, cy + pad - 6f, cellW - 2f * pad + 12f, cellH - 2f * pad + 12f)
            }
        }
    }

    /** 按对象自己的样式画它。坐标是**设备像素**。 */
    private fun drawItem(gc: Gc, item: DslItem, x: Float, y: Float, w: Float, h: Float) {
        when (item.name) {
            "填充矩形" -> {
                gc.fill = item.color
                gc.fillRect(x, y, w, h)
            }

            "描边矩形" -> {
                gc.stroke = item.color
                gc.lineWidth = 5f
                gc.strokeRect(x, y, w, h)
            }

            "圆角矩形" -> {
                val r = minOf(w, h) * 0.2f
                gc.fill = item.color
                gc.fillRect(x, y, w, h, r)
                gc.stroke = 0xFF5D4037.toInt()
                gc.lineWidth = 5f
                gc.strokeRect(x, y, w, h, r)
            }

            "实心圆" -> {
                gc.fill = item.color
                gc.fillCircle(x + w / 2f, y + h / 2f, minOf(w, h) / 2f)
            }

            "椭圆" -> {
                gc.stroke = item.color
                gc.lineWidth = 5f
                gc.strokeEllipse(x + w / 2f, y + h / 2f, w / 2f, h / 2f)
            }

            "多边形" -> {
                gc.fill = item.color
                gc.fillPolygon(
                    floatArrayOf(
                        x, y,
                        x + w, y,
                        x + w, y + h,
                        x + w * 0.62f, y + h * 0.45f,
                        x, y + h
                    )
                )
            }

            else -> error("示例里有一个没写画法的对象：${item.name}")
        }
    }
}
