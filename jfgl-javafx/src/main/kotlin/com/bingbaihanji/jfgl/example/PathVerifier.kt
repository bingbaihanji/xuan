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
 * 路径几何（`beginPath` / `moveTo` / `lineTo` / `close` / `fillPath` / `strokePath`）的
 * **像素校验器**：画几个几何量完全已知的多子路径图形，回读帧缓冲，逐项比对像素，
 * 失败则以非零码退出。
 *
 * <h2>它为什么存在</h2>
 *
 * <p>本仓库的多数缺陷属于「静默错误输出」：编译通过、单元测试全绿、画面却是错的。
 * 而**多子路径**这一类缺陷正好全都落在 `Gc` 里——`geom/` 那一侧各自都对，
 * 错的是调用方**怎么把子路径喂给它们**。单元测试的口径与这个"怎么喂"无关：
 * `StrokeGenerator` 的测试断言"闭合面积大于开放面积"，它自己是对的；
 * 错的是 `Gc.strokePath` 忘了传 `closed = true`。这类缺陷只有把**最终像素**当口径才拦得住。
 *
 * <p>本节场景对准一个"看起来完全正常"的错法：**把一条路径的所有子路径当成一条折线描边**。
 * 两条互不相连的横线之间会被连上一条斜线——那条线**显示了一个不存在的图形**
 * （与本仓库"缺口不能连过去"那条同理），而画面看上去"就是画了条折线"。
 *
 * <h2>场景为什么会变</h2>
 *
 * <p>[PickVerifier] 的教训：它 24 条断言全绿却漏掉一个真缺陷，因为**它的场景每帧完全相同**
 * ——陈旧数据与新鲜数据恰好一致。多子路径这一条尤其危险：`Gc` 的平坦化结果与子路径切片
 * 都跨帧复用同一块缓冲，"这一帧少画一条子路径"与"上一帧的顶点没被覆盖"在画面上是两回事，
 * 只有**前后两帧画不同的东西**才分得开。所以本校验器在连续两帧上各断言一次，
 * 两帧画的子路径数量不同（见 [PathVerifierApp.drawnVariant]）。
 *
 * <h2>运行</h2>
 *
 * ```
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.jfgl.example.PathVerifierKt"
 * ```
 *
 * <p>退出码 0 = 全部通过，1 = 有断言失败（失败详情打印在 stdout）。它会自己关窗退出。
 *
 * <h2>坐标前提</h2>
 *
 * <p>当前管线把用户坐标**按 1:1 映射到设备像素**，因此下面的期望值可以直接用用户坐标书写。
 * 若日后引入真正的 DPI 缩放，本文件的期望值需要乘以缩放系数——那时这些断言会失败，
 * 正是它该提醒的。
 */
private const val SCENE_W = 800
private const val SCENE_H = 600

/** 判定用的容差：折线逼近圆弧时像素数必然略少于解析值。 */
private const val TOLERANCE = 0.05

/** 第一次断言的帧号：此前画布尺寸与首帧布局尚未稳定。 */
private const val FIRST_ASSERT_FRAME = 6

/** 第二次断言的帧号：它画的是**另一个变体**，见 [PathVerifierApp.drawnVariant]。 */
private const val SECOND_ASSERT_FRAME = 7

/** 场景背景色，即 `FXGLTransfer` 的 `glClearColor(0.2, 0.2, 0.2, 1)`。 */
private const val BACKGROUND = 0x333333

// ---------------------------------------------------------------------------
// 多子路径描边：两条互不相连的横线
// ---------------------------------------------------------------------------

/** 两条横线的 x 范围（用户坐标，与设备像素 1:1）。 */
private const val SEG_X0 = 60f
private const val SEG_X1 = 260f

/** 第一条横线的 y。 */
private const val SEG_Y0 = 70f

/** 第二条横线的 y。 */
private const val SEG_Y1 = 170f

/**
 * 两条横线的描边色。
 *
 * <p>全画面只有这一个图形用它，因此"该色像素总数恰好等于横线面积"是一条
 * **覆盖整幅画面**的不变量：多画的（假连线、越界顶点、上一帧的残留）与少画的都拦得住。
 */
private const val SUB_PATH_RGB = 0xFFFF00

/** 断言时全画面应有的颜色种数：背景 + 上面那一个描边色。 */
private const val EXPECTED_COLORS = 2

/**
 * 校验器的启动入口。
 *
 * <p>函数名**必须**是本文件独有的：同包已有一个顶层 `main()`（[PipelineExample]），
 * 而顶层函数一旦重名，同包内无法用别名区分、`import` 也报"重载歧义"。
 * （踩过：本文件一度叫 `verifyMain`，与 `PipelineVerifier.kt` 的同名顶层函数
 * 直接让整个 `jfgl-javafx` **编译不过**。）
 *
 * <p>用 `@JvmName("main")` 把 JVM 方法名钉回 `main`，上面文档里的
 * `java -cp %classpath com.bingbaihanji.jfgl.example.PathVerifierKt` 因此照常可用。
 */
