package com.bingbaihanji.xuan.example

import com.bingbaihanji.xuan.chart.*
import com.bingbaihanji.xuan.glview.FXGLTransfer
import com.bingbaihanji.xuan.renderer.Gc
import com.bingbaihanji.xuan.util.Rect
import com.bingbaihanji.xuan.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.stage.Stage
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL33
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.system.exitProcess
import javafx.scene.Scene as FxScene

/**
 * 图表折线渲染器的**性能探针**（不是校验器）：它不做任何断言，只打印数字然后以 0 退出。
 *
 * <h2>它为什么存在</h2>
 *
 * <p>{@code LineSeriesRenderer} 走实例化绘制，<b>每个样本一个实例、从不抽稀</b>。
 * "百万级点该不该做降采样"这个问题在这份代码里全是推测，缺的只有实测数字。
 * 本文件就是那批数字的唯一来源：同一份数据、同一个渲染路径，
 * 换 N 与线宽各测一遍每帧耗时。
 *
 * <p><b>它不判断对错，也不知道自己画出来的是什么。</b>它证明不了折线画对了地方——
 * 那件事归 {@code ChartVerifier}（65 条像素断言）。这里只有时间。
 * 所以：数字异常时要先怀疑本文件的计时口径（见下面"口径与已知偏差"），
 * 而不是先去怀疑渲染器。
 *
 * <h2>测什么</h2>
 *
 * <p>对 [POINT_COUNTS] 里的每个 N 各测一遍，每遍两种线宽（[LINE_WIDTHS]）：
 *
 * <ol>
 *   <li><b>每帧毫秒</b>：跳过前 [WARMUP_FRAMES] 帧预热，再连续测 [MEASURE_FRAMES] 帧，
 *       报平均与 p95。计时<b>把 GPU 算进去</b>：起点记在 {@code onFrame} 的开头，
 *       终点记在 {@code onRender} 里 {@code glFinish()} 之后（口径见下）。</li>
 *   <li><b>填充率 vs 顶点/实例开销的拆分</b>：同一份数据、同样实例数，
 *       线宽 1px 与 8px 各测一遍，两者之差 ≈ 光栅化填充成本，
 *       剩下的（1px 减去空帧基线）≈ 顶点/实例/提交成本。
 *       <b>这两个数指向完全不同的优化方向</b>：前者要抽稀，后者要换渲染方式。</li>
 *   <li><b>每帧上传字节数</b>（{@code ChartRenderer.takeUploadedBytes}）：
 *       这是探针自身的对照。数据是静态的，稳态下每帧必须是 <b>0 字节</b>；
 *       不是 0 就说明测的根本不是稳态，那批毫秒数全部作废。</li>
 * </ol>
 *
 * <h2>计时口径（★ 这是本文件最需要被怀疑的地方）</h2>
 *
 * <p>一帧的区间是：{@code onFrame} 的第一行 → {@code onRender} 里 {@code glFinish()} 返回。
 * 它覆盖的东西是：
 *
 * <pre>
 *   onFrame {
 *     t0 = nanoTime()          // ← 起点
 *     gc.fillRect(绘图区底色)
 *     gc.flush()
 *     gc.charts.draw(...)      // 折线的 instanced draw call 就在这里发出去
 *   }                          // FXGLTransfer 随后调 context.endFrame()（提交批处理顶点）
 *   onRender {
 *     glFinish()               // ← 等 GPU 把上面全部做完
 *     t1 = nanoTime()          // ← 终点
 *   }
 * </pre>
 *
 * <p><b>它包含的</b>：CPU 侧记命令的时间 + GPU 执行的时间 + 一次帧内提交
 * （{@code endFrame}）。这正是"这一帧要花多久"的定义。
 *
 * <p><b>它刻意排除的</b>：{@code glFinish} 之后的缓冲交换与 JavaFX 合成。
 * 也就是说它是<b>渲染成本</b>，不是"用户看到的帧率"——下面折算出来的 fps
 * 是渲染吞吐，不是显示帧率。openglfx 的渲染线程默认 fps = -1（由 JavaFX 的脉冲驱动，
 * 名义 60Hz），所以 60 以下的数字<b>不会被测量本身压住</b>：
 * 帧率上限那件事发生在 {@code glFinish} <b>之后</b>的 {@code renderLock.wait()} 里。
 *
 * <p><b>它有偏差的地方（拿不准的都在这里）</b>：
 * <ul>
 *   <li><b>每帧一次 {@code glFinish} 会把帧与帧串行化。</b>真实应用里 GPU 在执行第 k 帧时
 *       CPU 已经在记第 k+1 帧的命令，两者重叠；这里被 {@code glFinish} 掐断了。
 *       于是本探针的量级偏保守（偏高），尤其是 CPU 侧开销大的配置。
 *       <b>实测数字请按"上界"读，不要当成流水线化之后的实际值。</b></li>
 *   <li><b>它包含 {@code gc.fillRect} 那一小块底色的成本</b>（2 个三角形，常量级，
 *       而且被空帧基线一起减掉了）。</li>
 *   <li>{@code glFinish} 对"上下文里的全部未完成命令"生效，而帧内并没有别的东西，
 *       所以它等到的确实只是这一帧——<b>前提是上一帧也在它自己的 {@code glFinish}
 *       处排干了</b>。这一点由"每帧都记终点"保证。</li>
 * </ul>
 *
 * <h2>x 轴窗口取整个数据范围，于是每个样本都不足一个像素</h2>
 *
 * <p>窗口是 {@code [0, N-1]}，绘图区宽 [PLOT_W] px，所以每个样本占
 * {@code PLOT_W / (N-1)} px——N = 100k 时是 0.006px，N = 3M 时是 0.0002px。
 * <b>相邻两个样本在屏幕上几乎是同一个点</b>，于是每个实例的四边形在横向上是一条极窄的
 * 薄片，它的"厚度"由线宽在法线方向撑开。<b>这直接决定了两件事</b>：
 * <ol>
 *   <li>填充成本不是"线宽 × 屏幕宽度"那么简单，而是"每个实例自己的四边形被光栅化出
 *       几个片元"之和——<b>这个数随 N 线性增长</b>，所以 8px 与 1px 的差本身就随 N 变；</li>
 *   <li>这个配置下曲线在屏幕上糊成一片，<b>不是"密集折线"的典型用法</b>
 *       （真实示波器的可见窗口只有几百到几千个点，每个样本占好几个像素）。
 *       本探针回答的是"把 N 个实例全画出来要多久"这个上界问题，不是"画一条好看的
 *       曲线要多久"。要做后者的数字，得把窗口收窄到几百个样本——那是另一个探针。</li>
 * </ol>
 *
 * <h2>本轮新增：四个观测（"平顶"的成因）</h2>
 *
 * <p>上一轮的批数字里 <b>N=1M 与 N=3M 的每帧耗时几乎相同</b>（6.26 ms vs 6.06 ms），
 * 而实例数是 3 倍。这个"平顶"让整批数字无法解读，而它<strong>可能是任何一种东西</strong>：
 * 实例被静默截断、GPU 真的饱和、笔记本 GPU 掉频、或者一个与 GPU 工作量无关的固定成本。
 * <b>要分辨它们只能靠观测，不能靠推理。</b>于是本文件加了三种模式与两种新口径：
 *
 * <ol>
 *   <li><b>{@code ramp} 模式（像素口径）</b>：数据换成"0.1 线性升到 0.9"的斜坡，
 *       x 窗口仍是整个数据范围。于是绘图区里那条线的<strong>斜率就是"实际画出的数据比例"</strong>：
 *       画满了就从底升到顶，只画了前 1/3 就在 1/3 处停住。它<strong>完全不看时间</strong>，
 *       所以计时那个可疑量再离谱也影响不到它。</li>
 *   <li><b>批量口径</b>（[BATCH_FRAMES] 帧只在最后 {@code glFinish} 一次，总时长 / 帧数）
 *       与既有的逐帧口径并列打印，差多少就报多少。<b>读它时必须连"帧周期"一起读</b>：
 *       一帧的入口到下一帧的入口隔多久由 {@code FXGLTransfer} 的驱动方式决定
 *       （JavaFX 脉冲），批量口径量到的其实是"帧周期"与"一帧的工作量"两者的较大者。</li>
 *   <li><b>GPU 计时</b>（{@code GL_TIME_ELAPSED} 查询，只包住 {@code charts.draw}）：
 *       它是<strong>唯一既不受脉冲影响、也不受"每帧等一次 glFinish"影响的口径</strong>——
 *       量的就是这段命令在 GPU 上执行了多久。固定外部成本在它面前无处可藏。</li>
 *   <li><b>{@code thermal} 模式</b>：同一个 N 在"冷"（新进程里第一个跑）与"热"
 *       （同一进程里跑第二、第三次，或先跑完小 N 再跑它）两种进程状态下各测一遍。
 *       掉频的判据只有这一条：<strong>同一个配置在两种进程状态下时间应当不同</strong>。
 *       它必须在新进程里跑——同进程里"冷却"是等不到的。</li>
 * </ol>
 *
 * <p>另有两条用来拆开混淆变量的配置：
 * <ul>
 *   <li><b>倒序第二轮</b>（{@code -Dxuan.probe.rounds=2}）：原来的顺序里"N 越大"与
 *       "跑得越晚"是同一件事，掉频造成的变慢会被读成"N 越大越慢"。</li>
 *   <li><b>可见窗口 A/B</b>：同一个 N=3M、同一份数据、同一块缓冲，只把 x 轴窗口里的
 *       样本数从 300 万改成 100 万或 30 万。成本若 ∝ 画出来的实例数，这三个数就该差 3/10 倍；
 *       若它们相同，说明成本根本不在实例上。</li>
 * </ul>
 *
 * <h2>跑法（与校验器完全相同）</h2>
 *
 * <pre>
 * # 先从仓库根：mvn -o install -DskipTests
 * # 再进入 xuan-javafx：
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-Dstdout.encoding=UTF-8 -cp %classpath com.bingbaihanji.xuan.example.ChartPerfProbeKt"
 * </pre>
 *
 * <p>三种模式由系统属性 {@code xuan.probe.mode} 选择（默认 {@code full}，
 * 即上一轮那套 N × 线宽的扫描）：
 *
 * <pre>
 * # ① 像素口径（斜坡）：回读帧缓冲，打印"x 比例 → 实测值"。**它不看时间。**
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-Dstdout.encoding=UTF-8 -Dxuan.probe.mode=ramp -cp %classpath com.bingbaihanji.xuan.example.ChartPerfProbeKt"
 *
 * # ② 热/掉频（冷）：新进程里第一个跑的就是 N=3M，再在同一个进程里跑第二、第三次
 * ... "-Dexec.args=-Dstdout.encoding=UTF-8 -Dxuan.probe.mode=thermal -cp %classpath ..."
 *
 * # ③ 热/掉频（复现原来那个"热"场景）：先把 100k/300k/1M 跑完，再跑 3M
 * ... "-Dexec.args=-Dstdout.encoding=UTF-8 -Dxuan.probe.mode=thermal -Dxuan.probe.warm=100000,300000,1000000 -cp %classpath ..."
 *
 * # ④ 在 ① / ② 之外还想关掉 GPU 计时查询时：-Dxuan.probe.gputimer=false
 * </pre>
 *
 * <p>{@code -Dstdout.encoding=UTF-8} 必须带，否则中文全是乱码（本机默认 GBK）。
 *
 * <p>退出码：0 = 跑完（<b>不代表任何数字合格</b>）；1 = 绘制期抛异常；
 * 2 = 墙钟超时（[DEADLINE_MS]）没排完，那时已完成的部分照常打印。
 *
 * <h2>入口函数名</h2>
 *
 * <p>不叫 {@code main}：同包的 {@code PipelineExample} 已有顶层 {@code main()}，
 * 两个同名顶层函数会让 {@code import ...example.main} 报"重载歧义"。
 * 用 {@code @JvmName("main")} 把 JVM 方法名钉回 {@code main}（与四个校验器一致）。
 */
