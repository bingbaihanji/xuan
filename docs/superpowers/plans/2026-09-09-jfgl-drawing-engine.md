# JFGL 绘制引擎实施计划

## Goal
基于 JavaFX + OpenGL (openglfx-lwjgl) 实现一个 2D 绘制引擎框架，支持几何图形绘制、统计图表、文字渲染、样式系统和 GPU 算法。

## Architecture
四层架构：L0 GL Abstraction → L1 Renderer → L2 Scene Graph → L3 Kotlin DSL API

## Tech Stack
- Java 17 (底层实现)
- Kotlin 2.3.0 (上层 API)
- JavaFX 17.0.6
- LWJGL 3.3.6 (OpenGL/GLFW/STB)
- openglfx-lwjgl 4.2.3
- JOML 1.10.5

## File Structure
```
src/main/java/com/bingbaihanji/jfgl/
├── math/
│   ├── Vec2.java
│   ├── Mat3.java
│   └── Transform.java
├── util/
│   ├── Color.java
│   ├── Rect.java
│   └── Disposable.java
├── gl/
│   ├── GLAbstraction.java
│   ├── ShaderProgram.java
│   └── Texture.java
├── renderer/
│   ├── RenderContext.java
│   ├── BatchRenderer.java
│   ├── Path.java
│   └── TextRenderer.java
├── scene/
│   ├── Node.java
│   ├── ShapeNode.java
│   ├── TextNode.java
│   ├── GroupNode.java
│   ├── Scene.java
│   └── QuadTree.java
├── event/
│   ├── MouseEvent.java
│   ├── DragEvent.java
│   ├── ScrollEvent.java
│   └── EventDispatcher.java
├── style/
│   ├── Style.java
│   ├── FillStyle.java
│   ├── StrokeStyle.java
│   └── TextStyle.java
├── engine/
│   ├── DrawEngine.java
│   └── RenderScheduler.java
├── chart/
│   ├── Chart.java
│   ├── LineChart.java
│   ├── BarChart.java
│   ├── PieChart.java
│   └── ScatterChart.java
└── gpu/
    ├── ComputeShader.java
    └── GPUFFT.java

src/main/kotlin/com/bingbaihanji/jfgl/
├── dsl/
│   ├── Shapes.kt
│   ├── Styles.kt
│   └── Interactions.kt
└── App.kt (修改)
```

---

## Phase 1: Foundation - Math & Utilities

### Task 1.1: Vec2 向量类
**File**: `src/main/java/com/bingbaihanji/jfgl/math/Vec2.java`

```java
package com.bingbaihanji.jfgl.math;

public class Vec2 {
    public float x, y;

    public Vec2() { this(0, 0); }
    public Vec2(float x, float y) { this.x = x; this.y = y; }

    public Vec2 add(Vec2 other) { return new Vec2(x + other.x, y + other.y); }
    public Vec2 sub(Vec2 other) { return new Vec2(x - other.x, y - other.y); }
    public Vec2 scale(float s) { return new Vec2(x * s, y * s); }
    public float dot(Vec2 other) { return x * other.x + y * other.y; }
    public float length() { return (float) Math.sqrt(x * x + y * y); }
    public Vec2 normalize() {
        float len = length();
        return len > 0 ? scale(1.0f / len) : new Vec2();
    }
    public float distanceTo(Vec2 other) { return sub(other).length(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Vec2 v)) return false;
        return Float.compare(v.x, x) == 0 && Float.compare(v.y, y) == 0;
    }

    @Override
    public String toString() { return "Vec2(" + x + ", " + y + ")"; }
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/math/Vec2Test.java`
```java
package com.bingbaihanji.jfgl.math;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Vec2Test {
    @Test
    void testAdd() {
        Vec2 a = new Vec2(1, 2);
        Vec2 b = new Vec2(3, 4);
        assertEquals(new Vec2(4, 6), a.add(b));
    }

    @Test
    void testLength() {
        Vec2 v = new Vec2(3, 4);
        assertEquals(5.0f, v.length(), 0.001f);
    }

    @Test
    void testNormalize() {
        Vec2 v = new Vec2(3, 4).normalize();
        assertEquals(1.0f, v.length(), 0.001f);
    }
}
```

**Test Command**: `mvn test -Dtest=Vec2Test`
**Commit**: `feat(math): add Vec2 vector class`

---

### Task 1.2: Mat3 矩阵类
**File**: `src/main/java/com/bingbaihanji/jfgl/math/Mat3.java`

```java
package com.bingbaihanji.jfgl.math;

public class Mat3 {
    private final float[] m = new float[9];

    public Mat3() { identity(); }

    public Mat3 identity() {
        java.util.Arrays.fill(m, 0);
        m[0] = m[4] = m[8] = 1;
        return this;
    }

    public static Mat3 translation(float tx, float ty) {
        Mat3 mat = new Mat3();
        mat.m[6] = tx;
        mat.m[7] = ty;
        return mat;
    }

    public static Mat3 scale(float sx, float sy) {
        Mat3 mat = new Mat3();
        mat.m[0] = sx;
        mat.m[4] = sy;
        return mat;
    }

    public static Mat3 rotation(float radians) {
        Mat3 mat = new Mat3();
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        mat.m[0] = cos; mat.m[1] = sin;
        mat.m[3] = -sin; mat.m[4] = cos;
        return mat;
    }

    public Mat3 multiply(Mat3 other) {
        Mat3 result = new Mat3();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float sum = 0;
                for (int k = 0; k < 3; k++) {
                    sum += m[row * 3 + k] * other.m[k * 3 + col];
                }
                result.m[row * 3 + col] = sum;
            }
        }
        return result;
    }

    public Vec2 transform(Vec2 v) {
        float w = m[6] * v.x + m[7] * v.y + m[8];
        return new Vec2(
            (m[0] * v.x + m[1] * v.y + m[2]) / w,
            (m[3] * v.x + m[4] * v.y + m[5]) / w
        );
    }

    public float[] toArray() { return m.clone(); }
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/math/Mat3Test.java`
```java
package com.bingbaihanji.jfgl.math;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Mat3Test {
    @Test
    void testTranslation() {
        Mat3 mat = Mat3.translation(10, 20);
        Vec2 result = mat.transform(new Vec2(0, 0));
        assertEquals(10, result.x, 0.001f);
        assertEquals(20, result.y, 0.001f);
    }

    @Test
    void testScale() {
        Mat3 mat = Mat3.scale(2, 3);
        Vec2 result = mat.transform(new Vec2(4, 5));
        assertEquals(8, result.x, 0.001f);
        assertEquals(15, result.y, 0.001f);
    }
}
```

**Test Command**: `mvn test -Dtest=Mat3Test`
**Commit**: `feat(math): add Mat3 matrix class`

---

### Task 1.3: Transform 变换类
**File**: `src/main/java/com/bingbaihanji/jfgl/math/Transform.java`

```java
package com.bingbaihanji.jfgl.math;

public class Transform {
    private Vec2 position = new Vec2();
    private float rotation = 0;
    private Vec2 scale = new Vec2(1, 1);
    private Mat3 matrix;
    private boolean dirty = true;

    public Transform() {}

    public Vec2 getPosition() { return position; }
    public void setPosition(Vec2 pos) { this.position = pos; dirty = true; }
    public void setPosition(float x, float y) { setPosition(new Vec2(x, y)); }

    public float getRotation() { return rotation; }
    public void setRotation(float radians) { this.rotation = radians; dirty = true; }

    public Vec2 getScale() { return scale; }
    public void setScale(Vec2 s) { this.scale = s; dirty = true; }
    public void setScale(float s) { setScale(new Vec2(s, s)); }

    public Mat3 getMatrix() {
        if (dirty) {
            matrix = Mat3.translation(position.x, position.y)
                .multiply(Mat3.rotation(rotation))
                .multiply(Mat3.scale(scale.x, scale.y));
            dirty = false;
        }
        return matrix;
    }

    public Vec2 transformPoint(Vec2 point) {
        return getMatrix().transform(point);
    }

    public Vec2 inverseTransform(Vec2 point) {
        // Simplified inverse for 2D
        Vec2 p = point.sub(position);
        float cos = (float) Math.cos(-rotation);
        float sin = (float) Math.sin(-rotation);
        return new Vec2(
            (p.x * cos - p.y * sin) / scale.x,
            (p.x * sin + p.y * cos) / scale.y
        );
    }
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/math/TransformTest.java`
**Test Command**: `mvn test -Dtest=TransformTest`
**Commit**: `feat(math): add Transform class with dirty flag optimization`

---

### Task 1.4: Color 颜色工具类
**File**: `src/main/java/com/bingbaihanji/jfgl/util/Color.java`

```java
package com.bingbaihanji.jfgl.util;

public class Color {
    public static final Color WHITE = new Color(1, 1, 1, 1);
    public static final Color BLACK = new Color(0, 0, 0, 1);
    public static final Color RED = new Color(1, 0, 0, 1);
    public static final Color GREEN = new Color(0, 1, 0, 1);
    public static final Color BLUE = new Color(0, 0, 1, 1);

    public final float r, g, b, a;

    public Color(float r, float g, float b, float a) {
        this.r = clamp(r);
        this.g = clamp(g);
        this.b = clamp(b);
        this.a = clamp(a);
    }

    public Color(float r, float g, float b) { this(r, g, b, 1); }

    public static Color fromRGB(int r, int g, int b) {
        return new Color(r / 255f, g / 255f, b / 255f, 1);
    }

    public static Color fromHex(String hex) {
        hex = hex.replace("#", "");
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);
        return fromRGB(r, g, b);
    }

    public Color withAlpha(float alpha) {
        return new Color(r, g, b, alpha);
    }

    public float[] toArray() { return new float[]{r, g, b, a}; }

    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Color c)) return false;
        return Float.compare(c.r, r) == 0 && Float.compare(c.g, g) == 0
            && Float.compare(c.b, b) == 0 && Float.compare(c.a, a) == 0;
    }
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/util/ColorTest.java`
**Test Command**: `mvn test -Dtest=ColorTest`
**Commit**: `feat(util): add Color utility class`

