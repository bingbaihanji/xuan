package com.bingbaihanji.xuan.example.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine

/**
 * 麦克风源：一条 [TargetDataLine] + 一个采集线程，样本经一个**有界**队列交给 [drainInto]。
 *
 * ## 为什么要队列，不直接读进下游缓冲
 * 采集线程按硬件节奏（约每 23 ms 一块）产数据，[drainInto] 按渲染节奏（约每 16 ms）取数据，
 * 两个节奏既不同步也不稳定——没有队列就得让其中一方等另一方。有了队列，采集线程永不阻塞，
 * 渲染那一侧跟不上时丢的是**最旧的**样本。
 *
 * ## 队列满时丢最旧，并计数
 * 队列长度是**时延**的直接来源：积压满 32 块就是 0.74 秒的滞后，画面上"嘴动了声音还没到"。
 * 所以宁可丢最旧也不让它无限长——少一段波形是看得见的，越拖越久的音画不同步更难受。
 * 丢了多少从 [droppedFrames] 读，**绝不静默**（同 `FXGLTransfer` 的点击队列）。
 *
 * ## 拿不到设备就明确报错
 * 构造时向混音器要线（此时**不占用设备**，真正打开是在 [start]），要不到就抛
 * `IllegalStateException`，消息里写清"本机没有可用的录音设备"。demo 的立场是宁可弹一个
 * 看得见的错，也不要一个静默无声的画布（规格 §10.3）。
 *
 * ## 为什么不做监听回放（把采到的声音从扬声器放出来）
 * 那是一条**正反馈回路**：扬声器 → 空气 → 同一个麦克风 → 再放大。回路增益只要接近 1，
 * 几毫秒内就会啸叫到满量程（这不是"可能有点吵"，是本机实测会发生的），而且它对用户是
 * 突然的、刺耳的。真要监听只能戴耳机，而"选哪个输出设备"已经超出本 demo 的范围。
 * 所以采集这条路只有输入、没有输出——`WavFileSource` 那边的声卡与这里无关。
 *
 * @param sampleRate 期望采样率。本机实测 44100 与 48000 都能拿到 DirectSound 的采集线。
 */
class MicrophoneSource(override val sampleRate: Int = DEFAULT_SAMPLE_RATE) : AudioSource {

