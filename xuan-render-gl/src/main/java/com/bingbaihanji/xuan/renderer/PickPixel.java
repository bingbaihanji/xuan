package com.bingbaihanji.xuan.renderer;

/**
 * 拾取缓冲里一个非零像素：命中的 ID 与它的坐标。
 *
 * <p>由 {@link PickBuffer#readRect} 产生，表示某个 ID 在查询区域内
 * <strong>按行扫描首次出现</strong>的那个像素。
 *
 * @param id 该像素的拾取 ID，恒不为 0
 * @param x  用户坐标 x（y 向下）
 * @param y  用户坐标 y（y 向下）
 */
public record PickPixel(int id, int x, int y) {
}