private const val SCENE_W = 800
private const val SCENE_H = 600

/** 绘图区（数据区域）矩形，设备像素。与 ChartVerifier 取同一个，好让数字可比。 */
private const val PLOT_X = 100f
private const val PLOT_Y = 100f
private const val PLOT_W = 600f
private const val PLOT_H = 400f

/**
 * 要测的点数。
 *
 * <p>6M 是本轮的极限：容量向上取到 2^23（VBO 33.5 MB），场景本身约 200 MB
 * （x/y 各一份 double 数组、`ArrayChartData` 再深拷一份）。
 * 跑不动时它的那一行会缺失（超时那条路），或者如实报"这一档跑不动"。
 */
private val POINT_COUNTS = intArrayOf(100_000, 300_000, 1_000_000, 3_000_000, 6_000_000)

/**
 * 要测的线宽。两者之差就是光栅化填充成本的量。
 *
 * <p>1 不是随手取的：它是最细的、也是"真实用法里最常见的折线宽度"；
 * 8 取得够粗（16px 宽的带子）才让填充成本从噪声里露出来。中间那些值（2/4）
 * 没有额外的信息量，只会让总时长翻倍。
 */
private val LINE_WIDTHS = floatArrayOf(1f, 8f)

/**
 * 模式选择用的系统属性。三个模式共用同一份代码，只有"配置表"不同。
 *
 * <p>用系统属性而不是命令行参数：入口是 {@code Application.launch}，
 * 命令行参数要经 JavaFX 转一手，而 `exec:exec` 的 `-Dexec.args` 里加 `-D` 是最直接的。
 */
private const val MODE_PROP = "xuan.probe.mode"

/** 默认模式：上一轮那套 N × 线宽的扫描（本轮加了 6M、可见窗口 A/B 与两种新口径）。 */
private const val MODE_FULL = "full"

/** 像素口径：斜坡数据 + 回读帧缓冲。**它不看时间。** */
private const val MODE_RAMP = "ramp"

/** 热/掉频：同一个 N 在冷、热两种进程状态下各测一遍。 */
private const val MODE_THERMAL = "thermal"

/** 跑几轮（第二轮把 N 的顺序倒过来）。见文件头"倒序第二轮"。 */
private const val ROUNDS_PROP = "xuan.probe.rounds"

/** THERMAL 模式下"正式测量之前先跑哪些 N"（逗号分隔）。空 = 冷启动。 */
private const val WARM_PROP = "xuan.probe.warm"

/** 关掉 GPU 计时查询（默认开）。只在它本身出问题时用。 */
private const val GPU_TIMER_PROP = "xuan.probe.gputimer"

/**
 * 斜坡模式要测的 N（逗号分隔），用于覆盖 [RAMP_COUNTS]。
 *
 * <p><b>它不是便利功能，是一条断言的前提。</b>斜坡的形状与 N 无关（都是 0.1 → 0.9 的直线），
 * 所以"回读到的是本档那一帧"这件事**看不出来**——万一读的是上一档留下的帧，
 * 结论就会是"3M 也画满了"，而实际上 3M 的帧根本没被看到。
 * 只跑一档时这个歧义不存在：`-Dxuan.probe.ramp.ns=3000000` 那一次，
 * 进程里从来没有画过别的 N。**本轮就是这么复核 3M 的。**
 */
private const val RAMP_NS_PROP = "xuan.probe.ramp.ns"

// ---- 斜坡观测（像素口径，Task 一）----------------------------------------

/** 斜坡的最低值。 */
private const val RAMP_LO = 0.1

/** 斜坡的最高值。整段从 [RAMP_LO] **线性**升到 [RAMP_HI]，没有别的形状。 */
private const val RAMP_HI = 0.9

/**
 * 斜坡观测要测的 N。
 *
 * <p>100k 是"已知跑得动、数字也正常"的对照；1M 与 3M 是"平顶"的两端。
 */
private val RAMP_COUNTS = intArrayOf(100_000, 1_000_000, 3_000_000)

/**
 * 斜坡观测用的线宽。
 *
 * <p>1px 是必须的那个——"平顶"那批数字就是 1px 测出来的。
 * 4px 是**对照**：它的墨迹一定有 4px 宽，于是"某一列数不到墨迹"不可能是
 * "线太细、光栅化没覆盖到这一列"造成的（那种歧义会让像素口径本身失效）。
 */
private val RAMP_WIDTHS = floatArrayOf(1f, 4f)

/**
 * 探测列的位置（占绘图区宽度的比例）。
 *
 * <p>10/30/50/70/90 是任务点名要的五个，另外几个是给"提前变平"定位用的中间点。
 */
private val RAMP_PROBE_FRACTIONS =
    doubleArrayOf(0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95)

/** 斜坡观测每个配置画几帧（第一帧含整批上传），在最后一帧回读。 */
private const val RAMP_FRAMES = 4

// ---- 热/掉频（Task 二）---------------------------------------------------

/** 被反复测的那一个配置的 N。3M 正是"平顶"右侧那个数。 */
private const val THERMAL_N = 3_000_000

/** 同一个进程里把 [THERMAL_N] 连测几次。第一次是"热"的起点，第二、三次才见分晓。 */
private const val THERMAL_REPEATS = 3

// ---- 可见窗口 A/B（拆开"成本 ∝ 实例数"与"成本是个固定值"）----------------

