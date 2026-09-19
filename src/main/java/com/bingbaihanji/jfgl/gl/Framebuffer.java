package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Disposable;

/**
 * 一个附有 {@code R32UI} 整数纹理的帧缓冲对象（FBO）。
 *
 * <p>只负责<strong>资源生命周期</strong>与创建时的完整性检查；
 * 绑定、清空、读回这些「用法」留给 {@code PickBuffer}。
 *
 * <p><strong>恒为单采样。</strong>拾取需要精确的 ID，抗锯齿产生的部分覆盖像素
 * 把 ID 插值成「零点几个对象」没有意义。
 *
 * <p>所有方法必须在 GL 线程上调用。
 */
public final class Framebuffer implements Disposable {

    /** GL 抽象层。 */
    private final GLAbstraction gl;

    /** FBO 的 ID。 */
    private int framebuffer;

    /** 颜色附件纹理的 ID。 */
    private int texture;

    /** 宽度（像素）。 */
    private final int width;

    /** 高度（像素）。 */
    private final int height;

    /** 是否已释放，保证 {@link #dispose()} 幂等。 */
    private boolean disposed = false;

    /**
     * 创建帧缓冲并把一张 {@code R32UI} 纹理挂到 0 号颜色附件上。
     *
     * <p>构造过程中会临时绑定自己的 FBO，<strong>结束后恢复原绑定</strong>：
     * openglfx 渲染到它自己的 FBO，不复原的话后续所有绘制都会画进这里。
     *
     * @param gl     GL 抽象层
     * @param width  宽度（像素），必须为正
     * @param height 高度（像素），必须为正
     * @throws IllegalArgumentException 宽或高不为正时
     * @throws IllegalStateException    帧缓冲不完整时
     */
    public Framebuffer(GLAbstraction gl, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "帧缓冲尺寸必须为正：实际 " + width + "x" + height);
        }
        this.gl = gl;
        this.width = width;
        this.height = height;

        int previous = gl.currentFramebufferBinding();
        this.framebuffer = gl.createFramebuffer();
        this.texture = gl.createIntegerTexture(width, height);
        gl.bindFramebuffer(framebuffer);
        gl.attachTextureToColor0(texture);
        int status = gl.framebufferStatus();
        gl.bindFramebuffer(previous);

        // 不完整的帧缓冲读回来全是 0，表现为「什么都拾取不到」——一个安静的、
        // 看起来像业务逻辑问题的失败。必须在这里就炸掉，并带上状态码以便定位
        // （GL_FRAMEBUFFER_UNSUPPORTED 与 GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT
        // 的排查方向完全不同）。
        if (status != GLAbstraction.FRAMEBUFFER_COMPLETE) {
            dispose();
            throw new IllegalStateException(String.format(
                    "拾取帧缓冲创建失败（%dx%d）：glCheckFramebufferStatus = 0x%04X，期望 0x%04X",
                    width, height, status, GLAbstraction.FRAMEBUFFER_COMPLETE));
        }
    }

    /**
     * 返回 FBO 的 ID。
     *
     * @return FBO 的 ID
     */
    public int id() {
        return framebuffer;
    }

    /**
     * 返回颜色附件纹理的 ID。
     *
     * @return 纹理的 ID
     */
    public int textureId() {
        return texture;
    }

    /**
     * 返回宽度（像素）。
     *
     * @return 宽度
     */
    public int width() {
        return width;
    }

    /**
     * 返回高度（像素）。
     *
     * @return 高度
     */
    public int height() {
        return height;
    }

    /** 释放 FBO 与纹理。重复调用无副作用。 */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        if (framebuffer != 0) {
            gl.deleteFramebuffer(framebuffer);
            framebuffer = 0;
        }
        if (texture != 0) {
            gl.deleteTexture(texture);
            texture = 0;
        }
        disposed = true;
    }
}
