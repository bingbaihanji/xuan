@file:JvmName("MsaaVerifier")

package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.PixelFormat
import javafx.scene.transform.Scale
import javafx.stage.Stage
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.GL_RGBA
import org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11.glGetError
import org.lwjgl.opengl.GL11.glGetInteger
import org.lwjgl.opengl.GL11.glReadPixels
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.min

/**
 * **MSAA 校验器**：`GLCanvas` 的 `msaa` 参数到底有没有真的抗锯齿，以及
 * `msaa > 0` 时像素回读会怎样。
 *
 * <p>它由 `MsaaProbe`（Task 0 的一次性探针）改造成——"探针用完要删"是本仓库的元规则，
 * 而它那两条**互相独立的读数路径**有长期价值（见下），所以留下来并补上断言与退出码。
 *
 * <h2>为什么要两条读数路径</h2>
 *
 * <table>
 *   <tr><th>路径</th><th>做法</th><th>它读的是</th></tr>
 *   <tr><td>A</td><td>`glReadPixels` 画布 FBO</td><td>GL 侧的帧缓冲内容</td></tr>
 *   <tr><td>B</td><td>JavaFX `node.snapshot()`</td><td>**用户眼睛看到的东西**</td></tr>
 * </table>
 *
 * <p>**必须两条都有**：`msaa=4` 时 A 读回**全 0**，看起来像"MSAA 把渲染整个搞坏了"。
 * 是 `GL_INVALID_OPERATION` 这个错误码提示了真相——**多采样 FBO 上 `glReadPixels`
 * 本身就是非法操作**，那是**读数方式坏了**，不是渲染坏了。B 路径（完全绕开
 * `glReadPixels`）否掉了那个结论：圆画得好好的。
 * ⇒ 这正是本仓库那条判据的又一次应验：**怀疑被测对象之前，先怀疑测量本身**。
 *
 * <p>★ 于是本校验器顺手把 [FXGLTransfer.canReadPixels] 那条守卫的前提也钉住：
 * **`canReadPixels == false` ⇔ `glReadPixels` 真的报错**。没有这一条，
 * 守卫就只是一句注释，而它的正确性没有任何证据。
 *
 * <h2>判据（与 `PipelineVerifier` 的 ★ 抗锯齿四条同构）</h2>
 *
 * <p>画面上一共四样东西：白圆（边缘方向连续变化）、1px 斜线（浅角度穿过像素格）、
 * **4px 水平探针线**（判据的载体）、洋红矩形（拾取探针）。判据全部落在探针线的
 * **一个窗**里，窗就是被打印的那一个（本仓库的规矩：**打印的窗必须就是被断言的窗**）。
 *
 * <p>探针线的线心取 `floor(h × 0.88) + 0.25`——**相位 0.25 是挑过的**，不是随手取的。
 * 带是 `[线心 − 2, 线心 + 2)`，记 `n = floor(线心)`，于是这 5 行是：
 * <pre>
 *   行 n−2：覆盖率 **0.75**   ← 过渡行（MSAA 下部分覆盖）
 *   行 n−1、n、n+1：覆盖率 **1.0**   ← 线心，两种模式都必须满覆盖
 *   行 n+2：覆盖率 **0.25**   ← 过渡行
 * </pre>
 * 四条判据（W = 窗宽）：
 * <pre>
 *   ① `msaa=0`（硬边）窗内**一个过渡像素都没有** = 0；`msaa>0` = **2W**
 *  ② **线心那 3 行的纯色像素数 = 3W**，两种模式**精确相等**（线心没移位、没变淡）
 *  ③ 窗内**纯色总数**：`msaa=0` = **4W**（硬边走像素中心采样，多覆盖行 n−2）、
 *     `msaa>0` = **3W**（行 n−2 退化成 0.75 的过渡行）
 *  ④ 三类像素之和 == 窗内像素数（**没有漏计的像素**——它是前三条的守门员）
 * </pre>
 *
 * <p>★ **② 为什么只取线心 3 行、不取"总纯色数"**：开了 MSAA 之后最外那一圈本来就会
 * 从纯色变成过渡色，总纯色数**必然变**——写成"总数相等"是一条**恒假断言**。
 * 与 `PipelineVerifier` 那条一样，线心那 3 行是两种模式的交集，它们相等是**成立**的。
 *
 * <p>★ **③ 是"硬边按像素中心采样"的直接推论**，不是看着像就写的：行 n−2 的像素中心
 * （`n−1.5`）落在带内（带起于 `n−1.75`）⇒ 硬边把它画成纯色；行 n+2 的中心（`n+2.5`）
 * 落在带外（带止于 `n+2.25`）⇒ 硬边不画它。于是硬边恰好覆盖 4 行、而 MSAA 只覆盖 3 行。
 * **它挡的是"关着 MSAA 时几何被动过"**——那 4 行是"硬边没被改过"的指纹。
 *
 * <h2>跨进程的那两条（由 `jfgl-javafx/scripts/msaa-verify.sh` 判）</h2>
 *
 * <p>一个进程只能有一个 `msaa` 值（采样数是帧缓冲的属性，`GLCanvas` 没有 setter），
 * 所以"**`msaa=4` 的过渡像素 > `msaa=0` 的**"与"**两者线心纯色数相等**"这两条
 * **只能跨进程比**：本校验器把自己的读数打成一行机器可读的 `MSAA_READING`，
 * 由那个脚本跑两次、解析两行、比对。脚本里用 `trap ... EXIT` 保证清理。
 *
 * <h2>跑法</h2>
 * ```
 * bash jfgl-javafx/scripts/msaa-verify.sh          # 跑两次并比对（推荐）
 *
 * # 单跑一次（一个 msaa 值）：
 * mvn -o -f jfgl-javafx/pom.xml compile exec:exec "-Dexec.executable=java" \
 *   "-Dexec.classpathScope=runtime" \
 *   "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djfgl.probe.msaa=4 -cp %classpath com.bingbaihanji.jfgl.example.MsaaVerifier"
 * ```
 *
 * <p>**它不 dispose**：跑完直接 `halt`。理由与自检那条相反——自检要反复跑、漏 GL 对象
 * 会积累；本校验器是**一次性**的，进程一死对象就没了，而 `halt` 避开关闭钩子正是为了
 * 不撞上本仓库实测过的那次原生崩溃（`exitProcess` 跑钩子会与 JavaFX 自己的关停并发碰
 * GL/D3D）。
 */