/**
 * 同一份 N=3M 的数据，只改 x 轴窗口里的可见样本数。
 *
 * <p>3000000 与全窗口等价（放在这里当 A/B 的基准），另外两个把实例数砍到 1/3 与 1/10。
 * 三个数若各自不同、且大致成比例，成本就真的在实例上；若三个数相同，
 * 成本就与实例数无关——那正是"平顶"要找的东西。
 */
private val WINDOW_AB = intArrayOf(3_000_000, 1_000_000, 300_000)

/** 每个配置跳过的预热帧数：第一帧含整批数据上传（N × 4 字节），必须跳过去。 */
private const val WARMUP_FRAMES = 30

/** 每个配置实测的帧数（口径 A：每帧一次 `glFinish`）。 */
private const val MEASURE_FRAMES = 120

/**
 * 口径 B（批量）的帧数：连续记这么多帧的命令，**只在最后一帧 `glFinish` 一次**。
 *
 * <p>上一位同学标注过"每帧一次 `glFinish` 把帧串行化了，数字是保守的上界"。
 * 这一档就是那个对照：把 120 次 `glFinish` 压成 1 次，看每帧省下多少。
 * <b>但读它时必须连"帧周期"一起读</b>（见 [Result.periodMs]）：
 * 帧是由 JavaFX 脉冲驱动的，脉冲之间的空档也会被算进这个总时长里，
 * 于是它量到的其实是"帧周期"与"每帧工作量 / 帧数"两者的较大者。
 */
private const val BATCH_FRAMES = 120

/** 每个配置的总帧数：预热 → 口径 A → 口径 B。 */
private const val FRAMES_PER_CONFIG = WARMUP_FRAMES + MEASURE_FRAMES + BATCH_FRAMES

/**
 * 整个探针的墙钟上限。
 *
 * <p>不是洁癖：N = 3M 时每帧可能要几十到几百毫秒，万一某台机器上它慢到跑不完，
 * 一个**不声不响挂在那里**的探针与"跑完了"在没有输出的终端里长得一模一样。
 * 超时走 [reportTimeout]，把已完成的部分照常打印、并以退出码 2 收场。
 */
private const val DEADLINE_MS = 8 * 60 * 1000L

/** 信号周期（样本数）：正弦的波长。取 4096 让它在 100k..3M 上都足够平滑。 */
private const val SIGNAL_PERIOD = 4096.0

/** 毛刺密度：每这么多个样本插一个窄脉冲。 */
private const val SPIKES_PER = 2000

/** 毛刺的固定随机种子——两次运行的输入必须逐位相同，否则数字没有可比性。 */
private const val SPIKE_SEED = 20260924L

/** 绘图区底色与折线色（0xAARRGGBB）。 */
private const val PLOT_BG = 0xFF202040.toInt()
private const val SIGNAL_ARGB = 0xFF00FF00.toInt()

/**
 * 折线色的 **RGB**（不含 alpha）。
 *
 * <p>回读帧缓冲的口径是"不含 alpha 的 RGB"（拿 ARGB 去比永远不相等，
 * 而看起来像"颜色画错了"——`ChartVerifier` 里那句注释是同一个坑）。
 * 所以像素口径的比较必须用这一个，不能用 [SIGNAL_ARGB]。
 */
private const val SIGNAL_RGB = 0x00FF00

/**
 * 探针的启动入口。见文件头"入口函数名"。
 */
@JvmName("main")
fun chartPerfProbeMain() {
    Application.launch(ChartPerfProbeApp::class.java)
}

class ChartPerfProbeApp : Application() {

    private var transfer: FXGLTransfer? = null

    /** 运行模式（见文件头）。三个模式共用同一份代码，只有配置表不同。 */
    private val mode: String = System.getProperty(MODE_PROP, MODE_FULL)

    /** 跑几轮。见文件头"倒序第二轮"。 */
    private val rounds: Int =
        (System.getProperty(ROUNDS_PROP) ?: "1").toIntOrNull()?.coerceIn(1, 4) ?: 1

    /**
     * THERMAL 模式下正式测量之前先跑的 N（逗号分隔）。空 = 冷启动。
     *
     * <p>解析失败时**抛异常**而不是当成空：一个被静默忽略的 `-Dxuan.probe.warm=...`
     * 会让人以为自己在跑"热"那一支，实际跑的是"冷"的——而两组数字看起来都正常。
     */
    private val warmCounts: IntArray = System.getProperty(WARM_PROP, "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map {
            it.toIntOrNull() ?: throw IllegalArgumentException(
                "$WARM_PROP 里的 «$it» 不是整数（写成逗号分隔的 N，例如 100000,300000,1000000）"
            )
        }
        .toIntArray()

    /** GPU 计时查询（`GL_TIME_ELAPSED`）是否被要求开启。 */
    private val gpuTimerWanted: Boolean =
        !"false".equals(System.getProperty(GPU_TIMER_PROP), ignoreCase = true)

    /** 全部待测配置。0 号是空帧基线（见 [prepare]）。顺序本身是探针的一部分，见 [buildConfigs]。 */
    private val configs: List<Config> = buildConfigs()

    /** 已完成并被打印的配置。 */
    private val results = ArrayList<Result>()

    /** 当前配置的下标。等于 [configs] 的大小时表示全部跑完。 */
    private var index = 0

    /** 当前配置里已经画过的帧数。 */
    private var frameInConfig = 0

    /** 当前配置的场景是否已经建好。换 N 的那一帧上建（见 [prepare]）。 */
    private var prepared = false

    /** 当前配置的场景。空帧基线时为 null。 */
    private var scene: Scene? = null

    /** 本帧 [onFrameBody] 的入口时刻。**口径 B 的起点就是它**，帧周期也由它算。 */
    private var frameStartNs = 0L

    /** 每个配置里各帧的入口时刻，用来量"帧周期"（下标是 frameInConfig）。 */
    private val frameStarts = LongArray(FRAMES_PER_CONFIG)

    /** 口径 A 的计时起点（[System.nanoTime]）；`timing` 为 false 时无意义。 */
    private var t0 = 0L

    /** 本帧是不是一个"要记终点"的帧（口径 A）。它在 [onFrameBody] 里置起、在 [runRender] 里消费。 */
    private var timing = false

    /** 本配置已记下的帧耗时（纳秒），口径 A。 */
    private val samples = LongArray(MEASURE_FRAMES)

    private var sampleCount = 0

    /** 口径 B：第一帧的入口时刻、已累计的帧数、总时长（纳秒）。 */
    private var batchStartNs = 0L
    private var batchFrames = 0
    private var batchTotalNs = 0L

    /** 本帧是不是口径 B 的最后一帧（要在 [runRender] 里做那唯一一次 `glFinish`）。 */
    private var batchFinishing = false

    /** 本配置在测量期内累计的上传字节数，以及其中非零的帧数。 */
    private var uploadedBytes = 0L
    private var nonZeroUploadFrames = 0

    /** 绘制期抛出的异常。GL 线程上一个没接住的异常会让 JVM 以 0 退出（静默的绿）。 */
    private var drawError: Throwable? = null

    /** `onRender` 期抛出的异常。与 [drawError] 同一个理由，但发生在另一半回调里。 */
    private var renderError: Throwable? = null

    private var startWall = 0L

    /** 帧缓冲尺寸与 GL 信息只打印一次。 */
    private var printedEnvironment = false

    // ---- GPU 计时查询（GL_TIME_ELAPSED，只包住 charts.draw）----

    /** 查询对象；0 表示没建。 */
    private var gpuQuery = 0

    /** 查询是否真的可用。它自己的失败**绝不能**影响绘制路径（否则探针会一个像素都画不出来）。 */
    private var gpuTimerEnabled = false

    /** 本帧开始时读到的"上一帧的 GPU 用时"（纳秒）；-1 = 没有。 */
    private var gpuFrameNs = -1L

    /** 上一帧开了查询、结果还没读。 */
    private var gpuQueryPending = false

    /** 本帧的查询正开着（[endGpuTimer] 要配对）。 */
    private var gpuQueryActive = false

    private val gpuSamples = LongArray(MEASURE_FRAMES + 4)
    private var gpuSampleCount = 0

