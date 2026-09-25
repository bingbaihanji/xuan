package com.bingbaihanji.jfgl.geom;

import java.util.Arrays;

/**
 * 把折线转成描边轮廓的三角形列表。
 *
 * <p>输出格式与 {@link Tessellator} 一致：每 6 个 float 一个三角形，
 * 依次为 {@code x0,y0,x1,y1,x2,y2}。生成结果是覆盖描边面积的一组三角形，
 * 调用方按普通三角形批次上传即可，无需 GL 线图元。
 *
 * <p>除了位置，本类还<strong>平行</strong>输出一份逐顶点的抗锯齿边距
 * （{@link #rawEdges()}，每顶点 {@code (横向, 沿向)}）：位置仍然是
 * {@link #triangles()} / {@link #rawTriangles()} 的布局，边距是另一个数组，
 * 两者逐顶点一一对应。边距的语义见 {@link #rawEdges()}。
 *
 * <p><strong>关于非均匀缩放</strong>：本类在<strong>局部空间</strong>生成轮廓，
 * 由调用方在烘焙变换时处理缩放。因此 {@code scale(2,1)} 下圆的描边会正确变成椭圆环，
 * 而不是一段宽度不均的环带。本类不做任何缩放补偿。
 *
 * <p><strong>已知限制</strong>：凹角处相邻段的偏移轮廓会自相交，产生重叠三角形。
 * 配合预乘 alpha，不透明描边无可见影响；半透明描边会出现颜色叠加。
 *
 * <p><strong>MITER 接头补的是完整那一块</strong>：顶点、两个偏移点、尖角围成的风筝形四边形
 * （两个三角形）。因此 90° 直角处填满的恰好是半线宽见方的角块，浅转角处几乎没有缝隙
 * ——miter 长度按 {@code half / sin(内角/2)} 增长，**内角越小（转角越尖）越长**，
 * 超过 miter limit 才退化成 {@link Join#BEVEL}。
 *
 * <p><strong>180° 折回</strong>（折线原路折返、反向共线）处两段轮廓完全重合，覆盖面积本就不缺。
 * 此处 {@link Join#MITER} 与 {@link Join#BEVEL} 不生成接头（三角形退化为零面积），
 * {@link Join#ROUND} 则在尖端补出一个半径等于半线宽的半圆——这才是圆角接头应有的外形，
 * 代价是该拐点额外产生 {@code roundSegments} 个三角形（默认 8 个）。只有选择 ROUND 才付这份代价。
 *
 * <p><strong>零长度段</strong>：长度小于 {@code 1e-6} 的段会被跳过（不产生三角形，
 * 也不会出现除零），其两侧的接头与端点会自动落到最近的有效段上。但重复顶点过多时
 * （整条折线退化）不会产生任何三角形。所有输出顶点都是有限值，不会出现 NaN/Infinity。
 *
 * <p>本类不依赖 {@code gl} 包，可脱离 GL 上下文进行单元测试。
 */
public final class StrokeGenerator {

    /** 未指定细分段数时，圆端点与圆角接头使用的默认段数。 */
    private static final int DEFAULT_ROUND_SEGMENTS = 8;

    /**
     * {@link #strokeDashed} 切分实线格时复用的端点对 {@code [ax,ay,bx,by]}。
     * <p>
     * 做成字段而不是局部变量，是为了让「不分配临时对象」的承诺成立：虚线每一格都要生成一次轮廓，
     * 每帧调用一次 {@code strokeDashed} 却按格分配数组的话，热路径就在持续制造垃圾。
     */
    private final float[] dashSegment = new float[4];

    /** 输出三角形顶点数组，布局：每个三角形 6 个 float（x0,y0,x1,y1,x2,y2）。 */
    private float[] triangles = new float[3 * 6 * 4];

    /** 已写入的三角形数量。 */
    private int triangleCount = 0;

    /**
     * 与 {@link #triangles} <strong>逐顶点平行</strong>的抗锯齿边距：每个三角形 6 个 float，
     * 依次是三个顶点的 {@code (横向, 沿向)}。
     *
     * <p><strong>两个数组的长度恒等</strong>，扩容时一起翻倍：一个三角形在
     * {@code triangles} 里占 6 个 float（3 顶点 × 2 个坐标），在这里同样占 6 个 float
     * （3 顶点 × 2 个分量）。有效数据是前 {@code triangleCount * 6} 个 float。
     *
     * <p><strong>为什么两者要分开存</strong>：{@code triangles} 是"位置"的唯一真相，
     * 已有的读取方（{@code Gc} 按 6 个 float 一个三角形遍历它）已经把那个布局当契约；
     * 把边距缠进同一个数组会改掉那个契约，而契约的另一头还有 {@code PathVerifier} 在跑。
     * 因此边距必须是<strong>另一个</strong>数组，按同一个下标口径对齐。
     *
     * <p><strong>⚠ 声明顺序是契约</strong>：初值取自 {@code triangles.length}，所以本字段
     * <strong>必须声明在 {@code triangles} 之后</strong>（Java 按声明顺序初始化实例字段）。
     * 调换顺序的话 {@code triangles} 此刻还是 {@code null}，这里会直接 NPE 而不是静默变小；
     * 但若有人把初值改成字面量又把扩容写成"按 {@code edges.length * 2}"而两个数组用不同
     * 判据，就会在第一次扩容后越界——两个数组一起翻倍（见 {@link #emitTriangle}）那条
     * 才是应当保持的写法。
     */
    private float[] edges = new float[triangles.length];

