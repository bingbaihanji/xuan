package com.bingbaihanji.xuan.example.demo

import com.bingbaihanji.xuan.chart.Axis
import com.bingbaihanji.xuan.chart.AxisRange
import com.bingbaihanji.xuan.chart.AxisStyle
import com.bingbaihanji.xuan.chart.AxisType
import com.bingbaihanji.xuan.chart.Chart
import com.bingbaihanji.xuan.chart.ChartInsets
import com.bingbaihanji.xuan.chart.ChartLayout
import com.bingbaihanji.xuan.chart.ChartSide
import com.bingbaihanji.xuan.chart.ChartTextMetrics
import com.bingbaihanji.xuan.chart.ChartType
import com.bingbaihanji.xuan.chart.RingChartData
import com.bingbaihanji.xuan.chart.Series
import com.bingbaihanji.xuan.example.audio.AudioSource
import com.bingbaihanji.xuan.example.audio.FileEnvelope
import com.bingbaihanji.xuan.example.audio.HistoryEnvelope
import com.bingbaihanji.xuan.example.audio.MicrophoneSource
import com.bingbaihanji.xuan.example.audio.WavFileSource
import com.bingbaihanji.xuan.example.audio.sliderToDb
import com.bingbaihanji.xuan.example.textFont
import com.bingbaihanji.xuan.glview.FXGLTransfer
import com.bingbaihanji.xuan.gpu.FftWindow
import com.bingbaihanji.xuan.renderer.Gc
import com.bingbaihanji.xuan.util.Rect
import com.bingbaihanji.xuan.view.MainView
import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.Separator
import javafx.scene.control.Slider
import javafx.scene.control.ToggleGroup
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Stage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 音频 FFT 演示：打开 WAV 文件或从麦克风实时采集，听 WAV 的声音，看时域波形 / 频谱折线 / 声谱图。
 *
 * <h2>它是什么、不是什么</h2>
 * 这是一个**有窗口、有按钮、有文件对话框、能用鼠标操作**的桌面应用，不是自动跑一遍就退出的
 * 校验器。频谱折线与声谱图共用同一份 `RingChartData`（见规格 §4：音频采集是单写者）；
 * 波形视图**不走环**，它画的是整个文件 / 整段累积历史，见下。
 *
 * <h2>声音（WAV 文件这一路）</h2>
 * 打开 WAV 就**直接开始播放**（`WavFileSource.attachPlayback` → `PcmOutput` 的
 * `SourceDataLine`），控制条上有播放/暂停与音量。换来的是**播放头有了真实来源**：
 * 那条竖线的位置读的是声卡真正播出去的帧数，不再靠墙钟猜（理由见 `PcmOutput` 的类注释）。
 *
 * 拿不到输出设备时**不静默**：状态栏与 stderr 各写一句为什么，播放/暂停与音量控件随之禁用，
 * 波形、频谱、声谱图照常（退化成墙钟驱动的播放头）。
 *
 * **麦克风不做监听回放**（即"边采边从扬声器放出来"）：那是一条扬声器 → 空气 → 麦克风的
 * 正反馈回路，几毫秒内就会啸叫到满量程。要监听得戴耳机，而那已经超出本 demo 的范围。
 *
 * <h2>波形视图画什么（与频谱那两个视图不是一条路）</h2>
 * 环只有 4096 个样本（≈93 ms），装不下一个 3 分钟的文件（800 万个样本），也装不下
 * 麦克风的无限历史。所以波形视图自己按"屏幕像素列"把样本压成 min/max 包络再画：
 * - **文件**：整个文件铺满绘图区，配一条随播放走的竖线；点波形上某处跳到那里继续播。
 * - **麦克风**：左 1/3 是累积历史（边采边压成 min/max，内存固定），右 2/3 是
 *   全分辨率的实时波形（1 列 = 1 样本，取自环里最近的那一段）。
 *
 * <h2>怎么跑</h2>
 * `mvn exec:java` 在本项目不可用（openglfx 类加载器冲突），必须 fork 独立 JVM：
 * ```
 * cd xuan-javafx
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
 *      -Dxuan.text.font=C:/Windows/Fonts/Candara.ttf \
 *      -cp %classpath com.bingbaihanji.xuan.example.demo.AudioDemoKt"
 * ```
 * 字体自 2026-09-28 起不再自带，**不给 `-Dxuan.text.font` 就没有轴标签**（本文件会退化成
 * 只画图不画字，并在启动时往 stderr 打一行说明，而不是每帧抛异常）。
 *
 * 本应用没有任何自动退出路径（它就是给人用的）。要在自动化里跑一遍确认"没抛异常"，
 * 加 `-Dxuan.audio.smoke=<毫秒>`：到点自己退出，并把**渲染异常与读数超时**折成退出码
 * （见 [armSmokeExit]）。顺带打出两轮播放读数（播放头 / 声卡帧位置 / 墙钟估算），
 * 那是"声音真的在走"唯一拿得到的实测证据——"没报错"与"声音出来了"是两回事。
 *
 * <h2>线程模型（照规格 §5，别改）</h2>
 * - 采集线程只把样本塞进 [AudioSource] 内部的有界队列；**采集线程一次都不碰 `Gc`**。
 * - `drainInto` 只在 GL 线程（`onFrame` 回调）里调用，它把新样本追加进 `RingChartData`
 *   与麦克风的历史包络；于是两者的**唯一写者就是 GL 线程**（既是写者也是读者），
 *   比 SPSC 还强一条。
 * - JavaFX 线程只做三件事：搭窗口、接控件事件、换源。它一次都不碰 `Gc` / 环 / `Axis`。
 *   唯一的例外是波形视图的点击处理器——它只读一个 `@Volatile` 的绘图区矩形与源的
 *   播放位置，然后调 `startAt`；这三样都是为它专门暴露的、不涉及 GL。
 * - 播放那一条线程（`xuan-pcm-writer`）在 `PcmOutput` 内部，**只碰声卡**：
 *   它不碰 `Gc`、不碰环、也不碰本类的绘制状态。这里读的播放位置来自 `source` 上的两个
 *   `@Volatile` 读数，不涉及 GL。
 *
 * <h2>换源为什么要重建整个场景</h2>
 * [AudioScene] 把「源 + 环 + 两张图 + 波形数据」绑成一个**不可变快照**，用
 * `AtomicReference` 整体发布。
 * 两个理由：
 * 1. `SeriesBuffer` 对"写入总数倒退"是**明确抛异常**的，所以换了数据源就必须换环
 *    （新环从 0 开始写）；而环换了，按它建出来的 `Series` / `Chart` 也必须跟着换。
 * 2. 分开发布（先换环、再换图）会让 GL 线程读到"新环 + 旧图"这种撕裂状态——旧图的系列
 *    指向旧环，画出来的东西与数据对不上，而且不报错。
 *
 * <h2>为什么"FFT 长度"下拉是禁用的（一条实测出来的库约束）</h2>
 * 两个渲染器都按 `min(2048, 环容量)` 取变换长度（各自的 `transformLength`），
 * **公开 API 里没有第二个能设置变换长度的地方**——要换长度只能换环容量（2 的幂）。
 * 而环容量还不能随便取小：实测（本机 2026-10-09）**环容量等于变换长度时声谱图是空的**。
 * 原因是 `SpectrogramRenderer.advance` 的那道夹取：一个 hop 的窗要整段留在环里才可用
 * （`h*hop >= writeCount - 容量` 与 `h*hop + n <= writeCount` 同时成立），容量 == n 时
 * 这两个条件**一个 hop 都满足不了**，于是每一帧算出的可见列数是 0、纹理一直是空的，
 * 而画面上只表现为"声谱图那块地方什么都没有"，不报错。
 * 容量 4096、变换长度 2048 时同一段代码给出 3 列/帧，声谱图正常。
 *
 * 于是两个要求互相锁死：`n = min(2048, 容量)` 且容量是 2 的幂 ⇒ 要让 n 小于 2048，
 * 容量就得等于 n（声谱图空）；要声谱图可用，容量必须 ≥ 4096，而那时 n 恒为 2048。
 * 所以本 demo 把环容量**固定 4096**（规格也这么要求），变换长度因此恒为 2048——
 * 下拉框如实做**禁用**并说明理由，而不是做一个点了没反应的控件。
 * 库哪天给出按系列设置变换长度的入口，这一项就能解开。
 */
private const val SCENE_W = 1180.0

private const val SCENE_H = 720.0

/** 画布背景。避开 `FXGLTransfer` 的 `glClearColor` (0.2,0.2,0.2)，免得分不清"没画"与"画了底色"。 */
private const val BG = 0xFF1B1E23.toInt()

/** 绘图区底色。 */
private const val PLOT_BG = 0xFF11141A.toInt()

private const val COLOR_WAVE = 0xFF4FC3F7.toInt()

private const val COLOR_SPECTRUM = 0xFF81C784.toInt()

/** 播放头（文件视图里那条跟着走的竖线）。用一眼能认出来的暖色，与波形本身分开。 */
private const val COLOR_PLAYHEAD = 0xFFFF5252.toInt()

/** 波形视图里的网格线与零线。零线亮一点，它是幅度轴的基准。 */
private const val COLOR_WAVE_GRID = 0xFF2A3038.toInt()

private const val COLOR_WAVE_ZERO = 0xFF3E4A56.toInt()

/** 麦克风那两半之间的分界线。 */
private const val COLOR_WAVE_SEAM = 0xFF55606D.toInt()

/** 波形视图幅度轴的档位。网格线与刻度标签共用这一份，免得两者对不上。 */
private val WAVE_GRID_VALUES = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)