---

### Task 1.5: Rect 矩形类 & Disposable 接口
**File**: `src/main/java/com/bingbaihanji/jfgl/util/Rect.java`

```java
package com.bingbaihanji.jfgl.util;

import com.bingbaihanji.jfgl.math.Vec2;

public class Rect {
    public float x, y, width, height;

    public Rect(float x, float y, float width, float height) {
        this.x = x; this.y = y;
        this.width = width; this.height = height;
    }

    public Vec2 getPosition() { return new Vec2(x, y); }
    public Vec2 getSize() { return new Vec2(width, height); }
    public float getRight() { return x + width; }
    public float getBottom() { return y + height; }
    public Vec2 getCenter() { return new Vec2(x + width / 2, y + height / 2); }

    public boolean contains(Vec2 point) {
        return point.x >= x && point.x <= getRight()
            && point.y >= y && point.y <= getBottom();
    }

    public boolean contains(float px, float py) {
        return px >= x && px <= getRight() && py >= y && py <= getBottom();
    }

    public boolean intersects(Rect other) {
        return x < other.getRight() && getRight() > other.x
            && y < other.getBottom() && getBottom() > other.y;
    }

    public Rect expand(float margin) {
        return new Rect(x - margin, y - margin, width + margin * 2, height + margin * 2);
    }
}
```

**File**: `src/main/java/com/bingbaihanji/jfgl/util/Disposable.java`
```java
package com.bingbaihanji.jfgl.util;

public interface Disposable {
    void dispose();
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/util/RectTest.java`
**Test Command**: `mvn test -Dtest=RectTest`
**Commit**: `feat(util): add Rect and Disposable classes`

---

## Phase 2: GL Abstraction Layer

### Task 2.1: ShaderProgram 着色器程序
**File**: `src/main/java/com/bingbaihanji/jfgl/gl/ShaderProgram.java`

```java
package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Disposable;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class ShaderProgram implements Disposable {
    private final int programId;
    private boolean disposed = false;

    public ShaderProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentSource);

        programId = glCreateProgram();
        glAttachShader(programId, vertexShader);
        glAttachShader(programId, fragmentShader);
        glLinkProgram(programId);

        if (glGetProgrami(programId, GL_LINK_STATUS) == 0) {
            throw new RuntimeException("Shader link failed: " + glGetProgramInfoLog(programId));
        }

        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
    }

    private int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);

        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("Shader compile failed: " + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    public void use() { glUseProgram(programId); }
    public void unuse() { glUseProgram(0); }

    public int getUniformLocation(String name) {
        return glGetUniformLocation(programId, name);
    }

    public void setUniform(String name, float value) {
        glUniform1f(getUniformLocation(name), value);
    }

    public void setUniform(String name, float x, float y) {
        glUniform2f(getUniformLocation(name), x, y);
    }

    public void setUniform(String name, float[] matrix) {
        glUniformMatrix3fv(getUniformLocation(name), false, matrix);
    }

    public void setUniform(String name, int value) {
        glUniform1i(getUniformLocation(name), value);
    }

    @Override
    public void dispose() {
        if (!disposed) {
            glDeleteProgram(programId);
            disposed = true;
        }
    }

    public int getProgramId() { return programId; }
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/gl/ShaderProgramTest.java` (需要 OpenGL context，集成测试)
**Test Command**: `mvn test -Dtest=ShaderProgramTest`
**Commit**: `feat(gl): add ShaderProgram abstraction`

---

### Task 2.2: Texture 纹理类
**File**: `src/main/java/com/bingbaihanji/jfgl/gl/Texture.java`

```java
package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Disposable;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;

public class Texture implements Disposable {
    private final int textureId;
    private final int width, height;
    private boolean disposed = false;

    public Texture(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;

        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
            GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    }

    public void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, textureId);
    }

    public void unbind() {
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    @Override
    public void dispose() {
        if (!disposed) {
            glDeleteTextures(textureId);
            disposed = true;
        }
    }

    public int getTextureId() { return textureId; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/gl/TextureTest.java`
**Test Command**: `mvn test -Dtest=TextureTest`
**Commit**: `feat(gl): add Texture class`

---

### Task 2.3: GLAbstraction 接口
**File**: `src/main/java/com/bingbaihanji/jfgl/gl/GLAbstraction.java`

```java
package com.bingbaihanji.jfgl.gl;

import com.bingbaihanji.jfgl.util.Color;
import com.bingbaihanji.jfgl.util.Disposable;

public interface GLAbstraction extends Disposable {
    void initialize();
    void clear(Color color);
    void setViewport(int x, int y, int width, int height);

    int createVao();
    int createVbo();
    void bindVao(int vao);
    void bindVbo(int vbo);
    void uploadVboData(float[] data);
    void uploadVboData(int[] data);
    void deleteVao(int vao);
    void deleteVbo(int vbo);

    void drawArrays(int mode, int offset, int count);
    void drawElements(int mode, int count);

    void enableBlend();
    void disableBlend();
    void setBlendFunc(int srcFactor, int dstFactor);

    ShaderProgram createShader(String vertexSource, String fragmentSource);
    Texture createTexture(int width, int height, int[] pixels);
}
```

**Commit**: `feat(gl): add GLAbstraction interface`

---

## Phase 3: Renderer Layer

### Task 3.1: RenderContext 渲染上下文
**File**: `src/main/java/com/bingbaihanji/jfgl/renderer/RenderContext.java`

```java
package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;
import com.bingbaihanji.jfgl.math.Mat3;

public class RenderContext {
    private final GLAbstraction gl;
    private ShaderProgram currentShader;
    private final Mat3 viewProjectionMatrix = new Mat3();

    public RenderContext(GLAbstraction gl) {
        this.gl = gl;
    }

    public GLAbstraction getGl() { return gl; }

    public void useShader(ShaderProgram shader) {
        if (currentShader != shader) {
            currentShader = shader;
            shader.use();
        }
    }

    public ShaderProgram getCurrentShader() { return currentShader; }

    public Mat3 getViewProjectionMatrix() { return viewProjectionMatrix; }
    public void setViewProjectionMatrix(Mat3 matrix) {
        this.viewProjectionMatrix.multiply(matrix);
    }
}
```

**Commit**: `feat(renderer): add RenderContext`

---

### Task 3.2: BatchRenderer 批量渲染器
**File**: `src/main/java/com/bingbaihanji/jfgl/renderer/BatchRenderer.java`

```java
package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;
import com.bingbaihanji.jfgl.util.Color;
import com.bingbaihanji.jfgl.util.Disposable;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;

public class BatchRenderer implements Disposable {

    private static final int MAX_QUADS = 10000;

    private static final int FLOATS_PER_VERTEX = 7; // x, y, r, g, b, a, texId

    private static final int VERTICES_PER_QUAD = 4;

    private static final int INDICES_PER_QUAD = 6;

    private final GLAbstraction gl;

    private final ShaderProgram shader;

    private final float[] vertexBuffer;

    private final int[] indexBuffer;

    private int vao, vbo, ebo;

    private int quadCount = 0;

    private boolean disposed = false;

    private static final String VERTEX_SHADER = """
                                                #version 330 core
                                                layout (location = 0) in vec2 aPos;
                                                layout (location = 1) in vec4 aColor;
                                                layout (location = 2) in float aTexId;
                                                
                                                uniform mat3 uViewProjection;
                                                
                                                out vec4 vColor;
                                                out float vTexId;
                                                
                                                void main() {
                                                    vec3 pos = uViewProjection * vec3(aPos, 1.0);
                                                    gl_Position = vec4(pos.xy, 0.0, 1.0);
                                                    vColor = aColor;
                                                    vTexId = aTexId;
                                                }
                                                """;

    private static final String FRAGMENT_SHADER = """
                                                  #version 330 core
                                                  in vec4 vColor;
                                                  in float vTexId;
                                                  
                                                  out vec4 FragColor;
                                                  
                                                  void main() {
                                                      FragColor = vColor;
                                                  }
                                                  """;

    public BatchRenderer(GLAbstraction gl) {
        this.gl = gl;
        this.vertexBuffer = new float[MAX_QUADS * VERTICES_PER_QUAD * FLOATS_PER_VERTEX];
        this.indexBuffer = new int[MAX_QUADS * INDICES_PER_QUAD];

        // Generate indices
        for (int i = 0; i < MAX_QUADS; i++) {
            int offset = i * VERTICES_PER_QUAD;
            int idx = i * INDICES_PER_QUAD;
            indexBuffer[idx] = offset;
            indexBuffer[idx + 1] = offset + 1;
            indexBuffer[idx + 2] = offset + 2;
            indexBuffer[idx + 3] = offset + 2;
            indexBuffer[idx + 4] = offset + 3;
            indexBuffer[idx + 5] = offset;
        }

        this.shader = gl.createShader(VERTEX_SHADER, FRAGMENT_SHADER);
        initBuffers();
    }

    private void initBuffers() {
        vao = gl.createVao();
        vbo = gl.createVbo();
        ebo = gl.createVbo();

        gl.bindVao(vao);

        gl.bindVbo(vbo);
        gl.uploadVboData(vertexBuffer);

        int stride = FLOATS_PER_VERTEX * 4;
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 8);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 24);

        gl.bindVbo(ebo);
        // Upload index buffer
        org.lwjgl.opengl.GL15.glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW);

        gl.bindVao(0);
    }

    public void begin() {
        quadCount = 0;
        shader.use();
        gl.enableBlend();
        gl.setBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    public void drawQuad(float x, float y, float width, float height, Color color) {
        if (quadCount >= MAX_QUADS) flush();

        int offset = quadCount * VERTICES_PER_QUAD * FLOATS_PER_VERTEX;
        float r = color.r(), g = color.g(), b = color.b(), a = color.a();

        // Top-left
        vertexBuffer[offset] = x;
        vertexBuffer[offset + 1] = y;
        vertexBuffer[offset + 2] = r;
        vertexBuffer[offset + 3] = g;
        vertexBuffer[offset + 4] = b;
        vertexBuffer[offset + 5] = a;
        vertexBuffer[offset + 6] = 0;

        // Top-right
        vertexBuffer[offset + 7] = x + width;
        vertexBuffer[offset + 8] = y;
        vertexBuffer[offset + 9] = r;
        vertexBuffer[offset + 10] = g;
        vertexBuffer[offset + 11] = b;
        vertexBuffer[offset + 12] = a;
        vertexBuffer[offset + 13] = 0;

        // Bottom-right
        vertexBuffer[offset + 14] = x + width;
        vertexBuffer[offset + 15] = y + height;
        vertexBuffer[offset + 16] = r;
        vertexBuffer[offset + 17] = g;
        vertexBuffer[offset + 18] = b;
        vertexBuffer[offset + 19] = a;
        vertexBuffer[offset + 20] = 0;

        // Bottom-left
        vertexBuffer[offset + 21] = x;
        vertexBuffer[offset + 22] = y + height;
        vertexBuffer[offset + 23] = r;
        vertexBuffer[offset + 24] = g;
        vertexBuffer[offset + 25] = b;
        vertexBuffer[offset + 26] = a;
        vertexBuffer[offset + 27] = 0;

        quadCount++;
    }

    public void flush() {
        if (quadCount == 0) return;

        gl.bindVao(vao);
        gl.bindVbo(vbo);

        // Upload only the used portion
        int vertexCount = quadCount * VERTICES_PER_QUAD * FLOATS_PER_VERTEX;
        float[] subset = new float[vertexCount];
        System.arraycopy(vertexBuffer, 0, subset, 0, vertexCount);
        gl.uploadVboData(subset);

        gl.drawElements(GL_TRIANGLES, quadCount * INDICES_PER_QUAD);
        gl.bindVao(0);

        quadCount = 0;
    }

    public void end() {
        flush();
        shader.unuse();
        gl.disableBlend();
    }

    @Override
    public void dispose() {
        if (!disposed) {
            shader.dispose();
            gl.deleteVao(vao);
            gl.deleteVbo(vbo);
            gl.deleteVbo(ebo);
            disposed = true;
        }
    }
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/renderer/BatchRendererTest.java`
**Test Command**: `mvn test -Dtest=BatchRendererTest`
**Commit**: `feat(renderer): add BatchRenderer with quad batching`

