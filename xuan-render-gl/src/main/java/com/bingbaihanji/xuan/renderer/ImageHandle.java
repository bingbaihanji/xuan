package com.bingbaihanji.xuan.renderer;

import com.bingbaihanji.xuan.util.Disposable;

/**
 * 一张图片的句柄：由 {@link Gc#createImage} 创建，可直接交给 {@code Gc.drawImage} 绘制。
 *
 * <h2>它只是"同一个键的另一种传法"</h2>
 * <p>句柄不持有 GL 名字。它持有的是<strong>像素数组</strong>与创建它的 {@link ImageStore}，
 * 而纹理仍在那个 store 里、仍按<strong>数组的对象身份</strong>索引。于是
 * {@code gc.drawImage(pixels, ...)} 与 {@code gc.drawImage(handle, ...)}
 * <strong>命中同一条缓存槽</strong>——用哪种方式画，都只上传一次。
 *
 * <p>它能做而裸数组做不到的只有一件事：<strong>确定性地提前释放</strong>。
 * 裸数组那条路只能等两代之后被自动回收。
 *
 * <h2>★ 不上传任何东西</h2>
 * <p>构造是<strong>纯 CPU</strong> 的：不建纹理、不调 GL。第一次真正绘制时才上传。
 * 于是"建了一个句柄却从没画过"的代价是零字节显存。
 *
 * <h2>★ dispose 是可选的，而且它对忘记的人是安全的</h2>
 * <p>不调它不会泄漏：连续两代没被画到的图片由 {@link ImageStore} 自动释放
 * （见那里的类文档）。所以忘记 dispose 的后果只是"多占两帧"。
 *
 * <h2>★ 但 dispose 必须在 GL 线程上调用</h2>
 * <p>它会立刻删掉 GL 名字，而 {@code glDeleteTextures} 只能在 GL 线程上执行——
 * 也就是 {@code xuan { onRender { ... } }} / {@code onInit} / {@code onDispose} 内部。
 * <strong>在 JavaFX 线程上不要调它</strong>（那是一次跨线程 GL 调用，崩得毫无规律）；
 * 那些地方请干脆不调，交给自动回收。
 *
 * <h2>★ 不要就地改那个像素数组</h2>
 * <p>纹理按数组<strong>身份</strong>缓存，所以交进来之后再改数组的内容
 * <strong>不会被感知</strong>，画面仍是旧的那张。要换图请传一个新的数组
 * （或者再建一个句柄）。
 */
public final class ImageHandle implements Disposable {

    /** 纹理的归属者；dispose 时由它删除 GL 名字。 */
    private final ImageStore store;

    /**
     * 像素数据。
     *
     * <p><strong>不是 final</strong>：{@link #dispose()} 会把它置空。4K 图一份就是 33 MB，
     * 而它整个生命周期都被本对象强引用着——不置空的话"dispose 了"只省下显存，
     * CPU 那份要等用户自己把数组的最后一个引用也丢掉才释放。
     */
    private int[] pixels;

    private final int width;

    private final int height;

    private boolean disposed;

    /**
     * 创建句柄。**不碰 GL**（构造是纯 CPU 的）。
     *
     * <p>包级可见：调用方只能经 {@code Gc.createImage} 拿到它，而那条路保证
     * store 与绘制的 {@code Gc} 是同一个。
     *
     * @param store 纹理的归属者
     * @param pixels 像素数据，{@code 0xAARRGGBB}
     * @param width  图像宽度
     * @param height 图像高度
     * @throws IllegalArgumentException 像素数与面积不匹配（判据与 {@link ImageStore} 共用一份）
     */
    ImageHandle(ImageStore store, int[] pixels, int width, int height) {
        ImageStore.requireValidPixels(pixels, width, height);
        this.store = store;
        this.pixels = pixels;
        this.width = width;
        this.height = height;
    }

    /**
     * 图像宽度（像素）。
     *
     * @throws IllegalStateException 已释放
     */
    public int width() {
        checkNotDisposed();
        return width;
    }

    /**
     * 图像高度（像素）。
     *
     * @throws IllegalStateException 已释放
     */
    public int height() {
        checkNotDisposed();
        return height;
    }

    /** 是否已释放。 */
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * 取出像素数据供绘制使用；<strong>已释放时抛异常</strong>。
     *
     * <p>包级可见，是 {@code Gc.drawImage(handle, ...)} 取像素的唯一入口——
     * 于是"用了已释放的句柄"这件事在那条路径上<strong>必然</strong>被拦住，
     * 不需要在 Gc 里再写一遍判据（两处判据迟早会分家）。
     *
     * @return 像素数据
     * @throws IllegalStateException 已释放
     */
    int[] requirePixels() {
        checkNotDisposed();
        return pixels;
    }

    /**
     * 提前释放：删掉 GPU 副本，并丢弃 CPU 那份像素引用。
     *
     * <p><strong>幂等</strong>，而且<strong>可省略</strong>（不调它由自动回收兜底）。
     * 必须在 GL 线程上调用——见类文档。
     *
     * <p>底层 {@link ImageStore} 已经释放过时是空操作：那种次序是正常的
     * （{@code RenderBatch.dispose()} 会先删掉整张表），那时没有名字可删，
     * 本方法只剩"置位自己的状态"这件事。
     */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        store.release(pixels);
        pixels = null;
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException(
                    "ImageHandle 已 dispose()，不能继续使用：像素数据在释放时已被丢弃。"
                            + "还要画这张图的话，请重新 createImage(...)"
                            + "（或者干脆不调 dispose，交给 ImageStore 的自动回收）。");
        }
    }
}