/** 频谱 y 轴的上界。单位幅度正弦的谱峰是 1.0，留一点余量。 */
private const val SPECTRUM_Y_MAX = 1.2

/**
 * 环形缓冲的容量（2 的幂）。**固定 4096**，理由见文件头那一节：它必须**严格大于**变换
 * 长度，否则声谱图一列都写不出来。
 */
private const val RING_CAPACITY = 4096

/**
 * 库的变换长度上限，**也是本 demo 实际用的变换长度**。
 *
 * 这个数不是本 demo 的选择：`SpectrumSeriesRenderer.DEFAULT_FFT_LENGTH` 与
 * `SpectrogramRenderer.DEFAULT_FFT_LENGTH` 都是 2048，而两者的 `transformLength` 都写成
 * `min(该常量, 环容量)`。
 *
 * 它与那两个常量是**同一份规则的第二份拷贝**——库哪天改了那个上限，这里的轴窗口就会
 * 与画出来的谱对不上（症状是频谱右半边空着、标签偏了）。放在这里并在状态栏里显示，
 * 是为了让对不上时看得见。
 */
private const val FFT_LENGTH_CAP = 2048

/**
 * 波形视图左右两部分的分界比例：左边这一份是**累积历史**（压缩包络），右边是实时波形。
 *
 * 固定比例而不是"按时间分"：按时间分的话两块的比例会随录制时长变化，画面一直在动；
 * 固定比例让"历史那块地方"永远在那儿，只是里面越来越挤。
 */
private const val WAVE_HISTORY_FRACTION = 1f / 3f

/** 波形视图顶部给标题留的带高（像素）。 */
private const val WAVE_TITLE_BAND = 18f

/** 画布四周留的边（给图表标题与外框）。 */
private const val FRAME_MARGIN = 12f

/** 自绘刻度标签的字号与颜色。 */
private const val LABEL_FONT = 11f

private const val LABEL_COLOR = 0xFF9AA6B2.toInt()

/** x 轴刻度标签的预留带高度 / y 轴的预留带宽度（像素）。 */
private const val X_LABEL_BAND = 20f

private const val Y_LABEL_BAND = 44f

/** 声谱图左侧留给频率标签的带宽。 */
private const val WATERFALL_LEFT_BAND = 56f

/**
 * 声谱图纹理的列数。与 `SpectrogramRenderer.DEFAULT_IMAGE_WIDTH` **必须同值**，
 * 本 demo 只用它来算"整幅图像覆盖多少秒"（一个 hop 一列）。
 * 同样属于"同一条规则的第二份拷贝"，改了库里的那一份这里会静默说错话。
 */
private const val SPECTROGRAM_COLUMNS = 512

private const val DEFAULT_RATE = 44100

/** 启动时直接打开的 WAV（见 [AudioDemoApp.applyStartupOptions]）。 */
private const val WAV_PROPERTY = "xuan.audio.wav"

/** 启动时的初始视图（见 [AudioDemoApp.applyStartupOptions]）。 */
private const val VIEW_PROPERTY = "xuan.audio.view"

/** 启动时直接开始麦克风采集（见 [AudioDemoApp.applyStartupOptions]）。 */
private const val MIC_PROPERTY = "xuan.audio.mic"

/**
 * 冒烟模式：跑到这个毫秒数后自己退出（见 [AudioDemoApp.armSmokeExit]）。
 *
 * 本应用**没有任何自动退出路径**——它就是给人用的。但"跑起来没抛异常"这件事需要证据，
 * 而手动关窗留下不了退出码，所以留一个只在给参数时才生效的自动退出。
 */
private const val SMOKE_PROPERTY = "xuan.audio.smoke"

/** 三个视图。顺序即控制条上三个单选的顺序。 */
private enum class View(val label: String) {
    WAVEFORM("波形"),
    SPECTRUM("频谱折线"),
    SPECTROGRAM("声谱图"),
}

/**
 * 窗函数下拉的一项。
 *
 * 单独一个类而不是直接用 `FftWindow`：`ComboBox` 显示的是 `toString()`，
 * 而枚举名（`BLACKMAN_HARRIS`）不是给用户看的。
 */
private class WindowChoice(val label: String, val window: FftWindow) {
    override fun toString(): String = label
}

/**
 * 一次渲染配置的**不可变快照**：源 + 环 + 三张图 + 与它们绑定的常量。
 *
 * 不可变是刻意的（见文件头）：GL 线程每一帧 `get()` 一次，此后整帧用这一个快照，
 * 不会出现"取到新环、取到旧图"的撕裂读。
 *
 * 它**没有**"已写入几个样本"这类计数器：那个数就是 [RingChartData.writeIndex] 本身，
 * 另存一份只会多出一个与环不同步的状态，而不同步的表现是"x 值错位"，不报错。
 */
private class AudioScene(
    /** 当前数据源；null = 没有在采集（画面停在最后一帧数据上）。 */
    val source: AudioSource?,
    val ring: RingChartData,
    val charts: Map<View, Chart>,
    val sampleRate: Int,
    /** 库会用的变换长度 = `min(FFT_LENGTH_CAP, 环容量)`。 */
    val transformLength: Int,
    /** 半谱的 bin 数 = `transformLength / 2 + 1`。 */
    val binCount: Int,
    /** 波形视图自己的数据（包络与文件引用）。见 [WaveformState]。 */
    val wave: WaveformState,
) {
    /** 换源/停源时只换 source，环、图与波形数据照旧（画面因此停在最后一帧数据上）。 */
    fun withSource(other: AudioSource?): AudioScene =
        AudioScene(other, ring, charts, sampleRate, transformLength, binCount, wave)
}

/**
 * 波形视图的数据侧。
 *
 * 它**不进环**：环的容量是 4096 个样本（≈93 ms），装不下"整个文件"，也装不下麦克风的
 * 无限历史。所以波形走自己这条路——文件是"全部样本 + 按当前列数算好的包络"，
 * 麦克风是"边采边压的累积包络 + 环里最近的那一小段"。
 *
 * 字段全部由 GL 线程读写（drain 在帧首、绘制在帧中），所以没有同步；
 * 对象本身经 `AtomicReference` 发布，发布前的构造对 GL 线程可见。
 */
private class WaveformState(
    /**
     * 当前打开的文件；麦克风与"没开始"时为 null。
     *
     * 停止之后**不清空**——那正是"画面停在最后一帧"这条口径：清了的话一按停止波形就没了。
     */
    val file: WavFileSource?,
) {
    /** 文件全览的包络，按绘图区像素列数重建（列数变了才重算）。 */
    val fileEnvelope = FileEnvelope()

    /** 麦克风的累积历史包络。文件模式下不会被喂，也不会被画。 */
    val history = HistoryEnvelope()

    /** [fileEnvelope] 是按哪个源建的；换源之后必须重算。 */
    var envelopeSource: WavFileSource? = null

    /** [fileEnvelope] 当前是多少列。与绘图区列数不同就重算。 */
    var envelopeColumns = 0
}

/**
 * 给 `ChartLayout` 用的文字度量。
 *
 * 与 `DemoChart` 里那份同构：`lineHeight` 必须是 `字号 × LINE_HEIGHT_FACTOR`，
 * 不是字体的真实行高——布局要能被精确预测，读真实 ascent 会让同一个外框在不同字体下
 * 给出不同的绘图区。
 *
 * 没给字体时**不能**去调 `Gc.measureText`（它会抛 `IllegalStateException`），
 * 所以退成一个按字符数估的宽度。估歪了的后果只是装饰的留白不准，
 * 而抛异常的后果是**每一帧都在 `drawChart` 里挂掉**、画布永久停在背景色。
 */
private class GcMetrics(private val gc: Gc, private val hasFont: Boolean) : ChartTextMetrics {
    override fun width(text: String, fontSize: Float): Float {
        if (!hasFont) return text.length * fontSize * 0.55f
        val saved = gc.fontSize
        gc.fontSize = fontSize
        val w = gc.measureText(text)
        gc.fontSize = saved
        return w
    }

    override fun lineHeight(fontSize: Float): Float = fontSize * ChartLayout.LINE_HEIGHT_FACTOR
}

class AudioDemoApp : Application() {

    // ---- 跨线程的配置：JavaFX 线程写、GL 线程每帧读，所以都是 @Volatile ----

    @Volatile
    private var view = View.WAVEFORM

    /**
     * 窗函数的选择。
     *
     * 说明白它今天的效力：`SpectrumSeriesRenderer` 与 `SpectrogramRenderer` 都把窗写死成
     * `BLACKMAN_HARRIS`（各自的 `private static final WINDOW`），**公开 API 没有入口能改**。
     * 所以这一项改不了 GPU 算出来的谱，它进状态栏（显示所选窗的相干增益）并在那里
     * 明确写出"渲染器固定用 Blackman-Harris"。做成一个点了没反应的静默控件是这里最不该
     * 发生的事，所以宁可把它说破。
     */
    @Volatile
    private var windowFn = FftWindow.BLACKMAN_HARRIS

    @Volatile
    private var hasFont = false

    /** 当前场景（源 + 环 + 图）。整表替换，不做原地改。 */
    private val sceneRef = AtomicReference<AudioScene>()

    private var transfer: FXGLTransfer? = null

    /** 当前源的描述（"未开始" / 文件名 / "麦克风"）。只在 JavaFX 线程写。 */
    @Volatile
    private var sourceDesc = "未开始"

    private val sourceLabel = Label()

    private val readoutLabel = Label()

    /** 点了麦克风、还在采集。 */
    private var micRunning = false

    /** 麦克风按钮（它的文字随开始/停止切换，见 [refreshStatus]）。 */
    private var micButton: Button? = null