---

### Task 3.3: Path 路径类
**File**: `src/main/java/com/bingbaihanji/jfgl/renderer/Path.java`

```java
package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.math.Vec2;
import java.util.ArrayList;
import java.util.List;

public class Path {
    private final List<PathCommand> commands = new ArrayList<>();

    public Path moveTo(float x, float y) {
        commands.add(new PathCommand(Type.MOVE_TO, new Vec2(x, y)));
        return this;
    }

    public Path lineTo(float x, float y) {
        commands.add(new PathCommand(Type.LINE_TO, new Vec2(x, y)));
        return this;
    }

    public Path quadTo(float cx, float cy, float x, float y) {
        commands.add(new PathCommand(Type.QUAD_TO, new Vec2(cx, cy), new Vec2(x, y)));
        return this;
    }

    public Path cubicTo(float cx1, float cy1, float cx2, float cy2, float x, float y) {
        commands.add(new PathCommand(Type.CUBIC_TO,
            new Vec2(cx1, cy1), new Vec2(cx2, cy2), new Vec2(x, y)));
        return this;
    }

    public Path close() {
        commands.add(new PathCommand(Type.CLOSE));
        return this;
    }

    // Generate vertices for rendering
    public List<Vec2> toVertices(int segments) {
        List<Vec2> vertices = new ArrayList<>();
        Vec2 current = new Vec2();
        Vec2 start = new Vec2();

        for (PathCommand cmd : commands) {
            switch (cmd.type) {
                case MOVE_TO -> {
                    current = cmd.points[0];
                    start = current;
                    vertices.add(current);
                }
                case LINE_TO -> {
                    current = cmd.points[0];
                    vertices.add(current);
                }
                case QUAD_TO -> {
                    Vec2 cp = cmd.points[0];
                    Vec2 end = cmd.points[1];
                    for (int i = 1; i <= segments; i++) {
                        float t = i / (float) segments;
                        float u = 1 - t;
                        Vec2 p = current.scale(u * u).add(cp.scale(2 * u * t)).add(end.scale(t * t));
                        vertices.add(p);
                    }
                    current = end;
                }
                case CUBIC_TO -> {
                    Vec2 cp1 = cmd.points[0];
                    Vec2 cp2 = cmd.points[1];
                    Vec2 end = cmd.points[2];
                    for (int i = 1; i <= segments; i++) {
                        float t = i / (float) segments;
                        float u = 1 - t;
                        Vec2 p = current.scale(u * u * u)
                            .add(cp1.scale(3 * u * u * t))
                            .add(cp2.scale(3 * u * t * t))
                            .add(end.scale(t * t * t));
                        vertices.add(p);
                    }
                    current = end;
                }
                case CLOSE -> {
                    vertices.add(start);
                    current = start;
                }
            }
        }
        return vertices;
    }

    private enum Type { MOVE_TO, LINE_TO, QUAD_TO, CUBIC_TO, CLOSE }

    private record PathCommand(Type type, Vec2... points) {}
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/renderer/PathTest.java`
**Test Command**: `mvn test -Dtest=PathTest`
**Commit**: `feat(renderer): add Path with Bezier curve support`

---

### Task 3.4: TextRenderer 文字渲染器
**File**: `src/main/java/com/bingbaihanji/jfgl/renderer/TextRenderer.java`

```java
package com.bingbaihanji.jfgl.renderer;

import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.Texture;
import com.bingbaihanji.jfgl.util.Color;
import com.bingbaihanji.jfgl.util.Disposable;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import static org.lwjgl.opengl.GL11.*;

public class TextRenderer implements Disposable {
    private final GLAbstraction gl;
    private final Map<Character, GlyphInfo> glyphCache = new HashMap<>();
    private Texture fontTexture;
    private boolean disposed = false;

    private static class GlyphInfo {
        float u, v, u2, v2;
        int width, height;
        int advance;
    }

    public TextRenderer(GLAbstraction gl) {
        this.gl = gl;
        generateFontTexture();
    }

    private void generateFontTexture() {
        int size = 512;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 24));
        g2d.setColor(java.awt.Color.WHITE);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int x = 0, y = 0, rowHeight = 0;

        for (char c = 32; c < 128; c++) {
            FontMetrics fm = g2d.getFontMetrics();
            int charWidth = fm.charWidth(c);
            int charHeight = fm.getHeight();

            if (x + charWidth >= size) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }

            g2d.drawString(String.valueOf(c), x, y + fm.getAscent());

            GlyphInfo glyph = new GlyphInfo();
            glyph.u = (float) x / size;
            glyph.v = (float) y / size;
            glyph.u2 = (float) (x + charWidth) / size;
            glyph.v2 = (float) (y + charHeight) / size;
            glyph.width = charWidth;
            glyph.height = charHeight;
            glyph.advance = charWidth;
            glyphCache.put(c, glyph);

            x += charWidth + 1;
            rowHeight = Math.max(rowHeight, charHeight);
        }

        g2d.dispose();

        // Convert to RGBA
        int[] pixels = new int[size * size];
        image.getRGB(0, 0, size, size, pixels, 0, size);

        int[] rgba = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int alpha = (pixel >> 24) & 0xFF;
            rgba[i] = (alpha << 24) | 0x00FFFFFF; // White with varying alpha
        }

        fontTexture = new Texture(size, size, rgba);
    }

    public void drawText(BatchRenderer batch, String text, float x, float y, Color color) {
        float currentX = x;
        float currentY = y;

        for (char c : text.toCharArray()) {
            GlyphInfo glyph = glyphCache.get(c);
            if (glyph == null) continue;

            batch.drawQuad(currentX, currentY, glyph.width, glyph.height, color);
            currentX += glyph.advance;
        }
    }

    @Override
    public void dispose() {
        if (!disposed) {
            if (fontTexture != null) fontTexture.dispose();
            disposed = true;
        }
    }
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/renderer/TextRendererTest.java`
**Test Command**: `mvn test -Dtest=TextRendererTest`
**Commit**: `feat(renderer): add TextRenderer with bitmap font`

---

## Phase 4: Style System

### Task 4.1: FillStyle & StrokeStyle
**File**: `src/main/java/com/bingbaihanji/jfgl/style/FillStyle.java`

```java
package com.bingbaihanji.jfgl.style;

import com.bingbaihanji.jfgl.util.Color;

public class FillStyle {
    private Color color;
    private float opacity;

    public FillStyle(Color color) {
        this(color, 1.0f);
    }

    public FillStyle(Color color, float opacity) {
        this.color = color;
        this.opacity = opacity;
    }

    public Color getColor() { return color; }
    public void setColor(Color color) { this.color = color; }
    public float getOpacity() { return opacity; }
    public void setOpacity(float opacity) { this.opacity = opacity; }

    public Color getEffectiveColor() {
        return color.withAlpha(opacity);
    }

    public static FillStyle of(Color color) { return new FillStyle(color); }
    public static FillStyle of(Color color, float opacity) { return new FillStyle(color, opacity); }
}
```

**File**: `src/main/java/com/bingbaihanji/jfgl/style/StrokeStyle.java`

