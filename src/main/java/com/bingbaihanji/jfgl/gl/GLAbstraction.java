package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Color;
import com.bingbaihanji.jfgl.util.Disposable;

import java.nio.ByteBuffer;

/**
 * OpenGL 抽象层接口。
 * <p>
 * 提供平台无关的 OpenGL 操作封装，便于在不同 OpenGL 实现之间切换。
 */
public interface GLAbstraction extends Disposable {

    /**
     * 初始化 OpenGL 状态。
     */
    void initialize();

    /**
     * 使用指定颜色清除屏幕。
     *
     * @param color 清除颜色
     */
    void clear(Color color);

    /**
     * 设置视口。
     *
     * @param x      视口左下角 x 坐标
     * @param y      视口左下角 y 坐标
     * @param width  视口宽度
     * @param height 视口高度
     */
    void setViewport(int x, int y, int width, int height);

    /**
     * 创建顶点数组对象（VAO）。
     *
     * @return VAO 的 ID
     */
    int createVao();

    /**
     * 创建顶点缓冲对象（VBO）。
     *
     * @return VBO 的 ID
     */
    int createVbo();

    /**
     * 绑定顶点数组对象。
     *
     * @param vao VAO 的 ID
     */
    void bindVao(int vao);

    /**
     * 绑定顶点缓冲对象。
     *
     * @param vbo VBO 的 ID
     */
    void bindVbo(int vbo);

    /**
     * 上传浮点数组数据到当前绑定的 VBO。
     *
     * @param data 要上传的浮点数据
     */
    void uploadVboData(float[] data);

    /**
     * 上传整数数组数据到当前绑定的 VBO。
     *
     * @param data 要上传的整数数据
     */
    void uploadVboData(int[] data);

    /**
     * 上传字节数据到当前绑定的 VBO。
     *
     * <p>与浮点/整数重载不同，本方法通常用于直接上传已经打包好的顶点字节，
     * 上传量为 {@code data} 的剩余字节数（{@code data.remaining()}）。
     *
     * @param data 要上传的字节数据
     */
    void uploadVboBytes(ByteBuffer data);

    /**
     * 删除顶点数组对象。
     *
     * @param vao VAO 的 ID
     */
    void deleteVao(int vao);

    /**
     * 删除顶点缓冲对象。
     *
     * @param vbo VBO 的 ID
     */
    void deleteVbo(int vbo);

    /**
     * 执行数组绘制调用。
     *
     * @param mode  图元类型（如 GL_TRIANGLES）
     * @param offset 起始索引
     * @param count  顶点数量
     */
    void drawArrays(int mode, int offset, int count);

    /**
     * 执行索引绘制调用。
     *
     * @param mode  图元类型（如 GL_TRIANGLES）
     * @param count 索引数量
     */
    void drawElements(int mode, int count);

    /**
     * 启用混合。
     */
    void enableBlend();

    /**
     * 禁用混合。
     */
    void disableBlend();

    /**
     * 设置混合函数。
     *
     * @param srcFactor 源混合因子
     * @param dstFactor 目标混合因子
     */
    void setBlendFunc(int srcFactor, int dstFactor);

    /**
     * 创建着色器程序。
     *
     * @param vertexSource   顶点着色器源码
     * @param fragmentSource 片段着色器源码
     * @return 着色器程序
     */
    ShaderProgram createShader(String vertexSource, String fragmentSource);

    /**
     * 创建纹理。
     *
     * @param width  纹理宽度
     * @param height 纹理高度
     * @param pixels 像素数据（RGBA 格式）
     * @return 纹理的 ID
     */
    int createTexture(int width, int height, int[] pixels);

    /**
     * {@code GL_FRAMEBUFFER_COMPLETE} 的枚举值。
     *
     * <p>放在接口上是为了让调用方不必为了比较一个状态码而引入 LWJGL 的常量。
     */
    int FRAMEBUFFER_COMPLETE = 0x8CD5;

    /**
     * 创建帧缓冲对象（FBO）。
     *
     * @return FBO 的 ID
     */
    int createFramebuffer();

    /**
     * 绑定帧缓冲。0 表示默认帧缓冲。
     *
     * @param framebuffer FBO 的 ID
     */
    void bindFramebuffer(int framebuffer);

    /**
     * 删除帧缓冲对象。
     *
     * @param framebuffer FBO 的 ID
     */
    void deleteFramebuffer(int framebuffer);

    /**
     * 返回当前绑定的帧缓冲 ID。
     *
     * <p>openglfx 渲染到它<strong>自己的</strong> FBO，因此正常运行时这个值通常<strong>非 0</strong>。
     * 任何临时切换帧缓冲的操作都必须先取这个值、事后再恢复回去。
     *
     * @return 当前绑定的帧缓冲 ID
     */
    int currentFramebufferBinding();

    /**
     * 创建一张 {@code R32UI} 整数纹理。
     *
     * <p>整数纹理的过滤器<strong>必须</strong>是 {@code GL_NEAREST}：{@code GL_LINEAR}
     * 对整数纹理非法。
     *
     * @param width  纹理宽度
     * @param height 纹理高度
     * @return 纹理的 ID
     */
    int createIntegerTexture(int width, int height);

    /**
     * 删除纹理。
     *
     * @param texture 纹理的 ID
     */
    void deleteTexture(int texture);

    /**
     * 把一张 2D 纹理挂到<strong>当前绑定的</strong>帧缓冲的 0 号颜色附件上。
     *
     * @param texture 纹理的 ID
     */
    void attachTextureToColor0(int texture);

    /**
     * 返回当前绑定的帧缓冲的完整性状态，等于 {@link #FRAMEBUFFER_COMPLETE} 表示可用。
     *
     * @return 帧缓冲状态码
     */
    int framebufferStatus();

    /**
     * 用一个整数清除值清空当前绑定的帧缓冲的 0 号颜色附件。
     *
     * <p>整数附件<strong>不能</strong>用 {@code glClearColor} + {@code glClear}：
     * 那对整数附件是未定义行为。本方法内部走 {@code glClearBufferuiv}。
     *
     * @param value 清除值（写进 R 通道，其余通道为 0）
     */
    void clearIntegerColor(int value);

    /**
     * 返回裁剪测试是否已启用。
     *
     * @return 已启用时为 true
     */
    boolean isScissorEnabled();

    /**
     * 启用或关闭裁剪测试。
     *
     * @param enabled 是否启用
     */
    void setScissorEnabled(boolean enabled);

    /**
     * 读回一个无符号整数像素。
     *
     * <p>坐标是 <strong>GL 约定</strong>：原点在帧缓冲左下角、y 向上。
     * 调用方负责从「原点左上、y 向下」的用户坐标换算过来。
     *
     * @param x 像素 x（GL 约定）
     * @param y 像素 y（GL 约定）
     * @return 该像素的整数值
     */
    int readUnsignedIntPixel(int x, int y);

    /**
     * 读回一块无符号整数像素。
     *
     * <p>坐标同样是 GL 约定。读回的行序<strong>自下而上</strong>，
     * 即 {@code out} 的第 0 行对应 GL 坐标系里最下面那一行。
     *
     * @param x      左上角 x（GL 约定）
     * @param y      左上角 y（GL 约定）
     * @param width  宽度
     * @param height 高度
     * @param out    结果数组，长度至少为 {@code width * height}
     */
    void readUnsignedIntPixels(int x, int y, int width, int height, int[] out);
}
