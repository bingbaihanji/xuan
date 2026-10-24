package com.bingbaihanji.xuan.example.audio

import java.io.File
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.math.roundToInt

/**
 * WAV 文件源：[构造][WavFileSource]时把整个文件解码进内存，之后按采样率实时吐样本，
 * **并接上声卡放出来**（见 [attachPlayback]）。
 *
 * ## 为什么构造时就整个解码
 * 边读边解要同时维护"文件游标 / 半个帧的残留 / 节流"三件事；而 demo 用的 WAV 都是秒级的
 * （44100 Hz 单声道 10 秒 ≈ 1.7 MB float），一次解码换来的是 [drainInto] 里只剩
 * "从内存切一段"这一件事，不必再和 IO 状态纠缠。
 *
 * 解码后的那一份 float 数组**同时**喂两个去处：声卡（[output]，听觉）与下游的环形缓冲
 * （[drainInto]，视觉）。一份数据、两个读者，两边读的区间由同一个播放头决定（见下）。
 *
 * ## 为什么只认 PCM
 * JDK 自带 ULAW / ALAW 这类编解码器，`AudioSystem` 会把它们**悄悄解开**再交出来。
 * 于是"拿到了音频"与"拿到的是我以为是的那种音频"就分不开了——音量刻度、位深、
 * 甚至采样率都可能与调用方以为的不同。本 demo 只解码 PCM，别的明确报错。
 *
 * ## 播放头（这一版的核心）
 * [positionSamples] 是波形上那条竖线的位置，也是 [drainInto] 决定"该吐到第几个样本"的依据。
 * 它有两个来源，**优先级明确**：
 *
 * | 情况 | 播放头 |
 * |---|---|
 * | 接了声卡（[attachPlayback] 成功） | 声卡的帧位置 + 跳转基准——**听到哪儿就是哪儿** |
 * | 没接上 / 没接（含单测那条 `realtime = false`） | 墙钟推算 |
 *
 * 墙钟那一路带着一个未知的缓冲偏移（数据要先填满驱动缓冲区才开始响），所以它只是**退化**：
 * 拿不到设备时画面照常动，但状态栏里会写明"没有声音"。它不会假装自己是声卡。
 *
 * ## 线程
 * [start] / [stop] 由 JavaFX 线程（换源、按按钮）调用，[drainInto] 由 GL 线程调用，
 * 所以那几个游标（`cursor` / `origin` / `startNanos` / `frozenPosition`）走同一把锁。
 * 锁只在每帧一次、且是无竞争的，代价可以忽略。
 * **声卡那一侧的写线程在 [PcmOutput] 内部**，它一次都不碰本类的状态。
 *
 * @param realtime `true`（默认）按采样率实时吐；`false` 不节流，第一次 [drainInto]
 *   就把剩下的全吐出来。**单测用 false**：要验的是"解出来的数值对不对"，
 *   带着节流的话断言只能写成"吐了一部分"，既弱又依赖墙钟。
 * @param outputFactory 打开输出设备的方式。默认就是 [PcmOutput.open]；单测传一个
 *   "总是抛"的替身来验"没有声卡"这条退化分支，而不去碰真实设备。
 */