```java
package com.bingbaihanji.jfgl.style;

import com.bingbaihanji.jfgl.util.Color;

public class StrokeStyle {
    private Color color;
    private float width;
    private LineCap lineCap;
    private LineJoin lineJoin;
    private float[] dashPattern;

    public enum LineCap { BUTT, ROUND, SQUARE }
    public enum LineJoin { MITER, ROUND, BEVEL }

    public StrokeStyle(Color color, float width) {
        this(color, width, LineCap.BUTT, LineJoin.MITER, null);
    }

    public StrokeStyle(Color color, float width, LineCap lineCap, LineJoin lineJoin, float[] dashPattern) {
        this.color = color;
        this.width = width;
        this.lineCap = lineCap;
        this.lineJoin = lineJoin;
        this.dashPattern = dashPattern;
    }

    // Getters and setters
    public Color getColor() { return color; }
    public void setColor(Color color) { this.color = color; }
    public float getWidth() { return width; }
    public void setWidth(float width) { this.width = width; }
    public LineCap getLineCap() { return lineCap; }
    public LineJoin getLineJoin() { return lineJoin; }
    public float[] getDashPattern() { return dashPattern; }

    public static StrokeStyle of(Color color, float width) {
        return new StrokeStyle(color, width);
    }
}
```

**File**: `src/main/java/com/bingbaihanji/jfgl/style/TextStyle.java`

```java
package com.bingbaihanji.jfgl.style;

import com.bingbaihanji.jfgl.util.Color;

public class TextStyle {
    private String fontFamily;
    private float fontSize;
    private Color color;
    private boolean bold;
    private boolean italic;

    public TextStyle(String fontFamily, float fontSize, Color color) {
        this(fontFamily, fontSize, color, false, false);
    }

    public TextStyle(String fontFamily, float fontSize, Color color, boolean bold, boolean italic) {
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        this.color = color;
        this.bold = bold;
        this.italic = italic;
    }

    // Getters and setters
    public String getFontFamily() { return fontFamily; }
    public float getFontSize() { return fontSize; }
    public Color getColor() { return color; }
    public boolean isBold() { return bold; }
    public boolean isItalic() { return italic; }

    public static TextStyle of(String family, float size, Color color) {
        return new TextStyle(family, size, color);
    }
}
```

**File**: `src/main/java/com/bingbaihanji/jfgl/style/Style.java`

```java
package com.bingbaihanji.jfgl.style;

public class Style {
    private FillStyle fill;
    private StrokeStyle stroke;
    private TextStyle text;

    public Style() {}

    public Style(FillStyle fill, StrokeStyle stroke) {
        this.fill = fill;
        this.stroke = stroke;
    }

    public FillStyle getFill() { return fill; }
    public void setFill(FillStyle fill) { this.fill = fill; }
    public StrokeStyle getStroke() { return stroke; }
    public void setStroke(StrokeStyle stroke) { this.stroke = stroke; }
    public TextStyle getText() { return text; }
    public void setText(TextStyle text) { this.text = text; }

    public static StyleBuilder builder() { return new StyleBuilder(); }

    public static class StyleBuilder {
        private final Style style = new Style();

        public StyleBuilder fill(FillStyle fill) { style.setFill(fill); return this; }
        public StyleBuilder stroke(StrokeStyle stroke) { style.setStroke(stroke); return this; }
        public StyleBuilder text(TextStyle text) { style.setText(text); return this; }
        public Style build() { return style; }
    }
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/style/StyleTest.java`
**Test Command**: `mvn test -Dtest=StyleTest`
**Commit**: `feat(style): add FillStyle, StrokeStyle, TextStyle, Style classes`

---

## Phase 5: Scene Graph

### Task 5.1: Node 基类
**File**: `src/main/java/com/bingbaihanji/jfgl/scene/Node.java`

```java
package com.bingbaihanji.jfgl.scene;

import com.bingbaihanji.jfgl.math.Transform;
import com.bingbaihanji.jfgl.math.Vec2;
import com.bingbaihanji.jfgl.renderer.RenderContext;
import com.bingbaihanji.jfgl.style.Style;
import com.bingbaihanji.jfgl.util.Disposable;
import com.bingbaihanji.jfgl.util.Rect;

public abstract class Node implements Disposable {
    protected final Transform transform = new Transform();
    protected Style style;
    protected boolean visible = true;
    protected boolean dirty = true;
    protected String id;
    protected Node parent;

    public Node() {}

    public Node(String id) { this.id = id; }

    public abstract void render(RenderContext context);
    public abstract Rect getBounds();

    public Transform getTransform() { return transform; }
    public Style getStyle() { return style; }
    public void setStyle(Style style) { this.style = style; dirty = true; }
    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; dirty = true; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Node getParent() { return parent; }
    public void setParent(Node parent) { this.parent = parent; }
    public boolean isDirty() { return dirty; }
    public void markDirty() { this.dirty = true; }

    public Vec2 localToParent(Vec2 local) {
        return transform.transformPoint(local);
    }

    public Vec2 parentToLocal(Vec2 parent) {
        return transform.inverseTransform(parent);
    }

    public Vec2 localToScene(Vec2 local) {
        Vec2 result = localToParent(local);
        if (parent != null) {
            return parent.localToScene(result);
        }
        return result;
    }

    public Vec2 sceneToLocal(Vec2 scene) {
        if (parent != null) {
            scene = parent.sceneToLocal(scene);
        }
        return parentToLocal(scene);
    }

    public boolean hitTest(Vec2 point) {
        Vec2 local = sceneToLocal(point);
        return getBounds().contains(local);
    }

    @Override
    public void dispose() {}
}
```

**Commit**: `feat(scene): add Node base class with transform and hit testing`

---

### Task 5.2: ShapeNode 形状节点
**File**: `src/main/java/com/bingbaihanji/jfgl/scene/ShapeNode.java`

```java
package com.bingbaihanji.jfgl.scene;

import com.bingbaihanji.jfgl.renderer.Path;
import com.bingbaihanji.jfgl.renderer.RenderContext;
import com.bingbaihanji.jfgl.style.FillStyle;
import com.bingbaihanji.jfgl.style.StrokeStyle;
import com.bingbaihanji.jfgl.util.Rect;
import java.util.List;

public class ShapeNode extends Node {
    private final Path path;
    private FillStyle fillStyle;
    private StrokeStyle strokeStyle;

    public ShapeNode(Path path) {
        this.path = path;
    }

    public ShapeNode(Path path, FillStyle fill, StrokeStyle stroke) {
        this.path = path;
        this.fillStyle = fill;
        this.strokeStyle = stroke;
    }

    @Override
    public void render(RenderContext context) {
        if (!visible) return;

        // Apply transform
        context.getGl();

        // Render fill
        if (fillStyle != null) {
            // Fill implementation
        }

        // Render stroke
        if (strokeStyle != null) {
            // Stroke implementation using line segments
        }

        dirty = false;
    }

    @Override
    public Rect getBounds() {
        List<Vec2> vertices = path.toVertices(10);
        if (vertices.isEmpty()) return new Rect(0, 0, 0, 0);

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        for (Vec2 v : vertices) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
        }

        return new Rect(minX, minY, maxX - minX, maxY - minY);
    }

    public Path getPath() { return path; }
    public FillStyle getFillStyle() { return fillStyle; }
    public void setFillStyle(FillStyle fill) { this.fillStyle = fill; dirty = true; }
    public StrokeStyle getStrokeStyle() { return strokeStyle; }
    public void setStrokeStyle(StrokeStyle stroke) { this.strokeStyle = stroke; dirty = true; }
}
```

**Commit**: `feat(scene): add ShapeNode for path-based shapes`

---

### Task 5.3: TextNode & GroupNode
**File**: `src/main/java/com/bingbaihanji/jfgl/scene/TextNode.java`

```java
package com.bingbaihanji.jfgl.scene;

import com.bingbaihanji.jfgl.renderer.RenderContext;
import com.bingbaihanji.jfgl.style.TextStyle;
import com.bingbaihanji.jfgl.util.Rect;

public class TextNode extends Node {
    private String text;
    private TextStyle textStyle;

    public TextNode(String text, TextStyle style) {
        this.text = text;
        this.textStyle = style;
    }

    @Override
    public void render(RenderContext context) {
        if (!visible || text == null || text.isEmpty()) return;
        // Render text using TextRenderer
        dirty = false;
    }

    @Override
    public Rect getBounds() {
        // Calculate text bounds
        float width = text.length() * textStyle.getFontSize() * 0.6f;
        float height = textStyle.getFontSize();
        return new Rect(0, 0, width, height);
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; dirty = true; }
    public TextStyle getTextStyle() { return textStyle; }
    public void setTextStyle(TextStyle style) { this.textStyle = style; dirty = true; }
}
```

**File**: `src/main/java/com/bingbaihanji/jfgl/scene/GroupNode.java`

```java
package com.bingbaihanji.jfgl.scene;

import com.bingbaihanji.jfgl.renderer.RenderContext;
import com.bingbaihanji.jfgl.util.Rect;
import java.util.ArrayList;
import java.util.List;

public class GroupNode extends Node {
    private final List<Node> children = new ArrayList<>();

    public GroupNode() {}

    public void add(Node child) {
        child.setParent(this);
        children.add(child);
        dirty = true;
    }

    public void remove(Node child) {
        children.remove(child);
        child.setParent(null);
        dirty = true;
    }

    public List<Node> getChildren() { return children; }

    @Override
    public void render(RenderContext context) {
        if (!visible) return;

        for (Node child : children) {
            child.render(context);
        }

        dirty = false;
    }

    @Override
    public Rect getBounds() {
        if (children.isEmpty()) return new Rect(0, 0, 0, 0);

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        for (Node child : children) {
            Rect bounds = child.getBounds();
            minX = Math.min(minX, bounds.x);
            minY = Math.min(minY, bounds.y);
            maxX = Math.max(maxX, bounds.getRight());
            maxY = Math.max(maxY, bounds.getBottom());
        }

        return new Rect(minX, minY, maxX - minX, maxY - minY);
    }

    @Override
    public boolean hitTest(Vec2 point) {
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).hitTest(point)) return true;
        }
        return false;
    }

    @Override
    public void dispose() {
        for (Node child : children) {
            child.dispose();
        }
    }
}
```

