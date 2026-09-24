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
 *
 * <p>颜色按<strong>地址升序</strong>存放为 {@code R,G,B,A} 四个字节——即偏移 16 处是 R、
 * 偏移 19 处是 A。{@code glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, normalized, ...)}
 * 正是按地址升序把 4 个字节读成 {@code vec4} 的 {@code (x,y,z,w)}，因此着色器里的
 * {@code (r,g,b,a)} 与这里的存放顺序一致。打包函数 {@link #packPremultiplied} 的整数布局
 * 也必须按这个顺序反推（见其说明）。
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
     * <strong>整数的最高字节是 A、最低字节是 R</strong>（{@code (a<<24)|(b<<16)|(g<<8)|r}）。
     * 这样配合写入方 {@link VertexWriter} 的小端 {@code ByteBuffer}，
     * 落进内存的字节顺序才是 {@code R,G,B,A}——也就是 GL 读到的 {@code vec4(r,g,b,a)}。
     * 分量先夹紧到 0..1，再预乘 alpha。
     * 预乘是为了避免重叠的半透明抗锯齿边缘出现二次混合的暗缝。
     *
     * @param r 红色分量（0-1，直通）
     * @param g 绿色分量（0-1，直通）
     * @param b 蓝色分量（0-1，直通）
     * @param a alpha 分量（0-1）
     * @return 打包后的整数（内存字节序为 R,G,B,A）
     */
    public static int packPremultiplied(float r, float g, float b, float a) {
        float ca = clamp(a);
        int ri = toByte(clamp(r) * ca);
        int gi = toByte(clamp(g) * ca);
        int bi = toByte(clamp(b) * ca);
        int ai = toByte(ca);
        // 必须让**内存字节序**为 R,G,B,A。写入方 VertexWriter 用的是小端 ByteBuffer，
        // 整数的低字节落在低地址，而 glVertexAttribPointer(..., GL_UNSIGNED_BYTE, ...)
        // 是按地址升序把 4 个字节读成 vec4 的 (x,y,z,w)，也就是 (r,g,b,a)。
        // 因此整数的最高字节必须是 A —— 写反的话通道会整体错位：
        // 不透明绿 (0,1,0,1) 会被读成 (1,0,1,0)，即 alpha=0 的品红，肉眼完全看不见。
        return (ai << 24) | (bi << 16) | (gi << 8) | ri;
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