    /**
     * 找到第一个非退化段的下标，用于闭合路径的首尾接头。
     *
     * @return 第一个长度不为零的段下标；全部退化时返回 {@code -1}
     */
    private static int firstValidSegment(float[] points, int count, int segmentCount) {
        for (int i = 0; i < segmentCount; i++) {
            int b = (i + 1) % count;
            float dx = points[b * 2] - points[i * 2];
            float dy = points[b * 2 + 1] - points[i * 2 + 1];
            if (dx * dx + dy * dy > 1e-12f) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 返回已生成的三角形数量。
     *
     * @return 三角形个数
     */
    public int triangleCount() {
        return triangleCount;
    }

    /**
     * 返回已生成三角形的顶点副本。
     *
     * <p>返回值只包含有效部分，长度为 {@code triangleCount() * 6}；
     * 每次调用都分配并复制一个新数组，热路径上请改用 {@link #rawTriangles()}。
     *
     * @return 顶点数组副本，布局为每个三角形 6 个 float
     */
    public float[] triangles() {
        return Arrays.copyOf(triangles, triangleCount * 6);
    }

    /**
     * 返回内部三角形数组本身，<strong>不复制</strong>。每 6 个 float 一个三角形
     * （{@code x0,y0,x1,y1,x2,y2}），有效数据是前 {@code triangleCount() * 6} 个 float，
     * 后面是上一次调用遗留的无效数据。
     *
     * <p>给热路径用：调用方可以直接遍历这 {@code count * 6} 个 float 写顶点，
     * 省掉一次整表复制。作为代价，数组长度通常<strong>大于</strong>有效数据长度，
     * 千万不要把整个数组当成三角形列表。
     *
     * <p><strong>不得保留</strong>：这是内部缓冲，内容只在下一次 {@link #reset()} /
     * {@link #stroke} / {@link #strokeDashed} 调用之前有效，且扩容时会换一块新数组。
     * 需要稳定副本请用 {@link #triangles()}。
     *
     * @return 内部三角形数组（数组长度 ≥ {@code triangleCount() * 6}）
     */
    public float[] rawTriangles() {
        return triangles;
    }

    /**
     * 返回内部边距数组本身，<strong>不复制</strong>。约定与 {@link #rawTriangles()} 逐条相同：
     * 每顶点 2 个 float（{@code 横向,沿向}），每 3 个顶点一个三角形，
     * 有效数据是前 {@code triangleCount() * 6} 个 float，后面是上一次调用遗留的无效数据。
     *
     * <p>第 {@code i} 个三角形的 3 个顶点，在 {@link #rawTriangles()} 里是
     * {@code [6i, 6i+6)} 的 {@code x,y} 对，在这里是 {@code [6i, 6i+6)} 的
     * {@code 横向,沿向} 对——<strong>两个数组逐顶点一一对应</strong>。
     *
     * <p><strong>两个分量的语义</strong>（与顶点格式里的 {@code aEdge} 一致）：
     * <ul>
     *   <li><strong>横向</strong>：到描边中心线的有符号距离，归一化到真实半线宽，
     *       {@code ±1} 正是两条真实外缘，{@code +} 在
     *       {@code n = (-dy/len, dx/len)} 那一侧（即该段行进方向的左法线侧）。
     *       <strong>接头（{@link #emitJoin} 发射的那几个三角形）是例外：横向一律 0</strong>
     *       ——不是"距离为 0"，而是"这一维在这里不够用"的显式取值，让片元走
     *       "完全覆盖"分支。理由见 {@code emitJoin} 的注释（取 ±1 会在抗锯齿下把拐角啃掉）。</li>
     *   <li><strong>沿向</strong>：到最近端帽的沿路径距离，同样除以半线宽。
     *       <strong>{@code 0} 就是端帽线：带内为正，带外为负</strong>——
     *       片段着色器正是靠这个符号区分"在线内"与"在线外"。
     *       开放路径取 {@code min(到起点, 到终点)} ⇒ 端线处恰为 0、中段最大
     *       （最大值 = {@code totalLength/2/half}）；
     *       闭合路径没有端帽，取 {@code 弧长 + 全长}，从而<strong>没有靠近 0 的顶点</strong>
     *       （否则会在路径起点凭空造出一条羽化边）。
     *       端帽几何（SQUARE / ROUND 与外扩四边形）自己的顶点沿向可以小于 0——见
     *       {@link #emitCap}。
     *       <p><strong>"带外"只能由 {@code capExtension} 显式要，不能靠折线形状碰运气</strong>：
     *       把折线两端各延长一点是<strong>没用</strong>的——延长点会成为所传折线的弧长端点，
     *       {@code min(arc, totalLength - arc)} 在那里恒为 0，而真实端线反而拿到
     *       {@code +e/half}（实测两点线两端各延长 1 局部单位后，沿向集合是 {@code {0.0, 0.2}}，
     *       一个负值都没有）。根因是任何折线的顶点弧长都不可能超过它自己所在折线的全长。
     *       要用带外的负值，请传 {@code capExtension}。</li>
     * </ul>
     *
     * <p><strong>不变量</strong>：所有值都是有限值，不会出现 {@code NaN} / {@code Infinity}。
     * 这一点与位置数组同源——边距只由位置的算术得出，位置有限则边距有限。
     *
     * <p><strong>不得保留</strong>：与 {@link #rawTriangles()} 同一条约定，这是内部缓冲，
     * 只在下一次 {@link #reset()} / {@link #stroke} / {@link #strokeDashed} 之前有效，
     * 扩容时会换一块新数组。
     *
     * @return 内部边距数组（数组长度 = {@code rawTriangles().length} ≥ {@code triangleCount() * 6}）
     */
    public float[] rawEdges() {
        return edges;
    }

    /**
     * 清空已生成的三角形，保留已分配的数组容量。
     *
     * <p>与 {@link #stroke} 不同，本方法只把计数归零，因此实例可在热路径上反复复用。
     */
    public void reset() {
        triangleCount = 0;
    }

    /**
     * 使用默认的圆角细分段数生成描边轮廓。
     *
     * @param points     扁平折线顶点数组，布局为 {@code x0,y0,x1,y1,...}
     * @param count      顶点个数
     * @param closed     是否闭合
     * @param width      线宽（局部空间单位）
     * @param cap        端点样式
     * @param join       接头样式
     * @param miterLimit miter 接头超限后回退为 bevel 的阈值（相对于半线宽）
     */
    public void stroke(float[] points, int count, boolean closed, float width,
                       Cap cap, Join join, float miterLimit) {
        stroke(points, count, closed, width, cap, join, miterLimit, DEFAULT_ROUND_SEGMENTS);
    }

    /**
     * 生成描边轮廓，结果写入本实例（会清空上一次的结果）。
     *
     * @param points        扁平折线顶点数组，布局为 {@code x0,y0,x1,y1,...}
     * @param count         顶点个数
     * @param closed        是否闭合；闭合路径不生成端点封口，并在首尾之间补接头
     * @param width         线宽（局部空间单位）
     * @param cap           端点样式
     * @param join          接头样式
     * @param miterLimit    miter 接头超限后回退为 bevel 的阈值（相对于半线宽）
     * @param roundSegments 圆端点与圆角接头的细分段数
     */
    public void stroke(float[] points, int count, boolean closed, float width,
                       Cap cap, Join join, float miterLimit, int roundSegments) {
        stroke(points, count, closed, width, cap, join, miterLimit, roundSegments, 0f);
    }

    /**
     * 生成描边轮廓，并把端帽沿向外扩出 {@code capExtension}（结果写入本实例）。
     *
     * <p><strong>为什么要外扩</strong>：沿向边距的 {@code 0} 等值线落在端帽线上，
     * 而端帽<strong>外侧</strong>没有任何几何，于是端线外侧那片像素拿不到沿向梯度
     * （{@code wa = 0} ⇒ 着色器走"完全覆盖"分支）——一条 4px 横线的两端就成了硬角。
     * 这里发出的正是一圈<strong>从端线向外到 {@code -capExtension/half}</strong> 的四边形：
     * 它让沿向在端线两侧都有梯度，端帽因此真的能羽化。
     *
     * <p><strong>⚠ 它必须配合着色器的沿向羽化</strong>：这圈几何在顶点色上是满不透明的，
     * 让它在端线外侧变透明是<strong>着色器</strong>的责任（按沿向算覆盖率）。
     * 在着色器落地之前调用它，只会把描边画长 {@code capExtension}——所以默认值是 {@code 0}，
     * 既有调用点与既有行为完全不受影响。
     *
     * <p><strong>不要用"把折线两端各延长一点"来替代它</strong>：延长点会成为所传折线的
     * 弧长端点，{@code min(arc, totalLength - arc)} 在那里恒为 0，而真实端线反而拿到
     * {@code +e/half}——实测两端各延长 1 局部单位后，沿向集合是 {@code {0.0, 0.2}}，
     * <strong>一个负值都没有</strong>，端线外侧照样是 {@code ≥ 0.5} ⇒ 端帽照样不羽化，
     * 同时还把描边实打实地画长了。任何折线的顶点弧长都不可能超过它自己所在折线的全长，
     * 所以"靠折线形状碰运气"这条路根本不成立。
     *
     * @param points        扁平折线顶点数组，布局为 {@code x0,y0,x1,y1,...}
     * @param count         顶点个数
     * @param closed        是否闭合
     * @param width         线宽（局部空间单位）
     * @param cap           端点样式；<strong>只有 {@link Cap#BUTT} 会外扩</strong>
     *                      （SQUARE / ROUND 自己就是端帽几何，再外扩会画到端帽之外）
     * @param join          接头样式
     * @param miterLimit    miter 接头超限后回退为 bevel 的阈值（相对于半线宽）
     * @param roundSegments 圆端点与圆角接头的细分段数
     * @param capExtension  端帽外扩量（局部空间单位，{@code 0} = 不外扩）。
     *                      调用方（{@code Gc}）应传"1 设备像素折成的局部单位数"
     * @see #stroke(float[], int, boolean, float, Cap, Join, float, int)
     *
     * <p><strong>⚠ 重载陷阱</strong>：{@code roundSegments} 是 {@code int}、{@code capExtension}
     * 是 {@code float}，而 {@code int} 可以加宽成 {@code float}——所以
     * {@code stroke(p, n, closed, w, cap, join, lim, 8, 16)} 里的 {@code 16} 会被当成
     * <strong>capExtension</strong>（{@code roundSegments} 仍是 8），编译通过、毫无提示。
     * 只想改细分段数请用 8 参重载；两个都要传就把它们都写全（{@code …, 16, 0f}）。
     */
    public void stroke(float[] points, int count, boolean closed, float width,
                       Cap cap, Join join, float miterLimit, int roundSegments,
                       float capExtension) {
        stroke(points, count, closed, width, cap, join, miterLimit, roundSegments,
                capExtension, width * 0.5f);
    }

    /**
     * 生成描边轮廓，并让**接头**用另一个半线宽 {@code joinHalf} 生成（结果写入本实例）。
     *
     * <p><strong>为什么接头要与其余几何分开一个半宽</strong>：调用方（{@code Gc}）为了
     * 给描边的长边留出外侧片元，会把 {@code width} **加宽 1 个设备像素**——那是对的，
     * 窄的那一圈会被着色器按覆盖率羽化掉。但接头不同：它的横向边距**恒为 0**
     * （见 {@link #emitJoin}），也就是说它**不会羽化**，是整块不透明的。
     * 外扩量加在它身上不会被羽化掉，只会让拐角**实打实地向外多画一圈**（实测约 1 个像素）。
     * 因此接头要用**真实**半线宽生成：它的风筝形外缘恰好落在真实轮廓上。
     *
     * <p>{@code joinHalf} 与 {@code width} 的关系只影响接头：段四边形、端帽、
     * 以及沿向的归一化都仍用 {@code width / 2}。相邻段四边形被外扩而接头不被外扩，
     * 两者的并集恰好是"真实墨迹 + 该有的 fringe"——交界处不会留缝。
     *
     * <p>{@code miterLimit} 是相对于 {@code joinHalf} 的（尖角的长度本来就是拿它量出来的）。
     *
     * @param points        扁平折线顶点数组，布局为 {@code x0,y0,x1,y1,...}
     * @param count         顶点个数
     * @param closed        是否闭合
     * @param width         线宽（局部空间单位），决定段四边形与端帽
     * @param cap           端点样式
     * @param join          接头样式
     * @param miterLimit    miter 接头超限后回退为 bevel 的阈值（相对于 {@code joinHalf}）
     * @param roundSegments 圆端点与圆角接头的细分段数
     * @param capExtension  端帽外扩量（局部空间单位，{@code 0} = 不外扩）
     * @param joinHalf      接头使用的半线宽（局部空间单位）；传"真实半线宽"可让接头
     *                      不被 {@code width} 里的外扩量撑大。不传时等于 {@code width / 2}
     * @see #stroke(float[], int, boolean, float, Cap, Join, float, int, float)
     */
    public void stroke(float[] points, int count, boolean closed, float width,
                       Cap cap, Join join, float miterLimit, int roundSegments,
                       float capExtension, float joinHalf) {
        reset();
        generateOutline(points, count, closed, width, cap, join, miterLimit,
                roundSegments, capExtension, joinHalf);
    }

    /**
     * 生成虚线描边轮廓，结果写入本实例（会清空上一次的结果）。
     *
     * <p>实现方式：沿折线按 dash 模式累计弧长切分出实线段，再逐段生成描边。
     * 相位在整条折线上连续推进，折线拐角处的虚线不会重新起算。
     *
     * <p>每一格实线的描边直接追加进本实例（见 {@link #generateOutline}），
     * 不分配临时对象，也不复制已有顶点，因此可在每帧的热路径上调用。
     *
     * <p><strong>每一格的沿向恒为 0</strong>：每格都是两点开放折线，而
     * {@code min(arc, totalLength - arc)} 在两点折线的<strong>两端都是 0</strong>
     * ——也就是说虚线格子里没有任何沿向梯度（{@code wa = 0}），端帽羽化只有靠
     * {@code capExtension} 外扩那一圈几何才做得出来（见本类的
     * {@code strokeDashed(…, capExtension)} 重载）。当前 {@code Gc} 不暴露虚线描边，
     * 所以这一条暂时没有生产后果。
     *
     * @param points        扁平折线顶点数组，布局为 {@code x0,y0,x1,y1,...}
     * @param count         顶点个数
     * @param closed        是否闭合
     * @param width         线宽（局部空间单位）
     * @param cap           端点样式，通常为 {@link Cap#BUTT}
     * @param join          接头样式
     * @param miterLimit    miter 接头超限后回退为 bevel 的阈值（相对于半线宽）
     * @param dashPattern   虚线模式，偶数下标为实线长度、奇数下标为空白长度；
     *                      为 {@code null} 或空数组时等价于实线，总长为零时不产生三角形
     * @param dashPhase     起始相位，会先归一化到 {@code [0, 模式总长)}
     * @param roundSegments 圆端点与圆角接头的细分段数
     */
    public void strokeDashed(float[] points, int count, boolean closed, float width,
                             Cap cap, Join join, float miterLimit,
                             float[] dashPattern, float dashPhase, int roundSegments) {
        strokeDashed(points, count, closed, width, cap, join, miterLimit,
                dashPattern, dashPhase, roundSegments, 0f);
    }

    /**
     * 生成虚线描边轮廓，并把每一格实线的端帽沿向外扩 {@code capExtension}。
     *
     * <p><strong>每一格都是独立的开放两点折线</strong>，所以它的两个端点各自就是一条端线，
     * 沿向在整格上是常量 {@code 0}（{@code min(arc, totalLength - arc)} 在两点折线的两端
     * 都是 0）——外扩四边形（见 {@link #stroke(float[], int, boolean, float, Cap, Join,
     * float, int, float)}）正是让这些格子两端能羽化的唯一手段。
     *
     * <p><strong>⚠ 当前没有消费者</strong>：{@code Gc} 不暴露虚线描边
     * （{@code StrokeGenerator} 的虚线能力有单测，但 {@code Gc} 的描边路径只调实线）。
     *
     * @see #stroke(float[], int, boolean, float, Cap, Join, float, int, float)
     */
    public void strokeDashed(float[] points, int count, boolean closed, float width,
                             Cap cap, Join join, float miterLimit,
                             float[] dashPattern, float dashPhase, int roundSegments,
                             float capExtension) {
        if (dashPattern == null || dashPattern.length == 0) {
            // 无模式即实线
            stroke(points, count, closed, width, cap, join, miterLimit, roundSegments, capExtension);
            return;
        }
        float patternLength = 0f;
        for (float d : dashPattern) {
            patternLength += d;
        }
        if (patternLength <= 1e-6f) {
            // 全零模式：没有任何实线格
            reset();
            return;
        }

        reset();
        int segmentCount = closed ? count : count - 1;
        if (count < 2 || width <= 0f || segmentCount < 1) {
            return;
        }
        int patternCount = dashPattern.length;

        // 相位归一化到 [0, patternLength)，再定位到起始模式格；相位小于总长，故最多走完一轮
        float consumed = dashPhase % patternLength;
        if (consumed < 0f) {
            consumed += patternLength;
        }
        int patternIndex = 0;
        for (int i = 0; i < patternCount
                && consumed >= dashPattern[patternIndex % patternCount] - 1e-6f; i++) {
            consumed -= dashPattern[patternIndex % patternCount];
            patternIndex++;
        }
        if (consumed < 0f) {
            consumed = 0f;
        }

        // 复用同一对端点数组（实例字段，跨调用也不分配），逐格追加；
        // patternIndex/consumed 跨段连续，不按段重置
        final float[] seg = dashSegment;
        for (int i = 0; i < segmentCount; i++) {
            int b = (i + 1) % count;
            float ax = points[i * 2], ay = points[i * 2 + 1];
            float bx = points[b * 2], by = points[b * 2 + 1];
            float dx = bx - ax, dy = by - ay;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-6f) {
                continue;
            }
            float ux = dx / len, uy = dy / len;

            float cursor = 0f;
            while (cursor < len) {
                float dashLen = dashPattern[patternIndex % patternCount];
                if (dashLen <= 1e-6f) {
                    // 零长度格不占用弧长，直接跳到下一格
                    consumed = 0f;
                    patternIndex++;
                    continue;
                }
                if (consumed >= dashLen - 1e-6f) {
                    // 当前格在本段之前已经走完，进入下一格
                    consumed = 0f;
                    patternIndex++;
                    continue;
                }
                float step = Math.min(dashLen - consumed, len - cursor);
                if (step <= 1e-6f) {
                    break;
                }
                if ((patternIndex % 2) == 0) {
                    seg[0] = ax + ux * cursor;
                    seg[1] = ay + uy * cursor;
                    seg[2] = ax + ux * (cursor + step);
                    seg[3] = ay + uy * (cursor + step);
                    // 虚线每一格都是**两点**开放折线：没有拐点，joinHalf 在这里没有用武之地，
                    // 传 width/2 让它与 9 参重载的行为逐位一致。
                    generateOutline(seg, 2, false, width, cap, join, miterLimit,
                            roundSegments, capExtension, width * 0.5f);
                }
                cursor += step;
                consumed += step;
                if (consumed >= dashLen - 1e-6f) {
                    consumed = 0f;
                    patternIndex++;
                }
            }
        }
    }

    /**
     * 生成描边轮廓并追加到当前三角形列表，<strong>不</strong>清空已有结果。
     *
     * <p>这是 {@link #stroke} 与 {@link #strokeDashed} 共用的核心：虚线需要把每一格
     * 依次追加到同一个实例上，因此拆出这个不调用 {@link #reset()} 的版本，
     * 避免为每格分配临时实例、也避免复制已生成的顶点。
     *
     * @param points        扁平折线顶点数组，布局为 {@code x0,y0,x1,y1,...}
     * @param count         顶点个数
     * @param closed        是否闭合；闭合路径不生成端点封口，并在首尾之间补接头
     * @param width         线宽（局部空间单位）
     * @param cap           端点样式
     * @param join          接头样式
     * @param miterLimit    miter 接头超限后回退为 bevel 的阈值（相对于半线宽）
     * @param roundSegments 圆端点与圆角接头的细分段数
     * @param capExtension  端帽外扩量（局部空间单位，{@code 0} = 不外扩）；
     *                      只对 {@link Cap#BUTT} 且非闭合路径生效，见
     *                      {@link #emitCap}
     * @param joinHalf      接头使用的半线宽；只影响 {@link #emitJoin} 的几何与
     *                      miter 阈值，其余（段四边形、端帽、沿向归一化）都用
     *                      {@code width / 2}。理由见 10 参的
     *                      {@link #stroke(float[], int, boolean, float, Cap, Join,
     *                      float, int, float, float)}
     */
    private void generateOutline(float[] points, int count, boolean closed, float width,
                                 Cap cap, Join join, float miterLimit, int roundSegments,
                                 float capExtension, float joinHalf) {
        if (count < 2 || width <= 0f) {
            return;
        }
        float half = width * 0.5f;
        int segmentCount = closed ? count : count - 1;
        if (segmentCount < 1) {
            return;
        }

        // 沿向边距要用到总弧长：开放路径取 min(到起点, 到终点)，闭合路径取 弧长+全长。
        // 前者让"远离两端"的顶点沿向很大（覆盖率恒 1），后者保证闭合路径**没有**靠近 0 的
        // 顶点——否则会在路径起点凭空造出一条羽化边。
        // 退化段（长度 < 1e-6）在这里也被累加，而主循环会把它们跳过——这是两个累加器唯一的
        // 分叉点：totalLength 含退化段、arc 不含。两者的差因此有上界
        // （退化段数 × 1e-6），落到沿向上是 (退化段数 × 1e-6)/half；远小于一个像素的
        // 覆盖率变化，所以这里不必像主循环那样跳过它们（跳过了反而要多一趟判断）。
        float totalLength = 0f;
        for (int i = 0; i < segmentCount; i++) {
            int b = (i + 1) % count;
            float ex = points[b * 2] - points[i * 2];
            float ey = points[b * 2 + 1] - points[i * 2 + 1];
            totalLength += (float) Math.sqrt(ex * ex + ey * ey);
        }
        // 当前段的**起点**在整条路径上的弧长。主循环每处理完一段推进一次，
        // 于是接头取到的正好是它所在拐点的弧长，段四边形取到的是两端的弧长。
        float arc = 0f;

        // 上一段有效段的终点与方向，用于补接头（重复顶点会被跳过，接头要落到有效段之间）
        float prevDx = 0f, prevDy = 0f;
        boolean hasPrev = false;
        boolean capStartDone = false;
        float lastX = 0f, lastY = 0f, lastDx = 0f, lastDy = 0f;
        // 最后一段有效段终点处的沿向，终点封口要用它当"端线"的基准——
        // 端线外侧那圈外扩四边形与它所贴的段四边形共用同一条几何线，沿向必须取同一个值，
        // 否则接缝两侧各插各的，会在那一圈里插出不连续。
        float lastAlong = 0f;

        for (int i = 0; i < segmentCount; i++) {
            int a = i;
            int b = (i + 1) % count;
            float ax = points[a * 2], ay = points[a * 2 + 1];
            float bx = points[b * 2], by = points[b * 2 + 1];
            float dx = bx - ax, dy = by - ay;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-6f) {
                continue;
            }
            float nx = -dy / len * half;
            float ny = dx / len * half;

            // 本段两端的沿向
            float aStart = alongAt(arc, totalLength, half, closed);
            float aEnd = alongAt(arc + len, totalLength, half, closed);

            // 与上一有效段之间补接头
            // ⚠ 接头拿的是 joinHalf（不是 half）：它的横向恒为 0、不会羽化，
            //   外扩量加在它身上只会让拐角实打实地多画一圈。见 10 参的 stroke 重载。
            if (hasPrev) {
                emitJoin(ax, ay, prevDx, prevDy, dx, dy, joinHalf, aStart,
                        join, miterLimit, roundSegments);
            }
            // 起点封口落在第一段有效段上
            if (!closed && !capStartDone) {
                emitCap(ax, ay, -dx, -dy, half, cap, roundSegments, aStart, capExtension);
                capStartDone = true;
            }

            // 每段是一个四边形（两个三角形）。
            // 横向：+n 侧恒为 +1、-n 侧恒为 -1（n 是本段行进方向的左法线）；
            // 沿向：两端各取自己那一端的弧长。**端帽附近**这样插值出来的沿向就等于
            // "到最近端帽的沿路径距离"（那里的弧长正是较小的那一项）；**中段会偏小**，
            // 极端情形可以小到 0（整条路径只有一段时，它两端的 alongAt 都是 0）。
            // 这不影响结果：着色器看的是 `0.5 + 沿向 / fwidth(沿向)`——中段的梯度小、
            // 比值大，仍然算出"完全覆盖"，而 0 等值线始终落在**真实端线**上。
            // 反过来说，中段的绝对值不可用于任何手算期望值。
            emitQuad(ax + nx, ay + ny, aStart, 1f,
                    bx + nx, by + ny, aEnd, 1f,
                    bx - nx, by - ny, aEnd, -1f,
                    ax - nx, ay - ny, aStart, -1f);

            arc += len;
            prevDx = dx;
            prevDy = dy;
            hasPrev = true;
            lastX = bx;
            lastY = by;
            lastDx = dx;
            lastDy = dy;
            lastAlong = aEnd;
        }

        if (closed && hasPrev) {
            // 闭合路径：最后一段与第一段有效段之间也要补接头
            int first = firstValidSegment(points, count, segmentCount);
            if (first >= 0) {
                int b = (first + 1) % count;
                // 首尾接头的拐点就是 points[first]，而它同时是**第一段的起点**（弧长 0）
                // 与最后一段的终点（弧长 totalLength）。取 0：这样它与第一段起点四边形
                // 拿到同一个沿向值，而两者本来就在同一个点上。
                emitJoin(points[first * 2], points[first * 2 + 1], prevDx, prevDy,
                        points[b * 2] - points[first * 2], points[b * 2 + 1] - points[first * 2 + 1],
                        joinHalf, alongAt(0f, totalLength, half, true),
                        join, miterLimit, roundSegments);
            }
        } else if (capStartDone) {
            // 终点封口落在最后一段有效段的终点上
            emitCap(lastX, lastY, lastDx, lastDy, half, cap, roundSegments, lastAlong, capExtension);
        }
    }