class WavFileSource
@Throws(IOException::class, UnsupportedAudioFileException::class)
@JvmOverloads
constructor(
    file: File,
    private val realtime: Boolean = true,
    private val outputFactory: PcmOutputFactory = PcmOutputFactory { PcmOutput.open(it) },
) : AudioSource {

    /** 解成单声道的全部样本，`[-1, 1]`。构造之后不再变。 */
    private val samples: FloatArray

    override val sampleRate: Int

    /** 解码后的总样本数（单声道；一个样本 = 一个音频帧）。 */
    val frameCount: Int get() = samples.size

    private val lock = Any()
    private var running = false

    /** 已经交给下游的样本数（绝对）。它不是播放头，是"画到哪儿了"。 */
    private var cursor = 0

    /** 本次播放的起点（跳转也算换起点）。退化模式（墙钟）算播放头的基准。 */
    private var origin = 0
    private var startNanos = 0L

    /** 停止时的播放头。线关了之后读不到帧位置，播放头就停在这儿。 */
    private var frozenPosition = 0L

    /** 交给 sink 的那块数组，复用（见 [AudioSource.drainInto] 的"不许留引用"那条）。 */
    private var scratch = FloatArray(0)

    // ---- 下面是跨线程读的那几个（JavaFX 线程写，GL 线程每帧读）----

    /** 声卡输出。null = 现在没有在出声（没接上 / 已停止），播放头于是走墙钟或冻结值。 */
    @Volatile
    private var output: PcmOutput? = null

    /** 接上过声卡。[stop] 会把线关掉（设备句柄不能一直占着），所以按播放键时要能重开。 */
    @Volatile
    private var playbackWanted = false

    /** 当前音量（dB）。线重开之后要重新应用，所以留一份。 */
    @Volatile
    private var volumeDb: Float? = null

    /** 本机的输出线支不支持 MASTER_GAIN。音量控件据此决定"能调"还是"明说不能调"。 */
    @Volatile
    var volumeSupported = false
        private set

    /** 接不上输出设备时的原因（一句给用户看的话）。接上了、或没接过就是 null。 */
    @Volatile
    var playbackError: String? = null
        private set

    /** 现在有没有声音在响。 */
    val playbackActive: Boolean get() = output != null

    /** 接上过声卡（含"现在没在响但线可以重开"）。播放按钮据它决定自己能不能点。 */
    val playbackAttached: Boolean get() = playbackWanted

    /**
     * 设备自己的帧位置读数（**不含**跳转基准）。没有声卡时是 -1。
     *
     * 状态栏与冒烟日志把它和 [positionSamples] 一起打出来当证据：前者是设备说它播了多少帧，
     * 后者是我们按它算出来的播放头。只打后者的话，"声音真的在走"与"我们那个算式在涨"
     * 在屏幕上是一样的。
     */
    val lineFramePosition: Long get() = output?.lineFramePosition() ?: -1L

    /** 现在是不是暂停中（正在播、但线的 I/O 停着）。 */
    val isPaused: Boolean get() = output?.isPaused ?: false

    init {
        val decoded = decode(file)
        sampleRate = decoded.sampleRate
        samples = decoded.samples
    }

    // ------------------------------------------------------------------
    // 声卡
    // ------------------------------------------------------------------

    /**
     * 接上声卡：从这个源放出来的声音由本机设备负责，播放头也随之改由声卡驱动。
     *
     * 拿不到设备**不抛**、也不静默：返回 false，原因写在 [playbackError] 里，调用方
     * （demo 的状态栏 + stderr）负责把它说出来。这样"没有声卡"这台机器上 demo 仍然能用
     * ——波形与频谱照常，只是没有声音，而且屏幕上写着为什么。
     *
     * @return 接上了没有
     */
    fun attachPlayback(): Boolean {
        val out = openOutputOrNull() ?: return false
        synchronized(lock) { output = out }
        playbackWanted = true
        volumeDb?.let { out.setVolumeDb(it) }
        volumeSupported = out.volumeSupported
        return true
    }

    /** 暂停：[start] 之后可以 [resume] 接着放（排队的数据留着，位置冻在那儿）。 */
    fun pause() {
        output?.pause()
    }

    /** 从暂停处继续。 */
    fun resume() {
        output?.resume()
    }

    /**
     * 设音量（dB）。本机输出线没有 MASTER_GAIN 时这是空操作——[volumeSupported] 为 false，
     * 调用方（demo 的音量滑块）据它把控件做成**禁用并说明**，而不是一个拖了没反应的滑块。
     */
    fun setVolumeDb(db: Float) {
        volumeDb = db
        output?.setVolumeDb(db)
    }

    /** 打开输出设备；失败就把原因记在 [playbackError] 里并返回 null。**不抛**。 */
    private fun openOutputOrNull(): PcmOutput? = try {
        outputFactory.open(sampleRate).also { playbackError = null }
    } catch (e: Exception) {
        playbackError = e.message ?: e.toString()
        playbackWanted = false
        volumeSupported = false
        null
    }

    /**
     * 起播时确保有声卡：接上过就必须重开（[stop] 把线关了，设备句柄不能一直占着）。
     *
     * **开线在锁外做**：`AudioSystem.getLine` / `open` 是阻塞的原生调用（几十毫秒量级），
     * 而 GL 线程每帧要用 [lock] 读播放头——把它关在锁里，换源那一下就会掉一帧，
     * 而现象只是"偶尔卡一下"，很难查到音频头上。
     *
     * 只有 JavaFX 线程会调到这里（换源、按播放、点波形），所以不必担心两个线程同时开线。
     */
    private fun openOutputOnce(): PcmOutput? {
        output?.let { return it }
        if (!playbackWanted) return null
        val out = openOutputOrNull() ?: return null
        synchronized(lock) { output = out }
        volumeDb?.let { out.setVolumeDb(it) }
        volumeSupported = out.volumeSupported
        return out
    }

    // ------------------------------------------------------------------
    // 播放控制
    // ------------------------------------------------------------------

    override fun start() {
        startAt(0)
    }

    /**
     * 从第 [fromSample] 个样本开始播放（跳转），**位置基准与声卡一起挪过去**。
     *
     * 跳转时声卡那侧会 `flush()` 掉已排队但还没播的旧数据（见 `PcmOutput.applySeek`）——
     * 不丢的话点一下要等小半个缓冲区才听见新位置的声音，听起来像"点了没反应"。
     *
     * 越界的 [fromSample] 被夹回 `[0, 样本总数]`：夹到上界等于"这一遍已经放完"，
     * 再点一次比它靠前的位置就能继续，与 [start] 之后文件自然放完是同一种状态。
     */
    fun startAt(fromSample: Int) {
        val target: Int
        synchronized(lock) {
            target = fromSample.coerceIn(0, samples.size)
            cursor = target
            origin = target
            frozenPosition = target.toLong()
            startNanos = System.nanoTime()
            running = true
        }
        // 只有 realtime 才让声卡决定播放头：`realtime = false` 是"全速吐完"那条单测路径，
        // 那时位置就该是"已经吐到哪"，不该被设备的节奏拖住。
        // 声卡那一半**在锁外**：可能要开线（阻塞的原生调用），而 GL 线程每帧要用这把锁。
        if (realtime) (output ?: openOutputOnce())?.start(samples, target)
    }

    /**
     * 当前的播放位置（绝对样本号）。
     *
     * 有声卡时它就是**设备真正播出去的帧数**（+ 跳转基准）——即"你听到哪儿"；
     * 停了之后是停止那一刻的值；没有声卡时退回墙钟估算。
     *
     * 它同时也是跳转要传给 [startAt] 的那个数，所以播放头与"点在哪里"用的是同一个量，
     * 不会各说各话。
     */
    fun positionSamples(): Long = synchronized(lock) {
        if (!realtime) return cursor.toLong()
        if (!running) return frozenPosition.coerceIn(0L, samples.size.toLong())
        playhead(samples.size.toLong()).coerceIn(0L, samples.size.toLong())
    }

    /** 播放头（绝对样本号）。调用方需持有 [lock]。 */
    private fun playhead(limit: Long): Long {
        if (!realtime) return limit
        output?.let { return it.positionSamples() }
        // 退化：没有设备时用墙钟。它带着未知的缓冲偏移，所以状态栏里会写明"没有声音"。
        return positionFromWallClock(origin, System.nanoTime() - startNanos, sampleRate)
    }

    /**
     * 全部解码样本的**只读**视图。包络计算要顺序扫一遍，逐样本调用会把开销放在虚调用上。
     *
     * `internal` 是刻意的：这是内部数组本身，改了它等于改了源的数据（[startAt] 之后
     * 重放出来的就不是文件内容了）。包络那一侧只读，不要写。
     */
    internal fun rawSamples(): FloatArray = samples

    /**
     * 停止：停采集、**关掉声卡**（写线程 + 线），播放头停在当前值上。
     *
     * 关线放在锁外面：`PcmOutput.close()` 要 join 写线程（几毫秒，上限 500 ms），
     * 而 [drainInto] 在 GL 线程上要用同一把锁——把 join 关在锁里就是让渲染线程等音频线程。
     *
     * 线不关的后果不是"少一个资源"：设备句柄是独占的，换源几十次之后 `getLine` 会开始失败
     * （与 `MicrophoneSource` 关线同一条理由）。[startAt] 会按需要把它重开。
     */
    override fun stop() {
        val out = synchronized(lock) {
            if (realtime) frozenPosition = playhead(samples.size.toLong()).coerceIn(0L, samples.size.toLong())
            running = false
            output.also { output = null }
        }
        out?.close()
    }

    override fun drainInto(sink: (FloatArray, Int) -> Unit) {
        synchronized(lock) {
            // 没 start、已经 stop、或者文件已经放完——都什么都不吐。最后一种不是错误，
            // "文件放完了就停"是这个源的正常终点。
            if (!running) return
            val remaining = samples.size - cursor
            if (remaining <= 0) return

            // 该吐到哪 = 播放头。用「播放头 − 已吐」而不是「本帧时长 × 采样率」，小数部分才
            // 不会被每帧截掉（60 帧/秒 × 每帧丢不到 1 个样本 ≈ 每秒少 60 个样本，跑几分钟就
            // 听得出来——而在有声卡之后，更要紧的是画面与声音来自同一个读数）。
            val played = playhead(samples.size.toLong())
            val want = minOf(played - cursor, remaining.toLong()).toInt()
            if (want <= 0) return

            if (scratch.size < want) scratch = FloatArray(want)
            System.arraycopy(samples, cursor, scratch, 0, want)
            cursor += want
            sink(scratch, want)
        }
    }
}