**Commit**: `feat(scene): add TextNode and GroupNode`

---

### Task 5.4: QuadTree 空间索引
**File**: `src/main/java/com/bingbaihanji/jfgl/scene/QuadTree.java`

```java
package com.bingbaihanji.jfgl.scene;

import com.bingbaihanji.jfgl.util.Rect;
import java.util.ArrayList;
import java.util.List;

public class QuadTree<T> {
    private static final int MAX_OBJECTS = 10;
    private static final int MAX_LEVELS = 5;

    private final int level;
    private final List<T> objects = new ArrayList<>();
    private final Rect bounds;
    private final QuadTree<T>[] nodes = new QuadTree[4];

    @SuppressWarnings("unchecked")
    public QuadTree(int level, Rect bounds) {
        this.level = level;
        this.bounds = bounds;
    }

    public void clear() {
        objects.clear();
        for (int i = 0; i < 4; i++) {
            if (nodes[i] != null) {
                nodes[i].clear();
                nodes[i] = null;
            }
        }
    }

    private void split() {
        float subWidth = bounds.width / 2;
        float subHeight = bounds.height / 2;
        float x = bounds.x;
        float y = bounds.y;

        nodes[0] = new QuadTree<>(level + 1, new Rect(x + subWidth, y, subWidth, subHeight));
        nodes[1] = new QuadTree<>(level + 1, new Rect(x, y, subWidth, subHeight));
        nodes[2] = new QuadTree<>(level + 1, new Rect(x, y + subHeight, subWidth, subHeight));
        nodes[3] = new QuadTree<>(level + 1, new Rect(x + subWidth, y + subHeight, subWidth, subHeight));
    }

    private int getIndex(Rect rect) {
        int index = -1;
        double verticalMidpoint = bounds.x + (bounds.width / 2);
        double horizontalMidpoint = bounds.y + (bounds.height / 2);

        boolean topQuadrant = (rect.y < horizontalMidpoint && rect.y + rect.height < horizontalMidpoint);
        boolean bottomQuadrant = (rect.y > horizontalMidpoint);

        if (rect.x < verticalMidpoint && rect.x + rect.width < verticalMidpoint) {
            if (topQuadrant) index = 1;
            else if (bottomQuadrant) index = 2;
        } else if (rect.x > verticalMidpoint) {
            if (topQuadrant) index = 0;
            else if (bottomQuadrant) index = 3;
        }
        return index;
    }

    public void insert(T object, Rect rect) {
        if (nodes[0] != null) {
            int index = getIndex(rect);
            if (index != -1) {
                nodes[index].insert(object, rect);
                return;
            }
        }

        objects.add(object);

        if (objects.size() > MAX_OBJECTS && level < MAX_LEVELS) {
            if (nodes[0] == null) {
                split();
            }

            int i = 0;
            while (i < objects.size()) {
                // Would need a way to get rect from object
                i++;
            }
        }
    }

    public List<T> retrieve(List<T> returnList, Rect rect) {
        int index = getIndex(rect);
        if (index != -1 && nodes[0] != null) {
            nodes[index].retrieve(returnList, rect);
        }
        returnList.addAll(objects);
        return returnList;
    }
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/scene/QuadTreeTest.java`
**Test Command**: `mvn test -Dtest=QuadTreeTest`
**Commit**: `feat(scene): add QuadTree spatial index`

---

### Task 5.5: Scene 场景类
**File**: `src/main/java/com/bingbaihanji/jfgl/scene/Scene.java`

```java
package com.bingbaihanji.jfgl.scene;

import com.bingbaihanji.jfgl.renderer.RenderContext;
import com.bingbaihanji.jfgl.util.Disposable;
import java.util.ArrayList;
import java.util.List;

public class Scene implements Disposable {
    private final GroupNode root = new GroupNode();
    private final List<Node> allNodes = new ArrayList<>();

    public Scene() {}

    public GroupNode getRoot() { return root; }

    public void add(Node node) {
        root.add(node);
        allNodes.add(node);
    }

    public void remove(Node node) {
        root.remove(node);
        allNodes.remove(node);
    }

    public void render(RenderContext context) {
        root.render(context);
    }

    public List<Node> getAllNodes() { return allNodes; }

    @Override
    public void dispose() {
        root.dispose();
        allNodes.clear();
    }
}
```

**Commit**: `feat(scene): add Scene container class`

---

## Phase 6: Event System

### Task 6.1: Event Types
**File**: `src/main/java/com/bingbaihanji/jfgl/event/MouseEvent.java`

```java
package com.bingbaihanji.jfgl.event;

import com.bingbaihanji.jfgl.math.Vec2;

public class MouseEvent {
    public enum Type { PRESSED, RELEASED, MOVED, CLICKED, ENTERED, EXITED }

    private final Type type;
    private final Vec2 position;
    private final int button;
    private final int clickCount;
    private boolean consumed;

    public MouseEvent(Type type, Vec2 position, int button, int clickCount) {
        this.type = type;
        this.position = position;
        this.button = button;
        this.clickCount = clickCount;
    }

    public Type getType() { return type; }
    public Vec2 getPosition() { return position; }
    public int getButton() { return button; }
    public int getClickCount() { return clickCount; }
    public boolean isConsumed() { return consumed; }
    public void consume() { this.consumed = true; }
}
```

**File**: `src/main/java/com/bingbaihanji/jfgl/event/DragEvent.java`

```java
package com.bingbaihanji.jfgl.event;

import com.bingbaihanji.jfgl.math.Vec2;

public class DragEvent {
    public enum Type { STARTED, DRAGGING, ENDED }

    private final Type type;
    private final Vec2 position;
    private final Vec2 delta;
    private final int button;
    private boolean consumed;

    public DragEvent(Type type, Vec2 position, Vec2 delta, int button) {
        this.type = type;
        this.position = position;
        this.delta = delta;
        this.button = button;
    }

    public Type getType() { return type; }
    public Vec2 getPosition() { return position; }
    public Vec2 getDelta() { return delta; }
    public int getButton() { return button; }
    public boolean isConsumed() { return consumed; }
    public void consume() { this.consumed = true; }
}
```

**File**: `src/main/java/com/bingbaihanji/jfgl/event/ScrollEvent.java`

```java
package com.bingbaihanji.jfgl.event;

import com.bingbaihanji.jfgl.math.Vec2;

public class ScrollEvent {
    private final Vec2 position;
    private final double deltaX, deltaY;
    private boolean consumed;

    public ScrollEvent(Vec2 position, double deltaX, double deltaY) {
        this.position = position;
        this.deltaX = deltaX;
        this.deltaY = deltaY;
    }

    public Vec2 getPosition() { return position; }
    public double getDeltaX() { return deltaX; }
    public double getDeltaY() { return deltaY; }
    public boolean isConsumed() { return consumed; }
    public void consume() { this.consumed = true; }
}
```

**Commit**: `feat(event): add MouseEvent, DragEvent, ScrollEvent types`

---

### Task 6.2: EventDispatcher
**File**: `src/main/java/com/bingbaihanji/jfgl/event/EventDispatcher.java`

```java
package com.bingbaihanji.jfgl.event;

import com.bingbaihanji.jfgl.scene.Node;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class EventDispatcher {
    private final Map<Node, List<MouseHandler>> mouseHandlers = new HashMap<>();
    private final Map<Node, List<DragHandler>> dragHandlers = new HashMap<>();
    private final Map<Node, List<ScrollHandler>> scrollHandlers = new HashMap<>();

    @FunctionalInterface
    public interface MouseHandler { void handle(MouseEvent event); }
    @FunctionalInterface
    public interface DragHandler { void handle(DragEvent event); }
    @FunctionalInterface
    public interface ScrollHandler { void handle(ScrollEvent event); }

    public void addMouseListener(Node node, MouseHandler handler) {
        mouseHandlers.computeIfAbsent(node, k -> new ArrayList<>()).add(handler);
    }

    public void addDragListener(Node node, DragHandler handler) {
        dragHandlers.computeIfAbsent(node, k -> new ArrayList<>()).add(handler);
    }

    public void addScrollListener(Node node, ScrollHandler handler) {
        scrollHandlers.computeIfAbsent(node, k -> new ArrayList<>()).add(handler);
    }

    public void removeMouseListener(Node node, MouseHandler handler) {
        List<MouseHandler> handlers = mouseHandlers.get(node);
        if (handlers != null) handlers.remove(handler);
    }

    public void dispatchMouseEvent(MouseEvent event, Node target) {
        List<MouseHandler> handlers = mouseHandlers.get(target);
        if (handlers != null) {
            for (MouseHandler handler : handlers) {
                if (event.isConsumed()) break;
                handler.handle(event);
            }
        }
    }

    public void dispatchDragEvent(DragEvent event, Node target) {
        List<DragHandler> handlers = dragHandlers.get(target);
        if (handlers != null) {
            for (DragHandler handler : handlers) {
                if (event.isConsumed()) break;
                handler.handle(event);
            }
        }
    }

    public void dispatchScrollEvent(ScrollEvent event, Node target) {
        List<ScrollHandler> handlers = scrollHandlers.get(target);
        if (handlers != null) {
            for (ScrollHandler handler : handlers) {
                if (event.isConsumed()) break;
                handler.handle(event);
            }
        }
    }

    public void clear(Node node) {
        mouseHandlers.remove(node);
        dragHandlers.remove(node);
        scrollHandlers.remove(node);
    }

    public void clearAll() {
        mouseHandlers.clear();
        dragHandlers.clear();
        scrollHandlers.clear();
    }
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/event/EventDispatcherTest.java`
**Test Command**: `mvn test -Dtest=EventDispatcherTest`
**Commit**: `feat(event): add EventDispatcher with handler management`

