package com.bingbaihanji.xuan.text;

/**
 * 把字形的覆盖度位图转成有符号距离场（SDF）。
 *
 * <h2>为什么是距离场</h2>
 * <p>位图字形一旦放大就是拉伸的糊图；距离场记录的是"每个像素离字形边界多远"，
 * 因此在任意尺寸下都能用屏幕空间的导数重新求出锐利的边缘。
 * 代价是字形内部不再有细节——这正是文本这种"只有轮廓有意义"的图形所不需要的。
 *
 * <h2>编码</h2>
 * <p>输出是 8 位无符号：<strong>0 = 远在字形外，128 = 边界，255 = 远在字形内</strong>。
 * 在 {@link #SPREAD} 像素之外饱和。
 *
 * <p>着色器里把它当 {@code d - 0.5} 用，配合 {@code fwidth} 求出屏幕空间的
 * 平滑宽度——**平滑宽度必须由屏幕空间决定**，预先烘进纹理是不可能的，
 * 因为同一个字形会在不同尺寸下被绘制。
 *
 * <h2>算法</h2>
 * <p>阈值化成二值掩码后，做两次精确欧氏距离变换（Felzenszwalb &amp; Huttenlocher，
 * 逐行再做逐列的一维平方距离变换），分别得到"到最近内部像素的距离"与
 * "到最近外部像素的距离"，相减即得有符号距离。
 *
 * <p><strong>必须是欧氏距离，不能用八邻域的切比雪夫近似</strong>：后者算出的
 * 斜向距离偏小，会让字形的斜笔画（撇、捺、点）显得比正交笔画粗。
 *
 * <p>纯函数、零依赖、无任何 GL 调用，因此可以彻底单测。
 */
public final class SdfGenerator {

    /**
     * 距离场向外/向内延伸的像素数。
     *
     * <p>它同时决定三件事：字形位图的外扩边宽、能被平滑处理的笔画粗细上限、
     * 以及**双线性过滤时相邻字形之间会不会互相渗色**——外扩出来的这一圈
     * 把采样限制在自己的槽位内。
     */
    public static final int SPREAD = 8;

    /** 覆盖度到达这个值即视为字形内部。 */
    private static final int DEFAULT_THRESHOLD = 128;

    private SdfGenerator() {
    }

    /**
     * 生成距离场。
     *
     * @param coverage 覆盖度位图，长度必须为 {@code width * height}，按行存储
     * @param width    位图宽度
     * @param height   位图高度
     * @return 距离场，尺寸为 {@code (width + 2*SPREAD) * (height + 2*SPREAD)}，按行存储
     */
    public static byte[] generate(byte[] coverage, int width, int height) {
        int w = width + 2 * SPREAD;
        int h = height + 2 * SPREAD;

        // 把覆盖度放进带外扩的网格中心；外圈保持「外部」（false）
        boolean[] inside = new boolean[w * h];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((coverage[y * width + x] & 0xFF) >= DEFAULT_THRESHOLD) {
                    inside[(y + SPREAD) * w + (x + SPREAD)] = true;
                }
            }
        }

        float[] distIn = distanceTo(inside, w, h, true);
        float[] distOut = distanceTo(inside, w, h, false);

        byte[] out = new byte[w * h];
        for (int i = 0; i < w * h; i++) {
            // 内部为正、外部为负，边界处恰好为 0
            double signed = Math.sqrt(distOut[i]) - Math.sqrt(distIn[i]);
            float t = (float) (0.5 + signed / (2.0 * SPREAD));
            if (t < 0f) {
                t = 0f;
            } else if (t > 1f) {
                t = 1f;
            }
            out[i] = (byte) Math.round(t * 255f);
        }
        return out;
    }

    /**
     * 算出每个像素到最近的、满足 {@code want} 的像素的<strong>平方</strong>欧氏距离。
     *
     * @param inside 二值掩码
     * @param w      网格宽
     * @param h      网格高
     * @param want   要找的目标是内部（true）还是外部（false）
     * @return 平方距离数组
     */
    private static float[] distanceTo(boolean[] inside, int w, int h, boolean want) {
        final float INF = 1e20f;
        float[] f = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            f[i] = (inside[i] == want) ? 0f : INF;
        }

        // 逐行做一维距离变换，再逐列做一次。两次一维变换合起来等价于二维欧氏距离变换。
        int n = Math.max(w, h);
        float[] src = new float[n];
        float[] dst = new float[n];
        int[] v = new int[n];
        double[] z = new double[n + 1];

        for (int y = 0; y < h; y++) {
            System.arraycopy(f, y * w, src, 0, w);
            edt1d(src, dst, v, z, w);
            System.arraycopy(dst, 0, f, y * w, w);
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                src[y] = f[y * w + x];
            }
            edt1d(src, dst, v, z, h);
            for (int y = 0; y < h; y++) {
                f[y * w + x] = dst[y];
            }
        }
        return f;
    }

    /**
     * 一维平方距离变换（Felzenszwalb &amp; Huttenlocher 的下包络法）。
     *
     * <p>输入 {@code f} 是每个位置的初始代价，输出 {@code d} 满足
     * {@code d[q] = min_p ( (q-p)^2 + f[p] )}。这是**精确**的，不是近似。
     *
     * @param f   输入代价
     * @param d   输出距离（平方）
     * @param v   抛物线的位置栈（工作数组，由调用方复用以避免每行分配）
     * @param z   抛物线的分界点栈（同上）
     * @param n   长度
     */
    private static void edt1d(float[] f, float[] d, int[] v, double[] z, int n) {
        int k = 0;
        v[0] = 0;
        z[0] = Double.NEGATIVE_INFINITY;
        z[1] = Double.POSITIVE_INFINITY;

        for (int q = 1; q < n; q++) {
            double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k]))
                    / (2.0 * q - 2.0 * v[k]);
            while (s <= z[k]) {
                k--;
                s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k]))
                        / (2.0 * q - 2.0 * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Double.POSITIVE_INFINITY;
        }

        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) {
                k++;
            }
            double diff = q - v[k];
            d[q] = (float) (diff * diff) + f[v[k]];
        }
    }
}
