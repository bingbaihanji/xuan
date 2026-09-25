@file:JvmName("MsaaProbe")

package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glGetInteger
import org.lwjgl.opengl.GL11.glReadPixels
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * **临时探针**：`GLCanvas` 的 `msaa` 参数到底有没有真的建出多采样缓冲。
 *
 * <p>存在的理由：设计文档写着"抗锯齿：MSAA。`GLCanvas` 构造函数已提供 `msaa` 参数，
 * 零额外实现成本"，但那是**从未验证过的推断**——`GLCanvas.Defaults.MSAA` 常量是 0，
 * 仓库 15 处调用点**没有一处传过 `msaa`**，这条路**一次都没跑起来过**。
 * 本仓库有前科（`GPUFFT.java` 当年也是"能编译"被当成"现成可用"，实际从未成功运行）。
 *
 * <p>**判据是行为证据，不是那个常量**：`GL_SAMPLES` 读回来是 4 只能说明"某个 FBO 报了 4"，
 * 说明不了"光栅化真的按 4 个子样本做了覆盖"。所以还要**画一条斜边、回读边缘像素**——
 * 抗锯齿真的生效时，边缘会出现在 0 与 255 之间的**中间值**；没生效时只有 0 与 255。
 *
 * <p>**跑法**（一个进程一个 msaa 值，因为它只能在构造时给）：
 * ```
 * mvn -o -f jfgl-javafx/pom.xml compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
 *   "-Dexec.args=-Dstdout.encoding=UTF-8 -Djfgl.probe.msaa=4 -cp %classpath com.bingbaihanji.jfgl.example.MsaaProbe"
 * ```
 *
 * <p>**它不 dispose**：探针跑完直接 `halt`。理由与自检那条相反——自检要反复跑、
 * 漏 GL 对象会积累；探针是**一次性**的，进程一死对象就没了，而
 * `halt` 避开关闭钩子正是为了不撞上本仓库实测过的那次原生崩溃。
 */
@JvmName("main")
fun msaaProbeMain() {
    Application.launch(MsaaProbeApp::class.java)
}

class MsaaProbeApp : Application() {

    private var transfer: FXGLTransfer? = null
    private var frame = 0
    private var done = false
    private var canvasNode: javafx.scene.Node? = null

    /** 要测的采样数。**只在这里读一次**，构造 `FXGLTransfer` 时用掉。 */
    private val requestedMsaa: Int = System.getProperty("jfgl.probe.msaa", "0").toIntOrNull() ?: 0

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer(msaa = requestedMsaa)
        bridge.onFrame { gc -> drawScene(gc) }
        bridge.onRender { probeOnce() }
        transfer = bridge