    /**
     * 按模式造配置表。
     *
     * <p>**顺序本身就是探针的一部分**（见文件头"倒序第二轮"与"可见窗口 A/B"），
     * 所以它集中在这里，而不是散在 [prepare] 或 [drawFrame] 里。
     */
    private fun buildConfigs(): List<Config> = when (mode) {
        MODE_RAMP -> buildList {
            // 可以只跑一档（见 [RAMP_NS_PROP]）：进程里没有别的 N，
            // "回读到的是本档那一帧"这件事因此不再需要靠推理。
            val ns = System.getProperty(RAMP_NS_PROP)
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: RAMP_COUNTS.toList()
            for (n in ns) for (w in RAMP_WIDTHS) add(Config(n, w, ramp = true))
        }

        MODE_THERMAL -> buildList {
            // 先跑的这几档不是"暖机"，它们就是原来那个场景里排在 3M 前面的那几档：
            // 100k/300k/1M 各两种线宽，跑完它们再测 3M。
            for (n in warmCounts) for (w in LINE_WIDTHS) add(Config(n, w, note = "预热"))
            repeat(THERMAL_REPEATS) { i -> add(Config(THERMAL_N, 1f, note = "第 ${i + 1} 次")) }
        }

        else -> buildList {
            add(Config(n = 0, lineWidth = 0f))
            for (r in 0 until rounds) {
                // 偶数轮正序、奇数轮倒序：N 与"跑到第几个"因此不再共线，
                // 时间漂移（掉频/升温）就不会被读成"N 越大越慢"。
                val ns = if (r % 2 == 0) POINT_COUNTS.toList() else POINT_COUNTS.toList().reversed()
                for (n in ns) {
                    // 线宽**交错**（1px, 8px, 1px, 8px…）而不是"先跑完所有 1px 再跑所有 8px"：
                    // 后一种写法里线宽与"跑得早还是晚"是同一件事，漂移会被读成线宽的影响。
                    for (w in LINE_WIDTHS) add(Config(n, w, round = r))
                }
            }
            // 可见窗口 A/B：同一个 N、同一份数据、同一块缓冲，只有"画出来的实例数"不同。
            for (v in WINDOW_AB) add(Config(3_000_000, 1f, visible = v, note = "窗口A/B"))
        }
    }

    /** 一个待测配置。`n == 0` 是空帧基线。 */
    private class Config(
        val n: Int,
        val lineWidth: Float,
        /** 可见窗口里的样本数；0 = 整个数据范围。 */
        val visible: Int = 0,
        /** 第几轮（> 0 的轮次在第二轮里是**倒序**跑的）。 */
        val round: Int = 0,
        /** 自由后缀，用来标注"第几次"这类重复。 */
        val note: String = "",
        /** 用斜坡数据（[RAMP_LO] → [RAMP_HI] 线性）而不是"正弦 + 毛刺"。 */
        val ramp: Boolean = false,
    ) {

        val label: String
            get() = if (n == 0) "空帧基线（同一场景，去掉 charts.draw）"
            else buildString {
                append("N=$n  lineWidth=${trim(lineWidth)}px")
                if (visible > 0) append("  可见 $visible 个样本")
                if (round > 0) append("  第${round + 1}轮")
                if (note.isNotEmpty()) append("  $note")
                if (ramp) append("  斜坡数据")
            }

        companion object {
            /** 线宽打印成整数时不带小数点，读起来干净。 */
            fun trim(w: Float): String =
                if (w == w.toInt().toFloat()) w.toInt().toString() else w.toString()
        }
    }

    /**
     * 一个 N 的场景：数据、轴、系列、图。
     *
     * <p><b>线宽不在里面</b>：它每帧从 {@code series.lineWidth()} 读（渲染器每帧都读），
     * 所以两个线宽配置<b>共用同一个 Scene、同一个 Series、同一块 GPU 缓冲</b>——
     * "同一份数据、同样实例数，只有线宽不同"这句话因此是字面成立的。
     * 给两个线宽各建一份数据的话，两者的差别里会掺进"两次分配的不同内存布局"。
     *
     * <p><b>x 轴也在里面，而它是每帧被改的那个量</b>：[Config.visible] 那一组 A/B 就靠它——
     * 换窗口只是换一个显示窗口（轴不持有数据），既不重建缓冲、也不触发上传，
     * 于是"时间跟着窗口变了吗"这个问题里只剩"画出来的实例数"这一个变量。
     */
    private class Scene(
        val n: Int,
        val ramp: Boolean,
        val series: Series,
        val chart: Chart,
        val xAxis: Axis,
    )

    /**
     * 一块回读下来的像素：RGB（不含 alpha）、行优先、**从上到下**。
     *
     * <p>与 {@code ChartVerifier} 里的同名类同一个口径，理由也一样：断言要按 (x, y) 取值，
     * 而裸数组的下标算式（`y * w + x`）散在各处写错一次就是"数了别的地方的像素"——
     * 那种失败看起来像"渲染错了"。
     */
    private class Shot(val w: Int, val h: Int, val px: IntArray) {

        fun at(x: Int, y: Int): Int = px[y * w + x]

        fun count(rgb: Int): Int = px.count { it == rgb }

