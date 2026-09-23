package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.gl.LwjglGLAbstraction
import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.gpu.FftKernel
import com.bingbaihanji.jfgl.gpu.FftWindow
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL43.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * `gpu/FftKernel.java` 输出正确性的**数值校验器**：喂进已知频率的纯正弦，
 * 断言峰值落在正确的 bin、与一份朴素 O(N²) DFT 逐 bin 相符、换窗不改变幅度读数、
 * 跨环绕取样本仍然正确、两个端点长度都跑得对。失败以非零码退出。
 *
 * <h2>它从哪来</h2>
 *
 * <p>本文件由 `GpuFftVerifier.kt`（提交 `adab313`）**改造**而来。那个版本测的是旧
 * `GPUFFT`，实测揭出它**三层缺陷**（保留字 `half` 导致从未编译成功；
 * `glUniform1i` 喂 `uint` uniform 导致 dispatch 空转；DIT 缺输入位反转导致峰位错乱），
 * 因此它**预期永远是红的**。
 *
 * <p>**一个预期永远红的校验器是负债**：人一旦习惯"它红是正常的"，它就再也不起 gate 作用。
 * 那三层缺陷的记录**已经落在两处不会腐烂的地方**——`CLAUDE.md` 的「未实现 / 待办」一节
 * 与提交 `adab313` 自身——所以这里只留这一句指回它们，不再重述。
 *
 * <p>旧版中已经证明有效的判据被保留下来：**与 CPU 参考逐 bin 比对**、
 * **一个最小 kernel 只换 uniform 类型**的对照实验（它把缺陷②钉死过）、
 * 以及"退出码必须钉死"的 `try/finally` 写法。
 *
 * <h2>被测对象</h2>
 *
 * <p>`FftKernel` 是**一次 dispatch 干完全部**的核：取数 → 加窗 → 位反转 →
 * log2(N) 级蝶形 → 幅度，全部在单个 workgroup 的 shared memory 里做完。
 * 输入是 ② 的环形缓冲，输出是**半谱**（`N/2+1` 个 bin，含 DC 与 Nyquist）。
 *
 * <h2>★ 四条实测警告，直接决定了下面判据怎么写</h2>
 *
 * <h3>一、不用"非峰处应当接近零"这种绝对阈值</h3>
 *
 * <p>旧校验器的 `SIDE_LOBE_LIMIT = 1%` 在**矩形窗**下成立（实测非峰 5.99e-08），
 * 但**对 Hann / Hamming / BH 会直接失败**：窗本身把能量摊开，实测非峰最大是
 * **0.503 / 0.428 / 0.683**（相对峰高 1.0）——而它们与 CPU 参考的偏差只有 **1e-7**。
 * **那是窗的主瓣/旁瓣，不是缺陷。**
 *
 * <p>所以那条断言**只用在矩形窗上**（[REST_LIMIT]），窗下的一律换成
 * **与 CPU 参考（同一套窗系数、同一个缩放）逐 bin 比对**。
 *
 * <h3>二、"一致性判据"单独用就是橡皮图章</h3>
 *
 * <p>Task 3 的探针里有一条"跨接缝与 ringStart=0 的谱一致"。实测：把 `bindBufferBase`
 * 的 index/buffer 互换后它**照样 PASS**——因为输出**全是 0**，而 `0 == 0` 恒真。
 * **一致性判据在"整条路径都坏了但坏得一致"时恒真。**
 *
 * <p>所以第五节那条一致性断言**必须与 CPU 参考并行**才有意义，本文件里它
 * 单独标了"弱断言"，真正把关的是同一节里与 CPU 参考比对的**那三条**——
 * 其中一条专门是"非矩形窗 + 跨接缝"的组合（见下）。
 *
 * <h3>三、`setUniform` 对不存在的 uniform 名字是静默无效</h3>
 *
 * <p>`glGetUniformLocation` 返回 −1，`glUniform1i(-1, …)` **不报错**。
 * 于是"峰值在正确的 bin"这类断言**抓不住 uniform 名字写错**。
 *
 * <p>本次靠"与 CPU 参考逐 bin 比对"兜住（任何 uniform 失效都会让输出全 0
 * 或退回矩形窗结果），另加一条**直接打在名字上**的断言（见 `probeUniforms`）：
 * 把 `FftKernel` 的着色器源码单独编译一遍，断言 `execute()` 用到的 5 个名字
 * **每一个都能解析出合法的 location**，且**活跃 uniform 恰好就是这 5 个**。
 * 这条是本文件对那个已知盲区的正面封堵。
 *
 * <h3>四、必须把 `MAX_N` 那个长度也测上</h3>
 *
 * <p>边界是这类缺陷唯一会现形的地方。实测：`n=256` 时把 `MAX_N` 与共享内存尺寸的
 * 绑定改坏了**看不出来**（数组够大、越界不发生），而 **`n=4096`（上限）时同样的破坏
 * 立刻显形**。所以第六节跑 `MIN_N` 与 `MAX_N` **两个端点**。
 *
 * <h2>判据（规格 §7.2 逐条）</h2>
 *
 * <ol>
 *   <li>已知频率的纯正弦 → 峰值落在正确的 bin（三个频率 100/400/900，都必须 &lt; N/2）；
 *       **不去找 `k = N-100` 那个共轭峰**——本核输出只有 `N/2+1` 个 bin，
 *       实余弦的共轭峰在半谱里被折叠掉了（旧校验器测的是 `N=64` 的完整输出，
 *       那里 `k=7` 与 `k=57` 都在，**那是另一回事**）。</li>
 *   <li>与 CPU 参考逐 bin 比对（最大相对误差）。CPU 侧是自己写的朴素 DFT，
 *       **不从别处抄黄金模型**（参考项目正是那样掩盖了一个窗函数 bug）。</li>
 *   <li>单位幅度余弦的峰值读回 1.0（容差内）——归一化与窗补偿一起钉住。</li>
 *   <li>换窗不改变幅度读数：同一个正弦用**四种窗**各跑一次，峰值在容差内一致。</li>
 *   <li>着色器编译成功（缺陷①的教训：别让失败晚到很久才暴露）。</li>
 *   <li>常输入 → 峰只在 bin 0。</li>
 *   <li>场景会变：输入频率中途改变 → 峰值跟着移动；幅度中途改变 → 峰值高度跟着变。
 *       （`PickVerifier` 出过真实盲区：**24 条全绿却漏掉一个真缺陷，因为场景每帧完全相同**。）</li>
 *   <li>跨环绕取样本（规格 R3）：`ringStart` 落在环的接缝附近、使 N 个样本跨过接缝，
 *       断言频谱仍然正确——**与 CPU 参考比对**，不是只看一致性。</li>
 *   <li>`MIN_N` 与 `MAX_N` 两个端点都跑。</li>
 *   <li>非法参数明确抛 `IllegalArgumentException`。</li>
 *   <li>★ <strong>矩形窗下的绝对下限</strong>：所有非峰 bin ≤ 1e-6 倍峰高。
 *       它不是"逐 bin 比对"的复述——那条的分母是**峰值**，于是真值远小于门槛的 bin
 *       （末几个 bin 的真值是 1e-17 量级）**完全不设防**：把 Nyquist 那一处写入整个删掉，
 *       没有这一条时全绿。夹具另把**输出缓冲预填成毒值**，没写到的 bin 因此必然现形。</li>
 *   <li>★ <strong>「窗」×「跨接缝」的组合</strong>：非矩形窗 + 非零 `ringStart`。
 *       单独跑"只用窗"或"只跨接缝"都盖不住 `windowAt(i)` → `windowAt(src)`
 *       （窗按**环槽位**取）——矩形窗的 `windowAt` 恒等于 1.0，窗的下标取哪个都对。</li>
 * </ol>
 *
 * <h2>★ 条目数是这个校验器的对外契约</h2>
 *
 * <p>一共 **50 条**断言。被测对象构造失败时，各节的断言**照记**，只是判为失败、
 * 细节写"无法评估……（原因见零节）"——条目数因此在任何失败模式下都是同一个数。
 * 早退时干脆不记的话，日志里只剩几条，读的人会以为"它一共就这么几条"。
 *
 * <p>同样地，**恒真的断言一律不留**：曾经有两条（"四种窗的峰值读数两两一致"、
 * "峰值随频率移动"）被写过又删掉——它们各自被别的断言算术蕴含，永远不会独立失败。
 * 一条不会独立失败的断言只是把分母撑大，让人以为覆盖到了什么。
 *
 * <h2>顺带测性能（规格 R6）</h2>
 *
 * <p>第八节量一次 `n = MAX_N` 的 `execute()` 耗时（`glFinish` 前后夹住，
 * 取 5 次里最快的一次）。**超过 [BUDGET_MS] 就报失败**——那说明"单 workgroup"这条路线
 * 要重新考虑，属于设计级的结论，不该藏在一行诊断里。
 *
 * <h2>运行</h2>
 *
 * ```
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.FftVerifierKt"
 * ```
 *
 * <p>（`-D` 必须加引号；本校验器**不能**用 `mvn exec:java` 跑，理由见 CLAUDE.md。）
 * 退出码 0 = 全部通过，1 = 有断言失败（失败详情打印在 stdout）。它会自己关窗退出。
 * **它不画任何东西**——要的是 GL 上下文，不是像素。
 *
 * <h2>退出码为什么写成 try/catch + finally</h2>
 *
 * <p>本仓库的几个既有校验器都栽在同一个坑里：断言抛出的异常逃到 GL 线程上，
 * 线程死掉、汇总行与 `exitProcess` 都走不到，JVM 因为"最后一个非守护线程结束"而
 * **以 0 退出**——一个已经打印了 FAIL 的校验器报出退出码 0。
 * 所以这里：校验体整体包在 try/catch 里（异常 → 退出码 1），
 * 建出来的 GL 资源在 `finally` 里释放，而 `exitProcess` 在**这两者之外**调用。
 */

