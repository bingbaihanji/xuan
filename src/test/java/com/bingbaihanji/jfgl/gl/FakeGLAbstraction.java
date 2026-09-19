package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Color;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的 {@link GLAbstraction} 假实现。
 *
 * <p>只实现拾取路径真正用到的那几个方法，其余一律抛
 * {@link UnsupportedOperationException}——测试里真调到了就说明走偏了，
 * 静默返回 0 反而会让断言看起来通过。
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

    // —— 以下与拾取路径无关，真调到了说明走偏了 ——

    @Override public void initialize() { throw new UnsupportedOperationException(); }
    @Override public void clear(Color color) { throw new UnsupportedOperationException(); }
    @Override public void setViewport(int x, int y, int w, int h) { throw new UnsupportedOperationException(); }
    @Override public int createVao() { throw new UnsupportedOperationException(); }
    @Override public int createVbo() { throw new UnsupportedOperationException(); }
    @Override public void bindVao(int vao) { throw new UnsupportedOperationException(); }
    @Override public void bindVbo(int vbo) { throw new UnsupportedOperationException(); }
    @Override public void uploadVboData(float[] data) { throw new UnsupportedOperationException(); }
    @Override public void uploadVboData(int[] data) { throw new UnsupportedOperationException(); }
    @Override public void uploadVboBytes(ByteBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void deleteVao(int vao) { throw new UnsupportedOperationException(); }
    @Override public void deleteVbo(int vbo) { throw new UnsupportedOperationException(); }
    @Override public void drawArrays(int mode, int offset, int count) { throw new UnsupportedOperationException(); }
    @Override public void drawElements(int mode, int count) { throw new UnsupportedOperationException(); }
    @Override public void enableBlend() { throw new UnsupportedOperationException(); }
    @Override public void disableBlend() { throw new UnsupportedOperationException(); }
    @Override public void setBlendFunc(int s, int d) { throw new UnsupportedOperationException(); }
    @Override public ShaderProgram createShader(String v, String f) { throw new UnsupportedOperationException(); }
    @Override public int createTexture(int w, int h, int[] p) { throw new UnsupportedOperationException(); }
    @Override public void dispose() { /* 无资源可释放 */ }
}
