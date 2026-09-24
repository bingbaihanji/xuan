package com.bingbaihanji.jfgl.renderer

import com.bingbaihanji.jfgl.math.Mat3
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * [Gc] 的纯坐标状态：像素→NDC 的基础矩阵、用户变换栈、裁剪栈，以及三者之间的换算。
 *
 * <p>本类<strong>不做任何 GL 调用，也不依赖 GL 上下文</strong>，因此可以脱离窗口直接单测。
 * 之所以把它从 [Gc] 里拆出来，是因为坐标换算是本项目反复出错的地方
 * （裁剪矩形在 GL 侧的 y 翻转、像素与 NDC 的单位混用、`limit` 与 `capacity` 之分的同类问题），
 * 而这些数学<strong>完全不需要 GL 就能验证</strong>；[Gc] 本身因为持有 [RenderBatch]
 * 而无法在无上下文的环境中构造。
 *
 * ## 三套坐标
 * - **用户空间**：像素、原点左上、y **向下**。
 * - **NDC**：x 向右、y **向上**，范围 `[-1, 1]`。
 * - **设备像素**：帧缓冲像素、原点左上、y **向下**——与用户空间同向，与 `glScissor` **相反**。
 *
 * ## 裁剪矩形的表示
 * 裁剪矩形在设备像素下用半开区间 `[left, right) × [top, bottom)` 表示：left/top 含、right/bottom 不含。
 * 这样"交集为空"就是 `right <= left`（或 `bottom <= top`），
 * 既不需要 `Rect` 哨兵值，也不需要为了 `save()` 而分配对象——栈就是一整块 float 数组。
 * 注意 left/top 是矩形的**顶边**，与 [DrawCommand] 的 `scissorY` 约定一致。
 */
internal class ViewTransform {

    /** 帧缓冲宽度（像素）。[beginFrame] 之前为 0。 */
    var viewportWidth: Int = 0
        private set

    /** 帧缓冲高度（像素）。[beginFrame] 之前为 0。 */
    var viewportHeight: Int = 0
        private set

    /** 当前用户变换（已含像素→NDC 的基础矩阵）。 */
    var matrix: Mat3 = Mat3.identity()
        private set

    /** 当前裁剪矩形左边界（设备像素，y 向下，含）。 */
    var clipLeft: Float = 0f
        private set

    /** 当前裁剪矩形上边界（设备像素，y 向下，含）。 */
    var clipTop: Float = 0f
        private set

    /** 当前裁剪矩形右边界（设备像素，y 向下，不含）。 */
    var clipRight: Float = 0f
        private set

    /** 当前裁剪矩形下边界（设备像素，y 向下，不含）。 */
    var clipBottom: Float = 0f
        private set

    /** 当前 [save] 的层数（即 [restore] 还能回退几次）。 */
    val stackDepth: Int
        get() = levels

    /**
     * [matrix] 的列优先副本。
     *
     * <p>[Mat3] 只提供返回副本的 [Mat3.toArray]，逐顶点变换若每次都走
     * [Mat3.transform] 会为每个顶点分配一个 `Vec2`。这里在<strong>变换改变时</strong>
     * （远少于顶点数）刷新一次副本，让 [transformX]/[transformY] 在热路径上零分配。
     * 全部改动都经由 [setMatrix]，二者不会失同步（`ViewTransformTest` 里有对应不变量测试）。
     */
    private val elements = FloatArray(9)

    /** 变换栈。每层保存一个 [Mat3] 引用。 */
    private val matrixStack = ArrayDeque<Mat3>()

    /** 裁剪栈。每层占 4 个 float：left、top、right、bottom。 */
    private var clipStack = FloatArray(INITIAL_STACK_LEVELS * 4)

    /** 变换栈与裁剪栈共用的层数，二者始终同步。 */
    private var levels = 0

    /** 计算裁剪包围盒时复用的用户坐标角点（4 个角，共 8 个 float）。 */
    private val cornerScratch = FloatArray(8)

