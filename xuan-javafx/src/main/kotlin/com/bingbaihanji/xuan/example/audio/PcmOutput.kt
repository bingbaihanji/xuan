package com.bingbaihanji.xuan.example.audio

import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlin.math.min

/**
 * 音频输出：把 `[-1, 1]` 的单声道样本写成 16 位 PCM 灌进 [SourceDataLine]。
 *
 * ## 播放头为什么由声卡驱动
 * 这一层存在的**主要理由**不是"能出声"，而是**播放头有了真实来源**。
 * 墙钟估算（`已过去多少纳秒 × 采样率`）与听到的声音之间差着一个未知量：线要先把缓冲区
 * 填满才开始响，而缓冲区多大由驱动决定。于是墙钟一按下播放就超前一个缓冲区的时长，
 * 并且一直保持这个偏移——画面上是"播放头比声音早一点"，没有报错，也没有别的读数能指出它。
 *
 * [positionSamples] 读的是 `line.getLongFramePosition()`：**设备真正播出去的帧数**
 * （不是写进去的字节数——写进去的可以比播出去的多一整个缓冲区）。加上跳转基准就是
 * 绝对样本号，正好是波形上那条竖线该在的位置。
 *
 * ## 线程
 * - **只有那个写线程碰 `line`**——不光是数据面（`write` / `flush`），读位置也算。
 *   控制线程（JavaFX 那一侧）只写意图（[start] / [pause] / [resume] / [close]），
 *   其它线程读的是写线程发布的快照（见 [refreshPosition] 里那段原生锁的说明）。
 *   让两个线程都碰数据面会出事：`flush()` 与正在进行的 `write()` 撞上，缓冲区里会混进
 *   一小段**旧位置**的音频。听起来只是"跳转时咔一下"，很难追。
 * - 写线程**不全靠阻塞 `write()` 节流**，而是先问 `line.available()`：有空间就写满一块，
 *   没空间就睡几毫秒再看一眼。理由是同一条——跳转要能立刻生效。`write()` 阻塞时如果线被
 *   [pause] 停住，缓冲区永远不会腾出来，那个线程就再也醒不过来了。
 *
 * ## 拿不到设备
 * [open] 拿不到线时**抛** [IllegalStateException]（消息里写清为什么），不返回一个哑对象。
 * demo 那一侧把它变成状态栏与 stderr 上的一句话——宁可看见一个错，也不要一块静默无声的画布
 * （与 `MicrophoneSource` 同一条口径）。接不上的**退化行为**由调用方决定：`WavFileSource`
 * 会退回墙钟驱动的播放头，画面照常动，只是没有声音。
 */
