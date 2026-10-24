package com.bingbaihanji.xuan.example.audio

import kotlin.math.max
import kotlin.math.min

/**
 * 波形的 min/max 包络：把"一屏像素列"与"它底下那一段样本"对应起来。
 *
 * 为什么必须有它：一个 3 分钟的 44.1 kHz 单声道文件有 800 万个样本，而绘图区只有一千来个
 * 设备像素列；一列样本摊到一列像素上，**既画不动也不是要看的形状**——真正有信息量的是
 * 那一列上样本的取值范围（min/max），把它画成一条竖线就是音频软件里那种"整段波形"。
 *
 * 这里的两个类都只做算术，不碰 GL，也不碰任何 JavaFX 类型。
 */

/**
 * 麦克风的**累积历史**包络：输入无限长，内存固定。
 *
 * 边采边压：攒够 [samplesPerColumn] 个样本就产出一列（记下这段的 min 与 max）。
 * 列数封顶 [capacity]，封顶之后把相邻两列合成一列、并把 [samplesPerColumn] 翻倍。
 * 于是无论录多久，占用的都是 [capacity] 对 float；代价是越老的那一段分辨率越粗
 * （每列代表的样本数翻倍增长）——那是"整段历史都要看得见"的必然代价，文件那种
 * 一次算完的包络没有这个问题，因为它的总长是已知的。
 *
 * 这个类只在 GL 线程上被读写（[feed] 在帧首的 drain 里、绘制在帧中），所以没有锁。
 *
 * @param capacity 列数上限，必须是偶数（压缩是按相邻两列成对合并的）
 */
internal class HistoryEnvelope(private val capacity: Int = DEFAULT_COLUMNS) {

    init {
        require(capacity >= 2 && capacity % 2 == 0) {
            "列数上限必须是不小于 2 的偶数（压缩成对合并），实际 $capacity"
        }
    }

    private val colMin = FloatArray(capacity)
    private val colMax = FloatArray(capacity)

    /** 已经产出的列数。 */
    var size = 0
        private set

    /** 每一列代表多少个样本。每压缩一次翻倍。 */
    var samplesPerColumn = 1
        private set

    /** 已经吃进去的样本总数（含还没攒满一列的那些）。 */
    var totalSamples = 0L
        private set

    private var accMin = 0f
    private var accMax = 0f
    private var accCount = 0

    /** 一列的 min 值。只在 `0 until size` 上有定义。 */
    fun minAt(index: Int): Float = colMin[index]

    /** 一列的 max 值。只在 `0 until size` 上有定义。 */
    fun maxAt(index: Int): Float = colMax[index]

    /** 底层的 min 缓冲，供一次性批量绘制用（读取前请确认 `0 until size` 有效）。 */
    fun minBuffer(): FloatArray = colMin

    /** 底层的 max 缓冲，同 [minBuffer]。 */
    fun maxBuffer(): FloatArray = colMax

    /** 清空。换源时用——沿用旧源的历史会让画面上挂着上一路信号。 */
    fun reset() {
        size = 0
        samplesPerColumn = 1
        totalSamples = 0
        accMin = 0f
        accMax = 0f
        accCount = 0
    }

    /**
     * 吃进一块新样本。
     *
     * 只读 `[0, n)`，与 `AudioSource.drainInto` 的约定一致（那块数组是复用的）。
     */
    fun feed(data: FloatArray, n: Int) {
        for (i in 0 until n) {
            val v = data[i]
            if (accCount == 0) {
                accMin = v
                accMax = v
            } else {
                if (v < accMin) accMin = v
                if (v > accMax) accMax = v
            }
            accCount++
            totalSamples++
            if (accCount >= samplesPerColumn) emit()
        }
    }

    private fun emit() {
        // 满了先压缩再放：压缩之后 size 减半，这一列放得进去。
        if (size == capacity) compact()
        colMin[size] = accMin
        colMax[size] = accMax
        size++
        accCount = 0
    }