    /** 播放/暂停按钮。只在"当前是 WAV 文件、且接上了声卡"时可用。 */
    private var playButton: Button? = null

    /** 音量滑块（0..100）。本机输出线没有 MASTER_GAIN 时禁用并说明，不做"拖了没反应"的控件。 */
    private var volumeSlider: Slider? = null

    /**
     * 起播那一刻的（墙钟，样本号）。**只用来在冒烟日志里量"声卡驱动"与"墙钟估算"差多少**
     * ——那是这一版加播放的主要收益，没有读数就成了口头声明。
     */
    @Volatile
    private var playbackStartNanos = 0L

    @Volatile
    private var playbackStartSample = 0
    /** 视图单选按钮，**只为了在启动时能按属性选中**（见 [applyStartupOptions]）。 */
    private val viewButtons = HashMap<View, RadioButton>()

    /** 画布尺寸只打一次（自动化冒烟要一个"真的开始出帧了"的正向信号）。 */
    @Volatile
    private var sizeLogged = false

    /** 进程启动到现在的毫秒数（诊断用，见 [startedAtMs]）。 */
    @Volatile
    private var startedAtMs = 0L

    /**
     * 波形视图上一帧的绘图区（设备像素）。
     *
     * GL 线程每帧写、JavaFX 线程在鼠标事件里读，所以是 `@Volatile`。写进去之后不再改它
     * （每帧是一个新对象），于是"读到的矩形"与"画那一帧用的矩形"永远是同一个。
     *
     * 为什么要点这一份而不是让鼠标事件自己算一遍布局：绘图区尺寸来自画布的设备像素尺寸
     * 与那几个预留带，自己再算一遍就是同一条尺规的第二份实现——两份漂移的表现是
     * "点击跳到的位置与画面上那条线对不上"，而画面完全正常。
     */
    @Volatile
    private var wavePlot: Rect? = null

    /** GL 线程上的渲染异常计数（`onError` 回调里累加），冒烟模式用它决定退出码。 */
    @Volatile
    private var renderErrors = 0

    /**
     * 冒烟脚本里"2 秒都没能从 JavaFX 线程读到数"的次数，同样进退出码。
     *
     * 为什么它必须让冒烟**失败**：读不到数说明应用已经没了（实测见过 JavaFX 线程整个消失、
     * 主线程停在 `DestroyJavaVM` 的形态），而那一轮的其余读数全是"(读数超时)"。
     * 只看渲染异常的话这种运行会报"0 次异常、退出码 0"——**报告与事实相反**，
     * 正是本仓库最防的那一类。超时不是"没测到"，是"这一轮不可信"。
     */
    @Volatile
    private var readTimeouts = 0

    /**
     * 当前打开的那个文件的描述（状态栏那行字）。
     *
     * 单独存一份是因为"停止之后点波形继续播"要把源接回场景、同时把状态栏改回文件模式；
     * 那一刻 [sourceDesc] 已经是"已停止"，而文件名只有这里还留着。只在 JavaFX 线程读写。
     */
    private var fileDesc = ""

    private var statusTimer: AnimationTimer? = null

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer(msaa = 0, font = textFont())
        hasFont = bridge.font != null
        if (!hasFont) {
            System.err.println(
                "[音频 demo] 没给字体（-D${com.bingbaihanji.xuan.example.FONT_PROPERTY}=<某个 .ttf>），" +
                        "轴标签、标题与提示文字都不会画。绘图本身照常。"
            )
        }
        sceneRef.set(buildScene(null, DEFAULT_RATE))

        // onInit 留空是刻意的：本 demo 的 GL 资源全由库自己持有（图表后端挂在 Gc.charts 上、
        // 由 FXGLTransfer.onDispose 释放），这里没有任何东西要在初始化时建。
        // 写一个空的 onInit 只是为了让"这里没有资源"这件事在代码里可见。
        bridge.onInit { }

        // 渲染异常处理器：GL 线程上的异常在本项目默认只 printStackTrace（每 60 次一行）。
        // demo 里把它顶到 stderr 的更前面，方便"画面怎么不动了"时立刻看到原因。
        bridge.onError { failure ->
            renderErrors++
            System.err.println("[音频 demo] 渲染异常：${failure}")
        }

        bridge.onFrame { gc -> drawFrame(gc) }
        transfer = bridge

        val canvas = bridge.createGlFXView()
        installWaveformSeek(canvas)
        val mainView = MainView().apply {
            // `MainView.menu` 就是 BorderPane 的 top（见它的 KDoc），工具条放那儿。
            menu = buildToolbar(stage)
            center = canvas
            bottom = buildStatusBar()
        }

        stage.title = "Xuan 音频 FFT 演示 —— 波形 / 频谱折线 / 声谱图"
        stage.scene = Scene(mainView.createMainView(), SCENE_W, SCENE_H)
        stage.setOnCloseRequest {
            System.err.println("[音频 demo] 收到窗口关闭请求，距首帧 ${System.nanoTime() / 1_000_000 - startedAtMs} ms")
        }
        stage.show()

        // 放在 show() 之后：`installSource` 会改状态栏，而状态栏是场景图里的控件，
        // 早点改也没关系，但文件对话框那条路本来就是 show() 之后才会被点。
        applyStartupOptions()
        // 先刷一次：不刷的话状态栏要等到定时器第一个 250 ms 周期才有字，
        // 那一小段时间里窗口看着像"什么都没接上"。
        refreshStatus()

        // 麦克风丢帧数、环容量这类读数只在状态栏里更新，用 4 Hz 的定时器刷新即可
        // （每帧刷新会让 Label 的文本布局跑 60 次/秒，纯属白烧钱）。
        statusTimer = object : AnimationTimer() {
            private var lastNanos = 0L
            override fun handle(now: Long) {
                if (now - lastNanos < 250_000_000L) return
                lastNanos = now
                refreshStatus()
            }
        }.also { it.start() }