private const val SCENE_W = 400.0
private const val SCENE_H = 300.0

/** 主判据的变换长度。2 的幂，落在 `[MIN_N, MAX_N]` 里。 */
private const val N_MAIN = 2048

/**
 * 三个测试频率（低 / 中 / 高）。
 *
 * <p>**三个都必须 &lt; N/2**，否则落在半谱的折叠区之外。
 * 多个频率都错位比"某一个错"更能说明是系统性的算法缺陷。
 * k0 也不取 0 或 N/2 这类对称位置——位反转置换对它们毫无影响。
 */
private val K0_LIST = intArrayOf(100, 400, 900)

/** 换窗那一节用的频率。 */
private const val WIN_K0 = 400

/**
 * 环容量。**必须 ≥ N 且是 2 的幂**。
 *
 * <p>取 4096 是为了让"跨接缝"那一条能真正跨过去：`ringStart = 4096 - 137` 时，
 * 窗口里有 137 个样本在环尾、其余 1911 个绕回环首。
 */
private const val RING_CAP = 4096

/** 跨接缝测试里落在环尾的样本数（见 [RING_CAP]）。 */
private const val RING_CROSS_OFFSET = 137

/**
 * 跨接缝的第二个配置：**非矩形窗 + 跨缝 1400 个样本**。
 *
 * <p><strong>为什么非要有这一条组合</strong>：矩形窗的 `windowAt` 恒等于 1.0，
 * **窗的下标取哪个都对**——所以"只跨接缝"与"只用窗"两节各自跑，都盖不住
 * `windowAt(i)` → `windowAt(src)`（窗按**环槽位**取）这个错。
 * 实测（复核时）：这个错在当时的出厂配置下 **47 条断言全绿**——而那是加本节这两条之前的事。
 *
 * <p>跨缝样本数取 1400（而不是 137）：误差随绕回的样本数增长，
 * 137 个时只有 4.2e-5，1400 个时是 6.9e-4——比 [RING_REF_TOL] 高一个半数量级。
 */
private const val RING_CROSS_OFFSET_BIG = 1400

/**
 * 第五节"非零起点但不跨接缝"那一跑用的起点。
 *
 * <p>刻意**不用 0**：起点 0 与第一节 k0=400 是完全相同的测量（同输入、同窗、同起点），
 * 两处打印的是同一个数——那不叫独立证据。1024 与它没有任何重合。
 */
private const val RING_PLAIN_START = 1024

/**
 * 矩形窗下"其余 bin 都远小于峰"的门槛：相对峰高。
 *
 * <p>**只用于矩形窗。** 窗下的旁瓣能到 0.5（见文件头警告一），
 * 那个量级的判据在窗下是错的——窗下改用与 CPU 参考逐 bin 比对。
 *
 * <p>它比 [ABS_LIMIT] 松一个数量级，所以它管的是**形状**（峰是唯一的），
 * 而 [ABS_LIMIT] 管的是**下限**（连 1e-6 量级的杂散都不许有）。
 */
private const val REST_LIMIT = 1e-3

/**
 * ★ 矩形窗下的**绝对下限**：所有非峰 bin 都不得超过峰高的这个比例。
 *
 * <p><strong>为什么"逐 bin 比对"不能替代它</strong>：那一比的门槛是**相对峰值**的
 * [CPU_REF_TOL]，于是**任何真值小于它的 bin 完全不设防**——实测末几个 bin 的真值只有
 * 1e-17 量级，`|0 - 1e-17|` 永远小于门槛。**把 Nyquist 那一处写入整个删掉**
 * （`k <= n/2` 改成 `k < n/2`），当时那 47 条断言可以**全绿**。
 *
 * <p>实测矩形窗的非峰最大是 **1.03e-07**（相对峰高），所以 1e-6 留了约 10 倍余量。
 * **只在矩形窗下用**：窗下的非峰处是旁瓣（实测 0.5 / 0.43 / 0.68），加绝对判据会误报。
 *
 * <p>它要真能抓住"核没写到的 bin"，还得靠夹具把**输出缓冲也预填成** [POISON]
 * ——否则那个 bin 留着上一次的残留，而"残留"看起来完全正常。
 */
private const val ABS_LIMIT = 1e-6

/**
 * 单位幅度余弦在**归一化之后**的谱峰：`1.0`（见"峰值读回 1.0"那条）。
 *
 * <p>绝对下限那条断言拿它当"峰高"，**不用观测到的最大值**——实测踩过：
 * 核没写到的 bin 留着毒值 1e30，它成了观测最大值，于是"非峰 ≤ 1e-6 倍峰高"
 * 变成 `1.0 ≤ 1e24`，**恒真**。（那个变异另有别的断言抓住，但这一条不能是橡皮图章。）
 */
private const val EXPECTED_PEAK = 1.0

/**
 * 第五节与 CPU 参考比对用的门槛，**比 [CPU_REF_TOL] 紧一个数量级**。
 *
 * <p>因为它要抓的错比"整体缩放错"小得多：把窗**按环槽位**取而不是按样本下标取
 * （`windowAt(i)` → `windowAt(src)`），跨接缝时误差只有 **4.2e-5**（跨缝 137 个样本）
 * 到 **6.9e-4**（跨缝 1400 个）——前者落在 [CPU_REF_TOL] 之内，**用那条门槛抓不住**。
 *
 * <p>实测这一节的正常误差是 1.16e-07（矩形窗）与 2e-07 量级（带窗），所以 1e-5
 * 留了约两个数量级余量，同时比那个 4.2e-5 还紧。
 */
private const val RING_REF_TOL = 1e-5

/**
 * 被测对象构造失败时，本节每一条断言**仍然照记**，只是判为失败、细节写这一句。
 *
 * <p>条目数因此**不随失败模式漂移**——条目数是这个校验器的对外契约：
 * 早退时若干脆不记，日志里就只剩几条断言，读的人会以为"它一共就这么几条"。
 */
private const val BLOCKED = "无法评估：FftKernel 没能构造出来（原因见零节的那条失败）"

/**
 * 幅度读数（"单位幅度余弦读回 1.0"、"换窗不改变读数"）的相对容差。
 *
 * <p>实测留了四个数量级的余量：一个漏了窗补偿的实现会让 Hann 的读数变成 2.0、
 * BH 变成 2.8，一个 `2.0/n` 写成 `1.0/n` 的实现会让所有窗都减半——都在 0.5 以上。
 */
private const val AMP_TOL = 1e-3

