package com.bingbaihanji.xuan.example

import com.bingbaihanji.xuan.glview.FXGLTransfer
import com.bingbaihanji.xuan.renderer.Gc
import com.bingbaihanji.xuan.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import java.util.concurrent.CopyOnWriteArrayList
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
 *     -Dexec.args="-cp %classpath com.bingbaihanji.xuan.example.PickVerifierKt"
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

    /**
     * **陈旧 ID 检查帧**：本帧只画被裁剪的那个矩形，其余图元全不画。
     *
     * <p>存在的理由：前几帧画的是同一个场景，上一帧留下的陈旧 ID 与这一帧的新鲜 ID
     * 恰好相同，`clear()` 的 scissor 泄漏因此完全不可见。只有「上一帧有、这一帧没有」
     * 的图元才暴露它——而那个缺陷的危害正是「用户点到一个已经消失的对象」。
     */
    private val staleIdFrame = 5

    /**
     * **异步路径的空场景帧**：整帧不画任何可拾取的东西。
     *
     * <p>与 [staleIdFrame] 同源，只是守的是另一条路径。帧 [staleIdFrame] 让**同步** `pick`
     * 暴露 `clear()` 的 scissor 泄漏；这一帧让**异步** PBO 读回暴露「ID pass 被跳过时，
     * 缓冲里留的是上一帧的 ID」。
     *
     * <p>为什么非要有它：`RenderBatch.enqueueAsyncPickPixel` 里那道
     * `if (!pickBufferValid) return NO_PICK_CONTENT` 守卫，**去掉它之后帧 0 那种场景
     * 照样通过**——帧 0 之前没有任何带 ID 的帧，缓冲里本来就全是 0，"读到旧值"
     * 与"读到 0"分不开。只有「前面有 ID、这一帧没有」才把两者分开。
     *
     * <p>⚠️ **要测的是这一帧，但请求必须在上一帧提交**——见 [asyncPhase] 里那段
     * 「读的是哪一帧」。第一版把它写成"在空帧上提交"，观测到的是**下一帧**的场景，
     * 于是断言失败而实现是对的。
     */
    private val asyncBlankFrame = 14

    /**
     * 帧 4 的失败数。
     *
     * <p>帧 4 报告完**不退**：它之后还有两个阶段要取样（帧 5 的陈旧 ID 检查、
     * 帧 5 起的异步拾取）。所有阶段的失败数在最后一并算进退出码——
     * 中途退出会让「已经打印了 FAIL 却报退出码 0」成为可能。
     */
    private var frameFourFailures = 0

    /** 帧 5 陈旧 ID 检查的失败数，与 [frameFourFailures] 一起并入最终退出码。 */
    private var staleIdFailures = 0

    /**
     * 异步拾取的提交记录。只在 GL 线程写，只在 GL 线程读。
     */
    private val asyncSubmissions = ArrayList<AsyncExpectation>()

    /**
     * 异步拾取的回收结果。
     *
     * <p>**由 JavaFX 线程写、GL 线程读**——这正是异步拾取存在的意义（回调必须回到
     * JavaFX 线程）。所以不能用普通 [ArrayList]：那个的读写在跨线程下没有可见性保证，
     * 表现是「结果明明交付了，校验器却报未交付」，而且**随机器负载时有时无**。
     */
    private val asyncOutcomes = CopyOnWriteArrayList<AsyncOutcome>()

    /**
     * 异步阶段的兜底截止帧。
     *
     * <p>正常情况到第 9~10 帧就齐了（PBO 读回至少跨一帧，加上 `Platform.runLater`
     * 还要等 JavaFX 线程空出来）。留到 20 帧是为了**把它变成一个确定性的失败**：
     * 结果永远不来时，报告里会出现「未交付」而不是校验器永久挂着不退出。
     */
    private val asyncDeadlineFrame = 30

    /**
     * 最后一次异步提交所在的帧（即 [asyncBlankFrame] 的前一帧）。
     *
     * <p>结算判据必须等它过去——否则那些**还没提交**的断言会在结算时被整批跳过，
     * 而报告上写着「全部通过」。见 [asyncPhase] 里那段说明。
     */
    private val asyncLastSubmitFrame = asyncBlankFrame - 1

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
        // 采样数走**同一个系统属性**（`-Dxuan.probe.msaa`，解析与 MsaaVerifier 共用）：
        // 没有这一行时下面那道守卫是**死代码**——msaa 恒为默认 0，"明确拒绝"只在改源码时才可能触发。
        val bridge = FXGLTransfer(msaa = readRequestedMsaa(), font = textFont())
        // ★ 回读拒绝守卫（实现见 MsaaVerifier.kt 的 requirePixelReadback）：本校验器的读数
        //   全部来自 glReadPixels，而多采样画布上那次调用是**非法操作**——它会读回全 0，
        //   然后让下面每一条断言报"画面全黑"式的假失败。
        requirePixelReadback(bridge)
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
        stage.title = "Xuan Pick Verifier"
        stage.scene = Scene(mainView.createMainView(), SCENE_W.toDouble(), SCENE_H.toDouble())
        stage.show()
    }

    /**
     * 画场景。
     *
     * <p>帧 0 与帧 1 **不带任何拾取 ID**，用来验证「整帧无拾取对象时 ID pass 被跳过」。
     * 帧 2 起才带上 ID。这个划分与 [verifyOnce] 里的两处计数断言是一对的。
     *
     * <p>帧 [staleIdFrame] 起，前四个图元**全不画**——它们是「上一帧有、这一帧没有」
     * 的那批，用来暴露 `clear()` 的 scissor 泄漏。被裁剪的那个矩形照画，
     * 保证 ID pass 仍会跑、且最后一条命令仍带裁剪盒（泄漏的触发条件）。
     */
    private fun drawScene(gc: Gc) {
        val withIds = rendered >= 2

        if (rendered < staleIdFrame) {
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
        }

        // 帧 asyncBlankFrame 整帧什么都不画：这是给异步路径准备的「上一帧有、这一帧没有」
        // 场景（理由见那个字段的说明）。**必须放在这里返回**，不能只把 pickId 置 0
        // ——图元仍然会被提交，`hasPickableVertices()` 仍为真，ID pass 照跑，
        // 于是那道守卫还是走不到。
        if (rendered == asyncBlankFrame) {
            return
        }

        // 被裁剪：大矩形只画出与裁剪区的交集，因此也只有交集可拾取
        gc.pickId = if (withIds) idClipped else 0
        gc.fill = 0xFFCC00CC.toInt()
        gc.save()
        gc.clipRect(clipX.toFloat(), clipY.toFloat(), clipW.toFloat(), clipH.toFloat())
        gc.fillRect(350f, 200f, 300f, 300f)
        gc.restore()
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
     *
     * <p>本文件有**两处**退出点（帧 1 的「跳过优化」与异步阶段末尾的结算），
     * 两者都在被包裹的 [verifyAll] 体内，所以这一层 try/catch 同时护住它们。
     * **退出点越少越好**：每一处提前退出都是一个「前面打印的 FAIL 被后面的 0 盖掉」
     * 的机会，所以帧 4 与帧 5 都只记录、不退出。
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
        val gc = bridge.gc() ?: return

        // 帧 2、3 本来就直接返回（同步那一整套断言在帧 4 做）。它们在这里顺带提交
        // 异步请求：**这两帧场景完整**（A/B/描边/透明/裁剪块全在），正好让异步路径
        // 面对一个"有几个不同答案"的场景，而不是只有单一 ID 的场景——
        // 单一场景下"读到了正确的那一个"和"读到了唯一的那一个"分不开。
        if (justRendered < 4) {
            when (justRendered) {
                // 帧 0、1 画的全是 ID=0 的图元，ID pass 一趟都不跑。这一条走的是
                // `resolvePendingPick` 里**另一条分支**：`RenderBatch.enqueueAsyncPickPixel`
                // 见到 `pickBufferValid == false` 直接返回 NO_PICK_CONTENT，回调收到 null。
                // 少了它，那条分支（以及它依赖的 pickBufferValid 守卫）一次都走不到。
                // 后者正是「跳过 ID pass 时异步读回会拿到上一帧残留 ID」的那道闸。
                0 -> submitAsync(bridge, gc, 100f, 80f, 0)
                2 -> submitAsync(bridge, gc, 100f, 80f, idA)
                3 -> submitAsync(bridge, gc, 200f, 120f, idB)
            }
            return
        }

        // 【陈旧 ID 检查】帧 5 只画被裁剪的那个矩形，其余图元全不画，所以
        // (100,80) 这一帧什么都没有——那里必须不再命中。这一段必须在帧 4 的
        // 完整校验**之前**返回：否则帧 5 会把帧 4 那一整套断言（含 ID pass 计数）
        // 再跑一遍，而那套断言是按帧 4 的场景写死的。
        if (justRendered == staleIdFrame) {
            println("\n-- 陈旧 ID 检查（上一帧有、这一帧没有） --")
            val staleFailures = ArrayList<String>()

            fun reportStale(label: String, ok: Boolean, detail: String) {
                println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
                if (!ok) staleFailures.add(label)
            }

            val atA = gc.pick(100f, 80f)?.id() ?: 0
            val atClip = gc.pick(420f, 270f)?.id() ?: 0
            // (420,270) 证明 ID pass 确实跑了——否则"没命中"可能只是因为它没跑，
            // 那样这条断言就变成了恒真检查。
            reportStale("已消失图元的位置不再命中", atA == 0,
                "(100,80) 实际=$atA 期望=0（帧 4 该处是 idA=$idA）")
            reportStale("本帧仍在的图元照常命中（证明 ID pass 跑了）", atClip == idClipped,
                "(420,270) 实际=$atClip 期望=$idClipped")

            staleIdFailures = staleFailures.size
        }

        // 三个阶段的失败合并成同一个退出码。**任何一阶段都不许自己先退出**：
        // 帧 4 不退是为了让帧 5 能取样，帧 5 不退是为了让异步阶段能取样。
        // 中途 exitProcess(0) 会让前面打印的 FAIL 白打印——那正是本仓库最忌讳的
        // 「静默的绿」。真正的结算点在 asyncPhase 的末尾。
        if (justRendered >= staleIdFrame) {
            asyncPhase(justRendered, bridge, gc)
            return
        }

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

        // 本帧场景仍完整，再提交一条异步请求——这一条的期望答案是"什么都没命中"，
        // 用来钉住异步路径上的 null 与同步路径上的 null 是同一个东西
        // （交付一个"有 ID 但 payload 为 null"的结果会被本条抓到）。
        submitAsync(bridge, gc, 700f, 560f, 0)

        // 帧 4 报告完**不退**：帧 5 才是「上一帧有、这一帧没有」的场景，
        // 那条断言只能在帧 5 取样。失败数先存着，退出码留到最后一起算。
        frameFourFailures = failures.size
        println()
        println(if (failures.isEmpty()) "=== 帧 4 校验全部通过，继续等帧 $staleIdFrame ==="
        else "=== 帧 $justRendered 失败 ${failures.size} 项：${failures.joinToString("；")} ===")
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
        println("=== Xuan 拾取校验（帧缓冲 ${bridge.scaledWidth}x${bridge.scaledHeight}）===")
        println("\n-- 跳过优化 --")
        println("  [${if (ok) "PASS" else "FAIL"}] 无拾取对象时不渲染 ID pass — " +
                "$detail，实际=$actual 期望=$expected")
        if (!ok) {
            println("\n=== 失败 1 项 ===")
            Platform.exit()
            exitProcess(1)
        }
    }

    /**
     * 异步拾取（PBO 读回）阶段：提交请求、回收结果、统一结算退出码。
     *
     * <h2>为什么提交节奏被压成「每帧一条」</h2>
     *
     * 两条都是**被测实现的性质**，不是校验器图省事：
     * - `FXGLTransfer.pickAsync` 只保留最新一次请求（连续调用会覆盖前一次的回调）；
     * - `FXGLTransfer.resolvePendingPick` 每帧只回收一条结果。
     *
     * 所以一帧里连发多条，除最后一条外其余回调**永远不会被调用**——那是写进方法
     * 文档的语义，不是缺陷。校验器据此安排：绝大多数帧只发一条，只在
     * `staleIdFrame + 2` 那一帧故意连发三条，专门钉住「最新覆盖旧的」。
     *
     * <h2>这一节补的是哪一块</h2>
     *
     * `PickBufferTest` 那两条用的是**假 GL**：它把「PBO 读发生在 ID pass 之后」当成
     * 前提直接抄像素，而真实 GL 里那是**命令流顺序**。真正只有真上下文能证的几件事
     * ——fence 会不会真的 signal、从 PBO 里读回的是不是那个 ID、回调落在哪个线程
     * ——都在这一节。
     *
     * <h2>⚠️ 读的是哪一帧</h2>
     *
     * 这一节里所有期望值都按「**提交帧 + 1**」的场景算，不是提交帧的场景。
     *
     * <p>理由在时序里：`[verifyAll]` 跑在 `onRender` 回调内，而 `resolvePendingPick`
     * 在它**之前**——所以本帧提交的请求要到**下一帧**的 `resolvePendingPick` 才入队，
     * 那时 `glReadPixels` 被插在下一帧 ID pass 之后，读到的自然是下一帧的内容。
     *
     * <p>第一版把空帧那条写成"在空帧上提交"，于是它读到了再下一帧（不空）的场景，
     * 报了一次**假失败**。凡是这类"帧号相邻"的期望值，改帧计划时都要按这一条重算。
     *
     * @param frame  刚刚渲染完的帧号
     * @param bridge 桥接对象
     * @param gc     当前帧的绘制上下文
     */
    private fun asyncPhase(frame: Int, bridge: FXGLTransfer, gc: Gc) {
        when (frame) {
            // 帧 5 起场景只剩被裁剪的那个矩形：A/B/描边/透明全不画了。
            // 于是 (100,80) 从 idA 变成「什么都没命中」——这一条同时钉住两件事：
            // 异步路径读到的必须是**本帧**的 ID pass，而不是上一帧残留的。
            staleIdFrame -> submitAsync(bridge, gc, 100f, 80f, 0)
            staleIdFrame + 1 -> submitAsync(bridge, gc, 420f, 270f, idClipped)
            staleIdFrame + 2 -> {
                // 一帧连发三条：只有最后一条会交付。前两条的期望值刻意与第三条不同，
                // 这样「第一条赢」与「最后一条赢」给出**不同的观测结果**——
                // 若三条期望值相同，这个变异就抓不住了。
                submitAsync(bridge, gc, 60f, 60f, 0, deliverExpected = false)
                submitAsync(bridge, gc, 450f, 300f, idClipped, deliverExpected = false)
                submitAsync(bridge, gc, 430f, 280f, idClipped)
            }
            staleIdFrame + 3 -> {
                // ★ 从 **JavaFX 线程**提交——这才是生产用法（鼠标事件在 JavaFX 线程上，
                // 而 Gc 只能在 GL 线程用）。上面几条都是从 GL 线程直接调的，走不到
                // 那个 AtomicReference 的跨线程交接。少了这一条，「跨线程提交」
                // 这个真实场景在本校验器里一次都没被走到。
                submitAsync(bridge, gc, 480f, 330f, idClipped, onFxThread = true)
            }
            // ★ 在空帧的**前一帧**提交，(420,270) 到读取时所在的那一帧（= 空帧）
            // 已经什么都不画了，必须是 0。去掉 `RenderBatch.enqueueAsyncPickPixel`
            // 那道 pickBufferValid 守卫之后，这里会读到缓冲里残留的 5
            // —— 而画面、以及前面所有断言都是正常的。
            //
            // checkSync = false：自洽对照在这条上**天然不成立**——提交帧的场景是
            // "有 idClipped"，读取帧的场景是"什么都没有"，同步答案（5）与正确结果（0）
            // 本来就该不同。硬套它会冤枉一个正确的实现。这里只剩硬编码期望这一道判据，
            // 而它本来就是这个文件里更强的那一道。
            asyncBlankFrame - 1 -> submitAsync(bridge, gc, 420f, 270f, 0, checkSync = false)
        }

        // ⚠️ 先等**最后一次提交**发生，再谈结算。
        //
        // 少了这一道，判据会退化成「**目前**提交了的都到达了吗」——于是它在最后一次
        // 提交之前就满足了，结算退出，那几条后来的断言**一次都没跑**，而报告上写着
        // 「全部通过」。第一版实测就是这样：seq10（空帧那条，最有价值的一条）压根没
        // 提交，退出码 0。**它不是一条会失败的断言，它是一条被静默跳过的断言**——
        // 比失败更坏，因为失败会有人查。
        if (frame <= asyncLastSubmitFrame) {
            return
        }

        // 结果齐了才结算；没齐就等下一帧，但到截止帧仍不齐就**判失败**——
        // 不这么写的话「结果永远不来」表现为校验器永久挂着，那是另一种静默。
        val expected = asyncSubmissions.filter { it.deliverExpected }
        val arrived = expected.count { e -> asyncOutcomes.any { it.seq == e.seq } }
        if (arrived < expected.size && frame < asyncDeadlineFrame) {
            return
        }
        reportAsyncAndExit()
    }

    /**
     * 提交一次异步拾取，并记下**提交当帧同步 pick 的答案**作为自洽对照。
     *
     * <p>对照的价值：异步与同步必须给出同一个结果，而它们是两条独立实现
     * （一条走 PBO + fence，一条走同步 `glReadPixels`）。**但只有它不够**——
     * 两条路径的 y 翻转若一起写错，它们会一起错、读数依然彼此相等
     * （本项目已经吃过一次「对照组与错误同向变化」的亏）。所以每条断言**同时**
     * 要求硬编码的期望 ID 与同步答案两者都成立。
     *
     * @param bridge          桥接对象
     * @param gc              当前帧的绘制上下文（用来取同步答案）
     * @param x               查询点 x
     * @param y               查询点 y
     * @param expectedId      硬编码的期望 ID（0 = 期望未命中）
     * @param deliverExpected 该请求是否**应该**被交付；一帧里连发的第二条起都是 false
     * @param onFxThread      是否从 JavaFX 线程提交（生产用法）
     * @param checkSync       是否把「提交当帧的同步答案」也当作判据。**只在该帧与
     *                        读取帧场景相同时才成立**（见 [asyncPhase] 的「读的是哪一帧」）
     */
    private fun submitAsync(
        bridge: FXGLTransfer, gc: Gc, x: Float, y: Float, expectedId: Int,
        deliverExpected: Boolean = true, onFxThread: Boolean = false, checkSync: Boolean = true
    ) {
        val seq = asyncSubmissions.size
        val syncId = if (checkSync) gc.pick(x, y)?.id() ?: 0 else -1
        asyncSubmissions.add(
            AsyncExpectation(seq, x, y, expectedId, syncId, deliverExpected, onFxThread, checkSync)
        )

        // 闭包捕获 seq，而不是拿回调里的 x/y 去反查：帧 5 与帧 2 用的是同一个坐标
        // (100,80)，但期望答案不同（0 与 1）。按坐标反查会把两条结果混在一起，
        // 而「混在一起」的表现恰好是两条断言一起通过或一起失败——分不出哪条错了。
        val submit = Runnable {
            bridge.pickAsync(x, y) { hit ->
                asyncOutcomes.add(
                    AsyncOutcome(seq, hit?.id() ?: 0, hit?.payload(), Platform.isFxApplicationThread())
                )
            }
        }
        if (onFxThread) {
            Platform.runLater(submit)
        } else {
            submit.run()
        }
    }

    /**
     * 结算：逐条断言异步结果，然后以退出码收场。
     *
     * <p>这里是**唯一**的退出点（帧 4、帧 5 都不退），所以三个阶段的失败数在这里合并。
     */
    private fun reportAsyncAndExit() {
        println("\n-- 异步拾取（PBO 读回） --")
        val failures = ArrayList<String>()

        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        for (e in asyncSubmissions) {
            val where = "(${e.x},${e.y})"
            val got = asyncOutcomes.filter { it.seq == e.seq }
            // singleOrNull 而不是 firstOrNull：交付两次也是错的（回调被重复调用），
            // 而 firstOrNull 会把重复交付掩盖成一次正常交付。
            val one = got.singleOrNull()

            if (!e.deliverExpected) {
                // 一帧连发时被覆盖的那几条：回调**不该**被调用。这条不是凑数——
                // 它钉住「最新覆盖旧的」。若实现改成排队，这里会收到结果，
                // 而排队在生产里表现为 hover 回调越积越多、延迟越来越大。
                report("[seq${e.seq}] 被覆盖的请求不交付（最新覆盖旧）", got.isEmpty(),
                    "$where 收到 ${got.size} 条结果${if (got.isEmpty()) "" else "(id=" + got.map { it.id } + ")"}")
                continue
            }
            report("[seq${e.seq}] 异步拾取交付且 ID 正确", one != null && one.id == e.expectedId,
                "$where 期望=${e.expectedId} 实际=${one?.id ?: "未交付"}，收到 ${got.size} 条")
            if (e.checkSync) {
                report("[seq${e.seq}] 异步与同步结论一致（自洽对照）", one != null && one.id == e.syncId,
                    "$where 同步=${e.syncId} 异步=${one?.id ?: "未交付"}")
            }
        }

        // 汇总断言：回调**必须**落在 JavaFX 线程上。不逐条报是因为它的判据是
        // 「有没有任何一条跑错了线程」，逐条报会淹没在噪音里。
        val wrongThread = asyncOutcomes.filter { !it.onFxThread }
        report("全部异步回调都在 JavaFX 线程上", asyncOutcomes.isNotEmpty() && wrongThread.isEmpty(),
            "${asyncOutcomes.size} 条结果中 ${wrongThread.size} 条不在 JavaFX 线程")

        // payload 链路：从 PBO 读回的 ID 也要能经注册表解析回对象。
        val fxSeq = asyncSubmissions.firstOrNull { it.onFxThread }?.seq
        val fxOutcome = fxSeq?.let { s -> asyncOutcomes.firstOrNull { it.seq == s } }
        report("跨线程提交的结果能解析回注册对象", fxOutcome?.payload == "Clipped",
            "payload=${fxOutcome?.payload}")

        val total = frameFourFailures + staleIdFailures + failures.size
        println()
        if (total == 0) {
            println("=== 全部通过 ===")
        } else {
            println("=== 失败 $total 项（帧 4 共 $frameFourFailures 项，" +
                    "帧 $staleIdFrame 共 $staleIdFailures 项，异步阶段共 ${failures.size} 项）：" +
                    "${failures.joinToString("；")} ===")
        }

        Platform.exit()
        exitProcess(if (total == 0) 0 else 1)
    }

    /**
     * 一次异步拾取的提交记录。
     *
     * @param seq             序号（唯一，回调按它归位）
     * @param x               查询点 x
     * @param y               查询点 y
     * @param expectedId      硬编码的期望 ID
     * @param syncId          提交当帧同步 `Gc.pick` 给出的答案；[checkSync] 为假时是 -1
     * @param deliverExpected 该请求是否应该被交付
     * @param onFxThread      是否从 JavaFX 线程提交
     * @param checkSync       是否把同步答案也当作判据
     */
    private class AsyncExpectation(
        val seq: Int, val x: Float, val y: Float, val expectedId: Int,
        val syncId: Int, val deliverExpected: Boolean, val onFxThread: Boolean,
        val checkSync: Boolean
    )

    /**
     * 一条异步拾取的回收结果。
     *
     * @param seq        对应的提交序号
     * @param id         命中的拾取 ID；0 表示未命中
     * @param payload    ID 解析回的对象（未命中或未注册时为 null）
     * @param onFxThread 回调执行时是否在 JavaFX 线程上
     */
    private class AsyncOutcome(val seq: Int, val id: Int, val payload: Any?, val onFxThread: Boolean)

    override fun stop() {
        transfer?.dispose()
    }
}