@JvmName("main")
fun msaaVerifierMain() {
    Application.launch(MsaaVerifierApp::class.java)
}

/**
 * **回读拒绝守卫**：七个像素校验器（`PipelineVerifier` / `PathVerifier` / `PickVerifier` /
 * `ClickVerifier` / `TextVerifier` / `ChartVerifier` / `FftVerifier`）在**入口各调一次**。
 *
 * <p>`msaa > 0` 时它抛异常、进程以非零码退出，**而不是**让那些校验器读回全 0
 * 再报一大堆"画面全黑"式的假失败。**假失败比"明确拒绝"坏得多**：
 * 它会让人去查渲染，而真因在配置里。
 *
 * <h2>为什么是**一个共享函数**，而不是七份各写一遍的 check</h2>
 *
 * <p>本仓库刚吃过一次同类的亏（见 `CLAUDE.md`：「**共用属性名 ≠ 共用判定**」
 * ——`JfglDemo` 与 `DemoChart` 两侧各写一份 `== "1"` / `== "true"` 的解析，
 * 于是同一份配置在两边给出不同判定，表现为"约 1/17 概率的间歇性缺陷"而实为确定性差异）。
 * 七个入口各抄一份守卫，就是同一件事再犯一次：抄漏一处、或者某一处改了判据，
 * 症状是"那个校验器在 `msaa>0` 时报假失败"，而它**只在 msaa>0 时才出现**。
 * 判据收成一份代码，就没得抄漏。
 *
 * <h2>它的前提有断言钉着</h2>
 *
 * <p>"多采样 FBO 上 `glReadPixels` 非法"这句话不是注释里的传说：
 * [MsaaVerifierApp] 在 `msaa=0` 与 `msaa=4` 下各跑一次，断言
 * **`canReadPixels == (glGetError == 0)`** —— 也就是说这条守卫的判据与实测**等价**。
 *
 * @param bridge 刚构造好的桥接对象（**在 `start()` 里、`stage.show()` 之前**调用，
 *   这样窗口根本不会打开，失败也不会被误当成"跑过了"）
 */
