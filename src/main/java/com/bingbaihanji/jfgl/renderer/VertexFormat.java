package com.bingbaihanji.jfgl.renderer;

/**
 * 顶点布局常量与颜色打包工具。
 * <p>
 * 布局（共 24 字节）：
 * <pre>
 *  偏移  0  vec2 float            位置（已烘焙到 NDC）
 *  偏移  8  vec2 float            纹理坐标
 *  偏移 16  vec4 ubyte normalized 颜色（预乘 alpha）
 *  偏移 20  uint                  拾取 ID
 * </pre>
 */
public final class VertexFormat {

    /** 每个顶点占用的 32 位字数（6 字 = 24 字节）。 */
    public static final int WORDS_PER_VERTEX = 6;

    /** 每个顶点占用的字节数。 */
    public static final int STRIDE_BYTES = 24;

    /** 位置（vec2 float）在顶点内的字节偏移。 */
    public static final int OFFSET_POSITION = 0;

    /** 纹理坐标（vec2 float）在顶点内的字节偏移。 */
    public static final int OFFSET_UV = 8;

    /** 颜色（vec4 ubyte normalized，预乘 alpha）在顶点内的字节偏移。 */
    public static final int OFFSET_COLOR = 16;

    /** 拾取 ID（uint）在顶点内的字节偏移。 */
    public static final int OFFSET_ID = 20;

    private VertexFormat() {
    }

    /**
     * 把直通（非预乘）的 RGBA 分量打包为预乘后的 32 位整数。
     * <p>
     * 字节序为 RGBA，即最高字节是 R。分量先夹紧到 0..1，再预乘 alpha。
     * 预乘是为了避免重叠的半透明抗锯齿边缘出现二次混合的暗缝。
     *
     * @param r 红色分量（0-1，直通）
     * @param g 绿色分量（0-1，直通）
     * @param b 蓝色分量（0-1，直通）
     * @param a alpha 分量（0-1）
     * @return 打包后的 RGBA 整数
     */
    public static int packPremultiplied(float r, float g, float b, float a) {
        float ca = clamp(a);
        int ri = toByte(clamp(r) * ca);
        int gi = toByte(clamp(g) * ca);
        int bi = toByte(clamp(b) * ca);
        int ai = toByte(ca);
        return (ri << 24) | (gi << 16) | (bi << 8) | ai;
    }

    /**
     * 把分量夹紧到 0..1。
     *
     * @param v 原始分量
     * @return 夹紧后的分量
     */
    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * 把 0..1 的分量量化为 0..255 的整数（四舍五入）。
     *
     * @param v 已夹紧的分量
     * @return 量化后的字节值
     */
    private static int toByte(float v) {
        return (int) (v * 255f + 0.5f) & 0xFF;
    }
}