    /** 换算设备像素包围盒时复用的输出数组，含义见 [toDeviceBounds]。 */
    private val boundsScratch = FloatArray(4)

    init {
        setMatrix(Mat3.identity())
    }

    // ------------------------------------------------------------------
    // 帧
    // ------------------------------------------------------------------

    /**
     * 开始一帧：设定视口尺寸、把变换重置为基础矩阵、清空两个栈、把裁剪恢复成整个帧缓冲。
     *
     * @param width  帧缓冲宽度（像素），必须为正
     * @param height 帧缓冲高度（像素），必须为正
     * @throws IllegalArgumentException 宽或高不为正时（会导致基础矩阵出现除零产生的 Infinity/NaN）
     */
    fun beginFrame(width: Int, height: Int) {
        require(width > 0 && height > 0) { "帧缓冲尺寸必须为正：${width}x$height" }
        viewportWidth = width
        viewportHeight = height
        matrixStack.clear()
        levels = 0
        setMatrix(baseMatrix(width, height))
        clipLeft = 0f
        clipTop = 0f
        clipRight = width.toFloat()
        clipBottom = height.toFloat()
    }

    /**
     * 清空两个栈，返回被丢弃的层数（当前变换与裁剪**不**回退，仅栈被清掉）。
     *
     * <p>供 [Gc.endFrame] 在一帧结束时兜底：若用户漏了 [restore]，栈会一直长下去并污染后续帧。
     *
     * @return 被丢弃的层数，0 表示栈本来就是平衡的
     */
    fun clearStack(): Int {
        val dropped = levels
        levels = 0
        matrixStack.clear()
        return dropped
    }

    // ------------------------------------------------------------------
    // 变换
    // ------------------------------------------------------------------

    /** 压入当前变换与裁剪状态。 */
    fun save() {
        matrixStack.addLast(matrix)
        ensureClipCapacity(levels + 1)
        val base = levels * 4
        clipStack[base] = clipLeft
        clipStack[base + 1] = clipTop
        clipStack[base + 2] = clipRight
        clipStack[base + 3] = clipBottom
        levels++
    }

    /**
     * 弹出并恢复最近一次 [save] 的变换与裁剪状态。
     *
     * @throws IllegalStateException 没有配对的 [save] 时
     */
    fun restore() {
        check(levels > 0) { "restore() 与 save() 不配对：当前栈为空，没有可恢复的状态" }
        levels--
        setMatrix(matrixStack.removeLast())
        val base = levels * 4
        clipLeft = clipStack[base]
        clipTop = clipStack[base + 1]
        clipRight = clipStack[base + 2]
        clipBottom = clipStack[base + 3]
    }

    /**
     * 在当前变换之后追加平移（用户坐标单位）。
     *
     * @param tx 沿 x 轴的平移量
     * @param ty 沿 y 轴的平移量（y 向下）
     */
    fun translate(tx: Float, ty: Float) {
        setMatrix(matrix.multiply(Mat3.translation(tx, ty)))
    }

    /**
     * 在当前变换之后追加缩放。
     *
     * @param sx 沿 x 轴的缩放因子
     * @param sy 沿 y 轴的缩放因子
     */
    fun scale(sx: Float, sy: Float) {
        setMatrix(matrix.multiply(Mat3.scale(sx, sy)))
    }

    /**
     * 在当前变换之后追加旋转，**正值在屏幕上是顺时针**（与 HTML Canvas、JavaFX 一致）。
     *
     * <p>不需要对角度取反：用户空间 y 向下，而 [Mat3.rotation] 是数学意义上的逆时针，
     * 两者相互抵消。`rotate(+90)` 把屏幕上的"右"方向转到"下"方向，见 `ViewTransformTest`。
     *
     * @param degrees 旋转角度（度），正值为屏幕上的顺时针
     */
    fun rotate(degrees: Float) {
        setMatrix(matrix.multiply(Mat3.rotation(Math.toRadians(degrees.toDouble()).toFloat())))
    }