    /**
     * 把沿路径的弧长换算成"沿向边距"（已归一化到半线宽）。
     *
     * <p>开放路径取"到<strong>最近</strong>端帽的距离" ⇒ 两端附近接近 0、中段最大
     * （{@code totalLength/2/half}）。闭合路径没有端帽，取 {@code arc + totalLength}
     * ⇒ <strong>恒 ≥ {@code totalLength/half}</strong>，远离 0 ⇒ 那片区域的沿向测试恒为
     * "完全覆盖"，不会在路径起点<strong>凭空造出一条羽化边</strong>。
     *
     * <p><strong>本方法恒 ≥ 0</strong>（两条分支都是）：弧长只累加有效段、而
     * {@code totalLength} 连退化段一起累加 ⇒ 恒有 {@code arc ≤ totalLength}，所以开放
     * 分支的 {@code totalLength - arc} 不会变负——<strong>"带外为负"不可能从这里出来</strong>。
     * 带外的负沿向只有一条来路：{@link #stroke} 的 {@code capExtension}，由
     * {@link #emitCap} 在真实端线之外显式铺出来。别指望"把折线两端延长"能得到负值
     * （见 {@link #rawEdges()}）。
     *
     * <p>为什么闭合路径不能也写成 {@code min(arc, totalLength - arc)}：那样弧长 0 处的
     * 顶点沿向为 0，而闭合路径<strong>没有端帽</strong>——着色器会在那里按"端线附近"羽化，
     * 于是一条本该处处实心的闭合轮廓，在起点处凭空多出一道两端渐隐的接缝。
     *
     * @param arc         顶点沿路径的弧长（顶点落在段上时取该处弧长，落在接头/端帽上时取拐点弧长）
     * @param totalLength 整条路径的总弧长
     * @param half        半线宽
     * @param closed      是否闭合
     * @return 沿向边距（开放路径：端线处为 0、带内为正、越过端线为负；闭合路径恒 ≥ {@code totalLength/half}）
     */
    private static float alongAt(float arc, float totalLength, float half, boolean closed) {
        float d = closed ? (arc + totalLength) : Math.min(arc, totalLength - arc);
        return d / half;
    }

