package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Disposable;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL20.*;

/**
 * 管理由顶点着色器和片段着色器组成的 OpenGL 着色器程序。
 */
public class ShaderProgram implements Disposable {

    /** 着色器程序 ID */
    private final int programId;

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
        glUseProgram(programId);
    }

    /**
     * 停用任何活动的着色器程序。
     */
    public void unuse() {
        glUseProgram(0);
    }

    /**
     * 获取此程序中 uniform 变量的位置。
     *
     * @param name uniform 变量的名称
     * @return uniform 位置，如果未找到则返回 {@code -1}
     */
    public int getUniformLocation(String name) {
        return glGetUniformLocation(programId, name);
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
    }
}