internal fun requirePixelReadback(bridge: FXGLTransfer) {
    check(bridge.canReadPixels) {
        "画布是 msaa=${bridge.msaa} 的多采样 FBO——本仓库的七个像素校验器一律要求在 msaa=0 下跑：" +
            "多采样 FBO 上 glReadPixels 是非法操作（实测 GL_INVALID_OPERATION，由 MsaaVerifier 钉着），" +
            "读回来的像素全是 0，会让每一条像素断言报「画面全黑」式的假失败——" +
            "而真因在配置里，不在渲染里。请用 msaa=0 跑（默认值就是 0；" +
            "想要细线质量请用 Gc.antialias，那是运行期开关、且不牺牲回读）。"
    }
}

/** 探针线线心在高度上的比例。取 0.88 是为了**整条线都在白圆之下**（圆最低到 0.8h）。 */
private const val PROBE_Y_FRAC = 0.88f

/** 探针线线心的小数相位。**0.25 是挑过的**——见文件头那段推导（它让两个过渡行都是部分覆盖）。 */
private const val PROBE_PHASE = 0.25f

/** 探针线线宽（用户坐标 = 设备像素）。取 4 是为了让"线心 3 行"离两个过渡行各有一行之隔。 */
private const val PROBE_LINE_WIDTH = 4f

/** 探针窗在宽度上的比例：窗取 `[0.1W, 0.9W)`，而线由 `0.05W` 画到 `0.95W` ⇒ 窗严格在线内。 */
private const val WINDOW_X_FRAC = 0.1f
private const val LINE_X_FRAC = 0.05f

/** 窗的纵向上下界（相对 `floor(线心)` 的行号，半开区间 `[-3, +4)`）。 */
private const val WINDOW_DY0 = -3
private const val WINDOW_DY1 = 4

/**
 * 窗的**期望行数** = 7。它的出处是几何，不是上面那两个常量：
 * 线心 3 行 + 两个过渡行 + 上下各留 1 行背景。
 *
 * <p>⚠️ **它必须是一个独立的字面量，不能就地写成 `WINDOW_DY1 - WINDOW_DY0`。**
 * 实测过：那样写时，把上面两个常量一起改成 `4 / 4`（= 空窗）会让**两边同时变成 0**，
 * 于是"窗非空"这条先决条件**恒真**——报的是 `[PASS] … 行数 == 0`，而它存在的
 * 全部理由就是抓这个（空窗下判据 ① 与 ④ 会退化成 `0 == 0`）。
 * **期望值不许由被测的那两个常量自己算出来**，否则变异一改就是两边一起改。
 */
private const val WINDOW_ROWS = 7

/** 背景（`glClearColor(0.2,0.2,0.2)` = #333333）与线色。判据里"过渡像素"就是**两者都不是**的像素。 */
private const val BG_RGB = 0x333333
private const val LINE_RGB = 0xFFFFFF

/** 拾取探针用的 ID。 */
private const val PICK_ID = 7

class MsaaVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null
    private var frame = 0
    private var done = false
    private var canvasNode: Node? = null

    /** 全部失败项。**跨线程累积**（GL 线程那一段与 JavaFX 线程的快照那一段各往同一份里记）。 */
    private val failures = ArrayList<String>()

    /** 要测的采样数。**只在这里读一次**，构造 `FXGLTransfer` 时用掉。 */
    private val requestedMsaa: Int = System.getProperty("jfgl.probe.msaa", "0").toIntOrNull() ?: 0

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer(msaa = requestedMsaa)
        bridge.onFrame { gc -> drawScene(gc) }
        bridge.onRender { verifyOnce() }
        transfer = bridge

        val canvas = bridge.createGlFXView()
        canvasNode = canvas
        val mainView = MainView().apply { center = canvas }
        stage.title = "JFGL MSAA Verifier"
        stage.scene = Scene(mainView.createMainView(), 900.0, 700.0)
        stage.show()
    }

    /**
     * 画面刻意只有四样东西：
     *
     * <ul>
     *   <li>**白圆**：边缘方向连续变化（水平扫描线在圆心那一行穿过竖直切线，别的行穿斜边）；</li>
     *   <li>**1px 斜线**：保证至少有一处边缘以浅角度穿过像素格——那正是"部分覆盖"
     *       最容易出现、也最容易看出来的地方；</li>
     *   <li>**4px 水平探针线**：四条判据的载体（几何与相位见 [PROBE_Y_FRAC] / [PROBE_PHASE]）；</li>
     *   <li>**洋红矩形**：拾取探针，位于右上角（不与圆重叠）。</li>
     * </ul>
     *
     * <p>斜线**刻意收在 `0.45h` 处**：它原本画到右下角，会与那条水平探针线相交——
     * 交点那一列会往判据窗里多塞几个纯色像素，而"窗内纯色数 = 3W / 4W"是精确断言。
     * 让它停在探针窗之上，窗内就**只有探针线自己**。
     */
    private fun drawScene(gc: Gc) {
        val w = gc.width.toFloat()
        val h = gc.height.toFloat()

        // 1. 圆：边缘方向连续变化
        gc.fill = 0xFFFFFFFF.toInt()
        gc.fillCircle(w / 2f, h / 2f, min(w, h) * 0.30f)

        // 2. 1px 斜线：浅角度穿过像素格（收在探针窗之上，见 KDoc）
        gc.stroke = 0xFFFFFFFF.toInt()
        gc.lineWidth = 1f
        gc.drawLine(w * LINE_X_FRAC, h * LINE_X_FRAC, w * 0.95f, h * 0.45f)

        // 3. ★ 判据载体：4px 水平线。相位 0.25 ⇒ 带 [线心−2, 线心+2) 盖住 5 行、其中 3 行满覆盖。
        //    antialias 显式置 false（它本来就是默认值，见 Gc.antialias）：本校验器量的是
        //    **MSAA**，解析式 AA 一旦掺进来，"过渡像素"就分不清是哪一家的了。
        gc.antialias = false
        gc.stroke = 0xFFFFFFFF.toInt()
        gc.lineWidth = PROBE_LINE_WIDTH
        val probeY = probeLineY(gc.height)
        gc.drawLine(w * LINE_X_FRAC, probeY, w * (1f - LINE_X_FRAC), probeY)

        // 4. 拾取探针：一块可拾取的矩形，位于右上角（不与圆重叠），ID 用 7。
        //    它存在的唯一理由是回答"msaa>0 时拾取还灵不灵"——拾取 FBO 是**独立的单采样 FBO**，
        //    理论上不受画布多采样影响，但它的读回路径也是 glReadPixels 一类，**必须实测**。
        gc.pickId = PICK_ID
        gc.fill = 0xFFFF00FF.toInt()
        gc.fillRect(w - 260f, 60f, 200f, 120f)
        gc.pickId = 0
    }

    private fun verifyOnce() {
        val bridge = transfer ?: return
        if (done) return
        if (++frame < 6) return          // 等几帧，让画布尺寸与内容都稳定下来
        done = true
        val w = bridge.scaledWidth
        val h = bridge.scaledHeight
        if (w <= 0 || h <= 0) { done = false; return }

        println("=== MSAA 校验 ===")
        println("requested msaa            = $requestedMsaa")
        println("bridge.msaa（实际生效）    = ${bridge.msaa}" +
            (if (bridge.msaa != requestedMsaa) "  ← ★ 与请求值不一致（配置没生效）" else ""))
        println("framebuffer               = ${w}x$h")
        // 0x8CA6 = GL_FRAMEBUFFER_BINDING（在 GL30 里，GL11 没有这个常量）
        println("GL_FRAMEBUFFER_BINDING    = ${glGetInteger(0x8CA6)}")
        // 0x80A9 = GL_SAMPLES，0x80A8 = GL_SAMPLE_BUFFERS，0x8D57 = GL_MAX_SAMPLES
        println("GL_SAMPLE_BUFFERS(bound)  = ${glGetInteger(0x80A8)}")
        println("GL_SAMPLES(bound)         = ${glGetInteger(0x80A9)}")
        println("GL_MAX_SAMPLES            = ${glGetInteger(0x8D57)}")
        println("canReadPixels             = ${bridge.canReadPixels}")

        // ---- 路径 A：glReadPixels（**多采样 FBO 上这是非法操作**）----
        // ★ 先清空错误队列：GL 的错误状态是**粘的**，早些时候别的调用留下的错误会在这里被
        //   读到，而那会被误当成"这次 glReadPixels 非法"。不清的话这条断言就不可靠。
        while (glGetError() != GL_NO_ERROR) {
            // 丢弃更早的错误
        }
        val buf = ByteBuffer.allocateDirect(w * h * 4)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        val err = glGetError()
        println("★ glReadPixels 之后 glGetError = $err（0x0502 = GL_INVALID_OPERATION；0 = 无错误）")
        // ★ 守卫的前提由本校验器钉住：`canReadPixels` 说的那件事必须与实测**等价**。
        //   没有这一条，`canReadPixels` 就只是一句注释——而它挡着七个校验器的假失败。
        report(
            "★ 回读守卫的前提：canReadPixels == (glReadPixels 无错误)",
            bridge.canReadPixels == (err == GL_NO_ERROR),
            "canReadPixels=${bridge.canReadPixels}，glGetError=$err" +
                (if (bridge.canReadPixels) "（msaa=0：回读合法，读数可用）"
                else "（msaa>0：多采样 FBO 上 glReadPixels 非法，读数全是 0）")
        )
        if (bridge.canReadPixels) {
            printProfile("A glReadPixels", w, h, buf, bottomUp = true)
        } else {
            println("[A glReadPixels] 跳过剖面：msaa>0 时这条路径的读数**不可信**（见上）")
        }

        // ---- 拾取在 msaa>0 时还灵不灵 ----
        // 本函数跑在 onRender 里，即 `endFrame()` **之后**、`pickBufferValid == true` 的那一刻
        // ——正是 pick/pickRect 唯一合法的时机（在 onFrame 里调是恒返回空的）。
        val gc = bridge.gc()
        val probeX = w - 160f          // 那个洋红矩形的中心
        val probeY = 120f
        val hit = gc?.pick(probeX, probeY)
        val miss = gc?.pick(w - 160f, h - 60f)   // 右下角空白处
        val rect = gc?.pickRect(w - 260f, 60f, 200f, 120f)
        // 拾取不受采样数影响这一条**要有断言**，不能只打印：它是"msaa 可以用"的一半理由
        // （另一半是画面正确）。拾取 FBO 是独立创建的单采样 FBO，理论上不受影响——
        // 但"理论上"在本仓库不算证据。
        report(
            "★ 拾取不受 msaa 影响：矩形中心命中 $PICK_ID",
            hit?.id() == PICK_ID, "实测 id=${hit?.id() ?: 0}（期望 $PICK_ID）"
        )
        report(
            "★ 拾取不受 msaa 影响：空白处未命中",
            (miss?.id() ?: 0) == 0, "实测 id=${miss?.id() ?: 0}（期望 0）"
        )
        report(
            "★ 拾取不受 msaa 影响：区域拾取恰好命中 1 个且是 $PICK_ID",
            rect != null && rect.size == 1 && rect[0].id() == PICK_ID,
            "实测命中 ${rect?.size ?: -1} 个，id=${rect?.map { it.id() }}"
        )

        // ---- 路径 B：JavaFX 快照（**完全绕开 glReadPixels**）----
        // 它走的是 JavaFX 合成器读那个共享纹理，也就是**用户眼睛看到的东西**。
        // 两条路径若不一致，说明「GL 侧没问题、只是回读方式非法」——那与
        // 「openglfx 的 MSAA 根本画不出东西」是两回事，不能混为一谈。
        Platform.runLater {
            try {
                snapshotPhase(bridge)
            } catch (t: Throwable) {
                println("快照阶段抛异常：${t::class.simpleName}: ${t.message}")
                t.printStackTrace()
                failures.add("快照阶段抛异常")
            }
            finish()
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
            "  ★ 既非背景也非纯白的像素=$edge")
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

    /**
     * 用 JavaFX 的快照读回画布内容——**与 `glReadPixels` 完全独立的一条路径**——
     * 并在它上面跑四条判据。
     *
     * <p>★ **快照必须按设备缩放取，否则它自带一个混淆项。** 本方法原来写的是
     * `node.snapshot(null, null)`，那得到的是**逻辑尺寸**的图（892×692），
     * 而对设备分辨率的纹理做**重采样**——**重采样自己会产生中间值**，
     * 于是"过渡像素"分不清是 MSAA 的还是重采样的（Task 0 的探针正是在这里读不出绝对量）。
     * 现在给 `SnapshotParameters` 一个 `Scale(deviceScale)`，快照就是设备分辨率、
     * 与帧缓冲逐像素对齐（下面有一条断言钉住这个 1:1）。
     */
    private fun snapshotPhase(bridge: FXGLTransfer) {
        val node = canvasNode ?: run {
            println("没有画布节点，跳过快照")
            failures.add("没有画布节点")
            return
        }
        val sp = SnapshotParameters()
        val s = bridge.deviceScale(node)
        sp.transform = Scale(s, s)
        val img = node.snapshot(sp, null)
        // `Image.getWidth()` 返回的是 **double**（不是 Int）——不收窄的话后面全是 Double 运算。
        val snapW = img.width.toInt()
        val snapH = img.height.toInt()
        if (snapW <= 0 || snapH <= 0) {
            println("快照尺寸为 0")
            failures.add("快照尺寸为 0")
            return
        }
        println("\n-- 路径 B：JavaFX 快照（设备缩放 ${"%.4f".format(s)}） --")
        println("  快照 ${snapW}x$snapH   帧缓冲 ${bridge.scaledWidth}x${bridge.scaledHeight}")
        // ★ 1:1 这条是后面所有坐标换算的前提：不是 1:1 的话，用户坐标 ≠ 快照像素坐标，
        //   "窗"就落在错的地方，而读数会**看起来正常**（只是数字不对）。
        report(
            "★ 快照与帧缓冲逐像素 1:1（设备缩放取对了）",
            snapW == bridge.scaledWidth && snapH == bridge.scaledHeight,
            "快照 ${snapW}x$snapH，帧缓冲 ${bridge.scaledWidth}x${bridge.scaledHeight}"
        )

        val pixels = IntArray(snapW * snapH)
        img.pixelReader.getPixels(
            0, 0, snapW, snapH, PixelFormat.getIntArgbInstance(), pixels, 0, snapW
        )

        // 整幅图的颜色直方图：**这是"MSAA 到底有没有生效"的全局读数**（打印，不判定——
        // 全局量里混着圆与斜线的边缘，判据只取下面那个窗）。
        val hist = HashMap<Int, Int>()
        for (p in pixels) hist.merge(p and 0xFFFFFF, 1, Int::plus)
        val edge = hist.entries.filter { it.key != BG_RGB && it.key != LINE_RGB }.sumOf { it.value }
        println("  整幅图：不同 RGB 值 ${hist.size} 个；背景=${hist[BG_RGB] ?: 0}  纯白=${hist[LINE_RGB] ?: 0}" +
            "  ★ 既非两者之一的像素=$edge")
        println("  出现最多的颜色：" + hist.entries.sortedByDescending { it.value }.take(6)
            .joinToString("  ") { "#%06X×%d".format(it.key, it.value) })

        // 探针窗：x 取 [0.1W, 0.9W)，y 取 floor(线心) 的 [-3, +4) 行。
        // ★ 打印的窗**就是**被断言的窗（本仓库的规矩）。
        // ⚠️ 线心的**位置**由帧缓冲高度算出（那是设备像素的权威），而窗要落在**快照**里
        //    ⇒ 两者尺寸不一致时（上面那条 1:1 断言已经 FAIL 了）**必须收窄到快照范围内**：
        //    不收窄就是数组越界，而那会把后面四条判据**全部吞掉**，报告里只剩一条 FAIL
        //    （实测过：那一版抛 `ArrayIndexOutOfBoundsException`，四条判据一条都没评估）。
        //    正常路径上尺寸一致 ⇒ 限幅恒等于原值，读数不受影响。
        val lineYdev = probeLineY(bridge.scaledHeight)
        val x0 = (snapW * WINDOW_X_FRAC).toInt()
        val x1 = snapW - x0
        val yBase = floor(lineYdev).toInt()
        val wantedY0 = yBase + WINDOW_DY0
        val wantedY1 = yBase + WINDOW_DY1
        val y0 = wantedY0.coerceIn(0, snapH)
        val y1 = wantedY1.coerceIn(y0, snapH)
        val winW = x1 - x0
        println("  探针线线心 y = $lineYdev（小数相位 ${"%.2f".format(lineYdev - floor(lineYdev))}），" +
            "带 = [${lineYdev - 2}, ${lineYdev + 2})")
        println("  探针窗 x∈[$x0,$x1) y∈[$y0,$y1)  宽 W=$winW，共 ${winW * (y1 - y0)} 个像素")
        if (y0 != wantedY0 || y1 != wantedY1) {
            println("  ★ 窗被收窄到快照范围内（快照与帧缓冲尺寸不一致——见上面那条 1:1 断言）")
        }

        var fringe = 0
        var white = 0
        var bg = 0
        var coreWhite = 0
        for (y in y0 until y1) {
            val isCore = y >= yBase - 1 && y <= yBase + 1
            var rowWhite = 0
            var rowFringe = 0
            var rowBg = 0
            for (x in x0 until x1) {
                val rgb = pixels[y * snapW + x] and 0xFFFFFF
                when (rgb) {
                    LINE_RGB -> { white++; rowWhite++ }
                    BG_RGB -> { bg++; rowBg++ }
                    else -> { fringe++; rowFringe++ }
                }
            }
            if (isCore) coreWhite += rowWhite
            // 逐行剖面：判据失败时能直接看出"是哪一个过渡行没了"。
            println("    y=$y（相对线心 ${y - yBase}）：纯白 $rowWhite，背景 $rowBg，过渡 $rowFringe")
        }
        // 每列横向剖面（取窗中线那一列，看斜坡形状）。
        val midX = (x0 + x1) / 2
        println("  第 $midX 列纵向剖面 y∈[$y0,$y1)：" +
            (y0 until y1).joinToString(" ") { "y$it=#%06X".format(pixels[it * snapW + midX] and 0xFFFFFF) })

        println("\n-- ★ MSAA 四条判据（W = 窗宽 = $winW） --")
        // ★ 先决条件：窗必须**非空**。它不是形式上的一句话——上面那个限幅会把一个
        //   越界的窗收窄成**空窗**，而空窗里"过渡像素 0 个"（①）与"三类之和 == 窗内像素数"
        //   （④，0 == 0）都是**恒真**的，也就是说不加这一条，尺寸不匹配时会有两条判据
        //   变成橡皮图章（②③ 仍会倒，所以退出码不受影响——但报告里会混着两条假 PASS）。
        report(
            "★ 前置：探针窗非空且落在快照内（W > 0，行数 == $WINDOW_ROWS）",
            winW > 0 && (y1 - y0) == WINDOW_ROWS,
            "窗 x∈[$x0,$x1) y∈[$y0,$y1)：W=$winW，行数 ${y1 - y0}"
        )
        // ① 期望值出处：硬边没有部分覆盖（像素中心采样）⇒ 0；MSAA 下两个过渡行都是部分覆盖 ⇒ 2W。
        val expFringe = if (bridge.msaa == 0) 0 else 2 * winW
        report(
            "★ MSAA① 过渡像素数 = ${if (bridge.msaa == 0) "0（硬边，没有部分覆盖）" else "2W（两个过渡行各 W 个）"}",
            fringe == expFringe,
            "实测 $fringe 个，期望 $expFringe" +
                (if (bridge.msaa == 0) "（msaa=0：像素中心采样 ⇒ 只有满覆盖与不覆盖两种）"
                else "（msaa>0：行 n−2 覆盖率 0.75、行 n+2 覆盖率 0.25 ⇒ 两行都是中间值）")
        )
        // ② 线心 3 行：两种模式下都必须恰好 3W 个纯色像素。它是"线心没移位、没变淡"。
        report(
            "★ MSAA② 线心（相对线心 −1..+1 三行）纯色像素数 = 3W",
            coreWhite == 3 * winW,
            "实测 $coreWhite，期望 ${3 * winW}（= 3 × $winW）"
        )
        // ③ 纯色总数：硬边多覆盖行 n−2 ⇒ 4W；MSAA 下行 n−2 退化成过渡行 ⇒ 3W。
        val expWhite = if (bridge.msaa == 0) 4 * winW else 3 * winW
        report(
            "★ MSAA③ 窗内纯色总数 = ${if (bridge.msaa == 0) "4W（硬边覆盖 4 行）" else "3W（行 n−2 已退化成过渡行）"}",
            white == expWhite,
            "实测 $white，期望 $expWhite（纯白 $white + 背景 $bg + 过渡 $fringe）"
        )
        // ④ 守门员：三类像素之和必须等于窗内像素数——少了它就可能是"漏计了某一类"。
        report(
            "★ MSAA④ 三类像素之和 == 窗内像素数（没有漏计的像素）",
            white + bg + fringe == winW * (y1 - y0),
            "纯白 $white + 背景 $bg + 过渡 $fringe = ${white + bg + fringe}，窗内 ${winW * (y1 - y0)}"
        )

        // 机器可读读数：跨进程那两条（fringe 变大、core 不变）由 msaa-verify.sh 解析它来判。
        println(
            "\nMSAA_READING msaa=${bridge.msaa} fringe=$fringe core=$coreWhite white=$white " +
                "bg=$bg width=$winW snap=${snapW}x$snapH"
        )
    }

    /** 打印判定行，失败则记进 [failures]。 */
    private fun report(label: String, ok: Boolean, detail: String) {
        println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
        if (!ok) failures.add(label)
    }

    /**
     * 汇总并以退出码落定。
     *
     * <p>`halt` 而不是 `exitProcess`：退出前要自己释放的东西这里**没有**
     * （本校验器是一次性的，进程一死 GL 对象就没了），而 `halt` 避开关闭钩子
     * ——钩子里再碰 GL/JavaFX 会与 JavaFX 自己的关停并发碰 D3D，本仓库实测撞出过
     * 原生崩溃（见 `CLAUDE.md` 的「退出路径」那条：`exitProcess` 5 次里崩 1 次，
     * 改成 `halt` 之后连跑 8 次全干净）。
     */
    private fun finish() {
        println("\n=== msaa=$requestedMsaa：${failures.size} 项失败 ===")
        if (failures.isEmpty()) {
            println("=== MSAA 校验全部通过（msaa=$requestedMsaa） ===")
        } else {
            println("=== 失败清单：${failures.joinToString("；")} ===")
        }
        println("MSAA_VERDICT msaa=$requestedMsaa ${if (failures.isEmpty()) "PASS" else "FAIL"}")
        System.out.flush()
        Platform.exit()
        Runtime.getRuntime().halt(if (failures.isEmpty()) 0 else 1)
    }
}

/**
 * 探针线的线心 y（用户坐标 = 设备像素）。
 *
 * <p>取 `floor(h × 0.88) + 0.25`：整数部分让"带盖住哪几行"可算（见文件头），
 * 而 **0.25 那个相位是挑过的**——它让带的两端各自落在某一行的**内部**
 * （覆盖率 0.75 / 0.25），于是 MSAA 下两个过渡行都是**部分覆盖**，
 * 而硬边下"哪几行的像素中心落在带内"也是确定的（行 n−2 在内、行 n+2 在外）。
 *
 * <p>⚠️ **换相位就要重推那四条判据的期望值**（`0.75` 与 `0.25`、"硬边画 4 行"
 * 以及"过渡 2W"都变）——相位不是随手取的，别把它当成一个无关紧要的小数。
 */
private fun probeLineY(h: Int): Float = floor(h.toFloat() * PROBE_Y_FRAC) + PROBE_PHASE
