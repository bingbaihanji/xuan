package com.bingbaihanji.jfgl.chart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一层：一组 {@link Series}，按添加顺序绘制。
 *
 * <p>层的意义是<strong>整体控制</strong>（例如底图整层调暗、整层隐藏），
 * 而不是"分组"——z 序仍然由添加顺序决定，与层内顺序是同一条规则。
 */
public final class Layer {

    private final String name;

    private final List<Series> series = new ArrayList<>();

    /**
     * 构造。
     *
     * @param name 层名，不能为空
     * @throws IllegalArgumentException 层名为空时
     */
    public Layer(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("图层名不能为空");
        }
        this.name = name;
    }

    /** 层名。 */
    public String name() {
        return name;
    }

    /**
     * 往这一层里加一个系列。添加顺序即绘制顺序。
     *
     * @param s 系列，不能为 null
     * @return 自身，便于链式调用
     * @throws IllegalArgumentException 系列为 null，或<strong>同一个系列已经在层里</strong>时
     */
    public Layer add(Series s) {
        if (s == null) {
            throw new IllegalArgumentException("系列不能为 null");
        }
        if (series.contains(s)) {
            throw new IllegalArgumentException(
                    "系列「" + s.name() + "」已经在这个图层里了：再加一次会被画两遍——"
                            + "在带透明度的图元上表现为颜色莫名变深，而且没有任何报错");
        }
        series.add(s);
        return this;
    }

    /**
     * 返回层内的系列，按绘制顺序。
     *
     * @return 不可修改的列表
     */
    public List<Series> series() {
        return Collections.unmodifiableList(series);
    }
}
