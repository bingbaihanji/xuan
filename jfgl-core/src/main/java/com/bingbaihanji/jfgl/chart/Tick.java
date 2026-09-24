package com.bingbaihanji.jfgl.chart;

/**
 * 一个刻度：值 + 位置 + 标签 + 级别。
 *
 * <h2>位置是"显示位置"，由映射函数算出来</h2>
 * <p>{@code position} 的单位由调用方给的映射决定：{@link Axis} 传进去的是它自己的
 * {@code dataToDisplay}，于是 position 就是像素（或者说，是这个轴上的一维位置）。
 * <strong>刻度生成器不自己算映射</strong>——那会变成第二份"值 → 位置"的实现，
 * 两边一旦不同步，症状是"刻度线与网格/数据对不上"，而两边各自都"是对的"。
 *
 * @param value    数据值
 * @param position 显示位置（由调用方给的映射函数算出）
 * @param label    标签；中/次刻度是空串（不是 null）
 * @param level    级别：{@link #MAJOR} / {@link #MEDIUM} / {@link #MINOR}
 */
public record Tick(double value, double position, String label, int level) {

    /** 主刻度：最长的那根，也是唯一带标签的。 */
    public static final int MAJOR = 0;

    /** 中刻度。 */
    public static final int MEDIUM = 1;

    /** 次刻度：最短的那根。 */
    public static final int MINOR = 2;

    public Tick {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("刻度值必须是有限数：" + value);
        }
        if (!Double.isFinite(position)) {
            throw new IllegalArgumentException("刻度位置必须是有限数：" + position);
        }
        if (label == null) {
            throw new IllegalArgumentException("标签不能为 null（没有标签就传空串）");
        }
        if (level < MAJOR || level > MINOR) {
            throw new IllegalArgumentException("级别只能是 0/1/2，实际为 " + level);
        }
    }

    /** 是否为主刻度。 */
    public boolean isMajor() {
        return level == MAJOR;
    }
}
