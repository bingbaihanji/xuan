package com.bingbaihanji.jfgl.renderer;

/**
 * 顶点布局常量与颜色打包工具。
 * <p>
 * 布局（共 32 字节）：
 * <pre>
 *  偏移  0  vec2 float            位置（已烘焙到 NDC）
 *  偏移  8  vec2 float            纹理坐标
 *  偏移 16  vec4 ubyte normalized 颜色（预乘 alpha）
 *  偏移 20  uint                  拾取 ID
 *  偏移 24  vec2 float            抗锯齿边距（横向、沿向）
 * </pre>
 *
 * <p>颜色按<strong>地址升序</strong>存放为 {@code R,G,B,A} 四个字节——即偏移 16 处是 R、
 * 偏移 19 处是 A。{@code glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, normalized, ...)}
 * 正是按地址升序把 4 个字节读成 {@code vec4} 的 {@code (x,y,z,w)}，因此着色器里的
 * {@code (r,g,b,a)} 与这里的存放顺序一致。打包函数 {@link #packPremultiplied} 的整数布局
 * 也必须按这个顺序反推（见其说明）。
 */
public final class VertexFormat {

    /** 每个顶点占用的 32 位字数（8 字 = 32 字节）。 */
    public static final int WORDS_PER_VERTEX = 8;

    /** 每个顶点占用的字节数。 */
    public static final int STRIDE_BYTES = 32;

    /** 位置（vec2 float）在顶点内的字节偏移。 */
    public static final int OFFSET_POSITION = 0;

    /** 纹理坐标（vec2 float）在顶点内的字节偏移。 */
    public static final int OFFSET_UV = 8;

    /** 颜色（vec4 ubyte normalized，预乘 alpha）在顶点内的字节偏移。 */
    public static final int OFFSET_COLOR = 16;

    /** 拾取 ID（uint）在顶点内的字节偏移。 */
    public static final int OFFSET_ID = 20;

    /**
     * 抗锯齿边距（vec2 float）在顶点内的字节偏移。
     *
     * <p><strong>两个分量分别是"横向"与"沿向"</strong>。简言之：{@code x} 是到中心线的
     * 有符号距离（归一化到真实半线宽，{@code ±1} 是两条真实外缘），
     * {@code y} 是到最近端帽的沿路径距离（同样归一化）：<strong>{@code 0} 是端帽线，
     * 带内为正，带外为负</strong>——片段着色器正是靠这个符号区分"在线内"与"在线外"，
     * 描边的端帽羽化就是靠带外那一圈负值做出来的。
     * （这里曾经写的是"恒 ≥ 0"，那是错的：沿向要能表示"越过了端帽线"，
     * 否则端帽外侧那圈 fringe 与带内无法区分——而平头端那圈 fringe 的沿向
     * 只能由 {@code StrokeGenerator} 的 {@code capExtension} 显式要出来，
     * <strong>不能靠把折线两端延长</strong>：延长点会成为折线自己的弧长端点，
     * 那里 {@code min(arc, totalLength - arc)} 恒为 0。见其 {@code rawEdges()}。）
     *
     * <p><strong>填充与文本两个分量都写 0</strong>——那时该 vary 在图元上是常量、
     * 屏幕空间导数为 0，片段着色器据此走"完全覆盖"的分支。
     * 也就是说：填充/文本与描边是靠 <strong>{@code fwidth == 0} 区分的，
     * 不是靠沿向的符号</strong>——它们写 0，而描边里同样有沿向为 0 的顶点
     * （正落在端帽线上的那些，它们是边界而非内部），
     * 以及<strong>横向也为 0</strong> 的接头三角形（它要的就是"完全覆盖"，见
     * {@code StrokeGenerator.emitJoin}）——所以"两个分量都是 0"并不专属于填充与文本，
     * 它只说明"这一维在这个图元上不携带梯度"。
     * 这条不是特例，是判据本身（片段着色器里那两个 {@code w > 0.0} 判别式
     * 就在 {@code RenderBatch.FRAGMENT_SHADER} 里，与描边羽化同时落地：
     * 它们同时是"这里不需要 {@code uAntialias} uniform"的原因）。
     *
     * <p>为什么需要"沿向"：{@code Gc} 的描边目前只用平头端（{@code Cap.BUTT}），
     * 而平头端的端边<strong>垂直于线段</strong>——它的边界上横向坐标从 {@code +1} 连续走到
     * {@code -1}（中途经过 0），也就是说<strong>平头端的边界由"沿向"描述，横向对它一无所知</strong>。
     * 只用横向的后果是：一条 4px 横线，上下长边有抗锯齿、左右两端是硬角。
     */
    public static final int OFFSET_EDGE = 24;

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
