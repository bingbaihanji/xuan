package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;
import com.bingbaihanji.jfgl.util.Color;
import com.bingbaihanji.jfgl.util.Disposable;

import static org.lwjgl.opengl.GL33.*;

/**
 * 批量渲染器，用于高效绘制大量四边形。
 * <p>
 * 通过将多个四边形合并到一个批次中，减少 OpenGL 绘制调用次数，
 * 从而提高渲染性能。支持颜色和纹理坐标。
 */
public class BatchRenderer implements Disposable {

    /** 最大四边形数量 */
    private static final int MAX_QUADS = 10_000;

    /** 每个顶点的浮点数数量：x, y, r, g, b, a, texId */
    private static final int FLOATS_PER_VERTEX = 7;

    /** 每个四边形的顶点数 */
    private static final int VERTICES_PER_QUAD = 4;

    /** 每个四边形的索引数 */
    private static final int INDICES_PER_QUAD = 6;

    /** 顶点着色器源码 */
    private static final String VERTEX_SHADER = """
                                                #version 330 core

                                                layout (location = 0) in vec2 aPosition;
                                                layout (location = 1) in vec4 aColor;
                                                layout (location = 2) in float aTexId;

                                                uniform mat4 uViewProjection;

                                                out vec4 vColor;
                                                out float vTexId;

                                                void main() {
                                                    gl_Position = uViewProjection * vec4(aPosition, 0.0, 1.0);
                                                    vColor = aColor;
                                                    vTexId = aTexId;
                                                }
                                                """;

    /** 片段着色器源码 */
    private static final String FRAGMENT_SHADER = """
                                                  #version 330 core

                                                  in vec4 vColor;
                                                  in float vTexId;

                                                  out vec4 fragColor;

                                                  void main() {
                                                      fragColor = vColor;
                                                  }
                                                  """;

    /** OpenGL 抽象层 */
    private final GLAbstraction gl;

    /** 着色器程序 */
    private final ShaderProgram shader;

    /** 顶点数据缓冲区 */
    private final float[] vertexBuffer;

    /** 索引数据缓冲区 */
    private final int[] indexBuffer;

    /** 顶点数组对象 */
    private int vao;

    /** 顶点缓冲对象 */
    private int vbo;

    /** 索引缓冲对象 */
    private int ebo;

    /** 当前批次中的四边形数量 */
    private int quadCount;

    /**
     * 创建一个新的批量渲染器。
     *
     * @param gl OpenGL 抽象层
     */
    public BatchRenderer(GLAbstraction gl) {
        this.gl = gl;
        this.shader = gl.createShader(VERTEX_SHADER, FRAGMENT_SHADER);

        int maxVertices = MAX_QUADS * VERTICES_PER_QUAD;
        int maxIndices = MAX_QUADS * INDICES_PER_QUAD;

        this.vertexBuffer = new float[maxVertices * FLOATS_PER_VERTEX];
        this.indexBuffer = new int[maxIndices];
        this.quadCount = 0;

        generateIndexBuffer();
        initBuffers();
    }

    /**
     * 生成索引缓冲区数据。
     * <p>
     * 每个四边形由两个三角形组成，使用 6 个索引。
     */
    private void generateIndexBuffer() {
        int offset = 0;
        for (int i = 0; i < MAX_QUADS * INDICES_PER_QUAD; i += INDICES_PER_QUAD) {
            indexBuffer[i] = offset;
            indexBuffer[i + 1] = offset + 1;
            indexBuffer[i + 2] = offset + 2;
            indexBuffer[i + 3] = offset + 2;
            indexBuffer[i + 4] = offset + 3;
            indexBuffer[i + 5] = offset;
            offset += VERTICES_PER_QUAD;
        }
    }

