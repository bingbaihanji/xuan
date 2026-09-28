package com.bingbaihanji.xuan.renderer;

/**
 * 一次拾取查询的命中结果。
 *
 * @param id      命中的拾取 ID，恒不为 0
 * @param payload 该 ID 在 {@link PickRegistry} 中注册的对象；
 *                <strong>ID 已注册但载荷为 null，与 ID 未注册，都表现为 null</strong>
 * @param x       查询点 x（用户坐标，y 向下）
 * @param y       查询点 y（用户坐标，y 向下）
 *
 * <p><strong>关于 x/y</strong>：{@code Gc.pick} 返回的就是传入的查询点坐标；
 * {@code Gc.pickRect} 返回的是该 ID 在查询区域内<strong>按行扫描首次出现的像素坐标</strong>
 * （从区域左上角起、逐行向右），是确定性的、可复现的，便于调试与断言。
 * 它<strong>不是</strong>该图元的中心或任何几何代表点。
 *
 * <p><strong>payload 为 null 不代表没命中</strong>：ID 本身仍是真实的命中信息。
 * 静默降级成「未命中」会让人以为没点到。
 */
public record PickHit(int id, Object payload, float x, float y) {
}
