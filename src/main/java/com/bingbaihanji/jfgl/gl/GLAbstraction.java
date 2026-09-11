package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Color;
import com.bingbaihanji.jfgl.util.Disposable;

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
}