/**
 * 与 CPU 参考逐 bin 比对的门槛：**相对峰值**的最大误差。
 *
 * <p>float32 累加 log2(N) 级蝶形的理论误差在 1e-6 量级，实测更小；
 * 而一个"峰值位置对、数值错"的实现（整体缩放、漏归一化、某个 uniform 没生效）
 * 的误差是 O(1) 量级——两者之间隔着好几个数量级，门槛随便落在哪里都分得开。
 */
private const val CPU_REF_TOL = 1e-4

/** 常输入的取值。DC 半谱的读数是它的 2 倍（`2/N` 归一化对 DC 就是 2 倍）。 */
private const val CONST_INPUT = 3.0

/** `n = MAX_N` 的一次 `execute()` 的时间预算（毫秒）。超了就报失败，见文件头。 */
private const val BUDGET_MS = 16.0

/**
 * 环里**不被本次变换读到**的槽位填的毒值。
 *
 * <p>取模写错（用错环容量、忘掉 `& (cap-1)`）时它会直接进到蝶形里，
 * 于是与 CPU 参考的比对**必然崩掉**——不填毒值的话，那些槽位里是上一次的残留，
 * "取模错了"可能悄悄给出一个仍然像模像样的谱。
 */
private const val POISON = 1.0e30f

/**
 * `FftKernel.execute()` 会去设置的那 5 个 uniform 名字。
 *
 * <p>**这一份是手写的，与生产代码各持一份**——这正是它的用处：着色器里若把某个名字
 * 改了而 Java 侧没跟着改，`glGetUniformLocation` 会返回 −1 而 `glUniform1i` **不报错**，
 * 值静默不生效。那条路径没有任何画面症状，只有"名字解析不出来"这条断言拦得住。
 */
private val UNIFORM_NAMES = listOf("u_N", "u_RingCapacity", "u_RingStart", "u_WindowKind", "u_Scale")

/**
 * 校验器的启动入口。
 *
 * <p>函数名不叫 `main`：同包的 [PipelineExample] 已有顶层 `main()`，
 * 两个同名顶层函数会让 `import com.bingbaihanji.jfgl.example.main` 报"重载歧义"。
 * 用 `@JvmName("main")` 把 JVM 方法名钉回 `main`，上面文档里的命令行因此照常可用。
 * 另外四个校验器用的是同一个写法，本文件保持一致。
 */
@JvmName("main")
fun fftVerifyMain() {
    Application.launch(FftVerifierApp::class.java)
}

class FftVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null

    /** 只跑一次。`onRender` 每帧都会被调用，而这是一次性的校验器。 */
    private var finished = false

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
        // 只在 onRender 里跑：GL 上下文只在 GLCanvas 的这几个回调里是当前的
        // （见 CLAUDE.md 的「线程模型」）。本校验器不画任何东西——它要的是 GL 上下文。
        bridge.onRender { verifyOnce() }
        transfer = bridge

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "JFGL FFT Verifier"
        stage.scene = Scene(mainView.createMainView(), SCENE_W, SCENE_H)
        stage.show()
    }

    /**
     * `onRender` 回调的入口：把校验体包进 try/catch，`exitProcess` 放在它之外。
     *
     * <p>理由见文件头的「退出码为什么写成 try/catch + finally」：校验体在 GL 线程上跑，
     * 一旦抛出去，线程死掉、汇总行与 `exitProcess` 都走不到，JVM 以 0 退出——
     * **一个已经打印了 FAIL 的校验器报出退出码 0**。而"绿"是所有信号里唯一
     * 不会被人再看一眼的那个。
     */
    private fun verifyOnce() {
        if (finished) return
        finished = true
        val code = try {
            verifyAll()
        } catch (t: Throwable) {
            println("\n=== 校验过程抛出异常，判为失败 ===")
            t.printStackTrace()
            1
        }
        Platform.exit()
        exitProcess(code)
    }

    /**
     * 校验体。返回退出码：0 = 全部通过，1 = 有断言失败。
     *
     * <p>**不在这里调用 `exitProcess`**：它的调用点必须在 try/catch 之外（见 [verifyOnce]）。
     * 建出来的 GL 资源在 `finally` 里统一释放——异常路径上也不泄漏。
     */
    private fun verifyAll(): Int {
        val failures = ArrayList<String>()

        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        /** 只打印、不计入失败的观察行。诊断用的量不该混进 pass/fail 的账里。 */
        fun info(label: String, detail: String) = println("  [诊断] $label — $detail")

        val gl = LwjglGLAbstraction()
        val harnesses = ArrayList<FftHarness>()
        fun harness(n: Int): FftHarness = FftHarness(gl, n, RING_CAP).also { harnesses.add(it) }

        println("=== JFGL FFT 校验器（gpu/FftKernel.java）===")
        println("主长度 N = $N_MAIN（半谱 $N_MAIN/2+1 个 bin），频率 k0 = ${K0_LIST.joinToString()}，环容量 $RING_CAP")
        println("GL_VERSION  = ${glGetString(GL_VERSION)}")
        println("GL_RENDERER = ${glGetString(GL_RENDERER)}")

        // 清掉此前积压的错误：后面每个阶段都要能区分"这次调用出错了"与"之前就错了"。
        drainGlError()

        try {
            // ==================================================================
            // 零、被测对象能不能跑起来
            // ==================================================================
            //
            // 这必须是**第一位**的结论：一个连着色器都编译不过的实现，它的"输出"
            // 根本不存在，谈"输出对不对"没有意义。（旧 GPUFFT 正是死在这一条上。）
            println("\n-- 零、被测对象能不能跑起来：着色器编译 + uniform 名字解析 --")

            var buildBlocker: Throwable? = null
            val main = try {
                harness(N_MAIN)
            } catch (t: Throwable) {
                buildBlocker = t
                null
            }
            report(
                "★ FftKernel 的着色器能编译并链接（N=$N_MAIN）",
                buildBlocker == null,
                buildBlocker?.message?.trim()?.take(500)
                    ?: "编译并链接通过——被测对象是可运行状态"
            )

            /** 被测对象构造失败时各节的统一细节文案（把这里抓到的原因带过去）。 */
            fun blocked(): String =
                buildBlocker?.let { "$BLOCKED —— ${it.message?.trim()?.take(200)}" } ?: BLOCKED

            verifyUniformNames(::report, ::info)

            // ==================================================================
            // 一、核心：纯正弦的峰值必须落在 k0 上（矩形窗）
            // ==================================================================
            println("\n-- 一、核心：纯正弦的峰值落在 k0 上，且与 CPU 参考逐 bin 相符（矩形窗）--")

            // 夹具自检（不经过任何生产代码）：输出缓冲能预填成毒值。
            // 没有它，"核没写到的 bin 会现形"就是一句没有依据的话——那个 bin 会留着
            // 上一次的残留，而残留看起来完全正常。
            val outputPoisoned = main?.let {
                it.poisonOutput()
                abs(it.readOutputFloat(0) - POISON) <= POISON * 1e-6
            } ?: false
            report(
                "夹具自检：输出缓冲能被预填成毒值（使「核没写到的 bin」必然留下毒值；这条不经过任何生产代码）",
                outputPoisoned,
                main?.let { "预填后读到 ${"%.6e".format(it.readOutputFloat(0))}，期望 $POISON" } ?: blocked()
            )

            for (k0 in K0_LIST) {
                // 输入过一遍 Float：GPU 里存的就是 float32，CPU 参考必须吃同一批数，
                // 否则比出来的是"输入取整误差"，不是变换误差。
                val x = cosineInput(N_MAIN, k0)
                val r = main?.let {
                    Reading(
                        it.spectrum(x, 0, FftWindow.RECTANGULAR),
                        CpuReference(N_MAIN, FftWindow.RECTANGULAR).magnitude(x)
                    )
                }

                println("\n  --- k0 = $k0 ---")
                if (r != null) {
                    println("  幅值最大的 4 个 bin：${topBins(r.mag, 4)}")
                    println("  |X[$k0]| = ${"%.6f".format(r.mag[k0])}（期望 1.0）；" +
                            "非峰最大 ${"%.3e".format(r.rest)}；与 CPU 参考最大相对偏差 ${"%.3e".format(r.err)}")
                }

                // 位置：唯一能把"对的"与"错位的"分开的量。去掉了位反转的实现在这里必然倒下
                // （那正是旧 GPUFFT 的缺陷③）。
                report(
                    "k0=$k0：峰值落在 k = $k0",
                    r != null && r.peak == k0,
                    r?.let {
                        "实际峰值在 k = ${it.peak}（|X|=${"%.6f".format(it.peakMag)}）" +
                                "——峰跑到别的 bin 上就是错位：错位的频谱不报错、不崩，只是数据错。" +
                                "半谱只有 N/2+1 个 bin，实余弦的共轭峰被折叠掉了，不要去找 k = N-$k0"
                    } ?: blocked()
                )

                // 幅度：归一化 2/N 与"半谱把共轭峰折叠进来"这两件事一起钉住。
                //
                // ★ 这一条**不可替代**：CPU 参考与生产代码共用 `FftWindow.compensation`，
                // 所以"整体缩放错"在逐 bin 比对里**两边同错、看不见**（CPU 参考自己也会
                // 用同一个错的 u_Scale 去缩放）——只有"读回 1.0"钉得住绝对归一化。
                report(
                    "k0=$k0：峰值读回 1.0（半谱的 2/N 归一化）",
                    r != null && abs(r.peakMag - 1.0) <= AMP_TOL,
                    r?.let {
                        "|X[$k0]| = ${"%.6f".format(it.peakMag)}，期望 1.0 ± $AMP_TOL" +
                                "——写成 1.0/n 会得到 0.5，漏掉窗补偿会得到 2.0 以上"
                    } ?: blocked()
                )

                // 逐 bin：只断言峰值位置的话，一个整体缩放错的实现照样通过。
                report(
                    "k0=$k0：与 CPU 参考（朴素 O(N²) DFT）逐 bin 的最大相对误差 ≤ $CPU_REF_TOL",
                    r != null && r.err <= CPU_REF_TOL,
                    r?.let {
                        "相对峰值 ${"%.3e".format(it.err)}（CPU 参考吃的是与 GPU 逐位相同的 float32 输入，" +
                                "并且用同一套窗系数与同一个缩放）"
                    } ?: blocked()
                )

                // ★ 绝对下限。与上面那条**互补**：那条的分母是峰值，于是真值远小于
                // CPU_REF_TOL 的 bin 完全不设防（"0 与 1e-17 之差"永远合格）。
                //
                // 两处刻意的选择：
                //   · 分母锚在**期望的**峰高 [EXPECTED_PEAK]，不是观测最大值——否则一个
                //     留着毒值的 bin 会把分母抬到 1e30，这条判据自己变成 `1.0 ≤ 1e24` 恒真；
                //   · 另外单独数一遍毒值 bin，因为"核没写到它们"比"非峰偏大"更接近根因。
                report(
                    "k0=$k0：所有非峰 bin ≤ $ABS_LIMIT 倍峰高，且没有 bin 留着毒值（矩形窗的**绝对**下限）",
                    r != null && r.poisonCount == 0 && r.rest <= EXPECTED_PEAK * ABS_LIMIT,
                    r?.let {
                        if (it.poisonCount > 0) {
                            "有 ${it.poisonCount} 个 bin 的幅值在毒值量级——**核没有写到它们**，" +
                                    "输出缓冲的预填就是为了让这件事现形"
                        } else {
                            "非峰最大 ${"%.3e".format(it.rest)}，门槛 ${"%.1e".format(EXPECTED_PEAK * ABS_LIMIT)}" +
                                    "——正常实现这里是 1.03e-07 量级。上面那条逐 bin 比对看不见它：" +
                                    "它的分母是峰值，真值远小于门槛的 bin 永远合格"
                        }
                    } ?: blocked()
                )
            }

            // ==================================================================
            // 二、换窗不改变幅度读数
            // ==================================================================
            //
            // 没有这一条，窗增益补偿写错不会有任何症状：谱的形状是对的，只是整体矮一截。
            //
            // ⚠️ 这里**不能**用"非峰应当接近零"那种绝对阈值：窗把能量摊开，
            // 实测非峰最大到 0.5（相对峰高），而那是窗的主瓣，不是缺陷。
            // 所以这一节的把关判据是**与 CPU 参考（同一套窗系数、同一个缩放）逐 bin 比对**。
            println("\n-- 二、换窗不改变幅度读数（四种窗，k0=$WIN_K0）--")

            // 「换窗不改变读数」这条产品性质，由下面四条「峰值读回 1.0」共同承担——
            // 四个窗都读回 1.0，它们当然两两一致。
            //
            // 曾经另写过一条「四窗峰值读数两两一致」的断言，**它是算术恒真的**：
            // 四个值各被夹在 1 ± AMP_TOL 内，极差必 ≤ 2·AMP_TOL，而那条的阈值就是 2·AMP_TOL
            // ——它不可能独立失败（"改哪一行只让它倒"的答案是"没有"）。已删除。
            for (window in FftWindow.values()) {
                val x = cosineInput(N_MAIN, WIN_K0)
                val r = main?.let {
                    Reading(it.spectrum(x, 0, window), CpuReference(N_MAIN, window).magnitude(x))
                }

                if (r != null) {
                    println(
                        "  " + "[诊断] ${window.name}：峰值在 k=${r.peak}，|X|=${"%.6f".format(r.peakMag)}；" +
                                "非峰最大 ${"%.3e".format(r.rest)}（窗的主瓣/旁瓣，不是缺陷）；" +
                                "与 CPU 参考最大相对偏差 ${"%.3e".format(r.err)}"
                    )
                }

                report(
                    "${window.name} 窗：峰值仍在 k = $WIN_K0",
                    r != null && r.peak == WIN_K0,
                    r?.let { "实际 k = ${it.peak}——窗是对称的，加了窗峰位不该动" } ?: blocked()
                )
                report(
                    "${window.name} 窗：峰值读回 1.0（窗的相干增益补偿生效）",
                    r != null && abs(r.peakMag - 1.0) <= AMP_TOL,
                    r?.let {
                        "|X[$WIN_K0]| = ${"%.6f".format(it.peakMag)}，期望 1.0 ± $AMP_TOL" +
                                "——不补偿的话它会变成 1/相干增益（Hann 约 2.0、BH 约 2.8）"
                    } ?: blocked()
                )
                report(
                    "${window.name} 窗：与 CPU 参考（同一套窗系数与缩放）逐 bin 比对 ≤ $CPU_REF_TOL",
                    r != null && r.err <= CPU_REF_TOL,
                    r?.let {
                        "相对峰值 ${"%.3e".format(it.err)}——窗下的把关量是它，" +
                                "不是「非峰接近零」（那个阈值在窗下本来就不成立）"
                    } ?: blocked()
                )
            }

            // ==================================================================
            // 三、常输入 → 峰只在 bin 0
            // ==================================================================
            println("\n-- 三、常输入（全同一个值 $CONST_INPUT）→ 峰只在 bin 0 --")

            run {
                val x = DoubleArray(N_MAIN) { CONST_INPUT }
                val r = main?.let {
                    Reading(
                        it.spectrum(x, 0, FftWindow.RECTANGULAR),
                        CpuReference(N_MAIN, FftWindow.RECTANGULAR).magnitude(x)
                    )
                }
                val expected = 2.0 * CONST_INPUT

                if (r != null) {
                    println(
                        "  [诊断] DC bin ${"%.6f".format(r.mag[0])}；非峰最大 ${"%.3e".format(r.rest)}"
                    )
                }

                // 注意：这一条对"位反转缺失"是**免疫的**——常输入对任何置换都不变，
                // 所以它天生看不见那类缺陷。它管的是 DC 路径与"别处不该有能量"。
                report(
                    "常输入：唯一的峰在 bin 0（DC）",
                    r != null && r.peak == 0 && r.rest <= r.mag[0] * REST_LIMIT,
                    r?.let {
                        "实际峰值在 k = ${it.peak}（|X|=${"%.6f".format(it.mag[it.peak])}），" +
                                "非峰最大 ${"%.3e".format(it.rest)}" +
                                "——常输入的谱只有一个直流分量，别处有能量就是错的"
                    } ?: blocked()
                )
                report(
                    "常输入：|X[0]| = $expected（半谱的 2/N 归一化对 DC 就是 2 倍）且逐 bin 与 CPU 参考相符",
                    r != null && abs(r.mag[0] - expected) <= expected * AMP_TOL && r.err <= CPU_REF_TOL,
                    r?.let {
                        "|X[0]| = ${"%.6f".format(it.mag[0])}，期望 $expected；" +
                                "与 CPU 参考最大相对偏差 ${"%.3e".format(it.err)}"
                    } ?: blocked()
                )
            }

            // ==================================================================
            // 四、场景会变
            // ==================================================================
            //
            // PickVerifier 出过真实盲区：**24 条断言全绿，却漏掉一个真缺陷，
            // 因为场景每帧完全相同**。所以这里让**同一个 kernel 实例**连续跑一组
            // 频率/幅度/窗都不同的输入，断言峰值跟着动——跨帧的状态泄漏、
            // "只有第一次算对"这类缺陷只有在这种场景里才现形。
            println("\n-- 四、场景会变：同一个 kernel 实例，频率/幅度/窗中途改变 --")

            // 「场景会变」这条产品性质，由下面**四条 step 断言**共同承担：每一条都是
            // **不同的输入**（频率 100/400/900、幅度 1.0/0.25、窗中途换成 Hann），
            // 而 kernel 实例自始至终是同一个——一个"只有第一次算对"、或跨次泄漏状态的
            // 实现，第 2/3/4 条必然倒下。
            //
            // 曾经另写过一条汇总断言（"峰值随频率移动、高度随幅度变化"），它的每个子句
            // 都是这四条的子集（peak == k0 与 |peakMag - amp| 已经逐条断言过），
            // **永远不会独立失败**。已删除。
            val steps = listOf(
                Triple(100, 1.0, FftWindow.RECTANGULAR),
                Triple(400, 1.0, FftWindow.RECTANGULAR),
                Triple(400, 0.25, FftWindow.RECTANGULAR),
                Triple(900, 1.0, FftWindow.HANN),
            )
            for ((i, s) in steps.withIndex()) {
                val (k0, amp, window) = s
                val x = cosineInput(N_MAIN, k0, amp)
                val r = main?.let {
                    Reading(it.spectrum(x, 0, window), CpuReference(N_MAIN, window).magnitude(x))
                }
                report(
                    "场景会变·第 ${i + 1} 次：k0=$k0 幅度 $amp ${window.name} 窗 → 峰值落在正确的位置与高度",
                    r != null && r.peak == k0 && abs(r.peakMag - amp) <= 2.0 * AMP_TOL && r.err <= CPU_REF_TOL,
                    r?.let {
                        "期望峰在 k=$k0、高度 $amp；实际 k=${it.peak}、高度 ${"%.6f".format(it.peakMag)}，" +
                                "与 CPU 参考最大相对偏差 ${"%.3e".format(it.err)}"
                    } ?: blocked()
                )
            }

            // ==================================================================
            // 五、跨环绕取样本（规格 R3）
            // ==================================================================
            //
            // 时域样本存在 ② 的环形缓冲里，N 个样本可能跨过环的接缝，所以取数要
            // `& (cap-1)` 取模。**这与 ② 的"槽位 0 镜像"是同一类问题**——那里没处理，
            // 产出过一段"掉到 0 的假信号"。
            //
            // ⚠️ 这一节里"两个起点彼此一致"那条是**弱断言**：整条路径都坏了
            // 但坏得一致时它恒真（实测：把 bindBufferBase 的 index/buffer 互换，
            // 输出全是 0，而 `0 == 0` 照样 PASS）。真正把关的是**与 CPU 参考比对**那三条。
            //
            // ⚠️ 而"与 CPU 参考比对"这条形式**本身**也有个洞：矩形窗的 `windowAt` 恒等于
            // 1.0，**窗的下标取哪个都对**。所以"只跨接缝"与"只用窗"两节各自跑，
            // 都盖不住 `windowAt(i)` → `windowAt(src)`（窗按环槽位取）——实测那个错
            // 在当时的出厂配置下 47 条断言全绿。于是这里必须有**窗 × 跨接缝**的组合。
            println("\n-- 五、跨环绕取样本（规格 R3）：起点落在接缝附近，且**窗 × 跨接缝**组合覆盖 --")

            val k0 = 400
            val x = cosineInput(N_MAIN, k0)
            val startCross = RING_CAP - RING_CROSS_OFFSET
            val startCrossBig = RING_CAP - RING_CROSS_OFFSET_BIG
            val startPlain = RING_PLAIN_START

            // 夹具自检（不经过任何生产代码）：环里没被读到的槽位确实是毒值。
            // 它给"取模写错会被暴露"那句担保——没有它，那句话就是没有依据的。
            //
            // 自检的槽位必须是**刚上传的这一份**（跨接缝 $startCross）不读的：
            // 它读 [0,1910] ∪ [3959,4095]，所以 [1911,3958] 是没人碰的——取 2096。
            //
            // （这条自检第一次跑就抓住了我自己写错的一次：原先取的槽位落在 [0,1910] 里，
            //   读回来是信号值 -0.989 而不是毒值。夹具自检的价值就在这里。）
            main?.uploadRing(x, startCross)
            val poisonSlot = 2096
            val poisonValue = main?.readInputFloat(poisonSlot) ?: Double.NaN
            report(
                "夹具自检：环里未被读到的槽位（$poisonSlot）确实填了毒值（这条不经过任何生产代码）",
                main != null && abs(poisonValue - POISON) <= POISON * 1e-6,
                if (main == null) blocked() else "读到 ${"%.6e".format(poisonValue)}，期望 $POISON"
            )

            val rCrossRect = main?.let {
                Reading(
                    it.spectrum(x, startCross, FftWindow.RECTANGULAR),
                    CpuReference(N_MAIN, FftWindow.RECTANGULAR).magnitude(x)
                )
            }
            val rCrossWin = main?.let {
                Reading(
                    it.spectrum(x, startCrossBig, FftWindow.BLACKMAN_HARRIS),
                    CpuReference(N_MAIN, FftWindow.BLACKMAN_HARRIS).magnitude(x)
                )
            }
            val rPlain = main?.let {
                Reading(
                    it.spectrum(x, startPlain, FftWindow.RECTANGULAR),
                    CpuReference(N_MAIN, FftWindow.RECTANGULAR).magnitude(x)
                )
            }

            report(
                "★ 跨接缝 · 矩形窗（ringStart=$startCross：$RING_CROSS_OFFSET 个样本绕回环首）：谱与 CPU 参考一致（≤ $RING_REF_TOL）",
                rCrossRect != null && rCrossRect.err <= RING_REF_TOL,
                rCrossRect?.let {
                    "与 CPU 参考的最大相对误差 ${"%.3e".format(it.err)}——这一条才是把关的：" +
                            "只看「与别的起点一致」的话，「整条路径都坏但坏得一致」时它恒真"
                } ?: blocked()
            )
            report(
                "★★ 跨接缝 · **非矩形窗**（ringStart=$startCrossBig：$RING_CROSS_OFFSET_BIG 个样本绕回环首，BH 窗）：谱与 CPU 参考一致（≤ $RING_REF_TOL）",
                rCrossWin != null && rCrossWin.err <= RING_REF_TOL,
                rCrossWin?.let {
                    "与 CPU 参考的最大相对误差 ${"%.3e".format(it.err)}" +
                            "——**矩形窗的 windowAt 恒等于 1.0，窗的下标取哪个都对**，所以「窗」与「跨接缝」" +
                            "必须在这一条里组合起来才守得住：把 `windowAt(i)` 写成 `windowAt(src)`" +
                            "（窗按环槽位取）在此处是 6.9e-4 量级的错，其余各条一条也看不见"
                } ?: blocked()
            )
            report(
                "ringStart=$startPlain（非零、不跨接缝）的谱与 CPU 参考一致（≤ $RING_REF_TOL）",
                rPlain != null && rPlain.err <= RING_REF_TOL,
                rPlain?.let {
                    "与 CPU 参考的最大相对误差 ${"%.3e".format(it.err)}" +
                            "——起点刻意不用 0：那时它与第一节 k0=400 是同一次测量的重放"
                } ?: blocked()
            )
            report(
                "跨接缝与 ringStart=$startPlain 的谱彼此一致（弱断言，只有与 CPU 参考并行才有意义）",
                rCrossRect != null && rPlain != null && maxAbsDiff(rCrossRect.mag, rPlain.mag) <= 1e-6,
                if (rCrossRect == null || rPlain == null) blocked()
                else "两者最大绝对差 ${"%.3e".format(maxAbsDiff(rCrossRect.mag, rPlain.mag))}" +
                        "——**单看这条抓不住「取模写错」**（输出全 0 时也成立），" +
                        "它只防「两种起始位置给出两个不同的谱」"
            )

            // ==================================================================
            // 六、端点长度：MIN_N 与 MAX_N
            // ==================================================================
            //
            // 边界是共享内存那类缺陷唯一会现形的地方。实测：n=256 时把 MAX_N 与共享内存
            // 尺寸的绑定改坏了看不出来（数组够大、越界不发生），而 n=4096（上限）时
            // 同样的破坏立刻显形。所以两个端点都要跑，而且都要与 CPU 参考比对。
            println(
                "\n-- 六、端点长度：MIN_N=${FftKernel.MIN_N} 与 MAX_N=${FftKernel.MAX_N}（都要与 CPU 参考比对）--"
            )

            for (n in intArrayOf(FftKernel.MIN_N, FftKernel.MAX_N)) {
                val k0 = n / 8
                val endpoint = if (n == FftKernel.MAX_N) "上限" else "下限"
                var h: FftHarness? = null
                var buildError: Throwable? = null
                try {
                    h = harness(n)
                } catch (t: Throwable) {
                    buildError = t
                }
                for (window in listOf(FftWindow.RECTANGULAR, FftWindow.BLACKMAN_HARRIS)) {
                    val x = cosineInput(n, k0)
                    val r = h?.let {
                        Reading(it.spectrum(x, 0, window), CpuReference(n, window).magnitude(x))
                    }
                    report(
                        "n=$n（$endpoint）${window.name} 窗：峰在 k=$k0 且与 CPU 参考逐 bin 一致",
                        r != null && r.peak == k0 && abs(r.peakMag - 1.0) <= AMP_TOL && r.err <= CPU_REF_TOL,
                        r?.let {
                            "峰值在 k=${it.peak}（期望 $k0）、|X|=${"%.6f".format(it.peakMag)}（期望 1.0）、" +
                                    "与 CPU 参考最大相对偏差 ${"%.3e".format(it.err)}"
                        } ?: "$BLOCKED —— n=$n 的核没能构造出来：${buildError?.message?.trim()?.take(200)}"
                    )
                }
            }

            // ==================================================================
            // 七、非法参数必须明确抛
            // ==================================================================
            println("\n-- 七、非法参数必须明确抛 IllegalArgumentException（不许静默）--")

            fun expectThrows(label: String, n: Int) {
                val t = try {
                    FftKernel(gl, n, RING_CAP).dispose()
                    null
                } catch (e: Throwable) {
                    e
                }
                report(
                    label,
                    t is IllegalArgumentException,
                    t?.let { "${it::class.simpleName}：${it.message}" }
                        ?: "构造成功了——非法长度被静默接受，后面会用错的长度去 dispatch"
                )
            }

            expectThrows("n=255（不是 2 的幂）抛 IllegalArgumentException", 255)
            expectThrows("n=5120（超过 MAX_N=${FftKernel.MAX_N}）抛 IllegalArgumentException", 5120)
            expectThrows("n=300（不是 2 的幂、且落在区间内）抛 IllegalArgumentException", 300)

            run {
                val t = try {
                    FftKernel(gl, N_MAIN, 1000).dispose()
                    null
                } catch (e: Throwable) {
                    e
                }
                report(
                    "环容量 1000（不是 2 的幂）抛 IllegalArgumentException",
                    t is IllegalArgumentException,
                    t?.let { "${it::class.simpleName}：${it.message}" }
                        ?: "构造成功了——`& (cap-1)` 会越过 SSBO 末尾读，未定义行为（两种都是静默的）"
                )
            }

            // ==================================================================
            // 八、性能（规格 R6）
            // ==================================================================
            println("\n-- 八、性能（规格 R6）：n=${FftKernel.MAX_N} 的一次 execute() 耗时 --")

            // 构造失败（着色器编译不过）时**报一条失败就收**，别让异常逃出去：
            // 逃出去的话汇总行打不出来，人只看得到一句 "校验过程抛出异常" 加栈——
            // 而"着色器编译不过"这个原因在零节已经报过了，这里只该复述一句。
            // 注意这一节**无论成败都只记一条**（条目数不随失败模式漂移）。
            val perf = try {
                harness(FftKernel.MAX_N)
            } catch (t: Throwable) {
                report(
                    "n=${FftKernel.MAX_N} 的一次 execute() 在 ${BUDGET_MS} ms 预算内",
                    false,
                    "$BLOCKED —— ${t.message?.trim()?.take(200)}"
                )
                null
            }
            if (perf != null) run {
                val h = perf
                val x = cosineInput(FftKernel.MAX_N, FftKernel.MAX_N / 8)
                h.uploadRing(x, 0)

                // 预热：第一次 dispatch 往往带着驱动的 pipeline 建立开销，不该记到账上。
                h.kernel.execute(h.inputBuffer, 0, FftWindow.BLACKMAN_HARRIS)
                glFinish()

                var bestTotal = Double.MAX_VALUE
                var bestSubmit = Double.MAX_VALUE
                repeat(5) {
                    val t0 = System.nanoTime()
                    h.kernel.execute(h.inputBuffer, 0, FftWindow.BLACKMAN_HARRIS)
                    val t1 = System.nanoTime()
                    glFinish()
                    val t2 = System.nanoTime()
                    bestSubmit = minOf(bestSubmit, (t1 - t0) / 1e6)
                    bestTotal = minOf(bestTotal, (t2 - t0) / 1e6)
                }

                println("  n=${FftKernel.MAX_N} 的一次 execute() 耗时 ${"%.3f".format(bestTotal)} ms")
                info("提交耗时", "${"%.3f".format(bestSubmit)} ms（不含 glFinish 的等待，只是 CPU 侧的提交）")
                report(
                    "n=${FftKernel.MAX_N} 的一次 execute() 在 ${BUDGET_MS} ms 预算内",
                    bestTotal <= BUDGET_MS,
                    "最快一次 ${"%.3f".format(bestTotal)} ms（5 次里取最快，已预热）" +
                            "——超了说明「单 workgroup」这条路线要重新考虑，不是调参能解决的"
                )
            }

            return finish(failures)
        } finally {
            // GL 资源必须在 GL 线程上释放，而这里正是 GL 线程；异常路径上也走到。
            harnesses.asReversed().forEach { it.close() }
            drainGlError()
        }
    }

    /**
     * 把 `execute()` 会用到的 5 个 uniform 名字**直接打在名字上**地检查一遍。
     *
     * <p>存在的理由见文件头的警告三：`glGetUniformLocation` 对写错的名字返回 −1，
     * 而 `glUniform1i(-1, …)` **不报错**——值静默不生效。那条路径没有任何画面症状，
     * "峰值在正确的 bin"这类断言也抓不住它。这里把着色器源码单独编译一遍，然后：
     *
     * <ol>
     *   <li>断言 5 个名字**每一个**都能解析出 ≥ 0 的 location
     *       （着色器里改了名而 Java 侧没跟着改，这里立刻红）；</li>
     *   <li>断言**活跃 uniform 恰好就是这 5 个**
     *       （着色器里多声明了一个而 `execute()` 从没设过 → 它是 0，输出悄悄错；
     *       反之少一个也在这里现形。GLSL 编译器会把"声明了却没用到"的 uniform 优化掉，
     *       所以这条同时也在守"某个 uniform 其实没参与计算"）。</li>
     * </ol>
     *
     * <p>取源码用**反射**而不是在这里手抄一份：手抄的那份会随上游改动静默失真。
     * 取不到就报一条失败，而不是抛出去把 GL 线程打死。
     *
     * <p><strong>四条断言无论成功失败都照记</strong>：早退时不记的话，日志里就只剩别的节的条目，
     * 读的人会以为"这个校验器一共就这么几条"。条目数是对外契约，不随失败模式漂移。
     */
    private fun verifyUniformNames(
        report: (String, Boolean, String) -> Unit,
        info: (String, String) -> Unit,
    ) {
        val source = readShaderSource()
        report(
            "前提：能反射取到 FftKernel 的着色器源码（否则下面那条 uniform 检查无从谈起）",
            source != null,
            if (source == null) "取不到（方法名或可见性变了）" else "${source.length} 字符"
        )

        var probe: ShaderProbe? = null
        var probeError: Throwable? = null
        if (source != null) {
            try {
                probe = ShaderProbe(source)
            } catch (t: Throwable) {
                probeError = t
            }
        }
        val compiled = probe?.compiled == true
        val blockedDetail = "无法评估：${
            probeError?.message?.trim()?.take(200)
                ?: "着色器源码取不到或编译不过（见上面那条）"
        }"
        report(
            "前提：能把 FftKernel 的着色器源码单独编译一遍",
            compiled,
            if (probeError != null) probeError.message?.trim()?.take(200) ?: probeError.toString()
            else if (compiled) "编译并链接通过"
            else blockedDetail
        )

        try {
            val active = probe?.activeUniforms ?: emptyList()
            val unresolved = if (compiled) UNIFORM_NAMES.filter { probe!!.location(it) < 0 } else emptyList()
            report(
                "★ execute() 用到的 ${UNIFORM_NAMES.size} 个 uniform 名字在着色器里都能解析出 location",
                compiled && unresolved.isEmpty(),
                if (!compiled) blockedDetail
                else if (unresolved.isEmpty()) UNIFORM_NAMES.joinToString() + " —— 全部解析成功"
                else "解析不出来的是 ${unresolved.joinToString()}——" +
                        "glGetUniformLocation 返回 −1 而 glUniform1i 不报错，值会静默不生效；" +
                        "着色器里活跃的是 ${active.joinToString()}"
            )

            val extra = active.toSet() - UNIFORM_NAMES.toSet()
            val missing = UNIFORM_NAMES.toSet() - active.toSet()
            report(
                "★ 着色器里活跃的 uniform 恰好就是 execute() 设的那 ${UNIFORM_NAMES.size} 个",
                compiled && extra.isEmpty() && missing.isEmpty(),
                if (!compiled) blockedDetail
                else "活跃 ${active.size} 个：${active.joinToString()}" +
                        (if (extra.isEmpty() && missing.isEmpty()) ""
                        else "；多出来的 ${extra.joinToString()}（execute 从没设过 → 它是 0）；" +
                                "少掉的 ${missing.joinToString()}（被优化掉或改名了）")
            )
        } finally {
            probe?.close()
        }

        info(
            "说明", "这一条封的是「uniform 名字写错静默无效」这个盲区——" +
                    "与 CPU 参考的逐 bin 比对能兜住它，但兜住的是「结果不对」，" +
                    "这里直接指出「哪里不对」"
        )
    }

    /** 汇总并给出退出码。失败清单为空即 0。 */
    private fun finish(failures: List<String>): Int {
        println()
        if (failures.isEmpty()) {
            println("=== 全部通过：FftKernel 的输出是对的 ===")
        } else {
            println("=== 失败 ${failures.size} 项：")
            failures.forEach { println("    · $it") }
            println("===")
        }
        return if (failures.isEmpty()) 0 else 1
    }

    /**
     * 用反射取出 `FftKernel.shaderSource()` 生成的那份源码。
     *
     * <p>为什么不在这里手抄一份：手抄的那份会随上游改动**静默失真**
     * （上游改了名字、这里还在测老的那份，结论就成了假的）。
     *
     * <p>取不到时返回 null（方法被改名或挪走），由调用方报成一条失败断言——
     * 而不是抛出去把 GL 线程打死。
     */
    private fun readShaderSource(): String? = try {
        val method = FftKernel::class.java.getDeclaredMethod("shaderSource")
        method.isAccessible = true
        method.invoke(null) as? String
    } catch (t: Throwable) {
        null
    }

    /**
     * 把 GL 错误队列抽干，返回第一个非 0 的错误码（没有则返回 `GL_NO_ERROR`）。
     *
     * <p>必须**抽干**而不是只取一个：`glGetError` 一次只弹一个，
     * 只读一次会把后面那些错误留到下一次检查里去，让"这一次出错了"变成一句谎言。
     */
    private fun drainGlError(): Int {
        var first = GL_NO_ERROR
        var e = glGetError()
        while (e != GL_NO_ERROR) {
            if (first == GL_NO_ERROR) first = e
            e = glGetError()
        }
        return first
    }

    override fun stop() {
        transfer?.dispose()
    }
}

