package com.bingbaihanji.jfgl.geom;

/**
 * 可变的 2D 路径累加器，用于在绘制热路径上零分配地构建路径。
 *
 * <p>与不可变的构建器不同，此类的实例可被反复 {@link #reset()} 并复用。
 * 命令与坐标存储在基本类型数组中，{@code moveTo}/{@code lineTo} 等调用不产生对象分配（扩容时除外）。
 *
 * <p>用法：
 * <pre>{@code
 * Path path = new Path();
 * path.reset();
 * path.moveTo(0, 0).lineTo(100, 0).lineTo(100, 100).close();
 * }</pre>
 *
 * <p>{@link #reset()} 只把命令计数归零，既不重新分配也不清零内部数组；追加命令时，
 * 点数为零的命令（{@link Type#CLOSE}）也不写入坐标槽。因此带下标的访问器
 * （{@link #commandType(int)}、{@link #pointCount(int)}、{@link #commandX(int, int)}、
 * {@link #commandY(int, int)}）只在 {@code i < commandCount()} 且 {@code p < pointCount(i)}
 * 时有意义，其余下标会读到上一次填充遗留的数据，不应作为有效几何使用。
 *
 * <p>本类不依赖 {@code gl} 包，可脱离 GL 上下文进行单元测试。
 */
public final class Path {

    /** 每条命令最多携带的点数（三次贝塞尔为 3）。 */
    public static final int MAX_POINTS_PER_COMMAND = 3;

    /**
     * {@link Type#values()} 的缓存。
     * <p>
     * {@code Type.values()} 每次都返回 {@code $VALUES.clone()}，即每个调用分配一个 5 元素数组；
     * 而 {@link #commandType(int)} 在平坦化时<strong>每条命令每帧</strong>都会被调用一次，
     * 处在类说明所承诺的零分配热路径上。这里缓存一份，按 {@code ordinal()} 直接下标取值。
     */
    private static final Type[] VALUES = Type.values();

    /** 每条命令的类型，存放 {@link Type#ordinal()}。 */
    private byte[] types = new byte[16];

    /** 坐标数组，布局：[commandIndex * MAX_POINTS_PER_COMMAND * 2 + pointIndex * 2 + (0=x,1=y)]。 */
    private float[] coords = new float[16 * MAX_POINTS_PER_COMMAND * 2];

    /** 每条命令实际携带的点数。 */
    private byte[] pointCounts = new byte[16];

    /** 当前已记录的命令数量。 */
    private int count = 0;

    /**
     * 清空所有命令，保留已分配的数组容量。
     *
     * <p>此方法不释放内部数组，因此可被复用的 {@code Path} 实例在稳态下不会产生分配。
     */
    public void reset() {
        count = 0;
    }

    /**
     * 返回路径是否不含任何命令。
     *
     * @return 不含任何命令时返回 {@code true}
     */
    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * 返回命令数量。
     *
     * @return 已记录的命令数量
     */
    public int commandCount() {
        return count;
    }

    /**
     * 返回内部命令数组容量（仅供测试与诊断）。
     *
     * @return 当前内部数组可容纳的命令数量
     */
    public int arrayCapacity() {
        return types.length;
    }

    /**
     * 返回第 {@code i} 条命令的类型。
     *
     * @param i 命令下标
     * @return 该命令的类型
     */
    public Type commandType(int i) {
        return VALUES[types[i]];
    }

    /**
     * 返回第 {@code i} 条命令携带的点数。
     *
     * @param i 命令下标
     * @return 该命令携带的点数
     */
    public int pointCount(int i) {
        return pointCounts[i];
    }

    /**
     * 返回第 {@code i} 条命令第 {@code p} 个点的 x 坐标。
     *
     * @param i 命令下标
     * @param p 该命令内的点下标
     * @return 点的 x 坐标
     */
    public float commandX(int i, int p) {
        return coords[(i * MAX_POINTS_PER_COMMAND + p) * 2];
    }

    /**
     * 返回第 {@code i} 条命令第 {@code p} 个点的 y 坐标。
     *
     * @param i 命令下标
     * @param p 该命令内的点下标
     * @return 点的 y 坐标
     */
    public float commandY(int i, int p) {
        return coords[(i * MAX_POINTS_PER_COMMAND + p) * 2 + 1];
    }

    /**
     * 将当前点移动到 ({@code x}, {@code y}) 但不绘制。
     *
     * @param x 目标点的 x 坐标
     * @param y 目标点的 y 坐标
     * @return 此对象，便于链式调用
     */
    public Path moveTo(float x, float y) {
        return add(Type.MOVE_TO, 1, x, y, 0f, 0f, 0f, 0f);
    }

