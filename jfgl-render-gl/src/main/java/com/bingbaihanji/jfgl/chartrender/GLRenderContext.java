package com.bingbaihanji.jfgl.chartrender;

import com.bingbaihanji.jfgl.chart.RenderContext;
import com.bingbaihanji.jfgl.chart.Series;
import com.bingbaihanji.jfgl.gl.GLAbstraction;
import com.bingbaihanji.jfgl.gl.ShaderProgram;

/**
 * 渲染一个系列时需要的全部东西。
 *
 * <p><strong>它必须是 {@link RenderContext} 的子接口，不能在 {@code RenderContext}
 * 原地加成员。</strong>{@code ChartPackageIsolationTest} 断言后者是空接口
 * （0 方法 / 0 字段 / 0 嵌套类型）——在原地加东西等于把 ② 的概念漏进 ①。
 *
 * <p>{@code SeriesRenderer} 的实现<b>第一行就向下转型</b>取它。
 *
 * <h2>这里只声明已经有人用的东西</h2>
 * <p>本接口只有折线渲染器一个消费者，所以这里只有它真正用到的那几项。
 * 拾取相关的成员（{@code pickShader} / {@code pickId} / {@code withPickPass}）
 * 是在 Task 13 <b>接拾取</b>时随消费者一起加进来的——
 * 预先声明一个没人实现也没人调用的方法，
 * 只会让"它到底有没有被验证过"变成一个说不清的问题。
 */
public interface GLRenderContext extends RenderContext {

    /** GL 抽象层。 */
    GLAbstraction gl();

    /** 折线族绘制用的着色器程序。 */
    ShaderProgram lineShader();

    /**
     * 散点绘制用的着色器程序。
     *
     * <p>与 {@link #lineShader()} 共用一份片段源码，只有顶点程序不同——
     * 一个实例的概念从"线段"变成了"点"（见 {@code SeriesShaders.SCATTER_VERTEX}）。
     */
    ShaderProgram scatterShader();

    /**
     * 折线族拾取用的着色器程序。绘制路径不设它，只有 ID pass 用。
     *
     * <p><b>不能用它做散点的 ID pass</b>：顶点程序决定实例的几何，而这个程序的顶点源码
     * 是 {@code LINE_VERTEX}——散点走它会画出一个"从数据值竖直拉到 0"的长条热区
     * （第二个实例属性没人喂、恒为 0），<b>画面完全正常，只有点击落在错误的对象上</b>。
     * 散点的 ID pass 用 {@link #scatterPickShader()}。
     */
    ShaderProgram pickShader();

    /**
     * 散点拾取用的着色器程序：{@code SCATTER_VERTEX} + {@code PICK_FRAGMENT}。
     *
     * <p>与 {@link #scatterShader()} 共用同一份顶点源码，因此绘制与拾取的几何
     * <b>逐顶点一致</b>（只差容差那一个 uniform）。理由见 {@link #pickShader()}。
     */
    ShaderProgram scatterPickShader();

    /**
     * 阶梯线绘制用的着色器程序（{@code STEP_VERTEX} + {@code LINE_FRAGMENT}）。
     *
     * <p>阶梯图的实例几何与折线<b>不</b>相同：一个实例是一条水平段加一个拐角
     * 再加一条竖直段（六个角）。所以它不能借用 {@link #lineShader()}——
     * 拿折线的顶点程序画阶梯会把它画成一条斜线，而画面完全正常。
     */
    ShaderProgram stepShader();

    /**
     * 阶梯线拾取用的着色器程序：{@code STEP_VERTEX} + {@code PICK_FRAGMENT}。
     *
     * <p>与 {@link #stepShader()} 共用同一份顶点源码（只差容差那一个 uniform），
     * 因此热区与画面逐顶点一致。理由见 {@link #pickShader()}。
     */
    ShaderProgram stepPickShader();

