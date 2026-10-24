package com.bingbaihanji.xuan.gl;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LwjglGLAbstractionTest {

    @Test
    void 区域读回缓冲能装下整个帧缓冲() {
        // 988x738 是 125% 缩放下 800x600 窗口的真实设备像素数（见 CLAUDE.md）。
        // 用 MemoryStack 的话这里必抛 OutOfMemoryError: Out of stack space.。
        int pixels = 988 * 738;
        IntBuffer buffer = LwjglGLAbstraction.allocateReadBuffer(pixels);
        assertEquals(pixels, buffer.capacity());
    }

    @Test
    void 区域读回缓冲能装下远大于64KB的区域() {
        // 64 KB / 4 = 16384 像素，正是 MemoryStack 的临界点。
        IntBuffer buffer = LwjglGLAbstraction.allocateReadBuffer(16384 * 4);
        assertEquals(16384 * 4, buffer.capacity());
    }

    @Test
    void 纹理像素从ARGB转换为OpenGL需要的RGBA() {
        ByteBuffer rgba = LwjglGLAbstraction.argbToRgba(2, 1,
                new int[]{0x80_12_34_56, 0xFF_A0_B0_C0});

        assertEquals(0x12, rgba.get() & 0xFF);
        assertEquals(0x34, rgba.get() & 0xFF);
        assertEquals(0x56, rgba.get() & 0xFF);
        assertEquals(0x80, rgba.get() & 0xFF);
        assertEquals(0xA0, rgba.get() & 0xFF);
        assertEquals(0xB0, rgba.get() & 0xFF);
        assertEquals(0xC0, rgba.get() & 0xFF);
        assertEquals(0xFF, rgba.get() & 0xFF);
    }

    @Test
    void 纹理像素尺寸非法时明确拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> LwjglGLAbstraction.argbToRgba(0, 1, new int[0]));
        assertThrows(IllegalArgumentException.class,
                () -> LwjglGLAbstraction.argbToRgba(2, 1, new int[1]));
    }

    /**
     * 预乘那一份的数值与字节序。
     *
     * <p>判据取的是**三个有判别力的端点**，而不是"随便一张图看着没变"：
     * <ul>
     *   <li>{@code 0x80FF0000}（半透明纯红）⇒ {@code 128,0,0,128}。这是管道真正要的那条：
     *       混合因子是 {@code GL_ONE / GL_ONE_MINUS_SRC_ALPHA}，不预乘时这一格会读出
     *       {@code 255}——**过亮一倍，而画面只是"颜色艳了点"**；</li>
     *   <li>{@code alpha = 255} ⇒ 与不预乘那一份**逐字节相同**（预乘在 a=1 时是恒等，
     *       这条同时钉住"别把 a=255 也乘一遍"这类写反）；</li>
     *   <li>{@code alpha = 0} ⇒ RGB 全归零。漏掉这一格时，全透明像素仍带着原来的颜色，
     *       混合结果与预乘语义不一致，而**它在画面上完全看不出来**（alpha=0 本来就不显示），
     *       只有和另一次"整体降 alpha"的绘制叠加时才露出来。</li>
     * </ul>
     *
     * <p>另外两条钉<b>取整方向</b>：实现取的是 {@code (c * a + 127) / 255}（四舍五入），
     * {@code (255,1)} 与 {@code (1,128)} 这两个点在 floor 下都是 0、在 round 下都是 1。
     */
    @Test
    void 预乘转换把每个通道乘以alpha() {
        ByteBuffer rgba = LwjglGLAbstraction.premultipliedArgbToRgba(3, 1, new int[]{
                0x80_FF_00_00,
                0xFF_FF_FF_FF,
                0x00_01_02_03});

        assertEquals(0x80, rgba.get() & 0xFF);   // R = round(255 * 128 / 255)
        assertEquals(0x00, rgba.get() & 0xFF);
        assertEquals(0x00, rgba.get() & 0xFF);
        assertEquals(0x80, rgba.get() & 0xFF);

        assertEquals(0xFF, rgba.get() & 0xFF);   // a = 255 时是恒等
        assertEquals(0xFF, rgba.get() & 0xFF);
        assertEquals(0xFF, rgba.get() & 0xFF);
        assertEquals(0xFF, rgba.get() & 0xFF);

        assertEquals(0x00, rgba.get() & 0xFF);   // a = 0 ⇒ RGB 也必须归零
        assertEquals(0x00, rgba.get() & 0xFF);
        assertEquals(0x00, rgba.get() & 0xFF);
        assertEquals(0x00, rgba.get() & 0xFF);
    }

    @Test
    void 预乘的取整是四舍五入而不是截断() {
        ByteBuffer rgba = LwjglGLAbstraction.premultipliedArgbToRgba(2, 1, new int[]{
                0x01_FF_FF_FF,   // 真值 255 * 1 / 255 = 1.0
                0x80_01_01_01}); // 真值 1 * 128 / 255 = 0.502

        assertEquals(0x01, rgba.get() & 0xFF);
        assertEquals(0x01, rgba.get() & 0xFF);

        assertEquals(0x01, rgba.get() & 0xFF);
        assertEquals(0x01, rgba.get() & 0xFF);
    }

    /**
     * 回读方向的字节序：必须是上传方向那条断言的<strong>反面</strong>，而不是另一份契约。
     *
     * <p>上传写的是 R,G,B,A 四个字节（上一条断言），回读用的是 {@code GL_BGRA}：
     * 内存里 B,G,R,A 正好是 {@code 0xAARRGGBB} 在小端机上的表示。所以同一个整数在两处
     * 的字节排列是镜像关系——这条断言钉的就是这个镜像，钉反了的表现是"读回来的图红蓝互换"，
     * 而它只在看图时显眼、在数值上完全正常。
     */
    @Test
    void 读回纹理的字节序与上传方向互为镜像() {
        // 0x80_12_34_56 ⇒ GL_BGRA 字节 [0x56, 0x34, 0x12, 0x80]（上传那边是 [12,34,56,80]）
        ByteBuffer bgra = ByteBuffer.allocateDirect(8);
        bgra.put((byte) 0x56).put((byte) 0x34).put((byte) 0x12).put((byte) 0x80);
        bgra.put((byte) 0xC0).put((byte) 0xB0).put((byte) 0xA0).put((byte) 0xFF);
        bgra.flip();

        int[] out = new int[2];
        LwjglGLAbstraction.bgraToArgb(bgra, 2, out);

        assertEquals(0x80_12_34_56, out[0]);
        assertEquals(0xFF_A0_B0_C0, out[1]);
    }

    @Test
    void 读回像素数非法时明确拒绝() {
        ByteBuffer enough = ByteBuffer.allocateDirect(16);
        int[] out = new int[4];
        // 缓冲装不下
        assertThrows(IllegalArgumentException.class,
                () -> LwjglGLAbstraction.bgraToArgb(ByteBuffer.allocateDirect(8), 4, out));
        // 数组装不下
        assertThrows(IllegalArgumentException.class,
                () -> LwjglGLAbstraction.bgraToArgb(enough, 4, new int[3]));
        // null 两个方向都要挡住：否则要么 NPE、要么读进空气
        assertThrows(IllegalArgumentException.class,
                () -> LwjglGLAbstraction.bgraToArgb(null, 4, out));
        assertThrows(IllegalArgumentException.class,
                () -> LwjglGLAbstraction.bgraToArgb(enough, 4, null));
        // 正向控制：恰好装得下时必须放行（否则上面四条可能只是恒抛）
        LwjglGLAbstraction.bgraToArgb(ByteBuffer.allocateDirect(16), 4, new int[4]);
    }

    @Test
    void image纹理尺寸非正时明确拒绝() {
        // 校验必须发生在 glGenTextures 之前，否则非法尺寸会留下一个没人删得掉的名字。
        // 两个入口（createImageTexture / readTextureImage）共用这一份，
        // 所以这里钉的就是"多大算合法"的唯一说法。
        assertThrows(IllegalArgumentException.class,
                () -> LwjglGLAbstraction.requirePositiveImageSize(0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> LwjglGLAbstraction.requirePositiveImageSize(4, 0));
        assertThrows(IllegalArgumentException.class,
                () -> LwjglGLAbstraction.requirePositiveImageSize(4, -1));
        // 正向控制
        LwjglGLAbstraction.requirePositiveImageSize(1, 1);
    }

    @Test
    void 预乘那一份的尺寸校验与不预乘的完全相同() {
        assertThrows(IllegalArgumentException.class,
                () -> LwjglGLAbstraction.premultipliedArgbToRgba(0, 1, new int[0]));
        assertThrows(IllegalArgumentException.class,
                () -> LwjglGLAbstraction.premultipliedArgbToRgba(2, 1, new int[1]));
    }
}
