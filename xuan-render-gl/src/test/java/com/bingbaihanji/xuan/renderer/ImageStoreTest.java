package com.bingbaihanji.xuan.renderer;

import com.bingbaihanji.xuan.gl.FakeGLAbstraction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageStore} 的缓存与回收行为。
 *
 * <p>全部用 {@link FakeGLAbstraction}，零 GL 上下文——这是刻意的：本类要证的两件事
 * （<b>只上传一次</b>与<b>不再画就释放</b>）在画面上<b>没有任何痕迹</b>。
 * "每帧全量重传"与"只传一次"逐像素相同，"释放了"与"泄漏着"也逐像素相同
 * （泄漏只会在显存耗尽时才显形，而那要几千帧之后）。<b>只有计数能证。</b>
 *
 * <p>帧号的记法沿用 {@code ChartRenderer.releaseUnused} 的口径：
 * 在帧 k 画过、之后不再画 ⇒ <b>第 k+3 帧的帧首</b>释放。下面每条断言的消息里
 * 都写明了它落在哪一帧，因为"差一帧"正是这套机制唯一会错的地方。
 */
class ImageStoreTest {

    private static int[] pixels(int count) {
        return new int[count];
    }

    @Test
    void 同一个数组只上传一次() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageStore store = new ImageStore(gl);
        store.beginFrame();
        int[] p = pixels(4);

        int first = store.textureFor(p, 2, 2);
        int second = store.textureFor(p, 2, 2);

