package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;
import com.bingbaihanji.jfgl.math.Mat3;

public class RenderContext {

    private final GLAbstraction gl;

    private ShaderProgram currentShader;

    private Mat3 viewProjectionMatrix;

    public RenderContext(GLAbstraction gl) {
        this.gl = gl;
        this.viewProjectionMatrix = Mat3.identity();
    }

    public GLAbstraction getGl() {
        return gl;
    }

    public void useShader(ShaderProgram shader) {
        this.currentShader = shader;
    }

    public ShaderProgram getCurrentShader() {
        return currentShader;
    }

    public Mat3 getViewProjectionMatrix() {
        return viewProjectionMatrix;
    }

    public void setViewProjectionMatrix(Mat3 matrix) {
        this.viewProjectionMatrix = matrix;
    }
}