        /** `[x0, x1]` 这几列里该颜色像素的行范围（含最上面与最下面那一个）；都没有时返回 null。 */
        fun inkRange(x0: Int, x1: Int, rgb: Int): IntRange? {
            var top = -1
            var bottom = -1
            for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(w - 1)) {
                for (y in 0 until h) {
                    if (at(x, y) == rgb) {
                        if (top < 0 || y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
            return if (top < 0) null else top..bottom
        }

        /** `[x0, x1]` 这几列里该颜色像素的个数。 */
        fun countInColumns(x0: Int, x1: Int, rgb: Int): Int {
            var n = 0
            for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(w - 1)) {
                for (y in 0 until h) if (at(x, y) == rgb) n++
            }
            return n
        }
    }

    /** 一个配置测完之后的结果。 */
    private class Result(
        val config: Config,
        val frames: Int,
        /** 口径 A：逐帧 `glFinish` 的平均值（毫秒）。 */
        val meanMs: Double,
        val p95Ms: Double,
        /** 口径 B：批量 `glFinish` 的"总时长 / 帧数"（毫秒）。 */
        val batchMs: Double,
        /** 帧周期：口径 A 期间相邻两帧**入口**之间的间隔（毫秒）。见 [framePeriodMs]。 */
        val periodMs: Double,
        /** GPU 计时（`GL_TIME_ELAPSED`，只包住 `charts.draw`）的平均值（毫秒）。 */
        val gpuMs: Double,
        val uploadBytes: Long,
        val nonZeroUploadFrames: Int,
        /** 这一档测完时距探针启动的秒数（THERMAL 模式靠它看"热"了多久）。 */
        val elapsedSec: Double,
    ) {
        val fps: Double get() = if (meanMs > 0) 1000.0 / meanMs else Double.NaN
    }

    private val plotRect = Rect(PLOT_X, PLOT_Y, PLOT_W, PLOT_H)

    override fun start(stage: Stage) {
        // 模式名写错时**大声报错**：静默回落到默认模式的话，一条写着 mode=rmap 的命令
        // 会跑出一整套 full 模式的数字，而它看起来完全正常（本仓库最警惕的形状）。
        require(mode == MODE_FULL || mode == MODE_RAMP || mode == MODE_THERMAL) {
            "$MODE_PROP 只认 $MODE_FULL / $MODE_RAMP / $MODE_THERMAL，实际是 «$mode»"
        }
        val bridge = FXGLTransfer(font = textFont())
        bridge.onFrame { gc -> runFrame(gc) }
        // **onRender 也必须包住**，理由与 runFrame 那里一字不差：
        // GL 线程上一个没接住的异常会让线程静默死掉，JVM 因为"最后一个非守护线程结束"
        // 而以 **0** 退出——一个半途而废的探针报出"成功"，而且它连"我挂了"都不说。
        // 实测过：6M/8px 那一档就是这样消失的（没有汇总、没有栈、退出码 0），
        // 补上这个 try/catch 之后才看得见原因。
        bridge.onRender {
            try {
                runRender()
            } catch (t: Throwable) {
                renderError = t
                println("\n=== onRender 抛出异常，探针中止（配置 ${configs.getOrNull(index)?.label}）===")
                t.printStackTrace()
                printSummary()
                println("=== 探针异常终止（退出码 1）===")
                Platform.exit()
                exitProcess(1)
            }
        }
        transfer = bridge

        installExitDiagnostics()

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "Xuan Chart Perf Probe"
        stage.scene = FxScene(mainView.createMainView(), SCENE_W.toDouble(), SCENE_H.toDouble())
        stage.show()
    }

    /**
     * 退出诊断：**探针曾经静默地死过两次**（没有汇总、没有栈、退出码 0，两次都死在某档
     * 跑完之后），而"什么都没说就结束"与"跑完了"在没有输出的终端里长得一模一样。
     *
     * <p>这里做两件事：
     * <ol>
     *   <li>{@code setImplicitExit(false)}：JavaFX 默认"最后一个窗口关掉就退出整个工具包"，
     *       而那会以退出码 0 静默收场。关掉它之后，若窗口真的被关，探针会**卡住**
     *       并最终撞上 [DEADLINE_MS] 的墙钟上限（退出码 2 + 一份汇总）——失败因此是响的。</li>
     *   <li>注册一个关闭钩子：JVM 一旦开始退出，就把"当时在哪一档、画到第几帧、
     *       以及全部线程的栈"打出来。有栈 ⇒ 是干净的退出（能找到是谁让它退的）；
     *       一条都没有 ⇒ JVM 是被硬杀的（native 崩溃），那结论完全不同。</li>
     * </ol>
     */
    private fun installExitDiagnostics() {
        Platform.setImplicitExit(false)
        Runtime.getRuntime().addShutdownHook(Thread {
            println()
            println("=== JVM 开始退出（退出钩子）===")
            println(
                "当时配置下标 $index / ${configs.size}"
                        + "：${configs.getOrNull(index)?.label ?: "（全部跑完）"}"
                        + "，本档已画 $frameInConfig 帧"
                        + "，已完成 ${results.size} 档"
            )
            for ((thread, stack) in Thread.getAllStackTraces()) {
                if (!thread.isAlive) continue
                println("  线程 [${thread.name}] daemon=${thread.isDaemon} state=${thread.state}")
                for (frame in stack.take(6)) println("      at $frame")
            }
        })
    }

    /**
     * `onFrame` 的入口：绘制期的异常必须被接住，而且**不能静默**。
     *
     * <p>GL 线程上逃出去的异常会让线程静默死掉，汇总与 {@code exitProcess} 都走不到，
     * JVM 因为"最后一个非守护线程结束"而以 <b>0</b> 退出——一个半途而废的探针
     * 报出"成功"。所以这里捕获、打印、以非零码退出（见文件头的退出码说明）。
     */
    private fun runFrame(gc: Gc) {
        try {
            onFrameBody(gc)
        } catch (t: Throwable) {
            drawError = t
            println("\n=== 绘制期抛出异常，探针中止 ===")
            t.printStackTrace()
            println("（已完成的配置见下面的汇总）")
            printSummary()
            println("=== 探针异常终止 ===")
            Platform.exit()
            exitProcess(1)
        }
    }

    private fun onFrameBody(gc: Gc) {
        if (startWall == 0L) startWall = System.currentTimeMillis()
        printEnvironmentOnce(gc)

        // 墙钟上限：见 DEADLINE_MS。它的存在是为了"跑不完时也能拿到已完成的那部分"。
        if (System.currentTimeMillis() - startWall > DEADLINE_MS) {
            println("\n=== 墙钟超时（${DEADLINE_MS / 1000}s），探针没有排完全部配置 ===")
            printSummary()
            println(
                "=== 以下配置没跑：${
                configs.drop(index).joinToString("；") { it.label }
            } ===")
            Platform.exit()
            exitProcess(2)
        }

        val cfg = configs.getOrNull(index)
        if (cfg == null) {
            printSummary()
            println("=== 探针跑完（数字都在上面，本行不是「通过」的意思）===")
            Platform.exit()
            exitProcess(0)
        }

        if (!prepared) {
            prepare(cfg)
            prepared = true
            frameInConfig = 0
            sampleCount = 0
            batchStartNs = 0L
            batchFrames = 0
            batchTotalNs = 0L
            batchFinishing = false
            gpuSampleCount = 0
            gpuFrameNs = -1L
            uploadedBytes = 0L
            nonZeroUploadFrames = 0
        }

        // 帧入口时刻：帧周期（相邻两帧入口的间隔）就是由它算的。
        // 它**必须**在 drawFrame 之前取——那才是"这一帧开始了"。
        frameStartNs = System.nanoTime()
        if (frameInConfig < frameStarts.size) frameStarts[frameInConfig] = frameStartNs

        drawFrame(gc)

        // 上传字节每帧都要问一次：takeUploadedBytes 是"取走即清零"的语义，
        // 只在测量帧问的话，预热期那一批（N × 4 字节）会落到第一个测量帧头上。
        val bytes = scene?.let { gc.charts.takeUploadedBytes(it.series) } ?: 0

        // GPU 计时：本帧读到的是**上一帧**那一段的用时（本帧的已经在上面的 drawFrame 里开了）。
        // 只在计时器打开的那些帧（口径 A）上记：口径 B 里没有逐帧 glFinish，
        // 结果不一定已经可用，读它会变成一次隐式同步——那会把口径 B 悄悄变回口径 A。
        if (gpuTimerActiveFrame() && gpuFrameNs >= 0 && gpuSampleCount < gpuSamples.size) {
            gpuSamples[gpuSampleCount++] = gpuFrameNs
        }

        if (mode != MODE_RAMP) {
            if (frameInConfig >= WARMUP_FRAMES) {
                uploadedBytes += bytes
                if (bytes != 0) nonZeroUploadFrames++
            }
            if (frameInConfig in WARMUP_FRAMES until (WARMUP_FRAMES + MEASURE_FRAMES)) {
                // 口径 A：这一帧要在 runRender 的 glFinish 之后记终点。
                t0 = System.nanoTime()
                timing = true
            } else if (frameInConfig in (WARMUP_FRAMES + MEASURE_FRAMES) until FRAMES_PER_CONFIG) {
                // 口径 B：起点取**第一帧的入口**，终点在最后一帧之后那唯一一次 glFinish。
                if (frameInConfig == WARMUP_FRAMES + MEASURE_FRAMES) batchStartNs = frameStartNs
                batchFrames++
                if (frameInConfig == FRAMES_PER_CONFIG - 1) batchFinishing = true
            }
        }
        frameInConfig++
    }

    /**
     * `onRender` 的入口：等 GPU、记终点。
     *
     * <p>{@code glFinish} 直接调 LWJGL（{@code xuan-javafx} 的传递依赖里有它），
     * <b>没有为它去改 {@code GLAbstraction} 接口</b>——那会牵动 {@code FakeGLAbstraction}
     * 与它的护栏测试，本任务只该新增一个文件。
     */
    private fun runRender() {
        if (mode == MODE_RAMP) {
            onRampRender()
            return
        }
        if (timing) {
            // 口径 A：这一帧的终点。
            timing = false
            glFinish()
            val dt = System.nanoTime() - t0
            if (sampleCount < MEASURE_FRAMES) {
                samples[sampleCount++] = dt
            }
            return
        }
        if (batchFinishing) {
            // 口径 B 的**唯一**一次等待：前面 [BATCH_FRAMES] 帧的命令全都还在流水线上
            // （连一次 glFinish 都没做过），所以这一次等到的是一整批。
            batchFinishing = false
            glFinish()
            batchTotalNs = System.nanoTime() - batchStartNs
            finishCurrent()
        }
    }

    /**
     * 斜坡观测的 `onRender`：最后一帧把绘图区那一块回读下来、打表，然后进下一档。
     *
     * <p>回读前必须 `glFinish()`：本项目的 GL 命令是异步的，不等它落地就 `glReadPixels`，
     * 读到的是**上一帧**的内容——而斜坡每档只画 4 帧，上一帧还是"上一档的图"。
     */
    private fun onRampRender() {
        glFinish()
        if (frameInConfig < RAMP_FRAMES) return
        val cfg = configs[index]
        try {
            printRampTable(cfg, grabPlot())
        } finally {
            index++
            prepared = false
        }
    }

    /**
     * 换配置时的准备工作。
     *
     * <p>它<b>只在换 N 时重建场景</b>：两个线宽配置共用一个 Scene
     * （理由见 [Scene]），换线宽只改一下 {@code series.lineWidth()}。
     *
     * <p>空帧基线（`n == 0`）刻意把场景设成 null：这样它与别的配置之间
     * <b>逐项相同</b>，只差一次 {@code charts.draw}——于是"基线减出来的那个差"
     * 就是图表路径的成本，不必再去猜 {@code Gc} 那一小块底色的开销有多少。
     */
    private fun prepare(cfg: Config) {
        if (cfg.n == 0) {
            scene = null
            return
        }
        if (scene?.n != cfg.n || scene?.ramp != cfg.ramp) {
            scene = buildScene(cfg.n, cfg.ramp)
        }
        val s = scene!!
        s.series.lineWidth(cfg.lineWidth)
        // x 轴窗口：默认是整个数据范围；[Config.visible] > 0 时只留最后那么多个样本。
        // **窗口只是显示窗口**（轴不持有数据），所以这一改不动缓冲、也不触发任何上传——
        // "可见窗口 A/B"那一组要的正是这个：唯一的变量是画出来的实例数。
        val windowEnd = (cfg.n - 1).toDouble()
        val windowStart = if (cfg.visible > 0) (cfg.n - cfg.visible).toDouble() else 0.0
        s.xAxis.setWindow(windowStart, windowEnd)
    }

    /**
     * 造一个 N 个点的场景：x 等距 0..N、y 是"正弦叠加随机窄脉冲"或一条线性斜坡。
     *
     * <p><b>为什么默认的 y 要有毛刺</b>：任务的背景是"抽稀会不会丢毛刺"，
     * 留一条有毛刺的信号，是为了将来真做降采样时能拿同一份数据去问
     * "抽稀之后毛刺还在不在"——那是像素口径的事（`ChartVerifier` 那种），不是本文件的事。
     *
     * <p><b>为什么斜坡那一支没有毛刺</b>：斜坡观测**刻意**只要一个形状——
     * 线性上升。它要读的量是"这条线在某条 x 列上到了多高"，
     * 任何第二个形状都会让那个读数变成两件事的叠加（见 [printRampTable]）。
     * 所以 `ramp` 是一支独立的输入，不是"把毛刺关掉"。
     *
     * <p>毛刺用固定种子（[SPIKE_SEED]），宽度一个样本、幅度 ±0.5，
     * 于是各个 N 的输入是同一套规则的放大版，可比。
     */
    private fun buildScene(n: Int, ramp: Boolean): Scene {
        val y = if (ramp) {
            DoubleArray(n) { RAMP_LO + (RAMP_HI - RAMP_LO) * it / (n - 1).toDouble() }
        } else {
            buildSignal(n)
        }
        val x = DoubleArray(n) { it.toDouble() }
        val data = ArrayChartData(
            arrayOf(
                AxisRange(0.0, (n - 1).toDouble(), "样本", ""),
                AxisRange(0.0, 1.0, "值", "")
            ),
            arrayOf(x, y)
        )
        // x 轴的窗口就是整个数据范围：可见实例数因此 = N - 1（最后一个线段要右端已采到）。
        val xAxis = Axis(AxisType.LINEAR, data.axisRange(0))
            .setDisplayLength(PLOT_W.toDouble())
            .setWindow(0.0, (n - 1).toDouble())
        val yAxis = Axis(AxisType.LINEAR, data.axisRange(1))
            .setDisplayLength(PLOT_H.toDouble())
        val series = Series("性能探针 N=$n", data, ChartType.LINE).color(SIGNAL_ARGB)
        val chart = Chart(xAxis, yAxis)
        chart.addLayer("性能").add(series)
        return Scene(n, ramp, series, chart, xAxis)
    }

    /** 生成"正弦 + 随机窄脉冲"的信号，值域 [0, 1]。 */
    private fun buildSignal(n: Int): DoubleArray {
        val rnd = Random(SPIKE_SEED)
        val y = DoubleArray(n)
        for (i in 0 until n) {
            y[i] = 0.5 + 0.45 * sin(2.0 * PI * i / SIGNAL_PERIOD)
        }
        val spikes = (n / SPIKES_PER).coerceAtLeast(1)
        repeat(spikes) {
            val i = rnd.nextInt(n)
            val sign = if (rnd.nextBoolean()) 1.0 else -1.0
            y[i] = (y[i] + sign * 0.5).coerceIn(0.0, 1.0)
        }
        return y
    }

    /**
     * 一帧的场景。**只有两块东西**：绘图区底色（普通 Gc 图元）与数据系列。
     *
     * <p>{@code gc.flush()} 不可省：数据系列是**当场就画**的（不走 {@code RenderBatch}
     * 的顶点缓冲），不 flush 的话底色会盖在曲线上——数字当然测不出来，
     * 但那是"测了一个不成立的场景"。
     */
    private fun drawFrame(gc: Gc) {
        gc.fill = PLOT_BG
        gc.fillRect(PLOT_X, PLOT_Y, PLOT_W, PLOT_H)
        gc.flush()
        val s = scene ?: return
        val timerOn = gpuTimerActiveFrame()
        if (timerOn) pollGpuTimer()
        if (timerOn) beginGpuTimer()
        try {
            gc.charts.draw(s.chart, plotRect, gc.width, gc.height)
        } finally {
            // 必须配对：一个开着的查询会一直计到下一次 glEndQuery，
            // 中途抛出去的话那个数会横跨好几帧（而且完全看不出来）。
            if (timerOn) endGpuTimer()
        }
    }

    // -----------------------------------------------------------------------
    // GPU 计时查询（GL_TIME_ELAPSED）
    //
    // 它只包住 charts.draw 那一段，量的是"这条绘制命令在 GPU 上执行了多久"。
    // 这是**唯一**既不受 JavaFX 脉冲影响、也不受"每帧等一次 glFinish"影响的量：
    // 口径 A 量的是"从记完命令到 GPU 排干"，其中掺着脉冲与别的固定成本；
    // 这一档把这些都排除掉了。所以"平顶"是不是真的 GPU 侧饱和，看它最直接。
    //
    // 它自己的任何失败都不许影响绘制路径（见 gpuTimerEnabled 的说明）。
    // -----------------------------------------------------------------------

    /** 只在口径 A 的那些帧上开计时。理由见 [onFrameBody] 里那段说明。 */
    private fun gpuTimerActiveFrame(): Boolean =
        mode != MODE_RAMP && gpuTimerEnabled &&
                frameInConfig in WARMUP_FRAMES until (WARMUP_FRAMES + MEASURE_FRAMES)

    /**
     * 第一次要用的时候才建查询对象——GL 上下文必须已 current，
     * 而 [printEnvironmentOnce] 是每帧第一件被调用的事、又在 GL 线程上。
     */
    private fun ensureGpuTimer() {
        if (!gpuTimerWanted || gpuTimerEnabled || gpuQuery != 0) return
        try {
            gpuQuery = GL15.glGenQueries()
            gpuTimerEnabled = gpuQuery != 0
        } catch (t: Throwable) {
            println("（GPU 计时查询创建失败，本次不用它：$t）")
        }
    }

    /**
     * 读上一帧那次查询的结果（纳秒）。
     *
     * <p>只可能在口径 A 期间被调用：那一档每帧都以 `glFinish` 收尾，
     * 所以结果必定已经可用，读它**不会**引入新的同步。
     */
    private fun pollGpuTimer() {
        if (!gpuQueryPending) return
        gpuQueryPending = false
        try {
            gpuFrameNs = GL33.glGetQueryObjectui64(gpuQuery, GL15.GL_QUERY_RESULT)
        } catch (t: Throwable) {
            gpuTimerEnabled = false
            println("（GPU 计时查询读取失败，后面不再用它：$t）")
        }
    }

    private fun beginGpuTimer() {
        try {
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, gpuQuery)
            gpuQueryActive = true
        } catch (t: Throwable) {
            gpuTimerEnabled = false
            println("（GPU 计时查询开启失败，后面不再用它：$t）")
        }
    }

