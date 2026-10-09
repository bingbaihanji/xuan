package com.bingbaihanji.xuan.gpu;

import com.bingbaihanji.xuan.util.Disposable;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL43.*;

/**
 * OpenGL 计算着色器程序：编译 GLSL 源码、链接成 program，并提供 dispatch 与 uniform 的便捷方法。
 */
public class ComputeShader implements Disposable {

    private final int programId;

    private boolean disposed = false;

    /**
     * @param source GLSL 计算着色器源码
     * @throws RuntimeException 编译或链接失败
     */
    public ComputeShader(String source) {
        int shaderId = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shaderId, source);
        glCompileShader(shaderId);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer success = stack.mallocInt(1);
            glGetShaderiv(shaderId, GL_COMPILE_STATUS, success);
            if (success.get(0) == 0) {
                String log = glGetShaderInfoLog(shaderId);
                glDeleteShader(shaderId);
                throw new RuntimeException("Compute shader compilation failed:\n" + log);
            }
        }

        programId = glCreateProgram();
        glAttachShader(programId, shaderId);
        glLinkProgram(programId);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer success = stack.mallocInt(1);
            glGetProgramiv(programId, GL_LINK_STATUS, success);
            if (success.get(0) == 0) {
                String log = glGetProgramInfoLog(programId);
                glDeleteShader(shaderId);
                glDeleteProgram(programId);
                throw new RuntimeException("Compute shader program linking failed:\n" + log);
            }
        }

        glDeleteShader(shaderId);
    }

    /**
     * 绑定为当前 program。
     *
     * @return this，便于链式调用
     */
    public ComputeShader use() {
        glUseProgram(programId);
        return this;
    }

    /** 解绑 program（置为 0）。 */
    public void unuse() {
        glUseProgram(0);
    }

    /**
     * 派发计算工作组。
     *
     * @param numGroupsX X 维工作组数
     * @param numGroupsY Y 维工作组数
     * @param numGroupsZ Z 维工作组数
     */
    public void dispatch(int numGroupsX, int numGroupsY, int numGroupsZ) {
        glDispatchCompute(numGroupsX, numGroupsY, numGroupsZ);
    }

    /**
     * 插入内存屏障（GL_ALL_BARRIER_BITS），保证此前的计算写入对后续操作可见。
     *
     * @return this，便于链式调用
     */
    public ComputeShader memoryBarrier() {
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        return this;
    }

    /**
     * @param name uniform 名
     * @return uniform 位置，未找到时返回 -1
     */
    public int getUniformLocation(String name) {
        return glGetUniformLocation(programId, name);
    }

    /**
     * @param name  uniform 名
     * @param value 整数值
     */
    public void setUniform(String name, int value) {
        glUniform1i(getUniformLocation(name), value);
    }

    /**
     * @param name  uniform 名
     * @param value 浮点值
     */
    public void setUniform(String name, float value) {
        glUniform1f(getUniformLocation(name), value);
    }

    /**
     * @return OpenGL program ID
     */
    public int getProgramId() {
        return programId;
    }

    @Override
    public void dispose() {
        if (!disposed) {
            glDeleteProgram(programId);
            disposed = true;
        }
    }
}
