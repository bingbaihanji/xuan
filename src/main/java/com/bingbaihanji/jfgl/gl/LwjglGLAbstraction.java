package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Color;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * LWJGL-based implementation of {@link GLAbstraction} that delegates
 * to the standard LWJGL OpenGL bindings (GL11, GL15, GL20, GL30).
 * <p>
 * This adapter is intended for use inside an OpenGLFX canvas where
 * the GL context is already current.
 */
public class LwjglGLAbstraction implements GLAbstraction {

    @Override
    public void initialize() {
        // GL context is already current when called from OpenGLFX events
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
    public void deleteVao(int vao) {
        GL30.glDeleteVertexArrays(vao);
    }

    @Override
    public void deleteVbo(int vbo) {
        GL15.glDeleteBuffers(vbo);
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
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels
        );
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
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
    public int readUnsignedIntPixel(int x, int y) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 必须是 callocInt 而不是 mallocInt：mallocInt 不清零，若 glReadPixels
            // 因 GL 错误没写入，读回的就是栈上的残留值——非确定的拾取结果。
            IntBuffer buffer = stack.callocInt(1);
            GL11.glReadPixels(x, y, 1, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, buffer);
            return buffer.get(0);
        }
    }

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
    public void readUnsignedIntPixels(int x, int y, int width, int height, int[] out) {
        IntBuffer buffer = allocateReadBuffer(width * height);
        GL11.glReadPixels(x, y, width, height,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, buffer);
        buffer.get(out, 0, width * height);
    }

    @Override
    public void dispose() {
        // No persistent native resources to release at this level
    }
}
