package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING
import java.nio.ByteBuffer
import kotlin.system.exitProcess

/**
 * 批处理管线的端到端**像素校验器**：画一个各图元颜色、位置、尺寸都已知的场景，
 * 回读帧缓冲，逐项比对像素数与包围盒，失败则以非零码退出。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>本管线的多数缺陷属于「静默错误输出」：编译通过、单元测试全绿、画面却是错的。
 * 原因是单元测试的口径与几何无关——`StrokeGenerator` 的测试断言"闭合面积大于开放面积"，
 * 它<strong>自己是对的</strong>；错的是调用方忘了传 `closed = true`。
 * 这类缺陷只有把**最终像素**作为口径才拦得住。
 *
 * <p>本文件就是这么来的：它第一次运行时就发现 `strokeRect/strokeCircle/strokeEllipse`
 * 全部漏掉了闭合边（矩形整整少一条边），而 124 个单元测试没有一个发现。
 *
 * <h2>运行</h2>
 *
 * ```
 * mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
 *     -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.PipelineVerifierKt"
 * ```
 *
 * <p>退出码 0 = 全部通过，1 = 有断言失败（失败详情打印在 stdout）。
 * 它会自己关窗退出，不需要手动关闭。
 *
 * <h2>坐标前提</h2>
 *
 * <p>当前管线把用户坐标**按 1:1 映射到设备像素**（`beginFrame` 拿到的是 DPI 缩放后的
 * `scaledWidth/scaledHeight`，而基础矩阵恰好使 `(u,v)` 落在设备像素 `(u,v)`）。
 * 因此下面的期望值可以直接用用户坐标书写。若日后引入真正的 DPI 缩放，本文件的
 * 期望值需要乘以缩放系数——那时这些断言会失败，正是它该提醒的。
 */
private const val SCENE_W = 800
private const val SCENE_H = 600

/** 判定用的容差：圆弧用折线逼近，像素数必然略少于解析值。 */
private const val TOLERANCE = 0.05

fun main() {
    Application.launch(PipelineVerifierApp::class.java)
}

class PipelineVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null
    private var frame = 0

    // 场景配色。每个图元都用与背景(0x333333)及其余图元**可区分**的颜色：
    // 原示例的凹多边形用 0xFF333333，与 glClearColor(0.2,0.2,0.2) 完全相同，
    // 于是"多边形到底画出来没有"根本无从判断——这本身就是个应当避免的示例缺陷。
    private val red = 0xFF0000
    private val green = 0x00FF00
    private val blue = 0x0000FF
    private val cyan = 0x00FFFF
    private val magenta = 0xFF00FF
    private val background = 0x333333

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
        bridge.onFrame { gc -> drawScene(gc) }
        bridge.onRender { verifyOnce() }
        transfer = bridge

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "JFGL Pipeline Verifier"
        stage.scene = Scene(mainView.createMainView(), SCENE_W.toDouble(), SCENE_H.toDouble())
        stage.show()
    }

    private fun drawScene(gc: Gc) {
        gc.fill = 0xFFFF0000.toInt()
        gc.fillRect(50f, 50f, 200f, 120f)

        gc.fill = 0xFF00FF00.toInt()
        gc.fillCircle(500f, 200f, 80f)

        gc.stroke = 0xFF0000FF.toInt()
        gc.lineWidth = 4f
        gc.strokeRect(100f, 300f, 250f, 150f, radius = 16f)

        gc.fill = 0xFF00FFFF.toInt()
        gc.fillPolygon(floatArrayOf(500f, 300f, 700f, 300f, 700f, 450f, 600f, 520f, 500f, 450f))

        gc.stroke = 0xFFFF00FF.toInt()
        gc.lineWidth = 3f
        gc.beginPath()
        gc.moveTo(50f, 550f)
        gc.bezierCurveTo(200f, 450f, 300f, 650f, 450f, 550f)
        gc.strokePath()
    }

    private fun verifyOnce() {
        val bridge = transfer ?: return
        if (++frame < 5) return
        val w = bridge.scaledWidth
        val h = bridge.scaledHeight
        if (w <= 0 || h <= 0) return

        val buf = ByteBuffer.allocateDirect(w * h * 4)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        buf.position(0)

        println("=== JFGL 管线像素校验 ===")
        println("帧缓冲 ${w}x${h}  GL_FRAMEBUFFER_BINDING=${glGetInteger(GL_FRAMEBUFFER_BINDING)}  glGetError=${glGetError()}")

        // glReadPixels 行序自下而上；用户坐标 y 向下，故翻转回读行号。
        fun pixelAt(x: Int, y: Int): Int {
            val i = ((h - 1 - y) * w + x) * 4
            return ((buf.get(i).toInt() and 0xFF) shl 16) or
                    ((buf.get(i + 1).toInt() and 0xFF) shl 8) or
                    (buf.get(i + 2).toInt() and 0xFF)
        }

        // 一次全图扫描，同时得到每色像素数与包围盒。
        val counts = HashMap<Int, Int>()
        val minX = HashMap<Int, Int>(); val minY = HashMap<Int, Int>()
        val maxX = HashMap<Int, Int>(); val maxY = HashMap<Int, Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixelAt(x, y)
                counts[c] = (counts[c] ?: 0) + 1
                if ((minX[c] ?: Int.MAX_VALUE) > x) minX[c] = x
                if ((maxX[c] ?: Int.MIN_VALUE) < x) maxX[c] = x
                if ((minY[c] ?: Int.MAX_VALUE) > y) minY[c] = y
                if ((maxY[c] ?: Int.MIN_VALUE) < y) maxY[c] = y
            }
        }

        fun countIn(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int): Int {
            var n = 0
            for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(h - 1)) {
                for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(w - 1)) {
                    if (pixelAt(x, y) == argb) n++
                }
            }
            return n
        }

        val failures = ArrayList<String>()

        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        fun approx(label: String, actual: Int, expected: Double, tol: Double = TOLERANCE) {
            val lo = expected * (1 - tol); val hi = expected * (1 + tol)
            report(label, actual.toDouble() in lo..hi,
                "实际 $actual，期望 ${expected.toInt()} ±${(tol * 100).toInt()}%")
        }

        println("\n-- 形状：像素数 --")
        approx("红矩形填充 200x120", counts[red] ?: 0, 200.0 * 120)
        approx("绿圆填充 r=80", counts[green] ?: 0, Math.PI * 80 * 80)
        // 凹多边形（耳切三角化）：鞋带公式面积 = 37000，三角化应当精确命中而非近似。
        approx("青凹多边形填充", counts[cyan] ?: 0, 37000.0, 0.01)
        // 圆角矩形描边：中心线周长 2*(250-32)+2*(150-32)+2π*16 ≈ 772.5，线宽 4。
        approx("蓝圆角矩形描边", counts[blue] ?: 0, 772.5 * 4)
        report("品红贝塞尔描边存在", (counts[magenta] ?: 0) > 200, "实际 ${counts[magenta] ?: 0} px")
        report("画面只有 6 种颜色（无杂散像素）", counts.size == 6, "实际 ${counts.size} 种：${counts.keys.sorted().joinToString { "#%06X".format(it) }}")

        println("\n-- 形状：包围盒 --")
        fun bbox(label: String, argb: Int, ex: Int, ey: Int, ew: Int, eh: Int) {
            if ((counts[argb] ?: 0) == 0) { report(label, false, "无像素"); return }
            val ax = minX[argb]!!; val ay = minY[argb]!!
            val aw = maxX[argb]!! - ax + 1; val ah = maxY[argb]!! - ay + 1
            report(label, ax == ex && ay == ey && aw == ew && ah == eh,
                "实际 ($ax,$ay) ${aw}x$ah，期望 ($ex,$ey) ${ew}x$eh")
        }
        bbox("红矩形位置尺寸", red, 50, 50, 200, 120)
        bbox("绿圆位置尺寸", green, 420, 120, 160, 160)
        // 凹多边形 x∈[500,700)、y∈[300,520)：宽 200、高 220。
        bbox("青多边形位置尺寸", cyan, 500, 300, 200, 220)
        // 描边向路径两侧各扩半个线宽：路径 100..350 / 300..450 → 98..351 / 298..451。
        bbox("蓝描边外沿（98..351 x 298..451）", blue, 98, 298, 254, 154)

        println("\n-- 描边：四条边分别计数 --")
        // 这是本文件存在的直接原因：闭合边曾经整条消失，而只有把每条边单独计数才看得出来。
        val top = countIn(116, 294, 334, 306, blue)
        val bottom = countIn(116, 444, 334, 456, blue)
        val left = countIn(94, 316, 106, 434, blue)
        val right = countIn(344, 316, 356, 434, blue)
        println("  上=$top 下=$bottom 左=$left 右=$right")
        report("描边无缺边（每条边都 > 300 px）", top > 300 && bottom > 300 && left > 300 && right > 300,
            "上=$top 下=$bottom 左=$left 右=$right")
        // 上下边、左右边各应等长；不对称就说明有一段几何被丢掉或重复。
        report("上下边对称", Math.abs(top - bottom) <= top * 0.05, "上=$top 下=$bottom")
        report("左右边对称", Math.abs(left - right) <= left * 0.05, "左=$left 右=$right")
        approx("上边长度", top, 218.0 * 4)
        approx("右边长度", right, 118.0 * 4)

        println("\n-- 背景 --")
        report("背景色为 clear 色", (counts[background] ?: 0) > 0, "背景像素 ${counts[background] ?: 0}")

        println()
        if (failures.isEmpty()) {
            println("=== 全部通过 ===")
        } else {
            println("=== 失败 ${failures.size} 项：${failures.joinToString("；")} ===")
        }

        Platform.exit()
        exitProcess(if (failures.isEmpty()) 0 else 1)
    }

    override fun stop() {
        transfer?.dispose()
    }
}
