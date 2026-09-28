package com.bingbaihanji.xuan.math;

import java.util.Objects;

/**
 * 2D 变换，组合了位置、旋转和非均匀缩放。
 * <p>
 * 内部缓存一个 3x3 仿射矩阵，仅当组件发生变化时才重新计算（脏标记模式）。
 * <p>
 * 组合矩阵按 TRS 顺序应用变换：
 * {@code point_parent = T * R * S * point_local}。
 */
public class Transform {

    /** 位置 */
    private Vec2 position;

    /** 旋转角度（弧度） */
    private float rotation;

    /** 缩放因子 */
    private Vec2 scale;

    /** 缓存的变换矩阵 */
    private Mat3 matrix;

    /** 缓存的逆变换矩阵 */
    private Mat3 inverseMatrix;

    /** 脏标记 */
    private boolean dirty;

    /**
     * 创建一个单位变换（无平移、无旋转、无缩放）。
     */
    public Transform() {
        this.position = new Vec2(0.0f, 0.0f);
        this.rotation = 0.0f;
        this.scale = new Vec2(1.0f, 1.0f);
        this.matrix = Mat3.identity();
        this.inverseMatrix = Mat3.identity();
        this.dirty = false;
    }

    /**
     * 创建一个具有指定位置、旋转和缩放的变换。
     *
     * @param position 位置
     * @param rotation 旋转角度（弧度）
     * @param scale    缩放因子
     */
    public Transform(Vec2 position, float rotation, Vec2 scale) {
        this.position = Objects.requireNonNull(position, "position");
        this.rotation = rotation;
        this.scale = Objects.requireNonNull(scale, "scale");
        this.matrix = Mat3.identity();
        this.inverseMatrix = Mat3.identity();
        this.dirty = true;
    }

    // ---- 位置 ---------------------------------------------------------------

    /**
     * 获取位置。
     *
     * @return 位置向量
     */
    public Vec2 getPosition() {
        return position;
    }

    /**
     * 设置位置。
     *
     * @param position 新位置
     */
    public void setPosition(Vec2 position) {
        this.position = Objects.requireNonNull(position, "position");
        this.dirty = true;
    }

    /**
     * 设置位置。
     *
     * @param x x 坐标
     * @param y y 坐标
     */
    public void setPosition(float x, float y) {
        this.position = new Vec2(x, y);
        this.dirty = true;
    }

    // ---- 旋转 ---------------------------------------------------------------

    /**
     * 获取旋转角度。
     *
     * @return 旋转角度（弧度）
     */
    public float getRotation() {
        return rotation;
    }

    /**
     * 设置旋转角度。
     *
     * @param rotation 新的旋转角度（弧度）
     */
    public void setRotation(float rotation) {
        this.rotation = rotation;
        this.dirty = true;
    }

    // ---- 缩放 ---------------------------------------------------------------

    /**
     * 获取缩放因子。
     *
     * @return 缩放因子向量
     */
    public Vec2 getScale() {
        return scale;
    }

    /**
     * 设置缩放因子。
     *
     * @param scale 新的缩放因子
     */
    public void setScale(Vec2 scale) {
        this.scale = Objects.requireNonNull(scale, "scale");
        this.dirty = true;
    }

    /**
     * 设置缩放因子。
     *
     * @param sx x 方向缩放因子
     * @param sy y 方向缩放因子
     */
    public void setScale(float sx, float sy) {
        this.scale = new Vec2(sx, sy);
        this.dirty = true;
    }

    /**
     * 设置均匀缩放因子。
     *
     * @param s 统一的缩放因子
     */
    public void setUniformScale(float s) {
        this.scale = new Vec2(s, s);
        this.dirty = true;
    }

    // ---- 矩阵 ---------------------------------------------------------------

    /**
     * 获取缓存的仿射矩阵，仅当自上次调用后组件发生变化时才重新计算。
     *
     * @return 3x3 仿射变换矩阵 (T * R * S)
     */
    public Mat3 getMatrix() {
        ensureClean();
        return matrix;
    }

    /**
     * 确保矩阵是最新的。
     */
    private void ensureClean() {
        if (dirty) {
            // TRS 顺序：平移 * 旋转 * 缩放
            Mat3 s = Mat3.scale(scale.x(), scale.y());
            Mat3 r = Mat3.rotation(rotation);
            Mat3 t = Mat3.translation(position.x(), position.y());
            matrix = t.multiply(r).multiply(s);

            // 预计算逆矩阵：(T*R*S)^-1 = S^-1 * R^-1 * T^-1
            float sxInv = (float) (1.0 / scale.x());
            float syInv = (float) (1.0 / scale.y());
            Mat3 sInv = Mat3.scale(sxInv, syInv);
            Mat3 rInv = Mat3.rotation(-rotation);
            Mat3 tInv = Mat3.translation(-position.x(), -position.y());
            inverseMatrix = sInv.multiply(rInv).multiply(tInv);

            dirty = false;
        }
    }

    // ---- 点变换 -------------------------------------------------------------

    /**
     * 将点从局部空间变换到父空间。
     *
     * @param localPoint 局部坐标点
     * @return 父（世界）坐标点
     */
    public Vec2 transformPoint(Vec2 localPoint) {
        ensureClean();
        return matrix.transform(localPoint);
    }

    /**
     * 将点从父空间变换回局部空间。
     *
     * @param worldPoint 父（世界）坐标点
     * @return 局部坐标点
     */
    public Vec2 inverseTransform(Vec2 worldPoint) {
        ensureClean();
        return inverseMatrix.transform(worldPoint);
    }

    // ---- Object 重写 --------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Transform that)) {
            return false;
        }
        return Float.compare(rotation, that.rotation) == 0
                && Objects.equals(position, that.position)
                && Objects.equals(scale, that.scale);
    }

    @Override
    public int hashCode() {
        return Objects.hash(position, rotation, scale);
    }

    @Override
    public String toString() {
        return "Transform{position=%s, rotation=%f, scale=%s}"
                .formatted(position, rotation, scale);
    }
}
