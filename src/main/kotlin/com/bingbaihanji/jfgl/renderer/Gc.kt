package com.bingbaihanji.jfgl.renderer

import com.bingbaihanji.jfgl.math.Mat3

/**
 * 立即模式的 2D 绘制上下文，对外提供类似 Canvas 的 API。
 *
 * <p>所有绘制调用只往顶点缓冲追加数据，**不做任何 GL 调用**；真正提交发生在 [endFrame]。
 * 因此整个 `onRender { }` 回调期间没有任何一次 GL 状态切换，
 * 整帧的 draw call 数量取决于状态切换次数（见 [VertexWriter] 的合批规则）。
 *
 * <p>坐标系统为**像素、原点左上、y 向下**，与 HTML Canvas 一致：
 * `translate`/`scale`/`rotate` 的角度以度为单位，正值在屏幕上是顺时针。
 *
 * ## 一帧的生命周期
 * ```
 * gc.beginFrame(w, h)   // 重置变换/裁剪/样式栈与顶点写入器，设置视口高度
 * gc.fillRect(...)      // 尽可能多地记录图元
 * gc.endFrame()         // 一次性提交；缓冲区中途写满时会自动提交一次再继续
 * ```
 * [beginFrame] 与 [endFrame] 必须严格配对：重复 [beginFrame]、或未经 [beginFrame] 就 [endFrame]
 * 都会抛出消息说明问题的 [IllegalStateException]，而不是安静地画出错误结果。
 *
 * ## 线程
 * 本类**不是线程安全的**，且必须在 GL 线程上使用（[RenderBatch] 只允许在 GL 线程调用）。
 *
 * ## 坐标数学在哪里
 * 像素→NDC 的基础矩阵、变换栈、裁剪栈及全部单位换算都在 [ViewTransform] 里，
 * 那是一个不依赖 GL 的纯数学类，可以脱离窗口单测；本类只负责状态管理与委托，
 * 以及后续任务加入的逐图元发射。
 */
class Gc internal constructor(private val batch: RenderBatch) {

    /** 顶点收集器。整帧的绘制结果都在这里，[endFrame] 时才交给 [RenderBatch]。 */
    private val writer = VertexWriter(INITIAL_VERTEX_CAPACITY)

    /** 纯坐标状态：基础矩阵、变换栈、裁剪栈。 */
    private val state = ViewTransform()

    /** 当前填充样式（ARGB 打包）。 */
    var fill: Int = 0xFFFFFFFF.toInt()

    /** 当前描边样式（ARGB 打包）。 */
    var stroke: Int = 0xFF000000.toInt()

    /** 当前线宽（用户坐标单位）。 */
    var lineWidth: Float = 1f

    /** 全局不透明度（0-1），与样式颜色相乘。 */
    var globalAlpha: Float = 1f

    /** 样式栈的整数部分：每层 2 个值（fill、stroke）。 */
    private var styleInts = IntArray(INITIAL_STACK_LEVELS * 2)

    /** 样式栈的浮点部分：每层 2 个值（lineWidth、globalAlpha）。 */
    private var styleFloats = FloatArray(INITIAL_STACK_LEVELS * 2)

    /**
     * 样式栈层数。
     *
     * <p>必须与 [ViewTransform.stackDepth] 始终相等——[save] 与 [restore] 是唯一改动它们的地方，
     * 每次都是成对压入/弹出。之所以不像变换与裁剪那样塞进 [ViewTransform]：
     * 那些是坐标数学，样式不是；而拆成两个类以后，保持同步的成本仅仅是在同一对方法里各动一次。
     */
    private var styleDepth = 0

    /** 本帧是否处于 [beginFrame] 与 [endFrame] 之间。 */
    private var frameActive = false

    // ------------------------------------------------------------------
    // 帧
    // ------------------------------------------------------------------

