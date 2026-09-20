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

/**
 * 校验器的启动入口。
 *
 * <p>函数名不叫 `main`：同包的 [PipelineExample] 已经有一个顶层 `main()`，两个同名顶层函数
 * 会让 `import com.bingbaihanji.jfgl.example.main` 报"重载歧义"——而 `Main.kt` 正是这样
 * 导入示例入口的（同包内无法靠别名区分）。用 `@JvmName("main")` 把 JVM 方法名钉回 `main`，
 * 上面文档里的 `java -cp %classpath com.bingbaihanji.jfgl.example.PipelineVerifierKt`
 * 因此照常可用。
 */
@JvmName("main")
fun verifyMain() {
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

    // flush() 的 z 序探针专用色。**刻意不复用上面任何一个**：这两个颜色在画面里
    // 只出现在三个探针矩形内，于是"先画的那层一个像素都不剩"就成了覆盖整幅画面的不变量，
    // 而失败信息也能直接点名是 flush 的问题，不会伪装成"红矩形画多了"。
    //
    // 分了 RGB 与 ARGB 两套：`pixelAt` 与 `counts` 的口径都是**不含 alpha 的 RGB**，
    // 拿 ARGB 去比会永远不相等（0xFFFF8000 != 0x00FF80），而那看起来像"颜色画错了"。
    private val probeUnderRgb = 0xFF8000
    private val probeOverRgb = 0x00FF80
    private val probeUnderArgb = probeUnderRgb or (0xFF shl 24)
    private val probeOverArgb = probeOverRgb or (0xFF shl 24)

    /** 三个 flush 探针的左上角 x（y、尺寸见 [assertFlushKeepsZOrder]），都落在主场景的空区域里。 */
    private val PROBE_X = floatArrayOf(40f, 200f, 620f)

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
        // flush 探针画在最前面：它们占的是主场景没碰过的空区域（y 175..295、x 40..739），
        // 因此既不影响下面那些颜色计数与包围盒，也让"flush 丢了顶点"这类退化**只**打在新断言上，
        // 失败信息不会指错方向。
        assertFlushKeepsZOrder(gc)

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

    /**
     * flush() 的 z 序：后画的必须盖住先画的，<b>无论中间有没有 flush</b>。
     *
     * <p>三种情况画的都是同一件事——同一个位置上先画 [probeUnder]、后画 [probeOver] 的
     * 两个重合矩形，只有中间那一步不同：
     * <ol>
     *   <li><b>不 flush</b>：两层落在同一个批里，靠批内的顶点顺序分胜负；</li>
     *   <li><b>中间 flush</b>：两层落在两个批里，靠两批的提交顺序分胜负；</li>
     *   <li><b>flush + 立即模式绘制</b>：上面那层不走 [Gc]，而是用 [immediateFill] 当场发一条
     *       GL 命令，模拟图表后端数据系列（instancing）的做法。</li>
     * </ol>
     *
     * <p><b>为什么必须有第三情况。</b>前两种情况里的两层都由 `Gc` 记在同一个顶点写入器里，
     * 最终按顶点顺序画进同一个帧缓冲——也就是说，<b>把 flush() 掏空成空方法，前两种情况的
     * 像素一模一样</b>。只测前两种的话，这条断言看着在守，其实拦不住 flush 退化，
     * 正是本仓库说的橡皮图章。只有让一方"当场就画"，"flush 把网格先落定"这件事才在像素上可观测：
     * 没有 flush，`Gc` 的顶点要等到 `endFrame` 才提交，于是它会盖在即时绘制的那层<b>上面</b>。
     */
    private fun assertFlushKeepsZOrder(gc: Gc) {
        val ys = 175f
        val size = 120f

        // 情况一：不 flush，under 先 over 后 —— 期望 over
        gc.fill = probeUnderArgb
        gc.fillRect(PROBE_X[0], ys, size, size)
        gc.fill = probeOverArgb
        gc.fillRect(PROBE_X[0], ys, size, size)

        // 情况二：中间 flush，under 先 over 后 —— 期望 over
        gc.flush()
        gc.fill = probeUnderArgb
        gc.fillRect(PROBE_X[1], ys, size, size)
        gc.flush()
        gc.fill = probeOverArgb
        gc.fillRect(PROBE_X[1], ys, size, size)

        // 情况三：flush 之后由**立即模式**的一条 GL 命令盖上去 —— 期望 over
        gc.flush()
        gc.fill = probeUnderArgb
        gc.fillRect(PROBE_X[2], ys, size, size)
        gc.flush()
        immediateFill(PROBE_X[2].toInt(), ys.toInt(), size.toInt(), size.toInt(), probeOverArgb)
    }

    /**
     * 直接向 GL 发一次"立即模式"的矩形填充，绕开 [Gc] 的批处理。
     *
     * <p>存在的理由：`Gc` 的图元攒到 `endFrame` 才提交，而图表后端（子项目 D-②）的数据系列
     * 是**当场就画**的。校验 `flush()` 的 z 序就必须有一方是"当场就画"的，否则两边都攒在同一个批里、
     * 按顶点顺序绘制，<b>flush 存在与否根本不影响最终像素</b>。
     *
     * <p>用 `glClear` + `glScissor` 而不是 `glDrawArrays`：它同样在本帧的 GL 命令流里
     * <b>就地</b>执行（而不是等到 `endFrame`），却不需要再搭一套着色器与 VAO。
     * 清屏色是全局状态，用完必须还原，否则下一帧的 `glClear` 会把整幅背景刷成这个颜色。
     *
     * @param x    矩形左上角 x（用户坐标，与设备像素 1:1）
     * @param y    矩形左上角 y（用户坐标，y 向下）
     * @param w    宽度
     * @param h    高度
     * @param argb 填充色
     */
    private fun immediateFill(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        val prevClear = FloatArray(4)
        glGetFloatv(GL_COLOR_CLEAR_VALUE, prevClear)

        glClearColor(
            ((argb ushr 16) and 0xFF) / 255f,
            ((argb ushr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
            1f
        )
        glEnable(GL_SCISSOR_TEST)
        // glScissor 的 y 从**底边**起算，用户坐标 y 向下，故翻成 height - (y + h)。
        glScissor(x, (transfer?.scaledHeight ?: 0) - (y + h), w, h)
        glClear(GL_COLOR_BUFFER_BIT)
        glDisable(GL_SCISSOR_TEST)

        glClearColor(prevClear[0], prevClear[1], prevClear[2], prevClear[3])
    }

    /**
     * `onRender` 回调的入口：把校验体包进 try/catch。
     *
     * <p>校验过程本身抛出异常时必须**以非零码退出**。
     *
     * <p>理由不是洁癖：这部分代码在 GL 线程上跑，一旦抛出去，线程死掉、
     * 汇总行与 `exitProcess` 都走不到，JVM 会因为「最后一个非守护线程结束」而
     * 以 0 退出——**一个已经打印了 FAIL 的校验器报出退出码 0**，
     * 正是本仓库最忌讳的那种「静默的绿」。
     */
    private fun verifyOnce() {
        try {
            verifyAll()
        } catch (t: Throwable) {
            println("\n=== 校验过程抛出异常，判为失败 ===")
            t.printStackTrace()
            Platform.exit()
            exitProcess(1)
        }
    }

    private fun verifyAll() {
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

        // Gc.width/height 是用户查询绘制区大小的唯一途径（逻辑窗口尺寸在高 DPI 下不等于它），
        // 必须与真实帧缓冲一致，否则用户按它铺满会铺歪。
        println("\n-- 绘制区尺寸 --")
        val gc = bridge.gc()
        report("Gc.width 等于帧缓冲宽度", gc?.width == w, "Gc.width=${gc?.width}，帧缓冲宽=$w")
        report("Gc.height 等于帧缓冲高度", gc?.height == h, "Gc.height=${gc?.height}，帧缓冲高=$h")

        println("\n-- 形状：像素数 --")
        approx("红矩形填充 200x120", counts[red] ?: 0, 200.0 * 120)
        approx("绿圆填充 r=80", counts[green] ?: 0, Math.PI * 80 * 80)
        // 凹多边形（耳切三角化）：鞋带公式面积 = 37000，三角化应当精确命中而非近似。
        approx("青凹多边形填充", counts[cyan] ?: 0, 37000.0, 0.01)
        // 圆角矩形描边：中心线周长 2*(250-32)+2*(150-32)+2π*16 ≈ 772.5，线宽 4。
        approx("蓝圆角矩形描边", counts[blue] ?: 0, 772.5 * 4)
        report("品红贝塞尔描边存在", (counts[magenta] ?: 0) > 200, "实际 ${counts[magenta] ?: 0} px")
        // 7 种 = 主场景的 6 种（背景 + 红绿蓝青品红）+ flush 探针的后画色。
        // 探针的**先画色**不在里面，正是因为它一个像素都不该剩下（见下面的 z 序断言）。
        report("画面只有 7 种颜色（无杂散像素）", counts.size == 7, "实际 ${counts.size} 种：${counts.keys.sorted().joinToString { "#%06X".format(it) }}")

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

        println("\n-- flush() 的 z 序 --")
        // 三个探针都是"先画 probeUnder、后画 probeOver"的重合矩形，最终必须**只剩 probeOver**。
        // 断言的量必须是颜色：只断言"有东西画出来了"，对顺序颠倒同样成立，那是橡皮图章。
        val probeLabels = arrayOf("不 flush", "中间 flush", "flush + 立即模式绘制")
        for (i in PROBE_X.indices) {
            val x0 = PROBE_X[i].toInt()
            val y0 = 175
            val cx = x0 + 60
            val cy = y0 + 60
            val center = pixelAt(cx, cy)
            report("z 序（${probeLabels[i]}）：后画的盖住先画的", center == probeOverRgb,
                "中心 ($cx,$cy) = #%06X，期望 #%06X".format(center, probeOverRgb))
            approx("z 序（${probeLabels[i]}）：后画色铺满 120x120",
                countIn(x0, y0, x0 + 119, y0 + 119, probeOverRgb), 120.0 * 120, 0.01)
        }
        // probeUnder 只出现在这三个探针里，所以"一个像素都不剩"是一条覆盖整幅画面的不变量：
        // 顺序反了会留下它，后画的那层没盖全（位置/尺寸错了）也会留下它。
        report("z 序：先画的那层被完全盖住（全画面无残影）", (counts[probeUnderRgb] ?: 0) == 0,
            "先画色像素 ${counts[probeUnderRgb] ?: 0}")

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
