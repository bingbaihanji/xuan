package com.bingbaihanji.jfgl.example

import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.gpu.GPUFFT
import com.bingbaihanji.jfgl.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL43.*
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * `gpu/GPUFFT.java` 输出正确性的**一次性校验器**：喂进已知频率的纯正弦，
 * 看 `|X[k]|` 的峰值落在哪个 bin，并与一份朴素的 O(N²) DFT 逐 bin 比对。
 * 失败以非零码退出。
 *
 * <h2>它为什么存在</h2>
 *
 * <p>`GPUFFT` 是本仓库里唯一一处"上下文明明支持、代码明明可用、却没有任何人引用"的
 * 计算着色器（见 CLAUDE.md 的「前置事实」：实际上下文是 **4.6 compatibility**，
 * `#version 430` 的 compute、SSBO 全都实测可用）。**没有任何测试碰过它**，
 * 于是它到底算得对不对，一直是个悬着的问题。
 *
 * <p>读代码得到的怀疑是：着色器的蝶形是标准 **DIT**（`m = 1<<(stage+1)`、`half = 1<<stage`、
 * twiddle 是 `exp(-2πi·pos/m)`），而 **DIT 的前提是输入先做位反转置换**；
 * 文件里算出了位反转索引 `rev`（`GPUFFT.java:56-61`）却**再也没有引用它**，
 * `execute()` 的流程是 `uploadData → dispatchFFT → readbackData`，中间没有位反转。
 * 但「读代码看出来的」不算数——这套怀疑必须**实测**。本文件就是那支探针。
 *
 * <h2>实测：算法前面横着三道墙，一道比一道靠前</h2>
 *
 * <p><b>第一道：默认 shader 根本编译不过。</b>驱动报
 * `0(38) : error C0502: syntax error at token "half"`——第 38 行
 * `uint half = 1u << u_stage;` 里的 `half` 是 **GLSL 规范明文保留的关键字**
 * （reserved for future use，留给将来的半精度类型），拿它当变量名**必然**编译失败，
 * 与驱动是谁无关。`GPUFFT()` 的无参构造因此抛
 * `RuntimeException("Compute shader compilation failed: …")`：
 * **它从来没有成功运行过一次**，"输出对不对"这个问题在那之前就没了附着点。
 *
 * <p><b>第二道：`glUniform1i` 喂 `uniform uint`，uniform 保持 0 → 每个线程提前返回。</b>
 * 就算把保留字改掉，输出也**逐位等于输入**：`u_N`、`u_stage`、`u_direction`
 * 在 shader 里声明成 `uniform uint`，而 `dispatchFFT` 用 `glUniform1i` 赋值——
 * 类型不匹配，驱动报 `GL_INVALID_OPERATION`（0x502）**且命令不生效**，
 * 于是 `u_N` 恒为 0、`halfN = 0`、`if (gid >= halfN) return;` 让
 * **每一次调用都提前返回**，SSBO 一个字节都没被写过。
 * 这一条正是本仓库自己记过的教训（`uPickId` 必须是 int：见 CLAUDE.md 的「图表」一节，
 * "`glUniform1i` 对 uint uniform 报 `GL_INVALID_OPERATION` 且值保持 0"）——
 * 只是那一次踩在图表后端上，这一次踩在 FFT 上。
 *
 * <p><b>第三道：终于跑起来之后，算法确实缺输入位反转——怀疑成立。</b>实测（等价 shader#2，
 * N=64，三个频率各喂一个纯正弦）：
 *
 * <pre>
 *   k0 = 1   峰值落在 k = 16（期望 1 或 63）   输出 vs 真 DFT 误差 9.98e-01
 *   k0 = 7   峰值落在 k = 28（期望 7 或 57）   输出 vs 真 DFT 误差 9.73e-01
 *   k0 = 20  峰值落在 k =  5（期望 20 或 44）  输出 vs 真 DFT 误差 9.86e-01
 * </pre>
 *
 * <p>而把同一批输出拿去与 **`DFT(x∘ρ)`**（ρ = 位反转置换）比，误差是
 * **5.96e-08 / 1.26e-07 / 1.20e-07**——float32 的精度极限。
 * 也就是说：**输出恰好是"输入先按位反转、再做正确 DFT"的那个谱**，
 * 这正是 DIT 少了输入位反转的指纹，而不是"有点噪声"。
 *
 * <p>同一个 kernel、只把输入先在 CPU 上做一次位反转（ρ 是对合，于是等价于把
 * 漏掉的那一步补回去）再上传，三个频率的峰值就都落回了 `k0`/`N-k0`（k=63、7、20），
 * 误差 2.08e-07 / 1.39e-07 / 2.97e-08。**一次对、一次错，差别只有输入顺序**——
 * 这既把缺陷钉死，也证明本文件的那几条判据不是橡皮图章。
 *
 * <p>三道墙的层次值得记一笔：**②与③都不报错**。②只是让输出悄悄退化成输入，
 * ③只是让频谱悄悄错位——而"输入原样返回"与"算法正确"在肉眼上都像个能用的 FFT。
 *
 * <h2>三道墙怎么测：不修改任何生产代码</h2>
 *
 * <p>约束是"只测不修"，所以三处手术全部落在**本校验器内部**，而做法是：
 *
 * <ol>
 *   <li>用**反射**从 `GPUFFT` 里取出那份默认源码（不是在这里手抄一份——
 *       抄一份会随上游改动静默失真）；</li>
 *   <li>在源码字符串上做**最小改动**，每一步都被一条独立的证据逼出来，
 *       而改动本身逐条说明"为什么语义不变"；</li>
 *   <li>交给 `GPUFFT(String shaderSource)` 这个**本来就在的公开构造**去跑。</li>
 * </ol>
 *
 * <p>三处改动是：<b>①</b>`half` → `halfLen`（改名字；顺带 `halfN` → `halfLenN`，
 * 声明与引用处处一致地跟着变）；<b>②</b>`uniform uint u_*` → `uniform int u_*`
 * （三个值都是非负小整数：`n=64`、`stage∈[0,6)`、`direction∈{0,1}`，
 * 参与比较与移位时转换结果完全相同；上游正确的修法是改用 `glUniform1ui`
 * 或把这三个 uniform 声明成 int，但那在生产代码里，本任务一行不许动）。
 * 蝴蝶结、twiddle、stage 循环、dispatch 全部是上游原样。
 *
 * <p>下面每一条算法层断言的标签里都写明"（等价 shader#2）"，
 * 不会与"默认 shader"或"只改了保留字的 shader#1"混起来。
 *
 * <h2>为什么判据是「峰值在哪个 bin」</h2>
 *
 * <p>这类缺陷属于本仓库最忌讳的「静默错误输出」：不报错、不崩、单元测试也拦不住
 * （压根没有单元测试），只是数据错。而错位了的频谱在肉眼上完全不像"错的"——
 * 它有峰、有形状、量纲也对。
 *
 * <p>唯一能把"对"与"错"分开的量，是把**输入的频率已知**这件事利用起来：
 * 令 `x[n] = cos(2π·k0·n/N)`，正确的 `|X[k]|` 在 `k = k0` 与 `k = N-k0` 各有一个
 * 高度 `N/2` 的峰，其余接近 0。峰值一旦跑到别的 bin 上，**就是错的**。
 *
 * <p>k0 取 7（而不是 0、N/2 这类"对称位置"）：`k0 = 0` 的频谱是单峰直流，
 * 位反转置换对它**毫无影响**；`k0 = N/2` 同理（自共轭）。只有一般的 k0 才看得出来。
 * 另外补跑 `k0 = 1`（低频）与 `k0 = 20`（中频）——**多个频率都错位**
 * 比"某一个错"更能说明是系统性的算法缺陷，而不是某个 bin 的边界巧合。
 *
 * <h2>为什么还要一份 CPU 参考</h2>
 *
 * <p>只断言峰值位置的话，一个**整体缩放错误**（例如漏掉归一化、twiddle 少一半）
 * 的实现照样能通过——峰还在原地，只是高度不对。所以这里用最朴素的 O(N²) DFT
 * （N=64，4096 次复数乘，微不足道）逐 bin 比对，报出**相对峰值的最大误差**。
 *
 * <p>CPU 参考吃的是**与 GPU 逐位相同的输入**（先把 `cos` 的结果过一遍 `Float`
 * 再转回 `Double`），否则比出来的是"输入取整误差"，不是变换误差。
 *
 * <h2>★ 反证：为什么还有"位反转输入"那一组</h2>
 *
 * <p>一个只会说"错"的校验器与一个恒假的校验器（比如回读永远是 0）**在输出上完全一样**。
 * 所以必须证明这套判据**有能力通过**：
 *
 * <p>DIT 缺位反转时，输出恰好是 `DFT(x∘ρ)`——**这一点可以拿来当实验**：
 * 把输入**先在 CPU 上做一次位反转**再上传，同一个 kernel、同一条路径，
 * 输出就应当是**正确的** `DFT(x)`（因为 ρ 是对合：`(x∘ρ)∘ρ = x`）。
 * 换句话说：**同一个 kernel，只换输入顺序，一次对一次错**——
 * 这既证明了校验器不是橡皮图章，也把"错的就是输入顺序"这件事钉死。
 *
 * <p>另外还有两条**置换不变量**作为前提断言：delta 输入（`x[0]=1`）的频谱恒为全 1，
 * 直流 bin `X[0] = Σx` 也与置换无关。它们证明 dispatch 与回读这条通路本身是通的
 * （否则"位反转缺失"这个结论会被一个"根本没跑"的探针误报出来——
 * 事实上第二道墙就是"根本没跑"的一种，正是这条前提把它抓了出来）。
 *
 * <h2>运行</h2>
 *
 * ```
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-cp %classpath com.bingbaihanji.jfgl.example.GpuFftVerifierKt"
 * ```
 *
 * <p>（`-D` 必须加引号；本校验器**不能**用 `mvn exec:java` 跑，理由见 CLAUDE.md。）
 * 退出码 0 = 全部通过，1 = 有断言失败（失败详情打印在 stdout）。它会自己关窗退出。
 *
 * <h2>退出码为什么写成 try/catch + finally</h2>
 *
 * <p>本仓库的四个既有校验器都栽在同一个坑里：断言抛出的异常逃到 GL 线程上，
 * 线程死掉、汇总行与 `exitProcess` 都走不到，JVM 因为"最后一个非守护线程结束"而
 * **以 0 退出**——一个已经打印了 FAIL 的校验器报出退出码 0。所以这里：
 * 校验体整体包在 try/catch 里（异常 → 退出码 1），GPUFFT 的释放放在 finally 里
 * （异常路径上 GL 资源也不会泄漏），而 `exitProcess` 在**这两者之外**调用。
 * 实测：完整跑一遍会打印 **失败 13 项**并给出**退出码 1**（不是 0，也不是"什么都没有"）。
 */
