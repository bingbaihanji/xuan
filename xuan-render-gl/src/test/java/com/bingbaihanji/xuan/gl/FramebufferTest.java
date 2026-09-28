package com.bingbaihanji.xuan.gl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FramebufferTest {

    @Test
    void 构造后恢复原先的帧缓冲绑定() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        gl.boundFramebuffer = 7;          // 模拟 openglfx 自己的 FBO
        new Framebuffer(gl, 16, 16);
        assertEquals(7, gl.boundFramebuffer,
                "构造完必须把绑定还给调用方；否则后续所有绘制都画进拾取缓冲");
        assertEquals(7, gl.bindCalls.get(gl.bindCalls.size() - 1),
                "最后一次 bindFramebuffer 应当是恢复，而不是又切到自己的 FBO");
    }

    @Test
    void 帧缓冲不完整时抛异常并带上状态码() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        gl.statusToReturn = 0x8CD6;       // GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new Framebuffer(gl, 16, 16));
        assertTrue(e.getMessage().contains("8CD6"),
                "消息必须带上实际状态码——UNSUPPORTED 与 INCOMPLETE_ATTACHMENT 的排查方向完全不同");
    }

    @Test
    void 帧缓冲不完整时不泄漏已创建的资源() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        gl.statusToReturn = 0x8CD6;
        assertThrows(IllegalStateException.class, () -> new Framebuffer(gl, 16, 16));
        assertEquals(1, gl.deletedFramebuffers.size(), "创建失败也必须回收 FBO");
        assertEquals(1, gl.deletedTextures.size(), "创建失败也必须回收纹理");
    }

    @Test
    void 尺寸非正时抛异常() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        assertThrows(IllegalArgumentException.class, () -> new Framebuffer(gl, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> new Framebuffer(gl, 16, -1));
        // 断言必须落在「创建」而不是「删除」上：只看删除记录的话，把校验挪到创建
        // 之后——建完就抛、根本没删——删除记录同样是空的，断言恒真而变异照样存活。
        assertTrue(gl.createdFramebuffers.isEmpty(), "参数校验应当在创建任何 GL 资源之前");
        assertTrue(gl.createdTextures.isEmpty(), "参数校验应当在创建任何 GL 资源之前");
    }

    @Test
    void 释放是幂等的() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        Framebuffer fb = new Framebuffer(gl, 16, 16);
        fb.dispose();
        fb.dispose();
        assertEquals(1, gl.deletedFramebuffers.size(), "重复 dispose 不该重复删除");
        assertEquals(1, gl.deletedTextures.size());
    }
}