    /**
     * 在折线拐点处补出接头三角形，填掉相邻两段偏移轮廓之间的缝隙。
     *
     * @param px            拐点坐标 x
     * @param py            拐点坐标 y
     * @param d1x           入段方向向量 x（未归一化）
     * @param d1y           入段方向向量 y（未归一化）
     * @param d2x           出段方向向量 x（未归一化）
     * @param d2y           出段方向向量 y（未归一化）
     * @param half          <strong>接头自己的</strong>半线宽（{@code stroke} 的
     *                      {@code joinHalf}）。调用方为长边留 fringe 而加宽线宽时，
     *                      这里要传**真实**半线宽：接头的横向恒为 0、不会被羽化，
     *                      外扩量加在它身上只会让拐角实打实地多画一圈（实测约 1 个像素）
     * @param along         该拐点的沿向边距（{@code alongAt(拐点弧长, …)}）：
     *                      整个接头都落在这一个弧长位置上，所以它<strong>只取决于拐点</strong>，
     *                      与顶点在接头内部的位置无关
     * @param join          接头样式
     * @param miterLimit    miter 阈值（相对于 {@code half}，也就是相对于 {@code joinHalf}）
     * @param roundSegments 圆角细分段数
     */
    private void emitJoin(float px, float py, float d1x, float d1y,
                          float d2x, float d2y, float half, float along,
                          Join join, float miterLimit, int roundSegments) {
        float l1 = (float) Math.sqrt(d1x * d1x + d1y * d1y);
        float l2 = (float) Math.sqrt(d2x * d2x + d2y * d2y);
        if (l1 < 1e-6f || l2 < 1e-6f) {
            return;
        }
        float u1x = d1x / l1, u1y = d1y / l1;
        float u2x = d2x / l2, u2y = d2y / l2;

        float cross = u1x * u2y - u1y * u2x;
        float dot = u1x * u2x + u1y * u2y;
        // 共线同向：直行，两段轮廓相接，不存在缝隙。
        // 共线反向（180° 折回）：只有 MITER 必须在这里返回——它要求两条偏移线的交点，
        // 而 cross = 0 会让 t = -Infinity，0 * -Infinity = NaN，污染顶点会被发出去。
        // BEVEL/ROUND 走下面的分支，够不到那次除法：BEVEL 退化为零面积三角形（无害），
        // ROUND 用有限的法线求 atan2 并扫出尖端半圆。
        if (Math.abs(cross) < 1e-6f && (dot > 0f || join == Join.MITER)) {
            return;
        }

        // 缝隙在凸侧：左转（cross > 0）时凸侧在行进方向右侧，右转时在左侧，
        // 即偏移法线取 (-u.y, u.x) 的反方向（左转）或正方向（右转）。
        boolean leftTurn = cross > 0f;
        float s = leftTurn ? -1f : 1f;
        float o1x = -u1y * half * s, o1y = u1x * half * s;
        float o2x = -u2y * half * s, o2y = u2x * half * s;

        // 边距的取值规则（三种接头共用一套）：接头三角形的横向**一律取 0**。
        //
        // 横坐标为 0 ⇒ 覆盖率公式里 `fwidth(cross) == 0` ⇒ 片元走"完全覆盖"分支
        // ⇒ 整个接头**不透明**，外缘是**硬边**。沿向三者都等于拐点自己的沿向。
        //
        // ★ 为什么不是 ±s：本意是"尖角在描边内部，让它完全覆盖"，但覆盖率公式在
        //   `|x| = 1` 处给的是 **0.5** 而不是 1 —— 那个本意从来没有实现过。
        //   而 `Gc` 开了抗锯齿时会按 `几何半宽 / 真实半宽` 缩放**所有**顶点的横向
        //   （因为描边带被外扩过 1 个像素），于是 ±1 变成 ±1.5 ⇒ 覆盖率 **0**
        //   ⇒ **每个拐角被啃掉一块**（风筝形外侧整片透明）。
        //   **取 0 才是真正达成那个本意的写法，且与缩放倍率无关**——它是唯一一个
        //   在"扩/不扩"两种几何下都给同一个覆盖率的取值。
        //
        // 代价一是**已声明的降级，不是缺陷**：miter 的外缘**没有羽化**（硬边）。
        // 在三个都错的选项里——硬边 / 半透明边 / 缺口——硬边是唯一"形状对得上"的那个。
        //
        // ★ 但"形状对得上"有一处**实测出的**偏差要一并记下来：接头几何本身**也被外扩**
        //   （它用的是同一个 `half`），而它现在恒不透明 ⇒ 拐角外沿会比真实轮廓
        //   多画约 1 个像素。实测（线宽 8、AA 开、真外缘 296.75）：真外缘之外 0.25 像素处，
        //   直边给 0.25、拐角给 **1.0**；外拐角对角线那个像素真实覆盖率只有 0.06，也画成纯白。
        //   要同时收掉这一条，得让接头用**未外扩**的半线宽生成（`stroke` 再收一个
        //   "接头半宽"参数，由 `Gc` 传真实半线宽）——那是另一处改动，已记进提交信息，
        //   不在本次范围内。数值由 `PipelineVerifier` 探针三钉着（那一行断言同时是
        //   这条降级的读数与"将来有人收掉它"的哨兵）。
        //
        // 要真正羽化 miter 需要每条边各一个距离分量（`aEdge` 从 vec2 扩到 vec3 或更多），
        // 那是另一个量级的改动，且当前没有证据说明它值得。
        //
        // 圆角接头的圆弧盘（下面的 emitArc）**不在**这条规则里：它的横向是圆弧自己的
        // 几何（到入段中心线的有符号投影），抹成 0 会把圆端的侧向羽化也一起去掉。
        if (join == Join.BEVEL || join == Join.ROUND) {
            emitTriangle(px, py, px + o1x, py + o1y, px + o2x, py + o2y,
                    0f, along, 0f, along, 0f, along);
            if (join == Join.ROUND) {
                // 从 o1 方向扫到 o2 方向，扫过角度即转向角
                float start = (float) Math.atan2(o1y, o1x);
                float sweep = (float) Math.atan2(cross, dot);
                if (Math.abs(cross) < 1e-6f && dot < 0f) {
                    // 180° 折回：o1 与 o2 恰好反向，两条半圆路线扫过的角度都是 π，
                    // 而 atan2(±0.0, -1) 的正负只由 cross 里那个零的符号决定（与几何无关），
                    // 会把半圆画到内侧、被两段重合的四边形完全盖住（+x 与 -y 方向曾如此）。
                    // 尖端必须朝行进方向 u1：从 o1 绕过 u1 到 o2，扫过 -π * s。
                    sweep = -s * (float) Math.PI;
                }
                // 横向参照法线取入段方向 u1 的左法线：在 o1 处它给出 s，与上面三个顶点一致；
                // 圆角盘上其余各点得到的是"到入段中心线的有符号垂直距离"——那是这一维真正
                // 的语义，也正是着色器要的。沿向则整盘恒定（向外方向传 (0,0)）：圆角接头
                // 整个落在拐点这一个弧长位置上，与上面三个顶点取同一个值。
                emitArc(px, py, half, start, sweep, roundSegments, along,
                        -u1y, u1x, 0f, 0f);
            }
            return;
        }

        // MITER：求两条偏移直线的交点（p + o1 + t*u1 与 p + o2 + t2*u2）
        float wx = o2x - o1x, wy = o2y - o1y;
        float t = (wx * u2y - wy * u2x) / cross;
        float mx = px + o1x + u1x * t;
        float my = py + o1y + u1y * t;
        float miterLength = (float) Math.sqrt((mx - px) * (mx - px) + (my - py) * (my - py));

        // 非有限值一并回退。这一条不是死代码：上面那道共线守卫若缺失或被改动，
        // 它就是接住 NaN/Infinity 的网（实测去掉共线守卫后，仅凭本条即可阻止污染顶点）；
        // 任何未预料的退化都应降级为 bevel，而不是把 NaN 发出去。
        if (!Float.isFinite(miterLength) || miterLength > miterLimit * half) {
            // 超过 miter limit，回退为 bevel
            emitTriangle(px, py, px + o1x, py + o1y, px + o2x, py + o2y,
                    0f, along, 0f, along, 0f, along);
            return;
        }
        // 完整的 miter 接头是"风筝形"四边形 (p, p+o1, m, p+o2)，**两个**三角形：
        // 底边之外那个尖角三角形，加上顶点与底边之间那个（与上面退化分支发的**同一个**——
        // 两条分支各自只发一次，互不重叠，共享的只有底边那条线）。
        //
        // 曾经只发前者：顶点内侧缺一块（面积 = 半线宽²/2，90° 直角处；线宽 20 时每角 50 px²，
        // 线宽 1 时只有 0.125 px²，所以细线几乎看不出来，缺陷因此活了很久）。
        // 90° 直角处风筝恰好是 2·半线宽 见方的角块，因此"补全"这一件事在像素上可读：
        // 闭合直角方框的总面积从 15800 变成理想值 16000（见 PathVerifier）。
        emitTriangle(px, py, px + o1x, py + o1y, px + o2x, py + o2y,
                0f, along, 0f, along, 0f, along);
        emitTriangle(px + o1x, py + o1y, mx, my, px + o2x, py + o2y,
                0f, along, 0f, along, 0f, along);
    }

