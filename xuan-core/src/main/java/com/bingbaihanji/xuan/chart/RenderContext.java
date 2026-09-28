package com.bingbaihanji.xuan.chart;

/**
 * 渲染上下文：<strong>① 只声明这个类型存在，不声明它有什么</strong>。
 *
 * <h2>它是空的，而且必须保持空</h2>
 * <p>规格 §9：{@code RenderContext} <strong>不属于 ①</strong>。它是渲染后端（子项目 ②）
 * 提供的"给你一支笔"的上下文——顶点写入器、当前帧号、纹理槽位等等。
 * ① 只声明签名，② 给它血肉。这样 ① 保持零 GL 依赖，而 ② 可以自由决定上下文里放什么。
 *
 * <p><strong>为什么是空接口而不是 {@code Object} 占位</strong>：用 {@code Object} 的话，
 * ② 必须回头修改 {@link SeriesRenderer} 的签名，而那时 ② 已经落地了——
 * 「签名承诺」的全部意义就是让这件事不必发生。空接口让签名现在就终局：
 * ② 定义 {@code GLRenderContext extends RenderContext}，
 * 实现里做一次向下转型（它是唯一的实现方，类型在编译期就能查），
 * 转型被限制在每个渲染器的第一行。
 *
 * <p><strong>一个方法都不能加。</strong>这条由 {@code ChartPackageIsolationTest} 断言：
 * 在原地加方法等于把 ② 的东西漏进 ①。② 要加东西请加在子接口上；
 * 若确实需要给本接口加方法，那说明分界线画错了，应当停下来重新讨论。
 */
public interface RenderContext {
}
