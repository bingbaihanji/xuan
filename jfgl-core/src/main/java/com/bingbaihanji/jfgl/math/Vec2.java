package com.bingbaihanji.jfgl.math;

/**
 * 不可变的 2D 向量，具有浮点分量。
 */
public record Vec2(float x, float y) {

    /** 零向量 */
    public static final Vec2 ZERO = new Vec2(0f, 0f);

    /** 沿 x 轴的单位向量 */
    public static final Vec2 UNIT_X = new Vec2(1f, 0f);

    /** 沿 y 轴的单位向量 */
    public static final Vec2 UNIT_Y = new Vec2(0f, 1f);

    /**
     * 返回此向量与 {@code other} 的和。
     *
     * @param other 要相加的向量
     * @return this + other
     */
    public Vec2 add(Vec2 other) {
        return new Vec2(x + other.x, y + other.y);
    }

    /**
     * 返回此向量与 {@code other} 的差。
     *
     * @param other 要相减的向量
     * @return this - other
     */
    public Vec2 sub(Vec2 other) {
        return new Vec2(x - other.x, y - other.y);
    }

    /**
     * 返回此向量按给定因子缩放后的新向量。
     *
     * @param scalar 缩放因子
     * @return this * scalar
     */
    public Vec2 scale(float scalar) {
        return new Vec2(x * scalar, y * scalar);
    }

    /**
     * 返回此向量与 {@code other} 的点积。
     *
     * @param other 另一个向量
     * @return 点积结果
     */
    public float dot(Vec2 other) {
        return x * other.x + y * other.y;
    }

    /**
     * 返回此向量的长度（大小）。
     *
     * @return 欧几里得长度
     */
    public float length() {
        return (float) Math.sqrt(x * x + y * y);
    }

    /**
     * 返回此向量的长度的平方（避免开平方根）。
     *
     * @return 长度的平方
     */
    public float lengthSquared() {
        return x * x + y * y;
    }

    /**
     * 返回此向量的单位长度副本，如果是零向量则返回 {@link #ZERO}。
     *
     * @return 归一化后的向量
     */
    public Vec2 normalize() {
        float len = length();
        return len < 1e-6f ? ZERO : new Vec2(x / len, y / len);
    }

    /**
     * 返回此点与 {@code other} 之间的距离。
     *
     * @param other 另一个点
     * @return 欧几里得距离
     */
    public float distanceTo(Vec2 other) {
        return sub(other).length();
    }
}