    /**
     * 用当前变换对用户坐标点做变换，返回 NDC 下的 x 分量。
     *
     * <p>热路径专用：与 [transformY] 一样直接读 [elements]，不产生任何分配。
     *
     * @param x 用户坐标 x
     * @param y 用户坐标 y
     * @return 变换后的 NDC x
     */
    fun transformX(x: Float, y: Float): Float = elements[0] * x + elements[3] * y + elements[6]

    /**
     * 用当前变换对用户坐标点做变换，返回 NDC 下的 y 分量。
     *
     * @param x 用户坐标 x
     * @param y 用户坐标 y
     * @return 变换后的 NDC y
     */
    fun transformY(x: Float, y: Float): Float = elements[1] * x + elements[4] * y + elements[7]

    /**
     * 返回当前变换把**用户单位**换算成**设备像素**的平均缩放因子。
     *
     * <p>用途是给 `Flattener` 的容差与圆的分段数提供"一个用户单位等于多少设备像素"。
     * 因此这里必须把基础矩阵（像素→NDC）也算进去，否则会因为漏掉视口尺寸而差出约 W/2 倍：
     * 曲线会被过度细分、圆的段数恒定顶到上限。
     *
     * <p>做法是把 [matrix] 的两个基向量分别映射到 NDC，再乘上 NDC→设备像素的线性因子
     * `(viewportWidth / 2, viewportHeight / 2)`（y 的符号对长度无影响），最后取两个轴长的平均。
     * 恒等变换下结果为 1——"一个用户单位就是一个像素"。
     *
     * @return 两个轴向缩放因子的算术平均
     */
    fun matrixScale(): Float {
        val halfWidth = viewportWidth * 0.5f
        val halfHeight = viewportHeight * 0.5f
        val sx = hypot(elements[0] * halfWidth, elements[1] * halfHeight)
        val sy = hypot(elements[3] * halfWidth, elements[4] * halfHeight)
        return (sx + sy) * 0.5f
    }

    // ------------------------------------------------------------------
    // 裁剪
    // ------------------------------------------------------------------

    /**
     * 用矩形裁剪后续绘制，矩形按**用户坐标**给出，与当前变换相乘后与已有裁剪**求交**。
     *
     * <p><strong>已声明的降级：当前变换含旋转时，实际生效的是旋转后矩形的
     * 轴对齐包围盒</strong>，而不是旋转矩形本身。原因在更下层——裁剪靠 `glScissor`，
     * 它按定义只能是轴对齐矩形；旋转裁剪要模板缓冲或着色器里的遮罩，本项目都没有。
     *
     * <p>方向是**单向**的：包围盒恒**包含**旋转后的矩形（它是那四个角点的包围盒），
     * 所以这个降级只会让裁剪区变大——**裁少，不会裁多**；代价是旋转矩形之外、
     * 包围盒之内的内容照样显示、照样可拾取。等价的说法：它永远不会吞掉本该显示的内容。
     * 这一点由 `ViewTransformTest.旋转裁剪的包围盒只多不少` 钉着。
     *
     * <p>只含平移与轴对齐缩放（不含旋转）时包围盒与变换后的矩形**重合**，
     * 没有任何降级。
     *
     * <p>设备像素上按"覆盖所有被矩形碰到的像素"取整——左/上边界向下取整、右/下边界向上取整，
     * 因此可能比用户给出的矩形最多多出不到一个像素，但绝不会漏掉任何被裁剪区域覆盖的像素。
     * 这个取整方向与上面的包围盒一致，都是"宁可多覆盖"。
     *
     * @param x 矩形左上角 x（用户坐标）
     * @param y 矩形左上角 y（用户坐标，y 向下）
     * @param w 矩形宽度（负值时等价于取绝对值，四个角点求包围盒不区分正负）
     * @param h 矩形高度（负值时等价于取绝对值，四个角点求包围盒不区分正负）
     */
    fun clipRect(x: Float, y: Float, w: Float, h: Float) {
        cornerScratch[0] = x
        cornerScratch[1] = y
        cornerScratch[2] = x + w
        cornerScratch[3] = y
        cornerScratch[4] = x + w
        cornerScratch[5] = y + h
        cornerScratch[6] = x
        cornerScratch[7] = y + h

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < 4) {
            val ux = cornerScratch[i * 2]
            val uy = cornerScratch[i * 2 + 1]
            val nx = transformX(ux, uy)
            val ny = transformY(ux, uy)
            if (nx < minX) minX = nx
            if (nx > maxX) maxX = nx
            if (ny < minY) minY = ny
            if (ny > maxY) maxY = ny
            i++
        }

