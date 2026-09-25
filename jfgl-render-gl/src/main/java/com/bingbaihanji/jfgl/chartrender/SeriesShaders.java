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
 * <h2>★ 隐式契约：{@code vEdge} 的第二个分量 = 0 表示"这一轴不是边界"</h2>
 * <p>五个顶点着色器都输出 {@code out vec2 vEdge}，而它们对两个分量的填法是**有约定**的：
 * 单轴图型（折线 / 阶梯 / 面积）把 {@code .y} 写成 <b>0</b>，盒式图型（散点 / 柱）两个分量
 * 都填真实的"到边界的归一化距离"。共享的 {@link #LINE_FRAGMENT} 依赖这条约定把
 * "一个轴的覆盖率"与"两个轴的覆盖率之积"写成**同一段代码**：
 * 常量分量的 {@code fwidth} 恒为 0 ⇒ 那一轴的覆盖率退化成 1 ⇒ 乘积等于单轴。
 *
 * <p><b>这条契约的两个前提，别当成理所当然：</b>
 * <ul>
 *   <li>常量分量插值之后仍是常量，且它的 {@code fwidth} 恒为 <b>0</b>（而不是某个极小的数）；
 *       GLSL 规范里 {@code fwidth} 是"邻域差分"，对常量表达式应当为 0，但**实测只在
 *       NVIDIA 4.6（本机）上确认过**——换驱动要重跑 {@code ChartVerifier} 的 AA 一节
 *       （折线/阶梯/面积那三条 ② 会立刻把"那一轴没退化成 1"报出来）；</li>
 *   <li>因此**新增顶点着色器时，单轴图型的 {@code .y} 必须写 0**；写成别的常量
 *       （哪怕是一个恒定的 1）语义就变了——那一轴会按"到边界的距离恒为 1"算出 0.5 的覆盖率，
 *       整条带子凭空淡一半，而画面看起来只是"颜色浅了点"。</li>
 * </ul>
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
                                      // "我在描边带的哪一侧"——与 Gc 路径的 aEdge.x 同一个意思。
                                      // 规范化到 [-1,1]：±1 就是带的两条外缘。
                                      //
                                      // ★ 它是 vec2 而不是 float：**第二个分量恒为 0**，表示"这一轴
                                      // 不是边界"。片元对两个分量**各自**算一个覆盖率再相乘，
                                      // 而常量分量的 fwidth 恒为 0、覆盖率退化成 1
                                      // ⇒ 乘积就是单轴的覆盖率（见 LINE_FRAGMENT 的说明）。
                                      out vec2 vEdge;
                                      flat out uint vId;

                                      void main() {
                                          vId = uint(uPickId);   // 发号从 1 开始、恒为正，转换无损
                                          vColor = uColor;
                                          // 取 aCorner.y 而不是 aCorner.x：后者挑的是线段的哪一端
                                          // （下面那句 `base = aCorner.x < 0.5 ? p0 : p1`），
                                          // 与"我在带的哪一侧"（下面那句 `offset` 的符号）是两件事。
                                          vEdge = vec2(aCorner.y * 2.0 - 1.0, 0.0);

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
                                      // "我在标记带的哪一侧"。
                                      //
                                      // 与折线那条不同：这里的 aCorner 两个分量都只是
                                      // "居中四边形的 x / y"，几何是个**方块**（见下面那句
                                      // `(aCorner - vec2(0.5)) * half * 2.0`），四条边都是边界，
                                      // 所以**两个分量都要留给片元**，由片元对每轴各算一个覆盖率再相乘
                                      // （盒式解析 AA 的乘积形式；两个轴的半宽不同时它也是对的，
                                      // 而 max(|x|,|y|) 那种 SDF 只用得上一个斜坡宽度）。
                                      //
                                      // ★ **不能在这里先取 max 再传一个标量**——四个角的
                                      // `max(|2·0−1|, |2·0−1|)` **全都等于 1**，于是 vary 是一个
                                      // **常量 1**：fwidth 恒为 0 ⇒ 覆盖率恒为 1 ⇒ **一点 AA 都没有**，
                                      // 而画面看起来"只是没那么细腻"。（实测：这样写之后
                                      // 散点那两条判据的关/开两帧**逐像素相同**。）
                                      out vec2 vEdge;
                                      flat out uint vId;

                                      void main() {
                                          vId = uint(uPickId);   // 发号从 1 开始、恒为正，转换无损
                                          vColor = uColor;
                                          vEdge = aCorner * 2.0 - 1.0;

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
                                      // "我在描边带的哪一侧"——取法与 LINE_VERTEX 逐字相同。
                                      //
                                      // 本条也用 aCorner.y：aCorner.x 在这里是点序号 0/1/2
                                      // （一个实例六个角），拿它算边界会把"拐角"那两列标成带的边缘。
                                      // 阶梯的拐角是同一个带的延续（法向取角平分线），
                                      // 它的外缘本来就该按到带中心的距离羽化。
                                      //
                                      // 第二个分量恒为 0（单轴图型，见 LINE_VERTEX）。
                                      out vec2 vEdge;
                                      flat out uint vId;

                                      void main() {
                                          vId = uint(uPickId);
                                          vColor = uColor;
                                          vEdge = vec2(aCorner.y * 2.0 - 1.0, 0.0);

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
                                     // "我在填充带的哪一侧"。
                                     //
                                     // ★ 这里只取 aCorner.y，不要取两个轴的 max。
                                     // aCorner.x 是"线段的哪一端"（0 左 / 1 右），而面积的左右两端
                                     // 落在绘图区/裁剪边界上——那两条边是裁剪切出来的，
                                     // 不是图形自己的边缘，不该被羽化。把它们也标成边界，
                                     // 绘图区左右各会多出一条半透明的竖带，而"边缘淡了一点"
                                     // 是最难被注意到的那一类画面差异（`Gc` 的 aEdge 那套里
                                     // "被裁掉的那两条边不参与羽化"是同一条约定）。
                                     //
                                     // 而 aCorner.y 恰好就是"取数据值（顶）还是取基线（底）"，
                                     // 顶边与底边才是这条填充真正的两条边界。
                                     //
                                     // 第二个分量恒为 0（单轴图型，见 LINE_VERTEX）。
                                     out vec2 vEdge;
                                     flat out uint vId;

                                     void main() {
                                         vId = uint(uPickId);
                                         vColor = uColor;
                                         // aCorner.y = 0（数据值）→ +1；= 1（基线）→ −1。
                                         // 两个都是 ±1，取绝对值之后谁正谁负无关紧要。
                                         vEdge = vec2(1.0 - aCorner.y * 2.0, 0.0);

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
                                    // "我在柱的哪一侧"。柱是一个矩形：四条边全是边界，
                                    // 所以与 SCATTER_VERTEX 一样取两个轴里离得远的那个（方形 SDF）。
                                    //
                                    // ★ 与 AREA_VERTEX 的区别在这里：面积的左右两端是裁剪边界
                                    // （所以那边只取 y），而柱的左右两条是柱子自己的边缘
                                    // （由 uBarHalfWidth 撑出来），不羽化的话细柱左右就是硬边——
                                    // 而柱宽本来就小，"两边有点毛"这一点在画面上完全看不出。
                                    //
                                    // ★ **两个分量都要留给片元**（与 SCATTER_VERTEX 同一条理由）：
                                    // 柱的宽高比可能是几十倍（半宽 17 px、半高 2.75 px），两个轴各需要
                                    // 自己那个 1 像素宽的斜坡；在顶点上先取 max 会把 vary 压成常量 1
                                    // ⇒ fwidth = 0 ⇒ 一点 AA 都没有（实测：关/开两帧逐像素相同）。
                                    out vec2 vEdge;
                                    flat out uint vId;

                                    void main() {
                                        vId = uint(uPickId);
                                        vColor = uColor;
                                        vEdge = aCorner * 2.0 - 1.0;

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
     *
     * <h2>解析式抗锯齿：覆盖率来自 {@code vEdge}，不是多次采样</h2>
     * <p>与 {@code Gc} 的描边路径同一条公式：片元按"到边界的归一化距离"算一个覆盖率，
     * 与颜色一起预乘出去。{@code fwidth} 给出的是 vary 在每个像素上的变化量，
     * 因此斜坡宽度只由"每像素的变化量"决定，<b>与几何尺寸、缩放都无关</b>。
     *
     * <p>⚠️ <b>但"斜坡宽 1 个设备像素"是 {@code fwidth} 的标准近似，不是对任意朝向都精确
     * </b>（这条口径是从 {@code RenderBatch} 继承来的，图表里有斜线段所以同样适用）：
     * {@code fwidth} 的定义是 {@code |dFdx| + |dFdy|}，<b>不是梯度长度</b>——
     * <ul>
     *   <li><b>轴向</b>边界（水平 / 竖直：折线带、柱的上下沿、面积填充的上下沿）上
     *       只有一个偏导非零 ⇒ {@code fwidth} 恰好等于梯度长度 ⇒ <b>1 px 精确</b>；</li>
     *   <li><b>45° 斜边</b>上两个偏导各是梯度长的 {@code 1/√2}，加起来大了 {@code √2} 倍
     *       ⇒ 斜坡在垂直于该边的方向上宽 <b>√2 px</b>（更软，不是更硬），
     *       按半宽说就是 {@code 1/√2} px。</li>
     * </ul>
     * <b>别把这句话读成"对任意几何都已保证 1 px"</b>——本节的四条判据几何全是水平的，
     * 所以那些读数里的 1 px 是精确的；换成斜边要按上面第二条重新量。
     *
     * <h2>为什么 {@code vEdge} 是 vec2、覆盖率是**乘积**</h2>
     * <p>五种几何所需的"边界"不一样：折线/阶梯/面积只有**一条轴**上有边界
     * （带的法向、或填充的上下沿），散点与柱状是个**盒子**、四条边都要。
     * 而一个**标量** vary 装不下盒子：四个角上的"到边界距离"全都等于 1，
     * 在顶点上算完再插值出来就是一个**常量**（fwidth = 0 ⇒ 覆盖率恒为 1 ⇒ 没有 AA）。
     * 所以两个分量都传下来，片元**逐轴各算一个覆盖率，再相乘**：
     * <ul>
     *   <li>单轴图型的第二个分量恒为 0 ⇒ 它的 {@code fwidth} 恒为 0 ⇒ 那个轴的覆盖率
     *       退化成 1 ⇒ 乘积就是单轴的覆盖率，**与只算一个轴逐位相同**；</li>
     *   <li>盒式图型两个轴各有自己的斜坡宽度（柱的半宽 17 px、半高 2.75 px 差几十倍），
     *       乘积形式对它们各自成立，且在角像素上给出"两轴覆盖率之积" ——
     *       那**恰好是角像素被盒覆盖的真实面积比例**（0.25 × 0.25 那种），
     *       而单通道的 {@code max} 形式在角上给不出这个值（它按"离最近一条边的距离"算）。</li>
     * </ul>
     *
     * <p>⚠️ <b>不要为了省一个分量而在顶点上先取 {@code max}。</b>坏的不是 {@code max} 这个
     * 形状，而是"**先在顶点上做非线性运算、再让硬件线性插值**"——那样得到的不是那个函数，
     * 而是一个**常量**（四角的值全相等）⇒ fwidth = 0 ⇒ 完全没有抗锯齿。实测关/开两帧
     * 逐像素相同。这一条由 {@code ChartVerifier} 的柱状与散点两组判据钉着。
     * （在<b>片元里</b>对插值出来的坐标取 {@code max} 是能工作的，那是距离场的常规写法。）
     *
     * <h2>★ 已声明的降级：外侧那半个斜坡没有片元，本相位下每列少 0.25 px 墨量</h2>
     * <p>覆盖率斜坡以**几何的真实边界**为心、总宽 1 个设备像素（轴向边界，见上）。
     * 而图表这条路径 <b>几何不外扩</b>（顶点着色器只画到真实边缘为止）——
     * <b>这与 {@code Gc} 的描边路径不同</b>：那边在 CPU 侧把几何双双向外扩了 1 个设备像素
     * （见 {@code Gc.strokeOutline}），正是为了把整个斜坡装进几何里。
     * 于是斜坡**在外侧的那一半没有片元**，覆盖率推不到 0。
     *
     * <p><b>每列少多少由相位决定，没有一条通用公式</b>——离散口径是：
     * 外侧半斜坡里<b>有像素中心</b>就丢那个样本本该贡献的覆盖率，没有就不丢。
     * <b>本相位</b>（带心 `k+0.25`、半宽 1.5，即 {@code ChartVerifier} 的相位 A）实测：
     * <ul>
     *   <li>上边 721.75：外侧半斜坡 {@code (721.25, 721.75)} 里**有一个**像素中心
     *       （721.5，落在边外 0.25）⇒ 丢 {@code 0.5 − 0.25 = 0.25}；</li>
     *   <li>下边 724.75：外侧半斜坡 {@code (724.75, 725.25)} 里**一个像素中心都没有**
     *       （最近的那个在 725.5，已经在斜坡之外）⇒ 丢 <b>0</b>。</li>
     * </ul>
     * ⇒ 本相位每列少 **0.25 px**（带的全宽 3 ⇒ 解析值 2.75）。
     *
     * <p>⚠️ <b>不要把这个数写成"两侧各 ∫₀^0.5(0.5−t)dt、对称"那种连续面积口径</b>
     * （那样算出来是 0.125 × 2 = 0.25）：连续口径给每边 0.125，而离散口径是
     * **一边 0.25、一边 0**——两者**在本相位下碰巧同值**。换个相位立刻不成立——
     * 例如带心落在 {@code k+0.5} 时同一套算术给出"丢 0"（三个被光栅化的行全满覆盖，
     * Σ = 3 = 全宽）。<b>换相位 / 换线宽都要按上面的办法重新数一遍</b>（判据那边有
     * 前置断言钉着相位，见 {@code ChartVerifier} 的"★ AA 前置"）。
     *
     * <p><b>它是声明过的降级，不是缺陷</b>——与 {@code Gc} 那条
     * "非等比缩放下压缩轴真外缘之外的羽化被切掉"并列同类：都是"几何止于真实边缘"
     * 这一条实现选择带来的、<b>方向单向</b>（只会让边缘略淡）的后果。
     * 要修好得让几何也外扩 1 像素（那时外扩量同样吃 {@code matrixScale} 的降级），
     * 属于新特性，不在本期。
     *
     * <p>它被 {@code ChartVerifier} 的 ④ 按**解析值 2.75 px/列**钉着（不是拿"AA 关的读数"
     * 当参照——硬边在这个几何上恰好是 3.0 px/列，比 AA 开**多** 8.3%）。
     *
     * <p><strong>{@code uAntialias} 用 {@code if} 而不是乘进公式里</strong>：
     * 关掉时必须是**逐位**的旧行为（{@code vec4(rgb * a, a)}）。乘进去在
     * {@code uAntialias = 0} 时结果相同，但 {@code fwidth} 仍然参与运算——
     * 它在某些驱动上对常量 vary 会给出 0 以外的值，那是"关着也可能改变画面"的一条路。
     * <b>关就是关，不要留任何一条能碰到像素的路径。</b>
     *
     * <p><strong>预乘必须两个通道一起乘</strong>（与 {@code Gc} 路径那条"乘标量不破坏
     * 预乘性"同一件事）：{@code vec4(rgb * a, a)} 才是预乘色，写成
     * {@code vec4(rgb, a * a)} 会丢掉预乘性，半透明系列整片偏色。
     *
     * <h2>这里可以用 uniform，而 {@code RenderBatch} 那边不行</h2>
     * <p>{@code RenderBatch} 的顶点侧把"要不要真实边距"烘焙进顶点缓冲，所以它
     * <b>不能</b>用 {@code uAntialias}（同一个批里可能有开有关）。图表系列不合并批次
     * ——<b>一个系列一条 draw call</b>——所以一个 uniform 是安全的，
     * 而那正是"开"与"关"两种画面的分界只有一处的地方。
     */
    static final String LINE_FRAGMENT = """
                                        #version 330 core
                                        in vec4 vColor;
                                        // 两个轴各自的"到边界的归一化距离"：1 = 正在边界上，0 = 在正中。
                                        in vec2 vEdge;
                                        uniform float uAntialias;    // 1.0 = 开，0.0 = 关
                                        out vec4 fragColor;
                                        void main() {
                                            float a = 1.0;
                                            if (uAntialias > 0.5) {
                                                vec2 e = abs(vEdge);
                                                vec2 w = fwidth(vEdge);
                                                // 逐轴一个覆盖率。w == 0 的轴是常量分量（单轴图型
                                                // 的第二分量），它退化成"满覆盖"——乘积于是等于
                                                // 另一个轴的覆盖率，与只算一个轴逐位相同。
                                                vec2 cov = vec2(
                                                    (w.x > 0.0) ? clamp(0.5 - (e.x - 1.0) / w.x, 0.0, 1.0) : 1.0,
                                                    (w.y > 0.0) ? clamp(0.5 - (e.y - 1.0) / w.y, 0.0, 1.0) : 1.0);
                                                a = cov.x * cov.y;
                                            }
                                            fragColor = vec4(vColor.rgb * vColor.a * a, vColor.a * a);
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