    /**
     * 面积图绘制用的着色器程序（{@code AREA_VERTEX} + {@code LINE_FRAGMENT}）。
     *
     * <p>面积图的轮廓线<b>不走这里</b>：那条线是普通折线，走
     * {@link #lineShader()}（由 {@code AreaSeriesRenderer} 显式委托折线路径画，
     * 理由见那个类）。本程序只画填充。
     */
    ShaderProgram areaShader();

    /** 面积图拾取用的着色器程序（{@code AREA_VERTEX} + {@code PICK_FRAGMENT}）。 */
    ShaderProgram areaPickShader();

    /** 柱状图绘制用的着色器程序（{@code BAR_VERTEX} + {@code LINE_FRAGMENT}）。 */
    ShaderProgram barShader();

    /**
     * 柱状图拾取用的着色器程序（{@code BAR_VERTEX} + {@code PICK_FRAGMENT}）。
     *
     * <p>细柱（缩得很小时不到一像素宽）同样要求用户点得中，所以 ID pass 用
     * {@code uPickTolerance} 把柱撑宽——与折线/散点同一条约定。
     */
    ShaderProgram barPickShader();

    /**
     * 当前柱状系列在<b>本层并排柱</b>里的序号。只对 {@link com.bingbaihanji.jfgl.chart.ChartType#BAR}
     * 有意义，其余图型为 0。
     *
     * <p>它由 {@code ChartRenderer} 按层算好（一层里的柱状系列并排成一组），
     * 渲染器自己<b>看不到兄弟系列</b>——{@code SeriesRenderer.render} 的签名里只有
     * 自己那一个系列。把"我是第几根"放在这里而不是让渲染器去猜，是因为猜出来的
     * 结果（全都当成第 0 根）会让同层多个柱状系列<b>完全重叠</b>：画面上只有最后
     * 画的那一个可见，而它看起来就是一张正常的单系列柱状图。
     */
    int barSlot();

    /**
     * 本层里并排的柱状系列总数（&ge; 1）。与 {@link #barSlot()} 成对使用。
     *
     * <p>它决定柱宽的分母：{@code n = 1} 时柱子占满整格，{@code n = 2} 时每根占一半。
     * 拿错的表现是<b>柱子宽度不对</b>——而宽度不对在单张图上没有任何参照物。
     */
    int barCount();

    /** 绘图区与映射。 */
    ChartRenderLayout layout();

    /** 帧缓冲宽度（设备像素）。 */
    int viewportWidth();

    /** 帧缓冲高度（设备像素）。 */
    int viewportHeight();

    /**
     * 取某系列的 GPU 常驻缓冲。
     *
     * <p>渲染器<b>不该自己持有它</b>——缓冲归 {@code ChartRenderer} 管，
     * 渲染器是无状态的纯函数（见 {@code SeriesRenderer} 的类文档）。
     *
     * @param series 当前正在渲染的系列
     * @return 该系列的常驻缓冲
     * @throws IllegalStateException 传进来的不是"当前系列"时（说明渲染器自己缓存了
     *                               跨系列的缓冲引用——那会画错，而且不报错）
     */
    SeriesBuffer bufferFor(Series series);

    /**
     * 当前系列的拾取 ID；<b>0 表示不参与拾取</b>（0 也是"什么都没命中"的返回值）。
     *
     * <p>ID 由 {@code ChartRenderer} 从 <b>{@code Gc} 的注册表</b>里取——
     * 不能另起一套，否则两套号会撞车，点击落在错误的对象上而画面正常。
     */
    int pickId();

    /**
     * 在拾取缓冲上执行一段绘制。转发给 {@code RenderBatch.withPickPass}——
     * 拾取缓冲与它那两个"本帧是否已清空/是否有效"的标志都归 {@code RenderBatch} 管，
     * 图表路径只是借用。
     *
     * @param body 要执行的绘制
     */
    void withPickPass(Runnable body);
}