        toDeviceBounds(minX, minY, maxX, maxY, boundsScratch)
        // 与当前裁剪求交（嵌套 clipRect 是求交，不是替换）
        if (boundsScratch[0] > clipLeft) clipLeft = boundsScratch[0]
        if (boundsScratch[1] > clipTop) clipTop = boundsScratch[1]
        if (boundsScratch[2] < clipRight) clipRight = boundsScratch[2]
        if (boundsScratch[3] < clipBottom) clipBottom = boundsScratch[3]
    }

    /**
     * 把 NDC 包围盒换算成设备像素包围盒，写进 `out`。
     *
     * <p>输出顺序为 `out[0]=left`、`out[1]=top`、`out[2]=right`、`out[3]=bottom`，
     * 全部为 **y 向下的设备像素**；`top` 是**顶边**（数值较小的一侧）。
     * NDC 的 y 向上，`ndcMaxY` 对应包围盒的上边，换算后仍落在较小的设备 y 上——
     * 这一步正是 `glScissor` 的 y 翻转容易搞反的地方（翻转只发生在 GL 侧，见 [DrawCommand]）。
     *
     * @param ndcMinX 包围盒在 NDC 下的最小 x
     * @param ndcMinY 包围盒在 NDC 下的最小 y（几何上的**下**边）
     * @param ndcMaxX 包围盒在 NDC 下的最大 x
     * @param ndcMaxY 包围盒在 NDC 下的最大 y（几何上的**上**边）
     * @param out      接收 4 个 float 的数组，长度至少为 4
     */
    fun toDeviceBounds(ndcMinX: Float, ndcMinY: Float, ndcMaxX: Float, ndcMaxY: Float, out: FloatArray) {
        require(out.size >= 4) { "输出数组长度至少为 4，实际为 ${out.size}" }
        val xa = deviceX(ndcMinX)
        val xb = deviceX(ndcMaxX)
        val ya = deviceY(ndcMaxY)
        val yb = deviceY(ndcMinY)
        out[0] = minOf(xa, xb)
        out[1] = minOf(ya, yb)
        out[2] = maxOf(xa, xb)
        out[3] = maxOf(ya, yb)
    }

    /**
     * NDC 的 x → 设备像素 x（y 向下，原点左上）。NDC 的 -1 对应 0，+1 对应 [viewportWidth]。
     *
     * @param ndcX NDC 坐标 x
     * @return 设备像素 x
     */
    fun deviceX(ndcX: Float): Float = (ndcX * 0.5f + 0.5f) * viewportWidth

    /**
     * NDC 的 y → 设备像素 y（y 向下，原点左上）。NDC 的 +1（屏幕上方）对应 0，
     * -1（屏幕下方）对应 [viewportHeight]。
     *
     * @param ndcY NDC 坐标 y
     * @return 设备像素 y
     */
    fun deviceY(ndcY: Float): Float = (0.5f - ndcY * 0.5f) * viewportHeight

    /** 当前裁剪矩形左边界（设备像素整数），向下取整。 */
    val clipDeviceX: Int
        get() = floor(clipLeft + SNAP_EPSILON).toInt()

    /**
     * 当前裁剪矩形**上边缘** y（设备像素整数），向下取整。
     *
     * <p>这是交给 [DrawCommand] 的 `scissorY` 的值：约定为顶边、y 向下。
     * `RenderBatch.applyScissor` 会在 GL 侧翻成 `viewportHeight - scissorY - scissorHeight`，
     * 此处<strong>不要</strong>再翻一次。
     */
    val clipDeviceY: Int
        get() = floor(clipTop + SNAP_EPSILON).toInt()

    /** 当前裁剪矩形宽度（设备像素整数）。右边界向上取整，交集为空时为 0。 */
    val clipDeviceWidth: Int
        get() = maxOf(0, ceil(clipRight - SNAP_EPSILON).toInt() - clipDeviceX)

    /** 当前裁剪矩形高度（设备像素整数）。下边界向上取整，交集为空时为 0。 */
    val clipDeviceHeight: Int
        get() = maxOf(0, ceil(clipBottom - SNAP_EPSILON).toInt() - clipDeviceY)

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 记录一个新矩阵，并刷新 [elements] 副本。**所有**改动都必须走这里，否则热路径会读到旧矩阵。
     *
     * @param m 新矩阵
     */
    private fun setMatrix(m: Mat3) {
        matrix = m
        val a = m.toArray()
        for (i in 0 until 9) {
            elements[i] = a[i]
        }
    }

    /**
     * 保证裁剪栈能容纳 `levels` 层。只在扩容时分配，稳态下 [save] 零分配。
     *
     * @param capacityLevels 需要的层数
     */
    private fun ensureClipCapacity(capacityLevels: Int) {
        if (capacityLevels * 4 <= clipStack.size) {
            return
        }
        var size = clipStack.size * 2
        while (size < capacityLevels * 4) {
            size *= 2
        }
        clipStack = clipStack.copyOf(size)
    }

    companion object {
        /**
         * 裁剪栈的初始层数。
         *
         * <p>取一个宽松的值，使常见的嵌套 `save()` 不至于触发扩容——
         * 但栈仍会按需翻倍，深层嵌套只是多一次扩容，不会失败。
         */
        private const val INITIAL_STACK_LEVELS = 8

        /**
         * 整像素取整前的吸附量（单位：设备像素）。
         *
         * <p>裁剪边界是浮点乘法与加法的结果，本该正好落在整数上的值常常差出 1e-5 上下，
         * 例如 `translate(100,50); clipRect(0,0,20,30)` 的右边界会算成 220.0000033。
         * 直接 `ceil` 会把它抬到 221，裁剪区莫名其妙宽出一个像素——这不是精度问题，
         * 而是一个肉眼可见的错误。取整前先减去（右/下）或加上（左/上）这个吸附量，
         * 把"离整数不到千分之一像素"的结果归位；代价是裁剪边界最多偏 0.001 像素，不可见。
         */
        private const val SNAP_EPSILON = 1e-3f

        /**
         * 构造像素→NDC 的基础矩阵。
         *
         * <p>推导：用户空间原点左上、y 向下，NDC 原点居中、y 向上。要求
         * `(0,0) → (-1,+1)`、`(w,h) → (+1,-1)`，于是
         * ```
         * ndcX = 2x/w - 1 = (2/w)·x + (-1)
         * ndcY = 1 - 2y/h = (-2/h)·y + (+1)
         * ```
         * 即"先缩放再平移"。而 [Mat3.multiply] 的语义是**先应用右操作数**，
         * 所以写作 `translation(-1, 1) * scale(2/w, -2/h)`。
         *
         * @param width  帧缓冲宽度
         * @param height 帧缓冲高度
         * @return 基础矩阵
         */
        private fun baseMatrix(width: Int, height: Int): Mat3 =
            Mat3.translation(-1f, 1f).multiply(Mat3.scale(2f / width, -2f / height))
    }
}
