package com.bingbaihanji.xuan.renderer;

import com.bingbaihanji.xuan.gl.FakeGLAbstraction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageHandle} 的生命周期。
 *
 * <p>句柄本身不持有 GL 名字——它持有的是<b>像素数组</b>与创建它的
 * {@link ImageStore}。所以"释放"分成两件不同的事，下面两类断言各钉一件：
 * <ul>
 *   <li>释放 <b>GPU 副本</b>：由 {@code store.release} 完成，表现为
 *       {@code imageTextureCount()} 下降、GL 名字被删；</li>
 *   <li>释放 <b>CPU 副本</b>：句柄把像素数组的引用置空。4K 图一份就是 33 MB，
 *       而它整个生命周期都被句柄强引用着——不置空的话"dispose 了"只省下显存。</li>
 * </ul>
 *
 * <p>释放之后<b>任何</b>使用都抛异常（{@code Disposable} 契约第 2 条）。
 * 尺寸这类纯元数据也抛，与 {@code Texture.getTextureId()} 同口径：
 * "这个对象还能不能用"只该有一个答案，分成"有些成员能用有些不能"迟早会被猜错。
 */
class ImageHandleTest {

    private static ImageHandle handle(ImageStore store, int[] pixels, int w, int h) {
        return new ImageHandle(store, pixels, w, h);
    }

    @Test
    void 尺寸从构造参数来() {
        ImageHandle img = handle(new ImageStore(new FakeGLAbstraction()), new int[6], 3, 2);

        assertEquals(3, img.width());
        assertEquals(2, img.height());
        assertFalse(img.isDisposed());
    }

    @Test
    void 像素数与面积不匹配时明确拒绝且不创建纹理() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageStore store = new ImageStore(gl);

        assertThrows(IllegalArgumentException.class, () -> handle(store, new int[5], 3, 2));
        assertThrows(IllegalArgumentException.class, () -> handle(store, null, 3, 2));

        assertTrue(gl.createdTextures.isEmpty());
    }

    @Test
    void dispose释放纹理并置位状态() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageStore store = new ImageStore(gl);
        store.beginFrame();
        int[] pixels = new int[1];
        ImageHandle img = handle(store, pixels, 1, 1);
        // 先画一次让纹理真的存在（创建本身是懒上传，见 ImageStore）
        store.textureFor(pixels, 1, 1);
        assertEquals(1, store.imageTextureCount());

        img.dispose();

        assertTrue(img.isDisposed());
        assertEquals(0, store.imageTextureCount());
        assertEquals(1, gl.deletedTextures.size());
    }

    @Test
    void dispose幂等() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageStore store = new ImageStore(gl);
        store.beginFrame();
        int[] pixels = new int[1];
        ImageHandle img = handle(store, pixels, 1, 1);
        store.textureFor(pixels, 1, 1);

        img.dispose();
        img.dispose();

        assertEquals(1, gl.deletedTextures.size(), "第二次 dispose 必须什么都不做");
    }

    @Test
    void 没画过就dispose是空操作() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageHandle img = handle(new ImageStore(gl), new int[1], 1, 1);

        img.dispose();

        assertTrue(img.isDisposed());
        assertTrue(gl.deletedTextures.isEmpty(), "从没上传过，就没有 GL 名字可删");
    }

    /**
     * 释放之后继续使用必须<strong>明确抛异常</strong>。
     *
     * <p>不抛的话会拿已经删掉的纹理名字去绑：GL 上那是绑到名字 0，采样退化成白纹理，
     * 整张图变成<strong>一个纯色方块</strong>，而没有任何一处报错。
     *
     * <p>这条断言同时是 {@code Gc.drawImage(handle, ...)} 那条路径的守卫：
     * 那次调用取像素走的就是 {@link ImageHandle#requirePixels()}。
     */
    @Test
    void 释放之后继续使用明确抛异常() {
        ImageHandle img = handle(new ImageStore(new FakeGLAbstraction()), new int[1], 1, 1);
        img.dispose();

        assertThrows(IllegalStateException.class, img::requirePixels);
        assertThrows(IllegalStateException.class, img::width);
        assertThrows(IllegalStateException.class, img::height);
    }

    /**
     * 底层 {@code ImageStore} 先释放时，句柄的 dispose 不该失败。
     *
     * <p>这是<b>正常</b>的关闭次序：{@code RenderBatch.dispose()} 会先把整张表连纹理一起
     * 删掉，而持有句柄的用户完全可能在那之后再走自己的清理（{@code finally} 块里）。
     * 那时句柄要做的事（删纹理）已经由别人做完了，剩下的只有"置位自己的状态"。
     */
    @Test
    void 底层已释放时句柄的dispose仍然成功() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        ImageStore store = new ImageStore(gl);
        store.beginFrame();
        int[] pixels = new int[1];
        ImageHandle img = handle(store, pixels, 1, 1);
        store.textureFor(pixels, 1, 1);

        store.dispose();
        img.dispose();

        assertTrue(img.isDisposed());
        assertEquals(1, gl.deletedTextures.size(), "store 删过一次，句柄不该再删一遍（名字已经归还了）");
    }
}
