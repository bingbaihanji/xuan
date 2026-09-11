package com.bingbaihanji.jfgl.gpu;

import com.bingbaihanji.jfgl.util.Disposable;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL43.*;

/**
 * Represents an OpenGL 4.3 compute shader program.
 * <p>
 * Compiles the given GLSL compute shader source, links it into a program,
 * and provides convenience methods for dispatching compute work and setting uniforms.
 * </p>
 */
public class ComputeShader implements Disposable {

    private final int programId;

    private boolean disposed = false;

    /**
     * Creates a new compute shader from the given GLSL source code.
     *
     * @param source the GLSL compute shader source code
     * @throws RuntimeException if compilation or linking fails
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
     * Binds this compute shader program as the active program.
     *
     * @return this instance for method chaining
     */
    public ComputeShader use() {
        glUseProgram(programId);
        return this;
    }

    /**
     * Unbinds any compute shader program (sets the active program to 0).
     */
    public void unuse() {
        glUseProgram(0);
    }

    /**
     * Dispatches compute work groups.
     *
     * @param numGroupsX number of work groups in the X dimension
     * @param numGroupsY number of work groups in the Y dimension
     * @param numGroupsZ number of work groups in the Z dimension
     */
    public void dispatch(int numGroupsX, int numGroupsY, int numGroupsZ) {
        glDispatchCompute(numGroupsX, numGroupsY, numGroupsZ);
    }

    /**
     * Issues a memory barrier to ensure prior compute shader writes are visible
     * to subsequent operations.
     *
     * @return this instance for method chaining
     */
    public ComputeShader memoryBarrier() {
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        return this;
    }

    /**
     * Retrieves the location of a uniform variable in this shader program.
     *
     * @param name the name of the uniform variable
     * @return the uniform location, or -1 if not found
     */
    public int getUniformLocation(String name) {
        return glGetUniformLocation(programId, name);
    }

    /**
     * Sets an integer uniform value.
     *
     * @param name  the name of the uniform variable
     * @param value the integer value to set
     */
    public void setUniform(String name, int value) {
        glUniform1i(getUniformLocation(name), value);
    }

    /**
     * Sets a float uniform value.
     *
     * @param name  the name of the uniform variable
     * @param value the float value to set
     */
    public void setUniform(String name, float value) {
        glUniform1f(getUniformLocation(name), value);
    }

    /**
     * Returns the OpenGL program ID.
     *
     * @return the program ID
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
