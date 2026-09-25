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
     *       {@code n = (-dy/len, dx/len)} 那一侧（即该段行进方向的左法线侧）。</li>
     *   <li><strong>沿向</strong>：到最近端帽的沿路径距离，同样除以半线宽。
     *       <strong>{@code 0} 就是端帽线：带内为正，带外为负</strong>——
     *       片段着色器正是靠这个符号区分"在线内"与"在线外"。
     *       开放路径取 {@code min(到起点, 到终点)} ⇒ 端线处恰为 0、中段最大；
     *       闭合路径没有端帽，取 {@code 弧长 + 全长}，从而<strong>没有靠近 0 的顶点</strong>
     *       （否则会在路径起点凭空造出一条羽化边）。
     *       端帽几何（SQUARE / ROUND）自己的顶点沿向可以小于 0——见 {@link #emitCap}。
     *       <p>还有一条值得写下来的性质：<strong>平头端的"带外"不需要任何特判</strong>。
     *       着色器要的那圈端帽 fringe，只要把折线在两端各延长一点再交给本类即可——
     *       延长部分的弧长大于全长，{@code min(arc, totalLength - arc)} 自然落到负区间。
     *       这也是"端点外侧"在本类里唯一的表达方式：{@link #stroke} 本身从不发射端线以外的几何
     *       （{@link Cap#BUTT} 直接不发射，SQUARE / ROUND 发射的端帽另有自己的沿向口径）。</li>
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
        reset();
        generateOutline(points, count, closed, width, cap, join, miterLimit, roundSegments);
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
        if (dashPattern == null || dashPattern.length == 0) {
            // 无模式即实线
            stroke(points, count, closed, width, cap, join, miterLimit, roundSegments);
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
                    generateOutline(seg, 2, false, width, cap, join, miterLimit, roundSegments);
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
     */
    private void generateOutline(float[] points, int count, boolean closed, float width,
                                 Cap cap, Join join, float miterLimit, int roundSegments) {
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
        // 退化段（长度 < 1e-6）贡献的弧长近似为 0，所以这里不必像主循环那样跳过它们。
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
            if (hasPrev) {
                emitJoin(ax, ay, prevDx, prevDy, dx, dy, half, aStart, join, miterLimit, roundSegments);
            }
            // 起点封口落在第一段有效段上
            if (!closed && !capStartDone) {
                emitCap(ax, ay, -dx, -dy, half, cap, roundSegments);
                capStartDone = true;
            }

            // 每段是一个四边形（两个三角形）。
            // 横向：+n 侧恒为 +1、-n 侧恒为 -1（n 是本段行进方向的左法线）；
            // 沿向：两端各取自己那一端的弧长（段内是线性的，于是插值出来的沿向
            // 恰好等于"到最近端帽的沿路径距离"）。
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
                        half, alongAt(0f, totalLength, half, true),
                        join, miterLimit, roundSegments);
            }
        } else if (capStartDone) {
            // 终点封口落在最后一段有效段的终点上
            emitCap(lastX, lastY, lastDx, lastDy, half, cap, roundSegments);
        }
    }

    /**
     * 把沿路径的弧长换算成"沿向边距"（已归一化到半线宽）。
     *
     * <p>开放路径取"到<strong>最近</strong>端帽的距离" ⇒ 两端附近接近 0、中段最大
     * （{@code totalLength/2/half}）；弧长越过全长（调用方把折线延长出去的情形）则为负，
     * 与"带外为负"的口径一致。闭合路径没有端帽，取 {@code arc + totalLength}
     * ⇒ <strong>恒 ≥ {@code totalLength/half}</strong>，远离 0 ⇒ 那片区域的沿向测试恒为
     * "完全覆盖"，不会在路径起点<strong>凭空造出一条羽化边</strong>。
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
     * @param half          半线宽
     * @param along         该拐点的沿向边距（{@code alongAt(拐点弧长, …)}）：
     *                      整个接头都落在这一个弧长位置上，所以它<strong>只取决于拐点</strong>，
     *                      与顶点在接头内部的位置无关
     * @param join          接头样式
     * @param miterLimit    miter 阈值（相对于半线宽）
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

        // 边距的取值规则（三种接头共用一套）：
        //   拐点 (px,py) 在中心线上 ⇒ 横向为 0；
        //   两个偏移点与 miter 尖角都在**凸侧**、距中心线恰好半线宽 ⇒ 横向为 s（凸侧符号）；
        //   沿向三者都等于拐点自己的沿向。
        // miter 尖角明明离中心线**超过**半线宽（直角处 √2 倍），却仍取 s 而不是那个比值：
        // 尖角属于描边内部，取 s 让整块尖角恒为"完全覆盖"；若照实取比值，着色器会把尖角
        // 当成落在两条外缘之外而把它羽化掉——尖角会自己变淡，而画面看起来"只是有点虚"。
        if (join == Join.BEVEL || join == Join.ROUND) {
            emitTriangle(px, py, px + o1x, py + o1y, px + o2x, py + o2y,
                    0f, along, s, along, s, along);
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
                    0f, along, s, along, s, along);
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
                0f, along, s, along, s, along);
        emitTriangle(px + o1x, py + o1y, mx, my, px + o2x, py + o2y,
                s, along, s, along, s, along);
    }

    /**
     * 在折线端点处补出封口三角形。
     *
     * <p><strong>{@code (dx,dy)} 是"向外"方向</strong>（由端点指向线段<strong>外</strong>侧），
     * 两个调用点都是这么传的：起点传 {@code (-dx,-dy)}（段方向的取反）、终点传段方向本身。
     * 半圆的圆心角因此从 {@code u} 逆转 90° 扫到顺转 90°，整个半圆盘都在 {@code +u} 一侧。
     *
     * <p><strong>沿向</strong>：端线（过端点、垂直于 {@code u} 的那条线）上沿向为 0，
     * 向外为负——SQUARE 的外边沿向 {@code -1}，ROUND 圆周上按
     * {@code -dot(圆周方向, u)} 连续变化（尖端 {@code -1}、两端 {@code 0}）。
     * 零点与 BUTT 端取的是同一个：平头端的端线沿向恰好为 0，而它不发射几何，
     * 端线<strong>就是最后一段四边形的边</strong>。
     *
     * <p><strong>横向</strong>：{@code +n} 侧为 {@code +1}、{@code -n} 侧为 {@code -1}，
     * 其中 {@code n} 是<strong>本方向的</strong>左法线。因为这里传进来的是"向外"方向，
     * 这个 {@code n} 在几何上与相邻段四边形的 {@code n} 是<strong>反向</strong>的——
     * 也就是说同一侧的两个图元会拿到相反的符号。着色器只用 {@code |横向|} 判覆盖，
     * 所以这没有后果；写下来是因为"两处的 ±1 是同一套"这个假设看上去太自然了。
     *
     * <p><strong>⚠ 本方法当前在生产代码里没有消费者</strong>：{@code Gc} 的描边只用
     * {@link Cap#BUTT}，而 BUTT 在这里直接 {@code return}。所以 SQUARE / ROUND 这两条
     * 分支目前只有 {@code StrokeGeneratorTest} 在跑——它们的几何由单测钉着，
     * 但"着色器怎么读这两个端帽的边距"还没有任何真实调用方。
     *
     * @param px            端点坐标 x
     * @param py            端点坐标 y
     * @param dx            由端点指向线段<strong>外</strong>侧的方向向量 x（未归一化）
     * @param dy            由端点指向线段<strong>外</strong>侧的方向向量 y（未归一化）
     * @param half          半线宽
     * @param cap           端点样式
     * @param roundSegments 圆端点细分段数
     */
    private void emitCap(float px, float py, float dx, float dy, float half,
                         Cap cap, int roundSegments) {
        if (cap == Cap.BUTT) {
            return;
        }
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6f) {
            return;
        }
        float ux = dx / len, uy = dy / len;
        float nx = -uy * half, ny = ux * half;

        if (cap == Cap.SQUARE) {
            // 端线（贴线段那一侧）沿向 0，向外那一条 -1
            emitQuad(px + nx, py + ny, 0f, 1f,
                    px + nx + ux * half, py + ny + uy * half, -1f, 1f,
                    px - nx + ux * half, py - ny + uy * half, -1f, -1f,
                    px - nx, py - ny, 0f, -1f);
            return;
        }

        // ROUND：以端点为中心、朝外（沿 +u 方向）的半圆扇，从 u 逆转 90° 扫到顺转 90°。
        // 圆心（端点）在端线上 ⇒ 沿向 0；圆周顶点按 -dot(圆周方向, u) 变化
        // （最外那一点沿向 -1）。
        float start = (float) Math.atan2(uy, ux) - (float) (Math.PI / 2);
        emitArc(px, py, half, start, (float) Math.PI, roundSegments, 0f,
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
     * <p><strong>已声明的降级（不是算错了）</strong>：圆周与参照中心线的交点处横向为 0，
     * 而 180° 折回的圆角接头<strong>尖端恰好就在那个交点上</strong>——那里圆弧本身就是
     * 可见外缘，却因横向为 0 而拿不到羽化，成了一条硬边（圆弧两端仍正常，因为它们横向为 ±1）。
     * 根因是"横向"这一维在拐点处不够用：尖端到最近中心线的距离确实是 0（它落在入段中心线
     * 的延长线上），而它同时也是外缘。要区分这两种身份得再加一个坐标，本期不做；
     * 实测影响范围只有那一个点，且 Gc 走不到这条路径（见下）。
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
