package com.bingbaihanji.xuan.math;

/**
 * 3x3 矩阵，按列优先顺序存储，适用于 2D 仿射变换。
 * <p>
 * 列优先布局（匹配 OpenGL 约定）：
 * <pre>
 *  m[0] m[3] m[6]
 *  m[1] m[4] m[7]
 *  m[2] m[5] m[8]
 * </pre>
 * 对于 2D 仿射变换，底行 {@code [m[2], m[5], m[8]]} 通常为 {@code [0, 0, 1]}。
 */
public final class Mat3 {

    /** 矩阵数据（列优先） */
    private final float[] m = new float[9];

    /** 创建零矩阵。 */
    public Mat3() {
        // Java 会自动初始化为零
    }

    /**
     * 返回新的单位矩阵。
     *
     * <pre>
     *  1  0  0
     *  0  1  0
     *  0  0  1
     * </pre>
     */
    public static Mat3 identity() {
        Mat3 mat = new Mat3();
        mat.m[0] = 1f;
        mat.m[4] = 1f;
        mat.m[8] = 1f;
        return mat;
    }

    /**
     * 返回 2D 平移矩阵。
     *
     * <pre>
     *  1  0  tx
     *  0  1  ty
     *  0  0   1
     * </pre>
     *
     * @param tx 沿 x 轴的平移量
     * @param ty 沿 y 轴的平移量
     */
    public static Mat3 translation(float tx, float ty) {
        Mat3 mat = identity();
        mat.m[6] = tx;
        mat.m[7] = ty;
        return mat;
    }

    /**
     * 返回 2D 缩放矩阵。
     *
     * <pre>
     *  sx  0  0
     *   0 sy  0
     *   0  0  1
     * </pre>
     *
     * @param sx 沿 x 轴的缩放因子
     * @param sy 沿 y 轴的缩放因子
     */
    public static Mat3 scale(float sx, float sy) {
        Mat3 mat = identity();
        mat.m[0] = sx;
        mat.m[4] = sy;
        return mat;
    }

    /**
     * 返回 2D 逆时针旋转矩阵。
     *
     * <pre>
     *  cos  -sin  0
     *  sin   cos  0
     *    0     0  1
     * </pre>
     *
     * @param radians 旋转角度（弧度，正值为逆时针）
     */
    public static Mat3 rotation(float radians) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        Mat3 mat = identity();
        mat.m[0] = cos;
        mat.m[1] = sin;
        mat.m[3] = -sin;
        mat.m[4] = cos;
        return mat;
    }

    /**
     * 返回此矩阵与 {@code other} 的乘积 {@code this * other}。
     * <p>
     * 结果是先应用 {@code other}，然后应用 {@code this}。
     *
     * @param other 右操作数
     * @return 乘积矩阵
     */
    public Mat3 multiply(Mat3 other) {
        Mat3 result = new Mat3();
        float[] a = this.m;
        float[] b = other.m;
        float[] r = result.m;

        for (int col = 0; col < 3; col++) {
            for (int row = 0; row < 3; row++) {
                float sum = 0f;
                for (int k = 0; k < 3; k++) {
                    sum += a[k * 3 + row] * b[col * 3 + k];
                }
                r[col * 3 + row] = sum;
            }
        }
        return result;
    }

    /**
     * 使用此矩阵变换 2D 点（隐式 w=1 的仿射变换）。
     * <p>
     * 计算 {@code [x', y']}，其中：
     * <pre>
     *  x' = m[0]*x + m[3]*y + m[6]
     *  y' = m[1]*x + m[4]*y + m[7]
     * </pre>
     *
     * @param v 输入点/向量
     * @return 变换后坐标的 {@link Vec2}
     */
    public Vec2 transform(Vec2 v) {
        float x = m[0] * v.x() + m[3] * v.y() + m[6];
        float y = m[1] * v.x() + m[4] * v.y() + m[7];
        return new Vec2(x, y);
    }

    /**
     * 返回底层列优先浮点数组的副本（长度为 9）。
     *
     * @return 包含矩阵元素的新浮点数组
     */
    public float[] toArray() {
        return m.clone();
    }

    @Override
    public String toString() {
        return String.format(
                "Mat3[%.2f %.2f %.2f | %.2f %.2f %.2f | %.2f %.2f %.2f]",
                m[0], m[3], m[6],
                m[1], m[4], m[7],
                m[2], m[5], m[8]
        );
    }
}