    /**
     * 从当前点到 ({@code x}, {@code y}) 绘制直线。
     *
     * @param x 端点的 x 坐标
     * @param y 端点的 y 坐标
     * @return 此对象，便于链式调用
     */
    public Path lineTo(float x, float y) {
        return add(Type.LINE_TO, 1, x, y, 0f, 0f, 0f, 0f);
    }

    /**
     * 从当前点到 ({@code x}, {@code y}) 添加二次贝塞尔曲线，
     * 以 ({@code cx}, {@code cy}) 为控制点。
     *
     * @param cx 控制点的 x 坐标
     * @param cy 控制点的 y 坐标
     * @param x  端点的 x 坐标
     * @param y  端点的 y 坐标
     * @return 此对象，便于链式调用
     */
    public Path quadraticCurveTo(float cx, float cy, float x, float y) {
        return add(Type.QUADRATIC_CURVE_TO, 2, cx, cy, x, y, 0f, 0f);
    }

    /**
     * 从当前点到 ({@code x}, {@code y}) 添加三次贝塞尔曲线，
     * 以 ({@code c1x}, {@code c1y}) 和 ({@code c2x}, {@code c2y}) 为控制点。
     *
     * @param c1x 第一个控制点的 x 坐标
     * @param c1y 第一个控制点的 y 坐标
     * @param c2x 第二个控制点的 x 坐标
     * @param c2y 第二个控制点的 y 坐标
     * @param x   端点的 x 坐标
     * @param y   端点的 y 坐标
     * @return 此对象，便于链式调用
     */
    public Path bezierCurveTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
        return add(Type.BEZIER_CURVE_TO, 3, c1x, c1y, c2x, c2y, x, y);
    }

    /**
     * 通过绘制直线回到最近的 {@code moveTo} 点来关闭当前子路径。
     *
     * @return 此对象，便于链式调用
     */
    public Path close() {
        return add(Type.CLOSE, 0, 0f, 0f, 0f, 0f, 0f, 0f);
    }

    /**
     * 追加一条命令并写入其携带的点，必要时扩容。
     *
     * @param type   命令类型
     * @param points 该命令携带的点数
     * @param x0     第 1 个点的 x 坐标
     * @param y0     第 1 个点的 y 坐标
     * @param x1     第 2 个点的 x 坐标
     * @param y1     第 2 个点的 y 坐标
     * @param x2     第 3 个点的 x 坐标
     * @param y2     第 3 个点的 y 坐标
     * @return 此对象，便于链式调用
     */
    private Path add(Type type, int points,
                     float x0, float y0, float x1, float y1, float x2, float y2) {
        ensureCapacity(count + 1);
        int ci = count++;
        types[ci] = (byte) type.ordinal();
        pointCounts[ci] = (byte) points;
        int base = ci * MAX_POINTS_PER_COMMAND * 2;
        if (points > 0) {
            coords[base] = x0;
            coords[base + 1] = y0;
        }
        if (points > 1) {
            coords[base + 2] = x1;
            coords[base + 3] = y1;
        }
        if (points > 2) {
            coords[base + 4] = x2;
            coords[base + 5] = y2;
        }
        return this;
    }

    /**
     * 确保内部数组可容纳给定数量的命令，容量不足时按倍数增长并复制已有数据。
     *
     * @param needed 需要的命令容量
     */
    private void ensureCapacity(int needed) {
        if (needed <= types.length) {
            return;
        }
        // 此处 needed == count + 1 <= types.length + 1 <= types.length * 2，按 2 倍增长必然够用。
        int newCap = types.length * 2;
        byte[] newTypes = new byte[newCap];
        byte[] newCounts = new byte[newCap];
        float[] newCoords = new float[newCap * MAX_POINTS_PER_COMMAND * 2];
        System.arraycopy(types, 0, newTypes, 0, count);
        System.arraycopy(pointCounts, 0, newCounts, 0, count);
        System.arraycopy(coords, 0, newCoords, 0, count * MAX_POINTS_PER_COMMAND * 2);
        types = newTypes;
        pointCounts = newCounts;
        coords = newCoords;
    }

    /** 路径命令类型。 */
    public enum Type {

        /** 移动当前点但不绘制。 */
        MOVE_TO,

        /** 从当前点到目标点绘制直线。 */
        LINE_TO,

        /** 绘制二次贝塞尔曲线。 */
        QUADRATIC_CURVE_TO,

        /** 绘制三次贝塞尔曲线。 */
        BEZIER_CURVE_TO,

        /** 通过绘制直线回到子路径起点来关闭当前子路径。 */
        CLOSE
    }
}