    private fun endGpuTimer() {
        if (!gpuQueryActive) return
        gpuQueryActive = false
        try {
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED)
            gpuQueryPending = true
        } catch (t: Throwable) {
            gpuTimerEnabled = false
            println("（GPU 计时查询关闭失败，后面不再用它：$t）")
        }
    }

    /**
     * 把绘图区那一块回读下来（与 `ChartVerifier.grab` 同一个口径：
     * `glReadPixels` 的原点在左下、y 向上，而本项目用户坐标原点在左上，所以要翻两趟）。
     *
     * @return 行优先、**从上到下**的 RGB（不含 alpha）
     */
    private fun grabPlot(): Shot {
        val bridge = transfer ?: throw IllegalStateException("GL 还没初始化")
        val h = bridge.scaledHeight
        val rw = PLOT_W.toInt()
        val rh = PLOT_H.toInt()
        val buf = ByteBuffer.allocateDirect(rw * rh * 4)
        glReadPixels(
            PLOT_X.toInt(), h - PLOT_Y.toInt() - rh, rw, rh,
            GL_RGBA, GL_UNSIGNED_BYTE, buf
        )
        buf.position(0)
        val px = IntArray(rw * rh)
        for (row in 0 until rh) {
            val glRow = rh - 1 - row
            for (col in 0 until rw) {
                val i = (glRow * rw + col) * 4
                px[row * rw + col] =
                    ((buf.get(i).toInt() and 0xFF) shl 16) or
                            ((buf.get(i + 1).toInt() and 0xFF) shl 8) or
                            (buf.get(i + 2).toInt() and 0xFF)
            }
        }
        return Shot(rw, rh, px)
    }

