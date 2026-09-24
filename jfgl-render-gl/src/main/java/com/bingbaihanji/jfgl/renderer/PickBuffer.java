package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.Framebuffer;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.util.Disposable;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.TreeMap;

/**
 * 拾取缓冲：一块与主帧缓冲同尺寸的 {@code R32UI} 离屏缓冲，存每个像素命中的拾取 ID。
 *
 * <h2>坐标约定</h2>
 * <p>本类对外的坐标一律是<strong>用户坐标</strong>：像素、原点左上、y 向下。
 * {@code glReadPixels} 的原点在左下、y 向上，<strong>翻转集中在本类内部完成</strong>——
 * 漏掉翻转会得到一个上下镜像的、部分正确的拾取结果，是本项目最擅长产生的那类缺陷。
 *
 * <h2>尺寸不变式</h2>
 * <p>必须与主帧缓冲<strong>同尺寸</strong>。裁剪靠 {@code glScissor}，而它的 y 是按
 * {@code viewportHeight} 换算的；两者尺寸不同会让裁剪错位——被裁掉的部分仍可拾取，
 * 用户点看不见的地方却命中。{@link #ensureSize} 负责在尺寸变化时重建。
 *
 * <p>所有方法必须在 GL 线程上调用。
 */
public final class PickBuffer implements Disposable {

    /** 双 PBO 让当前帧提交 GPU 读回时，CPU 消费上一帧已完成的结果。 */
    private static final int PIXEL_PACK_BUFFER_COUNT = 2;

    /** GL 抽象层。 */
    private final GLAbstraction gl;

    /** 底层帧缓冲，尺寸变化时会被替换。 */
    private Framebuffer framebuffer;

    /** 异步读回槽位按提交顺序排队，不能按 GPU 完成顺序乱序返回。 */
    private final Deque<PixelPackSlot> pendingPixelReads = new ArrayDeque<>();

    private final PixelPackSlot[] pixelPackSlots = new PixelPackSlot[PIXEL_PACK_BUFFER_COUNT];

    /** 是否已释放。 */
    private boolean disposed = false;

    /** 异步点读的提交状态。 */
    public enum AsyncReadStatus {
        QUEUED,
        OUT_OF_BOUNDS,
        NO_PICK_CONTENT,
        NO_FREE_SLOT
    }

    /** 已完成读回的令牌与拾取 ID。 */
    public record AsyncReadResult(long token, int id) {
    }

    private static final class PixelPackSlot {
        final int buffer;
        long token;
        long sync;

        PixelPackSlot(int buffer) {
            this.buffer = buffer;
        }

        boolean isBusy() {
            return sync != 0L;
        }
    }

    /**
     * 创建拾取缓冲。
     *
     * @param gl     GL 抽象层
     * @param width  宽度（像素），必须为正
     * @param height 高度（像素），必须为正
     */
    public PickBuffer(GLAbstraction gl, int width, int height) {
        this.gl = gl;
        this.framebuffer = new Framebuffer(gl, width, height);
        for (int i = 0; i < pixelPackSlots.length; i++) {
            pixelPackSlots[i] = new PixelPackSlot(gl.createPixelPackBuffer());
        }
        // 新建的纹理内容是规范定义的未定义值（多数驱动恰好给 0）。不在这里清一次，
        // 「从未渲染过拾取 pass」读回的就是驱动的恩赐，而不是代码保证的「什么都没命中」。
        clear();
    }

    /**
     * 确保缓冲尺寸与给定值一致，不一致则重建。
     *
     * <p>尺寸没变时是空操作，因此每帧调用没有开销。
     *
     * @param width  期望宽度
     * @param height 期望高度
     */
    public void ensureSize(int width, int height) {
        checkNotDisposed();
        if (framebuffer.width() == width && framebuffer.height() == height) {
            return;
        }
        // 先建后弃：构造函数抛错时旧缓冲必须原封不动。反过来写（先 dispose 再 new）
        // 一旦 new 抛错，字段就停在已释放对象上，而它的 id() 是 0——在 OpenGL 里
        // 0 是默认帧缓冲，此后读的是窗口自己的像素当成 ID，clear 则去擦窗口的颜色。
        Framebuffer next = new Framebuffer(gl, width, height);
        framebuffer.dispose();
        framebuffer = next;
        clear();
    }

    /**
     * 返回底层帧缓冲的 FBO ID，供调用方自行绑定。
     *
     * @return FBO 的 ID
     */
    public int framebufferId() {
        return framebuffer.id();
    }

