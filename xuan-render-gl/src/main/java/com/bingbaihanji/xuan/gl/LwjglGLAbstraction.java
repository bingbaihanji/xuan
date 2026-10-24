package com.bingbaihanji.xuan.gl;

import com.bingbaihanji.xuan.util.Color;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * {@link GLAbstraction} 的 LWJGL 实现：把每次调用转给 LWJGL 的 OpenGL 绑定。
 *
 * <p>它假定调用时 GL 上下文已经 current——本项目的上下文由 {@code GLCanvas} 置为当前，
 * 所以 {@link #initialize()} 与 {@link #dispose()} 都是诚实的 no-op。
 */
public class LwjglGLAbstraction implements GLAbstraction {

    /**
     * 为区域读回分配缓冲。
     *
     * <p><strong>不能用 {@link MemoryStack}</strong>：它默认只有 64 KB
     * （{@code Configuration.STACK_SIZE} 默认 64），而这里需要
     * {@code pixels * 4} 字节。128×128 恰好是临界点，再大一点就抛
     * {@link OutOfMemoryError}——框选稍大一点即废，且抛的是 {@code Error}。
     *
     * <p>返回直接缓冲交给 GC：{@code pickRect} 是用户触发的低频查询（框选），
     * 不是每帧路径，为此维护可复用缓冲池是不必要的复杂度。
     *
     * <p>包级可见且不碰 GL，<strong>专为可测</strong>：真正的方法需要 GL 上下文，
     * 在单测里调用会让 JVM 直接 abort。
     *
     * @param pixels 像素个数，必须非负
     * @return 容量为 {@code pixels} 的直接 IntBuffer
     */
    static IntBuffer allocateReadBuffer(int pixels) {
        return BufferUtils.createIntBuffer(pixels);
    }

    /**
     * 把公共 API 的 ARGB 整数像素转换成 OpenGL 上传所需的 RGBA 字节流。
     *
     * <p>不能直接把 {@code int[]} 交给 {@code glTexImage2D}：在小端机器上，
     * {@code 0xAARRGGBB} 的内存顺序是 B,G,R,A，蓝红通道会互换。该转换保持
     * 颜色契约与 {@link com.bingbaihanji.xuan.util.Color}、图表和 JavaFX 互操作一致。
     */
    static ByteBuffer argbToRgba(int width, int height, int[] pixels) {
        return argbToRgba(width, height, pixels, false);
    }

    /**
     * 与 {@link #argbToRgba} 同一份转换，但顺带做 <strong>alpha 预乘</strong>。
     *
     * <h2>为什么图片这条路必须预乘，而纯色那一条不必</h2>
     * <p>{@code RenderBatch} 的混合因子是 {@code GL_ONE / GL_ONE_MINUS_SRC_ALPHA}，
     * 也就是<strong>预乘 alpha</strong> 的混合公式。纯色绘制没有这个问题：顶点色在
     * {@code VertexFormat.packPremultiplied} 里已经乘过 alpha 了。而<strong>纹理是
     * 直接采样的</strong>——纹素里存什么就是什么，着色器最后那一句
     * {@code texture(uTex, vUV) * vColor} 只会再乘一个顶点色，<strong>不会替纹理补上预乘</strong>。
     *
     * <p>不做这一步的症状是<strong>半透明像素过亮</strong>：50% 透明纯红画在黑底上，
     * 正确的读数是 {@code (128,0,0)}，不预乘会读成 {@code (255,0,0)}——一倍亮，
     * 而画面看起来只是"颜色艳了点"，不报任何错。
     *
     * <p>做在<strong>上传时</strong>而不是每帧、也不在着色器里：上传一张图只发生一次
     * （见 {@code ImageStore} 的缓存），而着色器里做会让每个像素每帧都多乘一次。
     *
     * <p>取整是<strong>四舍五入</strong>（{@code (c * a + 127) / 255}），不是截断——
     * 截断会让 {@code (255, alpha=1)} 变成 0 而不是 1，整张图在极低 alpha 下系统性偏暗。
     *
     * @param width  纹理宽度
     * @param height 纹理高度
     * @param pixels 像素数据，{@code 0xAARRGGBB}，长度必须等于 {@code width * height}
     * @return 预乘后的 RGBA 字节流，通道顺序与 {@link #argbToRgba} 相同
     */
    static ByteBuffer premultipliedArgbToRgba(int width, int height, int[] pixels) {
        return argbToRgba(width, height, pixels, true);
    }

    /**
     * 两个入口<strong>唯一的一份</strong>转换实现。
     *
     * <p>刻意做成"一个核心 + 两个具名入口"而不是两份拷贝：本仓库已经为"同一份契约、
     * 两份实现"付过代价——{@code gl/Texture} 里那份独立拷贝把 RGB 写成了 BGR，
     * <strong>没有任何东西发现它</strong>，直到两边都补了测试才对照出来。
     * 字节序只写一次，就没有让它们分家的机会。
     * （<strong>那个类已于 2026-10-09 删除</strong>：全仓零引用，收口方式是删掉它而非再补交叉断言。）
     *
     * <p>刻意<strong>不</strong>把 {@code premultiply} 暴露成一个公开的布尔参数：
     * 调用点上写 {@code argbToRgba(w, h, px, true)} 读不出"true 是哪一件事"，
     * 而写错这一个布尔量的后果正是上面那段描述的"过亮一倍"。
     */
    private static ByteBuffer argbToRgba(int width, int height, int[] pixels,
                                         boolean premultiply) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("纹理尺寸必须为正数：" + width + "x" + height);
        }
        if (pixels == null || pixels.length != width * height) {
            throw new IllegalArgumentException(
                    "像素数量必须等于纹理面积，实际为 " + (pixels == null ? 0 : pixels.length)
                            + "，期望 " + (width * height));
        }
        ByteBuffer rgba = BufferUtils.createByteBuffer(width * height * 4);
        for (int pixel : pixels) {
            int a = (pixel >>> 24) & 0xFF;
            // 预乘时每个颜色通道都乘 a。未预乘那一支 r/g/b 原样写出——
            // 这正是两条入口唯一的差别，也是唯一一处需要同时维护的分支。
            int r = premultiply(pixel >>> 16 & 0xFF, a, premultiply);
            int g = premultiply(pixel >>> 8 & 0xFF, a, premultiply);
            int b = premultiply(pixel & 0xFF, a, premultiply);
            rgba.put((byte) r);
            rgba.put((byte) g);
            rgba.put((byte) b);
            rgba.put((byte) a);
        }
        return rgba.flip();
    }

    /**
     * 单个通道的预乘：{@code round(channel * alpha / 255)}。
     *
     * <p>{@code +127} 是四舍五入的整数写法（{@code 255 / 2 = 127.5}，取 127 与
     * 先乘后除的浮点写法在全部 65536 种输入上等价，且没有任何浮点舍入的余地）。
     * 不预乘时原样返回，避免多一次无意义的乘除。
     */
    private static int premultiply(int channel, int alpha, boolean premultiply) {
        if (!premultiply) {
            return channel;
        }
        return (channel * alpha + 127) / 255;
    }

    @Override
    public void initialize() {
        // 从 GLCanvas 的回调进来时 GL 上下文已经 current，这里无事可做。
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
    public void uploadVboBytes(ByteBuffer data) {
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
    }

    @Override
    public void uploadVboSubData(int offsetBytes, ByteBuffer data) {
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offsetBytes, data);
    }

    @Override
    public void setVertexAttribDivisor(int index, int divisor) {
        GL33.glVertexAttribDivisor(index, divisor);
    }

    @Override
    public void drawArraysInstancedBaseInstance(int mode, int first, int count,
                                                int instanceCount, int baseInstance) {
        GL42.glDrawArraysInstancedBaseInstance(mode, first, count, instanceCount, baseInstance);
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
    public int createBuffer() {
        return GL15.glGenBuffers();
    }

    @Override
    public void bindShaderStorageBuffer(int buffer) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
    }

    @Override
    public void allocateBufferStorage(long sizeBytes) {
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, sizeBytes, GL15.GL_DYNAMIC_COPY);
    }

    @Override
    public void uploadBufferSubData(long offsetBytes, ByteBuffer data) {
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, offsetBytes, data);
    }

    @Override
    public void bindBufferBase(int bindingIndex, int buffer) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, bindingIndex, buffer);
    }

    @Override
    public void deleteBuffer(int buffer) {
        GL15.glDeleteBuffers(buffer);
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
        // 转换先于 glGenTextures 求值：参数不合法时异常在**创建任何 GL 对象之前**抛出，
        // 不会留下一个没人删得掉的名字（Disposable 契约第 3 条）。
        return uploadTexture(width, height, argbToRgba(width, height, pixels));
    }

    @Override
    public int createPremultipliedTexture(int width, int height, int[] pixels) {
        return uploadTexture(width, height, premultipliedArgbToRgba(width, height, pixels));
    }

    /**
     * 两条上传路径<strong>唯一的一份</strong> GL 调用序列。
     *
     * <p>线程与状态无关性都靠这里统一：过滤方式是 {@code GL_LINEAR}（放大时平滑）、
     * 环绕方式是 {@code GL_CLAMP_TO_EDGE}（uv 取到 1.0 时不会绕回另一侧采到边缘的
     * 反面像素），上传后把绑定还原成 0。
     *
     * <p><strong>不生成 mipmap</strong>，{@code MIN_FILTER} 也就是 {@code GL_LINEAR}
     * 而不是 {@code GL_LINEAR_MIPMAP_LINEAR}：图片被缩到很小时会有摩尔纹。
     * 这是<strong>已声明的降级</strong>，不是漏了——两条路径都一样，且生成 mipmap
     * 会让"上传一张图"这件事带上与尺寸相关的额外开销。
     */
    private static int uploadTexture(int width, int height, ByteBuffer rgba) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba
        );
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    @Override
    public int createR8Texture(int width, int height) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // R8 是归一化格式，可以 LINEAR —— SDF 靠插值得到平滑边缘。
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        // CLAMP_TO_EDGE：默认的 REPEAT 会让图集边缘的采样绕回另一侧，
        // 表现为远端字形的边缘挂着别处的像素。
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // 内容未初始化：图集只采样已经上传过的槽位，而每个槽位四周有 SPREAD 像素的
        // 外扩把双线性过滤限制在槽内，所以未分配区域永远不会被采到。
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, width, height, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    @Override
    public void uploadR8SubImage(int texture, int x, int y, int width, int height, byte[] pixels) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // GL_UNPACK_ALIGNMENT 默认是 4，而单通道每行只有 width 个字节。
        // 不设成 1，width 不是 4 的倍数时从第二行起整行错位，字形会被斜切。
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        try {
            // 不能用 MemoryStack：它默认只有 64 KB，而一张 64x64 的字形是 4 KB
            // 还好、整块图集就是 16 MB，直接炸。BufferUtils 的直接缓冲交给 GC。
            ByteBuffer buf = BufferUtils.createByteBuffer(width * height);
            buf.put(pixels, 0, width * height);
            buf.flip();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, width, height,
                    GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, buf);
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
    }

    @Override
    public int createRgb565Texture(int width, int height) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, width, height, 0,
                GL12.GL_RGB, GL12.GL_UNSIGNED_SHORT_5_6_5, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    @Override
    public void uploadRgb565SubImage(int texture, int x, int y, int width, int height, ByteBuffer pixels) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // RGB_565 每像素 2 字节，每行可能不是 4 字节对齐
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 2);
        try {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, width, height,
                    GL12.GL_RGB, GL12.GL_UNSIGNED_SHORT_5_6_5, pixels);
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
    }

    @Override
    public void uploadArgbSubImage(int texture, int x, int y, int width, int height, int[] pixels) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        try {
            ByteBuffer buf = argbToRgba(width, height, pixels);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, width, height,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
    }

    /**
     * 校验 image 纹理的尺寸，由 {@link #createImageTexture} 与 {@link #readTextureImage}
     * <strong>共用一个说法</strong>。
     *
     * <p>包级可见且不碰 GL，<strong>专为可测</strong>——理由与 {@link #allocateReadBuffer}
     * 完全相同：真正那两个方法需要 GL 上下文，在单测里调用会让 JVM 直接 abort。
     */
    static void requirePositiveImageSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("纹理尺寸必须为正数：" + width + "x" + height);
        }
    }

    /**
     * 把 {@code GL_BGRA} 读回来的字节重新解释成 {@code 0xAARRGGBB} 整数。
     *
     * <p>它是 {@link #argbToRgba} 的<strong>另一个方向</strong>，而之所以不必再写第二份通道
     * 映射，就在读回格式取的是 {@code GL_BGRA}：内存里依次是 B,G,R,A 四个字节，正好与
     * {@code 0xAARRGGBB} 在小端机上的内存表示逐字节相同，于是 {@code asIntBuffer()} 出来
     * 就是 {@code 0xAARRGGBB}——<strong>全仓的通道映射仍然只有 argbToRgba 那一份</strong>。
     *
     * <p>反面就是 {@code GL_RGBA} 读：那会得到 R,G,B,A 的顺序，必须再写一次
     * {@code r/g/b/a} 的提取，正是本仓库刚删掉的那类"同一份契约、两份实现"。
     *
     * <p>依赖本机小端字节序这一件事照实说（大端机上要换成显式的重排）：同文件里的
     * {@link #readUnsignedIntPixelFromPixelPackBuffer} 用的是同一条路
     * （{@code byteBuffer.order(nativeOrder()).getInt(0)}）。本项目当前是 Windows 专用。
     */
    static void bgraToArgb(ByteBuffer bgra, int pixels, int[] out) {
        if (bgra == null || bgra.remaining() < pixels * 4) {
            throw new IllegalArgumentException(
                    "BGRA 缓冲至少要能装下 " + pixels + " 个像素（每像素 4 字节），实际剩余 "
                            + (bgra == null ? 0 : bgra.remaining()) + " 字节");
        }
        if (out == null || out.length < pixels) {
            throw new IllegalArgumentException(
                    "输出数组至少要能装下 " + pixels + " 个像素，实际 "
                            + (out == null ? 0 : out.length));
        }
        bgra.order(ByteOrder.nativeOrder()).asIntBuffer().get(out, 0, pixels);
    }

    @Override
    public int createImageTexture(int width, int height) {
        // 校验先于 glGenTextures：尺寸不合法时异常在创建任何 GL 对象之前抛出，
        // 不会留下一个没人删得掉的名字（Disposable 契约第 3 条）。
        requirePositiveImageSize(width, height);
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // NEAREST 而不是 LINEAR：这张纹理的每一列是一个独立的时间窗，列与列之间是真边界。
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        // CLAMP_TO_EDGE 而不是默认的 REPEAT：环形映射由调用方在 UV 上做，
        // uv 取到 1.0 时不该绕回另一侧采到边缘的反面像素。
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // 内容不传：由 compute 的 imageStore 填。
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    @Override
    public void bindImageTexture(int imageUnit, int texture, int access) {
        // level / layered / layer 固定 0/false/0：本项目只用第 0 级、不是数组纹理。
        // 格式固定 GL_RGBA8，与 createImageTexture 建出来的那张一致；纹理为 0 时 GL 不看它。
        GL42.glBindImageTexture(imageUnit, texture, 0, false, 0, access, GL11.GL_RGBA8);
    }

    @Override
    public void readTextureImage(int texture, int width, int height, int[] outArgb) {
        requirePositiveImageSize(width, height);
        // 有像素包缓冲绑着时 glGetTexImage 会把像素写进那个缓冲而不是 out，
        // 于是 out 一个字节都没动过——不报错、全是 0。临时解绑，事后再还原
        // （与 enqueueUnsignedIntPixelRead 同一条口径）。
        int previousPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        try {
            // 不能用 MemoryStack（64 KB 上限，见 allocateReadBuffer）。用 GL_BGRA 读，
            // 于是小端机上 asIntBuffer() 直接就是 0xAARRGGBB，见 bgraToArgb。
            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, pixels);
            bgraToArgb(pixels, width * height, outArgb);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPack);
        }
    }

    @Override
    public int createFramebuffer() {
        return GL30.glGenFramebuffers();
    }

    @Override
    public void bindFramebuffer(int framebuffer) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
    }

    @Override
    public void deleteFramebuffer(int framebuffer) {
        GL30.glDeleteFramebuffers(framebuffer);
    }

    @Override
    public int currentFramebufferBinding() {
        return GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
    }

    @Override
    public int createIntegerTexture(int width, int height) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // 整数纹理的过滤器必须是 GL_NEAREST。GL_LINEAR 对整数纹理非法
        // （会生成 GL_INVALID_OPERATION，且采样结果未定义）。默认的
        // GL_NEAREST_MIPMAP_LINEAR 在没有 mipmap 时同样是不完整的。
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32UI,
                width, height, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT,
                (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    @Override
    public void deleteTexture(int texture) {
        GL11.glDeleteTextures(texture);
    }

    @Override
    public void attachTextureToColor0(int texture) {
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, texture, 0);
    }

    @Override
    public int framebufferStatus() {
        return GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
    }

    @Override
    public void clearIntegerColor(int value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buffer = stack.ints(value, 0, 0, 0);
            GL30.glClearBufferuiv(GL30.GL_COLOR, 0, buffer);
        }
    }

    @Override
    public boolean isScissorEnabled() {
        return GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    }

    @Override
    public void setScissorEnabled(boolean enabled) {
        if (enabled) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    @Override
    public int readUnsignedIntPixel(int x, int y) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 必须是 callocInt 而不是 mallocInt：mallocInt 不清零，若 glReadPixels
            // 因 GL 错误没写入，读回的就是栈上的残留值——非确定的拾取结果。
            IntBuffer buffer = stack.callocInt(1);
            GL11.glReadPixels(x, y, 1, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, buffer);
            return buffer.get(0);
        }
    }

    @Override
    public void readUnsignedIntPixels(int x, int y, int width, int height, int[] out) {
        IntBuffer buffer = allocateReadBuffer(width * height);
        GL11.glReadPixels(x, y, width, height,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, buffer);
        buffer.get(out, 0, width * height);
    }

    @Override
    public int createPixelPackBuffer() {
        return GL15.glGenBuffers();
    }

    @Override
    public void deletePixelPackBuffer(int buffer) {
        GL15.glDeleteBuffers(buffer);
    }

    @Override
    public void enqueueUnsignedIntPixelRead(int x, int y, int buffer) {
        int previous = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
        try {
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, Integer.BYTES, GL15.GL_STREAM_READ);
            GL11.glReadPixels(x, y, 1, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, 0L);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previous);
        }
    }

    @Override
    public long fenceSync() {
        long sync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (sync == 0L) {
            throw new IllegalStateException("glFenceSync 创建失败");
        }
        return sync;
    }

    @Override
    public boolean isSyncSignaled(long sync) {
        int status = GL32.glClientWaitSync(sync, 0, 0L);
        if (status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED) {
            return true;
        }
        if (status == GL32.GL_TIMEOUT_EXPIRED) {
            return false;
        }
        throw new IllegalStateException("glClientWaitSync 失败，状态码 0x" + Integer.toHexString(status));
    }

    @Override
    public void deleteSync(long sync) {
        if (sync != 0L) {
            GL32.glDeleteSync(sync);
        }
    }

    @Override
    public int readUnsignedIntPixelFromPixelPackBuffer(int buffer) {
        int previous = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
        try {
            ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0,
                    Integer.BYTES, GL30.GL_MAP_READ_BIT, null);
            if (mapped == null) {
                throw new IllegalStateException("映射拾取 PBO 失败");
            }
            try {
                return mapped.order(ByteOrder.nativeOrder()).getInt(0);
            } finally {
                if (!GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER)) {
                    throw new IllegalStateException("拾取 PBO 在映射期间被损坏");
                }
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previous);
        }
    }

    @Override
    public void dispose() {
        // 这一层自己不持有任何长期的本机资源：纹理、缓冲、VAO 都是按需创建、
        // 由各自的持有者显式删除的，没有可在这里统一释放的东西。
    }
}