    /**
     * 在折线端点处补出封口几何：{@link Cap#BUTT} 的外扩四边形，或
     * {@link Cap#SQUARE} / {@link Cap#ROUND} 的端帽。
     *
     * <p><strong>{@code (dx,dy)} 是"向外"方向</strong>（由端点指向线段<strong>外</strong>侧），
     * 两个调用点都是这么传的：起点传 {@code (-dx,-dy)}（段方向的取反）、终点传段方向本身。
     * 半圆的圆心角因此从 {@code u} 逆转 90° 扫到顺转 90°，整个半圆盘都在 {@code +u} 一侧。
     *
     * <p><strong>沿向的零点与方向</strong>：{@code 0} 就是端线（过端点、垂直于 {@code u}
     * 的那条线），向外为负。三种端帽都守这一条：
     * <ul>
     *   <li><strong>BUTT</strong>（{@code capExtension > 0} 时）：端线沿向取
     *       {@code along}（就是相邻段四边形那条边的沿向，接缝两侧因此同一个值），
     *       外扩那一条取 {@code along - capExtension/half}。</li>
     *   <li><strong>SQUARE</strong>：端线沿向 {@code along}，外边向外半个线宽 ⇒ 沿向
     *       {@code along - 1}。</li>
     *   <li><strong>ROUND</strong>：圆心在端线上 ⇒ 沿向 {@code along}；圆周上按
     *       {@code along - dot(圆周方向, u)} 连续变化（最外那点 {@code along - 1}、
     *       圆周与端线相交的两点 {@code along}）。</li>
     * </ul>
     * 这里的 {@code along} 在正常情况下恰好是 {@code 0}（{@code alongAt} 在开放路径两端
     * 都返回 0）；传参而不是写字面量 {@code 0f}，是为了让"折线首尾有退化段"这种边角情形
     * 下接缝两侧仍然取同一个值（那里 {@code alongAt} 给出的是 {@code 10⁻⁶} 量级而非精确 0）。
     *
     * <p><strong>横向</strong>：{@code +n} 侧为 {@code +1}、{@code -n} 侧为 {@code -1}，
     * 其中 {@code n} 是<strong>本方向的</strong>左法线。因为这里传进来的是"向外"方向，
     * 这个 {@code n} 在几何上与相邻段四边形的 {@code n} 是<strong>反向</strong>的——
     * 也就是说同一侧的两个图元会拿到相反的符号。着色器只用 {@code |横向|} 判覆盖，
     * 所以这没有后果；写下来是因为"两处的 ±1 是同一套"这个假设看上去太自然了。
     *
     * <p><strong>⚠ BUTT 的外扩四边形是为端帽羽化而生的，不是"把线画长"</strong>：
     * 它从端线向外铺 {@code capExtension}，而让它在端线外侧变透明是<strong>着色器</strong>
     * 的责任（按沿向算覆盖率）。所以 {@code capExtension} 默认 {@code 0}，
     * 不传就退化成"什么都不发射"——与本方法加这个参数之前逐位相同。
     *
     * <p><strong>⚠ 本方法当前在生产代码里没有消费者</strong>：{@code Gc} 的描边只用
     * {@link Cap#BUTT} + {@code capExtension = 0}，此时这里直接 {@code return}。
     * 所以 SQUARE / ROUND 两条分支、以及外扩四边形，目前都只有
     * {@code StrokeGeneratorTest} 在跑——它们的<strong>几何与边距取值</strong>由单测钉着，
     * 但"着色器怎么读它们"还没有任何真实调用方。
     *
     * @param px            端点坐标 x
     * @param py            端点坐标 y
     * @param dx            由端点指向线段<strong>外</strong>侧的方向向量 x（未归一化）
     * @param dy            由端点指向线段<strong>外</strong>侧的方向向量 y（未归一化）
     * @param half          半线宽
     * @param cap           端点样式
     * @param roundSegments 圆端点细分段数
     * @param along         端线的沿向（正常情况下恰为 0，见上面的说明）
     * @param capExtension  端帽外扩量（局部空间单位）；只对 {@link Cap#BUTT} 有意义，
     *                      {@code ≤ 0} 且是 BUTT 时本方法直接返回
     */
    private void emitCap(float px, float py, float dx, float dy, float half,
                         Cap cap, int roundSegments, float along, float capExtension) {
        if (cap == Cap.BUTT && capExtension <= 0f) {
            // 平头端 + 不外扩：什么都不发射（这是既有行为，逐位不变）
            return;
        }
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6f) {
            return;
        }
        float ux = dx / len, uy = dy / len;
        float nx = -uy * half, ny = ux * half;