    /**
     * 开始一帧：设定视口尺寸、把变换重置为像素→NDC 的基础矩阵、清空全部状态栈与顶点缓冲。
     *
     * @param width  帧缓冲宽度（像素），必须为正
     * @param height 帧缓冲高度（像素），必须为正
     * @throws IllegalStateException    上一帧还没有 [endFrame] 时
     * @throws IllegalArgumentException 宽或高不为正时
     */
    fun beginFrame(width: Int, height: Int) {
        check(!frameActive) { "beginFrame 已调用过：beginFrame 与 endFrame 必须配对，不能嵌套" }
        state.beginFrame(width, height)
        styleDepth = 0
        writer.reset()
        batch.setViewportHeight(height)
        frameActive = true
    }

    /**
     * 结束一帧并把本帧收集到的全部顶点一次性提交。
     *
     * <p>提交后 GL 状态回到中性（见 [RenderBatch.submit]），因此可以在下一帧直接继续画。
     *
     * <p>若本帧有 [save] 没有配对的 [restore]，本方法仍然会先提交这一帧（用户能看到画面），
     * 随后清空状态栈并抛出 [IllegalStateException]。选择抛而不是安静地兜底，
     * 是因为漏掉 [restore] 会让栈无限增长并污染后续每一帧，属于必须暴露的缺陷。
     *
     * @throws IllegalStateException 未经 [beginFrame] 就调用，或本帧 [save]/[restore] 不配对
     */
    fun endFrame() {
        check(frameActive) { "endFrame 在 beginFrame 之前调用：beginFrame 与 endFrame 必须配对" }
        batch.submit(writer)
        frameActive = false
        val unbalanced = state.clearStack()
        styleDepth = 0
        check(unbalanced == 0) { "save() 与 restore() 不配对：本帧结束时仍残留 $unbalanced 层 save()" }
    }

    // ------------------------------------------------------------------
    // 状态栈
    // ------------------------------------------------------------------

    /**
     * 压入当前的**全部绘制状态**：变换、裁剪、填充色、描边色、线宽、全局不透明度。
     *
     * <p>与 HTML Canvas / JavaFX 的 `save()` 语义一致——用户改完样式再 [restore] 就能回到原样，
     * 不必手工记下每一个字段。每次 [save] 在稳态下不产生任何分配：
     * 变换压入的是引用，裁剪与样式压入的是预先分配的数组，裁剪的"无裁剪"用整个帧缓冲表示，
     * 不需要哨兵对象。
     */
    fun save() {
        state.save()
        ensureStyleCapacity(styleDepth + 1)
        val base = styleDepth * 2
        styleInts[base] = fill
        styleInts[base + 1] = stroke
        styleFloats[base] = lineWidth
        styleFloats[base + 1] = globalAlpha
        styleDepth++
    }

    /**
     * 弹出并恢复最近一次 [save] 的全部绘制状态。
     *
     * @throws IllegalStateException 没有配对的 [save] 时（此时状态不变）
     */
    fun restore() {
        check(styleDepth > 0) { "restore() 与 save() 不配对：当前栈为空，没有可恢复的状态" }
        state.restore()
        styleDepth--
        val base = styleDepth * 2
        fill = styleInts[base]
        stroke = styleInts[base + 1]
        lineWidth = styleFloats[base]
        globalAlpha = styleFloats[base + 1]
    }

    // ------------------------------------------------------------------
    // 变换
    // ------------------------------------------------------------------

    /**
     * 在当前变换之后追加平移（用户坐标单位）。
     *
     * @param tx 沿 x 轴的平移量
     * @param ty 沿 y 轴的平移量（y 向下）
     */
    fun translate(tx: Float, ty: Float) {
        state.translate(tx, ty)
    }

    /**
     * 在当前变换之后追加缩放。
     *
     * @param sx 沿 x 轴的缩放因子
     * @param sy 沿 y 轴的缩放因子
     */
    fun scale(sx: Float, sy: Float) {
        state.scale(sx, sy)
    }

    /**
     * 在当前变换之后追加旋转。**正值为屏幕上的顺时针**（与 HTML Canvas、JavaFX 一致）。
     *
     * @param degrees 旋转角度（度），正值为屏幕上的顺时针
     */
    fun rotate(degrees: Float) {
        state.rotate(degrees)
    }