    /**
     * 把相邻两列合并成一列，分辨率减半。
     *
     * 合的是 min/max 而不是样本本身，所以合并是精确的：这一段样本的最小值就是两个子段
     * 最小值的最小值，最大值同理。这也是"内存固定"与"不丢尖峰"能同时成立的原因。
     */
    private fun compact() {
        // 已经攒了一半的那一列（accCount 个样本）不参与压缩、也不重置：它的样本数
        // 在压缩后仍然按新的分辨率继续攒，于是最右边那一列在压缩发生的那一帧里代表的
        // 时间略短于左边的列。那是画面上看不出来的一点（一列对几百列），换掉它的写法
        // 是"把残余样本也按 2:1 合并"——那要额外记账，不值得。
        val half = capacity / 2
        for (i in 0 until half) {
            val a = i * 2
            colMin[i] = min(colMin[a], colMin[a + 1])
            colMax[i] = max(colMax[a], colMax[a + 1])
        }
        size = half
        samplesPerColumn *= 2
    }

    companion object {
        /**
         * 列数上限。
         *
         * 取 1024：它比任何窗口下的绘图区像素列都多（左边界那一块约 350 设备像素），
         * 所以绘制时不会因为"存的列不够"而出现空白；而 1024 对 float 只有 8 KB，
         * 录几个小时也不会长。再大只是白白占用，因为超过绘图区像素数的列最终会被
         * 绘制时重叠着画在同一列像素上。
         */
        const val DEFAULT_COLUMNS = 1024
    }
}

/**
 * 文件全览包络：整个文件按 [columns] 列压成 min/max。
 *
 * 与 [HistoryEnvelope] 的区别是它**不是增量的**——文件的总长已知，所以每列代表的样本数
 * 一次算清楚（`frameCount / columns`），不需要压缩、也不会越老越粗。
 *
 * 代价是重建一次要扫一遍全部样本。调用方必须做缓存（按"源 + 列数"判断是否需要重建）：
 * 每帧重建一个 800 万样本的包络是几百毫秒，而它只在换源与改窗口大小的时候才会变。
 */
internal class FileEnvelope {

    private var mins = FloatArray(0)
    private var maxs = FloatArray(0)

    /** 当前包络的列数；0 表示还没有可用的包络。 */
    var columns = 0
        private set

    /** 建包络时读到的样本总数，用来把 x 位置换算成时间。 */
    var frameCount = 0
        private set

    fun minAt(index: Int): Float = mins[index]

    fun maxAt(index: Int): Float = maxs[index]

    fun minBuffer(): FloatArray = mins

    fun maxBuffer(): FloatArray = maxs

    /**
     * 按 [columns] 列重建包络。列数或源变了才该调用它。
     *
     * 每列的样本区间用**整数除法**算边界（`[c*n/columns, (c+1)*n/columns)`），
     * 而不是累加一个浮点步长：累加会在长文件上漂移，漂到最后几列时边界跑到数组之外，
     * 而"越界读"在 Kotlin 里是抛异常、不是静默错——但真正该防的是漂移本身，
     * 那会让包络与文件内容对不上（画面看不出，读数全错）。
     */
    fun build(source: WavFileSource, columns: Int) {
        val data = source.rawSamples()
        val n = data.size
        frameCount = n
        if (columns <= 0 || n <= 0) {
            this.columns = 0
            return
        }
        if (mins.size < columns) {
            mins = FloatArray(columns)
            maxs = FloatArray(columns)
        }
        for (c in 0 until columns) {
            val from = (c.toLong() * n / columns).toInt()
            val to = ((c + 1L) * n / columns).toInt()
            if (to <= from) {
                // 列比样本还多（文件极短）：这一列没有样本，画成一条零线。
                mins[c] = 0f
                maxs[c] = 0f
                continue
            }
            var lo = data[from]
            var hi = lo
            for (i in from + 1 until to) {
                val v = data[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            mins[c] = lo
            maxs[c] = hi
        }
        this.columns = columns
    }
}