        System.getProperty(SMOKE_PROPERTY)?.toLongOrNull()?.takeIf { it > 0 }?.let { armSmokeExit(it) }
    }

    /**
     * 冒烟模式：到点之后自己退出，并把"这一轮有没有渲染异常"折成退出码。
     *
     * 退出用 `halt` 而不是 `exitProcess`：后者会跑关闭钩子，而钩子与 JavaFX 自己的关停
     * 并发去碰 GL/D3D，实测撞出过 ACCESS_VIOLATION——那会把一次"全过"报成非 0 退出。
     * `halt` 不跑钩子，退出码一样。
     *
     * 让 `Platform.exit()` 先走一步（它会跑到本类的 `stop()`，那里才 dispose GL 资源），
     * 再留 600 ms 给它，然后落退出码。
     *
     * 读数里那两行播放数据是**隔一秒取的两份**：一份只能说明"有个数"，两份才能说明
     * "声卡真的在走"——而"没报错"与"声音出来了"本来就是两回事。
     */
    private fun armSmokeExit(millis: Long) {
        Thread {
            Thread.sleep(millis)
            // 状态栏那两行是在 JavaFX 线程上维护的，所以在那一线程上取一份快照再打印：
            // 直接读 Label 是跨线程碰 JavaFX 控件，读出来是什么取决于运气。
            // 它同时是这一轮"真的在出帧"的读数（环容量、播放位置、丢帧数都在里面）。
            val first = readPlaybackOnFx()
            Thread.sleep(1000)
            val second = readPlaybackOnFx()

            // 跳转与暂停这两条路**鼠标才碰得到**，冒烟里没有鼠标，所以在这儿驱动一次——
            // 否则"点了没反应"与"跳过去了"在自动跑的这一轮里分不开（它们都只是"没有异常"）。
            val target = smokeSeekTo(fileFraction = 0.25)
            Thread.sleep(1000)
            val afterSeek = readPlaybackOnFx()

            smokeSetPaused(true)
            val paused = readPlaybackOnFx()
            Thread.sleep(400)
            val stillPaused = readPlaybackOnFx()
            smokeSetPaused(false)
            Thread.sleep(400)
            val resumed = readPlaybackOnFx()

            println("[音频 demo] 冒烟结束：渲染异常 $renderErrors 次；读数超时 $readTimeouts 次")
            println("[音频 demo] 播放读数 t0：$first")
            println("[音频 demo] 播放读数 t1：$second")
            println("[音频 demo] 跳转目标样本=$target；跳转后 t2：$afterSeek")
            println("[音频 demo] 暂停时 t3：$paused")
            println("[音频 demo] 暂停 400 ms 后 t4：$stillPaused（播放头该与 t3 相同）")
            println("[音频 demo] 恢复 400 ms 后 t5：$resumed（该比 t4 大）")
            Platform.runLater { Platform.exit() }
            Thread.sleep(600)
            val code = if (renderErrors > 0 || readTimeouts > 0) 1 else 0
            println("[音频 demo] 冒烟退出码 $code")
            Runtime.getRuntime().halt(code)
        }.apply {
            // 非守护：`Platform.exit()` 之后 JavaFX 那条非守护线程会结束，守护线程会被
            // 立刻连带杀掉——那样这一轮就既不打印读数、也不落退出码，看上去"跑完了"，
            // 实际上什么都没验到。
            isDaemon = false
            name = "xuan-audio-smoke"
            start()
        }
    }

    /**
     * 冒烟脚本里的跳转：把当前文件跳到 [fileFraction] 处继续播，返回目标样本号。
     *
     * **走的是与"点波形"同一条路**（`WavFileSource.startAt` → 声卡那一侧 flush + 重写），
     * 只是位置由参数直接给、不经过鼠标坐标换算——坐标换算由 `ClickVerifier` 那条路管，
     * 而这里要验的是跳转落到没落到声卡上。
     */
    private fun smokeSeekTo(fileFraction: Double): Long {
        val target = AtomicReference(-1L)
        val latch = CountDownLatch(1)
        Platform.runLater {
            val file = sceneRef.get().wave.file
            if (file != null) {
                val to = (file.frameCount * fileFraction).toInt().coerceIn(0, file.frameCount - 1)
                file.startAt(to)
                markPlaybackStart(file, to)
                refreshStatus()
                target.set(to.toLong())
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return target.get()
    }

    /** 冒烟脚本里的暂停/继续（与播放按钮调的是同一对方法）。 */
    private fun smokeSetPaused(paused: Boolean) {
        val latch = CountDownLatch(1)
        Platform.runLater {
            sceneRef.get().wave.file?.let { if (paused) it.pause() else it.resume() }
            refreshStatus()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    /**
     * 在 JavaFX 线程上取一份播放读数（状态栏两行 + 播放头 + 声卡帧位置 + 墙钟估算）。
     *
     * 墙钟那一份是**同一时刻**用旧的估算方式算出来的，两个数摆在一起才看得出这一版
     * 到底改了什么：声卡的读数滞后于墙钟估算的量，就是驱动缓冲区那一块（见 `PcmOutput`）。
     */
    private fun readPlaybackOnFx(): String {
        val text = AtomicReference("(读数超时)")
        val latch = CountDownLatch(1)
        Platform.runLater {
            val reading = runCatching {
                val file = sceneRef.get().wave.file
                val position = file?.positionSamples() ?: -1L
                val frames = file?.lineFramePosition ?: -1L
                val rate = sceneRef.get().sampleRate
                val elapsedNanos = System.nanoTime() - playbackStartNanos
                val wall = playbackStartSample + elapsedNanos * rate / 1_000_000_000L
                val lagMs = if (file != null) (wall - position) * 1000.0 / rate else Double.NaN
                "${sourceLabel.text} | ${readoutLabel.text} | 播放头=$position 声卡帧位置=$frames " +
                        "墙钟估算=$wall（声卡比墙钟滞后 %.1f ms）起播起点=$playbackStartSample 起播以来=%.2f s"
                            .format(lagMs, elapsedNanos / 1e9)
            }
            text.set(reading.getOrElse { "(读数异常：$it)" })
            latch.countDown()
        }
        if (!latch.await(2, TimeUnit.SECONDS)) {
            readTimeouts++
            val all = Thread.getAllStackTraces()
            System.err.println(
                "[音频 demo] 读数超时（JavaFX 线程 2 秒没跑我们的任务）；全部线程：" +
                        all.keys.joinToString(" ") { "${it.name}[${it.state}]" }
            )
            System.err.println(
                "[音频 demo] JavaFX 线程栈：" +
                        (all.entries.firstOrNull { it.key.name == "JavaFX Application Thread" }
                            ?.value?.joinToString("\n    ") { it.toString() } ?: "(没有这个线程)")
            )
        }
        return text.get()
    }

    /**
     * 波形视图的点击跳转：点文件波形上某处，播放位置跳到那里并继续播。
     *
     * ## 坐标换算（这一步漏了就是"点 A 跳到 B"，而画面完全正常）
     * 鼠标事件给的是画布节点的**逻辑**局部坐标（`e.x` / `e.y`），而 `Gc` 与这里画出来的
     * 绘图区都是**设备像素**，两者差一个**窗口输出缩放系数**（本机 125% 下是 1.25）。
     * 系数从 `FXGLTransfer.deviceScale(node)` 取，它读的是 `window.outputScaleY`。
     *
     * 两个轴都用 **Y** 那一份是刻意的：帧缓冲的宽高都是 `ceil(逻辑尺寸 × outputScaleY)`
     * （`ClickVerifier` 有一条断言钉着这条关系），所以绘图区的设备像素尺寸是按 Y 算出来的。
     * 非等比缩放下 x 该用 `outputScaleX` 才对得上 `pickAsyncAtNode`，而本机（125%）两轴相等、
     * 也造不出非等比缩放的局面——按 Y 走与"画出来的那个矩形"是自洽的，与 `pickAsyncAtNode`
     * 的 x 在非等比下会对不上，这是两者的差别，不是笔误。
     *
     * 用 `e.x` / `e.y` 而不是 `e.sceneX` / `e.sceneY`：后者是场景坐标，与画布原点之间
     * 还差一个布局偏移（工具条/状态栏把画布往下推了几十像素），乘出来是静默错位的。
     *
     * 判定与换算都用**节点局部坐标先乘系数**、再与 `wavePlot`（设备像素）比，
     * 而不是把 `wavePlot` 换算回局部坐标去比：少一次除法、少一处取整，
     * 而"点在不在矩形里"这件事对边界本来就不敏感。
     */
    private fun installWaveformSeek(node: Node) {
        node.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            val bridge = transfer ?: return@addEventHandler
            val scene = sceneRef.get()
            // 只有文件源能跳转：麦克风是实时流，"跳到过去"没有意义。
            val file = scene.wave.file ?: return@addEventHandler
            if (view != View.WAVEFORM) return@addEventHandler
            val total = file.frameCount
            if (total <= 0) return@addEventHandler
            val plot = wavePlot ?: return@addEventHandler
            val scale = bridge.deviceScale(node)
            val x = event.x * scale
            val y = event.y * scale
            if (x < plot.x || x > plot.x + plot.width || y < plot.y || y > plot.y + plot.height) {
                return@addEventHandler
            }
            val fraction = ((x - plot.x) / plot.width).coerceIn(0.0, 1.0)
            // 夹到 [0, total-1]：夹到 total 等于"这一遍已经放完"，而用户点的是画面上的
            // 某一点，那个点必然对应文件里的某个样本，不该产生一个"跳过尾"的落点。
            val sample = (fraction * total).toInt().coerceIn(0, total - 1)
            // `startAt` 里声卡那一侧会 flush 掉已排队未播的旧数据（不丢的话要等小半个
            // 缓冲区才听见新位置的声音，听起来像"点了没反应"）。
            file.startAt(sample)
            markPlaybackStart(file, sample)
            refreshStatus()
            if (scene.source !== file) {
                // 已经按过「停止」：源还在，只是没挂在场景上，所以 drainInto 不会被调到
                // ——只调 startAt 的话源的 running 变成真、而没有任何人在读它，画面上
                // 什么都不发生（这正是"点了没反应"那种最难受的形态）。
                // 把同一个源接回场景即可，**不能走 installSource**：那条路会 start()
                // 从头开始，把刚跳过去的位置抹掉。
                //
                // 环与波形状态沿用（换的不是源、是暂停/继续）：环的写入总数继续单调递增，
                // 于是 `SeriesBuffer` 那条"写入总数倒退就抛异常"的契约不会被触发。
                sceneRef.set(scene.withSource(file))
                sourceDesc = fileDesc
                micRunning = false
                refreshStatus()
            }
        }
    }

    override fun stop() {
        System.err.println("[音频 demo] stop() 被调用，距首帧 ${System.nanoTime() / 1_000_000 - startedAtMs} ms")
        statusTimer?.stop()
        statusTimer = null
        // 停源放在 dispose 之前：采集线程不占 GL 资源，但它握着系统音频设备，
        // 让它在窗口拆掉之后还活着，下一次运行就可能拿不到线（设备被自己占着）。
        sceneRef.get()?.source?.stop()
        transfer?.dispose()
    }

    // ==================================================================
    // 控制条
    // ==================================================================

    private fun buildToolbar(stage: Stage): Region {
        val openWav = Button("打开 WAV 文件…").apply {
            setOnAction { openWavFile(stage) }
        }
        val micButton = Button("麦克风").apply {
            setOnAction { toggleMicrophone() }
        }
        this.micButton = micButton
        val stopButton = Button("停止").apply {
            setOnAction { stopSource() }
        }
        // 播放/暂停与音量只对 WAV 文件那一路有意义（麦克风不做监听回放，见文件头）。
        // 没有声卡时它们是**禁用 + 说明**，不是一个点了没反应的控件（同下面那个 FFT 长度框）。
        val playButton = Button("暂停").apply {
            isDisable = true
            setOnAction { togglePlayback() }
        }
        this.playButton = playButton
        val volumeSlider = Slider(0.0, 100.0, 100.0).apply {
            prefWidth = 120.0
            isDisable = true
            tooltip = javafx.scene.control.Tooltip("音量（对数刻度：100% = 0 dB）")
            valueProperty().addListener { _, _, value -> applyVolume(value.toInt()) }
        }
        this.volumeSlider = volumeSlider

        val viewGroup = ToggleGroup()
        // 用 RadioButton 而不是 ToggleButton：ToggleButton 在 ToggleGroup 里**可以再点一下
        // 取消选中**，于是会出现"三个都没选"的状态，而本 demo 没有一个自然的默认视图。
        val viewBox = HBox(6.0).apply {
            alignment = Pos.CENTER_LEFT
            for (v in View.entries) {
                children.add(
                    RadioButton(v.label).apply {
                        toggleGroup = viewGroup
                        isSelected = (v == View.WAVEFORM)
                        setOnAction { this@AudioDemoApp.view = v }
                        viewButtons[v] = this
                    }
                )
            }
        }

        // 变换长度：**禁用**，理由见文件头那一节（库按 min(2048, 环容量) 取长度，
        // 而环容量必须严格大于它声谱图才有内容 ⇒ 长度恒为 2048）。
        // 做成禁用的说明项而不是一个点了没反应的活控件——本仓库对"设了但没用"的口径是
        // 明确拒绝（同 `XuanDemo` 里那个 MSAA 菜单项）。
        val fftBox = ComboBox<Int>(
            javafx.collections.FXCollections.observableArrayList(512, 1024, 2048, 4096)
        ).apply {
            value = FFT_LENGTH_CAP
            isDisable = true
            tooltip = javafx.scene.control.Tooltip(
                "变换长度由渲染器按 min($FFT_LENGTH_CAP, 环容量) 决定，公开 API 没有别的入口；" +
                        "而环容量必须严格大于变换长度声谱图才有列可写。环容量固定 $RING_CAPACITY ⇒ 恒为 $FFT_LENGTH_CAP。"
            )
        }

        val windowChoices = listOf(
            WindowChoice("矩形", FftWindow.RECTANGULAR),
            WindowChoice("Hann", FftWindow.HANN),
            WindowChoice("Hamming", FftWindow.HAMMING),
            WindowChoice("Blackman-Harris", FftWindow.BLACKMAN_HARRIS),
        )
        val windowBox = ComboBox<WindowChoice>().apply {
            items.addAll(windowChoices)
            value = windowChoices.last()
            setOnAction {
                selectionModel.selectedItem?.let { windowFn = it.window; refreshStatus() }
            }
        }

        fun caption(text: String) = Label(text).apply {
            padding = Insets(0.0, 2.0, 0.0, 8.0)
        }

        return HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(6.0, 8.0, 6.0, 8.0)
            children.addAll(
                openWav, micButton, playButton, stopButton,
                Separator(Orientation.VERTICAL),
                caption("音量"), volumeSlider,
                Separator(Orientation.VERTICAL),
                caption("视图"), viewBox,
                Separator(Orientation.VERTICAL),
                caption("FFT 长度"), fftBox, Label("（库固定）"),
                caption("窗函数"), windowBox,
            )
        }
    }

    private fun buildStatusBar(): Region = VBox(2.0).apply {
        padding = Insets(4.0, 10.0, 6.0, 10.0)
        children.addAll(sourceLabel, readoutLabel)
    }

    // ==================================================================
    // 换源 / 换配置
    // ==================================================================

    private fun openWavFile(stage: Stage) {
        val chooser = FileChooser().apply {
            title = "选择一个 PCM WAV 文件"
            extensionFilters.add(FileChooser.ExtensionFilter("WAV 音频 (*.wav)", "*.wav"))
        }
        chooser.showOpenDialog(stage)?.let { openWav(it) }
    }

    /**
     * 解码并切到这个 WAV。**在 JavaFX 线程上同步做**——`WavFileSource` 构造时把整个文件
     * 解进内存，秒级文件是几十毫秒的事；真接上小时的录音要挪到后台线程，本期不做。
     *
     * 解码失败（非 PCM、帧长对不上、读不了）在这里变成一句看得懂的话：不 catch 的话
     * 异常会从事件处理器里抛出去走 JavaFX 的默认打印，用户看到的是"点了按钮什么都没发生"。
     */
    private fun openWav(file: File) {
        sourceLabel.text = "正在解码 ${file.name} …"
        val wav = try {
            WavFileSource(file)
        } catch (e: Exception) {
            fail("打开失败：${e.message}")
            return
        }
        fileDesc = "WAV ${file.name}（${wav.frameCount} 样本 @ ${wav.sampleRate} Hz）"
        if (!wav.attachPlayback()) {
            // 拿不到声卡**不静默**（同麦克风那条口径）：一句 stderr + 状态栏里那句话。
            // 静默的后果是一块"听着没声"的画布，而它与"声音很小"在屏幕上看不出区别。
            System.err.println("[音频 demo] 声音不可用：${wav.playbackError}")
        }
        installSource(wav, fileDesc)
    }

    private fun toggleMicrophone() {
        if (micRunning) {
            stopSource()
            return
        }
        val mic = try {
            MicrophoneSource()
        } catch (e: Exception) {
            // 拿不到设备时**明确报错**（规格 §10.3），而不是给一块静默无声的画布。
            fail("麦克风不可用：${e.message}")
            return
        }
        installSource(mic, "麦克风 ${mic.sampleRate} Hz")
    }

    /**
     * 换源：**先停旧源、再起新源、最后整表替换场景**。
     *
     * 顺序不是随便排的：旧源不停，两个采集线程会同时往下游灌（规格 §5 第 1 条），
     * 而下游看到的是一段两路信号拼起来的怪东西——画面完全正常。
     *
     * @return 新源是否真的起来了
     */
    private fun installSource(source: AudioSource, desc: String): Boolean {
        sceneRef.get().source?.stop()
        try {
            source.start()
        } catch (e: Exception) {
            fail("启动失败：${e.message}")
            return false
        }
        // 换源必须重建环（新环从 0 开始写）。旧环里是另一个源的数据，沿用只会让
        // 波形左半边还挂着上一路信号。
        val next = buildScene(source, source.sampleRate)
        sceneRef.set(next)
        sourceDesc = desc
        micRunning = source is MicrophoneSource
        markPlaybackStart(source)
        refreshStatus()
        return true
    }

    /**
     * 记下这一轮起播的墙钟与起点。
     *
     * 它唯一的用途是**量"声卡驱动"与"墙钟估算"差多少**（冒烟日志里打出来）：
     * 换了播放头来源这件事如果只写在注释里，就是一个没有读数的声明。
     *
     * 起点要**由调用方给**，不能在这里现读 `positionSamples()`：跳转是异步落实的
     * （声卡那一侧要 flush + 重算基准，几毫秒），现读会读到**跳转前**的位置，
     * 于是"声卡比墙钟滞后"会算出一个假的负值——量具错了比没有量具更坏。
     */
    private fun markPlaybackStart(
        source: AudioSource?,
        startSample: Int = (source as? WavFileSource)?.positionSamples()?.toInt() ?: 0,
    ) {
        if (source !is WavFileSource) return
        playbackStartNanos = System.nanoTime()
        playbackStartSample = startSample
    }

    /** 播放/暂停。停止之后按它＝从当前位置接着播。 */
    private fun togglePlayback() {
        val scene = sceneRef.get()
        val file = scene.wave.file ?: return
        if (scene.source !== file) {
            // 按过「停止」：源还在（环与波形也留着），只是没挂在场景上——只调 startAt 的话
            // 没有任何人在读它，画面上什么都不发生（这正是"点了没反应"那种最难受的形态）。
            // 场景里挂着别的源（麦克风）时**不抢**：那会把采集线程晾在一边继续占设备。
            if (scene.source != null) return
            val from = file.positionSamples().toInt()
            file.startAt(from)
            sceneRef.set(scene.withSource(file))
            sourceDesc = fileDesc
            micRunning = false
            markPlaybackStart(file, from)
            refreshStatus()
            return
        }
        if (file.isPaused) file.resume() else file.pause()
        refreshStatus()
    }

    /** 音量滑块 → dB 应用到当前文件源。没有声卡 / 不支持 MASTER_GAIN 时是空操作（控件已禁用）。 */
    private fun applyVolume(percent: Int) {
        val file = sceneRef.get().wave.file ?: return
        if (!file.playbackActive) return
        file.setVolumeDb(sliderToDb(percent))
        refreshStatus()
    }

    private fun stopSource() {
        val cur = sceneRef.get()
        cur.source?.stop()
        // 只摘掉源，环与图留着：画面于是停在最后一帧的数据上，比"一按停止就变空"有用。
        sceneRef.set(cur.withSource(null))
        micRunning = false
        sourceDesc = "已停止"
        refreshStatus()
    }

    private fun fail(message: String) {
        sourceDesc = message
        System.err.println("[音频 demo] $message")
        refreshStatus()
    }

    /**
     * 两个启动开关，用来把 demo 直接拉到一个想要的状态（也方便自动化冒烟，
     * 因为本应用**没有任何自动退出路径**——它就是给人用的）：
     *
     * - `-Dxuan.audio.wav=<路径>`：启动后立刻打开这个 WAV，省掉一次文件对话框；
     * - `-Dxuan.audio.view=WAVEFORM|SPECTRUM|SPECTROGRAM`：初始视图（默认波形）。
     *
     * 认不出来的视图名**出声**：静默按默认处理的话，冒烟那一刻会以为自己在看频谱，
     * 实际看的是波形，而两者都"有画面"。
     */
    private fun applyStartupOptions() {
        System.getProperty(VIEW_PROPERTY)?.let { raw ->
            val picked = View.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            if (picked == null) {
                System.err.println(
                    "[音频 demo] $VIEW_PROPERTY=$raw 不是一个视图名" +
                            "（可填 ${View.entries.joinToString(" / ") { it.name }}），按波形处理"
                )
            } else {
                view = picked
                viewButtons[picked]?.isSelected = true
            }
        }
        System.getProperty(WAV_PROPERTY)?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (file.isFile) openWav(file) else fail("启动参数里的 WAV 不存在：$path")
        }
        // `-Dxuan.audio.mic=1`：启动时直接开始采集。与上面那个 WAV 开关同构——麦克风那条
        // 路要有人点一下按钮才会走到，没有这个开关的话它**一次运行都跑不到**。
        // 两个开关同时给的话麦克风赢（它排在后面，会把文件源顶掉）。
        if (System.getProperty(MIC_PROPERTY)?.let { it == "1" || it.equals("true", true) } == true) {
            toggleMicrophone()
        }
    }

    // ==================================================================
    // 场景装配（纯计算，任意线程可调——但只在 JavaFX 线程上被调用）
    // ==================================================================

    /**
     * 建一套「环 + 三张图」。
     *
     * 环容量固定 [RING_CAPACITY]，理由见文件头：它必须**严格大于**变换长度，声谱图才有列
     * 可写。容量同时也是波形能回看的时长上限（`容量 / 采样率` 秒）——状态栏里写着。
     */
    private fun buildScene(source: AudioSource?, sampleRate: Int): AudioScene {
        val ring = RingChartData(
            arrayOf(
                AxisRange(0.0, RING_CAPACITY.toDouble(), "样本序号", ""),
                AxisRange(-1.0, 1.0, "幅度", ""),
            ),
            RING_CAPACITY,
        )
        val n = min(FFT_LENGTH_CAP, RING_CAPACITY)
        val binCount = n / 2 + 1
        // 波形视图不再挂图表（见 drawWaveform）：它画的是"整个文件 / 整段历史"的包络，
        // 而环里只有最近 4096 个样本，两者根本不是一回事。环留着给频谱与声谱图用。
        val charts = mapOf(
            View.SPECTRUM to buildSpectrumChart(ring),
            View.SPECTROGRAM to buildSpectrogramChart(ring),
        )
        return AudioScene(source, ring, charts, sampleRate, n, binCount, WaveformState(source as? WavFileSource))
    }

    /** 三张图共用的外框配置。 */
    private fun Chart.applyBase(axisStyle: AxisStyle, leftBand: Float) {
        legendVisible(false)
        titleFontSize(13f)
        axisTitlesVisible(false)
        axisStyle(axisStyle)
        // 刻度文字由本 demo 自己画（频谱那条要换成 Hz、波形那条要换成毫秒），
        // 所以把库的刻度文字关掉、自己声明预留带。注意 ChartLayout 的规则是
        // "tickLabelsVisible 关着时用调用方声明的 tickLabelReserve"——两者必须配着改，
        // 只关一个的后果是标签画到绘图区里、压在数据上。
        tickLabelReserve(ChartSide.BOTTOM, X_LABEL_BAND)
        tickLabelReserve(ChartSide.LEFT, leftBand)
        padding(ChartInsets(6f, 12f, 6f, 6f))
    }

    private fun gridStyle(): AxisStyle = AxisStyle.defaults()
        .visible(true)
        .tickLabelsVisible(false)
        .tickLabelColor(LABEL_COLOR)

    private fun buildSpectrumChart(ring: RingChartData): Chart {
        val x = Axis(AxisType.LINEAR, ring.axisRange(0))
        val y = Axis(AxisType.LINEAR, ring.axisRange(1))
        val series = Series("频谱", ring, ChartType.SPECTRUM).color(COLOR_SPECTRUM).lineWidth(1.5f)
        return Chart(x, y).apply {
            if (hasFont) title("频谱折线（x 轴 = 频率）")
            applyBase(gridStyle(), Y_LABEL_BAND)
            addLayer("数据").add(series)
        }
    }

    private fun buildSpectrogramChart(ring: RingChartData): Chart {
        val x = Axis(AxisType.LINEAR, ring.axisRange(0))
        val y = Axis(AxisType.LINEAR, ring.axisRange(1))
        val series = Series("声谱图", ring, ChartType.WATERFALL)
        return Chart(x, y).apply {
            if (hasFont) title("声谱图（一列一个 hop，右端最新）")
            // 声谱图的渲染器不读轴（它把一整块纹理铺满绘图区），所以轴上的网格与刻度
            // 都是没有含义的装饰——一律关掉，只在左边与下边自己画频率/时间标签。
            applyBase(AxisStyle.defaults(), WATERFALL_LEFT_BAND)
            addLayer("数据").add(series)
        }
    }

    // ==================================================================
    // 每帧绘制（GL 线程）
    // ==================================================================

    private fun drawFrame(gc: Gc) {
        val scene = sceneRef.get()

        if (!sizeLogged) {
            sizeLogged = true
            startedAtMs = System.nanoTime() / 1_000_000
            // 这行是"窗口真的开起来了、GL 回调真的在跑"的正向信号（设备像素，不是逻辑尺寸）。
            println("[音频 demo] 绘制区 ${gc.width}x${gc.height} 设备像素，字体=${if (hasFont) "已加载" else "未加载"}")
        }

        // 先取数、再画：同一帧里让新样本立刻出现在画面上（否则画面永远差一帧）。
        drainIntoScene(scene)

        gc.fill = BG
        gc.fillRect(0f, 0f, gc.width.toFloat(), gc.height.toFloat())

        // 视图只读一次：`view` 是 JavaFX 线程随时可改的 @Volatile 字段，读两遍就可能
        // 拿到两个不同的值——那一帧会用 A 图配 B 的标签，画面上只是"标签怪怪的"。
        val shown = view
        if (shown == View.WAVEFORM) {
            drawWaveform(gc, scene)
        } else {
            val chart = scene.charts[shown] ?: return
            drawChart(gc, scene, chart, shown)
        }

        // 波形视图不吃这句通用提示：它自己有一行标题，两行字会压在同一条带子里。
        // "已停止 / 未开始"那两种状态由 drawWaveformLabels 并进标题里说。
        if (scene.source == null && hasFont && shown != View.WAVEFORM) {
            gc.fill = 0xFF7A8797.toInt()
            gc.fontSize = 14f
            val hint = if (scene.ring.writeIndex() > 0L) {
                "已停止（画面停在最后一帧的数据上；点「打开 WAV 文件…」或「麦克风」继续）"
            } else {
                "未开始：点「打开 WAV 文件…」，或点「麦克风」开始实时采集"
            }
            gc.drawText(hint, 28f, 32f)
        }
    }

    /**
     * 把源自上次调用以来新增的样本喂给这一帧要用的两处下游。
     *
     * **只在 GL 线程调用**（`drainInto` 的契约）。两处下游各有各的用途，都必须吃到：
     * - 环：频谱与声谱图的数据源。**即使当前看的是波形也要喂**，否则切到频谱的那一刻
     *   环里是空的、要重新攒一个窗才有画面。
     * - 麦克风历史包络：波形视图左边那 1/3。它在 [drainInto] 的回调里就地压成 min/max，
     *   所以内存与录制时长无关（另存全部样本的话，10 分钟就是 106 MB）。
     *
     * 环的 x 维度直接写当前 [RingChartData.writeIndex]：那正是这个样本的绝对序号，
     * 省掉一个会与环不同步的外部计数器。
     */
    private fun drainIntoScene(scene: AudioScene) {
        val source = scene.source ?: return
        val ring = scene.ring
        val wave = scene.wave
        source.drainInto { data, n ->
            for (i in 0 until n) {
                ring.append(ring.writeIndex().toDouble(), data[i].toDouble())
            }
            // 只有麦克风走得着历史包络；文件模式下喂它是白扫一遍数组。
            if (wave.file == null) wave.history.feed(data, n)
        }
    }

    // ==================================================================
    // 波形视图（GL 线程，直接用 Gc 画）
    // ==================================================================

    /**
     * 波形视图：不走图表后端，直接用 `Gc` 的立即模式画包络与竖线。
     *
     * 为什么不塞进图表：图表的数据容器是"最近 4096 个样本的环"（≈93 ms），而这里要画的是
     * **整个文件**（几百万个样本）或**整段累积历史**（无限长）。两者差好几个数量级，
     * 环里根本没有那些数据。每列画一条 min→max 的竖条（包络）是唯一既画得动、
     * 又有信息量的形状——几十万条线段既画不动，画出来也只是一团糊。
     *
     * 两种形态：
     * - **文件**：整个文件铺满绘图区，配一条随播放走的竖线；点一下跳到该处继续播。
     * - **麦克风**：左 1/3 是累积历史（压缩包络），右 2/3 是全分辨率的实时波形。
     *   两块按固定比例分界、共用同一条基线与同一个绘图区，接缝处不留空隙。
     */
    private fun drawWaveform(gc: Gc, scene: AudioScene) {
        val frame = Rect(
            FRAME_MARGIN,
            FRAME_MARGIN,
            gc.width - 2f * FRAME_MARGIN,
            gc.height - 2f * FRAME_MARGIN,
        )
        val plot = Rect(
            frame.x + Y_LABEL_BAND,
            frame.y + WAVE_TITLE_BAND,
            frame.width - Y_LABEL_BAND,
            frame.height - WAVE_TITLE_BAND - X_LABEL_BAND,
        )
        // 窗口太小（还没布局完、或用户拖到极小）时把绘图区作废：留一个过时的矩形会让
        // 鼠标点在一个"看起来在画面里"的位置上跳到别处——宁可不响应。
        if (plot.width < 8f || plot.height < 8f) {
            wavePlot = null
            return
        }
        // 发布给鼠标事件用（设备像素）。每帧一个新对象、写进去之后不再改，
        // 于是事件里读到的那一份与画这一帧用的那一份必然是同一个。
        wavePlot = Rect(plot.x, plot.y, plot.width, plot.height)

        val midY = plot.y + plot.height * 0.5f

        gc.save()
        gc.fill = PLOT_BG
        gc.fillRect(plot.x, plot.y, plot.width, plot.height)
        gc.fill = COLOR_WAVE_GRID
        for (v in WAVE_GRID_VALUES) {
            if (v == 0f) continue
            gc.fillRect(plot.x, midY - v * plot.height * 0.5f, plot.width, 1f)
        }
        gc.fill = COLOR_WAVE_ZERO
        gc.fillRect(plot.x, midY, plot.width, 1f)
        gc.restore()

        gc.save()
        gc.fill = COLOR_WAVE
        if (scene.wave.file != null) {
            drawFileWaveform(gc, scene, plot, midY)
        } else {
            drawMicWaveform(gc, scene, plot, midY)
        }
        gc.restore()

        if (hasFont) drawWaveformLabels(gc, scene, plot, frame)
    }

    /**
     * 文件：整个文件铺满绘图区 + 一条播放头。
     *
     * 包络的重建是 O(样本数)，所以只在**换源或列数变了**的时候做。每帧重建一个
     * 800 万样本的包络要几百毫秒，而画面上只表现为"窗口卡住"——不报错、也看不出原因。
     */
    private fun drawFileWaveform(gc: Gc, scene: AudioScene, plot: Rect, midY: Float) {
        val file = scene.wave.file ?: return
        val wave = scene.wave
        val columns = max(1, plot.width.roundToInt())
        if (wave.envelopeSource !== file || wave.envelopeColumns != columns) {
            wave.fileEnvelope.build(file, columns)
            wave.envelopeSource = file
            wave.envelopeColumns = columns
        }
        drawEnvelopeBars(
            gc, plot, midY, plot.x, plot.width, wave.fileEnvelope.columns,
            wave.fileEnvelope.minBuffer(), wave.fileEnvelope.maxBuffer(),
        )

        // 播放头的位置读的是源自己吐出的样本总数（cursor），与"点在哪里"用的是同一个量，
        // 所以点了之后头必然落在点的那一列上，不会差一个偏移。
        val total = wave.fileEnvelope.frameCount
        if (total > 0) {
            val fraction = (file.positionSamples().toDouble() / total).coerceIn(0.0, 1.0)
            val x = plot.x + (fraction * plot.width).toFloat()
            gc.fill = COLOR_PLAYHEAD
            gc.fillRect(x - 1f, plot.y, 2f, plot.height)
        }
    }

    /**
     * 麦克风：左 1/3 累积历史（压缩包络），右 2/3 全分辨率实时。
     *
     * 两块的**接缝在同一个像素位置上**，所以"没有空白带"是构造上的结果而不是调出来的：
     * 左边这一块的宽度就是 [WAVE_HISTORY_FRACTION] × 绘图区宽度，右边那一块从它的右边缘
     * 一直铺到绘图区右边缘，中间没有第三块地方可以空出来。
     *
     * 数据上接得上的口径：左边包络的最末一列覆盖到"当前样本数 − 右边那一块要用的样本数"
     * 附近，右边从那里往后取环里最近的一段。左边是按整段历史等比压缩的，末列与接缝之间
     * 最多差一列的样本（一列此刻代表成千上万个样本），肉眼与数据上都连续。
     */
    private fun drawMicWaveform(gc: Gc, scene: AudioScene, plot: Rect, midY: Float) {
        val history = scene.wave.history
        val leftWidth = max(plot.width * WAVE_HISTORY_FRACTION, 1f)
        val seamX = plot.x + leftWidth

        drawEnvelopeBars(
            gc, plot, midY, plot.x, leftWidth, history.size,
            history.minBuffer(), history.maxBuffer(),
        )

        // 分界线：画它只是让"固定比例"看得见，两块之间本来就不留空隙。
        gc.fill = COLOR_WAVE_SEAM
        gc.fillRect(seamX - 0.5f, plot.y, 1f, plot.height)

        gc.fill = COLOR_WAVE
        drawRealtimeWave(gc, scene.ring, plot, seamX, plot.x + plot.width - seamX, midY)
    }

    /**
     * 右边那一块：把环里最近的一段按 **1 个样本 = 1 个像素列**连成折线。
     *
     * 取环而不是另存一份"最近 N 个样本"：环就是"最近 4096 个样本"，容量（93 ms）远大于
     * 右边这一块要用的样本数（一千来个 ≈ 二十几毫秒），既够用又不占额外内存。
     *
     * 样本不够时（刚点开麦克风的那几毫秒）**按实际有的画**，不补零、也不拉伸到整块宽度：
     * 补零会画出一段"看起来像静音"的假信号，而拉伸会让横向时间刻度在开头几帧里失真。
     */
    private fun drawRealtimeWave(gc: Gc, ring: RingChartData, plot: Rect, x0: Float, width: Float, midY: Float) {
        if (width <= 0f) return
        val avail = ring.itemCount()
        val n = min(width.roundToInt(), avail)
        if (n < 2) return
        val from = avail - n                 // 环里最旧的那个可用下标
        val step = width / n
        val half = plot.height * 0.5f
        gc.stroke = COLOR_WAVE
        gc.lineWidth = 1.5f
        gc.beginPath()
        for (k in 0 until n) {
            val v = ring.value(1, from + k).toFloat().coerceIn(-1f, 1f)
            val x = x0 + k * step
            val y = midY - v * half
            if (k == 0) gc.moveTo(x, y) else gc.lineTo(x, y)
        }
        gc.strokePath()
    }

    /**
     * 把一串 min/max 列画成竖条。
     *
     * [count] 列摊在 [width] 像素上，每列占 `width / count` 像素宽；宽度取 `max(1px)`
     * 是为了列比像素多的时候竖条之间不留缝——真按 0.34 像素画的话，相邻竖条之间会露出
     * 背景，看着像信号里有缺口，而那是画法造成的、不是数据里的。
     *
     * 每条至少有 1 像素高，所以静音段画的是一条细线而不是什么都没有。
     */
    private fun drawEnvelopeBars(
        gc: Gc, plot: Rect, midY: Float,
        x0: Float, width: Float, count: Int, mins: FloatArray, maxs: FloatArray,
    ) {
        if (count <= 0 || width <= 0f) return
        val cw = width / count
        val barWidth = max(cw, 1f)
        val half = plot.height * 0.5f
        for (i in 0 until count) {
            val hi = maxs[i].coerceIn(-1f, 1f)
            val lo = mins[i].coerceIn(-1f, 1f)
            gc.fillRect(x0 + i * cw, midY - hi * half, barWidth, max((hi - lo) * half, 1f))
        }
    }

    /**
     * 波形视图的刻度与说明文字。
     *
     * 幅度轴的五档是自己画的：这里没有图表，`Axis.ticks()` 那一套用不上。档位值取的是
     * [WAVE_GRID_VALUES]——与上面画网格用的是同一份常量，不会出现"网格在 0.5、标签在 0.4"。
     */
    private fun drawWaveformLabels(gc: Gc, scene: AudioScene, plot: Rect, frame: Rect) {
        gc.fill = LABEL_COLOR
        gc.fontSize = LABEL_FONT
        val midY = plot.y + plot.height * 0.5f
        for (v in WAVE_GRID_VALUES) {
            val text = "%.1f".format(v)
            val w = gc.measureText(text)
            gc.drawText(text, plot.x - 6f - w, midY - v * plot.height * 0.5f + LABEL_FONT * 0.36f)
        }

        val bottomY = plot.y + plot.height + LABEL_FONT + 5f
        val rate = scene.sampleRate.toDouble()
        val file = scene.wave.file
        // 「已停止 / 未开始」并进标题（通用提示在波形视图里被压掉了，见 drawFrame）。
        val stopped = scene.source == null
        if (file != null) {
            val total = file.frameCount
            val action = if (stopped) "已停止 — 点波形跳到该处继续播" else "点波形跳到该处继续播"
            gc.drawText(
                "整个文件波形（共 %.3f s；%s）".format(total / rate, action),
                plot.x, frame.y + 12f,
            )
            if (total <= 0) return
            for (k in 0..4) {
                val f = k / 4.0
                val text = "%.2f s".format(f * total / rate)
                val w = gc.measureText(text)
                val x = plot.x + (f * plot.width).toFloat()
                // 两端的标签会露到绘图区之外，夹回来；极窄的窗口下 max() 兜住下限，
                // 免得 coerceIn 收到一个"下界大于上界"的区间直接抛。
                gc.drawText(text, (x - w / 2f).coerceIn(plot.x, max(plot.x, plot.x + plot.width - w)), bottomY)
            }
        } else {
            val history = scene.wave.history
            val head = when {
                !stopped -> "麦克风："
                history.totalSamples == 0L -> "未开始（点「打开 WAV 文件…」或「麦克风」）"
                else -> "已停止；麦克风："
            }
            gc.drawText(
                "${head}左 1/3 = 累积历史（压缩包络），右 2/3 = 实时（1 列 = 1 样本）",
                plot.x, frame.y + 12f,
            )
            val leftWidth = plot.width * WAVE_HISTORY_FRACTION
            gc.drawText(
                "历史 %.1f s（每列 %d 样本）".format(history.totalSamples / rate, history.samplesPerColumn),
                plot.x, bottomY,
            )
            val rightText = "实时 %.0f ms".format((plot.width - leftWidth) / rate * 1000.0)
            gc.drawText(rightText, plot.x + plot.width - gc.measureText(rightText), bottomY)
        }
    }

    private fun drawChart(gc: Gc, scene: AudioScene, chart: Chart, view: View) {
        val frame = Rect(
            FRAME_MARGIN,
            FRAME_MARGIN,
            gc.width - 2f * FRAME_MARGIN,
            gc.height - 2f * FRAME_MARGIN,
        )

        // 窗口每帧重设：声谱图的时间轴是滚动的，而 `Axis` 的窗口就是"现在看哪一段"。
        // （波形不走这条路——它自己画，见 [drawWaveform]。）
        when (view) {
            View.SPECTRUM -> {
                // x 轴单位是 bin：最后一个可见 bin 就是 Nyquist，Hz 由标签换算（见 spectrumLabel）。
                chart.axis(0).setWindow(0.0, scene.binCount.toDouble())
                chart.axis(1).setWindow(0.0, SPECTRUM_Y_MAX)
            }

            View.SPECTROGRAM -> {
                // 渲染器不读轴；窗口只要是个合法范围就行（不给的话 `withMinimumSpan` 会扩成 1.0）。
                chart.axis(0).setWindow(0.0, scene.ring.capacity().toDouble())
                chart.axis(1).setWindow(0.0, 1.0)
            }

            // 波形视图不挂图表（`drawFrame` 在进这里之前就分走了）。走到这里说明有人
            // 把波形重新接回了图表后端——那会把"整个文件"画成"环里最近 93 ms"，
            // 画面完全正常，所以宁可响亮地报错。
            View.WAVEFORM -> error("波形视图不走图表后端，见 drawWaveform")
        }

        val metrics = GcMetrics(gc, hasFont)
        val layout = ChartLayout.compute(chart, frame, metrics)
        val plot = layout.plotRect()
        if (plot.width <= 0f || plot.height <= 0f) return

        // displayLength 必须在算完布局之后设：`Axis.ticks()` 的位置依赖它。
        // （`ChartAxes` 画网格时用 `position / displayLength × 绘图区边长`，所以它与绘图区
        //   尺寸对不上也不会画歪——但本 demo 自绘的标签用的是同一条映射，两边必须一致。）
        chart.axis(0).setDisplayLength(plot.width.toDouble())
        chart.axis(1).setDisplayLength(plot.height.toDouble())

        // 绘图区底色**压栈**再画：`gc.fill` 是可变状态，写在外面会泄漏给本帧后续图元。
        gc.save()
        gc.fill = PLOT_BG
        gc.fillRect(plot.x, plot.y, plot.width, plot.height)
        gc.restore()

        // 背景落定。数据系列是当场就画的（实例化 draw call），不 flush 的话帧末提交的
        // 背景会把它们整个盖掉——画面上就是"什么都没有"，且不报错。
        gc.flush()

        gc.charts.drawChart(chart, frame, gc.width, gc.height)

        if (hasFont) drawAxisLabels(gc, scene, chart, plot, view)
    }

    /**
     * 自绘刻度标签。
     *
     * 用 `Axis.ticks()` 的主刻度**而不是自己按比例摊**：库画的网格线就在那些位置上，
     * 自己摊一套会让标签与网格错开——那看起来"只是没对齐"，排查方向会跑偏。
     *
     * 位置映射与 `ChartAxes` 用同一条：`position / displayLength × 绘图区边长`。
     */
    private fun drawAxisLabels(gc: Gc, scene: AudioScene, chart: Chart, plot: Rect, view: View) {
        gc.fontSize = LABEL_FONT
        gc.fill = LABEL_COLOR

        val xAxis = chart.axis(0)
        val yAxis = chart.axis(1)

        for (tick in xAxis.ticks()) {
            if (!tick.isMajor) continue
            val px = plot.x + (tick.position() / xAxis.displayLength()).toFloat() * plot.width
            if (px < plot.x || px > plot.x + plot.width) continue
            val text = when (view) {
                View.WAVEFORM -> continue        // 波形视图不挂图表，也不会走到这里
                View.SPECTRUM -> spectrumLabel(tick.value(), scene)
                View.SPECTROGRAM -> continue     // 声谱图的轴没有含义，标签在下面单独画
            }
            val w = gc.measureText(text)
            gc.drawText(text, px - w / 2f, plot.y + plot.height + LABEL_FONT + 5f)
        }

        if (view == View.SPECTROGRAM) {
            drawSpectrogramLabels(gc, plot, scene)
            return
        }

        for (tick in yAxis.ticks()) {
            if (!tick.isMajor) continue
            val py = plot.y + plot.height -
                    (tick.position() / yAxis.displayLength()).toFloat() * plot.height
            if (py < plot.y - 1f || py > plot.y + plot.height + 1f) continue
            val text = "%.2f".format(tick.value())
            val w = gc.measureText(text)
            gc.drawText(text, plot.x - 6f - w, py + LABEL_FONT * 0.36f)
        }
    }

    /**
     * 声谱图没有可用的轴（渲染器把一整块纹理铺满绘图区），所以自己画一圈边框与两端标签。
     *
     * 左侧的频率标签按**绘图区高度等比**：纹理第 0 行是最高频，而它的频率正是 Nyquist
     * （`(binCount-1) × Δf = 采样率/2`，这条等式成立不是因为巧合，见 `SpectrogramKernel`
     * 的行序约定），所以绘图区**上边缘 = 采样率/2**，往下线性递减到 0。
     */
    private fun drawSpectrogramLabels(gc: Gc, plot: Rect, scene: AudioScene) {
        val nyquist = scene.sampleRate / 2.0
        for (i in 0..4) {
            val fraction = i / 4f
            val freq = nyquist * (1.0 - fraction)
            val text = hzLabel(freq)
            val w = gc.measureText(text)
            val py = plot.y + fraction * plot.height
            gc.drawText(text, plot.x - 6f - w, py + LABEL_FONT * 0.36f)
        }
        // 时间轴的右端是"现在"：一个 hop 是 `transformLength/2` 个样本，一列一格。
        val hopSeconds = scene.transformLength / 2.0 / scene.sampleRate
        val spanSeconds = hopSeconds * SPECTROGRAM_COLUMNS
        gc.drawText("← 时间：右侧最新，整幅约 %.1f 秒".format(spanSeconds), plot.x, plot.y + plot.height + LABEL_FONT + 5f)
        gc.drawText("频率 ↑", plot.x + plot.width - gc.measureText("频率 ↑"), plot.y - 4f)
    }

    /** 频谱的频率标签：bin 下标 → Hz。Δf = 采样率 / 变换长度，最后一个 bin 是 Nyquist。 */
    private fun spectrumLabel(bin: Double, scene: AudioScene): String =
        hzLabel(bin * scene.sampleRate / scene.transformLength)

    private fun hzLabel(hz: Double): String =
        if (hz >= 1000.0) "%.1f kHz".format(hz / 1000.0) else "%.0f Hz".format(hz)

    // ==================================================================
    // 状态栏
    // ==================================================================

    /**
     * 状态栏第一行里那句"声音"。
     *
     * 它把**设备自己的帧位置**与**我们的播放头**一起打出来，理由见 `WavFileSource.lineFramePosition`：
     * 两个数都在，才分得清"声音在走"与"算式在走"。音量那一栏顺带说明"能不能调"——
     * 本机输出线没有 MASTER_GAIN 时滑块是禁用的，禁用而不说明与坏了差不多。
     */
    private fun playbackStatus(file: WavFileSource): String {
        val state = when {
            file.isPaused -> "已暂停"
            !file.playbackActive -> "已停止"
            else -> "播放中"
        }
        val percent = volumeSlider?.value ?: 0.0
        val volume = if (file.volumeSupported) {
            "音量 %.0f%%（%.1f dB）".format(percent, sliderToDb(percent.toInt()))
        } else {
            "音量不可调（本机输出线没有 MASTER_GAIN）"
        }
        return "声音=$state，声卡帧位置=${file.lineFramePosition} → 播放头样本=${file.positionSamples()}；$volume"
    }

    private fun refreshStatus() {
        val scene = sceneRef.get()
        val mic = scene.source as? MicrophoneSource
        // 按钮文字随状态切换（"可切换：开始/停止"）；它只在 JavaFX 线程上被读改。
        micButton?.text = if (micRunning) "停止麦克风" else "麦克风"
        val file = scene.wave.file
        // 控件状态跟着播放能力走：没有声卡就是**禁用**（并有一句话说明为什么），
        // 而不是一个点了/拖了没反应的控件。
        playButton?.isDisable = file?.playbackAttached != true
        playButton?.text = if (file?.isPaused == true) "播放" else "暂停"
        volumeSlider?.isDisable = file?.playbackActive != true || !file.volumeSupported
        sourceLabel.text = if (mic != null) {
            "$sourceDesc · 丢帧 ${mic.droppedFrames()}（队列满时丢最旧的块，丢帧数不静默）"
        } else {
            // 声音那一句并进第一行：它是"听不见"时第一眼要看的东西。
            val sound = when {
                file == null -> null
                !file.playbackAttached -> "声音不可用：${file.playbackError}（波形与频谱照常）"
                else -> playbackStatus(file)
            }
            if (sound == null) sourceDesc else "$sourceDesc · $sound"
        }

        val capacity = scene.ring.capacity()
        // 波形这一栏说明的是"它现在画的是什么尺度"，因为三种视图里只有波形的 x 轴不是
        // 一个统一的时间窗：文件是整个文件，麦克风是"历史 + 实时"两块。
        val waveInfo = scene.wave.file?.let {
            "波形=整个文件（${it.frameCount} 样本 @ ${scene.sampleRate} Hz，" +
                    "当前 ${it.positionSamples()}；点波形跳转）"
        } ?: "波形=麦克风（左 1/3 历史压缩包络，右 2/3 实时全分辨率）"
        val df = scene.sampleRate / scene.transformLength.toDouble()
        val gain = runCatching { windowFn.coherentGain(scene.transformLength) }.getOrDefault(Double.NaN)
        readoutLabel.text =
            "视图=${view.label}   $waveInfo   " +
                    "环容量=${capacity} 样本（频谱/声谱图的窗口）   " +
                    "变换长度=${scene.transformLength}（库按 min($FFT_LENGTH_CAP, 环容量) 决定，不可配；" +
                    "Δf=${"%.2f".format(df)} Hz，共 ${scene.binCount} 个 bin）   " +
                    "窗选择=${windowFn.name}（相干增益 ${"%.3f".format(gain)}）；" +
                    "GPU 频谱/声谱图当前固定用 Blackman-Harris，窗选择暂不改动它"
    }
}

/**
 * 应用入口。
 *
 * 命令打的是 `AudioDemoKt`，所以这个顶层 `main()` 是必需的——缺了它的报错是
 * "找不到 main 方法"，不是编译错，特别容易漏。
 */
fun main() {
    Application.launch(AudioDemoApp::class.java)
}