/** 解码结果。[samples] 已降混成单声道。 */
private class Decoded(val sampleRate: Int, val samples: FloatArray)

/**
 * 打开并解码整个文件。
 *
 * 只接受 `PCM_SIGNED` / `PCM_UNSIGNED`；别的编码（ULAW 之类）**在这里就抛**，
 * 而不是解码出一堆听不出问题的垃圾——`AudioSystem` 的转码是静默的，不给它这个机会。
 */
@Throws(IOException::class, UnsupportedAudioFileException::class)
private fun decode(file: File): Decoded {
    AudioSystem.getAudioInputStream(file).use { stream ->
        val format = stream.format
        val encoding = format.encoding
        if (encoding != AudioFormat.Encoding.PCM_SIGNED && encoding != AudioFormat.Encoding.PCM_UNSIGNED) {
            throw IllegalArgumentException(
                "本 demo 只解码 PCM WAV，而 $file 里是 $encoding（$format）。" +
                    "请先导出成 PCM，例如 Audacity 的「WAV（16-bit PCM 签名）」再喂进来。"
            )
        }
        val bits = format.sampleSizeInBits
        require(bits == 8 || bits == 16 || bits == 24 || bits == 32) {
            "本 demo 只解码 8 / 16 / 24 / 32 位的 PCM，而 $file 里是 $bits 位"
        }
        val channels = format.channels
        val bytesPerSample = bits / 8
        // 一般 PCM 的 frameSize 恰好是"声道数 × 每样本字节数"。对不上说明有填充或别的花样，
        // 按固定步长硬解会把声道错位，所以宁可报错。
        require(format.frameSize == channels * bytesPerSample) {
            "帧长 ${format.frameSize} 与 $channels 声道 × $bytesPerSample 字节对不上，不敢按它解码"
        }
        val mono = readMono(
            stream = stream,
            channels = channels,
            bits = bits,
            bytesPerSample = bytesPerSample,
            bigEndian = format.isBigEndian,
            unsigned = encoding == AudioFormat.Encoding.PCM_UNSIGNED,
        )
        return Decoded(format.sampleRate.roundToInt(), mono)
    }
}

