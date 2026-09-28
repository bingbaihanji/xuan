package com.bingbaihanji.xuan.gl;

import com.bingbaihanji.xuan.util.Color;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用的 {@link GLAbstraction} 假实现。
 *
 * <p>方法分成三类：
 * <ul>
 *   <li><strong>拾取路径</strong>（FBO / 整数纹理 / 读回 / 裁剪）：真正实现，
 *       供 {@code PickBuffer}、{@code Framebuffer}、{@code GlyphAtlas} 单测。</li>
 *   <li><strong>顶点缓冲路径</strong>（图表后端用）：创建与子上传记入<em>公开</em>记录列表，
 *       绑定记入公开字段 {@code boundVbo}；定容只记入<strong>私有</strong>容量表（定容被误调
 *       无法从记录里看出，只能间接由越界断言观测）。这一组<strong>不抛
 *       {@link UnsupportedOperationException}</strong>，但登记不合法（未创建 / 已删除 /
 *       从未绑定）的 VBO 名与越界上传会抛 {@link AssertionError}——拾取路径若误调它们
 *       不会立刻报错。</li>
 *   <li><strong>其余</strong>：一律抛 {@link UnsupportedOperationException}
 *       （见文件末尾的清单）。唯一例外是 {@link #dispose()}：空实现，无资源可释放。</li>
 * </ul>
 *
 * <p>这三类清单由 {@code FakeGLAbstractionGuardTest} 钉住。
 *
 * <p>{@link #setUserPixel} 是给测试用的便捷入口：按<strong>用户坐标</strong>
 * （原点左上、y 向下）写入虚拟屏幕，内部换算成 GL 行序。这样测试用例可以用
 * 人思考场景的方式来布置数据，而 {@code PickBuffer} 的翻转一旦写错，
 * 读回来的坐标就对不上——两者是独立的代码路径，不会互相抵消。
 */
public final class FakeGLAbstraction implements GLAbstraction {

    /** 分配出的 ID 从这里递增，便于断言「确实拿去用了」而不是默认值 0。 */
    private int nextId = 100;

    /** 当前绑定的 FBO。初值刻意非 0，模拟 openglfx 渲染到自己的 FBO。 */
    public int boundFramebuffer = 7;

    /** 每次 bindFramebuffer 的入参，用来断言「恢复了原绑定」。 */
    public final List<Integer> bindCalls = new ArrayList<>();

    /** framebufferStatus() 的返回值，测试可改。 */
    public int statusToReturn = FRAMEBUFFER_COMPLETE;

    /** 删除调用的记录。 */
    public final List<Integer> deletedFramebuffers = new ArrayList<>();
    public final List<Integer> deletedTextures = new ArrayList<>();

    /**
     * 创建调用的记录。
     *
     * <p>删除记录只能证明「建完又删了」，证明不了「压根没建」。要断言参数校验
     * 发生在创建<strong>之前</strong>，必须观察创建本身——否则把校验挪到创建之后，
     * 删除记录依然是空的，断言恒真。
     */
    public final List<Integer> createdFramebuffers = new ArrayList<>();
    public final List<Integer> createdTextures = new ArrayList<>();

    /** 置为 true 后，所有整数读回与整数清空都抛异常，用于验证「抛错时仍恢复绑定」。 */
    public boolean throwOnIntegerCall = false;

    /** 裁剪测试是否启用。默认关。 */
    public boolean scissorEnabled = false;

    private void maybeThrow() {
        if (throwOnIntegerCall) {
            throw new IllegalStateException("注入的 GL 故障");
        }
    }

    /** 虚拟屏幕，<strong>GL 行序</strong>：下标 0 对应最下面一行。 */
    private int[] screen = new int[0];
    private int screenWidth;
    private int screenHeight;

    /** PBO 名 → 已提交的像素值；模拟 GPU 完成前数据只属于缓冲而不交给 CPU。 */
    private final Map<Integer, Integer> pixelPackValues = new HashMap<>();

    /** fence 名 → 是否已完成。测试可关闭自动完成来覆盖「不阻塞」分支。 */
    private final Map<Long, Boolean> syncStates = new HashMap<>();

    private long nextSync = 1L;

    public boolean autoSignalPixelPackFences = true;

    public final List<Integer> createdPixelPackBuffers = new ArrayList<>();
    public final List<Integer> deletedPixelPackBuffers = new ArrayList<>();

    /** 令所有已提交的异步读回完成。 */
    public void signalPixelPackFences() {
        syncStates.replaceAll((ignored, value) -> true);
    }

    /** 按 GL 行序（自下而上）设置整块虚拟屏幕。 */
    public void setScreenBottomUp(int width, int height, int[] rowsBottomUp) {
        this.screenWidth = width;
        this.screenHeight = height;
        this.screen = rowsBottomUp.clone();
    }

    /** 按<strong>用户坐标</strong>（原点左上、y 向下）写一个像素。 */
    public void setUserPixel(int x, int y, int value) {
        screen[(screenHeight - 1 - y) * screenWidth + x] = value;
    }

    @Override
    public int createFramebuffer() {
        int id = nextId++;
        createdFramebuffers.add(id);
        return id;
    }

    @Override
    public void bindFramebuffer(int framebuffer) {
        boundFramebuffer = framebuffer;
        bindCalls.add(framebuffer);
    }

    @Override
    public void deleteFramebuffer(int framebuffer) {
        deletedFramebuffers.add(framebuffer);
    }

    @Override
    public int currentFramebufferBinding() {
        return boundFramebuffer;
    }

    @Override
    public int createIntegerTexture(int width, int height) {
        int id = nextId++;
        createdTextures.add(id);
        return id;
    }

    @Override
    public void deleteTexture(int texture) {
        deletedTextures.add(texture);
    }

    /** 按纹理 ID 记录的虚拟图集内容，用于断言上传落在正确的区域。 */
    public final java.util.Map<Integer, byte[]> r8Textures = new java.util.HashMap<>();

    /** 每次 uploadR8SubImage 的调用记录。 */
    public final java.util.List<String> r8Uploads = new java.util.ArrayList<>();

    /**
     * 每张 R8 纹理的宽度，用作 {@code uploadR8SubImage} 的行跨距。
     *
     * <p><strong>必须按纹理 ID 记录，不能只留一个标量。</strong>标量记的是"最后一次
     * {@code createR8Texture} 的宽度"，一旦同时存在两张<em>宽度不同</em>的 R8 纹理，
     * 往先建那张上传时行偏移就会按错误的跨距算——假实现会悄悄污染自己的虚拟纹理，
     * 于是所有建立在其上的断言都失去意义（本项目最警惕的「静默错误输出」形状）。
     */
    private final java.util.Map<Integer, Integer> r8Widths = new java.util.HashMap<>();

    @Override
    public int createR8Texture(int width, int height) {
        int id = nextId++;
        r8Textures.put(id, new byte[width * height]);
        r8Widths.put(id, width);
        return id;
    }

    @Override
    public void uploadR8SubImage(int texture, int x, int y, int width, int height, byte[] pixels) {
        maybeThrow();
        r8Uploads.add(x + "," + y + "," + width + "," + height);
        byte[] target = r8Textures.get(texture);
        if (target == null) {
            throw new AssertionError("上传到了未创建的 R8 纹理：" + texture);
        }
        int stride = r8Widths.get(texture);
        for (int row = 0; row < height; row++) {
            System.arraycopy(pixels, row * width,
                    target, (y + row) * stride + x, width);
        }
    }

    @Override
    public void attachTextureToColor0(int texture) {
        // 假实现不做附件检查，完整性由 statusToReturn 单独控制
    }

    @Override
    public int framebufferStatus() {
        return statusToReturn;
    }

    @Override
    public void clearIntegerColor(int value) {
        maybeThrow();
        // glClearBufferuiv 受 scissor 影响：开着裁剪清空只会清掉盒内那一块，
        // 盒外的像素保留上一帧的 ID——拾取到已经消失的对象，且不报错。
        // 真 GL 上这个错误是静默的，这里把它变成显式的失败。
        if (scissorEnabled) {
            throw new AssertionError(
                    "clearIntegerColor 在裁剪测试开启时被调用：只会清掉裁剪盒内的部分，"
                            + "盒外保留上一帧的 ID");
        }
        java.util.Arrays.fill(screen, value);
    }

    @Override
    public boolean isScissorEnabled() {
        return scissorEnabled;
    }

    @Override
    public void setScissorEnabled(boolean enabled) {
        scissorEnabled = enabled;
    }

    @Override
    public int readUnsignedIntPixel(int x, int y) {
        maybeThrow();
        return screen[y * screenWidth + x];
    }

    @Override
    public void readUnsignedIntPixels(int x, int y, int width, int height, int[] out) {
        maybeThrow();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                out[row * width + col] = screen[(y + row) * screenWidth + (x + col)];
            }
        }
    }

    @Override
    public int createPixelPackBuffer() {
        int id = nextId++;
        createdPixelPackBuffers.add(id);
        return id;
    }

    @Override
    public void deletePixelPackBuffer(int buffer) {
        deletedPixelPackBuffers.add(buffer);
        pixelPackValues.remove(buffer);
    }

    @Override
    public void enqueueUnsignedIntPixelRead(int x, int y, int buffer) {
        maybeThrow();
        if (!createdPixelPackBuffers.contains(buffer) || deletedPixelPackBuffers.contains(buffer)) {
            throw new AssertionError("读回写入了无效 PBO：" + buffer);
        }
        pixelPackValues.put(buffer, screen[y * screenWidth + x]);
    }

    @Override
    public long fenceSync() {
        long sync = nextSync++;
        syncStates.put(sync, autoSignalPixelPackFences);
        return sync;
    }

    @Override
    public boolean isSyncSignaled(long sync) {
        Boolean signaled = syncStates.get(sync);
        if (signaled == null) {
            throw new AssertionError("查询了无效 fence：" + sync);
        }
        return signaled;
    }

    @Override
    public void deleteSync(long sync) {
        syncStates.remove(sync);
    }

    @Override
    public int readUnsignedIntPixelFromPixelPackBuffer(int buffer) {
        Integer value = pixelPackValues.get(buffer);
        if (value == null) {
            throw new AssertionError("读取了没有结果的 PBO：" + buffer);
        }
        return value;
    }

    // —— 顶点缓冲路径（图表后端用）——
    //
    // 这一组原本是抛 UnsupportedOperationException 的（"真调到了说明走偏了"）。
    // 图表后端确实要建自己的 VBO，所以其中几个改成记录。
    //
    // **只放开 Task 8 真正会用到的那些。** 计划对"放开抛异常的方法"要求按需放开，
    // 同一条纪律对"新增字段"一样适用——预先加字段，就是预先卸掉护栏。

    /** 创建的 VBO 名字。 */
    public final List<Integer> createdVbos = new ArrayList<>();

    /** 删除的 VBO 名字。 */
    public final List<Integer> deletedVbos = new ArrayList<>();

    /** 当前绑定的 VBO。越界检查要靠它判断这次上传落在哪个缓冲上。 */
    public int boundVbo = 0;

    /**
     * 每个 VBO 的已分配字节数，由定容调用（{@code uploadVboData(float[])}）记录。
     *
     * <p><strong>必须按 VBO 名字记，不能只留一个标量</strong>——理由与
     * {@code r8Widths} 完全相同：标量记的是"最后一次调用"，一旦同时存在多个 VBO，
     * 检查就会按错误的容量算，于是假实现悄悄放过真正的越界。
     */
    private final Map<Integer, Integer> vboCapacityBytes = new HashMap<>();

    /**
     * 每次 uploadVboSubData 的记录："VBO名@偏移:字节数"。
     *
     * <p>第一个字段是<strong>目标 VBO</strong>，不是多余的：Task 11 之后会同时存在多个
     * VBO（一个系列一个），而"上传落到了别的系列的缓冲上"是画面正常、只是数据错的一类
     * 缺陷——记录里必须能问出这件事。
     */
    public final List<String> vboSubDataCalls = new ArrayList<>();

    @Override
    public int createVbo() {
        int id = nextId++;
        createdVbos.add(id);
        return id;
    }

    @Override
    public void bindVbo(int vbo) {
        boundVbo = vbo;
    }

    @Override
    public void deleteVbo(int vbo) {
        deletedVbos.add(vbo);
        vboCapacityBytes.remove(vbo);
    }

    /**
     * 断言当前绑定的确实是一个<b>活着</b>的 VBO。
     *
     * <p>不加这道检查的话，"漏了 bindVbo"（{@code boundVbo} 保持 0）会把容量记在 0 名下，
     * 而之后 {@code uploadVboSubData} 的两道断言都会放行——真 GL 上那是
     * {@code GL_INVALID_OPERATION}。堵越界却漏了这条，等于没堵。
     *
     * <p>{@code createVbo} 从 {@code nextId}（初值 100）开始发号，因此 0 永远不是合法名字。
     */
    private void requireLiveVbo(String operation) {
        if (boundVbo == 0 || !createdVbos.contains(boundVbo) || deletedVbos.contains(boundVbo)) {
            throw new AssertionError(operation + "：当前没有绑定有效的 VBO（boundVbo="
                    + boundVbo + "）。真 GL 上这是 GL_INVALID_OPERATION");
        }
    }

    @Override
    public void uploadVboData(float[] data) {
        requireLiveVbo("uploadVboData");
        vboCapacityBytes.put(boundVbo, data.length * Float.BYTES);
    }

    @Override
    public void uploadVboSubData(int offsetBytes, ByteBuffer data) {
        requireLiveVbo("uploadVboSubData");
        int bytes = data.remaining();
        // 真 GL 上，越界要么报 GL_INVALID_OPERATION（而本仓库没有任何地方读错误码——
        // glGetError 全库只在 PipelineVerifier 的一处诊断打印里出现过），要么静默损坏
        // 别的数据。假实现把它变成显式失败，与本文件 uploadR8SubImage 的做法一致。
        //
        // 这一条不是吹毛求疵：Task 8 的整个测试策略就是靠这个假实现验证 SeriesBuffer，
        // 而 SeriesBuffer 是"GPU 常驻 + 增量上传"这条主张的承载者。不校验前置条件的话，
        // 它上面的断言会在"参数其实非法"时通过——本项目最警惕的形状。
        Integer capacity = vboCapacityBytes.get(boundVbo);
        if (capacity == null) {
            throw new AssertionError(
                    "向从未定容的 VBO " + boundVbo + " 子上传：真 GL 上这是 GL_INVALID_OPERATION");
        }
        if (offsetBytes < 0 || (long) offsetBytes + bytes > capacity) {
            throw new AssertionError(
                    "子上传越界：offset=" + offsetBytes + " bytes=" + bytes
                            + " 容量=" + capacity + "（真 GL 上是静默损坏）");
        }
        vboSubDataCalls.add(boundVbo + "@" + offsetBytes + ":" + bytes);
    }

    // —— 以下与拾取路径无关，真调到了说明走偏了 ——

    @Override public void initialize() { throw new UnsupportedOperationException(); }
    @Override public void clear(Color color) { throw new UnsupportedOperationException(); }
    @Override public void setViewport(int x, int y, int w, int h) { throw new UnsupportedOperationException(); }
    @Override public int createVao() { throw new UnsupportedOperationException(); }
    @Override public void bindVao(int vao) { throw new UnsupportedOperationException(); }
    @Override public void uploadVboData(int[] data) { throw new UnsupportedOperationException(); }
    @Override public void uploadVboBytes(ByteBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void deleteVao(int vao) { throw new UnsupportedOperationException(); }
    @Override public void drawArrays(int mode, int offset, int count) { throw new UnsupportedOperationException(); }
    @Override public void drawElements(int mode, int count) { throw new UnsupportedOperationException(); }
    @Override public void enableBlend() { throw new UnsupportedOperationException(); }
    @Override public void disableBlend() { throw new UnsupportedOperationException(); }
    @Override public void setBlendFunc(int s, int d) { throw new UnsupportedOperationException(); }
    @Override public ShaderProgram createShader(String v, String f) { throw new UnsupportedOperationException(); }
    @Override public int createTexture(int w, int h, int[] p) { throw new UnsupportedOperationException(); }
    @Override public void setVertexAttribDivisor(int index, int divisor) { throw new UnsupportedOperationException(); }
    @Override public void drawArraysInstancedBaseInstance(int mode, int first, int count,
                                                          int instanceCount, int baseInstance) { throw new UnsupportedOperationException(); }
    // SSBO 一组（compute 用）。**一个都不放开**：当前没有任何单测会碰它们
    // （D-③-1 的下一个任务是纯算术，再往后的都要真 GL 上下文）。每多放开一个方法，
    // 就少一处"走偏了就报错"的护栏，而收益是零——后面哪个任务真的需要，那时再放开那一个。
    @Override public int createBuffer() { throw new UnsupportedOperationException(); }
    @Override public void bindShaderStorageBuffer(int buffer) { throw new UnsupportedOperationException(); }
    @Override public void allocateBufferStorage(long sizeBytes) { throw new UnsupportedOperationException(); }
    @Override public void uploadBufferSubData(long offsetBytes, ByteBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void bindBufferBase(int bindingIndex, int buffer) { throw new UnsupportedOperationException(); }
    @Override public void deleteBuffer(int buffer) { throw new UnsupportedOperationException(); }
    @Override public void dispose() { /* 无资源可释放 */ }
}