/**
 * 被测核 + 它的输入环形缓冲 + 读回。
 *
 * <p>每次 `spectrum(...)` 都**重新上传**整个环：输入变了而缓冲没变的话，
 * 断言看的是上一次的数据——这类"看起来全绿"的假象正是本仓库最警惕的。
 */
private class FftHarness(
    private val gl: LwjglGLAbstraction,
    val n: Int,
    val ringCap: Int,
) : AutoCloseable {

    val inputBuffer: Int = gl.createBuffer()

    init {
        gl.bindShaderStorageBuffer(inputBuffer)
        gl.allocateBufferStorage(ringCap.toLong() * Float.SIZE_BYTES)
        gl.bindShaderStorageBuffer(0)
    }

    val kernel = FftKernel(gl, n, ringCap)

    /**
     * 把 `x[0..n)` 依**环形语义**放进环里：`x[i]` 落在物理槽位 `(ringStart + i) & (cap-1)`。
     *
     * <p>其余槽位填 [POISON]。不填的话，那些槽位里是上一次的残留——
     * 取模写错时得到的谱可能**仍然像模像样**，而填了毒值它必然崩掉。
     */
    fun uploadRing(x: DoubleArray, ringStart: Int) {
        require(x.size == n) { "输入长度 ${x.size} 与变换长度 $n 不符" }
        val data = FloatArray(ringCap) { POISON }
        for (i in 0 until n) {
            data[(ringStart + i) and (ringCap - 1)] = x[i].toFloat()
        }
        val bytes = BufferUtils.createByteBuffer(ringCap * Float.SIZE_BYTES)
        bytes.asFloatBuffer().put(data)
        gl.bindShaderStorageBuffer(inputBuffer)
        gl.uploadBufferSubData(0L, bytes)
        gl.bindShaderStorageBuffer(0)
    }

    /** 上传 + 跑一次 + 读回半谱（`n/2+1` 个 bin 的幅值）。 */
    fun spectrum(x: DoubleArray, ringStart: Int, window: FftWindow): DoubleArray {
        uploadRing(x, ringStart)
        poisonOutput()
        kernel.execute(inputBuffer, ringStart, window)
        return readSpectrum()
    }

    /**
     * 把**输出**缓冲整块预填成 [POISON]。
     *
     * <p>这是输入侧那份毒值的对偶，理由完全一样：核**没写到的** bin 会留着上一次的
     * 残留，而残留看起来完全正常——尤其当残留是上一个频率的同一个 bin 时，
     * 它甚至还是"一个合理的谱值"。预填之后，没写到的 bin 一律是 1e30，
     * 任何一条判据都会当场炸掉。
     *
     * <p>实测的用处：把 Nyquist 那一处写入删掉（`k <= n/2` → `k < n/2`）时，
     * 没有它时全绿；有它时 [ABS_LIMIT] 那条立刻失败。
     */
    fun poisonOutput() {
        val bins = kernel.binCount()
        val filled = FloatArray(bins) { POISON }
        val bytes = BufferUtils.createByteBuffer(bins * Float.SIZE_BYTES)
        bytes.asFloatBuffer().put(filled)
        gl.bindShaderStorageBuffer(kernel.outputBufferId())
        gl.uploadBufferSubData(0L, bytes)
        gl.bindShaderStorageBuffer(0)
    }

    /** 读回输出缓冲一个槽位的原始值（用来确认预填真的生效了）。 */
    fun readOutputFloat(index: Int): Double {
        val fb = BufferUtils.createFloatBuffer(1)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, kernel.outputBufferId())
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, index.toLong() * Float.SIZE_BYTES, fb)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        return fb.get(0).toDouble()
    }

    /** 读回一个浮点槽位的原始值（用来确认毒值真的在缓冲里）。 */
    fun readInputFloat(index: Int): Double {
        val fb = BufferUtils.createFloatBuffer(1)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, inputBuffer)
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, index.toLong() * Float.SIZE_BYTES, fb)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        return fb.get(0).toDouble()
    }

    private fun readSpectrum(): DoubleArray {
        val bins = kernel.binCount()
        val fb = BufferUtils.createFloatBuffer(bins)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, kernel.outputBufferId())
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, fb)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        return DoubleArray(bins) { fb.get(it).toDouble() }
    }

    override fun close() {
        kernel.dispose()
        gl.deleteBuffer(inputBuffer)
    }
}

