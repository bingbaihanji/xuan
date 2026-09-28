package com.bingbaihanji.xuan.gl;

import com.bingbaihanji.xuan.util.Color;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * {@link GLAbstraction} 的 LWJGL 实现：把每次调用转给 LWJGL 的 OpenGL 绑定。
 *
 * <p>它假定调用时 GL 上下文已经 current——本项目的上下文由 {@code GLCanvas} 置为当前，
 * 所以 {@link #initialize()} 与 {@link #dispose()} 都是诚实的 no-op。
 */
public class LwjglGLAbstraction implements GLAbstraction {

    /**
     * 为区域读回分配缓冲。
     *
     * <p><strong>不能用 {@link MemoryStack}</strong>：它默认只有 64 KB
     * （{@code Configuration.STACK_SIZE} 默认 64），而这里需要
     * {@code pixels * 4} 字节。128×128 恰好是临界点，再大一点就抛
     * {@link OutOfMemoryError}——框选稍大一点即废，且抛的是 {@code Error}。
     *
     * <p>返回直接缓冲交给 GC：{@code pickRect} 是用户触发的低频查询（框选），
     * 不是每帧路径，为此维护可复用缓冲池是不必要的复杂度。
     *
     * <p>包级可见且不碰 GL，<strong>专为可测</strong>：真正的方法需要 GL 上下文，
     * 在单测里调用会让 JVM 直接 abort。
     *
     * @param pixels 像素个数，必须非负
     * @return 容量为 {@code pixels} 的直接 IntBuffer
     */
    static IntBuffer allocateReadBuffer(int pixels) {
        return BufferUtils.createIntBuffer(pixels);
    }

    @Override
    public void initialize() {
        // 从 GLCanvas 的回调进来时 GL 上下文已经 current，这里无事可做。
    }

    @Override
    public void clear(Color color) {
        GL11.glClearColor(color.r(), color.g(), color.b(), color.a());
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    @Override
    public void setViewport(int x, int y, int width, int height) {
        GL11.glViewport(x, y, width, height);
    }

    @Override
    public int createVao() {
        return GL30.glGenVertexArrays();
    }

    @Override
    public int createVbo() {
        return GL15.glGenBuffers();
    }

    @Override
    public void bindVao(int vao) {
        GL30.glBindVertexArray(vao);
    }

    @Override
    public void bindVbo(int vbo) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
    }

    @Override
    public void uploadVboData(float[] data) {
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
    }

    @Override
    public void uploadVboData(int[] data) {
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
    }

    @Override
    public void uploadVboBytes(ByteBuffer data) {
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
    }

    @Override
    public void uploadVboSubData(int offsetBytes, ByteBuffer data) {
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offsetBytes, data);
    }

    @Override
    public void setVertexAttribDivisor(int index, int divisor) {
        GL33.glVertexAttribDivisor(index, divisor);
    }

    @Override
    public void drawArraysInstancedBaseInstance(int mode, int first, int count,
                                                int instanceCount, int baseInstance) {
        GL42.glDrawArraysInstancedBaseInstance(mode, first, count, instanceCount, baseInstance);
    }

    @Override
    public void deleteVao(int vao) {
        GL30.glDeleteVertexArrays(vao);
    }

    @Override
    public void deleteVbo(int vbo) {
        GL15.glDeleteBuffers(vbo);
    }

    @Override
    public int createBuffer() {
        return GL15.glGenBuffers();
    }

    @Override
    public void bindShaderStorageBuffer(int buffer) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
    }

    @Override
    public void allocateBufferStorage(long sizeBytes) {
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, sizeBytes, GL15.GL_DYNAMIC_COPY);
    }

    @Override
    public void uploadBufferSubData(long offsetBytes, ByteBuffer data) {
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, offsetBytes, data);
    }

    @Override
    public void bindBufferBase(int bindingIndex, int buffer) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, bindingIndex, buffer);
    }

    @Override
    public void deleteBuffer(int buffer) {
        GL15.glDeleteBuffers(buffer);
    }

    @Override
    public void drawArrays(int mode, int offset, int count) {
        GL11.glDrawArrays(mode, offset, count);
    }

    @Override
    public void drawElements(int mode, int count) {
        GL11.glDrawElements(mode, count, GL11.GL_UNSIGNED_INT, 0);
    }

    @Override
    public void enableBlend() {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    @Override
    public void disableBlend() {
        GL11.glDisable(GL11.GL_BLEND);
    }

    @Override
    public void setBlendFunc(int srcFactor, int dstFactor) {
        GL11.glBlendFunc(srcFactor, dstFactor);
    }

    @Override
    public ShaderProgram createShader(String vertexSource, String fragmentSource) {
        return new ShaderProgram(vertexSource, fragmentSource);
    }

    @Override
    public int createTexture(int width, int height, int[] pixels) {
        ByteBuffer rgba = argbToRgba(width, height, pixels);
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba
        );
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    /**
     * 把公共 API 的 ARGB 整数像素转换成 OpenGL 上传所需的 RGBA 字节流。
     *
     * <p>不能直接把 {@code int[]} 交给 {@code glTexImage2D}：在小端机器上，
     * {@code 0xAARRGGBB} 的内存顺序是 B,G,R,A，蓝红通道会互换。该转换保持
     * 颜色契约与 {@link com.bingbaihanji.xuan.util.Color}、图表和 JavaFX 互操作一致。
     */
    static ByteBuffer argbToRgba(int width, int height, int[] pixels) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("纹理尺寸必须为正数：" + width + "x" + height);
        }
        if (pixels == null || pixels.length != width * height) {
            throw new IllegalArgumentException(
                    "像素数量必须等于纹理面积，实际为 " + (pixels == null ? 0 : pixels.length)
                            + "，期望 " + (width * height));
        }
        ByteBuffer rgba = BufferUtils.createByteBuffer(width * height * 4);
        for (int pixel : pixels) {
            rgba.put((byte) ((pixel >>> 16) & 0xFF));
            rgba.put((byte) ((pixel >>> 8) & 0xFF));
            rgba.put((byte) (pixel & 0xFF));
            rgba.put((byte) ((pixel >>> 24) & 0xFF));
        }
        return rgba.flip();
    }

    @Override
    public int createR8Texture(int width, int height) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // R8 是归一化格式，可以 LINEAR —— SDF 靠插值得到平滑边缘。
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        // CLAMP_TO_EDGE：默认的 REPEAT 会让图集边缘的采样绕回另一侧，
        // 表现为远端字形的边缘挂着别处的像素。
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // 内容未初始化：图集只采样已经上传过的槽位，而每个槽位四周有 SPREAD 像素的
        // 外扩把双线性过滤限制在槽内，所以未分配区域永远不会被采到。
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, width, height, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    @Override
    public void uploadR8SubImage(int texture, int x, int y, int width, int height, byte[] pixels) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // GL_UNPACK_ALIGNMENT 默认是 4，而单通道每行只有 width 个字节。
        // 不设成 1，width 不是 4 的倍数时从第二行起整行错位，字形会被斜切。
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        try {
            // 不能用 MemoryStack：它默认只有 64 KB，而一张 64x64 的字形是 4 KB
            // 还好、整块图集就是 16 MB，直接炸。BufferUtils 的直接缓冲交给 GC。
            ByteBuffer buf = BufferUtils.createByteBuffer(width * height);
            buf.put(pixels, 0, width * height);
            buf.flip();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, width, height,
                    GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, buf);
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
    }

    @Override
    public int createFramebuffer() {
        return GL30.glGenFramebuffers();
    }

    @Override
    public void bindFramebuffer(int framebuffer) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
    }

    @Override
    public void deleteFramebuffer(int framebuffer) {
        GL30.glDeleteFramebuffers(framebuffer);
    }

    @Override
    public int currentFramebufferBinding() {
        return GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
    }

    @Override
    public int createIntegerTexture(int width, int height) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // 整数纹理的过滤器必须是 GL_NEAREST。GL_LINEAR 对整数纹理非法
        // （会生成 GL_INVALID_OPERATION，且采样结果未定义）。默认的
        // GL_NEAREST_MIPMAP_LINEAR 在没有 mipmap 时同样是不完整的。
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32UI,
                width, height, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT,
                (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    @Override
    public void deleteTexture(int texture) {
        GL11.glDeleteTextures(texture);
    }

    @Override
    public void attachTextureToColor0(int texture) {
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, texture, 0);
    }

    @Override
    public int framebufferStatus() {
        return GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
    }

    @Override
    public void clearIntegerColor(int value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buffer = stack.ints(value, 0, 0, 0);
            GL30.glClearBufferuiv(GL30.GL_COLOR, 0, buffer);
        }
    }

    @Override
    public boolean isScissorEnabled() {
        return GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    }

    @Override
    public void setScissorEnabled(boolean enabled) {
        if (enabled) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    @Override
    public int readUnsignedIntPixel(int x, int y) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 必须是 callocInt 而不是 mallocInt：mallocInt 不清零，若 glReadPixels
            // 因 GL 错误没写入，读回的就是栈上的残留值——非确定的拾取结果。
            IntBuffer buffer = stack.callocInt(1);
            GL11.glReadPixels(x, y, 1, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, buffer);
            return buffer.get(0);
        }
    }

    @Override
    public void readUnsignedIntPixels(int x, int y, int width, int height, int[] out) {
        IntBuffer buffer = allocateReadBuffer(width * height);
        GL11.glReadPixels(x, y, width, height,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, buffer);
        buffer.get(out, 0, width * height);
    }

    @Override
    public int createPixelPackBuffer() {
        return GL15.glGenBuffers();
    }

    @Override
    public void deletePixelPackBuffer(int buffer) {
        GL15.glDeleteBuffers(buffer);
    }

    @Override
    public void enqueueUnsignedIntPixelRead(int x, int y, int buffer) {
        int previous = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
        try {
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, Integer.BYTES, GL15.GL_STREAM_READ);
            GL11.glReadPixels(x, y, 1, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, 0L);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previous);
        }
    }

    @Override
    public long fenceSync() {
        long sync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (sync == 0L) {
            throw new IllegalStateException("glFenceSync 创建失败");
        }
        return sync;
    }

    @Override
    public boolean isSyncSignaled(long sync) {
        int status = GL32.glClientWaitSync(sync, 0, 0L);
        if (status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED) {
            return true;
        }
        if (status == GL32.GL_TIMEOUT_EXPIRED) {
            return false;
        }
        throw new IllegalStateException("glClientWaitSync 失败，状态码 0x" + Integer.toHexString(status));
    }

    @Override
    public void deleteSync(long sync) {
        if (sync != 0L) {
            GL32.glDeleteSync(sync);
        }
    }

    @Override
    public int readUnsignedIntPixelFromPixelPackBuffer(int buffer) {
        int previous = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
        try {
            ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0,
                    Integer.BYTES, GL30.GL_MAP_READ_BIT, null);
            if (mapped == null) {
                throw new IllegalStateException("映射拾取 PBO 失败");
            }
            try {
                return mapped.order(ByteOrder.nativeOrder()).getInt(0);
            } finally {
                if (!GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER)) {
                    throw new IllegalStateException("拾取 PBO 在映射期间被损坏");
                }
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previous);
        }
    }

    @Override
    public void dispose() {
        // 这一层自己不持有任何长期的本机资源：纹理、缓冲、VAO 都是按需创建、
        // 由各自的持有者显式删除的，没有可在这里统一释放的东西。
    }
}
