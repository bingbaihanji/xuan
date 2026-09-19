package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.renderer.Gc
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import kotlin.system.exitProcess

/**
 * GPU 拾取的端到端**像素级校验器**。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>拾取是本项目里最危险的一类子系统：**一个错误的拾取实现不会让任何画面变坏**，
 * 只会让点击落在错误的对象上——而这在肉眼看来完全正常。单元测试也拦不住，
 * 因为它们测不到 GL 光栅化的结果。
 *
 * <p>所以这里用与 [PipelineVerifier] 相同的纪律：画一个每个图元 ID 都已知的场景，
 * 在已知坐标上查询，逐条断言结果。任何一条不成立就以非零码退出。
 *
 * <h2>运行</h2>
 *
 * ```
 * mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
 *     -Dexec.args="-cp %classpath com.bingbaihanji.jfgl.example.PickVerifierKt"
 * ```
 *
 * <p>退出码 0 = 全部通过，1 = 有断言失败。它自己关窗退出，不需要手动关闭。
 */
private const val SCENE_W = 800
private const val SCENE_H = 600

/**
 * 校验器启动入口。
 *
 * <p>函数名不叫 `main`：同包已有一个顶层 `main()`（[PipelineExample]），
 * 两个同名顶层函数会让 `import ...example.main` 报重载歧义，而同包内无法用别名区分。
 * 用 `@JvmName("main")` 把 JVM 方法名钉回 `main`，上面文档里的命令行因此照常可用。
 */
@JvmName("main")
fun pickVerifyMain() {
    Application.launch(PickVerifierApp::class.java)
}

class PickVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null

    /**
     * **刚刚渲染完的那一帧**的序号（从 0 开始）。
     *
     * <p>语义是「已完成」而不是「进行中」：{@code onFrame} 里 [drawScene] 读到的值与
     * 随后 {@code onRender} 里 [verifyOnce] 读到的值相同，两边对「现在是第几帧」没有分歧。
     */
    private var rendered = 0

    // 每个图形一个 ID。0 号不在此列——它恒定表示「什么都没命中」。
    private val idA = 1
    private val idB = 2
    private val idStroke = 3
    private val idTransparent = 4
    private val idClipped = 5

    // 图形几何（用户坐标）
    private val aX = 50f; private val aY = 50f; private val aW = 200f; private val aH = 100f
    private val bX = 150f; private val bY = 100f; private val bW = 200f; private val bH = 100f
    private val sX = 400f; private val sY = 50f; private val sW = 150f; private val sH = 100f
    private val tX = 50f; private val tY = 250f; private val tW = 150f; private val tH = 100f
    private val clipX = 400; private val clipY = 250; private val clipW = 100; private val clipH = 100

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
        bridge.onFrame { gc -> drawScene(gc) }
        bridge.onRender { verifyOnce() }
        transfer = bridge

        // 注册 payload，用来验证「ID 能解析回对象」这条链路。
        // 必须在 onInit 里注册：start() 的此刻 GL 还没初始化，gc() 返回 null，
        // 注册会静默不执行——然后「payload 解析」那条断言就会失败。
        //
        // ID 恰好是 1..5，与上面 idA..idClipped 的取值一致，因为注册表从 1 开始
        // 递增分配。这个耦合是刻意写明的：如果注册顺序变了，这段与上面的常量必须一起改。
        bridge.onInit {
            bridge.gc()?.let { gc ->
                gc.pickRegistry.register("A")
                gc.pickRegistry.register("B")
                gc.pickRegistry.register("Stroke")
                gc.pickRegistry.register("Transparent")
                gc.pickRegistry.register("Clipped")
            }
        }

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "JFGL Pick Verifier"
        stage.scene = Scene(mainView.createMainView(), SCENE_W.toDouble(), SCENE_H.toDouble())
        stage.show()
    }

    /**
     * 画场景。
     *
     * <p>帧 0 与帧 1 **不带任何拾取 ID**，用来验证「整帧无拾取对象时 ID pass 被跳过」。
     * 帧 2 起才带上 ID。这个划分与 [verifyOnce] 里的两处计数断言是一对的。
     */
    private fun drawScene(gc: Gc) {
        val withIds = rendered >= 2

        // A：只被 B 覆盖一部分
        gc.pickId = if (withIds) idA else 0
        gc.fill = 0xFFCC0000.toInt()
        gc.fillRect(aX, aY, aW, aH)

        // B：后画，压在 A 上面 → 重叠处 B 赢
        gc.pickId = if (withIds) idB else 0
        gc.fill = 0xFF00CC00.toInt()
        gc.fillRect(bX, bY, bW, bH)

        // 纯描边：只有边可拾取，内部不可
        gc.pickId = if (withIds) idStroke else 0
        gc.stroke = 0xFF0000FF.toInt()
        gc.lineWidth = 6f
        gc.strokeRect(sX, sY, sW, sH)

        // 全透明填充：肉眼看不见，但必须仍可拾取（隐形热区）
        gc.pickId = if (withIds) idTransparent else 0
        gc.fill = 0xFF00CCCC.toInt()
        gc.globalAlpha = 0f
        gc.fillRect(tX, tY, tW, tH)
        gc.globalAlpha = 1f

        // 被裁剪：大矩形只画出与裁剪区的交集，因此也只有交集可拾取
        gc.pickId = if (withIds) idClipped else 0
        gc.fill = 0xFFCC00CC.toInt()
        gc.save()
        gc.clipRect(clipX.toFloat(), clipY.toFloat(), clipW.toFloat(), clipH.toFloat())
        gc.fillRect(350f, 200f, 300f, 300f)
        gc.restore()
    }

    private fun verifyOnce() {
        val bridge = transfer ?: return
        val justRendered = rendered
        rendered++

        // 帧 0 与帧 1 画的全是 ID=0 的图元：一趟 ID pass 都不该跑。
        // 这条断言必须在带上 ID 之前取样——等到校验帧再看，计数里已经混进了
        // 后面那些带 ID 的帧，就什么都证明不了了。
        if (justRendered == 1) {
            reportSkipOptimization(bridge, expected = 0,
                detail = "前两帧均无拾取 ID")
            return
        }
        // 帧 2、3、4 带 ID，各跑一趟；此刻刚好三趟。
        if (justRendered < 4) return

        val gc = bridge.gc() ?: return

        val failures = ArrayList<String>()

        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        // PickHit / PickPixel 是 Java record，从 Kotlin 一律用显式访问器调用
        // （hit.id() 而不是 hit.id）：属性语法依赖 Kotlin 对 record 组件的处理，
        // 显式调用则永远是合法的 Java 方法调用。
        fun expectPick(label: String, x: Float, y: Float, expectedId: Int) {
            val hit = gc.pick(x, y)
            val actual = hit?.id() ?: 0
            report(label, actual == expectedId,
                "($x,$y) 实际=$actual 期望=$expectedId")
        }

        // 标题与「跳过优化」一节已在第 1 帧处打印过了，这里接着往下走。
        println("\n-- 跳过优化 --")
        // 帧 0、1 无 ID（已在第 1 帧处断言过为 0），帧 2、3、4 各跑一趟 → 恰好 3。
        // 这个数字与 drawScene 的帧划分是一对的：改动任何一边都要同步另一边。
        // 注意这个数字把两件事混在一起了：①帧 0、1 没有可拾取顶点，跳过优化生效；
        // ②帧 2、3、4 期间没有发生帧中途 flush。drawPickPass 是每个 submit 调一次
        // （不是每帧），任何一次 flushIfNeeded 都会让计数 +1。
        //
        // 所以若实测不是 3，**不要改这个数字**——先查是哪种原因：
        //   - 优化失效（每帧都跑）→ 5 或更多，那是真缺陷，去查 hasPickableVertices 那条链；
        //   - pickPassCount 被误加了复位 → 1，见 RenderBatch 类注释里那条警告；
        //   - 帧中途 flush → 多出来的那部分，属良性，但仍要如实记录实际值。
        // 直接改成实测值会让这条断言失去意义：它对「数字是多少」不敏感，对「为什么」才敏感。
        report("有拾取对象时每帧恰好一趟 ID pass", bridge.pickPassCountForTest() == 3,
            "ID pass 执行次数=${bridge.pickPassCountForTest()}，期望 3（帧 2、3、4）")

        println("\n-- 点查询 --")
        expectPick("A 独占区域命中 A", 100f, 80f, idA)
        expectPick("重叠区域取最上层（B）", 200f, 120f, idB)
        expectPick("B 独占区域命中 B", 300f, 180f, idB)
        expectPick("A 左边缘外 1px 未命中", aX - 1f, aY + 30f, 0)
        expectPick("A 上边缘外 1px 未命中", aX + 30f, aY - 1f, 0)
        expectPick("描边边缘命中", sX + sW / 2f, sY, idStroke)
        expectPick("描边内部未命中（纯描边不填充）", sX + sW / 2f, sY + sH / 2f, 0)
        expectPick("全透明图元仍可拾取", tX + tW / 2f, tY + tH / 2f, idTransparent)
        expectPick("裁剪区内命中", 420f, 270f, idClipped)
        expectPick("被裁掉的区域未命中", 380f, 270f, 0)
        expectPick("画面空白处未命中", 700f, 560f, 0)

        println("\n-- payload 解析 --")
        val hitA = gc.pick(100f, 80f)
        report("命中结果能解析回注册对象", hitA?.payload() == "A",
            "payload=${hitA?.payload()}")

        println("\n-- 矩形区域查询 --")
        fun expectRect(label: String, x: Float, y: Float, w: Float, h: Float, expected: Set<Int>) {
            val actual = gc.pickRect(x, y, w, h).map { it.id() }.toSet()
            report(label, actual == expected, "实际=$actual 期望=$expected")
        }
        expectRect("区域内只有 A", 60f, 60f, 80f, 40f, setOf(idA))
        expectRect("覆盖 A 与 B", 60f, 60f, 320f, 160f, setOf(idA, idB))

        // 首次出现坐标：区域从 (60,60) 起逐行扫描。A 在区域左上角就出现；
        // B 要到 y=100 那一行、且 x 越过 A 的右边界（250）之前的 150 才出现。
        // 这一条专门钉住 readRect 的扫描方向——回读结果自下而上，行序反了会得到
        // 上下颠倒的坐标，而 ID 集合完全正确，光看集合发现不了。
        val abHits = gc.pickRect(60f, 60f, 320f, 160f).associateBy { it.id() }
        val aAt = abHits[idA]
        val bAt = abHits[idB]
        report("A 首次出现坐标", aAt != null && aAt.x() == 60f && aAt.y() == 60f,
            "实际=(${aAt?.x()},${aAt?.y()}) 期望=(60.0,60.0)")
        report("B 首次出现坐标", bAt != null && bAt.x() == 150f && bAt.y() == 100f,
            "实际=(${bAt?.x()},${bAt?.y()}) 期望=(150.0,100.0)")
        expectRect("覆盖裁剪区与描边", 390f, 45f, 180f, 320f, setOf(idStroke, idClipped))
        expectRect("完全在画面外", -500f, -500f, 10f, 10f, emptySet())
        expectRect("拖到画面外仍返回交集部分", -500f, -500f, 700f, 700f, setOf(idA, idB))

        println("\n-- 状态栈 --")
        // 这里先显式把 ID 置 0，再 save——**不能**假设进入校验帧时 pickId 就是 0。
        // beginFrame/endFrame 复位的是样式"栈"（styleDepth），**不是样式的值**：
        // fill/stroke/pickId/lineWidth/globalAlpha 与变换栈不同，是跨帧保留的。
        // 而 drawScene 最后一步是 `pickId = idClipped` 之后 restore，所以本帧刚画完时
        // pickId 其实是 idClipped(5)。不先把"save 时的值"钉成 0，这条断言测的就不是
        // restore() 本身，而是"上一帧结束时 pickId 恰好是 0"这个不成立的巧合。
        gc.pickId = 0
        gc.save()
        gc.pickId = idA
        gc.pickable(idB) {
            report("pickable 块内 ID 生效", gc.pickId == idB, "块内 pickId=${gc.pickId}")
        }
        report("pickable 块结束后 ID 复原", gc.pickId == idA, "块后 pickId=${gc.pickId}")
        gc.restore()
        report("restore 后 ID 回到 save 时的值（0）", gc.pickId == 0, "pickId=${gc.pickId}")

        println()
        if (failures.isEmpty()) {
            println("=== 全部通过 ===")
        } else {
            println("=== 失败 ${failures.size} 项：${failures.joinToString("；")} ===")
        }

        Platform.exit()
        exitProcess(if (failures.isEmpty()) 0 else 1)
    }

    /**
     * 断言「无拾取对象时整趟跳过 ID pass」。
     *
     * <p>这是**唯一一条在取样帧当场判定**的断言（其余都攒到校验帧统一报告）：
     * 它必须在带上拾取 ID **之前**取样——等到校验帧再看，计数里已经混进了后面那些
     * 带 ID 的帧，就什么都证明不了了。
     *
     * @param bridge   桥接对象
     * @param expected 期望的 ID pass 执行次数
     * @param detail   说明文字
     */
    private fun reportSkipOptimization(bridge: FXGLTransfer, expected: Int, detail: String) {
        val actual = bridge.pickPassCountForTest()
        val ok = actual == expected
        println("=== JFGL 拾取校验（帧缓冲 ${bridge.scaledWidth}x${bridge.scaledHeight}）===")
        println("\n-- 跳过优化 --")
        println("  [${if (ok) "PASS" else "FAIL"}] 无拾取对象时不渲染 ID pass — " +
                "$detail，实际=$actual 期望=$expected")
        if (!ok) {
            println("\n=== 失败 1 项 ===")
            Platform.exit()
            exitProcess(1)
        }
    }

    override fun stop() {
        transfer?.dispose()
    }
}