        if (cap == Cap.BUTT) {
            // 端帽外扩：从端线（沿向 along）向外铺 capExtension，外缘沿向 along - capExtension/half。
            // 它是**半透明的**一圈（透明度由着色器按沿向算），目的是让端线两侧都有沿向梯度：
            // 没有它，端线外侧一片几何都没有，沿向在那里是常量 ⇒ fwidth = 0 ⇒ 着色器走
            // "完全覆盖"分支 ⇒ 端帽是硬角；而端线内侧同样没有梯度，端线本身也拿不到 50%。
            // 四个顶点的横向仍按 +n 侧 +1、-n 侧 -1，与它所贴的段四边形一致。
            float outerAlong = along - capExtension / half;
            emitQuad(px + nx, py + ny, along, 1f,
                    px + nx + ux * capExtension, py + ny + uy * capExtension, outerAlong, 1f,
                    px - nx + ux * capExtension, py - ny + uy * capExtension, outerAlong, -1f,
                    px - nx, py - ny, along, -1f);
            return;
        }

        if (cap == Cap.SQUARE) {
            // 端线（贴线段那一侧）沿向 along，向外那一条 along - 1（外扩量恰好半个线宽）
            emitQuad(px + nx, py + ny, along, 1f,
                    px + nx + ux * half, py + ny + uy * half, along - 1f, 1f,
                    px - nx + ux * half, py - ny + uy * half, along - 1f, -1f,
                    px - nx, py - ny, along, -1f);
            return;
        }