    /**
     * 初始化 OpenGL 缓冲区。
     * <p>
     * 创建 VAO、VBO 和 EBO，并设置顶点属性指针。
     */
    private void initBuffers() {
        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) vertexBuffer.length * Float.BYTES, GL_DYNAMIC_DRAW);

        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;

        // 位置属性 (location = 0): vec2
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);

        // 颜色属性 (location = 1): vec4
        glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        // 纹理ID属性 (location = 2): float
        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 6L * Float.BYTES);
        glEnableVertexAttribArray(2);

        glBindVertexArray(0);
    }

    /**
     * 开始一个新的渲染批次。
     * <p>
     * 重置四边形计数器并激活着色器。
     */
    public void begin() {
        quadCount = 0;
        shader.use();
    }

    /**
     * 绘制一个四边形。
     *
     * @param x      左上角 x 坐标
     * @param y      左上角 y 坐标
     * @param width  四边形宽度
     * @param height 四边形高度
     * @param color  颜色
     */
    public void drawQuad(float x, float y, float width, float height, Color color) {
        drawQuad(x, y, width, height, 0, 0, 1, 1, color);
    }

    /**
     * 绘制一个带纹理坐标的四边形。
     *
     * @param x      左上角 x 坐标
     * @param y      左上角 y 坐标
     * @param width  四边形宽度
     * @param height 四边形高度
     * @param u      纹理左上角 u 坐标
     * @param v      纹理左上角 v 坐标
     * @param u2     纹理右下角 u 坐标
     * @param v2     纹理右下角 v 坐标
     * @param color  颜色
     */
    public void drawQuad(float x, float y, float width, float height,
                         float u, float v, float u2, float v2, Color color) {
        if (quadCount >= MAX_QUADS) {
            flush();
            quadCount = 0;
        }

        int vertexOffset = quadCount * VERTICES_PER_QUAD * FLOATS_PER_VERTEX;

        // 左上角顶点
        vertexBuffer[vertexOffset] = x;
        vertexBuffer[vertexOffset + 1] = y;
        vertexBuffer[vertexOffset + 2] = color.r();
        vertexBuffer[vertexOffset + 3] = color.g();
        vertexBuffer[vertexOffset + 4] = color.b();
        vertexBuffer[vertexOffset + 5] = color.a();
        vertexBuffer[vertexOffset + 6] = 0f;

        // 右上角顶点
        vertexBuffer[vertexOffset + 7] = x + width;
        vertexBuffer[vertexOffset + 8] = y;
        vertexBuffer[vertexOffset + 9] = color.r();
        vertexBuffer[vertexOffset + 10] = color.g();
        vertexBuffer[vertexOffset + 11] = color.b();
        vertexBuffer[vertexOffset + 12] = color.a();
        vertexBuffer[vertexOffset + 13] = 0f;

        // 右下角顶点
        vertexBuffer[vertexOffset + 14] = x + width;
        vertexBuffer[vertexOffset + 15] = y + height;
        vertexBuffer[vertexOffset + 16] = color.r();
        vertexBuffer[vertexOffset + 17] = color.g();
        vertexBuffer[vertexOffset + 18] = color.b();
        vertexBuffer[vertexOffset + 19] = color.a();
        vertexBuffer[vertexOffset + 20] = 0f;

        // 左下角顶点
        vertexBuffer[vertexOffset + 21] = x;
        vertexBuffer[vertexOffset + 22] = y + height;
        vertexBuffer[vertexOffset + 23] = color.r();
        vertexBuffer[vertexOffset + 24] = color.g();
        vertexBuffer[vertexOffset + 25] = color.b();
        vertexBuffer[vertexOffset + 26] = color.a();
        vertexBuffer[vertexOffset + 27] = 0f;

        quadCount++;
    }

    /**
     * 将当前批次的数据刷新到 GPU 并执行绘制调用。
     */
    public void flush() {
        if (quadCount == 0) {
            return;
        }

        glBindVertexArray(vao);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        int vertexCount = quadCount * VERTICES_PER_QUAD * FLOATS_PER_VERTEX;
        glBufferSubData(GL_ARRAY_BUFFER, 0, java.util.Arrays.copyOf(vertexBuffer, vertexCount));

        glDrawElements(GL_TRIANGLES, quadCount * INDICES_PER_QUAD, GL_UNSIGNED_INT, 0);

        glBindVertexArray(0);
    }

    /**
     * 结束当前渲染批次。
     * <p>
     * 刷新剩余数据并停用着色器。
     */
    public void end() {
        flush();
        shader.unuse();
    }

    /**
     * 释放所有 OpenGL 资源。
     */
    @Override
    public void dispose() {
        shader.dispose();
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteVertexArrays(vao);
    }
}