    /** 帧缓冲尺寸与 GPU 型号：性能数字没有它们就没法复现。 */
    private fun printEnvironmentOnce(gc: Gc) {
        if (printedEnvironment) return
        printedEnvironment = true
        val bridge = transfer ?: return
        ensureGpuTimer()
        println("=== Xuan 折线渲染器性能探针 ===")
        println(
            "模式 = $mode   配置数 = ${configs.size}   轮数 = $rounds"
                    + (if (warmCounts.isNotEmpty()) "   预热档 = ${warmCounts.joinToString()}" else "")
        )
        println(
            "帧缓冲 ${bridge.scaledWidth}x${bridge.scaledHeight}"
                    + "（Gc ${gc.width}x${gc.height}）"
                    + "  绘图区 ${PLOT_W.toInt()}x${PLOT_H.toInt()}"
        )
        println("GL_RENDERER = ${glGetString(GL_RENDERER)}")
        println("GL_VERSION  = ${glGetString(GL_VERSION)}")
        println(
            "GPU 计时（GL_TIME_ELAPSED） = "
                    + if (gpuTimerEnabled) "可用（只包住 charts.draw）"
            else "不可用（这一档没有 GPU 数字）"
        )
        println(
            "配置：N ∈ ${POINT_COUNTS.joinToString()}，线宽 ∈ ${LINE_WIDTHS.joinToString()}"
                    + "，预热 $WARMUP_FRAMES 帧、口径A $MEASURE_FRAMES 帧、口径B $BATCH_FRAMES 帧"
        )
        println(
            "数据：ArrayChartData（静态），x 等距 0..N，y = 正弦(周期 $SIGNAL_PERIOD 样本)"
                    + " + 每 $SPIKES_PER 个样本一个随机窄脉冲；x 轴窗口 = 整个数据范围"
        )
        println()
        println("口径 A（与上一轮数字可比）：起点在 onFrame 的**末尾**（`t0 = nanoTime()` 排在")
        println("    drawFrame 与 takeUploadedBytes 之后）→ 终点在 onRender 里 glFinish() 之后。")
        println("    也就是说：**CPU 侧记命令的时间不在里面**，量到的就是\"GPU 把这段命令排干\"。")
        println("    （文件头那段伪代码写的是\"onFrame 第一行\"，与代码不符——以这里为准。）")
        println("口径 B：连续 $BATCH_FRAMES 帧只在最后 glFinish 一次，总时长 / 帧数。")
        println("帧周期：相邻两帧**入口**的间隔。由 FXGLTransfer 的驱动方式决定（JavaFX 脉冲）。")
        println("    ★ 口径 A 远小于帧周期时，循环有大量余量；两者接近时才是工作受限。")
        println("GPU：GL_TIME_ELAPSED 查询，只包住 charts.draw（含它的 ID pass）。")
        println("★ 每帧一次 glFinish 会把帧串行化，口径 A 请按\"一帧的工作\"读，不要当成帧率上限")
        println()
    }

    /** 一个配置测完（口径 B 那唯一一次 `glFinish` 之后）：算统计、打印、进下一个。 */
    private fun finishCurrent() {
        val cfg = configs[index]
        val n = sampleCount
        val sorted = samples.copyOf(n).also { it.sort() }
        val meanNs = samples.take(n).average()
        val p95 = percentile(sorted, 0.95)
        val r = Result(
            config = cfg,
            frames = n,
            meanMs = meanNs / 1e6,
            p95Ms = p95 / 1e6,
            batchMs = if (batchFrames > 0) batchTotalNs / 1e6 / batchFrames else Double.NaN,
            periodMs = framePeriodMs(),
            gpuMs = if (gpuSampleCount > 0) {
                gpuSamples.take(gpuSampleCount).average() / 1e6
            } else {
                Double.NaN
            },
            uploadBytes = uploadedBytes,
            nonZeroUploadFrames = nonZeroUploadFrames,
            elapsedSec = (System.currentTimeMillis() - startWall) / 1000.0,
        )
        results.add(r)
        println(formatLine(r))
        // GL 错误必须在这里问一次：一个失败的 draw call（例如实例数超限、属性没配好）
        // 在画面上留下的是"少画了一部分"，而在时间上留下的正是**变快**——
        // 也就是说它会把"截断"伪装成"平顶"，且完全安静。
        val err = glGetError()
        if (err != 0) println("    ★ glGetError=$err（这一档的绘制可能压根没执行完）")
        index++
        prepared = false
        timing = false
        batchFinishing = false
        gpuQueryPending = false
        gpuQueryActive = false
        gpuFrameNs = -1L
    }

