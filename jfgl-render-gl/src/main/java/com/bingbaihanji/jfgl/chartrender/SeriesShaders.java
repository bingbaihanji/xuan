package com.bingbaihanji.jfgl.chartrender;

/**
 * 图表渲染器用的 GLSL 源码。
 *
 * <h2>顶点着色器按"一个实例是什么"分，片段着色器按"这一趟是画还是拾取"分</h2>
 * <p>五个顶点程序，各对应一种实例：
 * <ul>
 *   <li>{@link #LINE_VERTEX}：实例是<b>一个线段</b>（折线、频谱）；</li>
 *   <li>{@link #SCATTER_VERTEX}：实例是<b>一个点</b>（散点，几何居中的小四边形）；</li>
 *   <li>{@link #STEP_VERTEX}：实例是<b>一个阶梯段</b>（一条水平段 + 一个拐角 + 一条竖直段）；</li>
 *   <li>{@link #AREA_VERTEX}：实例是<b>一个梯形</b>（线段两端各向基线垂下来）；</li>
 *   <li>{@link #BAR_VERTEX}：实例是<b>一根柱</b>（在数值与基线之间的矩形）。</li>
 * </ul>
 * <p>两个片段源码只有一份，按"这一趟是画还是拾取"二选一：绘制用
 * {@link #LINE_FRAGMENT}，拾取用 {@link #PICK_FRAGMENT}。两者正交，
 * 于是程序数 = 顶点程序数 × 2。这与 {@code RenderBatch} 里
 * "SDF 文本复用同一个顶点着色器、只换片段着色器"是同一个做法。
 *
 * <h2>顶点着色器只输出 vId，绘制时没人用</h2>
 * <p>所有顶点着色器都声明了 {@code flat out uint vId}，而绘制用的片段着色器不声明对应的
 * {@code in}——GLSL 允许，未使用的输出会被丢弃。这样它们能共用同一对片段源码。
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
     * 散点的顶点着色器。
     *
     * <p>每个实例是<b>一个点</b>，几何是<b>以该点为中心</b>的小四边形。
     * 它与 {@link #LINE_VERTEX} 的差别只有三处：
     * <ol>
     *   <li>实例数是点数（{@code writeIndex - 1}）而不是线段数，见
     *       {@link WindowRange#computePoints}；</li>
     *   <li>只有一个实例属性 {@code aY}——点自己就是完整的，不需要"第二端"；</li>
     *   <li>几何居中，尺寸用 {@code uMarkerSize} 而不是 {@code uHalfWidth}。</li>
     * </ol>
     *
     * <h2>点的 x 与折线的顶点取同一个映射</h2>
     * <p>{@code sx = plotX + rel · pxPerSample}——与 {@link #LINE_VERTEX} 的
     * {@code sx0} 逐像素一致，也与 {@code ChartRenderLayout.screenX} 一致。
     * <b>不要写成"单元格中心"（{@code (rel + 0.5) · pxPerSample}）</b>：
     * 那会让散点整体右移半个采样间隔——刻度线（按 {@code screenX} 定位）会正好
     * 落在两个标记点之间，同一个系列画成折线时标记点也会浮在线段顶点右边半个间隔；
     * 而窗口是 {@code [0, N-1]}（本仓库所有校验器里的图都是这么配的）时，
     * 最右那个点会被画到绘图区之外、被 scissor 整个裁掉——<b>最后一个数据点静默消失</b>。
     *
     * <h2>uMarkerSize 是边长，uPickTolerance 是半宽</h2>
     * <p><b>注意 {@code uMarkerSize} 是边长，而 {@code Series.markerSize()} 声明的是半径</b>——
     * 换算（乘 2）由 {@code ScatterSeriesRenderer} 在传进来之前做掉，着色器只认几何。
     * 两处各持一半解释的话，用户设半径 5 会拿到宽 5 的方块（本该宽 10），
     * 而画面上没有任何症状。
     * <p>绘制时容差为 0，{@code max()} 取到的就是真实边长；ID pass 时容差非 0，
     * 标记被撑大成一个"热区"。两个 pass 因此共用一个顶点程序，
     * 与折线那边用 {@code max(uHalfWidth, uPickTolerance)} 是同一个做法。
     */
    static final String SCATTER_VERTEX = """
                                      #version 330 core

                                      // —— 每顶点（divisor = 0）：单位四边形的四个角 ——
                                      layout(location = 0) in vec2  aCorner;   // (0/1, 0/1)

                                      // —— 每实例（divisor = 1）：该点的数值 ——
                                      layout(location = 1) in float aY;

                                      // 绘图区（设备像素，原点左上）
                                      uniform vec4  uPlotRect;      // x, y, w, h
                                      uniform vec2  uViewport;      // 帧缓冲宽高
                                      // 数值窗口
                                      uniform vec2  uValueRange;    // min, max
                                      // 横轴：本次 draw 的第一个实例相对窗口左端的偏移（见 WindowRange）
                                      uniform float uFirstRelIndex;
                                      uniform float uPxPerSample;
                                      // 标记点边长（设备像素）
                                      uniform float uMarkerSize;
                                      // 拾取容差（半宽，设备像素）：绘制时为 0，ID pass 时撑大热区
                                      uniform float uPickTolerance;
                                      // 颜色（直通，非预乘）
                                      uniform vec4  uColor;
                                      // 拾取 ID
                                      //
                                      // 必须是 int 而不是 uint：本项目的 ShaderProgram 只有 glUniform1i，
                                      // 对 uint uniform 用 glUniform1i 会报 GL_INVALID_OPERATION 且**值保持 0**——
                                      // 而 0 正是"什么都没命中"，于是图表拾取会静默地永远返回没点到。
                                      // （实测：glUniform1i → 0x502、回读 0；glUniform1ui → 0x0、回读正确。）
                                      uniform int   uPickId;

                                      out vec4 vColor;
                                      flat out uint vId;

                                      void main() {
                                          vId = uint(uPickId);   // 发号从 1 开始、恒为正，转换无损
                                          vColor = uColor;

                                          // NaN 的点整个退化到裁剪空间之外。
                                          //
                                          // 与折线那边同一个理由：不能靠"NaN 自然传播"——
                                          // NaN 位置的光栅化行为是未定义的，驱动可能丢掉它，
                                          // 也可能产生垃圾像素，而且不保证丢掉。
                                          if (isnan(aY)) {
                                              gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
                                              return;
                                          }

                                          float uMin = uValueRange.x;
                                          float uMax = uValueRange.y;
                                          float fy = (aY - uMin) / (uMax - uMin);
                                          float sy = uPlotRect.y + (1.0 - fy) * uPlotRect.w;

                                          // 实例序号 -> 数据下标 -> 屏幕 x（与 LINE_VERTEX 的 sx0 同一个映射）
                                          float rel = uFirstRelIndex + float(gl_InstanceID);
                                          float sx = uPlotRect.x + rel * uPxPerSample;

                                          // 居中的四边形：aCorner 的 0/1 映射到 ∓半个边长。
                                          //
                                          // 绘制时 uPickTolerance = 0，所以 max() 取到的是真实边长；
                                          // markerSize ≤ 0 时边长为 0，四个角重合成一个点——
                                          // 光栅化不出任何片段，与折线的"零线宽"是同一种退化
                                          // （见 ScatterSeriesRenderer 的说明）。
                                          float half = max(uMarkerSize, uPickTolerance * 2.0) * 0.5;
                                          vec2 p = vec2(sx, sy) + (aCorner - vec2(0.5)) * (half * 2.0);

                                          // 屏幕像素 -> NDC。y 要翻：屏幕原点在左上、y 向下。
                                          vec2 ndc = vec2(p.x / uViewport.x * 2.0 - 1.0,
                                                          1.0 - p.y / uViewport.y * 2.0);
                                          gl_Position = vec4(ndc, 0.0, 1.0);
                                      }
                                      """;

    /**
     * 阶梯线的顶点着色器。
     *
     * <p>每个实例是<b>一个阶梯段</b>：从 {@code (x_i, y_i)} 先水平走到 {@code (x_{i+1}, y_i)}，
     * 再竖直走到 {@code (x_{i+1}, y_{i+1})}——"先横后竖"（step-after）。
     *
     * <h2>为什么它不能由 {@link #LINE_VERTEX} 换几个参数画出来</h2>
     * <p>折线的四边形是"沿 {@code p0→p1} 的法向各撑开半个线宽"，而阶梯段是
     * <b>两个方向不同的四边形在拐角处相接</b>。用折线那段代码画阶梯只有两条路，
     * 两条都是错的：
     * <ul>
     *   <li>把 {@code (x_i,y_i)→(x_{i+1},y_{i+1})} 当斜线画：阶梯被拉成折线，
     *       <b>形状是错的，画面却完全正常</b>（与"按线性去画对数轴"同类）；</li>
     *   <li>拆成"一条水平段 + 一条竖直段"两次 draw：拐角处会缺一个
     *       {@code 半宽 × 半宽} 的方口——柱状/阶梯图里每级台阶的外角都豁一块，
     *       而"豁了一小块"看起来像抗锯齿。</li>
     * </ul>
     * <p>所以这里用<b>六个角</b>（三个点 × 两侧）一次画出两个四边形，拐角用
     * <b>斜接（miter）</b>：拐角处的法向取两条段法向的角平分线，长度按
     * {@code 1/cos(夹角/2)} 放大。直角时那个放大系数恰好是 {@code √2}，
     * 于是外角正好补成一个完整的方块（见 {@code StepSeriesRenderer} 的断言）。
     *
     * <h2>角点的含义与顺序</h2>
     * <p>{@code aCorner.x} 是点序号 {@code 0/1/2}，{@code aCorner.y} 是法向的哪一侧；
     * 六个角按 {@code (0,0),(0,1),(1,0),(1,1),(2,0),(2,1)} 组成一条
     * {@code GL_TRIANGLE_STRIP}——四个三角形、两个四边形，共用中间那两个角点。
     * 中间的四边形退化成零面积（{@code p2 == p1}，本段是平的）时不产生任何片段，
     * 于是"平的阶梯"就是一条普通水平线段。
     */
    static final String STEP_VERTEX = """
                                      #version 330 core

                                      // —— 每顶点（divisor = 0）：六个角 ——
                                      // aCorner.x = 点序号（0/1/2），aCorner.y = 法向的哪一侧（0/1）
                                      layout(location = 0) in vec2  aCorner;

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
                                      // 拾取容差：绘制时为 0，ID pass 时用一个更大的值
                                      uniform float uPickTolerance;
                                      // 颜色（直通，非预乘）
                                      uniform vec4  uColor;
                                      // 拾取 ID（必须是 int，理由见 LINE_VERTEX）
                                      uniform int   uPickId;

                                      out vec4 vColor;
                                      flat out uint vId;

                                      void main() {
                                          vId = uint(uPickId);
                                          vColor = uColor;

                                          // 任一端是 NaN 就把整个实例退化到裁剪空间之外（理由同 LINE_VERTEX）
                                          if (isnan(aY0) || isnan(aY1)) {
                                              gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
                                              return;
                                          }

                                          float uMin = uValueRange.x;
                                          float span = uValueRange.y - uMin;
                                          float sy0 = uPlotRect.y + (1.0 - (aY0 - uMin) / span) * uPlotRect.w;
                                          float sy1 = uPlotRect.y + (1.0 - (aY1 - uMin) / span) * uPlotRect.w;

                                          float rel = uFirstRelIndex + float(gl_InstanceID);
                                          float sx0 = uPlotRect.x + rel * uPxPerSample;
                                          float sx1 = sx0 + uPxPerSample;

                                          // 三个点：水平段的左端 → 拐角 → 竖直段的另一端
                                          vec2 p0 = vec2(sx0, sy0);
                                          vec2 p1 = vec2(sx1, sy0);
                                          vec2 p2 = vec2(sx1, sy1);

                                          // 第一段的法向。它恒为水平段（"先横后竖"），所以是 (0,1)。
                                          vec2 n1 = vec2(0.0, 1.0);
                                          // 第二段的方向可能为零长（本段是平的），此时取第一段的方向：
                                          // 法向随之等于 n1，斜接公式退化成"不放大"（见下）。
                                          vec2 d2 = p2 - p1;
                                          float len2 = length(d2);
                                          vec2 u2 = len2 > 0.0 ? d2 / len2 : vec2(1.0, 0.0);
                                          vec2 n2 = vec2(-u2.y, u2.x);

                                          float pointIndex = aCorner.x;
                                          vec2 p;
                                          vec2 n;
                                          if (pointIndex < 0.5) {
                                              p = p0;
                                              n = n1;
                                          } else if (pointIndex < 1.5) {
                                              // 拐角：角平分线方向，除以 cos(夹角/2) 放大。
                                              // 两法向相等时（第二段退化了）normalize(n1+n1) == n1 且
                                              // dot == 1，于是不放大——不会变成两倍线宽。
                                              vec2 sn = normalize(n1 + n2);
                                              p = p1;
                                              n = sn / dot(sn, n1);
                                          } else {
                                              p = p2;
                                              n = n2;
                                          }

                                          float halfWidth = max(uHalfWidth, uPickTolerance);
                                          vec2 pos = p + n * halfWidth * (aCorner.y < 0.5 ? -1.0 : 1.0);

                                          // 屏幕像素 -> NDC。y 要翻：屏幕原点在左上、y 向下。
                                          vec2 ndc = vec2(pos.x / uViewport.x * 2.0 - 1.0,
                                                          1.0 - pos.y / uViewport.y * 2.0);
                                          gl_Position = vec4(ndc, 0.0, 1.0);
                                      }
                                      """;

    /**
     * 面积图的顶点着色器。
     *
     * <p>每个实例是<b>一个梯形</b>：线段两端的数值各向基线垂下来，四条边是
     * {@code (x_i, y_i)}、{@code (x_{i+1}, y_{i+1})}、{@code (x_{i+1}, base)}、
     * {@code (x_i, base)}。相邻实例共用一条边，于是整条曲线下方填成一片。
     *
     * <h2>四个角一次画完，不做"每个样本一根竖线"那种做法</h2>
     * <p>逐样本发一根从曲线到基线的竖直四边形同样能填满，但那样每个实例的几何
     * 与"线段的两端"这件事无关，{@link WindowRange#compute} 那套"最后一个实例要右端
     * 已采到"的算术也就用不上了。用梯形的话实例仍然是<b>线段</b>，
     * 与折线走同一份实例区间算术——数组下标、环绕切分、NaN 断开全部照旧。
     *
     * <h2>NaN 让整个实例消失，而不是只丢一半</h2>
     * <p>与 {@link #LINE_VERTEX} 逐字相同：任一端是 NaN 就把四个角一起退化掉。
     * 只退化"有 NaN 的那半"会让缺口两侧各留下一个尖角，而尖角看起来像数据本身的形状。
     *
     * <h2>{@code aCorner.y} 选的是"曲线上还是基线上"，不是"上边还是下边"</h2>
     * <p>写成"上下"就错了：基线在数值上低于曲线时是下边，高于曲线时（基线取 0.5、
     * 曲线在 0.3）就变成上边——那种写法的面积图在"基线高于数据"时会画成一条反面填充，
     * 而画面看起来完全正常。
     */
    static final String AREA_VERTEX = """
                                     #version 330 core

                                     // —— 每顶点（divisor = 0）：四个角 ——
                                     // aCorner.x = 线段的哪一端（0=左 1=右），aCorner.y = 0 取数据值 / 1 取基线
                                     layout(location = 0) in vec2  aCorner;

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
                                     // 下沿（数值）：填充的下边界所在的值
                                     uniform float uBaseline;
                                     // 颜色（直通，非预乘）；a 分量就是填充的不透明度
                                     uniform vec4  uColor;
                                     // 拾取 ID（必须是 int，理由见 LINE_VERTEX）
                                     uniform int   uPickId;

                                     out vec4 vColor;
                                     flat out uint vId;

                                     void main() {
                                         vId = uint(uPickId);
                                         vColor = uColor;

                                         if (isnan(aY0) || isnan(aY1)) {
                                             gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
                                             return;
                                         }

                                         float uMin = uValueRange.x;
                                         float span = uValueRange.y - uMin;

                                         // 本角取数据值还是基线：由 aCorner.y 决定；
                                         // 取数据值时再按 aCorner.x 选是哪一端的值。
                                         float v = (aCorner.y < 0.5)
                                                 ? (aCorner.x < 0.5 ? aY0 : aY1)
                                                 : uBaseline;
                                         float sy = uPlotRect.y + (1.0 - (v - uMin) / span) * uPlotRect.w;

                                         float rel = uFirstRelIndex + float(gl_InstanceID)
                                                 + (aCorner.x < 0.5 ? 0.0 : 1.0);
                                         float sx = uPlotRect.x + rel * uPxPerSample;

                                         // 屏幕像素 -> NDC。y 要翻：屏幕原点在左上、y 向下。
                                         vec2 ndc = vec2(sx / uViewport.x * 2.0 - 1.0,
                                                         1.0 - sy / uViewport.y * 2.0);
                                         gl_Position = vec4(ndc, 0.0, 1.0);
                                     }
                                     """;

    /**
     * 柱状图的顶点着色器。
     *
     * <p>每个实例是<b>一根柱</b>：在数值与基线之间、以"样本所在的类别格"为宽度基准的矩形。
     *
     * <h2>柱宽与位置全由 CPU 算好，着色器只认几何</h2>
     * <p>类别间距（{@code categoryGap}）、同类别柱间距（{@code barGap}）、
     * 以及"本系列是本类别里的第几根"（并排分组，见 {@code ChartRenderer}）都会影响
     * 柱的位置与宽度，三者的算术是纯 CPU 的事，结论就两个数：{@code uBarOffset}
     * （柱心相对样本中心的偏移）与 {@code uBarHalfWidth}（半宽）。
     * 把它们放进着色器等于把"分组"这件事摊在两份代码里，而两份迟早会分叉——
     * 分叉的表现是<b>柱子的位置和刻度对不上</b>，看起来只是没对齐。
     *
     * <h2>只有一个实例属性</h2>
     * <p>与 {@link #SCATTER_VERTEX} 同类：一根柱自己就是完整的，没有"第二端"，
     * 所以实例数是<b>点数</b>（{@link WindowRange#computePoints}）而不是线段数。
     * 多配一个 {@code aY1} 属性不会报错也不会画错，只是那个 location 在着色器里
     * 没人声明、被静默忽略——所以这里刻意只配一个。
     */
    static final String BAR_VERTEX = """
                                    #version 330 core

                                    // —— 每顶点（divisor = 0）：四个角 ——
                                    // aCorner.x = 0 左边缘 / 1 右边缘，aCorner.y = 0 取数值 / 1 取基线
                                    layout(location = 0) in vec2  aCorner;

                                    // —— 每实例（divisor = 1）：该样本的数值 ——
                                    layout(location = 1) in float aY;

                                    // 绘图区（设备像素，原点左上）
                                    uniform vec4  uPlotRect;      // x, y, w, h
                                    uniform vec2  uViewport;      // 帧缓冲宽高
                                    // 数值窗口
                                    uniform vec2  uValueRange;    // min, max
                                    // 横轴：本次 draw 的第一个实例相对窗口左端的偏移（见 WindowRange）
                                    uniform float uFirstRelIndex;
                                    uniform float uPxPerSample;
                                    // 柱心相对样本中心的偏移（设备像素，可为负）
                                    uniform float uBarOffset;
                                    // 柱的半宽（设备像素）
                                    uniform float uBarHalfWidth;
                                    // 下沿（数值）
                                    uniform float uBaseline;
                                    // 拾取容差：绘制时为 0，ID pass 时把柱撑宽（细柱也点得中）
                                    uniform float uPickTolerance;
                                    // 颜色（直通，非预乘）
                                    uniform vec4  uColor;
                                    // 拾取 ID（必须是 int，理由见 LINE_VERTEX）
                                    uniform int   uPickId;

                                    out vec4 vColor;
                                    flat out uint vId;

                                    void main() {
                                        vId = uint(uPickId);
                                        vColor = uColor;

                                        // 该样本没有值（NaN = 缺口）时整根柱不画。
                                        //
                                        // 不能靠"NaN 自然传播"：NaN 位置的光栅化行为是未定义的。
                                        // 也不能退化成"从基线到 0 的一根柱"——那会凭空画出一根
                                        // 不存在的柱子，比不画更糟。
                                        if (isnan(aY)) {
                                            gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
                                            return;
                                        }

                                        float uMin = uValueRange.x;
                                        float span = uValueRange.y - uMin;
                                        float v = (aCorner.y < 0.5) ? aY : uBaseline;
                                        float sy = uPlotRect.y + (1.0 - (v - uMin) / span) * uPlotRect.w;

                                        // 样本中心 -> 柱心 -> 左/右边缘
                                        float rel = uFirstRelIndex + float(gl_InstanceID);
                                        float cx = uPlotRect.x + rel * uPxPerSample + uBarOffset;
                                        float halfWidth = max(uBarHalfWidth, uPickTolerance);
                                        float sx = cx + (aCorner.x < 0.5 ? -halfWidth : halfWidth);

                                        // 屏幕像素 -> NDC。y 要翻：屏幕原点在左上、y 向下。
                                        vec2 ndc = vec2(sx / uViewport.x * 2.0 - 1.0,
                                                        1.0 - sy / uViewport.y * 2.0);
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
