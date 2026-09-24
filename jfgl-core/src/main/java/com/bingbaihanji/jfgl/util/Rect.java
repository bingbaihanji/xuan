package com.bingbaihanji.jfgl.util;

import com.bingbaihanji.jfgl.math.Vec2;

/**
 * 2D 轴对齐矩形，由左上角坐标和尺寸定义。
 */
public class Rect {

    /** 左上角 x 坐标 */
    public float x;

    /** 左上角 y 坐标 */
    public float y;

    /** 矩形宽度 */
    public float width;

    /** 矩形高度 */
    public float height;

    /**
     * 创建一个新的矩形。
     *
     * @param x      左上角 x 坐标
     * @param y      左上角 y 坐标
     * @param width  宽度
     * @param height 高度
     */
    public Rect(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /**
     * 获取矩形的位置（左上角）。
     *
     * @return 位置向量
     */
    public Vec2 getPosition() {
        return new Vec2(x, y);
    }

    /**
     * 获取矩形的尺寸。
     *
     * @return 尺寸向量（宽度，高度）
     */
    public Vec2 getSize() {
        return new Vec2(width, height);
    }

    /**
     * 获取矩形右边界 x 坐标。
     *
     * @return 右边界 x 坐标
     */
    public float getRight() {
        return x + width;
    }

    /**
     * 获取矩形下边界 y 坐标。
     *
     * @return 下边界 y 坐标
     */
    public float getBottom() {
        return y + height;
    }

    /**
     * 获取矩形的中心点。
     *
     * @return 中心点向量
     */
    public Vec2 getCenter() {
        return new Vec2(x + width * 0.5f, y + height * 0.5f);
    }

    /**
     * 测试点是否在此矩形内。
     *
     * @param point 要测试的点
     * @return 如果点在矩形内则返回 {@code true}
     */
    public boolean contains(Vec2 point) {
        return contains(point.x(), point.y());
    }

    /**
     * 测试坐标是否在此矩形内。
     *
     * @param px x 坐标
     * @param py y 坐标
     * @return 如果坐标在矩形内则返回 {@code true}
     */
    public boolean contains(float px, float py) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }

    /**
     * 测试此矩形是否与另一个矩形相交。
     *
     * @param other 另一个矩形
     * @return 如果两个矩形相交则返回 {@code true}
     */
    public boolean intersects(Rect other) {
        return x < other.x + other.width
                && x + width > other.x
                && y < other.y + other.height
                && y + height > other.y;
    }

    /**
     * 返回此矩形向外扩展指定边距后的新矩形。
     *
     * @param margin 边距
     * @return 扩展后的新矩形
     */
    public Rect expand(float margin) {
        return new Rect(
                x - margin,
                y - margin,
                width + margin * 2,
                height + margin * 2
        );
    }

    @Override
    public String toString() {
        return "Rect{x=%.2f, y=%.2f, w=%.2f, h=%.2f}".formatted(x, y, width, height);
    }
}
