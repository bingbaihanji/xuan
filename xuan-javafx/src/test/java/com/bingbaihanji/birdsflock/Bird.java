package com.bingbaihanji.birdsflock;

/**
 * 鸟群个体：持有位置、速度与颜色。
 *
 * <p>位置与速度都是 public 的可变字段——Boids 算法每帧直接修改它们，
 * 不需要 getter/setter 的间接层。
 */
public class Bird {

    /** 颜色（ARGB 打包，与 {@code Gc.fill} 同格式）。 */
    public final int color;

    /** 位置 x（像素坐标，原点左上）。 */
    public float x;

    /** 位置 y（像素坐标，原点左上，y 向下）。 */
    public float y;

    /** 速度 x（像素/秒）。 */
    public float vx;

    /** 速度 y（像素/秒）。 */
    public float vy;

    public Bird(float x, float y, float vx, float vy, int color) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.color = color;
    }
}
