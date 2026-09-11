package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Color;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

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
    public void dispose() {
        // No persistent native resources to release at this level
    }
}
