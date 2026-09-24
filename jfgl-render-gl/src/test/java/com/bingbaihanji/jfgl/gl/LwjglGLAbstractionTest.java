package com.bingbaihanji.jfgl.gl;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;

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
}