        val canvas = bridge.createGlFXView()
        canvasNode = canvas
        val mainView = MainView().apply { center = canvas }
        stage.title = "JFGL MSAA Probe"
        stage.scene = Scene(mainView.createMainView(), 900.0, 700.0)
        stage.show()
    }

    /**
     * 画面刻意只有两样东西：一个**白圆**与一条**1px 斜线**，背景纯黑。
     *
     * <p>两者选得都有理由：圆的边缘是**任意方向**的（水平扫描线在圆心那一行穿过的是
     * 竖直切线，在别的行穿过的是斜边），斜线则保证至少有一处边缘是以浅角度穿过像素格的
     * ——那正是"部分覆盖"最容易出现、也最容易看出来的地方。
     */
    private fun drawScene(gc: Gc) {
        val w = gc.width.toFloat()
        val h = gc.height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) * 0.30f

        gc.fill = 0xFFFFFFFF.toInt()
        gc.fillCircle(cx, cy, r)                       // 圆：边缘方向连续变化

        gc.stroke = 0xFFFFFFFF.toInt()
        gc.lineWidth = 1f
        gc.drawLine(40f, 40f, w - 40f, h - 40f)        // 1px 斜线：浅角度穿过像素格

        // 拾取探针：一块可拾取的矩形，位于右上角（不与圆重叠），ID 用 7。
        // 它存在的唯一理由是回答"msaa>0 时拾取还灵不灵"——拾取 FBO 是**独立的单采样 FBO**，
        // 理论上不受画布多采样影响，但它的读回路径也是 glReadPixels 一类，**必须实测**。
        gc.pickId = 7
        gc.fill = 0xFFFF00FF.toInt()
        gc.fillRect(w - 260f, 60f, 200f, 120f)
        gc.pickId = 0
    }

    private fun probeOnce() {
        val bridge = transfer ?: return
        if (done) return
        if (++frame < 6) return          // 等几帧，让画布尺寸与内容都稳定下来
        done = true
        val w = bridge.scaledWidth
        val h = bridge.scaledHeight
        if (w <= 0 || h <= 0) { done = false; return }

        println("=== MSAA 探针 ===")
        println("requested msaa            = $requestedMsaa")
        println("framebuffer               = ${w}x${h}")
        // 0x8CA6 = GL_FRAMEBUFFER_BINDING（在 GL30 里，GL11 没有这个常量）
        println("GL_FRAMEBUFFER_BINDING    = ${glGetInteger(0x8CA6)}")
        // 0x80A9 = GL_SAMPLES，0x80A8 = GL_SAMPLE_BUFFERS，0x8D57 = GL_MAX_SAMPLES
        println("GL_SAMPLE_BUFFERS(bound)  = ${glGetInteger(0x80A8)}")
        println("GL_SAMPLES(bound)         = ${glGetInteger(0x80A9)}")
        println("GL_MAX_SAMPLES            = ${glGetInteger(0x8D57)}")

        // ---- 读数路径 A：glReadPixels（**多采样 FBO 上这是非法操作**）----
        val buf = ByteBuffer.allocateDirect(w * h * 4)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        val errA = org.lwjgl.opengl.GL11.glGetError()
        println("★ glReadPixels 之后 glGetError = $errA" +
            (if (errA == 0x0502) "  ← GL_INVALID_OPERATION（多采样 FBO 上 glReadPixels 非法，读数**不可信**）" else ""))
        printProfile("A glReadPixels", w, h, buf, bottomUp = true)

        // ---- 拾取在 msaa>0 时还灵不灵 ----
        // 本函数跑在 onRender 里，即 `endFrame()` **之后**、`pickBufferValid == true` 的那一刻
        // ——正是 pick/pickRect 唯一合法的时机（在 onFrame 里调是恒返回空的）。
        val gc = bridge.gc()
        val probeX = (w - 160f)          // 那个洋红矩形的中心
        val probeY = 120f
        val hit = gc?.pick(probeX, probeY)
        val miss = gc?.pick(w - 160f, h - 60f)   // 右下角空白处
        println("★ 拾取(msaa=$requestedMsaa)：可拾取处 -> id=${hit?.id() ?: 0}" +
            "（期望 7）；空白处 -> id=${miss?.id() ?: 0}（期望 0）")
        val rect = gc?.pickRect(w - 260f, 60f, 200f, 120f)
        println("★ 区域拾取(msaa=$requestedMsaa)：命中 ${rect?.size ?: -1} 个，id=${rect?.map { it.id() }}（期望含 7）")

        // ---- 读数路径 B：JavaFX 快照（**完全绕开 glReadPixels**）----
        // 它走的是 JavaFX 合成器读那个共享纹理，也就是**用户眼睛看到的东西**。
        // 两条路径若不一致，说明「GL 侧没问题、只是回读方式非法」——那与
        // 「openglfx 的 MSAA 根本画不出东西」是两回事，不能混为一谈。
        Platform.runLater {
            try {
                snapshotPhase(bridge)
            } catch (t: Throwable) {
                println("快照阶段抛异常：${t::class.simpleName}: ${t.message}")
                t.printStackTrace()
            }
            System.out.flush()
            Platform.exit()
            Runtime.getRuntime().halt(0)
        }
    }

    /** 打印一条读数路径的统计与两条剖面。 */
    private fun printProfile(tag: String, w: Int, h: Int, buf: ByteBuffer, bottomUp: Boolean) {
        fun lum(x: Int, y: Int): Int {
            if (x < 0 || y < 0 || x >= w || y >= h) return -1
            val i = ((if (bottomUp) h - 1 - y else y) * w + x) * 4
            val r = buf.get(i).toInt() and 0xFF
            val g = buf.get(i + 1).toInt() and 0xFF
            val b = buf.get(i + 2).toInt() and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000          // 亮度，0..255
        }
        val hist = HashMap<Int, Int>()
        for (y in 0 until h) for (x in 0 until w) hist.merge(lum(x, y), 1, Int::plus)
        // 背景是 0x333333（亮度 51）、图元是纯白（255）。除这两者之外的都算"边缘中间值"。
        val edge = hist.entries.filter { it.key != 51 && it.key != 255 && it.key >= 0 }
            .sumOf { it.value }
        println("[$tag] 不同亮度值 ${hist.size} 个；背景(51)=${hist[51] ?: 0}  纯白(255)=${hist[255] ?: 0}" +
            "  ★ 既非背景也非纯白的像素=${edge}")
        val top = hist.entries.sortedByDescending { it.value }.take(6)
            .joinToString("  ") { "lum${it.key}×${it.value}" }
        println("[$tag] 出现最多的亮度值：$top")

        val cxi = w / 2
        val cyi = h / 2
        val ri = (min(w, h) * 0.30).toInt()
        val from = (cxi - ri - 5).coerceAtLeast(0)
        val to = (cxi - ri + 5).coerceAtMost(w - 1)
        println("[$tag] 圆心行左缘剖面 x=$from..$to : " +
            (from..to).joinToString(" ") { lum(it, cyi).toString().padStart(3) })
        println("[$tag] 圆心行右缘剖面 x=${cxi + ri - 5}..${cxi + ri + 5} : " +
            ((cxi + ri - 5)..(cxi + ri + 5)).joinToString(" ") { lum(it, cyi).toString().padStart(3) })
    }

    /** 用 JavaFX 的快照读回画布内容——**与 `glReadPixels` 完全独立的一条路径**。 */
    private fun snapshotPhase(bridge: FXGLTransfer) {
        val node = canvasNode ?: run { println("没有画布节点，跳过快照"); return }
        val img = node.snapshot(null, null)
        // `Image.getWidth()` 返回的是 **double**（不是 Int）——不收窄的话后面全是 Double 运算。
        val w = img.width.toInt()
        val h = img.height.toInt()
        if (w <= 0 || h <= 0) { println("快照尺寸为 0"); return }
        val reader = img.pixelReader
        val buf = ByteBuffer.allocateDirect(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val argb = reader.getArgb(x, y)
            val i = (y * w + x) * 4
            buf.put(i, ((argb shr 16) and 0xFF).toByte())
            buf.put(i + 1, ((argb shr 8) and 0xFF).toByte())
            buf.put(i + 2, (argb and 0xFF).toByte())
            buf.put(i + 3, ((argb ushr 24) and 0xFF).toByte())
        }
        // 快照的 y 已经是向下（JavaFX 口径），所以 bottomUp = false。
        printProfile("B snapshot ${w}x${h}", w, h, buf, bottomUp = false)
    }
}