    /**
     * 把整个缓冲清成 0（即「什么都没命中」）。
     *
     * <p>走 {@code glClearBufferuiv} 而不是 {@code glClearColor} + {@code glClear}：
     * 后者对整数附件是未定义行为。帧缓冲绑定在使用前后被恢复。
     *
     * <p><strong>裁剪测试在清空期间会被临时关闭</strong>：{@code glClearBufferuiv}
     * 受 scissor 影响，而本方法的调用点（{@code RenderBatch.drawPickPass}）恰好发生在
     * 颜色 pass 之后、{@code glDisable(GL_SCISSOR_TEST)} 之前，此时裁剪盒还是
     * 最后一条颜色命令留下的那个。不关的话清到的只是那个盒子，
     * <strong>盒外的像素保留上一帧的 ID——拾取到已经消失的对象，且不报错</strong>。
     *
     * <p>关掉再恢复、而不是要求调用方先关：本方法承诺的是「整个缓冲」，
     * 那就不该依赖调用点的 GL 状态。实测确认过（见计划 Task 10.5）。
     */
    public void clear() {
        checkNotDisposed();
        int previous = gl.currentFramebufferBinding();
        boolean scissorWasEnabled = gl.isScissorEnabled();
        gl.setScissorEnabled(false);
        gl.bindFramebuffer(framebuffer.id());
        try {
            gl.clearIntegerColor(0);
        } finally {
            // 必须用 finally：任何 GL 调用都可能抛错，一旦抛出去而绑定停在拾取 FBO，
            // 后续所有绘制都会画进这里——画面全黑或停在上一帧，且不报错。
            gl.bindFramebuffer(previous);
            gl.setScissorEnabled(scissorWasEnabled);
        }
    }

    /**
     * 读回一个像素的拾取 ID。
     *
     * @param x 用户坐标 x（y 向下）
     * @param y 用户坐标 y（y 向下）
     * @return 该像素的 ID；坐标越界时返回 0
     */
    public int readPixel(int x, int y) {
        checkNotDisposed();
        if (x < 0 || y < 0 || x >= framebuffer.width() || y >= framebuffer.height()) {
            // 越界读 glReadPixels 是未定义行为，必须在这里挡掉。
            return 0;
        }
        int glY = framebuffer.height() - 1 - y;
        int previous = gl.currentFramebufferBinding();
        gl.bindFramebuffer(framebuffer.id());
        try {
            return gl.readUnsignedIntPixel(x, glY);
        } finally {
            gl.bindFramebuffer(previous);
        }
    }

    /**
     * 将一个像素的 ID 读回提交给 PBO，并在命令流末尾插入 fence。
     *
     * <p>本方法不会映射 PBO 或等待 GPU，因此可以在每帧 hover 查询时调用。稍后由
     * {@link #pollAsyncPixel()} 在 fence 已完成时取回结果。两个 PBO 都忙时返回
     * {@link AsyncReadStatus#NO_FREE_SLOT}，调用方应保留最新请求到下一帧而不能同步兜底，
     * 否则高频输入仍会把 CPU 卡在 {@code glReadPixels} 上。
     *
     * @param x 用户坐标 x
     * @param y 用户坐标 y
     * @param token 调用方关联请求与回调的令牌
     * @return 提交状态
     */
    public AsyncReadStatus enqueueAsyncPixel(int x, int y, long token) {
        checkNotDisposed();
        if (x < 0 || y < 0 || x >= framebuffer.width() || y >= framebuffer.height()) {
            return AsyncReadStatus.OUT_OF_BOUNDS;
        }
        PixelPackSlot slot = findFreePixelPackSlot();
        if (slot == null) {
            return AsyncReadStatus.NO_FREE_SLOT;
        }

        int previous = gl.currentFramebufferBinding();
        gl.bindFramebuffer(framebuffer.id());
        try {
            gl.enqueueUnsignedIntPixelRead(x, framebuffer.height() - 1 - y, slot.buffer);
            slot.token = token;
            slot.sync = gl.fenceSync();
            pendingPixelReads.addLast(slot);
            return AsyncReadStatus.QUEUED;
        } finally {
            gl.bindFramebuffer(previous);
        }
    }

    /**
     * 非阻塞地消费最早一条已经完成的 PBO 读回。
     *
     * @return 已完成结果；最早一条尚未完成或队列为空时为 {@code null}
     */
    public AsyncReadResult pollAsyncPixel() {
        checkNotDisposed();
        PixelPackSlot slot = pendingPixelReads.peekFirst();
        if (slot == null || !gl.isSyncSignaled(slot.sync)) {
            return null;
        }
        try {
            return new AsyncReadResult(slot.token,
                    gl.readUnsignedIntPixelFromPixelPackBuffer(slot.buffer));
        } finally {
            gl.deleteSync(slot.sync);
            slot.sync = 0L;
            pendingPixelReads.removeFirst();
        }
    }

    private PixelPackSlot findFreePixelPackSlot() {
        for (PixelPackSlot slot : pixelPackSlots) {
            if (!slot.isBusy()) {
                return slot;
            }
        }
        return null;
    }