    // ------------------------------------------------------------------
    // 裁剪
    // ------------------------------------------------------------------

    /**
     * 用矩形裁剪后续绘制，矩形按**用户坐标**给出，与已有裁剪**求交**。
     *
     * <p>注意：底层使用 `glScissor`，实际生效的是**变换后包围盒**。
     * 若当前变换包含旋转，裁剪区域会是旋转后矩形的轴对齐包围盒，而非旋转矩形本身。
     *
     * @param x 矩形左上角 x（用户坐标）
     * @param y 矩形左上角 y（用户坐标，y 向下）
     * @param w 矩形宽度
     * @param h 矩形高度
     */
    fun clipRect(x: Float, y: Float, w: Float, h: Float) {
        state.clipRect(x, y, w, h)
    }

    // ------------------------------------------------------------------
    // 供后续任务使用
    // ------------------------------------------------------------------

    /** 返回内部顶点写入器，供绘制方法追加顶点。 */
    internal fun writer(): VertexWriter = writer

    /** 当前变换（含基础矩阵）。逐顶点发射时用它把用户坐标烘焙到 NDC。 */
    internal val currentMatrix: Mat3
        get() = state.matrix

    /**
     * 把当前变换与裁剪状态同步给顶点写入器。
     *
     * <p>**裁剪 y 传的是顶边**：[ViewTransform.clipDeviceY] 已是 y 向下的设备像素顶边，
     * 与 [DrawCommand] 的 `scissorY` 约定一致；翻成 `glScissor` 的 y 向上坐标是
     * [RenderBatch.applyScissor] 的职责，这里<strong>不能再翻一次</strong>。
     *
     * <p>[VertexWriter.setState] 在状态未变时是空操作，所以每个图元调用一次没有额外开销；
     * 而帧中途 flush 之后 [VertexWriter.reset] 会把写入器带回"未设置状态"，
     * 下一次调用本方法正好重新设上——见 [VertexWriter.isFlushRequested] 的消费方契约。
     *
     * @param textureId 要绑定的纹理 ID
     */
    internal fun syncState(textureId: Int) {
        writer.setState(
            textureId,
            state.clipDeviceX,
            state.clipDeviceY,
            state.clipDeviceWidth,
            state.clipDeviceHeight
        )
    }

    /** 返回当前变换把用户单位换算成设备像素的平均缩放因子，供平坦化容差与分段数使用。 */
    internal fun matrixScale(): Float = state.matrixScale()

    /**
     * 应用全局不透明度后把 ARGB 颜色打包成顶点格式要求的预乘整数。
     *
     * @param argb 原始 ARGB 颜色
     * @return 预乘 alpha 后的 RGBA 整数（内存字节序 R,G,B,A）
     */
    internal fun packColor(argb: Int): Int {
        val a = ((argb ushr 24) and 0xFF) / 255f * globalAlpha
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return VertexFormat.packPremultiplied(r, g, b, a)
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 保证样式栈能容纳 `capacityLevels` 层。只在扩容时分配。
     *
     * @param capacityLevels 需要的层数
     */
    private fun ensureStyleCapacity(capacityLevels: Int) {
        val needed = capacityLevels * 2
        if (needed <= styleInts.size) {
            return
        }
        var size = styleInts.size * 2
        while (size < needed) {
            size *= 2
        }
        styleInts = styleInts.copyOf(size)
        styleFloats = styleFloats.copyOf(size)
    }

    companion object {
        /** 初始顶点容量，约 1.5 MB（65536 个顶点 * 24 字节）。 */
        private const val INITIAL_VERTEX_CAPACITY = 65536

        /**
         * 样式栈的初始层数。
         *
         * <p>与 [ViewTransform] 的裁剪栈互相独立，各自按需翻倍扩容，取值相同只是巧合而非约束。
         */
        private const val INITIAL_STACK_LEVELS = 8
    }
}
