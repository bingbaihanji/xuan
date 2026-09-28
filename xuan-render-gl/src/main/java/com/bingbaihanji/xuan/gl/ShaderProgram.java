package com.bingbaihanji.xuan.gl;

import com.bingbaihanji.xuan.util.Disposable;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL20.*;

/**
 * 管理由顶点着色器和片段着色器组成的 OpenGL 着色器程序。
 *
 * <h2>线程</h2>
 * <p>与 {@code Gc}、{@code RenderBatch} 等持有 GL 资源的类同一条纪律：
 * <b>所有方法只在 GL 线程调用</b>。本类不是线程安全的——它有一处可变的缓存
 * （{@link #uniformLocations}），而 {@code glUseProgram} / {@code glUniform*} 本身就是
 * 上下文状态操作，跨线程调用无论如何都不成立。
 */
public class ShaderProgram implements Disposable {

    /** 着色器程序 ID */
    private int programId;

    private boolean disposed;

    /**
     * uniform 名字 → 位置。程序链接后位置就固定了，不必每次现查。
     *
     * <p><strong>本类不提供重新链接入口</strong>（着色器源码只在构造时编译、链接一次）。
     * 若将来加入 {@code glLinkProgram}，<b>必须同时清空这个缓存</b>：
     * 重新链接之后位置可能变，而缓存会**静默返回旧位置**——{@code glUniform*} 拿旧位置
     * 去设值，症状是"uniform 设了但没生效"，画面只是不对，不报任何错。
     */
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    /** 顶点着色器 ID */
    private int vertexShaderId;

    /** 片段着色器 ID */
    private int fragmentShaderId;

    /**
     * 通过编译和链接给定的顶点和片段着色器源码创建新的着色器程序。
     *
     * @param vertexSource   顶点着色器的 GLSL 源码
     * @param fragmentSource 片段着色器的 GLSL 源码
     * @throws RuntimeException 如果创建、编译或链接失败
     */
    public ShaderProgram(String vertexSource, String fragmentSource) {
        programId = glCreateProgram();
        if (programId == 0) {
            throw new RuntimeException("创建着色器程序失败");
        }

        vertexShaderId = compileShader(GL_VERTEX_SHADER, vertexSource);
        fragmentShaderId = compileShader(GL_FRAGMENT_SHADER, fragmentSource);

        glLinkProgram(programId);
        if (glGetProgrami(programId, GL_LINK_STATUS) == 0) {
            throw new RuntimeException("链接着色器程序失败：" + glGetProgramInfoLog(programId));
        }

        glValidateProgram(programId);
        if (glGetProgrami(programId, GL_VALIDATE_STATUS) == 0) {
            System.err.println("警告：验证着色器程序：" + glGetProgramInfoLog(programId));
        }
    }

    /**
     * 从提供的源码编译指定类型的着色器。
     *
     * @param type   着色器类型（如 {@link GL20#GL_VERTEX_SHADER}、{@link GL20#GL_FRAGMENT_SHADER}）
     * @param source GLSL 源码
     * @return 编译后的着色器 ID
     * @throws RuntimeException 如果编译失败
     */
    private int compileShader(int type, String source) {
        int shaderId = glCreateShader(type);
        if (shaderId == 0) {
            throw new RuntimeException("创建类型为 " + type + " 的着色器失败");
        }

        glShaderSource(shaderId, source);
        glCompileShader(shaderId);

        if (glGetShaderi(shaderId, GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("编译着色器失败：" + glGetShaderInfoLog(shaderId));
        }

        glAttachShader(programId, shaderId);

        return shaderId;
    }

    /**
     * 激活此着色器程序，用于后续的绘制调用。
     */
    public void use() {
        checkNotDisposed();
        glUseProgram(programId);
    }

    /**
     * 停用任何活动的着色器程序。
     */
    public void unuse() {
        glUseProgram(0);
    }

    /**
     * 获取此程序中 uniform 变量的位置，带缓存。
     *
     * <p><strong>为什么要缓存</strong>：{@code glGetUniformLocation} 是按名字做字符串查找。
     * 图表每个系列每帧要设约 8 个 uniform，多个系列叠加时它会变成热路径上的可见开销。
     * 程序链接之后位置就固定了，因此可以安全缓存。
     *
     * <p>找不到的 uniform 会返回 -1 并<strong>把这个 -1 也缓存下来</strong>——
     * 免得每帧都去查一个永远不存在的名字。注意 -1 传给 {@code glUniform*} 是**静默无操作**，
     * 所以 uniform 名字写错不会有任何报错，只会"设了但没生效"。
     *
     * @param name uniform 变量的名称
     * @return uniform 位置，未找到时为 -1
     */
    public int getUniformLocation(String name) {
        checkNotDisposed();
        Integer cached = uniformLocations.get(name);
        if (cached != null) {
            return cached;
        }
        int location = glGetUniformLocation(programId, name);
        uniformLocations.put(name, location);
        return location;
    }

    /**
     * 设置单个浮点 uniform。
     *
     * @param name  uniform 名称
     * @param value 浮点值
     */
    public void setUniform(String name, float value) {
        glUniform1f(getUniformLocation(name), value);
    }

    /**
     * 设置两个分量的浮点 uniform。
     *
     * @param name uniform 名称
     * @param x    第一个分量
     * @param y    第二个分量
     */
    public void setUniform(String name, float x, float y) {
        glUniform2f(getUniformLocation(name), x, y);
    }

    /**
     * 设置四分量浮点 uniform。
     *
     * <p>图表用它一次传绘图区矩形（x, y, 宽, 高）。
     *
     * <p><strong>不要拿 {@link #setUniform(String, float[])} 代替</strong>——
     * 那个是 mat3（{@code glUniformMatrix3fv}），名字像但语义完全不同，
     * 而且传错了不会有任何报错。
     *
     * @param name uniform 名称
     * @param x    第一个分量
     * @param y    第二个分量
     * @param z    第三个分量
     * @param w    第四个分量
     */
    public void setUniform(String name, float x, float y, float z, float w) {
        glUniform4f(getUniformLocation(name), x, y, z, w);
    }

    /**
     * 从扁平浮点数组（列优先顺序）设置 3x3 矩阵 uniform。
     *
     * @param name   uniform 名称
     * @param matrix 表示 3x3 矩阵的 9 元素浮点数组
     */
    public void setUniform(String name, float[] matrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(matrix.length);
            buffer.put(matrix).flip();
            glUniformMatrix3fv(getUniformLocation(name), false, buffer);
        }
    }

    /**
     * 设置整数 uniform。
     *
     * @param name  uniform 名称
     * @param value 整数值
     */
    public void setUniform(String name, int value) {
        glUniform1i(getUniformLocation(name), value);
    }

    /**
     * 释放所有着色器资源。
     */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        unuse();
        if (vertexShaderId != 0) {
            glDetachShader(programId, vertexShaderId);
            glDeleteShader(vertexShaderId);
            vertexShaderId = 0;
        }
        if (fragmentShaderId != 0) {
            glDetachShader(programId, fragmentShaderId);
            glDeleteShader(fragmentShaderId);
            fragmentShaderId = 0;
        }
        glDeleteProgram(programId);
        programId = 0;
        disposed = true;
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("着色器程序已释放");
        }
    }
}