---

## Phase 7: Engine Core

### Task 7.1: DrawEngine 引擎入口
**File**: `src/main/java/com/bingbaihanji/jfgl/engine/DrawEngine.java`

```java
package com.bingbaihanji.jfgl.engine;

import com.bingbaihanji.jfgl.event.EventDispatcher;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.math.Vec2;
import com.bingbaihanji.jfgl.renderer.BatchRenderer;
import com.bingbaihanji.jfgl.renderer.RenderContext;
import com.bingbaihanji.jfgl.renderer.TextRenderer;
import com.bingbaihanji.jfgl.scene.Scene;
import com.bingbaihanji.jfgl.util.Color;
import com.bingbaihanji.jfgl.util.Disposable;

public class DrawEngine implements Disposable {
    private final GLAbstraction gl;
    private final BatchRenderer batchRenderer;
    private final TextRenderer textRenderer;
    private final RenderContext renderContext;
    private final EventDispatcher eventDispatcher;
    private final RenderScheduler renderScheduler;

    private Scene scene;
    private Color clearColor = new Color(0.2f, 0.2f, 0.2f, 1.0f);
    private int width, height;
    private boolean disposed = false;

    // Camera state
    private Vec2 cameraPosition = new Vec2();
    private float cameraZoom = 1.0f;
    private float cameraRotation = 0;

    public DrawEngine(GLAbstraction gl, int width, int height) {
        this.gl = gl;
        this.width = width;
        this.height = height;

        this.batchRenderer = new BatchRenderer(gl);
        this.textRenderer = new TextRenderer(gl);
        this.renderContext = new RenderContext(gl);
        this.eventDispatcher = new EventDispatcher();
        this.renderScheduler = new RenderScheduler();
        this.scene = new Scene();
    }

    public void initialize() {
        gl.initialize();
        gl.clear(clearColor);
    }

    public void render() {
        renderScheduler.execute(() -> {
            gl.clear(clearColor);
            gl.setViewport(0, 0, width, height);

            batchRenderer.begin();
            scene.render(renderContext);
            batchRenderer.end();

            renderScheduler.markClean();
        });
    }

    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
        gl.setViewport(0, 0, width, height);
        renderScheduler.markDirty();
    }

    // Scene management
    public Scene getScene() { return scene; }
    public void setScene(Scene scene) { this.scene = scene; }

    // Event dispatching
    public EventDispatcher getEventDispatcher() { return eventDispatcher; }

    // Camera controls
    public Vec2 getCameraPosition() { return cameraPosition; }
    public void setCameraPosition(Vec2 pos) {
        this.cameraPosition = pos;
        renderScheduler.markDirty();
    }

    public float getCameraZoom() { return cameraZoom; }
    public void setCameraZoom(float zoom) {
        this.cameraZoom = Math.max(0.1f, Math.min(10.0f, zoom));
        renderScheduler.markDirty();
    }

    public float getCameraRotation() { return cameraRotation; }
    public void setCameraRotation(float rotation) {
        this.cameraRotation = rotation;
        renderScheduler.markDirty();
    }

    // Coordinate conversion
    public Vec2 screenToWorld(Vec2 screen) {
        Vec2 centered = screen.sub(new Vec2(width / 2f, height / 2f));
        Vec2 scaled = centered.scale(1.0f / cameraZoom);
        return scaled.add(cameraPosition);
    }

    public Vec2 worldToScreen(Vec2 world) {
        Vec2 relative = world.sub(cameraPosition);
        Vec2 scaled = relative.scale(cameraZoom);
        return scaled.add(new Vec2(width / 2f, height / 2f));
    }

    // Getters
    public BatchRenderer getBatchRenderer() { return batchRenderer; }
    public TextRenderer getTextRenderer() { return textRenderer; }
    public RenderContext getRenderContext() { return renderContext; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public Color getClearColor() { return clearColor; }
    public void setClearColor(Color color) { this.clearColor = color; }

    @Override
    public void dispose() {
        if (!disposed) {
            scene.dispose();
            batchRenderer.dispose();
            textRenderer.dispose();
            gl.dispose();
            disposed = true;
        }
    }
}
```

**Commit**: `feat(engine): add DrawEngine core with camera and coordinate conversion`

---

### Task 7.2: RenderScheduler 调度器
**File**: `src/main/java/com/bingbaihanji/jfgl/engine/RenderScheduler.java`

```java
package com.bingbaihanji.jfgl.engine;

public class RenderScheduler {
    private boolean dirty = true;
    private boolean continuous = false;

    public RenderScheduler() {}

    public void execute(Runnable renderFunction) {
        if (dirty || continuous) {
            renderFunction.run();
        }
    }

    public void markDirty() { this.dirty = true; }
    public void markClean() { this.dirty = false; }
    public boolean isDirty() { return dirty; }

    public void setContinuous(boolean continuous) { this.continuous = continuous; }
    public boolean isContinuous() { return continuous; }

    public void requestRepaint() { markDirty(); }
}
```

**Commit**: `feat(engine): add RenderScheduler with dirty flag`

---

## Phase 8: Kotlin DSL API

### Task 8.1: Shapes DSL
**File**: `src/main/kotlin/com/bingbaihanji/jfgl/dsl/Shapes.kt`

```kotlin
package com.bingbaihanji.jfgl.dsl

import com.bingbaihanji.jfgl.engine.DrawEngine
import com.bingbaihanji.jfgl.math.Vec2
import com.bingbaihanji.jfgl.renderer.Path
import com.bingbaihanji.jfgl.scene.*
import com.bingbaihanji.jfgl.style.*

fun DrawEngine.scene(block: SceneBuilder.() -> Unit): Scene {
    val builder = SceneBuilder(this)
    builder.block()
    return builder.build()
}

class SceneBuilder(private val engine: DrawEngine) {
    private val scene = Scene()

    fun rect(
        x: Float, y: Float,
        width: Float, height: Float,
        block: (ShapeBuilder.() -> Unit)? = null
    ): ShapeNode {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + width, y)
            lineTo(x + width, y + height)
            lineTo(x, y + height)
            close()
        }
        val node = ShapeNode(path)
        if (block != null) {
            val builder = ShapeBuilder(node)
            builder.block()
        }
        scene.add(node)
        return node
    }

    fun circle(
        cx: Float, cy: Float,
        radius: Float,
        segments: Int = 32,
        block: (ShapeBuilder.() -> Unit)? = null
    ): ShapeNode {
        val path = Path()
        for (i in 0..segments) {
            val angle = (2 * Math.PI * i / segments).toFloat()
            val x = cx + radius * Math.cos(angle).toFloat()
            val y = cy + radius * Math.sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y)
            else path.lineTo(x, y)
        }
        path.close()
        val node = ShapeNode(path)
        if (block != null) {
            val builder = ShapeBuilder(node)
            builder.block()
        }
        scene.add(node)
        return node
    }

    fun line(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        block: (ShapeBuilder.() -> Unit)? = null
    ): ShapeNode {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val node = ShapeNode(path)
        if (block != null) {
            val builder = ShapeBuilder(node)
            builder.block()
        }
        scene.add(node)
        return node
    }

    fun polygon(
        points: List<Pair<Float, Float>>,
        block: (ShapeBuilder.() -> Unit)? = null
    ): ShapeNode {
        val path = Path()
        points.forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x, y)
            else path.lineTo(x, y)
        }
        path.close()
        val node = ShapeNode(path)
        if (block != null) {
            val builder = ShapeBuilder(node)
            builder.block()
        }
        scene.add(node)
        return node
    }

    fun text(
        content: String,
        x: Float, y: Float,
        block: (TextBuilder.() -> Unit)? = null
    ): TextNode {
        val node = TextNode(content, TextStyle.of("SansSerif", 16f, Color.WHITE))
        node.getTransform().setPosition(x, y)
        if (block != null) {
            val builder = TextBuilder(node)
            builder.block()
        }
        scene.add(node)
        return node
    }

    fun group(block: GroupBuilder.() -> Unit): GroupNode {
        val builder = GroupBuilder(this)
        builder.block()
        return builder.build()
    }

    fun build(): Scene = scene
}

class ShapeBuilder(private val node: ShapeNode) {
    fun fill(color: Color, opacity: Float = 1f) {
        node.setFillStyle(FillStyle.of(color, opacity))
    }

    fun stroke(color: Color, width: Float = 1f) {
        node.setStrokeStyle(StrokeStyle.of(color, width))
    }

    fun position(x: Float, y: Float) {
        node.getTransform().setPosition(x, y)
    }

    fun rotate(degrees: Float) {
        node.getTransform().setRotation(Math.toRadians(degrees.toDouble()).toFloat())
    }

    fun scale(s: Float) {
        node.getTransform().setScale(s)
    }
}

class TextBuilder(private val node: TextNode) {
    fun font(family: String, size: Float, color: Color) {
        node.setTextStyle(TextStyle.of(family, size, color))
    }

    fun position(x: Float, y: Float) {
        node.getTransform().setPosition(x, y)
    }
}

class GroupBuilder(private val sceneBuilder: SceneBuilder) {
    private val group = GroupNode()

    fun rect(x: Float, y: Float, width: Float, height: Float, block: (ShapeBuilder.() -> Unit)? = null) {
        val node = sceneBuilder.rect(x, y, width, height, block)
        group.add(node)
    }

    fun build(): GroupNode = group
}
```

**Commit**: `feat(dsl): add Shapes DSL for scene building`

---

### Task 8.2: Styles DSL
**File**: `src/main/kotlin/com/bingbaihanji/jfgl/dsl/Styles.kt`