private const val SCENE_W = 400.0
private const val SCENE_H = 300.0

/** 变换长度。2 的幂，且小到 O(N²) 的 CPU 参考可以随手算。 */
private const val N = 64

private const val LOG_N = 6

/**
 * 三个测试频率。7 是主判据（既不是 0 也不是 N/2，位反转置换对它有效）；
 * 1 与 20 是补充，用来证明错位是系统性的。
 */
private val K0_LIST = intArrayOf(1, 7, 20)

/** 正弦峰的期望高度（未归一化 DFT，实余弦劈成两个共轭峰各占一半）。 */
private const val EXPECTED_PEAK = N / 2.0

/** 峰值高度允许的相对偏差（float32 的变换误差远小于它）。 */
private const val PEAK_TOLERANCE = 0.05

/** "其余 bin 都很小"的门槛：相对峰值。 */
private const val SIDE_LOBE_LIMIT = 0.01

/**
 * 与 CPU 参考比对的门槛：**相对峰值**的最大误差。
 *
 * <p>取 1e-3 是留了三个数量级的余量：float32 累加 N=64 次的理论误差在 1e-6 量级；
 * 而一个"峰值位置对、数值错"的实现（整体缩放、少一个 twiddle、少归一化）
 * 的误差是 O(1) 量级——两者之间隔着三个数量级，门槛随便落在哪里都分得开。
 */