/**
 * 一份着色器源码的编译结果：只为了**问它 uniform 的名字**。
 *
 * <p>与 [FftKernel] 无关的第二份编译——`ComputeShader` 不暴露 `getUniformLocation`，
 * 而"名字到底解析得出来吗"这件事必须能单独问。
 */
private class ShaderProbe(source: String) : AutoCloseable {

    private val program: Int

    /** 编译+链接是否成功。失败时 [activeUniforms] 为空。 */
    val compiled: Boolean

    /** 着色器里活跃（未被编译器优化掉）的 uniform 名字。 */
    val activeUniforms: List<String>

    init {
        val shader = glCreateShader(GL_COMPUTE_SHADER)
        glShaderSource(shader, source)
        glCompileShader(shader)
        program = glCreateProgram()
        glAttachShader(program, shader)
        glLinkProgram(program)
        compiled = glGetShaderi(shader, GL_COMPILE_STATUS) != 0 &&
                glGetProgrami(program, GL_LINK_STATUS) != 0
        glDeleteShader(shader)
        activeUniforms = if (compiled) {
            // glGetActiveUniform(program, index, size, type) 的便捷重载自己分配名字缓冲。
            val size = BufferUtils.createIntBuffer(1)
            val type = BufferUtils.createIntBuffer(1)
            (0 until glGetProgrami(program, GL_ACTIVE_UNIFORMS))
                .map { glGetActiveUniform(program, it, size, type) }
        } else {
            emptyList()
        }
    }