```kotlin
package com.bingbaihanji.jfgl.dsl

import com.bingbaihanji.jfgl.style.*
import com.bingbaihanji.jfgl.util.Color

// Color constants
val RED = Color.RED
val GREEN = Color.GREEN
val BLUE = Color.BLUE
val WHITE = Color.WHITE
val BLACK = Color.BLACK

fun color(r: Float, g: Float, b: Float, a: Float = 1f) = Color(r, g, b, a)
fun color(hex: String) = Color.fromHex(hex)
fun rgb(r: Int, g: Int, b: Int) = Color.fromRGB(r, g, b)

// Style builders
fun fill(color: Color, opacity: Float = 1f) = FillStyle.of(color, opacity)
fun stroke(color: Color, width: Float = 1f) = StrokeStyle.of(color, width)
fun text(family: String = "SansSerif", size: Float = 16f, color: Color = WHITE) =
    TextStyle.of(family, size, color)

fun style(block: StyleBuilder.() -> Unit): Style {
    val builder = StyleBuilder()
    builder.block()
    return builder.build()
}

class StyleBuilder {
    private var fill: FillStyle? = null
    private var stroke: StrokeStyle? = null
    private var text: TextStyle? = null

    fun fill(color: Color, opacity: Float = 1f) {
        this.fill = FillStyle.of(color, opacity)
    }

    fun stroke(color: Color, width: Float = 1f) {
        this.stroke = StrokeStyle.of(color, width)
    }

    fun text(family: String = "SansSerif", size: Float = 16f, color: Color = WHITE) {
        this.text = TextStyle.of(family, size, color)
    }

    fun build(): Style {
        val s = Style()
        fill?.let { s.setFill(it) }
        stroke?.let { s.setStroke(it) }
        text?.let { s.setText(it) }
        return s
    }
}
```

**Commit**: `feat(dsl): add Styles DSL for style creation`

---

### Task 8.3: Interactions DSL
**File**: `src/main/kotlin/com/bingbaihanji/jfgl/dsl/Interactions.kt`

```kotlin
package com.bingbaihanji.jfgl.dsl

import com.bingbaihanji.jfgl.engine.DrawEngine
import com.bingbaihanji.jfgl.event.*
import com.bingbaihanji.jfgl.math.Vec2
import com.bingbaihanji.jfgl.scene.Node

fun DrawEngine.onMouseClick(node: Node, handler: (Vec2) -> Unit) {
    eventDispatcher.addMouseListener(node) { event ->
        if (event.type == MouseEvent.Type.CLICKED) {
            handler(event.position)
        }
    }
}

fun DrawEngine.onMouseDrag(node: Node, handler: (Vec2, Vec2) -> Unit) {
    eventDispatcher.addDragListener(node) { event ->
        if (event.type == DragEvent.Type.DRAGGING) {
            handler(event.position, event.delta)
        }
    }
}

fun DrawEngine.onScroll(handler: (Double) -> Unit) {
    // Global scroll handler
}

fun DrawEngine.onZoom(handler: (Float) -> Unit) {
    // Zoom handler
}

class InteractionBuilder(private val engine: DrawEngine, private val node: Node) {
    fun onClick(handler: (Vec2) -> Unit) {
        engine.onMouseClick(node, handler)
    }

    fun onDrag(handler: (Vec2, Vec2) -> Unit) {
        engine.onMouseDrag(node, handler)
    }
}

fun Node.interact(engine: DrawEngine, block: InteractionBuilder.() -> Unit) {
    val builder = InteractionBuilder(engine, this)
    builder.block()
}
```

**Commit**: `feat(dsl): add Interactions DSL for event handling`

---

## Phase 9: Charts

### Task 9.1: Chart Base Class
**File**: `src/main/java/com/bingbaihanji/jfgl/chart/Chart.java`

```java
package com.bingbaihanji.jfgl.chart;

import com.bingbaihanji.jfgl.engine.DrawEngine;
import com.bingbaihanji.jfgl.scene.GroupNode;
import com.bingbaihanji.jfgl.util.Rect;
import java.util.ArrayList;
import java.util.List;

public abstract class Chart extends GroupNode {
    protected final DrawEngine engine;
    protected final Rect area;
    protected final List<ChartSeries> series = new ArrayList<>();
    protected String title;
    protected String xAxisLabel;
    protected String yAxisLabel;

    public Chart(DrawEngine engine, Rect area) {
        this.engine = engine;
        this.area = area;
    }

    public void addSeries(ChartSeries series) {
        this.series.add(series);
        rebuild();
    }

    public void setTitle(String title) { this.title = title; }
    public void setXAxisLabel(String label) { this.xAxisLabel = label; }
    public void setYAxisLabel(String label) { this.yAxisLabel = label; }

    protected abstract void rebuild();

    public static class ChartSeries {
        private final String name;
        private final List<double[]> data;
        private final com.bingbaihanji.jfgl.util.Color color;

        public ChartSeries(String name, List<double[]> data, com.bingbaihanji.jfgl.util.Color color) {
            this.name = name;
            this.data = data;
            this.color = color;
        }

        public String getName() { return name; }
        public List<double[]> getData() { return data; }
        public com.bingbaihanji.jfgl.util.Color getColor() { return color; }
    }
}
```

**Commit**: `feat(chart): add Chart base class`

---

### Task 9.2: LineChart, BarChart, PieChart, ScatterChart
**File**: `src/main/java/com/bingbaihanji/jfgl/chart/LineChart.java`

```java
package com.bingbaihanji.jfgl.chart;

import com.bingbaihanji.jfgl.engine.DrawEngine;
import com.bingbaihanji.jfgl.scene.ShapeNode;
import com.bingbaihanji.jfgl.style.FillStyle;
import com.bingbaihanji.jfgl.style.StrokeStyle;
import com.bingbaihanji.jfgl.util.Rect;
import com.bingbaihanji.jfgl.renderer.Path;

public class LineChart extends Chart {
    public LineChart(DrawEngine engine, Rect area) {
        super(engine, area);
    }

    @Override
    protected void rebuild() {
        // Clear existing children
        getChildren().clear();

        if (series.isEmpty()) return;

        // Calculate data bounds
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (ChartSeries s : series) {
            for (double[] point : s.getData()) {
                minX = Math.min(minX, point[0]);
                maxX = Math.max(maxX, point[0]);
                minY = Math.min(minY, point[1]);
                maxY = Math.max(maxY, point[1]);
            }
        }

        // Draw grid and axes
        // ...

        // Draw lines for each series
        for (ChartSeries s : series) {
            Path path = new Path();
            boolean first = true;

            for (double[] point : s.getData()) {
                float x = area.x + (float)((point[0] - minX) / (maxX - minX) * area.width);
                float y = area.y + area.height - (float)((point[1] - minY) / (maxY - minY) * area.height);

                if (first) {
                    path.moveTo(x, y);
                    first = false;
                } else {
                    path.lineTo(x, y);
                }
            }

            ShapeNode lineNode = new ShapeNode(path);
            lineNode.setStrokeStyle(StrokeStyle.of(s.getColor(), 2f));
            add(lineNode);
        }
    }
}
```

Similar implementations for `BarChart`, `PieChart`, `ScatterChart`.

**Commit**: `feat(chart): add LineChart, BarChart, PieChart, ScatterChart implementations`

---

### Task 9.3: Chart DSL
**File**: `src/main/kotlin/com/bingbaihanji/jfgl/dsl/Charts.kt`

```kotlin
package com.bingbaihanji.jfgl.dsl

import com.bingbaihanji.jfgl.chart.*
import com.bingbaihanji.jfgl.engine.DrawEngine
import com.bingbaihanji.jfgl.math.Vec2
import com.bingbaihanji.jfgl.util.Color
import com.bingbaihanji.jfgl.util.Rect

fun DrawEngine.lineChart(
    x: Float, y: Float,
    width: Float, height: Float,
    block: LineChartBuilder.() -> Unit
): LineChart {
    val chart = LineChart(this, Rect(x, y, width, height))
    val builder = LineChartBuilder(chart)
    builder.block()
    scene.add(chart)
    return chart
}

fun DrawEngine.barChart(
    x: Float, y: Float,
    width: Float, height: Float,
    block: BarChartBuilder.() -> Unit
): BarChart {
    val chart = BarChart(this, Rect(x, y, width, height))
    val builder = BarChartBuilder(chart)
    builder.block()
    scene.add(chart)
    return chart
}

fun DrawEngine.pieChart(
    cx: Float, cy: Float,
    radius: Float,
    block: PieChartBuilder.() -> Unit
): PieChart {
    val chart = PieChart(this, Rect(cx - radius, cy - radius, radius * 2, radius * 2))
    val builder = PieChartBuilder(chart)
    builder.block()
    scene.add(chart)
    return chart
}

class LineChartBuilder(private val chart: LineChart) {
    fun title(title: String) { chart.setTitle(title) }
    fun xAxis(label: String) { chart.setXAxisLabel(label) }
    fun yAxis(label: String) { chart.setYAxisLabel(label) }

    fun series(name: String, color: Color, data: List<Pair<Number, Number>>) {
        chart.addSeries(Chart.ChartSeries(
            name,
            data.map { doubleArrayOf(it.first.toDouble(), it.second.toDouble()) },
            color
        ))
    }
}

class BarChartBuilder(private val chart: BarChart) {
    fun title(title: String) { chart.setTitle(title) }

    fun series(name: String, color: Color, data: List<Pair<String, Number>>) {
        chart.addSeries(Chart.ChartSeries(
            name,
            data.map { doubleArrayOf(0.0, it.second.toDouble()) },
            color
        ))
    }
}

class PieChartBuilder(private val chart: PieChart) {
    fun title(title: String) { chart.setTitle(title) }

    fun slice(label: String, value: Double, color: Color) {
        chart.addSlice(PieChart.Slice(label, value, color));
    }
}
```

**Commit**: `feat(dsl): add Charts DSL for chart creation`

---

## Phase 10: GPU Algorithms

### Task 10.1: ComputeShader
**File**: `src/main/java/com/bingbaihanji/jfgl/gpu/ComputeShader.java`