@JvmName("main")
fun pathVerifyMain() {
    Application.launch(PathVerifierApp::class.java)
}

class PathVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null

    /** 已到达的渲染帧数（[verifyAll] 里递增）。 */
    private var frame = 0

    /** 绘制次数，用来在变体之间轮换。只在 GL 线程访问。 */
    private var drawCount = 0

    /**
     * **最近一帧实际画出去的**变体下标。
     *
     * <p>断言读的是它而不是自己算一个：绘制在 `onFrame` 里、断言在 `onRender` 里，
     * 两者各数各的帧号极易错开一帧，而错开之后断言会拿"变体 B 的期望值"去比"变体 A 的画面"
     * ——那会是一次**看起来像几何错误的假失败**。这里把"画了什么"直接记下来，断言与画面
     * 就不可能对不上。
     */
    private var drawnVariant = 0

    /** 两次断言累积的失败项。退出码取它，而不是最后一帧的结果。 */
    private val failures = ArrayList<String>()

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
        // 绘制回调也包一层：GL 线程上一个没接住的异常会让 JVM 以 0 正常退出，
        // 校验器就会报一个**静默的绿**（下面 verifyOnce 里那层是同样理由）。
        bridge.onFrame { gc -> drawFrame(gc) }
        bridge.onRender { verifyOnce() }
        transfer = bridge

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "JFGL Path Verifier"
        stage.scene = Scene(mainView.createMainView(), SCENE_W.toDouble(), SCENE_H.toDouble())
        stage.show()
    }

    private fun drawFrame(gc: Gc) {
        try {
            drawnVariant = drawCount++ % 2
            drawSubPathCase(gc, twoSegments = drawnVariant == 0)
        } catch (t: Throwable) {
            println("\n=== 绘制过程抛出异常，判为失败 ===")
            t.printStackTrace()
            Platform.exit()
            exitProcess(1)
        }
    }

    /**
     * 多子路径描边的场景：一条路径里的**两条互不相连**的横线。
     *
     * <p>变体 0 画两条、变体 1 只画第一条。两个变体都断言：变体 1 里"第二条横线原来的位置
     * 必须是干净的"——那正是"上一帧的顶点留在缓冲里"这类**跨帧残留**唯一会露出来的地方。
     *
     * @param gc          当前帧的绘制上下文
     * @param twoSegments 是否画第二条横线
     */
    private fun drawSubPathCase(gc: Gc, twoSegments: Boolean) {
        gc.stroke = SUB_PATH_RGB or (0xFF shl 24)
        gc.lineWidth = 4f
        gc.beginPath()
        gc.moveTo(SEG_X0, SEG_Y0)
        gc.lineTo(SEG_X1, SEG_Y0)
        if (twoSegments) {
            gc.moveTo(SEG_X0, SEG_Y1)
            gc.lineTo(SEG_X1, SEG_Y1)
        }
        gc.strokePath()
    }

    /**
     * `onRender` 回调的入口：把校验体包进 try/catch。
     *
     * <p>校验过程本身抛出异常时必须**以非零码退出**：这部分代码在 GL 线程上跑，
     * 一旦抛出去，线程死掉、汇总行与 `exitProcess` 都走不到，JVM 会因为
     * 「最后一个非守护线程结束」而以 0 退出——**一个已经打印了 FAIL 的校验器报出退出码 0**，
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
        frame++
        if (frame < FIRST_ASSERT_FRAME) return
        if (frame > SECOND_ASSERT_FRAME) return

        val w = bridge.scaledWidth
        val h = bridge.scaledHeight
        if (w <= 0 || h <= 0) return

        val buf = ByteBuffer.allocateDirect(w * h * 4)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        buf.position(0)

        println("\n=== JFGL 路径几何像素校验（第 $frame 帧，变体 $drawnVariant）===")
        println("帧缓冲 ${w}x$h  GL_FRAMEBUFFER_BINDING=${glGetInteger(GL_FRAMEBUFFER_BINDING)}  glGetError=${glGetError()}")

        fun pixelAt(x: Int, y: Int): Int {
            // glReadPixels 行序自下而上；用户坐标 y 向下，故翻转回读行号。
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

        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        fun approx(label: String, actual: Int, expected: Double, tol: Double = TOLERANCE) {
            val lo = expected * (1 - tol); val hi = expected * (1 + tol)
            report(label, actual.toDouble() in lo..hi,
                "实际 $actual，期望 ${expected.toInt()} ±${(tol * 100).toInt()}%")
        }

        fun bbox(label: String, argb: Int, ex: Int, ey: Int, ew: Int, eh: Int) {
            if ((counts[argb] ?: 0) == 0) {
                report(label, false, "无像素")
                return
            }
            val ax = minX[argb]!!; val ay = minY[argb]!!
            val aw = maxX[argb]!! - ax + 1; val ah = maxY[argb]!! - ay + 1
            report(label, ax == ex && ay == ey && aw == ew && ah == eh,
                "实际 ($ax,$ay) ${aw}x$ah，期望 ($ex,$ey) ${ew}x$eh")
        }

        println("\n-- 绘制区尺寸 --")
        val gc = bridge.gc()
        report("Gc.width 等于帧缓冲宽度", gc?.width == w, "Gc.width=${gc?.width}，帧缓冲宽=$w")
        report("Gc.height 等于帧缓冲高度", gc?.height == h, "Gc.height=${gc?.height}，帧缓冲高=$h")

        println("\n-- 多子路径描边：MOVE_TO 处必须断开 --")
        val twoSegments = drawnVariant == 0
        val sx0 = SEG_X0.toInt(); val sx1 = SEG_X1.toInt()
        val sy0 = SEG_Y0.toInt(); val sy1 = SEG_Y1.toInt()

        // 一条 200 长的横线、线宽 4：四条像素行、每行 200 个像素，应当**精确**是 800。
        approx("第一条横线（y=$sy0）铺满 200x4",
            countIn(sx0 - 5, sy0 - 4, sx1 + 5, sy0 + 4, SUB_PATH_RGB), 200.0 * 4)
        if (twoSegments) {
            approx("第二条横线（y=$sy1）铺满 200x4",
                countIn(sx0 - 5, sy1 - 4, sx1 + 5, sy1 + 4, SUB_PATH_RGB), 200.0 * 4)
            bbox("两条横线的包围盒（在一起）", SUB_PATH_RGB, sx0, sy0 - 2, sx1 - sx0, sy1 - sy0 + 4)
        } else {
            // 变体 1 不画第二条：那个位置必须**一个像素都没有**。这一句与变体 0 里那句
            // "第二条横线铺满"合起来才有意义——只断言"这里是干净的"，对"两条都没画"同样成立。
            report("变体 1：未绘制的第二条横线位置必须是干净的",
                countIn(sx0 - 5, sy1 - 4, sx1 + 5, sy1 + 4, SUB_PATH_RGB) == 0,
                "实际 ${countIn(sx0 - 5, sy1 - 4, sx1 + 5, sy1 + 4, SUB_PATH_RGB)} px")
            bbox("变体 1：只剩第一条横线", SUB_PATH_RGB, sx0, sy0 - 2, sx1 - sx0, 4)
        }

        // 这是本节的**判别式**。把两个子路径当成一条折线描边时，相邻两点会被连起来：
        // 从第一条的末点 (260,70) 到第二条的起点 (60,170)，那条假斜线正好穿过 (160,120)。
        // 断言"这个方框里没有像素"，而不是断言"两条横线都在"——后者对"多连了一条线"
        // 同样成立（那条线是**多出来的**，不是替掉了什么）。
        //
        // 变体 1 里第二条横线不存在，也就无从连起；那一帧这条断言退化为恒真，
        // 由变体 0 那一帧负责。
        val corridor = countIn(150, 110, 170, 130, SUB_PATH_RGB)
        report("两条子路径之间没有连线（假斜线会穿过 (160,120)）", corridor == 0,
            "实际 $corridor px")

        println("\n-- 无杂散像素（覆盖整幅画面）--")
        val expectedStrokes = if (twoSegments) 2 else 1
        // 该颜色全画面只出现在这些横线上：多画一块、少画一块、上一帧的顶点留在缓冲里，
        // 都会让这个总数变化。颜色种数同时钉住"没有出现别的颜色"。
        report("描边色像素总数恰好等于 ${expectedStrokes} 条横线的面积",
            counts[SUB_PATH_RGB] == expectedStrokes * 200 * 4,
            "实际 ${counts[SUB_PATH_RGB] ?: 0}，期望 ${expectedStrokes * 200 * 4}")
        report("画面只有 $EXPECTED_COLORS 种颜色（无杂散像素）", counts.size == EXPECTED_COLORS,
            "实际 ${counts.size} 种：${counts.keys.sorted().joinToString { "#%06X".format(it) }}")
        report("背景色为 clear 色", (counts[BACKGROUND] ?: 0) > 0, "背景像素 ${counts[BACKGROUND] ?: 0}")

        println()
        if (failures.isEmpty()) {
            println("=== 第 $frame 帧全部通过 ===")
        } else {
            println("=== 第 $frame 帧为止失败 ${failures.size} 项：${failures.joinToString("；")} ===")
        }

        if (frame == SECOND_ASSERT_FRAME) {
            Platform.exit()
            exitProcess(if (failures.isEmpty()) 0 else 1)
        }
    }

    override fun stop() {
        transfer?.dispose()
    }
}