    private val format = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        sampleRate.toFloat(),
        BITS,
        CHANNELS,
        // 帧长是"声道数 × 每样本字节数"（16 位单声道 = 2）。写成采样率的整数倍是常见的错，
        // 那是**字节率**，不是帧长——格式对不上时 getLine 会直接找不到设备。
        BYTES_PER_FRAME,
        sampleRate.toFloat(),
        false,
    )

    private val line: TargetDataLine = acquireLine()

    /** 生命周期（start / stop）用。采集线程只碰 [queueLock]，两把锁不会互相等待。 */
    private val lifecycle = Any()
    private val queueLock = Any()
    private val queue = ArrayDeque<FloatArray>()
    private var droppedTotal = 0L
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /**
     * 代际。采集线程每轮读回数据后都要比一次，不等就不入队。
     *
     * 为什么需要它：`stop()` 不 join 采集线程（它可能正卡在 `read` 里），
     * 于是重开时上一代线程可能还活着并把手上的旧数据推进来。清空队列之后又冒出一块
     * 上一代的数据，症状就是"换了源但图里混进了旧源的一小段"。
     */
    @Volatile
    private var generation = 0

    /** 采集线程异常退出时留下的异常；正常运行时为 null。 */
    @Volatile
    var captureError: Throwable? = null
        private set

    private fun acquireLine(): TargetDataLine {
        // 注意：这里**不**先用 AudioSystem.isLineSupported 做闸门。本机实测它对采样率几乎
        // 不设防（0 / 7 / 12345 Hz 全报 supported），问它等于没问；真正会失败的是 getLine。
        val info = DataLine.Info(TargetDataLine::class.java, format)
        return try {
            AudioSystem.getLine(info) as TargetDataLine
        } catch (e: LineUnavailableException) {
            throw IllegalStateException("本机没有可用的录音设备：$format 打不开（驱动不支持或被其它程序占用）", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("本机没有可用的录音设备：没有匹配 $format 的采集线", e)
        }
    }

    override fun start() {
        synchronized(lifecycle) {
            if (running) teardown() // 重复 start = 重新开始
            // 先推进代际再清队列：上一代线程就算还活着，它手上的数据也进不来了。
            generation++
            synchronized(queueLock) { queue.clear() }
            try {
                line.open(format)
            } catch (e: LineUnavailableException) {
                throw IllegalStateException("本机没有可用的录音设备：$format 打不开（可能已被其它程序占用）", e)
            }
            line.start()
            running = true
            val myGeneration = generation
            thread = Thread({ captureLoop(myGeneration) }, "xuan-microphone").apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun stop() {
        synchronized(lifecycle) {
            if (running) teardown()
        }
    }

    /**
     * 队列满时被丢掉的**样本数**（单声道，一个样本 = 一个音频帧）。大于 0 就说明下游跟不上。
     */
    fun droppedFrames(): Long = synchronized(queueLock) { droppedTotal }

    override fun drainInto(sink: (FloatArray, Int) -> Unit) {
        while (true) {
            val chunk = synchronized(queueLock) {
                if (queue.isEmpty()) null else queue.removeFirst()
            } ?: return
            sink(chunk, chunk.size)
        }
    }

    /** 停采集线程、关线。调用方需持有 [lifecycle]。 */
    private fun teardown() {
        running = false
        if (line.isOpen) {
            line.stop()
            line.flush()
        }
        // 最多等 200 ms：卡在 read 里的那一次通常会被 stop/flush 唤醒，
        // 没醒也没关系——close 会让它抛出来自己退出，代际检查兜住它手里的数据。
        thread?.join(200)
        thread = null
        line.close()
    }

    private fun captureLoop(myGeneration: Int) {
        val bytes = ByteArray(CHUNK_FRAMES * BYTES_PER_FRAME)
        while (running && generation == myGeneration) {
            val n = try {
                line.read(bytes, 0, bytes.size)
            } catch (e: Exception) {
                // 收尾时关线会让 read 抛出来，那不算故障；还在跑却抛了就是真故障，记下来。
                if (running && generation == myGeneration) captureError = e
                return
            }
            if (n <= 0) continue
            val frames = n / BYTES_PER_FRAME
            if (frames <= 0) continue
            val chunk = FloatArray(frames)
            for (i in 0 until frames) {
                chunk[i] = pcmToFloat(bytes, i * BYTES_PER_FRAME, BITS, bigEndian = false, unsigned = false)
            }
            // 读一次要几十毫秒，这期间可能已经 stop/换源了，所以入队前再确认一次代际。
            if (!running || generation != myGeneration) return
            push(chunk)
        }
    }

    private fun push(chunk: FloatArray) {
        synchronized(queueLock) {
            while (queue.size >= MAX_QUEUED_CHUNKS) {
                droppedTotal += queue.removeFirst().size
            }
            queue.addLast(chunk)
        }
    }

    companion object {
        /** 默认采样率。44100 是 WAV 与采集设备里最通用的一档。 */
        const val DEFAULT_SAMPLE_RATE = 44100

        private const val BITS = 16
        private const val CHANNELS = 1
        private const val BYTES_PER_FRAME = CHANNELS * BITS / 8

        /** 每次从线里读多少帧。1024 帧 ≈ 23 ms @44.1k：再小是白加调度开销，再大是白加时延。 */
        private const val CHUNK_FRAMES = 1024

        /** 队列上限（块）。32 块 ≈ 0.74 秒，够吸收一次 GC/掉帧的抖动，又不会积出可感的时延。 */
        private const val MAX_QUEUED_CHUNKS = 32
    }
}