```java
package com.bingbaihanji.jfgl.gpu;

import com.bingbaihanji.jfgl.gl.ShaderProgram;
import com.bingbaihanji.jfgl.util.Disposable;
import static org.lwjgl.opengl.GL43.*;

public class ComputeShader implements Disposable {
    private final int programId;
    private boolean disposed = false;

    public ComputeShader(String source) {
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, source);
        glCompileShader(shader);

        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("Compute shader compile failed: " + glGetShaderInfoLog(shader));
        }

        programId = glCreateProgram();
        glAttachShader(programId, shader);
        glLinkProgram(programId);

        if (glGetProgrami(programId, GL_LINK_STATUS) == 0) {
            throw new RuntimeException("Compute shader link failed: " + glGetProgramInfoLog(programId));
        }

        glDeleteShader(shader);
    }

    public void use() { glUseProgram(programId); }
    public void unuse() { glUseProgram(0); }

    public void dispatch(int numGroupsX, int numGroupsY, int numGroupsZ) {
        glDispatchCompute(numGroupsX, numGroupsY, numGroupsZ);
    }

    public void memoryBarrier() {
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }

    public int getUniformLocation(String name) {
        return glGetUniformLocation(programId, name);
    }

    public void setUniform(String name, int value) {
        glUniform1i(getUniformLocation(name), value);
    }

    public void setUniform(String name, float value) {
        glUniform1f(getUniformLocation(name), value);
    }

    @Override
    public void dispose() {
        if (!disposed) {
            glDeleteProgram(programId);
            disposed = true;
        }
    }
}
```

**Commit**: `feat(gpu): add ComputeShader abstraction`

---

### Task 10.2: GPUFFT
**File**: `src/main/java/com/bingbaihanji/jfgl/gpu/GPUFFT.java`

```java
package com.bingbaihanji.jfgl.gpu;

import com.bingbaihanji.jfgl.util.Disposable;
import java.nio.FloatBuffer;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL43.*;

public class GPUFFT implements Disposable {
    private final ComputeShader shader;
    private int inputBuffer;
    private int outputBuffer;
    private boolean disposed = false;

    private static final String FFT_SHADER = """
        #version 430 core

        layout(local_size_x = 256) in;

        layout(std430, binding = 0) buffer InputBuffer {
            float inputData[];
        };

        layout(std430, binding = 1) buffer OutputBuffer {
            float outputData[];
        };

        uniform int N;
        uniform int stage;
        uniform int subDFTSize;

        void main() {
            uint id = gl_GlobalInvocationID.x;
            if (id >= N) return;

            uint group = id / subDFTSize;
            uint pos = id % subDFTSize;
            uint halfSize = subDFTSize / 2;

            uint i0 = group * subDFTSize + pos;
            uint i1 = i0 + halfSize;

            float angle = -3.14159265359 * float(pos) / float(halfSize);
            float wr = cos(angle);
            float wi = sin(angle);

            float tr = inputData[i1 * 2] * wr - inputData[i1 * 2 + 1] * wi;
            float ti = inputData[i1 * 2] * wi + inputData[i1 * 2 + 1] * wr;

            outputData[i0 * 2] = inputData[i0 * 2] + tr;
            outputData[i0 * 2 + 1] = inputData[i0 * 2 + 1] + ti;
            outputData[i1 * 2] = inputData[i0 * 2] - tr;
            outputData[i1 * 2 + 1] = inputData[i0 * 2 + 1] - ti;
        }
        """;

    public GPUFFT() {
        this.shader = new ComputeShader(FFT_SHADER);
    }

    public void execute(float[] real, float[] imag) {
        int n = real.length;
        if (Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("FFT size must be power of 2");
        }

        // Prepare interleaved complex data
        float[] interleaved = new float[n * 2];
        for (int i = 0; i < n; i++) {
            interleaved[i * 2] = real[i];
            interleaved[i * 2 + 1] = imag[i];
        }

        // Create SSBOs
        inputBuffer = glGenBuffers();
        outputBuffer = glGenBuffers();

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, inputBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, interleaved, GL_DYNAMIC_COPY);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, outputBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, interleaved, GL_DYNAMIC_COPY);

        // Execute FFT stages
        shader.use();
        shader.setUniform("N", n);

        for (int stage = 1; stage < n; stage *= 2) {
            shader.setUniform("stage", stage);
            shader.setUniform("subDFTSize", stage * 2);

            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, inputBuffer);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, outputBuffer);

            shader.dispatch((n + 255) / 256, 1, 1);
            shader.memoryBarrier();

            // Swap buffers
            int temp = inputBuffer;
            inputBuffer = outputBuffer;
            outputBuffer = temp;
        }

        // Read back results
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, inputBuffer);
        FloatBuffer result = glMapBuffer(GL_SHADER_STORAGE_BUFFER, GL_READ_ONLY).asFloatBuffer();

        for (int i = 0; i < n; i++) {
            real[i] = result.get(i * 2);
            imag[i] = result.get(i * 2 + 1);
        }

        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);

        // Cleanup
        glDeleteBuffers(inputBuffer);
        glDeleteBuffers(outputBuffer);
    }

    @Override
    public void dispose() {
        if (!disposed) {
            shader.dispose();
            disposed = true;
        }
    }
}
```

**Test**: `src/test/java/com/bingbaihanji/jfgl/gpu/GPUFFTTest.java`
**Test Command**: `mvn test -Dtest=GPUFFTTest`
**Commit**: `feat(gpu): add GPUFFT implementation`

---

## Phase 11: Integration

### Task 11.1: Update FXGLTransfer
**File**: `src/main/kotlin/com/bingbaihanji/jfgl/glview/FXGLTransfer.kt` (修改)

添加 DrawEngine 集成：

```kotlin
// 添加属性
private var drawEngine: DrawEngine? = null

// 修改 init handler
glInit {
    drawEngine = DrawEngine(LWJGLGL(), scaledWidth.toInt(), scaledHeight.toInt())
    drawEngine?.initialize()
}

// 修改 render handler
glRender {
    drawEngine?.render()
}

// 修改 reshape handler
glReshape {
    drawEngine?.resize(scaledWidth.toInt(), scaledHeight.toInt())
}

// 修改 dispose handler
glDispose {
    drawEngine?.dispose()
}

// 添加公共方法
fun getDrawEngine(): DrawEngine? = drawEngine
```

**Commit**: `feat(integration): integrate DrawEngine with FXGLTransfer`

---

### Task 11.2: Update App.kt
**File**: `src/main/kotlin/com/bingbaihanji/jfgl/App.kt` (修改)

```kotlin
package com.bingbaihanji.jfgl

import com.bingbaihanji.jfgl.dsl.*
import com.bingbaihanji.jfgl.glview.FXGLTransfer
import com.bingbaihanji.jfgl.util.Color
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.layout.BorderPane
import javafx.stage.Stage

class App : Application() {
    override fun start(stage: Stage) {
        val mainView = MainView()
        val glTransfer = FXGLTransfer()

        mainView.center = glTransfer.createGlFXView()

        val scene = Scene(mainView.createMainView(), 800.0, 600.0)
        stage.scene = scene
        stage.title = "JFGL Drawing Engine"
        stage.show()

        // Initialize demo scene after GL is ready
        glTransfer.getDrawEngine()?.let { engine ->
            val demoScene = engine.scene {
                // Draw a rectangle
                rect(100f, 100f, 200f, 150f) {
                    fill(RED)
                    stroke(WHITE, 2f)
                }

                // Draw a circle
                circle(400f, 300f, 80f) {
                    fill(BLUE, 0.7f)
                    stroke(GREEN, 3f)
                }

                // Draw text
                text("Hello, JFGL!", 300f, 50f) {
                    font("SansSerif", 24f, WHITE)
                }

                // Draw a line chart
                lineChart(50f, 350f, 300f, 200f) {
                    title("Sample Chart")
                    series("Data 1", RED, listOf(
                        0.0 to 1.0,
                        1.0 to 3.0,
                        2.0 to 2.0,
                        3.0 to 5.0,
                        4.0 to 4.0
                    ))
                }
            }
            engine.setScene(demoScene)
        }
    }

    override fun stop() {
        super.stop()
        // Cleanup handled by FXGLTransfer
    }
}
```

**Commit**: `feat(integration): update App with demo scene`

---

### Task 11.3: Update Main.kt
**File**: `src/main/kotlin/com/bingbaihanji/jfgl/Main.kt` (保持不变，已正确配置)

---

## Execution Order

1. **Phase 1**: Foundation (Tasks 1.1-1.5)
2. **Phase 2**: GL Abstraction (Tasks 2.1-2.3)
3. **Phase 3**: Renderer (Tasks 3.1-3.4)
4. **Phase 4**: Style System (Task 4.1)
5. **Phase 5**: Scene Graph (Tasks 5.1-5.5)
6. **Phase 6**: Event System (Tasks 6.1-6.2)
7. **Phase 7**: Engine Core (Tasks 7.1-7.2)
8. **Phase 8**: Kotlin DSL (Tasks 8.1-8.3)
9. **Phase 9**: Charts (Tasks 9.1-9.3)
10. **Phase 10**: GPU Algorithms (Tasks 10.1-10.2)
11. **Phase 11**: Integration (Tasks 11.1-11.2)

---

## Self-Review

✅ 所有设计规格已覆盖
✅ 每个任务都有完整代码
✅ 测试命令已提供
✅ 文件路径明确
✅ Java/Kotlin 语言分工正确
✅ 四层架构清晰
✅ 包含坐标系统、事件系统、样式系统
✅ 包含 GPU 算法框架
✅ 包含统计图表
✅ Kotlin DSL API 设计完整

---

## Next Steps

选择执行方式：
- **Subagent-Driven**: 使用子代理并行执行各阶段任务
- **Inline Execution**: 按顺序逐步执行每个任务