    /** 该名字的 location；名字不存在时（GL 的约定）返回 −1。 */
    fun location(name: String): Int = if (compiled) glGetUniformLocation(program, name) else -1

    override fun close() {
        glDeleteProgram(program)
    }
}

/**
 * 一次测量的四个量，凑在一起是因为它们**全部来自同一次 GPU 运行**——
 * 分开各算一次会让"这条断言看的是上一次的输出"这种事有可乘之机。
 *
 * <p>调用方拿到的是**可空的** [Reading]（被测对象构造失败时为 null），
 * 但断言照记，只是判为失败、细节写 [BLOCKED]——见 [BLOCKED] 的说明。
 */
private class Reading(val mag: DoubleArray, val cpu: DoubleArray) {

    /** 幅值最大的 bin。 */
    val peak: Int = peakIndex(mag)

    val peakMag: Double = mag[peak]

    /** 除峰之外的最大幅值——"其余 bin 都很小"要看的正是它。 */
    val rest: Double = maxExcept(mag, peak)

    /** 与 CPU 参考逐 bin 的最大相对误差（分母是 CPU 参考的峰值）。 */
    val err: Double = maxRelError(mag, cpu, cpu.max())

    /**
     * 幅值落在毒值量级的 bin 个数：**核没写到的** bin 会留着夹具预填的 [POISON]。
     *
     * <p>单独数出来，是因为光看"非峰最大"会被毒值本身骗过去：它会成为观测最大值，
     * 于是以观测峰高为分母的判据全部变宽（见 [EXPECTED_PEAK]）。
     */
    val poisonCount: Int = mag.count { it >= POISON / 2.0 }
}