    /**
     * 读回一块区域内出现过的拾取 ID，每个 ID 附带它在区域内**首次出现**的像素坐标。
     *
     * <p>区域会先与缓冲求交；交集为空返回空列表（<strong>不抛异常</strong>——
     * 刷选拖到窗口外是正常操作）。
     *
     * <p><strong>扫描顺序</strong>：从区域左上角起、逐行向右，先遇到的那个像素即为
     * 「首次出现」。结果按 ID 升序排列（{@link TreeMap} 的键序），
     * 因此同一个场景两次查询的返回顺序稳定，便于断言与调试。
     *
     * <p><strong>行序要反过来遍历</strong>：{@code glReadPixels} 的回读结果自下而上，
     * {@code raw} 的第 0 行对应 GL 坐标里最下面那一行，也就是用户坐标里 y 最大的那一行。
     * 所以「从区域顶部开始」对应 {@code raw} 的行下标<strong>递减</strong>。
     * 顺着遍历会得到一个上下颠倒的「首次出现」，答案看着合理但位置是错的。
     *
     * @param x 用户坐标左边缘
     * @param y 用户坐标上边缘（y 向下）
     * @param w 宽度
     * @param h 高度
     * @return 区域内出现过的非零 ID 及其首次出现坐标，按 ID 升序
     */
    public List<PickPixel> readRect(int x, int y, int w, int h) {
        // 显式写出来，而不是靠内部调 framebuffer.width() 间接生效：后者漏掉
        // w <= 0 || h <= 0 的提前返回分支，缺了守卫会在已释放的缓冲上静默返回空列表。
        checkNotDisposed();
        if (w <= 0 || h <= 0) {
            return List.of();
        }
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(framebuffer.width(), x + w);
        int y1 = Math.min(framebuffer.height(), y + h);
        if (x1 <= x0 || y1 <= y0) {
            return List.of();
        }
        int readWidth = x1 - x0;
        int readHeight = y1 - y0;

        // 用户坐标的 y0 是上边缘，换算到 GL：该区域占据 GL 行 [height - y1, height - y0)。
        int glY = framebuffer.height() - y1;

        int[] raw = new int[readWidth * readHeight];
        int previous = gl.currentFramebufferBinding();
        gl.bindFramebuffer(framebuffer.id());
        try {
            gl.readUnsignedIntPixels(x0, glY, readWidth, readHeight, raw);
        } finally {
            // 必须用 finally：读回抛错（区域过大时 F1 那条路径必然可达）而绑定停在
            // 拾取 FBO 上，下一帧的颜色会全画进拾取缓冲——画面全黑或停在上一帧，不报错。
            gl.bindFramebuffer(previous);
        }

        // 这是一次按需查询（刷选），不是每帧路径，为了清晰起见接受这点装箱开销。
        TreeMap<Integer, PickPixel> firstSeen = new TreeMap<>();
        for (int row = readHeight - 1; row >= 0; row--) {
            // raw 的第 row 行对应 GL 行 glY + row，即用户坐标 y = height - 1 - (glY + row)。
            int userY = framebuffer.height() - 1 - (glY + row);
            int rowBase = row * readWidth;
            for (int col = 0; col < readWidth; col++) {
                int value = raw[rowBase + col];
                if (value != 0 && !firstSeen.containsKey(value)) {
                    firstSeen.put(value, new PickPixel(value, x0 + col, userY));
                }
            }
        }
        return new ArrayList<>(firstSeen.values());
    }

    /**
     * 返回当前宽度（像素）。
     *
     * @return 宽度
     */
    public int width() {
        checkNotDisposed();
        return framebuffer.width();
    }

    /**
     * 返回当前高度（像素）。
     *
     * @return 高度
     */
    public int height() {
        checkNotDisposed();
        return framebuffer.height();
    }

    /**
     * 断言尚未被释放。
     *
     * <p>释放之后底层 FBO 的 id 变成 0，而 0 在 OpenGL 里是<strong>默认帧缓冲</strong>。
     * 不挡的话，读回拿的是窗口自己的像素（当成拾取 ID），清空擦的是窗口画面——
     * 两个都是不崩溃、只是结果悄悄错了的失败。宁可在这里炸。
     *
     * @throws IllegalStateException 已释放时
     */
    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException(
                    "PickBuffer 已释放：底层 FBO id 已是 0，而 0 在 OpenGL 里是默认帧缓冲；"
                            + "继续读会拿到窗口像素当成拾取 ID，继续清会擦掉窗口画面");
        }
    }

    /** 释放底层帧缓冲。重复调用无副作用。 */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        for (PixelPackSlot slot : pixelPackSlots) {
            if (slot.sync != 0L) {
                gl.deleteSync(slot.sync);
                slot.sync = 0L;
            }
            gl.deletePixelPackBuffer(slot.buffer);
        }
        pendingPixelReads.clear();
        framebuffer.dispose();
        disposed = true;
    }
}