        assertEquals(first, second, "同一个数组对象必须命中同一个纹理槽");
        assertEquals(1, gl.createdTextures.size(), "同一张图第二帧起就不该再上传");
        assertEquals(1L, store.takeUploadedImageCount());
    }

    /**
     * 键是<strong>对象身份</strong>，不是内容——两个内容逐位相同的数组是两张纹理。
     *
     * <p>这条断言存在的理由是把它钉成<b>已知且刻意</b>的行为，而不是让下一个人
     * 当成 bug 去"优化"：改成按内容比较要在每次绘制时扫一遍整张图
     * （100 万像素 = 每帧 4 MB 的读），而收益只是省下"用户把同一张图读了两遍"那点显存。
     */
    @Test
    void 内容相同的两个数组是两张纹理() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageStore store = new ImageStore(gl);
        store.beginFrame();

        int a = store.textureFor(new int[]{0xFF_11_22_33}, 1, 1);
        int b = store.textureFor(new int[]{0xFF_11_22_33}, 1, 1);

        assertNotEquals(a, b);
        assertEquals(2, gl.createdTextures.size());
    }

    /**
     * 同一个数组换个尺寸必须<strong>响亮失败</strong>。
     *
     * <p>不拦的话会拿旧尺寸上传的那张纹理硬画：uv 仍是 0..1，于是每一行按错误的
     * 跨距解释，画面是<strong>一张扭曲但完全正常的图</strong>——本仓库最防的形状。
     */
    @Test
    void 同一个数组换个尺寸是明确抛异常而不是拿旧纹理硬画() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageStore store = new ImageStore(gl);
        store.beginFrame();
        int[] p = pixels(4);

        store.textureFor(p, 2, 2);

        assertThrows(IllegalArgumentException.class, () -> store.textureFor(p, 4, 1));
        assertEquals(1, gl.createdTextures.size(), "抛异常的那次不该已经建出纹理");
    }

    @Test
    void 像素数与面积不匹配时明确抛异常且不创建纹理() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageStore store = new ImageStore(gl);
        store.beginFrame();

        assertThrows(IllegalArgumentException.class, () -> store.textureFor(pixels(3), 2, 2));
        assertThrows(IllegalArgumentException.class, () -> store.textureFor(pixels(4), 0, 2));
        assertThrows(IllegalArgumentException.class, () -> store.textureFor(null, 2, 2));

        assertTrue(gl.createdTextures.isEmpty(),
                "校验必须发生在创建之前——否则每次失败都漏一个没人删得掉的名字");
    }

    @Test
    void 连续两帧没用到就在第三帧的帧首释放() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageStore store = new ImageStore(gl);
        int[] p = pixels(1);

        store.beginFrame();                    // 帧 1
        store.textureFor(p, 1, 1);

        store.beginFrame();                    // 帧 2：帧首，隔了一帧
        assertEquals(1, store.imageTextureCount(), "第 k+1 帧不该释放");

        store.beginFrame();                    // 帧 3：帧首，隔了两帧
        assertEquals(1, store.imageTextureCount(), "第 k+2 帧仍不该释放（宽限是两代）");

        store.beginFrame();                    // 帧 4：帧首，隔了三帧
        assertEquals(0, store.imageTextureCount(), "第 k+3 帧的帧首才释放");
        assertEquals(1, gl.deletedTextures.size(), "释放必须真的把 GL 名字删掉，而不是只从表里移除");
    }

    /**
     * 宽限两代挡住的正是这种用法：<b>每隔一帧画一次</b>。
     *
     * <p>改成"上一帧没画就收"（{@code GRACE_GENERATIONS} = 1）之后，这种用法会
     * <b>每画一次就销毁又重建一次</b>，而重建意味着重传整张图——1M 像素的图就是每两帧
     * 4 MB，<b>而画面逐像素相同</b>。这条断言是那个取值的唯一判据。
     */
    @Test
    void 每隔一帧画一次的用法不会被反复回收重传() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageStore store = new ImageStore(gl);
        int[] p = pixels(1);

        for (int frame = 0; frame < 8; frame++) {
            store.beginFrame();
            if (frame % 2 == 0) {
                store.textureFor(p, 1, 1);
            }
        }

        assertEquals(1, gl.createdTextures.size(), "宽限两代正是为了挡住这种用法");
        assertEquals(1, store.imageTextureCount());
    }

    @Test
    void 被回收之后重新画会自动重建而不是报错() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageStore store = new ImageStore(gl);
        int[] p = pixels(1);

        store.beginFrame();                    // 帧 1
        store.textureFor(p, 1, 1);
        store.beginFrame();                    // 帧 2
        store.beginFrame();                    // 帧 3
        store.beginFrame();                    // 帧 4：帧首释放
        assertEquals(0, store.imageTextureCount());

        store.beginFrame();                    // 帧 5
        int again = store.textureFor(p, 1, 1);

        assertEquals(1, store.imageTextureCount());
        assertTrue(gl.createdTextures.contains(again), "重建出来的必须是真上传过的新名字");
        assertEquals(2, gl.createdTextures.size());
        assertEquals(2L, store.takeUploadedImageCount());
    }

    @Test
    void 显式释放一个键只影响它自己的纹理() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageStore store = new ImageStore(gl);
        store.beginFrame();
        int[] a = pixels(1);
        int[] b = pixels(1);
        int idA = store.textureFor(a, 1, 1);
        store.textureFor(b, 1, 1);

        store.release(a);

        assertEquals(1, store.imageTextureCount());
        assertEquals(List.of(idA), gl.deletedTextures);
        assertThrows(IllegalArgumentException.class, () -> store.textureFor(a, 2, 2),
                "释放过的键不该还留着旧尺寸的记录");
    }

    @Test
    void dispose释放全部纹理且幂等() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageStore store = new ImageStore(gl);
        store.beginFrame();
        store.textureFor(pixels(1), 1, 1);
        store.textureFor(pixels(1), 1, 1);

        store.dispose();

        assertEquals(0, store.imageTextureCount());
        assertEquals(2, gl.deletedTextures.size());

        store.dispose();

        assertEquals(2, gl.deletedTextures.size(), "第二次 dispose 必须什么都不做");
    }

    /**
     * 释放之后继续使用<strong>明确抛异常</strong>——{@code Disposable} 契约第 2 条，
     * 与 {@code ShaderProgram} / {@code Texture} 同口径。
     *
     * <p>不抛的话会拿已经 {@code glDeleteTextures} 掉的名字继续绑：GL 上那是
     * "绑到名字 0"，采样结果退化成白纹理，于是整张图变成<strong>一个纯色方块</strong>，
     * 而没有任何一处报错。
     */
    @Test
    void 释放之后继续使用明确抛异常() {
        ImageStore store = new ImageStore(new FakeGLAbstraction());
        store.dispose();

        assertThrows(IllegalStateException.class, () -> store.textureFor(pixels(1), 1, 1));
        assertThrows(IllegalStateException.class, store::beginFrame);
    }
}