/**
 * 朴素 O(N²) DFT 参考：`|X[k]| = |Σ x[t]·w[t]·exp(-2πi·k·t/n)| · 2/n · 窗补偿`，只出半谱。
 *
 * <p>刻意**不写成 FFT**——这份参考的全部价值在于**它显然是对的**：
 * 它没有位反转、没有蝶形、没有 shared memory，只有一个二重循环。
 * 拿一个同样可能写错的 FFT 来当参考，就成了两个未知数互相印证。
 * （参考项目正是"黄金模型"与被测代码共用同一个公式，才掩盖了一个窗函数 bug。）
 *
 * <p>窗系数用生产代码的 [FftWindow.coefficient]——那是**同一套系数**，
 * 而且是单独单测守着的纯算术；比的是它**上面的**变换，不是窗本身。
 *
 * <p>三角函数只在构造时算 n 次（一张 `exp(-2πi·t/n)` 的表），内层循环只做索引，
 * 所以 n=4096 也只是几千万次乘加，不需要为它写一份 FFT。
 */
private class CpuReference(private val n: Int, window: FftWindow) {

    /** 窗系数，按样本下标。刻意不在二重循环里现算：BH 每个系数要 3 次 cos。 */
    private val w = DoubleArray(n) { window.coefficient(it, n) }

    private val cosTab = DoubleArray(n) { cos(-2.0 * PI * it / n) }
    private val sinTab = DoubleArray(n) { sin(-2.0 * PI * it / n) }

    /** 与 `FftKernel.scaleFor` 同一个式子的 double 版本：`2/n × 窗补偿`。 */
    private val scale = 2.0 / n * window.compensation(n)

    /** 幅值谱，长度 `n/2+1`（含 DC 与 Nyquist），与核的输出逐 bin 对应。 */
    fun magnitude(x: DoubleArray): DoubleArray {
        require(x.size == n) { "输入长度 ${x.size} 与变换长度 $n 不符" }
        val out = DoubleArray(n / 2 + 1)
        for (k in out.indices) {
            var sr = 0.0
            var si = 0.0
            // exp(-2πi·k·t/n) 的下标就是 (k·t) mod n，用累加代替乘法（k < n，加一次就够减回来）。
            var idx = 0
            for (t in 0 until n) {
                val v = x[t] * w[t]
                sr += v * cosTab[idx]
                si += v * sinTab[idx]
                idx += k
                if (idx >= n) idx -= n
            }
            out[k] = hypot(sr, si) * scale
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// 纯计算的小工具（与 GL 无关）
// ---------------------------------------------------------------------------

/** 幅值最大的 bin。并列时取下标最小的那个（确定性）。 */
private fun peakIndex(mag: DoubleArray): Int {
    var best = 0
    for (k in mag.indices) if (mag[k] > mag[best]) best = k
    return best
}

/** 除 `except` 之外的最大幅值——"其余 bin 都很小"那条断言要的量。 */
private fun maxExcept(mag: DoubleArray, except: Int): Double {
    var best = 0.0
    for (k in mag.indices) if (k != except && mag[k] > best) best = mag[k]
    return best
}

/**
 * 逐 bin 的最大**相对**误差，以 `scale`（通常是 CPU 参考的峰值）为分母。
 *
 * <p>用相对值而不是绝对值：整条谱的量纲由归一化因子决定，
 * 拿绝对值当判据会把"归一化差一倍"与"差几个 float32 位"混在一起。
 */
private fun maxRelError(a: DoubleArray, b: DoubleArray, scale: Double): Double {
    var worst = 0.0
    for (k in a.indices) {
        val d = abs(a[k] - b[k])
        if (d > worst) worst = d
    }
    return if (scale > 0.0) worst / scale else worst
}

/** 两条谱逐 bin 的最大绝对差（弱断言用）。 */
private fun maxAbsDiff(a: DoubleArray, b: DoubleArray): Double {
    var worst = 0.0
    for (k in a.indices) {
        val d = abs(a[k] - b[k])
        if (d > worst) worst = d
    }
    return worst
}

/** 幅值最大的几个 bin，打印用。 */
private fun topBins(mag: DoubleArray, count: Int): String =
    mag.indices.sortedByDescending { mag[it] }
        .take(count)
        .joinToString { "k=$it |X|=${"%.4f".format(mag[it])}" }

/**
 * 单位幅度余弦输入 `x[i] = amp·cos(2π·k0·i/n)`，**过一遍 `Float`**。
 *
 * <p>必须过那一遍：GPU 里存的是 float32，CPU 参考拿 double 去比对，
 * 比出来的是"输入本来就有的取整误差"，不是变换误差。
 */
private fun cosineInput(n: Int, k0: Int, amp: Double = 1.0): DoubleArray =
    DoubleArray(n) { (amp * cos(2.0 * PI * k0 * it / n)).toFloat().toDouble() }