    private fun percentile(sorted: LongArray, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        // 最近秩法：第 ceil(p·n) 个（1 起算）。
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1].toDouble()
    }

    /**
     * 帧周期：口径 A 那一段里相邻两帧**入口**之间的平均间隔（毫秒）。
     *
     * <p>它是判断"这一档到底受什么限制"的关键量：口径 A 量的是"一帧的工作"，
     * 帧周期量的是"一帧的工作 + 等下一次脉冲的空档"。两者差着多少，
     * 就是这套循环还有多少余量。**它与 GPU 忙不忙无关**，由 FXGLTransfer 的驱动方式支配。
     */
    private fun framePeriodMs(): Double {
        val from = WARMUP_FRAMES
        val to = WARMUP_FRAMES + MEASURE_FRAMES - 1
        if (to <= from) return Double.NaN
        var sum = 0L
        var cnt = 0
        for (i in from until to) {
            val dt = frameStarts[i + 1] - frameStarts[i]
            if (dt > 0) {
                sum += dt
                cnt++
            }
        }
        return if (cnt == 0) Double.NaN else sum.toDouble() / cnt / 1e6
    }

    /** 数值打印：NaN 打成 n/a（这一档没量到），**不要**打成 0.00——那是"量到了 0"。 */
    private fun num(v: Double): String = if (v.isNaN()) "n/a" else "%.2f".format(v)

    private fun formatLine(r: Result): String =
        "[${r.config.label}] 口径A ${"%.2f".format(r.meanMs)} ms" +
                "  p95 ${"%.2f".format(r.p95Ms)} ms" +
                "  折算 ${"%.1f".format(r.fps)} fps" +
                "  （${r.frames} 帧）" +
                "  ★口径B ${num(r.batchMs)} ms/帧" +
                "  帧周期 ${num(r.periodMs)} ms" +
                "  ★GPU ${num(r.gpuMs)} ms" +
                "  每帧上传 ${r.uploadBytes / r.frames} B" +
                "（非零帧 ${r.nonZeroUploadFrames}/${r.frames}）" +
                "  距启动 ${"%.0f".format(r.elapsedSec)}s"

    /**
     * 汇总：逐行重印，再按 N 给出"填充 vs 顶点/实例/提交"的拆分，
     * 最后是这一轮新增的三张观测表（可见窗口 A/B、倒序第二轮）。
     *
     * <p>拆分是两次相减：[LINE_WIDTHS] 里粗的减细的 ≈ 光栅化填充成本；
     * 细的减 [空帧基线] ≈ 顶点/实例/提交成本。基线缺席（超时）时后者不打印，
     * 免得读者拿 [PLOT_W] 那块底色的开销当成图表开销。
     */
    private fun printSummary() {
        if (results.isEmpty()) {
            println("（没有任何配置跑完）")
            return
        }
        println()
        println("=== 汇总 ===")
        // 被 onRender 的异常中止时，先把原因说出来——否则这份汇总看着像"跑完了"，
        // 而它其实只到某一档为止（这是本仓库最忌讳的那种报告）。
        renderError?.let {
            println("★ onRender 曾抛出异常，探针是被它中止的（数字只到下面最后一行为止）")
            it.printStackTrace()
        }
        results.forEach { println(formatLine(it)) }

        val baseline = results.firstOrNull { it.config.n == 0 }
        println()
        println("=== 拆分（同一 N、同一份数据、同样实例数）===")
        if (baseline == null) {
            println(
                "本模式没有空帧基线（只有 full 模式排了它），这一节跳过；"
                        + "每行的口径A / 批量 / 帧周期 / GPU 数字仍然可比。"
            )
        } else {
            println(
                "空帧基线：口径A ${"%.2f".format(baseline.meanMs)} ms"
                        + "（口径B ${num(baseline.batchMs)}，帧周期 ${num(baseline.periodMs)}，"
                        + "GPU ${num(baseline.gpuMs)}）"
                        + "——只有绘图区底色那 2 个三角形，没有任何图表绘制"
            )
            for (n in POINT_COUNTS) {
                // 保持 LINE_WIDTHS 的顺序：先细后粗。只取"全窗口 + 第一轮"那一份，
                // 否则可见窗口 A/B 那几档（同一个 N、同一个线宽）会被 firstOrNull 挑中。
                val byWidth = LINE_WIDTHS.toList().mapNotNull { w ->
                    results.firstOrNull {
                        it.config.n == n && it.config.lineWidth == w
                                && it.config.visible == 0 && it.config.round == 0
                    }
                }
                if (byWidth.size < LINE_WIDTHS.size) {
                    println("N=$n：线宽两个样本没凑齐，跳过")
                    continue
                }
                val thin = byWidth[0]
                val thick = byWidth[1]
                val fill = thick.meanMs - thin.meanMs
                val vertexCost = thin.meanMs - baseline.meanMs
                println(
                    "N=$n：口径A 1px ${"%.2f".format(thin.meanMs)}"
                            + " ｜ 8px ${"%.2f".format(thick.meanMs)}"
                            + " ｜ 差（≈光栅化填充）${"%+.2f".format(fill)}"
                            + " ｜ 1px−基线（≈顶点/实例/提交）${"%.2f".format(vertexCost)}"
                            + " ｜ 批量 ${num(thin.batchMs)} ｜ 帧周期 ${num(thin.periodMs)}"
                            + " ｜ ★GPU 1px ${num(thin.gpuMs)} / 8px ${num(thick.gpuMs)}"
                )
            }
        }

        // ★ 可见窗口 A/B：实例数变了 3 倍 / 10 倍，时间跟着变了吗？
        val ab = results.filter {
            it.config.n == 3_000_000 && it.config.visible > 0 && it.config.lineWidth == 1f
        }
        if (ab.isNotEmpty()) {
            println()
            println("=== ★ 可见窗口 A/B（N=3M、同一份数据、同一块缓冲，只有画出来的实例数不同）===")
            val full = results.firstOrNull {
                it.config.n == 3_000_000 && it.config.visible == 0
                        && it.config.lineWidth == 1f && it.config.round == 0
            }
            full?.let {
                println(
                    "  可见 3000000 个样本（全窗口）：口径A ${"%.2f".format(it.meanMs)} ms"
                            + " ｜ GPU ${num(it.gpuMs)} ms ｜ 帧周期 ${num(it.periodMs)}"
                )
            }
            for (r in ab.sortedByDescending { it.config.visible }) {
                println(
                    "  可见 ${r.config.visible} 个样本：口径A ${"%.2f".format(r.meanMs)} ms"
                            + " ｜ GPU ${num(r.gpuMs)} ms ｜ 批量 ${num(r.batchMs)}"
                            + " ｜ 帧周期 ${num(r.periodMs)}"
                )
            }
            println("  判据：口径A 与 GPU 若跟着实例数成比例地变 ⇒ 成本**真的在实例上**；")
            println("        若三个数几乎相同 ⇒ 成本与实例数无关，那个固定值才是平顶的成因。")
        }

        // ★ 倒序第二轮：同一档在两轮里的差，就是时间漂移（掉频/升温）。
        if (rounds > 1) {
            println()
            println("=== ★ 第二轮（倒序）与第一轮的差 = 时间漂移 ===")
            for (n in POINT_COUNTS) for (w in LINE_WIDTHS) {
                val r0 = results.firstOrNull {
                    it.config.n == n && it.config.lineWidth == w && it.config.round == 0
                }
                val r1 = results.firstOrNull {
                    it.config.n == n && it.config.lineWidth == w && it.config.round == 1
                }
                if (r0 != null && r1 != null) {
                    println(
                        "  N=$n ${Config.trim(w)}px：第1轮 ${"%.2f".format(r0.meanMs)} ms"
                                + "（距启动 ${"%.0f".format(r0.elapsedSec)}s）"
                                + " → 第2轮（倒序、后跑）${"%.2f".format(r1.meanMs)} ms"
                                + "（距启动 ${"%.0f".format(r1.elapsedSec)}s）"
                                + "  差 ${"%+.2f".format(r1.meanMs - r0.meanMs)} ms"
                    )
                }
            }
        }

        println()
        println("提醒：口径A 是「一帧的工作」，帧周期是「一帧的工作 + 等下一次脉冲的空档」。")
        println("      口径A 远小于帧周期 ⇒ 循环有大量余量（这一档不是被帧率压住的）；")
        println("      两者接近 ⇒ 循环工作受限，那时口径A 才是真正的天花板。")
        println("      折算 fps 是渲染吞吐（1000/口径A），不是显示帧率。")
    }

    /**
     * 斜坡观测的结果表。**它不看时间**，只看"每条探测列上最上面那个有色像素在哪一行"。
     *
     * <h2>为什么这个量就是"画了多少比例"</h2>
     * <p>数据是一条从 [RAMP_LO] 线性升到 [RAMP_HI] 的斜坡，x 轴窗口是整个数据范围。
     * 于是绘图区里那条线的斜率**就是**实际画出来的数据比例：
     * <ul>
     *   <li>实例全画了 → 线从底升到顶，每条探测列上的实测值一路升到约 [RAMP_HI]；</li>
     *   <li>实例被截断在 1/3 处 → 前 1/3 是正常的斜坡，**后面那些列一个墨迹都没有**；</li>
     *   <li>画的是别的 x 区间（窗口被裁剪）→ 左边一段是空的，墨迹从某个 x 开始。</li>
     * </ul>
     * 所以表里除了"实测值"还有一列"该列±3px 有几行墨"：**"提前变平"与"提前消失"
     * 是两件不同的事**，前者说明画到那里就没数据了，后者说明那一列压根没被画。
     *
     * <h2>它刻意不看时间</h2>
     * <p>这套观测的全部价值在于它与计时口径**无关**。计时正是"平顶"里可疑的那个量，
     * 拿它来判断"是不是被截断了"是循环论证。像素不会说谎：少画的实例在画面上就是没有。
     *
     * @param shot 绘图区那一块的回读（局部坐标，左上角为原点）
     */
    private fun printRampTable(cfg: Config, shot: Shot) {
        val width = cfg.lineWidth
        // 一条铺满绘图区宽度的斜线：每列有 `线宽 + 每列上升的行数` 行墨。
        val rowsPerColumn = width + (RAMP_HI - RAMP_LO) * PLOT_H / PLOT_W
        val expectedInk = (PLOT_W * rowsPerColumn).toInt()
        println()
        println("=== 斜坡观测 N=${cfg.n}  lineWidth=${Config.trim(width)}px ===")
        println(
            "数据：y 从 $RAMP_LO 线性升到 $RAMP_HI（没有别的形状）；"
                    + "x 轴窗口 = 整个数据范围 0..${cfg.n - 1}"
        )
        println("绘图区 ${PLOT_W.toInt()}x${PLOT_H.toInt()}（回读的就是这一块，局部坐标）")
        val ink = shot.count(SIGNAL_RGB)
        println(
            "这一块里折线色像素共 $ink px"
                    + "（一条铺满 ${PLOT_W.toInt()} px 宽、线宽 ${Config.trim(width)}px 的斜线约 $expectedInk px）"
        )
        if (ink == 0) {
            println("★ 一个像素都没有：这一档压根没画出东西（或画的不是这个颜色）")
        } else if (ink < expectedInk / 4) {
            println(
                "★ 墨迹只有预期的一个零头：这条线是**断的/稀疏的**"
                        + "（每个实例的四边形都退化成亚像素大小，光栅化可能几乎不出片元）"
            )
        }
        println()
        println("  x比例 ｜  列 ｜ 期望值 ｜ 实测值 ｜  偏差   ｜ 该列±3px：墨迹行范围 / 墨迹px数")
        for (f in RAMP_PROBE_FRACTIONS) {
            val col = (f * (PLOT_W - 1)).roundToInt()
            val band = shot.inkRange(col - 3, col + 3, SIGNAL_RGB)
            val bandPx = shot.countInColumns(col - 3, col + 3, SIGNAL_RGB)
            val expected = RAMP_LO + (RAMP_HI - RAMP_LO) * f
            val mark = if (f == 0.1 || f == 0.3 || f == 0.5 || f == 0.7 || f == 0.9) "★" else " "
            if (band == null) {
                println(
                    "$mark ${"%5.1f%%".format(f * 100)} ｜ ${"%4d".format(col)} ｜ "
                            + "${"%6.3f".format(expected)} ｜   无墨迹 ｜     —     ｜ — / 0"
                )
            } else {
                // 局部行 → 数据值：行 0 = 绘图区上边缘 = y 窗口上界（值 1.0）。
                val measured = 1.0 - band.first / PLOT_H.toDouble()
                println(
                    "$mark ${"%5.1f%%".format(f * 100)} ｜ ${"%4d".format(col)} ｜ "
                            + "${"%6.3f".format(expected)} ｜ ${"%6.3f".format(measured)} ｜ "
                            + "${"%+.3f".format(measured - expected)} ｜ 行 ${band.first}..${band.last} / $bandPx"
                )
            }
        }
        // 偏置的解析式：实测取的是「±3 列里最上面那个有色像素」，也就是
        //   （最右那一列的中心线高度）+（半个线宽）——两者都要换算成"值"。
        val bias = (width * 0.5 + 3 * (RAMP_HI - RAMP_LO) * PLOT_H / PLOT_W) / PLOT_H
        println("说明：实测值取「该列±3px 里最上面那个有色像素」，因此系统性地偏高约")
        println(
            "      (半个线宽 + 3 列的斜率) / 绘图区高度 = ${"%.4f".format(bias)}"
                    + "（线宽 ${Config.trim(width)}px 的解析值）——"
                    + "整条线上「偏差」那一列应当是**同一个常数**。"
        )
        println("      ★ 的五行是任务点名要的 10%/30%/50%/70%/90%。")
    }

    override fun stop() {
        transfer?.dispose()
    }
}
