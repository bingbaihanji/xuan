package com.bingbaihanji.jfgl.chartrender;

/**
 * 图表渲染器用的 GLSL 源码。
 *
 * <h2>一个顶点着色器，两个片段着色器</h2>
 * <p>绘制用 {@link #LINE_FRAGMENT}，拾取用 {@link #PICK_FRAGMENT}，
 * 两者共用 {@link #LINE_VERTEX}。这与 {@code RenderBatch} 里
 * "SDF 文本复用同一个顶点着色器、只换片段着色器"是同一个做法。
 *
 * <h2>顶点着色器只输出 vId，绘制时没人用</h2>
 * <p>顶点着色器声明了 {@code flat out uint vId}，而绘制用的片段着色器不声明对应的
 * {@code in}——GLSL 允许，未使用的输出会被丢弃。这样两个程序能共用一份顶点源码。
 *
 * <h2>位置在着色器里算，不在 CPU 算</h2>
 * <p>这是 ② 的核心：数据在 GPU 里存的是<b>数值</b>不是屏幕坐标，
 * 于是滚动、缩放、自动量程、窗口尺寸变化全都是改 uniform，<b>零重传</b>。
 *
 * <h2>横轴为什么用"相对窗口左端的偏移"</h2>
 * <p>绝对数据下标是 {@code long}（每秒几百万点，{@code int} 几十分钟就溢出）。
 * 着色器里用不了 long，所以 CPU 每帧算好"本次 draw 的第一个实例相对窗口左端差多少"，
 * 传一个小的浮点进来。**不要改成传绝对下标**——那会在大约 36 分钟后静默错位。
 */
final class SeriesShaders {

    /**
     * 折线族的顶点着色器。
     *
     * <p>每个实例是一个线段。两端 y 值由两个实例属性供给——它们是<b>同一个缓冲、
     * 偏移差 4 字节</b>（见 {@code LineSeriesRenderer} 的 VAO 配置）。
     */
    static final String LINE_VERTEX = """
                                      #version 330 core
                                      
                                      // —— 每顶点（divisor = 0）：单位四边形的四个角 ——
                                      layout(location = 0) in vec2 aCorner;   // (0/1, 0/1)
                                      
                                      // —— 每实例（divisor = 1）：线段两端的数值 ——
                                      layout(location = 1) in float aY0;
                                      layout(location = 2) in float aY1;
                                      
                                      // 绘图区（设备像素，原点左上）
                                      uniform vec4  uPlotRect;      // x, y, w, h
                                      uniform vec2  uViewport;      // 帧缓冲宽高
                                      // 数值窗口
                                      uniform vec2  uValueRange;    // min, max
                                      // 横轴：本次 draw 的第一个实例相对窗口左端的偏移（见 WindowRange）
                                      uniform float uFirstRelIndex;
                                      uniform float uPxPerSample;
                                      // 线宽（半宽，设备像素）
                                      uniform float uHalfWidth;
                                      // 拾取容差：绘制时为 0，ID pass 时用一个更大的值，
                                      // 让"点在线旁边几像素"也能命中。max() 让两份共用一个着色器。
                                      uniform float uPickTolerance;
                                      // 颜色（直通，非预乘）
                                      uniform vec4  uColor;
                                      // 拾取 ID
                                      //
                                      // 必须是 int 而不是 uint：本项目的 ShaderProgram 只有 glUniform1i，
                                      // 对 uint uniform 用 glUniform1i 会报 GL_INVALID_OPERATION 且**值保持 0**——
                                      // 而 0 正是"什么都没命中"，于是图表拾取会静默地永远返回没点到。
                                      // （实测：glUniform1i → 0x502、回读 0；glUniform1ui → 0x0、回读正确。）
                                      uniform int  uPickId;
                                      
                                      out vec4 vColor;
                                      flat out uint vId;
                                      
                                      void main() {
                                          vId = uint(uPickId);   // 发号从 1 开始、恒为正，转换无损
                                          vColor = uColor;
                                      
                                          // 任一端是 NaN 就把整个四边形退化到裁剪空间之外。
                                          //
                                          // 不能靠"NaN 自然传播"：NaN 位置的光栅化行为是未定义的——
                                          // 驱动可能丢掉它，也可能产生垃圾像素，而且不保证丢掉。
                                          if (isnan(aY0) || isnan(aY1)) {
                                              gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
                                              return;
                                          }
                                      
                                          float uMin = uValueRange.x;
                                          float uMax = uValueRange.y;
                                          float span = uMax - uMin;
                                      
                                          // 数值 -> 屏幕 y。值越大越靠上，所以要翻。
                                          float fy0 = (aY0 - uMin) / span;
                                          float fy1 = (aY1 - uMin) / span;
                                          float sy0 = uPlotRect.y + (1.0 - fy0) * uPlotRect.w;
                                          float sy1 = uPlotRect.y + (1.0 - fy1) * uPlotRect.w;
                                      
                                          // 实例序号 -> 数据下标 -> 屏幕 x。
                                          // 全程用"相对窗口左端"的小数，避免绝对下标溢出 int。
                                          float rel = uFirstRelIndex + float(gl_InstanceID);
                                          float sx0 = uPlotRect.x + rel * uPxPerSample;
                                          float sx1 = sx0 + uPxPerSample;
                                      
                                          vec2 p0 = vec2(sx0, sy0);
                                          vec2 p1 = vec2(sx1, sy1);
                                      
                                          // 沿屏幕空间法线把四边形撑成有粗细的线段。
                                          vec2 delta = p1 - p0;
                                          float len = length(delta);
                                          vec2 dir = len > 0.0 ? delta / len : vec2(1.0, 0.0);
                                          vec2 nrm = vec2(-dir.y, dir.x);
                                      
                                          float halfWidth = max(uHalfWidth, uPickTolerance);
                                          vec2 base = aCorner.x < 0.5 ? p0 : p1;
                                          vec2 offset = nrm * halfWidth * (aCorner.y < 0.5 ? -1.0 : 1.0);
                                          vec2 p = base + offset;
                                      
                                          // 屏幕像素 -> NDC。y 要翻：屏幕原点在左上、y 向下。
                                          vec2 ndc = vec2(p.x / uViewport.x * 2.0 - 1.0,
                                                          1.0 - p.y / uViewport.y * 2.0);
                                          gl_Position = vec4(ndc, 0.0, 1.0);
                                      }
                                      """;

    /**
     * 绘制用的片段着色器。
     *
     * <p><strong>颜色是直通（非预乘）的，最后一步必须做预乘。</strong>
     * 半透明线段的端点会互相重叠，不预乘就会出现二次混合的暗缝——
     * 与 {@code RenderBatch} 里"混合因子顺序不能调换"是同一类问题。
     */
    static final String LINE_FRAGMENT = """
                                        #version 330 core
                                        in vec4 vColor;
                                        out vec4 fragColor;
                                        void main() {
                                            fragColor = vec4(vColor.rgb * vColor.a, vColor.a);
                                        }
                                        """;

    /**
     * 拾取用的片段着色器。
     *
     * <p>直接写出 ID，不看颜色、不看 alpha。{@code flat} 不能省——ID 是整数，
     * 跨三角形插值出来的中间值对应不存在的对象。
     */
    static final String PICK_FRAGMENT = """
                                        #version 330 core
                                        flat in uint vId;
                                        out uint fragId;
                                        void main() {
                                            fragId = vId;
                                        }
                                        """;

    private SeriesShaders() {
    }
}