        // ROUND：以端点为中心、朝外（沿 +u 方向）的半圆扇，从 u 逆转 90° 扫到顺转 90°。
        // 圆心（端点）在端线上 ⇒ 沿向 along；圆周顶点按 -dot(圆周方向, u) 变化
        // （最外那一点 along - 1，圆周与端线相交的两点 along）。
        float start = (float) Math.atan2(uy, ux) - (float) (Math.PI / 2);
        emitArc(px, py, half, start, (float) Math.PI, roundSegments, along,
                -uy, ux, ux, uy);
    }

    /**
     * 生成以 {@code (cx,cy)} 为圆心、{@code radius} 为半径的圆弧三角扇。
     *
     * <p><strong>边距怎么来</strong>：圆心顶点横向 0（它在中心线上/端点中心）、沿向取
     * {@code alongBase}；圆周顶点的方向向量是 {@code (cos a, sin a)}，于是
     * <ul>
     *   <li>横向 = 该方向在 {@code (nx,ny)} 上的投影，即<strong>到参照中心线的有符号垂直
     *       距离</strong>（按半线宽归一化）。圆周与中心线相交处为 0、与两条外缘相交处为 ±1
     *       ——这正是"横向"这一维的语义，插值出来的就是横向边距本身，而不是某种近似。</li>
     *   <li>沿向 = {@code alongBase - 该方向在 (ox,oy) 上的投影}，{@code (ox,oy)} 是"向外"
     *       单位方向：端帽上传 {@code u}（于是尖端沿向 -1、两端 0，与端线取同一个零点）；
     *       圆角接头没有"向外"可言，传 {@code (0,0)} ⇒ 整盘沿向恒为 {@code alongBase}
     *       （整个角盘落在拐点这一个弧长位置上）。</li>
     * </ul>
     * 两个投影都<strong>没有</strong>再乘 {@code radius/half}：本方法的两个调用点传进来的
     * {@code radius} 都恰好是半线宽（圆端点与圆角接头的半径按定义就是它），比值恒为 1。
     * 若将来要传别的半径，这两个投影必须补上那个比值，否则边距会被整体缩放而不报错。
     *
     * <p><strong>已声明的降级（不是算错了）</strong>：圆周与参照中心线相交的地方横向为 0，
     * 而那一处有时<strong>同时</strong>是可见外缘，于是拿不到羽化：
     * <ul>
     *   <li><strong>180° 折回的圆角接头</strong>：尖端就是那个交点（{@code dir = u1}，
     *       落在入段中心线的延长线上），远端点的横向为 0 而不是 -1 ⇒ 尖端是一条硬边。
     *       圆弧两端仍正常（横向 ±1）。</li>
     *   <li><strong>直角圆角接头</strong>：圆弧的收尾端（{@code p + o2}）横向也是 0
     *       （实测 90° 接头处读数为 {@code +0.000}），而同一位置在 BEVEL 分支下取 {@code s}。
     *       这里<strong>没有可见后果</strong>：该点正落在出段四边形的那条边上，被它的 ±1 盖住。</li>
     * </ul>
     * 根因是"横向"这一维在拐点处不够用：这些点到最近中心线的距离确实是 0，而它们同时也是
     * 外缘。要区分这两种身份得再加一个坐标，本期不做；且 {@code Gc} 走不到这条路径（见下）。
     *
     * <p><strong>⚠ 本方法当前在生产代码里没有消费者</strong>：{@code Gc} 的描边只用
     * {@link Cap#BUTT} 与 {@link Join#MITER}，而这两条路径都不发射圆弧（BUTT 不发射几何、
     * MITER 只有一条线段的尖角）。圆端点与圆角接头目前只有 {@code StrokeGeneratorTest}
     * 在跑。
     *
     * @param cx         圆心 x
     * @param cy         圆心 y
     * @param radius     半径
     * @param startAngle 起始角（弧度）
     * @param sweep      扫过角度（弧度，可为负）
     * @param segments   细分段数
     * @param alongBase  圆心顶点的沿向，也是圆周顶点沿向的基准
     * @param nx         横向参照法线的 x（单位向量：圆周方向在它上面的投影就是横向边距）
     * @param ny         横向参照法线的 y
     * @param ox         "向外"单位方向的 x（沿向 {@code -} 该方向投影）
     * @param oy         "向外"单位方向的 y
     */
    private void emitArc(float cx, float cy, float radius, float startAngle, float sweep, int segments,
                         float alongBase, float nx, float ny, float ox, float oy) {
        for (int i = 0; i < segments; i++) {
            float a0 = startAngle + sweep * i / segments;
            float a1 = startAngle + sweep * (i + 1) / segments;
            float u0x = (float) Math.cos(a0), u0y = (float) Math.sin(a0);
            float u1x = (float) Math.cos(a1), u1y = (float) Math.sin(a1);
            emitTriangle(cx, cy,
                    cx + u0x * radius, cy + u0y * radius,
                    cx + u1x * radius, cy + u1y * radius,
                    0f, alongBase,
                    u0x * nx + u0y * ny, alongBase - (u0x * ox + u0y * oy),
                    u1x * nx + u1y * ny, alongBase - (u1x * ox + u1y * oy));
        }
    }

    /**
     * 生成一个四边形的两个三角形，顶点按 {@code p0 → p1 → p2 → p3} 顺序环绕。
     *
     * <p>每个顶点带一对边距 {@code (沿向 a, 横向 c)}——参数顺序与
     * {@link #emitTriangle} 的"位置在前、边距在后且横向在前"不同，是为了让
     * {@code (x, y, a, c)} 四个一组、读调用点时能一眼看出哪个数字属于哪个顶点。
     * 本方法只做转置，没有别的逻辑。
     *
     * <p><strong>⚠ 防呆</strong>：这条差异意味着 {@code (a, c)} 与 {@code (c, a)}
     * <strong>写反了照样编译</strong>（全是 {@code float}，没有类型能拦），后果是两个分量
     * 互换——画面上是"边距全乱"而不是编译错误。签名刻意不改（一改 3 个调用点全要跟着动，
     * 而那 3 处正是本方法唯一的用处：段四边形、SQUARE 端帽、外扩四边形），
     * 所以读到这里的调用方请把"本方法 a 在前、{@link #emitTriangle} c 在前"记牢。
     */
    private void emitQuad(float x0, float y0, float a0, float c0,
                          float x1, float y1, float a1, float c1,
                          float x2, float y2, float a2, float c2,
                          float x3, float y3, float a3, float c3) {
        emitTriangle(x0, y0, x1, y1, x2, y2, c0, a0, c1, a1, c2, a2);
        emitTriangle(x2, y2, x3, y3, x0, y0, c2, a2, c3, a3, c0, a0);
    }

    /**
     * 追加一个三角形（含三个顶点的抗锯齿边距），必要时扩容内部数组。
     *
     * <p><strong>没有"省略边距"的重载</strong>：本类的契约是<strong>每个顶点都有</strong>
     * 横向与沿向两个分量（见 {@link #rawEdges()}），少写一个分量不该有语法上的捷径——
     * 那会让"沿向可以不管"变成一个随手可做的选择，而实际上它会静默地变成 0
     * （端线附近 ⇒ 整条描边被当成端帽附近而羽化）。
     *
     * @param e0c 第 0 个顶点的横向边距
     * @param e0a 第 0 个顶点的沿向边距（其余同此，{@code c} 为横向、{@code a} 为沿向）
     */
    private void emitTriangle(float x0, float y0, float x1, float y1,
                              float x2, float y2,
                              float e0c, float e0a, float e1c, float e1a,
                              float e2c, float e2a) {
        if (triangleCount * 6 + 6 > triangles.length) {
            // 两个数组长度恒等（每三角形各占 6 个 float），所以一起翻倍即可
            triangles = Arrays.copyOf(triangles, triangles.length * 2);
            edges = Arrays.copyOf(edges, edges.length * 2);
        }
        int o = triangleCount * 6;
        triangles[o] = x0;
        triangles[o + 1] = y0;
        triangles[o + 2] = x1;
        triangles[o + 3] = y1;
        triangles[o + 4] = x2;
        triangles[o + 5] = y2;
        edges[o] = e0c;
        edges[o + 1] = e0a;
        edges[o + 2] = e1c;
        edges[o + 3] = e1a;
        edges[o + 4] = e2c;
        edges[o + 5] = e2a;
        triangleCount++;
    }

    /** 端点样式。 */
    public enum Cap {

        /** 平端点：轮廓恰好止于线段端点，不向外延伸。 */
        BUTT,

        /** 圆端点：以端点为圆心补一个半径等于半线宽的半圆。 */
        ROUND,

        /** 方端点：在端点处沿切线方向向外延伸半个线宽。 */
        SQUARE
    }

    /** 接头样式。 */
    public enum Join {

        /** 尖角接头：延伸到两条偏移线的交点，超过 miter limit 时回退为 {@link #BEVEL}。 */
        MITER,

        /** 圆角接头：用半径等于半线宽的圆弧补角。 */
        ROUND,

        /** 斜接接头：用连接两条偏移线的弦切掉尖角。 */
        BEVEL
    }
}