private const val CPU_REF_TOLERANCE = 1e-3

/**
 * 第一道墙：默认 shader 里的**保留字**，以及它在等价 shader 里的替身。
 *
 * <p>`half` 在 GLSL 规范里被列为 reserved for future use（留给将来的半精度类型），
 * 拿它当标识符必然编译失败。改名字不改算法——两者处处成对出现，语义一字未动。
 */
private const val RESERVED_TOKEN = "half"
private const val RENAMED_TOKEN = "halfLen"

/**
 * 第二道墙：三个 `uniform uint` 的声明文本，以及把类型换成 `int` 之后的写法。
 *
 * <p>`glUniform1i` 对 `uint` uniform 报 `GL_INVALID_OPERATION` 且**不生效**，
 * 于是 `u_N` 保持 0、所有线程提前返回。三个值都是非负小整数，
 * 换成 `int` 之后每一处比较与移位的转换结果完全相同。
 */
private const val UNIFORM_UINT_PREFIX = "uniform uint u_"
private const val UNIFORM_INT_PREFIX = "uniform int u_"

/** 位反转置换（ρ）：下标 i 的 logN 位倒序。ρ 是对合，ρ∘ρ = 恒等。 */
private val BIT_REV = IntArray(N) { i ->
    var r = 0
    var v = i
    repeat(LOG_N) {
        r = (r shl 1) or (v and 1)
        v = v shr 1
    }
    r
}

/**
 * 校验器的启动入口。
 *
 * <p>函数名不叫 `main`：同包的 [PipelineExample] 已有顶层 `main()`，
 * 两个同名顶层函数会让 `import com.bingbaihanji.jfgl.example.main` 报"重载歧义"。
 * 用 `@JvmName("main")` 把 JVM 方法名钉回 `main`，上面文档里的命令行因此照常可用。
 * 另外四个校验器用的是同一个写法，本文件保持一致。
 */
@JvmName("main")
fun gpuFftVerifyMain() {
    Application.launch(GpuFftVerifierApp::class.java)
}

class GpuFftVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null

    /** 只跑一次。`onRender` 每帧都会被调用，而这是一次性的校验器。 */
    private var finished = false

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
        // 只在 onRender 里跑：GL 上下文只在 GLCanvas 的这几个回调里是当前的
        // （见 CLAUDE.md 的「线程模型」）。本校验器不画任何东西——它要的是 GL 上下文，
        // 不是像素。
        bridge.onRender { verifyOnce() }
        transfer = bridge

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "JFGL GPUFFT Verifier"
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
     * <p>**不在这里调用 `exitProcess`**：它的调用点必须在 try/finally 之外（见 [verifyOnce]）。
     */
    private fun verifyAll(): Int {
        val failures = ArrayList<String>()

        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        /** 只打印、不计入失败的观察行。诊断用的量不该混进 pass/fail 的账里。 */
        fun info(label: String, detail: String) = println("  [诊断] $label — $detail")

        println("=== JFGL GPU 计算着色器输出校验（gpu/GPUFFT.java）===")
        println("N = $N（2^$LOG_N），测试频率 k0 = ${K0_LIST.joinToString()}")
        println("GL_VERSION  = ${glGetString(GL_VERSION)}")
        println("GL_RENDERER = ${glGetString(GL_RENDERER)}")

        // 清掉此前积压的错误：后面每个阶段都要能区分"这次调用出错了"与"之前就错了"。
        drainGlError()

        // ==================================================================
        // 零、第一道墙：默认 shader 编译不过
        // ==================================================================
        //
        // 这必须是**第一位**的结论：一个连 shader 都编译不过的实现，它的"输出"
        // 根本不存在，谈"输出对不对"没有意义。
        println("\n-- 零、第一道墙：默认 shader 能否编译并链接（被测对象能不能跑起来）--")

        var defaultBlocker: Throwable? = null
        try {
            GPUFFT().dispose()
        } catch (t: Throwable) {
            defaultBlocker = t
        }
        report("★ 默认 shader（无参构造 GPUFFT()）能编译并链接",
            defaultBlocker == null,
            defaultBlocker?.message?.trim()
                ?: "编译并链接通过——被测对象是可运行状态")

        // 编译失败的证据要机器可查，而不是靠人读一遍日志：
        // 报错的那个 token 就是 RESERVED_TOKEN 本身。
        val tokenInLog = defaultBlocker?.message?.contains("\"$RESERVED_TOKEN\"") == true
        if (defaultBlocker != null) {
            report("★ 编译失败的原因就是保留字 `$RESERVED_TOKEN`（不是别的语法问题）",
                tokenInLog,
                if (tokenInLog) "驱动的报错信息里点的名就是 token \"$RESERVED_TOKEN\"——" +
                        "它在 GLSL 规范里是 reserved for future use 的关键字，" +
                        "因此这是必然编译不过，与驱动是谁无关"
                else "报错信息里没提到 \"$RESERVED_TOKEN\"，需要人工看日志：${defaultBlocker.message}")
        }
        drainGlError() // 编译失败本身不该留下 GL 错误；留了也不该记到后面的账上

        // ==================================================================
        // 一、等价 shader#1：只改保留字，算法一字未动
        // ==================================================================
        //
        // 不这么做的话，本校验器就停在"编译不过"这一句上，而"算法到底对不对"
        // 那个问题会永远悬着。取源码用**反射**而不是在这里手抄一份：
        // 手抄的那份会随上游改动静默失真，而反射取到的**就是上游那一份**。
        println("\n-- 一、等价 shader#1：只把保留字改掉，算法一字未动 --")

        val defaultSource = readDefaultShaderSource()
        report("前提：能从 GPUFFT 里反射取到默认 shader 源码",
            defaultSource != null,
            if (defaultSource == null)
                "取不到（字段名或可见性变了）——下面的全部算法层断言都无法进行"
            else "${defaultSource.length} 字符")

        if (defaultSource == null) {
            return finish(failures)
        }

        // 只改标识符。half → halfLen 同时也把 halfN 变成 halfLenN——
        // 声明与引用处处一致地跟着变，语义一字未动。
        val lexOnly = defaultSource.replace(RESERVED_TOKEN, RENAMED_TOKEN)
        val occurrences = Regex(RESERVED_TOKEN).findAll(defaultSource).count()
        info("改动 ①", "把 $occurrences 处 `$RESERVED_TOKEN` 改名为 `$RENAMED_TOKEN`" +
                "（其中一部分来自 halfN）")

        val fft1 = try {
            GPUFFT(lexOnly)
        } catch (t: Throwable) {
            report("★ 等价 shader#1 能编译并链接（否则算法层的断言一条也做不了）",
                false, t.message?.trim() ?: t.toString())
            null
        }
        if (fft1 != null) {
            report("★ 等价 shader#1 能编译并链接（否则算法层的断言一条也做不了）",
                true, "只改掉保留字之后就能跑了——第一道墙确实只有那个名字")
        }

        // ==================================================================
        // 二、第二道墙：dispatch 空转（glUniform1i 喂 uint uniform）
        // ==================================================================
        //
        // 这一节同时用两种互相独立的证据把机制钉死：
        //   (a) **行为**：等价 shader#1 的输出**逐位等于输入**——SSBO 一个字节都没被写过。
        //       这只可能来自"每个线程都提前返回"或"压根没跑"，
        //       而下面的对照实验把前者坐实。
        //   (b) **对照实验**：同一个最小 kernel，只把 `uniform uint u_N` 换成 `uniform int u_N`，
        //       一个报 GL_INVALID_OPERATION 且值没生效，一个干干净净且值生效。
        println("\n-- 二、第二道墙：dispatch 空转（glUniform1i 对 uint uniform 不生效）--")

        if (fft1 != null) {
            val xRe = DoubleArray(N).also { it[0] = 1.0 }
            val xIm = DoubleArray(N)
            val gpu = runGpu(fft1, xRe, xIm)
            val glErr = drainGlError()
            // 逐位比较（不是"误差很小"）：SSBO 要么被写过、要么没被写过，中间没有别的可能。
            val identical = (0 until N).all {
                gpu.re[it] == xRe[it] && gpu.im[it] == 0.0
            }
            report("★ 等价 shader#1 的输出**逐位等于输入**（说明 SSBO 一个字节都没被写过）",
                identical,
                if (identical)
                    "输入是 delta（x[0]=1，其余 0），读回来仍是 delta——" +
                            "若蝶形真的跑了，输出该是全 1（DFT 与位反转置换对 delta 都没有影响）"
                else "输出与输入不同：${(0 until N).take(4).joinToString { "%.3f".format(gpu.re[it]) }}…" +
                        "——那就不是「空转」，需要另找原因")
            report("前提：这一次 execute 确实留下了 GL 错误（证明下面的对照实验不是无的放矢）",
                glErr != GL_NO_ERROR,
                "glGetError=0x${Integer.toHexString(glErr)}（0x502 = GL_INVALID_OPERATION）")
            fft1.dispose()
        }

        // 对照实验：同一个最小 kernel，唯一的变量是那个 uniform 的**类型**。
        // 单看"uint 那一组失败"是橡皮图章（可能是别的原因），必须成对：
        // int 那一组必须干净且值真的生效，才证明变量就是类型本身。
        val (uintErr, uintWrote) = uniformTypeProbe("uniform uint u_N;")
        val (intErr, intWrote) = uniformTypeProbe("uniform int u_N;")
        report("★ 对照 A：`uniform uint u_N` + `glUniform1i` → 报错且值不生效",
            uintErr == GL_INVALID_OPERATION && !uintWrote,
            "glGetError=0x${Integer.toHexString(uintErr)}，写进去了吗=$uintWrote" +
                    "（u_N 保持 0 → `gl_GlobalInvocationID.x >= u_N` 恒真 → 每个线程都提前返回）")
        report("★ 对照 B：`uniform int u_N` + 同一个 `glUniform1i` → 无错误且值生效",
            intErr == GL_NO_ERROR && intWrote,
            "glGetError=0x${Integer.toHexString(intErr)}，写进去了吗=$intWrote" +
                    "——两组的差别只有那个类型，所以机制就是它")
        drainGlError()

        // ==================================================================
        // 三、等价 shader#2：再把三个 uniform 换成 int，算法才真的开始跑
        // ==================================================================
        println("\n-- 三、等价 shader#2：三个 uniform 换成 int（值都是非负小整数，语义不变）--")
        val repaired = lexOnly.replace(UNIFORM_UINT_PREFIX, UNIFORM_INT_PREFIX)
        val swapped = Regex(UNIFORM_UINT_PREFIX).findAll(lexOnly).count()
        info("改动 ②", "把 $swapped 处 `$UNIFORM_UINT_PREFIX*` 声明成 `$UNIFORM_INT_PREFIX*`" +
                "（n=64、stage∈[0,6)、direction∈{0,1}，比较与移位的转换结果完全相同）")

        val fft2 = try {
            GPUFFT(repaired)
        } catch (t: Throwable) {
            report("★ 等价 shader#2 能编译并链接（否则算法层的断言一条也做不了）",
                false, t.message?.trim() ?: t.toString())
            null
        }
        if (fft2 != null) {
            report("★ 等价 shader#2 能编译并链接（否则算法层的断言一条也做不了）",
                true, "两处改动之后，算法终于跑得起来了")
        }

        try {
            if (fft2 == null) {
                println("\n  （算法层断言无法进行：没有可运行的 shader。上面那些失败即结论。）")
            } else {
                // 局部函数不能直接当函数值传，包一层 lambda。
                verifyAlgorithm(
                    fft2,
                    { label, ok, detail -> report(label, ok, detail) },
                    { label, detail -> info(label, detail) }
                )
            }
        } finally {
            // 异常路径上也释放（GL 资源必须在 GL 线程上释放，而这里正是 GL 线程）。
            fft2?.dispose()
            drainGlError()
        }

        return finish(failures)
    }

    /** 汇总并给出退出码。失败清单为空即 0。 */
    private fun finish(failures: List<String>): Int {
        println()
        if (failures.isEmpty()) {
            println("=== 全部通过：GPUFFT 的输出是对的 ===")
        } else {
            println("=== 失败 ${failures.size} 项：${failures.joinToString("；")} ===")
        }
        return if (failures.isEmpty()) 0 else 1
    }

    /**
     * 全部**算法层**的断言：前提（置换不变量）、核心（峰值位置 + 逐 bin 比对）、
     * 反证（位反转输入）、诊断。
     *
     * <p>它跑在一份改掉两处阻塞点的等价 shader 上——这一点写在每一条断言的标签里
     * （"（等价 shader#2）"），否则读的人会以为默认那份也能跑。
     *
     * <p>写成独立方法是为了让"shader 从来没跑起来"与"算法错了"这两件事在代码里
     * 也是分开的：前者决定这里跑不跑，后者只由这里说话。
     */
    private fun verifyAlgorithm(
        fft: GPUFFT,
        report: (String, Boolean, String) -> Unit,
        info: (String, String) -> Unit,
    ) {
        var glError = GL_NO_ERROR

        /**
         * 抽干 GL 错误并记下第一个。一个失败的 dispatch（uniform 类型不匹配、
         * 上下文其实不支持 4.3 ……）会**静默地什么都不做**，于是输出退回成输入，
         * 看起来像是"算法错了"。所以每一段都要查。
         */
        fun checkGl(tag: String) {
            val e = drainGlError()
            if (e != GL_NO_ERROR) {
                if (glError == GL_NO_ERROR) glError = e
                println("  !! $tag 之后出现 GL 错误 0x${Integer.toHexString(e)}")
            }
        }

        // ------------------------------------------------------------------
        // 前提：两条**置换不变量**。它们证明 dispatch 与回读是通的
        // ------------------------------------------------------------------
        //
        // 这两条的期望值在"正确实现"与"缺位反转的实现"下**完全相同**，所以它们
        // 一条也分不开对错——它们的作用恰恰在此：**只**测通路。少了它们，
        // 一个"根本没跑起来"的探针会把"回读全是垃圾"报成"算法错了"
        // （第二道墙就是"没跑起来"的一种，正是这类前提把它抓出来的）。
        println("\n-- 前提：dispatch 与回读这条通路本身是通的（等价 shader#2，两条置换不变量）--")

        // delta 输入：x[0] = 1，其余为 0。DFT 恒为全 1，且 x∘ρ 只是把那个 1 挪了个位置
        // （ρ(0) = 0，其实连挪都没挪），所以两种实现都该给全 1。
        run {
            val xRe = DoubleArray(N).also { it[0] = 1.0 }
            val xIm = DoubleArray(N)
            val gpu = runGpu(fft, xRe, xIm)
            checkGl("delta 输入")
            val worst = (0 until N).maxOf { abs(gpu.re[it] - 1.0) + abs(gpu.im[it]) }
            report("前提（等价 shader#2）：delta 输入的输出恒为全 1（证明 dispatch + 回读通了）",
                worst <= 1e-4,
                "与 1 的最大偏差 $worst——偏差大说明 dispatch 没跑（输出会退回成输入：" +
                        "第 0 个 bin 是 1 而其余是 0）或回读拿到的是垃圾")
        }

        // 直流 bin 与置换无关：X[0] = Σx，无论输入怎么排。
        //
        // 输入刻意**加一个直流偏置 1.0**：纯余弦的 Σx 恒为 0（k0 与 N 互素时），
        // 于是容差 `1e-3 × sum` 退化成 `0 ≤ 0`，一条恒假的断言——
        // 实测踩过：一个 float32 泄漏量级的 1e-7 就能让它 FAIL，
        // 而那看起来像"算法错了"，其实是校验器自己写坏了。
        run {
            val xRe = DoubleArray(N) { (1.0 + cos(2.0 * PI * 3 * it / N)).toFloat().toDouble() }
            val xIm = DoubleArray(N)
            val gpu = runGpu(fft, xRe, xIm)
            checkGl("直流 bin 探针")
            val sum = xRe.sum()
            report("前提（等价 shader#2）：直流 bin X[0] 等于 Σx（与输入置换无关）",
                abs(gpu.mag(0) - sum) <= 1e-3 * sum,
                "X[0] 幅值 ${"%.6f".format(gpu.mag(0))}，Σx = ${"%.6f".format(sum)}" +
                        "（期望都是 $N：偏置 1.0 乘上 $N 个样本）")
        }

        // ------------------------------------------------------------------
        // ★ 核心：峰值落在哪个 bin + 逐 bin 与 CPU 参考比对
        // ------------------------------------------------------------------
        println("\n-- ★ 核心：纯正弦的峰值必须落在 k0 与 N-k0 上（等价 shader#2）--")

        // 三个频率各自的诊断，攒起来在最后统一打印，免得中间被断言淹掉。
        val diagnosis = ArrayList<String>()
        // 每个 k0 上"GPU 输出与真 DFT 的误差"，供反证使用。
        val directErrors = ArrayList<Double>()
        // 每个 k0 上"GPU 输出与 DFT(x∘ρ) 的误差"——缺输入位反转时它应当接近 0。
        val bitRevErrors = ArrayList<Double>()

        for (k0 in K0_LIST) {
            // 输入过一遍 Float：GPU 上传的就是 float32，CPU 参考必须吃同一批数，
            // 否则比出来的是"输入取整"，不是"变换误差"。
            val xRe = DoubleArray(N) { cos(2.0 * PI * k0 * it / N).toFloat().toDouble() }
            val xIm = DoubleArray(N)

            val cpu = dft(xRe, xIm)
            val gpu = runGpu(fft, xRe, xIm)
            checkGl("k0=$k0 的 execute")

            val cpuPeak = (0 until N).maxOf { cpu.mag(it) }
            val peak = gpu.peakIndex()
            val peakMag = gpu.mag(peak)
            val errAbs = (0 until N).maxOf { hypot(gpu.re[it] - cpu.re[it], gpu.im[it] - cpu.im[it]) }
            val errRel = errAbs / cpuPeak
            directErrors.add(errRel)

            val conj = N - k0
            val magA = gpu.mag(k0)
            val magB = gpu.mag(conj)
            val otherMax = (0 until N).filter { it != k0 && it != conj }.maxOf { gpu.mag(it) }

            println("\n  --- k0 = $k0 ---")
            println("  幅值最大的 4 个 bin：" + (0 until N)
                .sortedByDescending { gpu.mag(it) }
                .take(4)
                .joinToString { "k=$it |X|=${"%.4f".format(gpu.mag(it))}" })
            println("  |X[$k0]| = ${"%.4f".format(magA)}，|X[$conj]| = ${"%.4f".format(magB)}" +
                    "（期望各约 $EXPECTED_PEAK）")
            println("  与 CPU 参考（O(N²) DFT）的最大偏差 $errAbs，相对峰值 ${"%.3e".format(errRel)}")

            // 1) 位置：这是唯一能把"对的"与"错位的"分开的量。
            report("k0=$k0（等价 shader#2）：幅值最大的 bin 是 $k0 或 $conj",
                peak == k0 || peak == conj,
                "实际峰值在 k = $peak（|X|=${"%.6f".format(peakMag)}），期望 $k0 或 $conj" +
                        "——峰跑到别的 bin 上就是错位：错位的频谱不报错、不崩，只是数据错")

            // 2) 两个共轭峰的高度：实余弦的谱是 N/2 各一个峰。
            //    只断言位置的话，一个整体缩放错误的实现照样通过——这是"数值也要对"。
            report("k0=$k0（等价 shader#2）：两个共轭峰的高度都接近 N/2 = $EXPECTED_PEAK",
                abs(magA - EXPECTED_PEAK) <= EXPECTED_PEAK * PEAK_TOLERANCE &&
                        abs(magB - EXPECTED_PEAK) <= EXPECTED_PEAK * PEAK_TOLERANCE,
                "|X[$k0]| = ${"%.4f".format(magA)}，|X[$conj]| = ${"%.4f".format(magB)}，" +
                        "期望都在 $EXPECTED_PEAK ±${(PEAK_TOLERANCE * 100).toInt()}%")

            report("k0=$k0（等价 shader#2）：两个共轭峰彼此接近（实信号的谱是对称的）",
                abs(magA - magB) <= 0.5,
                "|X[$k0]| = ${"%.4f".format(magA)}，|X[$conj]| = ${"%.4f".format(magB)}，差 " +
                        "${"%.4f".format(abs(magA - magB))}（期望 ≤ 0.5）——" +
                        "注意这一条**本来就分不开对错**：错位的谱照样可以是对称的" +
                        "（实测在错位输出上它照样全绿），它只防「半边整个塌掉」这种退化")

            // 3) 其余 bin 都很小。纯正弦的谱除了那两个峰应当什么都不剩；
            //    位反转置换打乱的是一个"看似有峰、其实到处都是能量"的谱。
            val limit = maxOf(magA, magB) * SIDE_LOBE_LIMIT
            report("k0=$k0（等价 shader#2）：其余 bin 都远小于峰（≤ ${SIDE_LOBE_LIMIT * 100}% 峰高）",
                otherMax <= limit,
                "非峰 bin 的最大幅值 ${"%.6f".format(otherMax)}，门槛 ${"%.6f".format(limit)}" +
                        "（正确实现在这里是 1e-5 量级的 float32 泄漏）")

            // 4) ★ 逐 bin 与 CPU 参考比对：把"位置对但数值错"也抓住。
            report("k0=$k0（等价 shader#2）：逐 bin 与 CPU 参考的最大相对误差 ≤ $CPU_REF_TOLERANCE",
                errRel <= CPU_REF_TOLERANCE,
                "相对峰值 ${"%.3e".format(errRel)}（一个漏了归一化、或 twiddle 少一半的实现" +
                        "也能让峰值留在原地，只有逐 bin 比对才抓得住）")

            val wrong = dft(
                DoubleArray(N) { xRe[BIT_REV[it]] },
                DoubleArray(N) { xIm[BIT_REV[it]] }
            )
            val errVsWrong = (0 until N).maxOf {
                hypot(gpu.re[it] - wrong.re[it], gpu.im[it] - wrong.im[it])
            } / cpuPeak
            bitRevErrors.add(errVsWrong)
            diagnosis.add(
                "k0=$k0：输出 vs 真 DFT 误差 ${"%.3e".format(errRel)}；" +
                        "输出 vs DFT(x 按位反转后) 误差 ${"%.3e".format(errVsWrong)}"
            )
        }

        // 这条断言刻意做成**两个宇宙里都成立**的形式：不管实现是对的还是缺位反转的，
        // 输出都必须是这两个谱之一。它两个作用：
        //   · 万一实现错成了第三种东西（比如 race、错读槽位），它立刻报出来；
        //   · 它把"输出 = DFT(x∘ρ)"与"输出 = DFT(x)"变成一个只有二选一的问题，
        //     于是上面三条"峰值位置"的失败**只**可能是那两种之一造成的，
        //     而不是某种说不清的浑浊状态。（实测：三个频率都落在后者上。）
        val explained = K0_LIST.indices.all {
            minOf(directErrors[it], bitRevErrors[it]) <= CPU_REF_TOLERANCE
        }
        report("输出必然是这两个谱之一：真 DFT，或 DFT(x 按位反转后)（否则还有别的问题）",
            explained,
            "自然顺序 vs 真 DFT 的误差 ${directErrors.joinToString { "%.3e".format(it) }}；" +
                    "vs DFT(x∘ρ) 的误差 ${bitRevErrors.joinToString { "%.3e".format(it) }}")

        // ------------------------------------------------------------------
        // ★ 反证：把输入先位反转再上传，同一个 kernel 必须给出正确的谱
        // ------------------------------------------------------------------
        //
        // 这一组的存在理由见文件头的「反证」一节：
        //   · 若 DIT 缺位反转 → 输出 = DFT(x∘ρ)，那么喂 x∘ρ 进去就得到 DFT(x)，**这一组通过**；
        //   · 若实现本来就是对的 → 输出 = DFT(x)，喂 x∘ρ 进去反而错，**这一组不通过**。
        // 所以它**不能**单独当成"实现正确"的判据（方向是反的）；它的价值在于：
        // 与核心那组合起来，**两次运行里必须至少有一种输入顺序是对的**——
        // 一次都不对，说明错的是校验器（或 CPU 参考），而不是那个 kernel。
        println("\n-- ★ 反证：输入先做一次位反转再上传（同一个 kernel，只换输入顺序）--")

        val reversedOk = ArrayList<Boolean>()
        for (k0 in K0_LIST) {
            val xRe = DoubleArray(N) { cos(2.0 * PI * k0 * it / N).toFloat().toDouble() }
            val xIm = DoubleArray(N)

            // ρ 是对合：(x∘ρ)∘ρ = x。喂进去的那一份就是"位反转后的输入"。
            val revRe = DoubleArray(N) { xRe[BIT_REV[it]] }
            val revIm = DoubleArray(N) { xIm[BIT_REV[it]] }

            val cpu = dft(xRe, xIm)
            val gpuRev = runGpu(fft, revRe, revIm)
            checkGl("k0=$k0 的反证运行")

            val cpuPeak = (0 until N).maxOf { cpu.mag(it) }
            val err = (0 until N).maxOf {
                hypot(gpuRev.re[it] - cpu.re[it], gpuRev.im[it] - cpu.im[it])
            } / cpuPeak
            val peak = gpuRev.peakIndex()
            val lo = minOf(k0, N - k0)
            val hi = maxOf(k0, N - k0)
            val ok = err <= CPU_REF_TOLERANCE
            reversedOk.add(ok)
            println("  [诊断] k0=$k0：位反转输入 → 峰值在 k=$peak" +
                    "（与 CPU 参考相符=$ok），与 CPU 参考的相对误差 ${"%.3e".format(err)}" +
                    "（真值应在 k=$lo 或 k=$hi）")
        }

        // 两个方向合起来只要求一件事：**至少有一次输入顺序是对的**。
        // 这一条是"校验器不是恒假"的守卫——一个回读永远是 0 的探针两个方向都会失败。
        val directOk = K0_LIST.indices.filter { directErrors[it] <= CPU_REF_TOLERANCE }
        val revOk = K0_LIST.indices.filter { reversedOk[it] }
        report("反证：自然顺序或位反转顺序，至少有一种输入能算出正确的谱" +
                "（否则错的是校验器本身，不是那个 kernel）",
            directOk.isNotEmpty() || revOk.isNotEmpty(),
            "自然顺序：${K0_LIST.size} 个频率里 ${directOk.size} 个对上" +
                    "（相对误差 ${directErrors.joinToString { "%.3e".format(it) }}）；" +
                    "位反转顺序：${revOk.size} 个对上——" +
                    "两边一个都对不上，说明问题在校验器或 CPU 参考，而不在那个 kernel")

        // 逐个频率打印方向诊断：这一行才是"错在哪"的答案。
        println("\n-- 诊断：输出到底等于哪一个谱 --")
        diagnosis.forEach { info("它更像哪个谱", it) }
        info("读法", "若第一项很大、第二项是 1e-6 量级 → 输出恰好是 DFT(x 按位反转后的输入)，" +
                "也就是 DIT 缺了输入位反转的那枚指纹；若第一项很小 → 实现本来就是对的。")

        // GL 错误单独报一条：它会把上面每一条断言都变成"看起来像算法错了"的假象。
        val err = drainGlError()
        if (err != GL_NO_ERROR) glError = err
        report("算法层这一整段没有 GL 错误（失败的 dispatch 会静默地什么都不做）",
            glError == GL_NO_ERROR,
            "glGetError=$glError" + if (glError == GL_NO_ERROR) "" else
                "（0x${Integer.toHexString(glError)}）——先查这个，再谈频谱")
    }

    // ---------------------------------------------------------------------------
    // 对照实验
    // ---------------------------------------------------------------------------

    /**
     * 第二道墙的**对照实验**：同一个最小 compute kernel，唯一的变量是那个 uniform 的**类型**。
     *
     * <p>kernel 是刻意照着 `GPUFFT` 的用法缩小的：一个 uint/int 的 uniform 当上界，
     * `gl_GlobalInvocationID.x >= u_N` 就提前返回，否则往 SSBO 里写 7。
     * 于是两组结果的差别只有"值有没有生效"这一件事：
     *
     * <ul>
     *   <li>`uniform uint u_N`：`glUniform1i` 报 `GL_INVALID_OPERATION`，值保持 0，
     *       `x >= 0` 恒真 → **每个线程都提前返回** → SSBO 里一个字节都没被写过
     *       （这正是 `GPUFFT` 当时的处境）；</li>
     *   <li>`uniform int u_N`：同一个 `glUniform1i` 干干净净，值生效 → 4 个 7.0。</li>
     * </ul>
     *
     * <p>单看"uint 那一组失败"是橡皮图章（可能是别的原因），必须成对才有判别力。
     *
     * @param uniformDecl uniform 声明那一行，两组之间唯一的差别
     * @return (第一个 GL 错误码, 值有没有真的写进 SSBO)
     */
    private fun uniformTypeProbe(uniformDecl: String): Pair<Int, Boolean> {
        val src = """
            #version 430
            layout(local_size_x = 4) in;
            layout(std430, binding = 0) buffer B { float v[]; };
            $uniformDecl
            void main() {
                if (gl_GlobalInvocationID.x >= u_N) return;
                v[gl_GlobalInvocationID.x] = 7.0;
            }
        """.trimIndent()

        val shader = glCreateShader(GL_COMPUTE_SHADER)
        glShaderSource(shader, src)
        glCompileShader(shader)
        val program = glCreateProgram()
        glAttachShader(program, shader)
        glLinkProgram(program)
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0 ||
            glGetProgrami(program, GL_LINK_STATUS) == 0
        ) {
            println("  对照实验的 kernel 编译/链接失败：${glGetShaderInfoLog(shader)}" +
                    glGetProgramInfoLog(program))
            return GL_NO_ERROR to false
        }

        val buffer = glGenBuffers()
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer)
        glBufferData(GL_SHADER_STORAGE_BUFFER, 4L * Float.SIZE_BYTES, GL_DYNAMIC_COPY)
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer)
        glUseProgram(program)

        // 与 GPUFFT.dispatchFFT 里一模一样的那一句：glUniform1i 喂一个 u_ 开头的 uniform。
        glUniform1i(glGetUniformLocation(program, "u_N"), 4)
        val err = drainGlError()

        glDispatchCompute(1, 1, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

        var wrote = false
        val mapped = glMapBuffer(GL_SHADER_STORAGE_BUFFER, GL_READ_ONLY,
            4L * Float.SIZE_BYTES, ByteBuffer.allocateDirect(4 * Float.SIZE_BYTES))
        if (mapped != null) {
            val fb = mapped.asFloatBuffer()
            wrote = (0 until 4).all { fb.get(it) == 7.0f }
            glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)
        }

        glUseProgram(0)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        glDeleteBuffers(buffer)
        glDeleteProgram(program)
        glDeleteShader(shader)
        drainGlError() // 清理本身不该留下错误
        return err to wrote
    }

    // ---------------------------------------------------------------------------
    // 纯计算部分（与 GL 无关）
    // ---------------------------------------------------------------------------

    /** 一次变换的结果：实部与虚部，单位是"未归一化的 DFT"。 */
    private class Spectrum(val re: DoubleArray, val im: DoubleArray) {
        fun mag(k: Int): Double = hypot(re[k], im[k])

        /** 幅值最大的 bin。并列时取下标最小的那个（确定性）。 */
        fun peakIndex(): Int {
            var best = 0
            for (k in re.indices) if (mag(k) > mag(best)) best = k
            return best
        }
    }

    /**
     * 跑一次 GPU 变换。
     *
     * <p>入参过一遍 `Float`：GPU 里存的就是 float32，拿 double 去比对只会比出
     * "输入本来就有的取整误差"。
     *
     * <p>注意 `GPUFFT.execute` 是**就地**修改传入的数组的——所以每次都要新造一份。
     */
    private fun runGpu(fft: GPUFFT, re: DoubleArray, im: DoubleArray): Spectrum {
        val fr = FloatArray(re.size) { re[it].toFloat() }
        val fi = FloatArray(im.size) { im[it].toFloat() }
        fft.execute(fr, fi)
        return Spectrum(
            DoubleArray(fr.size) { fr[it].toDouble() },
            DoubleArray(fi.size) { fi[it].toDouble() }
        )
    }

    /**
     * 最朴素的 O(N²) DFT：`X[k] = Σ x[n]·exp(-2πi·k·n/N)`，全程 double。
     *
     * <p>刻意不写成 FFT——这份参考的全部价值在于**它显然是对的**：
     * 它没有位反转、没有蝶形、没有 twiddle 表，只有一个二重循环。
     * 拿一个同样可能写错的 FFT 来当参考，就成了两个未知数互相印证。
     */
    private fun dft(re: DoubleArray, im: DoubleArray): Spectrum {
        val n = re.size
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        for (k in 0 until n) {
            var sRe = 0.0
            var sIm = 0.0
            for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                val c = cos(a)
                val s = sin(a)
                sRe += re[t] * c - im[t] * s
                sIm += re[t] * s + im[t] * c
            }
            outRe[k] = sRe
            outIm[k] = sIm
        }
        return Spectrum(outRe, outIm)
    }

    /**
     * 用反射取出 `GPUFFT.DEFAULT_FFT_SHADER` 那份源码。
     *
     * <p>为什么不在这里手抄一份：手抄的那份会随上游改动**静默失真**
     * （上游改了算法、这里还在测老的那份，结论就成了假的）。
     * 反射取到的**就是上游那一份**，于是"只改保留字"这句话可以被逐字兑现。
     *
     * <p>取不到时返回 null（字段被改名或挪走），由调用方报成一条失败断言——
     * 而不是抛出去把 GL 线程打死。
     */
    private fun readDefaultShaderSource(): String? = try {
        val field = GPUFFT::class.java.getDeclaredField("DEFAULT_FFT_SHADER")
        field.isAccessible = true
        field.get(null) as? String
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
