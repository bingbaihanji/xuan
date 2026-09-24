package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.dsl.jfgl
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.renderer.PickHit

/**
 * 用 **`jfgl { }` DSL** 走一遍完整的点击闭环。
 *
 * <p>存在的理由：`ClickExample` 是拿 `FXGLTransfer` 自己搭窗口的（要看坐标换算的细节），
 * 而 README 的快速开始用的是 `jfgl { }`。**从文档宣称的入口必须也能接上"点击"**，
 * 否则那个功能对按文档入门的用户等于不存在。这个文件就是那条路的证明：
 * 整个应用只有 `onInit` / `onRender` / `onClick` 三块，没有一处手工搭 `Scene`/`Stage`，
 * 也没有一处手写坐标换算。
 *
 * <p>与 `ClickExample` 的两点差别：
 * - **反馈画在画布上**而不是 JavaFX 控件里（DSL 只把画布放进场景图，不暴露 Label 之类的
 *   容器），所以命中结果用 `drawText` 画在顶部，选中项加一圈黄色高亮；
 * - 每次点击额外 `println` 一行，好让"真实鼠标点下去确实有反应"这件事**可以被自动核对**
 *   （一个只画在 GL 画布上的示例，截图是抓不到的——见 `CLAUDE.md` 里那条）。
 *
 * <p>**运行方式**同其它示例：必须 `exec:exec` 另起 JVM（`exec:java` 必崩）：
 * ```
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.ClickDslExampleKt"
 * ```
 */
@JvmName("main")
fun clickDslMain() {
    jfgl {
        title = "JFGL 点击事件（DSL 版）"
        width = 760.0
        height = 560.0

        // 注册发生在数据变化时，不是每帧：注册表持有 payload 的强引用，
        // 每帧重注册会耗尽 ID 空间（PickRegistry 会抛异常，不会静默）。
        onInit { gc -> DslScene.register(gc) }

        onRender { DslScene.draw(this) }

        // 一行接上鼠标。坐标换算、事件注册、点击队列都在里面（见 JFGL.onClick 的说明）。
        onClick { hit -> DslScene.onHit(hit) }
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
        // 一个真实应用这里会去改 Label；DSL 不暴露容器，所以改为画在画布上 + 打一行日志。
        // 打日志不只是给人看：GL 画布上的内容截图抓不到，这行 stdout 是这条链路唯一的
        // 可自动核对的出口。
        println("[点击] $status")
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