/** 每次向流要多少帧。取大一点纯粹是少几次系统调用。 */
private const val READ_FRAMES = 4096

/** 边读边降混。多声道取**算术平均**（规格 §2：不做多声道分离）。 */
private fun readMono(
    stream: AudioInputStream,
    channels: Int,
    bits: Int,
    bytesPerSample: Int,
    bigEndian: Boolean,
    unsigned: Boolean,
): FloatArray {
    val bytesPerFrame = channels * bytesPerSample
    val buffer = ByteArray(bytesPerFrame * READ_FRAMES)
    var out = FloatArray(buffer.size / bytesPerSample)
    var count = 0
    // 流的 read 不保证按帧对齐返回，尾部那不到一帧的字节要搬到下一轮的开头。
    var carry = 0
    while (true) {
        val n = stream.read(buffer, carry, buffer.size - carry)
        if (n <= 0) break
        val total = carry + n
        val ready = total / bytesPerFrame
        if (count + ready > out.size) {
            var grown = out.size * 2
            while (grown < count + ready) grown *= 2
            out = out.copyOf(grown)
        }
        for (frame in 0 until ready) {
            val base = frame * bytesPerFrame
            // 先归一化再求和（double）：8 声道 32 位 PCM 直接相加会溢出 int。
            var sum = 0.0
            for (channel in 0 until channels) {
                sum += pcmToFloat(buffer, base + channel * bytesPerSample, bits, bigEndian, unsigned).toDouble()
            }
            out[count + frame] = (sum / channels).toFloat()
        }
        count += ready
        carry = total - ready * bytesPerFrame
        if (carry > 0) System.arraycopy(buffer, total - carry, buffer, 0, carry)
    }
    return out.copyOf(count)
}
