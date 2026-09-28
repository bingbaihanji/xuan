package com.bingbaihanji.xuan.gl;

import com.bingbaihanji.xuan.util.Disposable;

import java.nio.ByteBuffer;

/**
 * 可增长的顶点缓冲对象（VBO）封装。
 *
 * <p>使用 {@code GL_DYNAMIC_DRAW}，每帧通过 {@link #upload} 覆盖写入。
 * 容量不足时自动重建更大的缓冲。
 *
 * <p>所有方法都必须在 GL 线程上调用。
 */
public final class VertexBuffer implements Disposable {

    /** GL 抽象层，所有 GL 调用都经它转发 */
    private final GLAbstraction gl;

    /** 底层 VBO 的 ID */
    private int vbo;

    /** 当前缓冲容量（字节） */
    private int capacityBytes;

    /** 是否已释放，保证 {@link #dispose()} 幂等 */
    private boolean disposed = false;

    /**
     * 创建顶点缓冲并按给定字节数预分配容量。
     *
     * <p>构造过程为：创建 VBO → 绑定 → 上传占位数据（占位数据以 float 数组形式给出）→ 解绑。
     *
     * @param gl                   GL 抽象层
     * @param initialCapacityBytes 初始容量（字节），小于 1 时按 1 处理，
     *                             非 4 的倍数时向上对齐到 4 的倍数
     */
    public VertexBuffer(GLAbstraction gl, int initialCapacityBytes) {
        this.gl = gl;
        // 占位数据以 float 数组给出，而 new float[capacityBytes / 4] 会截断小数部分，
        // 因此容量必须向上对齐到 4 的倍数：否则 capacityBytes 会高估实际分配量，
        // 恰好上传这么多字节时就会越界写入 GL 缓冲。
        this.capacityBytes = (Math.max(1, initialCapacityBytes) + 3) & ~3;
        this.vbo = gl.createVbo();
        gl.bindVbo(vbo);
        gl.uploadVboData(new float[this.capacityBytes / 4]);
        gl.bindVbo(0);
    }

    /**
     * 返回底层 VBO 的 ID。
     *
     * @return VBO 的 ID，扩容后会变为新的 ID
     */
    public int id() {
        return vbo;
    }

    /**
     * 返回当前缓冲容量（字节）。
     *
     * <p>容量始终为 4 的倍数，与底层实际分配量一致。
     *
     * @return 当前容量（字节）
     */
    public int capacityBytes() {
        return capacityBytes;
    }

    /**
     * 上传数据。若数据超过当前容量则先扩容。
     *
     * <p>上传完成后 VBO 绑定会恢复为 0。
     *
     * @param data 数据，position 为 0、limit 为有效字节数
     */
    public void upload(ByteBuffer data) {
        int needed = data.remaining();
        if (needed > capacityBytes) {
            grow(needed);
        }
        gl.bindVbo(vbo);
        gl.uploadVboBytes(data);
        gl.bindVbo(0);
    }

    /**
     * 重建一个容量翻倍直到能容纳 {@code neededBytes} 的新 VBO。
     *
     * <p>旧的 VBO 会被删除，{@link #id()} 随之改变。
     *
     * @param neededBytes 需要的总字节数
     */
    private void grow(int neededBytes) {
        int newCapacity = capacityBytes;
        while (newCapacity < neededBytes) {
            newCapacity *= 2;
        }
        // 同构造函数：分配量按 newCapacity / 4 个 float 计算，必须对齐到 4 的倍数，
        // 否则 capacityBytes 会高估实际分配量，恰好上传这么多字节时就会越界。
        newCapacity = (newCapacity + 3) & ~3;
        gl.deleteVbo(vbo);
        this.capacityBytes = newCapacity;
        this.vbo = gl.createVbo();
        gl.bindVbo(vbo);
        gl.uploadVboData(new float[newCapacity / 4]);
        gl.bindVbo(0);
    }

    /**
     * 释放底层 VBO。重复调用无副作用。
     */
    @Override
    public void dispose() {
        if (!disposed) {
            gl.deleteVbo(vbo);
            disposed = true;
        }
    }
}