class PcmOutput private constructor(
    private val line: SourceDataLine,
    private val sampleRate: Int,
) : AutoCloseable {

    /** 只保护"起线程/关线程"这类生命周期动作，写线程自己不用它（见类注释的线程一节）。 */
    private val lock = Any()

    private val seekRequest = AtomicInteger(NO_SEEK)

    /** 下一个要写出去的样本号（绝对）。只有写线程改它（[start] 里起线程那一次除外）。 */
    private var writeCursor = 0

    /** 绝对样本号 = [posOffset] + 线自己的帧位置。写线程在跳转时改它。 */
    @Volatile
    private var posOffset = 0L

    /** 关线之后位置就冻在这儿——线关了就读不到帧位置了。 */
    @Volatile
    private var lastPosition = 0L

    /** 同 [lastPosition]，存的是设备原始读数（未加跳转基准）。 */
    @Volatile
    private var lastLineFrame = 0L

    @Volatile
    private var samples: FloatArray = EMPTY_SAMPLES

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var closed = false

    @Volatile
    private var generation = 0

    private var thread: Thread? = null

    /** 文件已经写完、尾巴也播完了；换了位置要清掉（只有写线程碰）。 */
    private var tailDone = false

    /** 暂停中（线停着、位置冻着，排队的数据还留着，[resume] 从那儿接着放）。 */
    val isPaused: Boolean get() = paused

    /**
     * 当前播放到第几个样本（绝对样本号）。
     *
     * 它是**听到的声音**的位置，与"已经交给下游画图的样本数"可以差一个缓冲区——
     * 那个差正是这一层要暴露的东西，不是误差。
     *
     * 读的是**写线程发布的快照**，不是现场问线要——理由见 [refreshPosition]。
     */
    fun positionSamples(): Long = lastPosition

    /**
     * 设备自己的帧位置（**没有**加跳转基准），同样是写线程发布的快照。
     *
     * 与 [positionSamples] 的区别是它不含我们那一份算法：它是"设备说它播了多少帧"的原始读数。
     * 状态栏与冒烟日志把它和播放头一起打出来——只打播放头的话，"声音真的在走"与
     * "我们那个算式在涨"在屏幕上是一样的。
     */
    fun lineFramePosition(): Long = lastLineFrame

    /**
     * 读一次线、更新那两个快照。**只有写线程调**（[close] 里那一次除外）。
     *
     * 为什么不许别人直接读 `line.longFramePosition()`：JDK 的实现在它外层套了一把
     * 原生锁，`write` / `available` / `flush` 用的是同一把（`DirectAudioDevice`）。
     * 于是"JavaFX 线程顺手读一下位置"这件事，实质是**在音频设备锁上排队**——
     * 万一写线程正卡在原生调用里，界面就跟着一起卡，而栈上只会看到一次无辜的
     * `getLongFramePosition`。音频线程独占设备、其它线程只读快照，这条纪律与
     * "GL 资源只在 GL 线程上碰"是同一条。
     *
     * 快照的时效：写线程每轮都刷一次，最长间隔是 [`IDLE_POLL_MS`] 那几毫秒，
     * 远小于一帧（16 ms），所以播放头看起来仍然是连续的。
     */
    private fun refreshPosition() {
        if (closed) return
        val frames = line.longFramePosition
        lastLineFrame = frames
        lastPosition = posOffset + frames
    }

    /** 从 [fromSample] 开始播；跳转走同一条路。重复调用＝换位置继续播。 */
    fun start(data: FloatArray, fromSample: Int) {
        synchronized(lock) {
            check(!closed) { "这个输出已经关闭了" }
            val target = fromSample.coerceIn(0, data.size)
            samples = data
            running = true
            paused = false
            val t = thread
            if (t != null && t.isAlive) {
                // 已经在放：把跳转意图交给写线程，别在这儿碰线。
                seekRequest.set(target)
            } else {
                generation++
                val myGeneration = generation
                seekRequest.set(target)
                // 线是刚开的（帧位置从 0 起），先按它算一次位置基准与快照，跳转的读数就不会
                // 在头几毫秒里还指着旧位置。写线程起来之后会按 flush 后的帧位置重算一次。
                posOffset = target.toLong() - line.longFramePosition
                writeCursor = target
                tailDone = false
                refreshPosition()
                thread = Thread({ writerLoop(myGeneration, data) }, WRITER_THREAD_NAME).apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    /** 暂停：线的 I/O 停住，位置冻住，已经写进缓冲区的数据留着。 */
    fun pause() {
        if (!closed) paused = true
    }

    /** 从暂停处继续。 */
    fun resume() {
        if (!closed) paused = false
    }

    /** 本机的输出线支不支持 [FloatControl.Type.MASTER_GAIN]。不支持时 [setVolumeDb] 是空操作。 */
    val volumeSupported: Boolean = line.isControlSupported(FloatControl.Type.MASTER_GAIN)

    val volumeMinDb: Float get() = gainControl()?.minimum ?: 0f

    val volumeMaxDb: Float get() = gainControl()?.maximum ?: 0f

    /** 设音量。单位 dB；超出线支持的范围就夹回去。 */
    fun setVolumeDb(db: Float) {
        val control = gainControl() ?: return
        control.value = db.coerceIn(control.minimum, control.maximum)
    }

    private fun gainControl(): FloatControl? =
        if (volumeSupported) runCatching {
            line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        }.getOrNull() else null

    /**
     * 停写线程、flush、关线。
     *
     * 关线这一步不能省：设备句柄是**独占**的，换源几十次而不关，`getLine` 就会开始失败
     * （麦克风那条同一条理由）。顺序也是必需的：先让写线程退出去，再动线——
     * 反过来会在一个正在 `write` 的线上 flush/close，那是一次没有报错的数据损坏。
     */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            refreshPosition()
            closed = true
            running = false
            paused = false
        }
        // 写线程最多睡 IDLE_POLL_MS 一轮，等它是可数的（不像"卡在 write 里"那样无界）。
        runCatching { thread?.join(WRITER_JOIN_MS) }
        thread = null
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }

    // ------------------------------------------------------------------
    // 写线程
    // ------------------------------------------------------------------

    private fun writerLoop(myGeneration: Int, data: FloatArray) {
        val bytes = ByteArray(WRITE_FRAMES * PLAYBACK_BYTES_PER_FRAME)
        while (running && generation == myGeneration && !closed) {
            // 每轮先刷一次位置快照：其它线程（GL 画图、JavaFX 状态栏）读的是它，
            // 而不是直接去问设备（见 refreshPosition）。
            refreshPosition()

            val seek = seekRequest.getAndSet(NO_SEEK)
            if (seek != NO_SEEK) {
                applySeek(seek)
                continue
            }

            if (paused) {
                if (line.isRunning) line.stop()
                sleepQuietly(IDLE_POLL_MS)
                continue
            }

            if (!line.isRunning) line.start()

            val from = writeCursor
            if (from >= data.size) {
                // 全部写完了。队列里还剩不到一个缓冲区的数据在播，**等它播完再停线**：
                // 直接 stop() 会把结尾那几十毫秒剪掉，听起来是"最后被切了一刀"。
                if (!tailDone) {
                    awaitTail(myGeneration, data.size)
                    if (generation == myGeneration && !closed) runCatching { line.stop() }
                    tailDone = true
                }
                sleepQuietly(IDLE_POLL_MS)
                continue
            }

            // 有空间才写：这样每轮都是"不阻塞地写一块"，跳转与暂停都能在几毫秒内生效。
            val free = line.available() and 1.inv()
            if (free < PLAYBACK_BYTES_PER_FRAME) {
                sleepQuietly(IDLE_POLL_MS)
                continue
            }
            val count = min(WRITE_FRAMES, min(data.size - from, free / PLAYBACK_BYTES_PER_FRAME))
            if (count <= 0) {
                sleepQuietly(IDLE_POLL_MS)
                continue
            }
            encodePcm16(data, from, count, bytes)
            val written = runCatching { line.write(bytes, 0, count * PLAYBACK_BYTES_PER_FRAME) }.getOrDefault(-1)
            if (written <= 0) {
                sleepQuietly(IDLE_POLL_MS)
                continue
            }
            writeCursor = from + written / PLAYBACK_BYTES_PER_FRAME
        }
    }

    /**
     * 跳转：**丢掉已排队但没播的旧数据**，再把位置基准挪到新位置。
     *
     * `flush()` 之后才读帧位置是刻意的：读的是"真正播出去的帧数"，flush 前后它是不一样的状态
     * （丢掉的那些帧到底算不算"已播"，各家实现说法不一），而**先 flush 再算基准**让两种实现
     * 都对——基准是在那一刻校准的，之后位置从它开始涨。
     */
    private fun applySeek(target: Int) {
        val s = target.coerceIn(0, samples.size)
        runCatching { line.flush() }
        posOffset = s.toLong() - line.longFramePosition
        writeCursor = s
        tailDone = false
        refreshPosition()
    }

    /**
     * 等尾巴播完：位置追到文件末尾，或者等满了缓冲区那么久就放弃。
     *
     * 有界的等待是必需的：位置在这种场景下不一定按我们想的方式涨（线可能因为欠载而
     * 停在某个值上），无界等下去就是把一个线程永远挂在那儿。
     */
    private fun awaitTail(myGeneration: Int, limit: Int) {
        val deadline = System.nanoTime() + TAIL_WAIT_NANOS
        while (generation == myGeneration && running && !closed && System.nanoTime() < deadline) {
            if (!line.isRunning) return                 // 用户按了暂停：别把线停掉，位置也没在涨
            if (posOffset + line.longFramePosition >= limit) return
            sleepQuietly(IDLE_POLL_MS)
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            running = false
        }
    }

    companion object {
        /** 写线程的名字。线程转储里一眼能认出是谁。 */
        const val WRITER_THREAD_NAME = "xuan-pcm-writer"

        /** 一次写多少帧。1024 帧 ≈ 23 ms @44.1k：比它小是白加系统调用，比它大则跳转的粒度变粗。 */
        private const val WRITE_FRAMES = 1024

        /** 线内部的缓冲区大小（帧）。4096 帧 ≈ 93 ms @44.1k，是本机实测不欠载的档。 */
        private const val BUFFER_FRAMES = 4096

        /** 没空间可写/被暂停时睡多久。几毫秒：既是"不空转"，又让跳转与暂停能被及时看见。 */
        private const val IDLE_POLL_MS = 3L

        /** 等写线程退出的上限。它每轮最多睡 [IDLE_POLL_MS]，正常远小于这个数。 */
        private const val WRITER_JOIN_MS = 500L

        /** 等尾巴播完的上限：一个缓冲区那么久再加点余量。 */
        private const val TAIL_WAIT_NANOS = 200_000_000L

        private const val NO_SEEK = -1

        private val EMPTY_SAMPLES = FloatArray(0)

        /**
         * 打开一条 16 位单声道输出线。
         *
         * @throws IllegalStateException 本机没有可用的播放设备（消息是给用户看的一句话）
         */
        @Throws(IllegalStateException::class)
        fun open(sampleRate: Int): PcmOutput {
            val format = pcmPlaybackFormat(sampleRate)
            // 与麦克风那条同一条口径：**不**先拿 AudioSystem.isLineSupported 当闸门。
            // 它对格式几乎不设防（本机实测连 0 Hz 都报 supported），问它等于没问；
            // 真正会失败的是 getLine。
            val info = DataLine.Info(SourceDataLine::class.java, format)
            val line = try {
                AudioSystem.getLine(info) as SourceDataLine
            } catch (e: LineUnavailableException) {
                throw IllegalStateException(
                    "本机没有可用的播放设备：$format 打不开（驱动不支持，或已被其它程序独占）", e
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("本机没有可用的播放设备：没有匹配 $format 的输出线", e)
            }
            try {
                line.open(format, BUFFER_FRAMES * PLAYBACK_BYTES_PER_FRAME)
            } catch (e: LineUnavailableException) {
                throw IllegalStateException("本机没有可用的播放设备：$format 打不开（可能已被其它程序独占）", e)
            }
            return PcmOutput(line, sampleRate)
        }
    }
}

/**
 * 一条输出线的格式。**只有这一份**，因为这几个参数有踩过坑的地方：
 *
 * 第 5 个参数是**帧长**（声道数 × 每样本字节数 = 2），第 6 个是**帧率**（= 采样率）。
 * 顺手把第 5 个写成"采样率 × 2"（那是**字节率**，16 位单声道下恰好等于 帧率 × 帧长）会构造出
 * 一个帧长 88200 字节的格式：`AudioFormat` 的构造器**不检查**帧长与声道数是否自洽，
 * 于是它构造成功、`getLine` 抛 IllegalArgumentException、`isLineSupported` 全 false，
 * 看起来像"这台机器没有声卡"——上一版就在这儿白查了一轮。
 */
internal fun pcmPlaybackFormat(sampleRate: Int): AudioFormat = AudioFormat(
    AudioFormat.Encoding.PCM_SIGNED,
    sampleRate.toFloat(),
    PLAYBACK_BITS,
    PLAYBACK_CHANNELS,
    PLAYBACK_BYTES_PER_FRAME,
    sampleRate.toFloat(),
    false,
)

/** 输出位宽。16 位是 SourceDataLine 上最通用的一档，也是本 demo 解码之后统一的口径。 */
internal const val PLAYBACK_BITS = 16

internal const val PLAYBACK_CHANNELS = 1

internal const val PLAYBACK_BYTES_PER_FRAME = PLAYBACK_CHANNELS * PLAYBACK_BITS / 8

/**
 * 绝对样本号 = 跳转基准 + 声卡帧位置。
 *
 * 单独拆出来是为了能离线验它（`WavPlaybackTest`）：这条式子错了的表现是"播放头整段偏一个
 * 固定量"，而画面上完全正常——只有把两个数摆出来比才看得见。
 */
internal fun positionFromLineFrame(posOffsetSamples: Long, lineFramePosition: Long): Long =
    posOffsetSamples + lineFramePosition

/**
 * 退化模式（没有输出设备）的播放头：墙钟推算。
 *
 * 这就是加声卡之前的那份估算。它带着一个未知的缓冲偏移（见 [PcmOutput] 的类注释），
 * 所以只在真的拿不到设备时用；退化时 demo 会在状态栏里写明"没有声音"。
 */
internal fun positionFromWallClock(startSample: Int, elapsedNanos: Long, sampleRate: Int): Long =
    startSample + elapsedNanos * sampleRate / 1_000_000_000L

/**
 * 一个浮点样本 → 16 位 PCM 的整数值。
 *
 * 乘 32768 而不是 32767：与解码侧同一条尺规（`Pcm.kt` 里"满量程的一半是 32768"那一段）。
 * 乘 32767 会让"解码 → 播放"的往返整体差 0.003%——听不出来，但它是一条没有理由的偏差，
 * 而这条偏差恰好是"两个文件音量不一样"那类问题里最难查的那种。正的满量程在 int16 里
 * 放不下（+32768），夹到 32767。
 */
internal fun pcm16Value(sample: Float): Int = (sample * 32768f).toInt().coerceIn(-32768, 32767)

/** 把小端 16 位 PCM 写进 [out]（`[from, from + count)` 个样本 → `2 * count` 个字节）。 */
internal fun encodePcm16(samples: FloatArray, from: Int, count: Int, out: ByteArray) {
    for (i in 0 until count) {
        val v = pcm16Value(samples[from + i])
        out[i * 2] = (v and 0xFF).toByte()
        out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
    }
}

/**
 * 音量滑块（0..100）→ dB。
 *
 * 用对数刻度：滑块的百分比是**响度**的量，线性映射到 dB 会让 0% 附近连着好几格都听不出变化、
 * 100% 附近一格一跳。0% 直接给一个足够小的值当静音（`20*log10(0)` 是负无穷）。
 */
internal fun sliderToDb(percent: Int): Float {
    if (percent <= 0) return SILENT_DB
    if (percent >= 100) return 0f
    return (20.0 * log10(percent / 100.0)).toFloat()
}

/** 0% 时给的 dB。够小（听不见），又比 `MASTER_GAIN` 常见的下限 -80 dB 不至于超出。 */
internal const val SILENT_DB = -80f

/**
 * 输出设备工厂。存在的理由是**能把"没有声卡"这条分支测出来**：单测传一个"抛异常"的替身，
 * 就能验证退化路径（退回墙钟、画面照常、错误被报出来），而不必在 CI 机器上真去开设备。
 */
fun interface PcmOutputFactory {
    /** 打开一条输出线；拿不到就抛 [IllegalStateException]。 */
    fun open(sampleRate: Int): PcmOutput
}
