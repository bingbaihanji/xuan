# SDF 文本子系统实施计划（子项目 B）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 Xuan 加上支持中文、任意缩放清晰的 GL 原生文本绘制能力。

**Architecture:** 字形由 `stb_truetype` 光栅化成覆盖度位图，经纯数组运算生成有符号距离场（SDF），存入一张固定的 R8 图集纹理；`drawText` 把每个字形发成一个带纹理的四边形，走**现有**的批处理管线（因此裁剪、z 序、合批、GPU 拾取全部自动成立）。

**Tech Stack:** Java 21 + Kotlin 17、LWJGL 3.3.6（`lwjgl-opengl` + `lwjgl-stb`）、OpenGL 3.3 core。

**设计依据**：`docs/superpowers/specs/2026-09-20-xuan-sdf-text-design.md`

---

## 对规格的一处修正（写实现时发现，已在此处生效）

规格 §3.1 写的是「动态图集 + **LRU 逐槽淘汰**」。本计划改为**分代重置**，理由：

1. **逐槽淘汰正是规格 §5.3 那个 bug 的温床**——淘汰掉一个本帧已经发射过 uv 的槽，会让同一帧内的字形互相覆盖。要靠"只淘汰本帧没用过的槽"来防，那是给一个本可不存在的问题写防守。
2. 货架分配 + 逐槽回收**必然碎片化**，最终会"还有空间却分配不出"。
3. 对驱动场景（图表，几百个字形）两者都不会触发，区别只在真触发时哪个是对的。

**做法**：图集固定 4096×4096（R8，16 MB 显存），容量 4096 个 64² 槽位，**只增不减**；在**帧边界**发现货架区耗尽时整体重置。
于是"失效"只可能发生在帧边界、且是整体失效，**§5.3 那类 bug 在结构上不可能发生**。`ATLAS_MAX_SIZE` 这个常量因此不再需要。

---

## 文件结构

**新建（main）**
| 文件 | 职责 |
|------|------|
| `text/SdfGenerator.java` | 覆盖度位图 → 距离场。**纯数组，零依赖** |
| `text/GlyphBitmap.java` | record：位图尺寸 + 像素 + 相对基线的偏移 |
| `text/GlyphSource.java` | 函数式接口：key → `GlyphBitmap`。让图集不认识字体 |
| `text/GlyphAtlas.java` | R8 纹理 + 货架分配 + 分代重置。只依赖 `GLAbstraction` **接口** |
| `text/GlyphSlot.java` | record：uv 矩形 + 偏移 + 尺寸 + advance |
| `text/FontFile.java` | stb 封装：字形索引与度量 |
| `text/GlyphRasterizer.java` | 字形 → 覆盖度位图 |
| `text/FontGlyphSource.java` | 把 `FontFile` + `GlyphRasterizer` + `SdfGenerator` 串成 `GlyphSource` |
| `text/TextLayout.java` | slot 序列 → 四边形顶点。**纯算术，不碰 GL 与 stb** |

**新建（test / example）**
`SdfGeneratorTest`、`GlyphAtlasTest`、`FontFileTest`、`TextLayoutTest`、`example/TextVerifier.kt`

**修改**
`gl/GLAbstraction.java`、`gl/LwjglGLAbstraction.java`、`test/.../FakeGLAbstraction.java`、
`renderer/DrawCommand.java`、`renderer/VertexWriter.java`、`renderer/RenderBatch.java`、
`renderer/Gc.kt`、`CLAUDE.md`、`README.md`

**资源**
`src/main/resources/fonts/simhei.ttf` + `fonts/README.md`

---

## Task 1: SdfGenerator —— 覆盖度位图转距离场

**这是整个子系统里最该被单测钉死的一块**：它是纯数组运算、零依赖，而它错了的表现是"字形边缘不对"——不会报错。

**Files:**
- Create: `src/main/java/com/bingbaihanji/xuan/text/SdfGenerator.java`
- Test: `src/test/java/com/bingbaihanji/xuan/text/SdfGeneratorTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/xuan/text/SdfGeneratorTest.java`：

```java
package com.bingbaihanji.xuan.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SdfGeneratorTest {

    /** 造一张 width×height 的覆盖度位图，inside 为 true 的像素取 255，其余 0。 */
    private static byte[] coverage(int width, int height, boolean[][] inside) {
        byte[] c = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (inside[y][x]) {
                    c[y * width + x] = (byte) 255;
                }
            }
        }
        return c;
    }

    /**
     * 把「到边界的欧氏距离（外部为正）」换算成期望的编码值。
     * 与实现里的编码式子是同一个，但测试里独立写一遍——这样实现改了式子会被发现。
     */
    private static int expectedOutside(double distance) {
        float t = 0.5f - (float) distance / (2f * SdfGenerator.SPREAD);
        return Math.round(Math.max(0f, Math.min(1f, t)) * 255f);
    }

    private static int expectedInside(double distance) {
        float t = 0.5f + (float) distance / (2f * SdfGenerator.SPREAD);
        return Math.round(Math.max(0f, Math.min(1f, t)) * 255f);
    }

    private static int at(byte[] sdf, int stride, int x, int y) {
        return sdf[y * stride + x] & 0xFF;
    }

    @Test
    void 输出尺寸比输入每边多一个spread() {
        boolean[][] inside = new boolean[4][4];
        inside[1][1] = true;
        byte[] sdf = SdfGenerator.generate(coverage(4, 4, inside), 4, 4);
        int stride = 4 + 2 * SdfGenerator.SPREAD;
        assertEquals(stride * stride, sdf.length);
    }

    @Test
    void 边界相邻的像素编码接近一半() {
        boolean[][] inside = new boolean[4][4];
        inside[1][1] = true;
        byte[] sdf = SdfGenerator.generate(coverage(4, 4, inside), 4, 4);
        int stride = 4 + 2 * SdfGenerator.SPREAD;

        // (1,1) 是内部像素，它自己到内部的距离是 0、到外部的距离是 1
        assertEquals(expectedInside(1.0), at(sdf, stride, 1 + SdfGenerator.SPREAD, 1 + SdfGenerator.SPREAD));
        // 紧邻它左侧的像素在外部，到边界的距离是 1
        assertEquals(expectedOutside(1.0), at(sdf, stride, 0 + SdfGenerator.SPREAD, 1 + SdfGenerator.SPREAD));
    }

    @Test
    void 对角方向用的是欧氏距离而不是切比雪夫() {
        boolean[][] inside = new boolean[4][4];
        inside[1][1] = true;
        byte[] sdf = SdfGenerator.generate(coverage(4, 4, inside), 4, 4);
        int stride = 4 + 2 * SdfGenerator.SPREAD;

        // 斜对角 (2,2) 在外部，到最近内部像素 (1,1) 的欧氏距离是 sqrt(2)≈1.4142
        int diagonal = at(sdf, stride, 2 + SdfGenerator.SPREAD, 2 + SdfGenerator.SPREAD);
        assertEquals(expectedOutside(Math.sqrt(2)), diagonal);

        // 切比雪夫距离会把 1.4142 当成 1，得到下面这个值。两者必须不同，
        // 否则这条断言就区分不了「真欧氏距离」与「八邻域距离变换」。
        assertNotEquals(expectedOutside(1.0), diagonal,
                "斜对角的值与正交方向相同，说明用的是切比雪夫距离而不是欧氏距离");
    }

    @Test
    void 内部深处饱和到255() {
        boolean[][] inside = new boolean[20][20];
        for (boolean[] row : inside) {
            java.util.Arrays.fill(row, true);
        }
        byte[] sdf = SdfGenerator.generate(coverage(20, 20, inside), 20, 20);
        int stride = 20 + 2 * SdfGenerator.SPREAD;
        // 中心点离边界远超过 SPREAD
        assertEquals(255, at(sdf, stride, 10 + SdfGenerator.SPREAD, 10 + SdfGenerator.SPREAD));
    }

    @Test
    void 外部远处饱和到0() {
        boolean[][] inside = new boolean[20][20];
        inside[10][10] = true;
        byte[] sdf = SdfGenerator.generate(coverage(20, 20, inside), 20, 20);
        int stride = 20 + 2 * SdfGenerator.SPREAD;
        assertEquals(0, at(sdf, stride, 0, 0), "外扩出来的边角应当饱和到 0");
    }

    @Test
    void 全空输入得到全零() {
        byte[] sdf = SdfGenerator.generate(new byte[4 * 4], 4, 4);
        for (byte b : sdf) {
            assertEquals(0, b & 0xFF);
        }
    }

    @Test
    void 全满输入得到全255() {
        // 边长必须是 4*SPREAD，不能是 2*SPREAD（更正见 Step 5 后）：
        // 2*SPREAD 恰好是临界情形——中心离边界正好 SPREAD，t = 0.5 + SPREAD/(2*SPREAD) = 1.0，
        // 落在钳位上。那样即使编码尺度略有偏差也照样通过，等于没验证"饱和"。
        // 4*SPREAD 让中心离边界整整 2*SPREAD，t = 1.5 远超饱和，断言才真的咬住。
        int n = 4 * SdfGenerator.SPREAD;
        byte[] cover = new byte[n * n];
        java.util.Arrays.fill(cover, (byte) 255);
        byte[] sdf = SdfGenerator.generate(cover, n, n);
        int stride = n + 2 * SdfGenerator.SPREAD;
        int c = n / 2 + SdfGenerator.SPREAD;
        assertEquals(255, at(sdf, stride, c, c), "实心块的正中心应当饱和到 255");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=SdfGeneratorTest`
Expected: 编译失败 — `找不到符号: 类 SdfGenerator`

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/bingbaihanji/xuan/text/SdfGenerator.java`：

```java
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o test -Dtest=SdfGeneratorTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: 变异验证**（每条做完立刻还原，共 4 条）

| 变异 | 应失败的测试 |
|------|--------------|
| **把 `edt1d` 整体换成八邻域两趟扫描的棋盘距离变换**（不是把某个乘法改成 `Math.abs`，见下方更正） | `对角方向用的是欧氏距离而不是切比雪夫` |
| 编码式子里的符号反过来（`distOut - distIn` 改成 `distIn - distOut`） | `内部深处饱和到255`、`边界相邻的像素编码接近一半` |
| 去掉外扩（`SPREAD` 当成 0 用，即循环里不偏移） | `输出尺寸比输入每边多一个spread` |
| 阈值判定反过来（`>=` 改成 `<`） | `全空输入得到全零`、`内部深处饱和到255` |

**若某条实测存活，先怀疑检查、再怀疑代码，然后如实上报**——本项目已经四次撞上「变异表本身写错」。

> **更正（2026-09-20，实施时实测）**：第 1 行原本写的是「把 `(q - v[k]) * (q - v[k])` 改成
> `Math.abs(q - v[k])`」，并称那是切比雪夫近似。**那是错的，实测存活。**
>
> `Math.abs` 得到的是 **L1 / 曼哈顿**距离，不是切比雪夫。而对「孤立的单个内部像素」
> 这个特定的测试形状，曼哈顿距离 2 经调用方的 `Math.sqrt` 之后**恰好等于 √2**，
> 与真欧氏距离在数值上完全一致——**这个形状区分不了两者**。实测该变异杀掉的是
> 另外三条（`全满输入得到全255` 191、`内部深处饱和到255` 178、`外部远处饱和到0` 32）。
>
> 执行者另外用一个**真正的八邻域棋盘距离变换**验证过：那条对角测试确实按预期失败
> （`expected: <105> but was: <112>`），所以**这个断言不是橡皮图章**，错的是变异表的标注。
>
> 教训：写变异时要说清「改成什么样**算法**」，而不是「改哪一行代码」——
> 一行之差可能落在完全不同的距离度量上，而两者在这个测试形状下可能数值相等。

> **更正二（同日）**：`全满输入得到全255` 原来的输入是 4×4，**那条断言不可能成立**——
> 4×4 的实心块，中心离边界只有 2 像素，远达不到 `SPREAD = 8` 的饱和距离，
> 实现给出的是 159 而不是 255。**159 是对的，是测试的输入形状选错了。**
> 已改成边长 `4 * SPREAD`。
>
> 注意**不能改成 `2 * SPREAD`**：那恰好是临界情形（中心离边界正好 `SPREAD`，
> `t = 1.0` 落在钳位上），即使编码尺度有偏差也照样通过，等于没验证饱和。
> `4 * SPREAD` 让中心超出饱和距离整整一倍，断言才真的咬住。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/bingbaihanji/xuan/text/SdfGenerator.java \
        src/test/java/com/bingbaihanji/xuan/text/SdfGeneratorTest.java
git commit -F - <<'EOF'
feat(text): SdfGenerator —— 覆盖度位图转有符号距离场

用 Felzenszwalb & Huttenlocher 的精确欧氏距离变换，逐行再做逐列。
不用八邻域的切比雪夫近似：后者算出的斜向距离偏小，会让撇、捺、点这些
斜笔画显得比正交笔画粗。

编码 0=远在字形外 / 128=边界 / 255=远在字形内，在 SPREAD 像素外饱和。
着色器把它当 d-0.5 用，平滑宽度由 fwidth 在屏幕空间求——这一步没法预先
烘进纹理，因为同一个字形会在不同尺寸下被绘制，这正是 SDF 相对位图的价值。

外扩那一圈还有个不显眼的作用：把双线性过滤的采样限制在自己的槽位内，
不让相邻字形互相渗色。

纯函数、零 GL 依赖，7 条单测 + 4 条变异验证（欧氏/切比雪夫、编码符号、
外扩、阈值方向）。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 2: GLAbstraction 补 R8 纹理的创建与局部上传

**Files:**
- Modify: `src/main/java/com/bingbaihanji/xuan/gl/GLAbstraction.java`
- Modify: `src/main/java/com/bingbaihanji/xuan/gl/LwjglGLAbstraction.java`
- Modify: `src/test/java/com/bingbaihanji/xuan/gl/FakeGLAbstraction.java`

- [ ] **Step 1: 在接口上声明**

在 `GLAbstraction.java` 的 `createTexture(int width, int height, int[] pixels)` 声明之后加：

```java
    /**
     * 创建一张 {@code GL_R8} 单通道归一化纹理，内容未初始化。
     *
     * <p>与拾取用的 {@code GL_R32UI} 只差两个字母，但语义相反：
     * {@code R8} 是<strong>归一化</strong>格式，可以用 {@code GL_LINEAR} 过滤
     * （SDF 正需要靠插值得到平滑边缘）；{@code R8UI} 是<strong>整数</strong>格式，
     * 必须用 {@code GL_NEAREST}。混用不会报错，只会得到全糊或全锯齿的画面。
     *
     * @param width  宽度（像素）
     * @param height 高度（像素）
     * @return 纹理 ID
     */
    int createR8Texture(int width, int height);

    /**
     * 把一块 8 位单通道数据上传到纹理的指定矩形区域。
     *
     * <p><strong>实现必须处理 {@code GL_UNPACK_ALIGNMENT}</strong>：它的默认值是 4，
     * 而单通道每行只有 {@code width} 个字节，{@code width} 不是 4 的倍数时
     * GL 会按 4 字节对齐去读，<strong>从第二行起整行错位</strong>，
     * 表现为字形被斜切。上传前后必须设/恢复该状态。
     *
     * @param texture 目标纹理
     * @param x       目标矩形左边缘
     * @param y       目标矩形上边缘
     * @param width   矩形宽度
     * @param height  矩形高度
     * @param pixels  数据，长度必须为 {@code width * height}，按行存储
     */
    void uploadR8SubImage(int texture, int x, int y, int width, int height, byte[] pixels);
```

- [ ] **Step 2: 实现**

在 `LwjglGLAbstraction.java` 里加（放在 `createTexture` 之后）：

```java
    @Override
    public int createR8Texture(int width, int height) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // R8 是归一化格式，可以 LINEAR —— SDF 靠插值得到平滑边缘。
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        // CLAMP_TO_EDGE：默认的 REPEAT 会让图集边缘的采样绕回另一侧，
        // 表现为远端字形的边缘挂着别处的像素。
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // 内容未初始化：图集只采样已经上传过的槽位，而每个槽位四周有 SPREAD 像素的
        // 外扩把双线性过滤限制在槽内，所以未分配区域永远不会被采到。
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, width, height, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    @Override
    public void uploadR8SubImage(int texture, int x, int y, int width, int height, byte[] pixels) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        // GL_UNPACK_ALIGNMENT 默认是 4，而单通道每行只有 width 个字节。
        // 不设成 1，width 不是 4 的倍数时从第二行起整行错位，字形会被斜切。
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        try {
            // 不能用 MemoryStack：它默认只有 64 KB，而一张 64x64 的字形是 4 KB
            // 还好、整块图集就是 16 MB，直接炸。BufferUtils 的直接缓冲交给 GC。
            ByteBuffer buf = BufferUtils.createByteBuffer(width * height);
            buf.put(pixels, 0, width * height);
            buf.flip();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, width, height,
                    GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, buf);
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
    }
```

补两条 import：`import org.lwjgl.BufferUtils;`（若已有则跳过）、`import org.lwjgl.opengl.GL12;`

- [ ] **Step 3: 假实现**

在 `FakeGLAbstraction.java` 里加：

```java
    /** 按纹理 ID 记录的虚拟图集内容，用于断言上传落在正确的区域。 */
    public final java.util.Map<Integer, byte[]> r8Textures = new java.util.HashMap<>();

    /** 每次 uploadR8SubImage 的调用记录。 */
    public final java.util.List<String> r8Uploads = new java.util.ArrayList<>();

    private int nextR8Size = 0;

    @Override
    public int createR8Texture(int width, int height) {
        int id = nextId++;
        r8Textures.put(id, new byte[width * height]);
        nextR8Size = width;
        return id;
    }

    @Override
    public void uploadR8SubImage(int texture, int x, int y, int width, int height, byte[] pixels) {
        maybeThrow();
        r8Uploads.add(x + "," + y + "," + width + "," + height);
        byte[] target = r8Textures.get(texture);
        if (target == null) {
            throw new AssertionError("上传到了未创建的 R8 纹理：" + texture);
        }
        for (int row = 0; row < height; row++) {
            System.arraycopy(pixels, row * width,
                    target, (y + row) * nextR8Size + x, width);
        }
    }
```

（`maybeThrow()` 是 Task 6.5 加进假 GL 的故障注入开关，直接复用。）

- [ ] **Step 4: 编译并跑全量测试**

Run: `mvn -o compile && mvn -o test`
Expected: BUILD SUCCESS；`Tests run: 177, Failures: 0, Errors: 0, Skipped: 2`（170 + 7）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/bingbaihanji/xuan/gl/GLAbstraction.java \
        src/main/java/com/bingbaihanji/xuan/gl/LwjglGLAbstraction.java \
        src/test/java/com/bingbaihanji/xuan/gl/FakeGLAbstraction.java
git commit -F - <<'EOF'
feat(gl): R8 单通道纹理的创建与局部上传

SDF 图集需要两张能力：一张可 LINEAR 过滤的归一化单通道纹理，以及往它的
任意矩形区域写像素——字形是按需生成的，不能每来一个新字形就重传整张图集。

两处必须做对、错了都不报错的地方：

GL_UNPACK_ALIGNMENT 默认是 4，而单通道每行只有 width 个字节。不设成 1，
width 不是 4 的倍数时从第二行起整行错位，字形被斜切。上传后恢复。

上传缓冲不能用 MemoryStack（默认 64 KB）：单张字形 4 KB 尚可，整块图集
16 MB 直接炸。改用 BufferUtils 的直接缓冲。

CLAMP_TO_EDGE 同理：默认的 REPEAT 会让图集边缘的采样绕回另一侧。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## 全量测试计数

起点 **170**（子项目 C 收尾时的实测值，见 `2026-09-17-xuan-gpu-picking.md` 的计数表）。
下表每个数字都是照着**代码块里 `@Test` 的条数**点出来的，不是估的；改动任何一个任务的
测试都要同步改这张表。

> **⚠️ 这张表假设串行执行。** 若多个任务**并行**（本项目 2026-09-20 的实际做法），
> 每个执行者看到的全量数取决于**别人的任务是否已落地**，中间的绝对数字会对不上——
> 例如 Task 5 做完时基数是 182 而不是 192，只因为 Task 3 当时还没提交。
>
> **并行时判定标准不是这张表**，而是：**你自己新增的测试条数对不对 + 全量无失败**。
> 绝对数**如实报告即可，绝不允许为了让数字对上而增删测试**。
>
> 另一个并行副作用：多个代理同时重编译 `target/classes` 会让 surefire 偶发
> `Unable to create test class`（不是代码问题，重跑即可）。**遇到先重跑一次再判断。**

| 任务 | 新增测试 | 全量 |
|------|----------|------|
| 起点 | —— | 170 |
| Task 1 | +7 `SdfGeneratorTest` | 177 |
| Task 2 | 0（纯管道，不写橡皮图章测试） | 177 |
| Task 3 | +10 `GlyphAtlasTest` | **187** |
| Task 4 | +5 `FontFileTest` | **192** |
| Task 5 | +4 `GlyphRasterizerTest` | **196** |
| Task 6 | +6 `TextLayoutTest` | **202** |
| Task 7 | +3 `VertexWriterTest`（`VertexFormatTest` 里那条是**改**不是加） | **205** |
| Task 8 | 0（`Gc` 要真 GL 上下文，由 Task 9 的校验器覆盖） | 205 |
| Task 9 | 0（校验器不是单测） | 205 |
| Task 10 | 0（只改文档） | **205** |

Task 3 的 10 条比派单时列的 8 条多两条：**空字形不发上传**与**货架耗尽抛异常**。
这两条都是 Task 3 自己行为清单里要求的行为（"空字形……也不上传"、"货架耗尽抛
`IllegalStateException`"），却没有任何测试对着它们——本项目最擅长产生的正是
"代码里写了、但没人防守"的静默缺口，所以补上，计数随之改为 10。

**数字对不上就停下来查**，不要为了让计数凑上而增删测试——本项目已经踩过两次，
详见 picking 计划里「数字对不上就停下来查」那一节。**计划里的数字是笔算结果，
代码块才是唯一事实来源。**

---

## Task 3: GlyphBitmap / GlyphSlot / GlyphSource / GlyphAtlas

这一组是**唯一能零 GL、零字体依赖地单测**的部分：图集不认识字体、也不认识 SDF，
它只认识 `GlyphSource` 这个函数式接口。喂一个返回固定尺寸位图的假实现，
分配、uv、上传、重置、耗尽全都能在毫秒级测完。

**Files:**
- Create: `src/main/java/com/bingbaihanji/xuan/text/GlyphBitmap.java`
- Create: `src/main/java/com/bingbaihanji/xuan/text/GlyphSlot.java`
- Create: `src/main/java/com/bingbaihanji/xuan/text/GlyphSource.java`
- Create: `src/main/java/com/bingbaihanji/xuan/text/GlyphAtlas.java`
- Test: `src/test/java/com/bingbaihanji/xuan/text/GlyphAtlasTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/xuan/text/GlyphAtlasTest.java`：

```java
package com.bingbaihanji.xuan.text;

import com.bingbaihanji.xuan.gl.FakeGLAbstraction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GlyphAtlas} 的单测：**零 GL 上下文、零字体依赖**。
 *
 * <p>这是整个文本子系统里唯一能做到这一点的部分——图集只认识 {@link GlyphSource}
 * 这个接口，喂一个返回固定尺寸位图的假实现就能把分配、uv、上传、重置、耗尽全测完。
 * 真字体与真 SDF 由 {@code GlyphRasterizerTest} 和 {@code TextVerifier} 覆盖。
 */
class GlyphAtlasTest {

    /** 假的字形来源：按 key 生成固定尺寸的位图，并记录每个 key 被问了几次。 */
    private static final class FakeSource implements GlyphSource {

        /** 默认位图宽。 */
        int width = 64;

        /** 默认位图高。 */
        int height = 64;

        /** 默认推进宽度（em 像素）。 */
        float advance = 48f;

        /** 尺寸例外：key → {宽, 高}。用来造"空字形"与"比图集还大的字形"。 */
        final Map<Integer, int[]> sizes = new HashMap<>();

        /** 每个 key 被索取的次数。 */
        private final Map<Integer, Integer> calls = new HashMap<>();

        @Override
        public GlyphBitmap pixelsFor(int key) {
            calls.merge(key, 1, Integer::sum);
            int[] wh = sizes.get(key);
            int w = wh == null ? width : wh[0];
            int h = wh == null ? height : wh[1];
            byte[] pixels = new byte[w * h];
            // 用 key 低 8 位填充：这样"上传的内容确实落在自己的槽位里"可以被逐像素断言，
            // 而不是只能断言"上传过一次"。
            Arrays.fill(pixels, (byte) (key & 0xFF));
            return new GlyphBitmap(w, h, -2, -40, advance, pixels);
        }

        int calls(int key) {
            return calls.getOrDefault(key, 0);
        }
    }

    /** 槽位左上角在纹理里的 x。uv 带半点偏移，所以反过来算时要先减掉。 */
    private static int slotX(GlyphSlot slot) {
        return Math.round(slot.u0() * GlyphAtlas.SIZE - 0.5f);
    }

    /** 槽位左上角在纹理里的 y。 */
    private static int slotY(GlyphSlot slot) {
        return Math.round(slot.v0() * GlyphAtlas.SIZE - 0.5f);
    }

    private static void assertNoOverlap(GlyphSlot a, GlyphSlot b) {
        int ax = slotX(a);
        int ay = slotY(a);
        int bx = slotX(b);
        int by = slotY(b);
        boolean disjoint = ax + a.width() <= bx || bx + b.width() <= ax
                || ay + a.height() <= by || by + b.height() <= ay;
        assertTrue(disjoint, "两个字形的槽位重叠了：a=(" + ax + "," + ay + ") "
                + a.width() + "x" + a.height() + "，b=(" + bx + "," + by + ") "
                + b.width() + "x" + b.height());
    }

    @Test
    void 同一个key只生成一次() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        FakeSource source = new FakeSource();
        GlyphAtlas atlas = new GlyphAtlas(gl, source);

        GlyphSlot first = atlas.acquire(7);
        GlyphSlot second = atlas.acquire(7);

        assertEquals(1, source.calls(7), "同一个字形第二次索取必须命中缓存，不能重新光栅化");
        assertEquals(first, second, "两次拿到的槽位必须完全相同");
        assertEquals(1, gl.r8Uploads.size(), "命中缓存不该再上传一次");
    }

    @Test
    void 不同的key分配到的槽位不重叠() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        GlyphAtlas atlas = new GlyphAtlas(gl, new FakeSource());

        GlyphSlot a = atlas.acquire(1);
        GlyphSlot b = atlas.acquire(2);
        GlyphSlot c = atlas.acquire(3);

        assertNoOverlap(a, b);
        assertNoOverlap(a, c);
        assertNoOverlap(b, c);
    }

    @Test
    void uv带半点偏移且归一化在0到1之间() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        GlyphAtlas atlas = new GlyphAtlas(gl, new FakeSource());

        GlyphSlot slot = atlas.acquire(1);
        int w = slot.width();
        int h = slot.height();

        assertTrue(slot.u0() > 0f, "第一个槽位在左上角，左边缘仍要留出半个纹素，不能恰好是 0");
        assertTrue(slot.v0() > 0f, "同理，上边缘也不能恰好是 0");
        assertTrue(slot.u1() < 1f && slot.v1() < 1f);

        // 采样点落在纹素中心（i + 0.5）。于是"首尾两个纹素中心之间的距离"是
        // (w - 1) / SIZE，而不是 w / SIZE。去掉半点偏移后这里会变成 w / SIZE，
        // 差出 1/SIZE —— 恰好能被这两条断言抓住。
        assertEquals((w - 1) / (float) GlyphAtlas.SIZE, slot.u1() - slot.u0(), 1e-6f,
                "uv 少了半点纹素偏移：会采到相邻纹素，表现为边缘发虚或字形轻微错位");
        assertEquals((h - 1) / (float) GlyphAtlas.SIZE, slot.v1() - slot.v0(), 1e-6f);
    }

    @Test
    void 上传的内容落在自己的槽位内() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        GlyphAtlas atlas = new GlyphAtlas(gl, new FakeSource());

        GlyphSlot slot = atlas.acquire(5);
        byte[] texture = gl.r8Textures.get(atlas.textureId());
        assertNotNull(texture, "图集必须真的创建了一张纹理");

        int x = slotX(slot);
        int y = slotY(slot);
        for (int row = 0; row < slot.height(); row++) {
            for (int col = 0; col < slot.width(); col++) {
                assertEquals((byte) 5, texture[(y + row) * GlyphAtlas.SIZE + (x + col)],
                        "槽位内 (" + col + "," + row + ") 的像素不是这个字形的数据");
            }
        }
        // 槽位右侧紧邻的一列仍是 0：证明写入没有越界到邻居身上。
        assertEquals(0, texture[y * GlyphAtlas.SIZE + x + slot.width()],
                "写入越界到了槽位右边");
    }

    @Test
    void 一行填满之后换到下一行() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        FakeSource source = new FakeSource();
        source.width = GlyphAtlas.SIZE / 2;   // 一行恰好放两个
        source.height = 8;
        GlyphAtlas atlas = new GlyphAtlas(gl, source);

        GlyphSlot first = atlas.acquire(1);
        GlyphSlot second = atlas.acquire(2);
        GlyphSlot third = atlas.acquire(3);

        assertEquals(0, slotX(first));
        assertEquals(GlyphAtlas.SIZE / 2, slotX(second), "第二个应当紧挨着第一个");
        assertEquals(slotY(first), slotY(second), "同一行的 v 应当相同");
        assertEquals(0, slotX(third), "第三个应当回到行首");
        assertEquals(8, slotY(third), "第三个应当在下一行，行高是本行位图的最大高度");
    }

    @Test
    void 位图大于图集时抛异常() {
        FakeSource source = new FakeSource();
        source.sizes.put(1, new int[]{GlyphAtlas.SIZE + 1, 4});
        GlyphAtlas atlas = new GlyphAtlas(new FakeGLAbstraction(), source);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> atlas.acquire(1));
        assertTrue(e.getMessage().contains(String.valueOf(GlyphAtlas.SIZE)),
                "消息里要带上图集边长，否则读者不知道该把上限调成多少");
    }

    @Test
    void 货架耗尽时抛异常且下一个帧边界自愈() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        GlyphAtlas atlas = new GlyphAtlas(gl, new FakeSource());   // 默认 64x64

        // 循环必须有界：若耗尽检测被改坏，无界循环会让测试**挂住**而不是失败，
        // 那比失败更难查。
        int placed = 0;
        IllegalStateException failure = null;
        for (int i = 0; i < 5000 && failure == null; i++) {
            try {
                atlas.acquire(placed);
                placed++;
            } catch (IllegalStateException e) {
                failure = e;
            }
        }
        assertNotNull(failure, "装了 5000 个 64x64 的字形都没报满——4096x4096 只装得下 4096 个");
        assertEquals(64 * 64, placed, "4096 / 64 = 每行 64 个、共 64 行，恰好 4096 个槽位");
        assertTrue(failure.getMessage().contains("已满"), "消息要说清是图集满了：" + failure.getMessage());

        // 帧边界整体重置：分代重置的全部意义就在这里——失效只可能发生在帧边界，
        // 因此同一帧内已经发射过的 uv 绝不会被回收掉（规格 §5.3 那类缺陷）。
        atlas.beginFrame();
        assertDoesNotThrow(() -> atlas.acquire(9999), "下一个帧边界应当整体重置并自愈");
    }

    @Test
    void reset之后字形会重新生成() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        FakeSource source = new FakeSource();
        GlyphAtlas atlas = new GlyphAtlas(gl, source);

        GlyphSlot before = atlas.acquire(3);
        atlas.reset();
        GlyphSlot after = atlas.acquire(3);

        assertEquals(2, source.calls(3), "reset 之后缓存必须失效，字形要重新生成");
        assertEquals(2, gl.r8Uploads.size(), "重新生成必然伴随一次重新上传");
        assertEquals(before, after, "重置后货架从左上角重新开始，分配结果应当与第一次相同");
    }

    @Test
    void 空字形只让笔前进不占槽位也不上传() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        FakeSource source = new FakeSource();
        source.sizes.put(9, new int[]{0, 0});      // 空格这类字形
        GlyphAtlas atlas = new GlyphAtlas(gl, source);

        GlyphSlot slot = atlas.acquire(9);

        assertEquals(0, slot.width());
        assertEquals(0, slot.height());
        assertEquals(48f, slot.advance(), 0f, "空字形仍要让笔前进——这是正常情况，不是错误");
        assertTrue(gl.r8Uploads.isEmpty(), "空字形不该上传任何东西");

        // 空字形也不能推动货架游标：下一个真实字形仍然从左上角开始。
        GlyphSlot real = atlas.acquire(1);
        assertEquals(0, slotX(real));
        assertEquals(0, slotY(real));
    }

    @Test
    void 释放之后再acquire会抛异常() {
        FakeGLAbstraction gl = new FakeGLAbstraction();
        GlyphAtlas atlas = new GlyphAtlas(gl, new FakeSource());

        atlas.dispose();
        atlas.dispose();     // 幂等

        assertEquals(1, gl.deletedTextures.size(), "重复 dispose 不该重复删除纹理");
        assertThrows(IllegalStateException.class, () -> atlas.acquire(1),
                "释放之后再分配必须炸：纹理名字可能已经被驱动发给了别的纹理");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=GlyphAtlasTest`
Expected: 编译失败 — `找不到符号: 类 GlyphBitmap`（以及 `GlyphSlot`、`GlyphSource`、`GlyphAtlas`）

- [ ] **Step 3: 写三个数据类型与函数式接口**

创建 `src/main/java/com/bingbaihanji/xuan/text/GlyphBitmap.java`：

```java
package com.bingbaihanji.xuan.text;

/**
 * 一个字形光栅化之后的位图：尺寸、相对于基线的偏移、推进宽度，以及像素本身。
 *
 * <h2>坐标约定</h2>
 * <p>{@code offsetX}/{@code offsetY} 是<strong>位图左上角相对于笔位置（基线起点）的偏移</strong>，
 * 单位是 em 像素，y 向下（与本项目其余部分一致）。
 * 因此 {@code offsetY} <strong>通常是负数</strong>——字形画在基线<em>之上</em>，
 * 而 y 向下意味着"之上"是更小的 y。
 * 这一条是最容易写反的地方，写反的表现是字形整体跑到基线下面去，而画面"看起来只是位置偏了"。
 *
 * <h2>为什么带 SPREAD 的那一圈</h2>
 * <p>{@code pixels} 是距离场，尺寸为「字形包围盒 + 每边 {@link SdfGenerator#SPREAD} 像素」。
 * 外扩那一圈的作用是把双线性过滤限制在自己的槽位内，同时给平滑边缘留出距离信息。
 *
 * @param width   位图宽度（像素），含两侧外扩
 * @param height  位图高度（像素），含上下外扩
 * @param offsetX 位图左边缘相对笔位置的 x 偏移（em 像素）
 * @param offsetY 位图<strong>上</strong>边缘相对基线的 y 偏移（em 像素），通常为负数
 * @param advance 推进宽度（em 像素）：画完这个字形之后笔要往右挪多少
 * @param pixels  距离场像素，长度必须为 {@code width * height}，按行存储
 */
public record GlyphBitmap(int width, int height, int offsetX, int offsetY,
                          float advance, byte[] pixels) {

    public GlyphBitmap {
        if (pixels.length != width * height) {
            throw new IllegalArgumentException(
                    "像素数量与尺寸不符：期望 " + (width * height) + "，实际 " + pixels.length);
        }
    }
}
```

创建 `src/main/java/com/bingbaihanji/xuan/text/GlyphSlot.java`：

```java
package com.bingbaihanji.xuan.text;

/**
 * 一个字形在图集里占据的槽位：uv 矩形 + 相对基线的偏移 + 尺寸 + 推进宽度。
 *
 * <h2>单位</h2>
 * <p><strong>除了 uv，其余全部是 {@code GlyphRasterizer.EM_SIZE} 尺寸下的像素，
 * 不预乘字号缩放。</strong>
 * 字号缩放由 {@code TextLayout} 在发射顶点时乘上去。放在这里预乘是错的：
 * 同一个槽位会被不同字号复用，图集是按字形而不是按 (字形, 字号) 缓存的。
 *
 * <h2>uv 的半点偏移</h2>
 * <p>{@code u0}/{@code v0} 取的是槽位<strong>第一个纹素的中心</strong>，
 * {@code u1}/{@code v1} 取的是<strong>最后一个纹素的中心</strong>，
 * 即 {@code (x + 0.5) / SIZE} 与 {@code (x + w - 0.5) / SIZE}。
 * 直接写 {@code x / SIZE} 会采到相邻纹素，表现为边缘发虚或字形轻微错位——
 * 这是"看起来只是有点糊"的那类错误，很难靠肉眼定位。
 *
 * @param u0      纹理坐标左边界（首个纹素中心）
 * @param v0      纹理坐标上边界（首个纹素中心）
 * @param u1      纹理坐标右边界（末个纹素中心）
 * @param v1      纹理坐标下边界（末个纹素中心）
 * @param offsetX 位图左边缘相对笔位置的 x 偏移（em 像素）
 * @param offsetY 位图上边缘相对基线的 y 偏移（em 像素），通常为负数
 * @param width   位图宽度（像素），为 0 表示空字形
 * @param height  位图高度（像素），为 0 表示空字形
 * @param advance 推进宽度（em 像素）
 */
public record GlyphSlot(float u0, float v0, float u1, float v1,
                        int offsetX, int offsetY, int width, int height, float advance) {

    /**
     * 是否为空字形（宽或高为 0）。
     *
     * <p>空字形（空格、以及度量上宽度为 0 的字形）只让笔前进，
     * <strong>不发顶点、也不上传</strong>。这是正常情况而不是错误。
     *
     * @return 空字形时为 true
     */
    public boolean isEmpty() {
        return width == 0 || height == 0;
    }
}
```

创建 `src/main/java/com/bingbaihanji/xuan/text/GlyphSource.java`：

```java
package com.bingbaihanji.xuan.text;

/**
 * 字形位图的来源：给一个 key，返回它的位图。
 *
 * <p><strong>图集只认识这个接口，不认识字体，也不认识 SDF。</strong>
 * 这个切分是为了可测：{@code GlyphAtlasTest} 喂一个返回固定尺寸位图的假实现，
 * 就能在不加载 stb、不开 GL 上下文的前提下测全部分配逻辑；
 * 真实实现 {@link FontGlyphSource}（把 {@link FontFile} + {@link GlyphRasterizer} +
 * {@link SdfGenerator} 串起来）则由 {@code TextVerifier} 覆盖。
 *
 * <p><strong>实现必须是同步的、且不得返回 {@code null}。</strong>
 * 本接口在 GL 线程上被调用，首次出现的字形会在这里当场完成光栅化与距离场计算。
 *
 * <p>key 的含义由实现定义。真实实现按<strong>字形索引</strong>缓存，
 * 因为本期只有一个 em 尺寸桶（见 {@link GlyphRasterizer#EM_SIZE}）。
 */
@FunctionalInterface
public interface GlyphSource {

    /**
     * 返回 key 对应的字形位图。
     *
     * @param key 字形标识，含义由实现定义
     * @return 位图，不得为 {@code null}
     */
    GlyphBitmap pixelsFor(int key);
}
```

- [ ] **Step 4: 写 GlyphAtlas**

创建 `src/main/java/com/bingbaihanji/xuan/text/GlyphAtlas.java`：

```java
package com.bingbaihanji.xuan.text;

import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.util.Disposable;

import java.util.HashMap;
import java.util.Map;

/**
 * 字形图集：一张固定大小的 {@code GL_R8} 纹理，加一个货架（shelf）分配器。
 *
 * <h2>它不认识字体，也不认识 SDF</h2>
 * <p>它是一个通用的「小位图图集」：给一个 key，返回一个 {@link GlyphSlot}（uv 矩形 + 偏移）；
 * 未命中时通过 {@link GlyphSource} 索取像素。只依赖 {@link GLAbstraction} <strong>接口</strong>，
 * 因此和 {@code PickBuffer} 一样可以用 {@code FakeGLAbstraction} 零 GL 上下文单测。
 *
 * <h2>为什么是"只增不减 + 帧边界整体重置"，而不是逐槽 LRU 淘汰</h2>
 * <p>规格 §3.1 原本写的是「动态图集 + LRU 逐槽淘汰」，本实现改为分代重置，理由：
 * <ol>
 *   <li><strong>逐槽淘汰正是规格 §5.3 那个 bug 的温床</strong>——淘汰掉一个本帧已经
 *       发射过 uv 的槽，会让同一帧内的字形互相覆盖，表现为"某些字偶尔变成别的字"。
 *       要靠"只淘汰本帧没用过的槽"来防，那是给一个本可不存在的问题写防守。</li>
 *   <li>货架分配 + 逐槽回收<strong>必然碎片化</strong>，最终会"还有空间却分配不出"。</li>
 *   <li>对驱动场景（图表，几百个字形）两者都不会触发，区别只在真触发时哪个是对的。</li>
 * </ol>
 * <p><strong>做法</strong>：固定 {@value #SIZE}×{@value #SIZE}（R8，16 MB 显存），
 * 容量 4096 个 64² 槽位，只增不减；分配器判定装不下时置位 {@code exhausted} 并抛异常，
 * 下一个 {@link #beginFrame()} 发现该标志就整体重置。
 * 于是"失效"只可能发生在<strong>帧边界</strong>、且是<strong>整体失效</strong>，
 * §5.3 那类 bug 在结构上不可能发生。
 *
 * <p><strong>为什么不就地重置</strong>：耗尽的那一刻很可能已经往顶点缓冲里发射过
 * 本帧的 uv 了，此刻重置等于把那些 uv 指向别人的字形——正是上面第 1 条要避免的事。
 * 所以帧中途唯一安全的动作是<strong>抛异常</strong>，把问题暴露在它发生的地方。
 *
 * <h2>线程</h2>
 * <p>非线程安全，且必须在 GL 线程上使用（{@link GLAbstraction} 的约定）。
 */
public final class GlyphAtlas implements Disposable {

    /**
     * 图集边长（像素）。
     *
     * <p>取 4096 是显存与容量的折中：R8 单通道，16 MB 显存，装得下 4096 个 64² 的槽位。
     * 单字形位图的最大边长是「em 尺寸 + 2×spread」（见 {@link GlyphRasterizer#EM_SIZE}
     * 与 {@link SdfGenerator#SPREAD}），图集边长必须<strong>不小于</strong>它，
     * 否则一个字形的槽位就放不进一行。
     */
    public static final int SIZE = 4096;

    /** GL 抽象层。 */
    private final GLAbstraction gl;

    /** 字形来源。 */
    private final GlyphSource source;

    /** 图集纹理的 ID。 */
    private final int texture;

    /** 已分配的槽位，key → 槽位。 */
    private final Map<Integer, GlyphSlot> slots = new HashMap<>();

    /** 当前货架行的起始 x。 */
    private int shelfX = 0;

    /** 当前货架行的起始 y。 */
    private int shelfY = 0;

    /** 当前货架行的高度（本行内已放入位图的最大高度）。 */
    private int shelfHeight = 0;

    /** 上一次分配是否判定为"装不下"。为 true 时下一个 {@link #beginFrame()} 会整体重置。 */
    private boolean exhausted = false;

    /** 是否已释放。 */
    private boolean disposed = false;

    /**
     * 创建图集并分配纹理。
     *
     * <p>必须在 GL 线程（且 GL 上下文已 current）上调用。
     *
     * @param gl     GL 抽象层
     * @param source 字形来源
     * @throws IllegalArgumentException 任一参数为 {@code null} 时
     */
    public GlyphAtlas(GLAbstraction gl, GlyphSource source) {
        if (gl == null) {
            throw new IllegalArgumentException("gl 不能为 null");
        }
        if (source == null) {
            throw new IllegalArgumentException("source 不能为 null");
        }
        this.gl = gl;
        this.source = source;
        this.texture = gl.createR8Texture(SIZE, SIZE);
    }

    /**
     * 返回图集纹理的 ID。
     *
     * <p>{@link #dispose()} 之后返回的是一个已被删除的名字——调用方不应在释放后使用它。
     *
     * @return 纹理 ID
     */
    public int textureId() {
        return texture;
    }

    /**
     * 帧边界：若上一帧判定过"装不下"，就整体重置。
     *
     * <p>整体重置会让<strong>此前发射过的全部 uv 失效</strong>，所以只能在帧边界做——
     * 这也是本类选择"分代重置"而不是"逐槽淘汰"的全部理由（见类说明）。
     */
    public void beginFrame() {
        if (exhausted) {
            resetShelves();
            slots.clear();
            exhausted = false;
        }
    }

    /**
     * 取一个字形的槽位，未命中则向 {@link GlyphSource} 索取并上传。
     *
     * @param key 字形标识，含义由 {@link GlyphSource} 实现定义
     * @return 槽位；空字形返回宽高为 0 的槽位
     * @throws IllegalStateException    已释放后调用时；货架装不下时
     * @throws IllegalArgumentException 单个位图大于图集边长时
     */
    public GlyphSlot acquire(int key) {
        if (disposed) {
            throw new IllegalStateException(
                    "字形图集已释放：dispose() 之后不能再 acquire（纹理名字可能已经发给别人了）");
        }
        GlyphSlot cached = slots.get(key);
        if (cached != null) {
            return cached;
        }
        GlyphSlot slot = allocate(source.pixelsFor(key));
        slots.put(key, slot);
        return slot;
    }

    /**
     * 丢弃全部槽位并从左上角重新开始分配。
     *
     * <p>已经发射出去的 uv 在调用之后<strong>立即失效</strong>，因此不要在帧中途调用。
     * 正常情况下不需要手动调用——{@link #beginFrame()} 会在需要时自己重置。
     */
    public void reset() {
        slots.clear();
        resetShelves();
        exhausted = false;
    }

    /** 释放图集纹理。重复调用无副作用，释放后不可再 {@link #acquire}。 */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        gl.deleteTexture(texture);
        slots.clear();
        disposed = true;
    }

    /**
     * 在货架里分配一块 {@code w×h} 的区域并上传像素，返回槽位。
     *
     * @param bitmap 字形位图
     * @return 槽位
     */
    private GlyphSlot allocate(GlyphBitmap bitmap) {
        int w = bitmap.width();
        int h = bitmap.height();
        float advance = bitmap.advance();

        // 空字形：只让笔前进，不占槽位、不上传、不发顶点。这是正常情况而不是错误——
        // 若把它当成"分配一个 0 大小的槽"，货架的换行判断会被 0 宽度的位图搅乱
        // （明明放了东西的行高却不增长），下一个字形就会压在它上面。
        if (w == 0 || h == 0) {
            return new GlyphSlot(0f, 0f, 0f, 0f,
                    bitmap.offsetX(), bitmap.offsetY(), 0, 0, advance);
        }

        // 单字形必须能装进一行，否则分配会死循环。规格 §5.7 把这条列进了"必须做对的细节"。
        if (w > SIZE || h > SIZE) {
            throw new IllegalArgumentException(
                    "字形位图 " + w + "x" + h + " 大于图集边长 " + SIZE
                            + "：请调大 GlyphAtlas.SIZE（会线性增加显存），"
                            + "或调小 GlyphRasterizer.EM_SIZE / SdfGenerator.SPREAD");
        }

        if (shelfX + w > SIZE) {
            // 换行：上一行的高度就是新行的起始 y 增量
            shelfY += shelfHeight;
            shelfX = 0;
            shelfHeight = 0;
        }
        if (shelfY + h > SIZE) {
            // 不在这里重置：此刻很可能已经发射过本帧的 uv，重置会让它们指向别人的字形。
            // 只置位标志，交给下一个 beginFrame()（帧边界，整体失效是安全的）。
            exhausted = true;
            throw new IllegalStateException(
                    "字形图集已满（" + SIZE + "x" + SIZE + "）：本帧需要的字形超过图集的槽位容量。"
                            + "请调大 GlyphAtlas.SIZE，或减少单帧内出现的不同字形数"
                            + "（例如把文本拆到多帧绘制）");
        }

        int x = shelfX;
        int y = shelfY;
        shelfX += w;
        if (h > shelfHeight) {
            shelfHeight = h;
        }

        gl.uploadR8SubImage(texture, x, y, w, h, bitmap.pixels());

        // 采样点落在纹素中心（i + 0.5）：uv 必须按"首尾纹素中心"给出，
        // 否则会采到相邻纹素。规格 §5.4。
        float u0 = (x + 0.5f) / SIZE;
        float v0 = (y + 0.5f) / SIZE;
        float u1 = (x + w - 0.5f) / SIZE;
        float v1 = (y + h - 0.5f) / SIZE;
        return new GlyphSlot(u0, v0, u1, v1,
                bitmap.offsetX(), bitmap.offsetY(), w, h, advance);
    }

    /** 把货架游标带回左上角。 */
    private void resetShelves() {
        shelfX = 0;
        shelfY = 0;
        shelfHeight = 0;
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o test -Dtest=GlyphAtlasTest`
Expected: `Tests run: 10, Failures: 0, Errors: 0`

- [ ] **Step 6: 跑全量测试确认没有破坏别的**

Run: `mvn -o test`
Expected: `Tests run: 187, Failures: 0, Errors: 0, Skipped: 2`（177 + 10）

- [ ] **Step 7: 变异验证**（每条做完立刻还原，共 5 条）

| 变异 | 应失败的测试 |
|------|--------------|
| uv 去掉半点偏移。**注意要写成浮点除法**：`(x + 0.5f) / SIZE` → `(float) x / SIZE`（若直接写 `x / SIZE` 会变成**整数除法**，那是更粗暴的破坏，会连带弄坏别的断言，测不出"半点偏移"这一件事）。**更精确的做法是只把 `u1`/`v1` 的偏移去掉**，这样只有那条带符号的断言能触发 | `uv带半点偏移且归一化在0到1之间` |
| 空字形不提前返回，照常走分配（把 `w == 0 \|\| h == 0` 那个分支删掉） | `空字形只让笔前进不占槽位也不上传` |
| 耗尽时不抛异常，改为就地整体重置并继续分配 | `货架耗尽时抛异常且下一个帧边界自愈`（会拿到 5000 个槽位而不是 4096） |
| 换行时 `shelfY += shelfHeight` 改成 `shelfY += h` | `一行填满之后换到下一行` —— **前提是那一行的字形高度不一**。实测：若整行字形同高，换行点上 `shelfHeight == h`，正确实现与变异算出的值相同，**变异存活**。本测试的 fixture 因此必须让**同一行里出现不同高度的字形**（例如首个字形 32 高、后续 8 高），否则它区分不了「行高取该行最大」与「行高取触发换行那个字形的高度」 |
| `acquire` 不做缓存（每次都问 source 并重新分配） | `同一个key只生成一次` |

**第一条要特别当心**：这条变异有可能存活——去掉半点偏移后 uv 只差 1/4096，
若那条测试当时只断言了 `0 < u0 < u1 < 1`，除了 `u0` 变成 0 之外一切照常通过。
**因此断言写成了 `u1 - u0 == (w-1)/SIZE` 这种带符号的形式**，它对那 1/4096 的差别是敏感的。
若实测**仍然**存活，**必须改断言**（例如加一条 `assertTrue(slot.u1() < 1.0f)` 严格小于、
或把 `u0` 的断言收紧到 `> 1e-4f`），并如实记录改了什么——
**不要**把这条变异标成"已覆盖"就完事。

**若某条实测存活，先怀疑检查、再怀疑代码，然后如实上报**——本项目已经四次撞上
「变异表本身写错」。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/bingbaihanji/xuan/text/GlyphBitmap.java \
        src/main/java/com/bingbaihanji/xuan/text/GlyphSlot.java \
        src/main/java/com/bingbaihanji/xuan/text/GlyphSource.java \
        src/main/java/com/bingbaihanji/xuan/text/GlyphAtlas.java \
        src/test/java/com/bingbaihanji/xuan/text/GlyphAtlasTest.java
git commit -F - <<'EOF'
feat(text): 字形图集与它的三个数据类型

GlyphAtlas 只认识 GlyphSource 这个函数式接口，不认识字体也不认识 SDF——
这个切分是为了可测：喂一个返回固定尺寸位图的假实现，分配、uv、上传、重置、
耗尽全都能在零 GL 上下文、零字体依赖下测完（10 条单测，毫秒级）。

分配策略是"只增不减 + 帧边界整体重置"，不是规格原本写的逐槽 LRU：
逐槽淘汰正是规格 §5.3 那个 bug 的温床（淘汰掉本帧已发射 uv 的槽会让同帧字形
互相覆盖），而且必然碎片化。改为帧边界整体重置后，失效只可能整体发生，
那类缺陷在结构上不可能出现；代价是帧中途耗尽时只能抛异常——那正是它该做的，
因为此刻已经发射过的 uv 无法追回。

uv 按首尾纹素中心给出（(x+0.5)/SIZE 与 (x+w-0.5)/SIZE）：采样点在纹素中心，
少这半点偏移就会采到相邻纹素，表现为边缘发虚——"看起来只是有点糊"的那类错误。
断言写成 u1-u0 == (w-1)/SIZE 这种对 1/4096 敏感的带符号形式，否则变异会存活。

空字形（宽或高为 0）只让笔前进，不占槽位、不上传——这条单独有测试，
因为把它当成"分配一个 0 大小的槽"会搅乱货架的换行判断，让下一个字形压上来。

五条变异验证：uv 半点偏移、空字形、耗尽抛异常、换行行高、缓存。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 4: 字体文件入库 + FontFile

**Files:**
- Create: `src/main/resources/fonts/simhei.ttf`（从本机复制，9.7 MB）
- Create: `src/main/resources/fonts/README.md`
- Create: `src/main/java/com/bingbaihanji/xuan/text/FontFile.java`
- Test: `src/test/java/com/bingbaihanji/xuan/text/FontFileTest.java`

- [ ] **Step 1: 先做探针 —— stb 的本地库在 surefire 里能不能加载**

**在做任何别的事之前先跑这一步。** 本任务的 5 条测试全部依赖 `stbtt_*` 的本地库
（`lwjgl-stb` + `natives-windows` 已在 `pom.xml` 里，但本地库能否在 surefire 的
类加载器下解包并加载，只有实测知道）。

先在 `src/test/java/com/bingbaihanji/xuan/text/` 下建一个**临时**探针
（名字随便，比如 `StbProbeTest.java`，确认完就删）：

```java
package com.bingbaihanji.xuan.text;

import org.junit.jupiter.api.Test;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;

import static org.junit.jupiter.api.Assertions.*;

class StbProbeTest {

    @Test
    void 本地库能加载() {
        STBTTFontinfo info = STBTTFontinfo.malloc();
        try {
            assertNotEquals(0L, info.address(), "STBTTFontinfo 分配失败");
        } finally {
            info.free();
        }
        // 触发一次真正的本地调用：_malloc 能过不代表 stbtt_* 的符号也能解析
        assertTrue(STBTruetype.stbtt_FindGlyphIndex(info, 0x41) == 0,
                "未初始化的 fontinfo 上查字形索引应当返回 0（而不是崩溃）");
    }
}
```

Run: `mvn -o test -Dtest=StbProbeTest`

> **实测结论（2026-09-20）：探针通过，走「直接测真路径」分支，不需要 `StbBackend` 隔离层。**
>
> 但**本 Step 原本给的探针代码是错的，且错得很危险**：它断言"未初始化的
> `STBTTFontinfo` 上查字形索引应当返回 0（而不是崩溃）"。**那是未定义行为，
> 实际是整个 forked JVM 段错误**：
>
> ```
> #  EXCEPTION_ACCESS_VIOLATION (0xc0000005)
> # Problematic frame:
> # C  [lwjgl_stb.dll+0x39b38]
> Tests run: 0 ... The forked VM terminated without properly saying goodbye.
> ```
>
> 只要这个文件在工作区里，**任何人跑 `mvn test` 都会得到 0 测试的报告**，
> 并行执行时会毒化所有其他人的运行。
>
> 正确的探针必须**先 `stbtt_InitFont` 喂真实字体字节**再查字形。执行者改成这样后通过：
>
> ```
> [探针] 字体字节数 = 9745792
> [探针] stbtt_InitFont = true
> [探针] glyphIndex('A') = 36, glyphIndex('中') = 1123
> [探针] scaleForPixelHeight(48) = 0.1875
> ```
>
> 顺带一条**换字体时会踩的坑**：`scaleForPixelHeight(48) = 0.1875` 即 `48/256`，
> 说明黑体的 `unitsPerEm` 是 **256**（老式 CJK 字体的常见值，不是现在常见的
> 1000/2048）。所以**必须用 `stbtt_ScaleForPixelHeight`**，绝不能自己拿
> `unitsPerEm` 硬算缩放——换个字体就会整体错位。
>
> 另一条安全实践（由变异实证）：`FontFile.ensureAlive()` **不是防御性编程，是唯一能挡住
> 进程崩溃的东西**。去掉它之后，释放后读字形的测试不是"断言失败"而是**整个 JVM 段错误**，
> 签名与上面的探针崩溃完全一致。

- **探针通过** → 删掉这个临时文件，继续 Step 2。
- **探针失败**（`UnsatisfiedLinkError` / `ExceptionInInitializerError`）→ **停下来，
  把完整的失败输出如实上报**，然后按下面这条改：

  > 新建 `text/StbBackend.java`（接口：`initFont/ glyphIndex / scaleForPixelHeight /
  > hMetrics / glyphBox / glyphPixelBox / makeGlyphBitmap / free`）
  > 与 `text/LwjglStbBackend.java`（真实现，把现在写在 `FontFile` 里的 `stbtt_*`
  > 调用原样搬进去）。`FontFile` 改为持有一个 `StbBackend`，构造时由调用方注入。
  > `FontFileTest` 改用 `FakeStbBackend`——它对外暴露的**行为契约**（"已知字符的
  > 字形索引非 0"、"不存在码点回落 0"、"推进宽度随像素高度线性"）一条都不改，
  > 只是数据换成假的。真正的 stb 路径则由 `TextVerifier` 覆盖（它跑在 `exec:exec`
  > fork 出来的独立 JVM 里，不受 surefire 类加载器影响）。
  >
  > **不要**写一个"跳过测试"的注解把失败掩盖过去，也**不要**把它标成已覆盖。

- [ ] **Step 2: 把字体复制进仓库**

```bash
mkdir -p src/main/resources/fonts
cp "C:/Windows/Fonts/simhei.ttf" src/main/resources/fonts/simhei.ttf
ls -l src/main/resources/fonts/simhei.ttf
```

Expected: 大小 9745792 字节。

**提交一个 9.7 MB 的二进制文件是不可逆的仓库增重**（历史里永久保留）。
这是有意为之：文本子系统没有字体就完全跑不起来，而字体必须是仓库的一部分
才能让 `TextVerifier` 在任何机器上可复现。若将来要减小仓库体积，
正确的做法是换一个 OFL 授权的子集字体，**而不是**从历史里删文件。

- [ ] **Step 3: 写 fonts/README.md**

创建 `src/main/resources/fonts/README.md`：

```markdown
# 字体资源

## simhei.ttf

- **来源**：本机 `C:\Windows\Fonts\simhei.ttf`，Windows 自带的「黑体」（SimHei）。
- **大小**：9745792 字节（9.7 MB）。
- **为什么会在这里**：SDF 文本子系统需要一份能覆盖 CJK 常用区的字体，
  而它必须随仓库走，否则 `TextVerifier` 换台机器就跑不起来。

### ⚠️ 授权

**黑体是微软 / 中易（ZhongYi）的专有字体，不是自由字体。**

- 本机自用、内部开发、跑测试：没有问题。
- **若这个仓库要公开分发**（开源、发布 jar、镜像到别的平台）：**必须换掉它**。
  可选的 OFL 授权替代品：`NotoSansSC-VF.ttf`（同目录下就有，但它是可变字体，
  见下）、或从网上取静态版 Noto Sans SC / 思源黑体。

### 换字体的方法

替换本目录下的 `simhei.ttf` 文件即可（资源路径 `GlyphRasterizer` 一侧是写死的
`/fonts/simhei.ttf`，见 `FontFile.DEFAULT_RESOURCE`）。

选字体时有两条硬性约束：

1. **优先选 TTF（`glyf` 轮廓），避开 OTF（`CFF`/PostScript 轮廓）。**
   `stb_truetype` 对 CFF 的支持较弱，这是本期直接选 `simhei.ttf` 的原因。
2. **避开可变字体（带 `fvar`/`gvar` 表的 `.ttf`）。**
   `stb` 会忽略变体轴、只渲染默认实例——原则上能用，但第一次实现时不该同时
   跟可变字体较劲。`NotoSansSC-VF.ttf` 就属于这一类。

换完之后必须重跑 `TextVerifier`（见 `CLAUDE.md` 的「怎么验证改动」）：
不同的字体度量不同，`TextVerifier` 里凡是与具体字体相关的期望值都要重新核对。
```

- [ ] **Step 4: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/xuan/text/FontFileTest.java`：

```java
package com.bingbaihanji.xuan.text;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FontFile} 的单测。
 *
 * <p><strong>本类会加载 stb 的本地库。</strong>加载不了的话整个类都会报错——
 * 那是有意的：与其假装测过，不如让它在最显眼的地方失败，然后按实施计划里
 * Step 1 的处置办法把 stb 调用隔离到薄接口后面。
 */
class FontFileTest {

    private static FontFile font;

    @BeforeAll
    static void 加载字体() {
        font = FontFile.loadClasspath(FontFile.DEFAULT_RESOURCE);
    }

    @AfterAll
    static void 释放字体() {
        if (font != null) {
            font.dispose();
        }
    }

    @Test
    void stb原生库能在surefire环境加载并解析字体() {
        // 单独再加载一份并释放：既证明"能加载"，也证明"能释放"，而不动共享实例。
        FontFile probe = assertDoesNotThrow(
                () -> FontFile.loadClasspath(FontFile.DEFAULT_RESOURCE),
                "stb 的本地库在 surefire 里没能加载，或字体资源解析失败——"
                        + "见实施计划 Task 4 Step 1 的处置办法");
        assertTrue(probe.scaleForPixelHeight(48f) > 0f, "能算出度量才算真的加载成功");

        probe.dispose();
        probe.dispose();     // 幂等：重复释放不该重复 memFree
    }

    @Test
    void 已知字符的字形索引非零() {
        // 0 恒定是 .notdef。汉字与拉丁字母都必须拿到真实字形。
        assertTrue(font.glyphIndex('A') > 0, "拉丁字母 A 的字形索引");
        assertTrue(font.glyphIndex('中') > 0, "汉字「中」的字形索引");
        assertTrue(font.glyphIndex('0') > 0, "数字 0 的字形索引");
    }

    @Test
    void 字体里没有的码点回落到notdef() {
        // 规格 §5.6：拿到 0 不代表"跳过"，而是"照常画 .notdef（豆腐块）"。
        // 静默跳过的表现是"这段文字少了几个字"，用户会以为是排版 bug。
        assertEquals(0, font.glyphIndex(0x10FFFF),
                "非字符码点不在 simhei 里，必须回落成 0，调用方据此画 .notdef");
    }

    @Test
    void 推进宽度随像素高度线性放大() {
        int glyph = font.glyphIndex('中');
        float at48 = font.advance(glyph) * font.scaleForPixelHeight(48f);
        float at96 = font.advance(glyph) * font.scaleForPixelHeight(96f);

        assertTrue(at48 > 0f, "全角汉字的推进宽度必须为正");
        // 字号翻倍，推进宽度也翻倍——这正是"字号缩放靠乘法"这条设计的前提。
        assertEquals(2f, at96 / at48, 1e-3f,
                "scaleForPixelHeight 必须与像素高度成正比，否则字号与实际尺寸会对不上");
    }

    @Test
    void 释放之后再使用会抛异常() {
        FontFile temp = FontFile.loadClasspath(FontFile.DEFAULT_RESOURCE);
        temp.dispose();

        // 释放后字体字节已经被 memFree，再交给 stb 就是 use-after-free：
        // 它不会报错，只会读到垃圾或者直接崩进程。必须在 Java 侧挡住。
        assertThrows(IllegalStateException.class, () -> temp.glyphIndex('A'));
        assertThrows(IllegalStateException.class, () -> temp.scaleForPixelHeight(48f));
        assertThrows(IllegalStateException.class, () -> temp.advance(1));
    }
}
```

- [ ] **Step 5: 跑测试确认失败**

Run: `mvn -o test -Dtest=FontFileTest`
Expected: 编译失败 — `找不到符号: 类 FontFile`

- [ ] **Step 6: 写实现**

创建 `src/main/java/com/bingbaihanji/xuan/text/FontFile.java`：

```java
package com.bingbaihanji.xuan.text;

import com.bingbaihanji.xuan.util.Disposable;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 一份 TrueType 字体：stb 的封装，提供字形索引与度量。
 *
 * <h2>字体字节必须活到最后一刻</h2>
 * <p>stb 的 {@code stbtt_fontinfo} <strong>不复制</strong>字体数据，它只记住
 * 数据块的地址。因此本类：
 * <ol>
 *   <li>用 {@link MemoryUtil#memAlloc(int)} 分配<strong>堆外</strong>内存持有字体字节，
 *       并在 {@link #dispose()} 里 {@link MemoryUtil#memFree}；</li>
 *   <li><strong>绝不用 {@code MemoryStack}</strong>——它默认只有 64 KB，
 *       而字体是 9.7 MB，压栈的那一刻就炸。</li>
 * </ol>
 * <p>{@code STBTTFontinfo} 本身也是 {@code malloc} 出来的本地结构，同样在 dispose 里释放。
 *
 * <p>释放之后再调用任何方法都会抛 {@link IllegalStateException}：此刻字体字节已经
 * 归还，再交给 stb 就是 use-after-free——它不会报错，只会读到垃圾或者直接崩进程。
 *
 * <h2>线程</h2>
 * <p>非线程安全（内部有一个复用的度量暂存数组）。与 {@link GlyphAtlas} 一样，
 * 只在 GL 线程上使用。
 */
public final class FontFile implements Disposable {

    /** 默认字体资源路径，见 {@code src/main/resources/fonts/README.md}。 */
    public static final String DEFAULT_RESOURCE = "/fonts/simhei.ttf";

    /**
     * 字体字节的持有者。
     *
     * <p>这块内存<strong>必须</strong>比 {@link #info} 活得久：stb 只记住地址，不复制。
     * 用直接内存而不是 {@code MemoryStack}——后者默认 64 KB，装不下 9.7 MB 的字体。
     */
    private final ByteBuffer data;

    /** stb 的字体信息结构。 */
    private final STBTTFontinfo info;

    /** 度量查询的复用暂存，避免每个字形都往 {@code MemoryStack} 里压两格。 */
    private final int[] hMetricsScratch = new int[2];

    /** 光栅化时复用的直接缓冲，按需扩容。 */
    private ByteBuffer rasterScratch;

    /** 是否已释放。 */
    private boolean disposed = false;

    private FontFile(ByteBuffer data, STBTTFontinfo info) {
        this.data = data;
        this.info = info;
    }

    /**
     * 从文件加载字体。
     *
     * @param path 字体文件路径
     * @return 加载好的字体
     * @throws UncheckedIOException 读取失败时
     * @throws IllegalArgumentException 不是有效的 TrueType 字体时
     */
    public static FontFile load(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("读取字体文件失败: " + path, e);
        }
    }

    /**
     * 从流加载字体。
     *
     * @param in 字体数据的输入流，由本方法负责读完（但不负责关闭）
     * @return 加载好的字体
     * @throws UncheckedIOException 读取失败时
     * @throws IllegalArgumentException 不是有效的 TrueType 字体时
     */
    public static FontFile load(InputStream in) {
        byte[] bytes;
        try {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("读取字体数据失败", e);
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("字体数据为空");
        }

        // 用 memAlloc 而不是 MemoryStack：字体是 9.7 MB，MemoryStack 默认只有 64 KB。
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes);
        data.flip();

        STBTTFontinfo info = STBTTFontinfo.malloc();
        if (!STBTruetype.stbtt_InitFont(info, data)) {
            // 解析失败也要把这两块资源还回去，否则每失败一次泄一次
            info.free();
            MemoryUtil.memFree(data);
            throw new IllegalArgumentException(
                    "无法解析字体数据（stbtt_InitFont 返回 0）：请确认它是一个 TTF 文件。"
                            + "stb_truetype 对 CFF/PostScript 轮廓（.otf）支持较弱，"
                            + "可变字体（带 fvar/gvar）也只能渲染默认实例——"
                            + "见 src/main/resources/fonts/README.md");
        }
        return new FontFile(data, info);
    }

    /**
     * 从 classpath 加载字体。
     *
     * @param resource 资源路径，如 {@value #DEFAULT_RESOURCE}
     * @return 加载好的字体
     * @throws IllegalStateException 资源不存在时（消息里给出期望路径）
     */
    public static FontFile loadClasspath(String resource) {
        try (InputStream in = FontFile.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "字体资源不存在: " + resource
                                + "。它应当在 src/main/resources" + resource
                                + "（构建后会进 target/classes）——"
                                + "见 src/main/resources/fonts/README.md");
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("读取字体资源失败: " + resource, e);
        }
    }

    /**
     * 返回码点对应的字形索引。
     *
     * @param codepoint Unicode 码点
     * @return 字形索引；<strong>0 表示 .notdef</strong>（字体里没有这个码点），
     *         调用方应当照常画它（通常是个方框），不要静默跳过
     */
    public int glyphIndex(int codepoint) {
        ensureAlive();
        return STBTruetype.stbtt_FindGlyphIndex(info, codepoint);
    }

    /**
     * 返回"把字体缩放到给定像素高度"所需的缩放因子。
     *
     * <p>用法：{@code 像素值 = 字体单位值 * scaleForPixelHeight(像素高度)}。
     * 它与像素高度<strong>严格成正比</strong>——本期的字号缩放完全建立在这条性质上。
     *
     * @param pixelHeight 期望的像素高度（字号）
     * @return 缩放因子
     */
    public float scaleForPixelHeight(float pixelHeight) {
        ensureAlive();
        return STBTruetype.stbtt_ScaleForPixelHeight(info, pixelHeight);
    }

    /**
     * 返回字形的推进宽度，单位是<strong>字体单位</strong>。
     *
     * @param glyphIndex 字形索引
     * @return 推进宽度（字体单位）；先乘 {@link #scaleForPixelHeight(float)} 才是像素
     */
    public int advance(int glyphIndex) {
        hMetrics(glyphIndex);
        return hMetricsScratch[0];
    }

    /**
     * 返回字形的左旁距，单位是<strong>字体单位</strong>。
     *
     * @param glyphIndex 字形索引
     * @return 左旁距（字体单位）
     */
    public int glyphLeftSideBearing(int glyphIndex) {
        hMetrics(glyphIndex);
        return hMetricsScratch[1];
    }

    /**
     * 返回字形在字体坐标下的包围盒，写进 {@code out} 的 {@code [0..3]}。
     *
     * <p>顺序为 {@code x0, y0, x1, y1}，y 向下，因此 {@code y0} 通常是负数（基线之上）。
     * 没有轮廓的字形（空格、以及索引不在字体里的字形）四值全为 0。
     *
     * @param glyphIndex 字形索引
     * @param out        接收 4 个值的数组，长度至少为 4
     */
    public void glyphBox(int glyphIndex, int[] out) {
        ensureAlive();
        requireOut(out);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x0 = stack.mallocInt(1);
            IntBuffer y0 = stack.mallocInt(1);
            IntBuffer x1 = stack.mallocInt(1);
            IntBuffer y1 = stack.mallocInt(1);
            boolean hasBox = STBTruetype.stbtt_GetGlyphBox(info, glyphIndex, x0, y0, x1, y1);
            out[0] = hasBox ? x0.get(0) : 0;
            out[1] = hasBox ? y0.get(0) : 0;
            out[2] = hasBox ? x1.get(0) : 0;
            out[3] = hasBox ? y1.get(0) : 0;
        }
    }

    /**
     * 返回字形在<strong>给定缩放下的像素包围盒</strong>，写进 {@code out} 的 {@code [0..3]}。
     *
     * <p>顺序为 {@code x0, y0, x1, y1}，y 向下，{@code y0} 通常是负数。
     * 这一步同时完成了取整：stb 的 {@code stbtt_GetGlyphBitmapBox} 会把包围盒按
     * "覆盖所有被字形碰到的像素"取整，因此用它算出来的位图尺寸不会裁掉任何笔画。
     *
     * <p>没有轮廓的字形返回全 0（即宽度或高度为 0 → 空字形）。
     * 注意 {@code y1 - y0} 可能为负（退化字形），调用方必须先判空再算宽高。
     *
     * @param glyphIndex 字形索引
     * @param scale      缩放因子，见 {@link #scaleForPixelHeight(float)}
     * @param out        接收 4 个值的数组，长度至少为 4
     */
    public void glyphPixelBox(int glyphIndex, float scale, int[] out) {
        ensureAlive();
        requireOut(out);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x0 = stack.mallocInt(1);
            IntBuffer y0 = stack.mallocInt(1);
            IntBuffer x1 = stack.mallocInt(1);
            IntBuffer y1 = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphBitmapBox(info, glyphIndex, scale, scale, x0, y0, x1, y1);
            out[0] = x0.get(0);
            out[1] = y0.get(0);
            out[2] = x1.get(0);
            out[3] = y1.get(0);
        }
    }

    /**
     * 把字形光栅化成 8 位覆盖度位图，写进 {@code out}，按行存储。
     *
     * <p>覆盖度是 stb 的抗锯齿结果：0 = 完全在字形外，255 = 完全在字形内。
     * 之后要交给 {@link SdfGenerator} 转成距离场。
     *
     * @param glyphIndex 字形索引
     * @param scale      缩放因子，见 {@link #scaleForPixelHeight(float)}
     * @param width      位图宽度，来自 {@link #glyphPixelBox}
     * @param height     位图高度，来自 {@link #glyphPixelBox}
     * @param out        接收 {@code width * height} 个字节的数组，长度至少为这么多
     * @throws IllegalArgumentException 数组长度不足时
     */
    public void makeGlyphBitmap(int glyphIndex, float scale, int width, int height, byte[] out) {
        ensureAlive();
        int bytes = width * height;
        if (out.length < bytes) {
            throw new IllegalArgumentException(
                    "输出数组长度不足：需要 " + bytes + "，实际 " + out.length);
        }
        ensureRasterScratch(bytes);
        rasterScratch.clear();
        rasterScratch.limit(bytes);
        // stb 要的是本地内存，byte[] 不行，所以必须过一趟直接缓冲。
        // 缓冲复用、按需扩容，避免每来一个新字形就 memAlloc 一次。
        STBTruetype.stbtt_MakeGlyphBitmap(info, rasterScratch, width, height, width,
                scale, scale, glyphIndex);
        rasterScratch.position(0);
        rasterScratch.get(out, 0, bytes);
    }

    /** 释放字体字节、stb 结构体与光栅化缓冲。重复调用无副作用。 */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        // 先放 stb 结构体再放字体内存：stb 结构体里存着指向字体字节的裸指针，
        // 虽然 free 本身不会解引用它，但这个顺序读起来更安全。
        info.free();
        MemoryUtil.memFree(data);
        if (rasterScratch != null) {
            MemoryUtil.memFree(rasterScratch);
            rasterScratch = null;
        }
        disposed = true;
    }

    /**
     * 释放之后再使用就抛异常。
     *
     * <p>必须挡住：字体字节已经归还，再交给 stb 是 use-after-free，
     * 它不会报错，只会读到垃圾或者直接崩进程。
     */
    private void ensureAlive() {
        if (disposed) {
            throw new IllegalStateException(
                    "字体已释放（dispose()）：此时字体字节已归还，再调用 stb 就是 use-after-free");
        }
    }

    private void requireOut(int[] out) {
        if (out == null || out.length < 4) {
            throw new IllegalArgumentException("输出数组长度至少为 4");
        }
    }

    /** 查询字形的水平度量，结果写进 {@link #hMetricsScratch}。 */
    private void hMetrics(int glyphIndex) {
        ensureAlive();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer advanceWidth = stack.mallocInt(1);
            IntBuffer leftSideBearing = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphHMetrics(info, glyphIndex, advanceWidth, leftSideBearing);
            hMetricsScratch[0] = advanceWidth.get(0);
            hMetricsScratch[1] = leftSideBearing.get(0);
        }
    }

    /** 保证光栅化用的直接缓冲至少有 {@code bytes} 字节。 */
    private void ensureRasterScratch(int bytes) {
        if (rasterScratch == null || rasterScratch.capacity() < bytes) {
            if (rasterScratch != null) {
                MemoryUtil.memFree(rasterScratch);
            }
            rasterScratch = MemoryUtil.memAlloc(bytes);
        }
    }
}
```

- [ ] **Step 7: 跑测试确认通过**

Run: `mvn -o test -Dtest=FontFileTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 8: 跑全量测试确认没有破坏别的**

Run: `mvn -o test`
Expected: `Tests run: 192, Failures: 0, Errors: 0, Skipped: 2`（187 + 5）

- [ ] **Step 9: 变异验证**（每条做完立刻还原，共 4 条）

| 变异 | 应失败的测试 |
|------|--------------|
| 字体字节改用 `MemoryStack` 持有（`stack.malloc(bytes.length)`） | `stb原生库能在surefire环境加载并解析字体`（9.7 MB 远超默认 64 KB，直接炸） |
| `load` 里把 `stbtt_InitFont` 的返回值忽略（不抛异常） | 无（**如实记录"未能覆盖"**：本类没有"故意喂坏数据"的测试，因为 stb 对垃圾输入的行为是未定义的，写不了稳定断言） |
| 去掉 `ensureAlive()` 的检查 | `释放之后再使用会抛异常` |
| `dispose()` 不释放 `data`（只 `info.free()`） | **实测很可能存活**——内存泄漏拿不到可观测的行为差异。如实记录，**不要**为了让它失败去写一个读 `/proc` 的测试 |

**后两条不用勉强。** 本项目对变异表的要求是"把错误实现与正确实现都摆出来，
问这两个状态可不可区分"——不可区分就如实写下来，**不许**编一个看起来像覆盖的断言。
上面第 4 条若实测存活，正确结论是"内存泄漏在单测口径下不可观测，
它由代码评审与 `dispose()` 的一行代码保证"，而不是"这条可以删掉"。

- [ ] **Step 10: 提交**

```bash
git add src/main/resources/fonts/simhei.ttf \
        src/main/resources/fonts/README.md \
        src/main/java/com/bingbaihanji/xuan/text/FontFile.java \
        src/test/java/com/bingbaihanji/xuan/text/FontFileTest.java
git commit -F - <<'EOF'
feat(text): 字体入库 + FontFile（stb 的字体与度量封装）

字体选 simhei.ttf：它是 stb_truetype 最不容易出岔子的输入（纯静态 TrueType，
只有 glyf 表）。同机的 NotoSansSC-VF.ttf 是可变字体，stb 会忽略变体轴只渲染
默认实例——第一次实现时不该同时跟可变字体较劲。fonts/README.md 里写明了
来源、授权（微软/中易的专有字体，自用没问题、公开分发必须换）与换字体的方法。

FontFile 用 MemoryUtil.memAlloc 持有字体字节并在 dispose 释放：stb 的
stbtt_fontinfo 不复制字体数据，只记住地址，字节必须活到它最后一刻。
绝不能用 MemoryStack——默认 64 KB，字体 9.7 MB，压栈即炸。

释放后再调用一律抛异常：字体字节已归还，再交给 stb 是 use-after-free，
它不报错，只读到垃圾或直接崩进程。

5 条单测。先跑了 stb 本地库在 surefire 下的加载探针，结果记录在实施报告里。
变异验证：MemoryStack、ensureAlive、initFont 返回值、dispose 漏 memFree
（后两条中的内存泄漏一条在单测口径下不可观测，已如实记录）。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 5: GlyphRasterizer + FontGlyphSource

**Files:**
- Create: `src/main/java/com/bingbaihanji/xuan/text/GlyphRasterizer.java`
- Create: `src/main/java/com/bingbaihanji/xuan/text/FontGlyphSource.java`
- Test: `src/test/java/com/bingbaihanji/xuan/text/GlyphRasterizerTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/xuan/text/GlyphRasterizerTest.java`：

```java
package com.bingbaihanji.xuan.text;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GlyphRasterizer} 的单测：字号 → 覆盖度 → 距离场的完整链路。
 *
 * <p>重点在<strong>几何换算</strong>：包围盒、外扩、偏移。这些错了都不会报错，
 * 只会让字形画歪、被裁、或者跑到基线下面去。
 *
 * <p>与 {@code FontFileTest} 一样，本类会加载 stb 的本地库。
 */
class GlyphRasterizerTest {

    private static FontFile font;
    private static GlyphRasterizer rasterizer;

    @BeforeAll
    static void 准备() {
        font = FontFile.loadClasspath(FontFile.DEFAULT_RESOURCE);
        rasterizer = new GlyphRasterizer(font);
    }

    @AfterAll
    static void 收尾() {
        if (font != null) {
            font.dispose();
        }
    }

    /** 取字形在光栅化尺寸下的像素包围盒。 */
    private static int[] boxOf(int glyphIndex) {
        int[] box = new int[4];
        font.glyphPixelBox(glyphIndex, font.scaleForPixelHeight(GlyphRasterizer.EM_SIZE), box);
        return box;
    }

    @Test
    void 位图尺寸等于包围盒加两倍spread() {
        int glyph = font.glyphIndex('国');
        int[] box = boxOf(glyph);
        int expectedWidth = box[2] - box[0] + 2 * SdfGenerator.SPREAD;
        int expectedHeight = box[3] - box[1] + 2 * SdfGenerator.SPREAD;

        GlyphBitmap bitmap = rasterizer.rasterize(glyph);

        assertEquals(expectedWidth, bitmap.width(), "宽度少了外扩的那一圈，笔画会被裁掉");
        assertEquals(expectedHeight, bitmap.height(), "高度少了外扩的那一圈，笔画会被裁掉");
        assertEquals(bitmap.width() * bitmap.height(), bitmap.pixels().length);
    }

    @Test
    void 偏移量减去了spread且纵偏移为负() {
        int glyph = font.glyphIndex('国');
        int[] box = boxOf(glyph);

        GlyphBitmap bitmap = rasterizer.rasterize(glyph);

        // 距离场在位图四周各外扩 SPREAD 像素，所以"位图左上角"相对包围盒向左上移动了
        assertEquals(box[0] - SdfGenerator.SPREAD, bitmap.offsetX());
        assertEquals(box[1] - SdfGenerator.SPREAD, bitmap.offsetY());
        assertTrue(bitmap.offsetY() < 0,
                "汉字画在基线之上，而本项目的 y 向下——offsetY 必须是负数");
    }

    @Test
    void 空字形返回零尺寸位图() {
        // 空格：有推进宽度，但没有轮廓。这是正常情况而不是错误。
        int space = font.glyphIndex(' ');
        assertNotEquals(0, space,
                "simhei 里空格没有字形（回落成了 .notdef）——那说明这条测试选错了输入，"
                        + "换成 U+3000 全角空格再跑，并把结论报上来");

        GlyphBitmap bitmap = rasterizer.rasterize(space);

        assertEquals(0, bitmap.width());
        assertEquals(0, bitmap.height());
        assertEquals(0, bitmap.pixels().length);
        assertTrue(bitmap.advance() > 0f, "空格仍然要让笔前进");
    }

    @Test
    void 汉字的推进宽度约等于一个字号() {
        GlyphBitmap bitmap = rasterizer.rasterize(font.glyphIndex('国'));

        // 全角汉字的推进宽度就是 1 em，而 em 高度被光栅化成了 EM_SIZE 像素。
        // 容差取 10%：不同字体的全角宽度可能略有出入，但绝不可能差一个数量级。
        //
        // 漏乘 scale 时得到的是**字体单位**下的推进宽度，对黑体就是 256
        // （实测 scaleForPixelHeight(48) = 0.1875 = 48/256，即 unitsPerEm = 256；
        // 见 Task 4 的探针结论）。这条注释一度写成"约 1000"，那是想当然的常见值，
        // 与实测不符——**写这类具体数字前先查，不要按印象填**。
        assertEquals(GlyphRasterizer.EM_SIZE, bitmap.advance(), GlyphRasterizer.EM_SIZE * 0.1f,
                "推进宽度约为一个字号——漏乘 scale 会让它变成字体单位（黑体是 256）");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=GlyphRasterizerTest`
Expected: 编译失败 — `找不到符号: 类 GlyphRasterizer`

- [ ] **Step 3: 写 GlyphRasterizer**

创建 `src/main/java/com/bingbaihanji/xuan/text/GlyphRasterizer.java`：

```java
package com.bingbaihanji.xuan.text;

/**
 * 把字形光栅化成覆盖度位图，再交给 {@link SdfGenerator} 转成距离场。
 *
 * <h2>两个常量一起决定单字形的最大边长</h2>
 * <p>{@link #EM_SIZE}（em 高度，像素）与 {@link SdfGenerator#SPREAD}（外扩像素数）
 * 决定单字形位图的最大边长 = {@code EM_SIZE + 2 * SPREAD}（默认 48 + 16 = 64）。
 * <strong>{@code GlyphAtlas.SIZE} 必须不小于这个值</strong>，否则一个字形的槽位
 * 会放不进一行，货架分配会退化。
 *
 * <p>本期只有<strong>一个</strong> em 尺寸桶（{@link #EM_SIZE}）：字形在此尺寸下光栅化一次，
 * 之后靠 SDF 在任意字号下重新求出锐利边缘。这是 SDF 相对位图拉伸的全部价值所在，
 * 也是驱动场景（图表刻度、图例）能只付一次生成代价的原因。
 *
 * <h2>线程</h2>
 * <p>非线程安全。与 {@link GlyphAtlas} 一样，只在 GL 线程上使用。
 */
public final class GlyphRasterizer {

    /**
     * 光栅化时的 em 高度（像素）。
     *
     * <p>取 48 是质量与代价的折中：更小会丢掉小字号的笔画细节，更大则位图变宽、
     * 图集容量下降，而 SDF 的质量在大尺寸下并不会有明显提升。
     */
    public static final int EM_SIZE = 48;

    /** 字体。 */
    private final FontFile font;

    /** 光栅化时的 em 高度（像素）。 */
    private final int pixelHeight;

    /** 包围盒查询的复用暂存。 */
    private final int[] boxScratch = new int[4];

    /** 用默认的 {@link #EM_SIZE} 构造。 */
    public GlyphRasterizer(FontFile font) {
        this(font, EM_SIZE);
    }

    /**
     * 指定 em 高度构造。
     *
     * @param font        字体
     * @param pixelHeight 光栅化时的 em 高度（像素），必须为正
     * @throws IllegalArgumentException 参数非法时
     */
    public GlyphRasterizer(FontFile font, int pixelHeight) {
        if (font == null) {
            throw new IllegalArgumentException("font 不能为 null");
        }
        if (pixelHeight <= 0) {
            throw new IllegalArgumentException("pixelHeight 必须为正，实际为 " + pixelHeight);
        }
        this.font = font;
        this.pixelHeight = pixelHeight;
    }

    /**
     * 返回光栅化时使用的 em 高度（像素）。
     *
     * <p>{@code Gc} 用它把字号换算成"相对本尺寸的缩放"：
     * {@code scale = fontSize / rasterizer.pixelHeight()}。
     *
     * @return em 高度（像素）
     */
    public int pixelHeight() {
        return pixelHeight;
    }

    /**
     * 光栅化一个字形。
     *
     * @param glyphIndex 字形索引（来自 {@link FontFile#glyphIndex(int)}）
     * @return 距离场位图；字形没有轮廓（空格等）时返回宽高为 0 的空位图
     */
    public GlyphBitmap rasterize(int glyphIndex) {
        float scale = font.scaleForPixelHeight(pixelHeight);
        font.glyphPixelBox(glyphIndex, scale, boxScratch);
        int x0 = boxScratch[0];
        int y0 = boxScratch[1];
        int x1 = boxScratch[2];
        int y1 = boxScratch[3];

        // 推进宽度也在这里算：它是 em 像素，与字号无关（字号缩放在发射顶点时才乘）。
        // 放在 GlyphSlot 里预乘字号是错的——同一个槽位会被不同字号复用。
        float advance = font.advance(glyphIndex) * scale;

        int width = x1 - x0;
        int height = y1 - y0;
        if (width <= 0 || height <= 0) {
            // 空字形（空格、以及度量上退化的字形）：只让笔前进，不产生位图。
            // 这是正常情况而不是错误。
            return new GlyphBitmap(0, 0, 0, 0, advance, new byte[0]);
        }

        byte[] coverage = new byte[width * height];
        font.makeGlyphBitmap(glyphIndex, scale, width, height, coverage);
        byte[] sdf = SdfGenerator.generate(coverage, width, height);

        int paddedWidth = width + 2 * SdfGenerator.SPREAD;
        int paddedHeight = height + 2 * SdfGenerator.SPREAD;
        // 距离场在位图四周各外扩了 SPREAD 像素，所以相对基线的偏移要各减去 SPREAD。
        // 纵偏移减去之后只会更负——字形在基线之上，而本项目的 y 向下。
        // 漏减的表现是字形整体往右下挪 8 个 em 像素，且边缘被裁——"看起来只是位置偏了"。
        return new GlyphBitmap(paddedWidth, paddedHeight,
                x0 - SdfGenerator.SPREAD,
                y0 - SdfGenerator.SPREAD,
                advance, sdf);
    }
}
```

- [ ] **Step 4: 写 FontGlyphSource**

创建 `src/main/java/com/bingbaihanji/xuan/text/FontGlyphSource.java`：

```java
package com.bingbaihanji.xuan.text;

/**
 * 把 {@link FontFile} 与 {@link GlyphRasterizer} 串成一个 {@link GlyphSource}，
 * 外加两个 {@code Gc} 直接要用的度量入口。
 *
 * <p><strong>key 就是字形索引。</strong>本期只有一个 em 尺寸桶
 * （{@link GlyphRasterizer#EM_SIZE}），因此图集按字形缓存即可；
 * 将来若加"小字号走独立位图桶"，key 才需要变成 (字形, 桶)。
 *
 * <p>为什么要有这个类、而不是让 {@code Gc} 自己把两者串起来：
 * <ul>
 *   <li>让 {@code Gc} 只认识一个对象，而不是三个；</li>
 *   <li>{@link #advancePixels} 走的是<strong>字体度量</strong>而不是图集，
 *       因此 {@code measureText} 不必为了量一个宽度而生成字形——
 *       这是"测量不该有副作用"这条老规矩，也是 {@code measureText} 能用于布局的原因。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * <p>非线程安全，只在 GL 线程上使用。
 */
public final class FontGlyphSource implements GlyphSource {

    /** 字体。 */
    private final FontFile font;

    /** 光栅化器。 */
    private final GlyphRasterizer rasterizer;

    /**
     * 构造。
     *
     * @param font       字体
     * @param rasterizer 光栅化器，必须与 {@code font} 是同一份字体
     * @throws IllegalArgumentException 任一参数为 {@code null} 时
     */
    public FontGlyphSource(FontFile font, GlyphRasterizer rasterizer) {
        if (font == null) {
            throw new IllegalArgumentException("font 不能为 null");
        }
        if (rasterizer == null) {
            throw new IllegalArgumentException("rasterizer 不能为 null");
        }
        this.font = font;
        this.rasterizer = rasterizer;
    }

    @Override
    public GlyphBitmap pixelsFor(int key) {
        return rasterizer.rasterize(key);
    }

    /**
     * 返回字体本身，供调用方查字形索引。
     *
     * @return 字体
     */
    public FontFile font() {
        return font;
    }

    /**
     * 返回码点对应的字形索引。
     *
     * @param codepoint Unicode 码点
     * @return 字形索引；0 表示 .notdef（字体里没有这个码点），照常画它，不要跳过
     */
    public int glyphIndex(int codepoint) {
        return font.glyphIndex(codepoint);
    }

    /**
     * 返回某字号下字形的推进宽度（像素）。
     *
     * <p>直接从字体度量算，<strong>不生成字形、不碰图集</strong>。
     *
     * @param glyphIndex 字形索引
     * @param fontSize   字号（像素）
     * @return 推进宽度（像素）
     */
    public float advancePixels(int glyphIndex, float fontSize) {
        return font.advance(glyphIndex) * font.scaleForPixelHeight(fontSize);
    }

    /**
     * 返回光栅化时使用的 em 高度（像素）。
     *
     * <p>发射顶点时用它把字号换算成缩放：{@code scale = fontSize / rasterizer.pixelHeight()}。
     *
     * @return em 高度（像素）
     */
    public int pixelHeight() {
        return rasterizer.pixelHeight();
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -o test -Dtest=GlyphRasterizerTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 6: 跑全量测试确认没有破坏别的**

Run: `mvn -o test`
Expected: `Tests run: 196, Failures: 0, Errors: 0, Skipped: 2`（192 + 4）

- [ ] **Step 7: 变异验证**（每条做完立刻还原，共 4 条）

| 变异 | 应失败的测试 |
|------|--------------|
| 偏移量漏减 SPREAD（`x0 - SPREAD` 改回 `x0`；`y0 - SPREAD` 改回 `y0`） | `偏移量减去了spread且纵偏移为负` |
| 宽高忘加 `2 * SPREAD`（改回 `width` / `height`） | **先由 `GlyphBitmap` 的紧凑构造器守卫报错**（像素数量与尺寸不符），断言还没机会跑；把守卫绕过之后才轮到 `位图尺寸等于包围盒加两倍spread`。**归属与下表不同，实测已确认** |
| `advance` 漏乘 `scale`（`font.advance(glyphIndex)` 不乘） | `汉字的推进宽度约等于一个字号` |
| 空字形不提前返回，照常走 `SdfGenerator.generate`（位图尺寸为 0，会生成 16×16 的场） | `空字形返回零尺寸位图` |

**派单时提过的第三条「用切比雪夫距离」在这里不适用**——那是 `SdfGenerator` 内部
的实现选择，已经在 Task 1 的变异表里覆盖过了（`对角方向用的是欧氏距离而不是切比雪夫`），
`GlyphRasterizer` 这一层只是调用它。**重复列一条"看起来覆盖面更大"的变异没有意义**，
所以换成了 `advance` 漏乘 scale。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/bingbaihanji/xuan/text/GlyphRasterizer.java \
        src/main/java/com/bingbaihanji/xuan/text/FontGlyphSource.java \
        src/test/java/com/bingbaihanji/xuan/text/GlyphRasterizerTest.java
git commit -F - <<'EOF'
feat(text): 字形光栅化器与它到 GlyphSource 的接线

GlyphRasterizer：字形索引 → 包围盒 → 覆盖度 → 距离场，外加四周各 SPREAD
像素的外扩。两处几何换算错了都不报错：
- 偏移漏减 SPREAD → 字形整体往右下挪 8 个 em 像素，且边缘被裁
- 宽高忘加 2*SPREAD → 笔画被裁掉，看起来只是"字有点瘦"

advance 在这里算成 em 像素而不是预乘字号：同一个槽位会被不同字号复用，
按字号预乘等于把图集缓存键变成 (字形, 字号)，那是多桶方案才该付的代价。

FontGlyphSource 把 FontFile + GlyphRasterizer 串成图集认识的 GlyphSource，
另外给 Gc 一个走字体度量的 advancePixels——measureText 因此不生成字形、
不碰图集，"测量无副作用"这条才成立。

4 条单测 + 4 条变异验证（漏减 SPREAD、忘加 2*SPREAD、advance 漏乘 scale、
空字形不提前返回）。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 6: TextLayout —— 槽位序列 → 四边形顶点

**Files:**
- Create: `src/main/java/com/bingbaihanji/xuan/text/TextLayout.java`
- Test: `src/test/java/com/bingbaihanji/xuan/text/TextLayoutTest.java`

**这是文本子系统里第二块纯算术**（第一块是 `SdfGenerator`）：输入全是普通数值，
输出是 `VertexWriter` 里的顶点，因此可以用真的 `VertexWriter` 与真的 `ViewTransform`
直接单测——**不需要 GL、不需要字体、不需要 stb**。

**变换怎么进来（已对着代码核实，不是猜的）**：`Gc.emitTriangles` 现在走的是
`state.transformX(wx, wy)` / `state.transformY(wx, wy)`（`Gc.kt:846`），
`state` 是 `ViewTransform`（`Gc.kt:56`）。
`ViewTransform` 虽然标了 Kotlin 的 `internal`，但**它的成员在字节码里是公开且没有被
改名修饰的**（`javap -p target/classes/.../ViewTransform.class` 显示
`public final float transformX(float, float)`），所以 Java 侧的 `TextLayout`
可以直接接收并使用它。**不要**自己写一遍 NDC 换算：那会变成第二份像素→NDC 的实现，
两边一旦不同步，症状是"文字与图形错位"而两边各自都"是对的"。

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/bingbaihanji/xuan/text/TextLayoutTest.java`：

```java
package com.bingbaihanji.xuan.text;

import com.bingbaihanji.xuan.renderer.VertexFormat;
import com.bingbaihanji.xuan.renderer.VertexWriter;
import com.bingbaihanji.xuan.renderer.ViewTransform;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TextLayout} 的单测：**纯算术**，用真的 {@link VertexWriter} 与真的
 * {@link ViewTransform}，不需要 GL 上下文、不需要字体、不需要 stb。
 *
 * <p>断言落在三样东西上：顶点坐标（NDC）、uv、返回的推进宽度。
 * 这三样任何一样错了都不会报错，只会让文字画歪或者字距不对。
 */
class TextLayoutTest {

    private static final int W = 800;
    private static final int H = 600;

    /** 测试用的基础矩阵：用户坐标 1:1 映射到设备像素，与管线一致。 */
    private static ViewTransform transform() {
        ViewTransform t = new ViewTransform();
        t.beginFrame(W, H);
        return t;
    }

    /** 一个非空槽位：尺寸 8×10、偏移 (-1,-9)、推进 12。 */
    private static GlyphSlot slot() {
        return new GlyphSlot(0.1f, 0.2f, 0.15f, 0.25f, -1, -9, 8, 10, 12f);
    }

    /** 一个空槽位（宽高为 0），推进同样是 12。 */
    private static GlyphSlot emptySlot() {
        return new GlyphSlot(0f, 0f, 0f, 0f, 0, 0, 0, 0, 12f);
    }

    private static float ndcX(float userX) {
        return 2f * userX / W - 1f;
    }

    private static float ndcY(float userY) {
        return 1f - 2f * userY / H;
    }

    private static float userY(float ndcY) {
        return (1f - ndcY) * H / 2f;
    }

    private static void assertVertex(ByteBuffer bytes, int index, float userX, float userY) {
        int base = index * VertexFormat.STRIDE_BYTES;
        assertEquals(ndcX(userX), bytes.getFloat(base), 1e-4f, "顶点 " + index + " 的 x");
        assertEquals(ndcY(userY), bytes.getFloat(base + 4), 1e-4f, "顶点 " + index + " 的 y");
    }

    private static VertexWriter writer() {
        VertexWriter writer = new VertexWriter(64);
        writer.setState(1, 0, 0, W, H);
        return writer;
    }

    @Test
    void 顶点数等于非空字形数乘以六() {
        VertexWriter writer = writer();
        List<GlyphSlot> slots = List.of(slot(), slot(), slot());

        float advance = TextLayout.layout(slots, 100f, 200f, 1f, 0xFFFFFFFF, 0, writer, transform());

        assertEquals(18, writer.vertexCount(), "每个字形两个三角形、六个顶点");
        assertEquals(36f, advance, 1e-3f);
    }

    @Test
    void 四边形坐标符合偏移与缩放() {
        VertexWriter writer = writer();
        float scale = 2f;
        TextLayout.layout(List.of(slot()), 100f, 200f, scale, 0xFFFFFFFF, 0, writer, transform());

        ByteBuffer bytes = writer.buffer();
        // 缩放 2：左 = 100 + (-1)*2 = 98，上 = 200 + (-9)*2 = 182，
        //         右 = 98 + 8*2 = 114，下 = 182 + 10*2 = 202。
        // 四个角点的顺序必须是 左上、右上、右下、左下，第二个三角形共用 0-2 对角线。
        assertVertex(bytes, 0, 98f, 182f);     // 左上
        assertVertex(bytes, 1, 114f, 182f);    // 右上
        assertVertex(bytes, 2, 114f, 202f);    // 右下
        assertVertex(bytes, 3, 98f, 182f);     // 又是左上：第二个三角形的起点
        assertVertex(bytes, 4, 114f, 202f);    // 右下
        assertVertex(bytes, 5, 98f, 202f);     // 左下
    }

    @Test
    void uv取自槽位() {
        VertexWriter writer = writer();
        TextLayout.layout(List.of(slot()), 100f, 200f, 1f, 0xFFFFFFFF, 0, writer, transform());

        ByteBuffer bytes = writer.buffer();
        // 顶点布局：x(0) y(4) u(8) v(12)
        // quad 的 uv 约定：角点 0/3 取 (u0,v0)，角点 1/4 取 (u1,v1) 的一侧，角点 5 取 (u0,v1)
        assertEquals(0.1f, bytes.getFloat(0 * VertexFormat.STRIDE_BYTES + 8), 1e-6f);
        assertEquals(0.2f, bytes.getFloat(0 * VertexFormat.STRIDE_BYTES + 12), 1e-6f);
        assertEquals(0.15f, bytes.getFloat(1 * VertexFormat.STRIDE_BYTES + 8), 1e-6f);
        assertEquals(0.2f, bytes.getFloat(1 * VertexFormat.STRIDE_BYTES + 12), 1e-6f);
        assertEquals(0.15f, bytes.getFloat(4 * VertexFormat.STRIDE_BYTES + 8), 1e-6f);
        assertEquals(0.25f, bytes.getFloat(4 * VertexFormat.STRIDE_BYTES + 12), 1e-6f);
        assertEquals(0.1f, bytes.getFloat(5 * VertexFormat.STRIDE_BYTES + 8), 1e-6f);
        assertEquals(0.25f, bytes.getFloat(5 * VertexFormat.STRIDE_BYTES + 12), 1e-6f);
    }

    @Test
    void 推进总量等于各字形推进之和乘以缩放() {
        VertexWriter writer = writer();
        List<GlyphSlot> slots = List.of(slot(), slot(), slot());

        float advance = TextLayout.layout(slots, 100f, 200f, 2.5f, 0xFFFFFFFF, 0, writer, transform());

        // 每个槽位推进 12，三个共 36；缩放 2.5 → 90
        assertEquals(90f, advance, 1e-3f);
        assertNotEquals(36f, advance,
                "用了未缩放的推进值——字号越大字距越紧，而且每个字都会往左偏，越靠后越偏");
    }

    @Test
    void 空槽位只推进不发顶点() {
        VertexWriter writer = writer();
        List<GlyphSlot> slots = List.of(emptySlot(), slot(), emptySlot());

        float advance = TextLayout.layout(slots, 100f, 200f, 1f, 0xFFFFFFFF, 0, writer, transform());

        assertEquals(6, writer.vertexCount(), "只有中间那个非空字形该发顶点");
        assertEquals(36f, advance, 1e-3f, "三个槽位都在推进，空槽位也不例外");
    }

    @Test
    void 起点是基线而不是文本框左上角() {
        VertexWriter writer = writer();
        TextLayout.layout(List.of(slot()), 100f, 200f, 1f, 0xFFFFFFFF, 0, writer, transform());

        ByteBuffer bytes = writer.buffer();
        float top = userY(bytes.getFloat(4));
        float bottom = userY(bytes.getFloat(4 * VertexFormat.STRIDE_BYTES + 4));

        // 缩放 1：上 = 200 + (-9) = 191，下 = 191 + 10 = 201
        assertEquals(191f, top, 1e-3f);
        assertEquals(201f, bottom, 1e-3f);
        assertTrue(top < 200f,
                "上沿在基线之上。若把 (x,y) 当成文本框左上角，上沿会正好等于 200——"
                        + "而画面看起来「只是位置偏了一点」");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=TextLayoutTest`
Expected: 编译失败 — `找不到符号: 类 TextLayout`

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/bingbaihanji/xuan/text/TextLayout.java`：

```java
package com.bingbaihanji.xuan.text;

import com.bingbaihanji.xuan.renderer.VertexWriter;
import com.bingbaihanji.xuan.renderer.ViewTransform;

import java.util.List;

/**
 * 把一串字形槽位摆成四边形顶点，追加进 {@link VertexWriter}。
 *
 * <h2>纯算术</h2>
 * <p>本类<strong>不碰 GL、不碰 stb、不碰图集</strong>：输入是一串普通数值
 * （{@link GlyphSlot}）、起点、缩放、颜色、拾取 ID 与变换，输出是顶点。
 * 因此可以用真的 {@code VertexWriter} 与真的 {@code ViewTransform} 直接单测。
 *
 * <h2>{@code (x, y)} 是基线起点，不是文本框左上角</h2>
 * <p>{@code y} 是文字<strong>基线</strong>所在的像素行，{@code x} 是第一个字形的笔位置。
 * 选基线而不是左上角，是因为只有基线是排版的稳定参照——图表的刻度文字要沿轴线对齐时，
 * 基线对齐才是想要的；而"左上角对齐"会让不同高度的字符视觉上跳来跳去。
 * <strong>这条是调用方最容易猜错、且猜错后"看起来只是位置偏了一点"的那类约定。</strong>
 *
 * <h2>空槽位</h2>
 * <p>宽或高为 0 的槽位（空格等）<strong>只推进笔，不发顶点</strong>。
 * 这是正常情况而不是错误。
 *
 * <h2>缩放</h2>
 * <p>槽位里的偏移、尺寸、推进全部是 em 尺寸下的像素，<strong>不预乘字号缩放</strong>
 * （见 {@link GlyphSlot}）。本方法在发射时把它们统一乘以 {@code scale}。
 * 于是同一个字形槽位可以被任意字号复用，图集只需按字形缓存一次。
 *
 * <h2>关于顶点绕向</h2>
 * <p>四个角点按左上、右上、右下、左下给出，两个三角形共用 0-2 对角线——
 * 这与 {@link VertexWriter#quad} 的约定一致，本方法直接复用它而不是自己写六个顶点，
 * 免得出现第二份"四边形怎么拆成三角形"的实现。
 *
 * <p>顺带记一笔：本项目<strong>没有开启背面剔除</strong>（全工程没有
 * {@code GL_CULL_FACE}），所以绕向目前不影响可见性。真正会出错的是把四个角点的
 * 顺序打乱——那会让两个三角形交叉成一个蝴蝶结，四个角还在，中间却缺一块。
 */
public final class TextLayout {

    private TextLayout() {
    }

    /**
     * 把槽位序列发射成四边形顶点。
     *
     * <p><strong>前置条件</strong>：调用方必须已经对 {@code writer} 调用过
     * {@code setState}（纹理设成图集、材质设成 SDF 文本），并且已经检查过
     * {@link VertexWriter#isFlushRequested()}。本方法只管写顶点，不管 GL 状态。
     *
     * @param slots              字形槽位，按绘制顺序
     * @param x                  起点 x（用户坐标）：第一个字形的笔位置
     * @param y                  起点 y（用户坐标）：<strong>基线</strong>所在的像素行
     * @param scale              字号缩放因子，{@code fontSize / GlyphRasterizer.EM_SIZE}
     * @param premultipliedRgba  预乘 alpha 后的颜色
     * @param pickId             拾取 ID，0 表示不参与拾取
     * @param writer             顶点写入器
     * @param transform          当前变换（含像素→NDC 的基础矩阵）
     * @return 总推进宽度（<strong>已乘缩放</strong>），即笔最终走到 {@code x + 返回值}
     */
    public static float layout(List<GlyphSlot> slots, float x, float y, float scale,
                               int premultipliedRgba, int pickId,
                               VertexWriter writer, ViewTransform transform) {
        float penX = x;
        for (int i = 0; i < slots.size(); i++) {
            GlyphSlot slot = slots.get(i);
            if (!slot.isEmpty()) {
                // 位图矩形（用户坐标，y 向下）。offsetY 通常是负数：字形在基线之上。
                float left = penX + slot.offsetX() * scale;
                float top = y + slot.offsetY() * scale;
                float right = left + slot.width() * scale;
                float bottom = top + slot.height() * scale;

                // 四个角各自过一遍变换：仿射变换下矩形变成平行四边形，
                // 而 writer.quad 收的本来就是任意四角，所以旋转/斜切也成立。
                float nx0 = transform.transformX(left, top);
                float ny0 = transform.transformY(left, top);
                float nx1 = transform.transformX(right, top);
                float ny1 = transform.transformY(right, top);
                float nx2 = transform.transformX(right, bottom);
                float ny2 = transform.transformY(right, bottom);
                float nx3 = transform.transformX(left, bottom);
                float ny3 = transform.transformY(left, bottom);

                writer.quad(nx0, ny0, nx1, ny1, nx2, ny2, nx3, ny3,
                        slot.u0(), slot.v0(), slot.u1(), slot.v1(),
                        premultipliedRgba, pickId);
            }
            // 空槽位也推进——空格的存在感就体现在这里。
            penX += slot.advance() * scale;
        }
        return penX - x;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o test -Dtest=TextLayoutTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 跑全量测试确认没有破坏别的**

Run: `mvn -o test`
Expected: `Tests run: 202, Failures: 0, Errors: 0, Skipped: 2`（196 + 6）

- [ ] **Step 6: 变异验证**（每条做完立刻还原，共 5 条）

| 变异 | 应失败的测试 |
|------|--------------|
| 缩放漏乘（`slot.offsetX() * scale` 改成 `slot.offsetX()`，`slot.width() * scale` 改成 `slot.width()`） | `四边形坐标符合偏移与缩放` |
| 推进用未缩放值（`penX += slot.advance()`） | `推进总量等于各字形推进之和乘以缩放` |
| 空槽位仍然发顶点（删掉 `if (!slot.isEmpty())` 判断） | `空槽位只推进不发顶点` |
| 角点顺序打乱（把四个角点给成 左上、左下、右下、右上） | `四边形坐标符合偏移与缩放` |
| 把 `(x, y)` 当成文本框左上角（`top` 用 `y` 而不是 `y + slot.offsetY() * scale`） | `起点是基线而不是文本框左上角` |

比派单时列的 4 条多一条：最后那条"基线语义"是 Task 6 最该被钉死的约定
（KDoc 里专门写了一段），没有道理不给它一个变异验证。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/bingbaihanji/xuan/text/TextLayout.java \
        src/test/java/com/bingbaihanji/xuan/text/TextLayoutTest.java
git commit -F - <<'EOF'
feat(text): TextLayout —— 槽位序列摆成四边形顶点

纯算术，不碰 GL、不碰 stb、不碰图集：输入是一串槽位 + 起点 + 缩放 + 颜色 +
拾取 ID + 变换，输出是 VertexWriter 里的顶点。因此可以用真的 VertexWriter
与真的 ViewTransform 直接单测（6 条）。

(x, y) 是基线起点而不是文本框左上角，这条写进了 KDoc 也写进了测试：
只有基线是排版的稳定参照，刻度文字沿轴线对齐要靠它；当成左上角的话画面
"只是位置偏了一点"，最难查。

槽位里的偏移/尺寸/推进全是 em 像素，不预乘字号——发射时才乘 scale，
这样一个槽位能被任意字号复用，图集只需按字形缓存一次。

四个角点直接交给 VertexWriter.quad，不自己写六个顶点：免得出现第二份
"四边形怎么拆成三角形"的实现。顺带记一笔：本项目没开背面剔除，绕向目前
不影响可见性，真正会出错的是角点顺序打乱（两个三角形交叉成蝴蝶结）。

5 条变异验证：缩放漏乘、推进未缩放、空槽位发顶点、角点顺序、基线语义。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 7: 材质选择位 + SDF 着色器

**这是本期唯一改动现有管线契约的地方**：`DrawCommand` 多一个组件、`VertexWriter.setState`
多一个参数、合批判据多一项。三处必须一起改——漏掉任何一处都不会报错，
只会让文本被当成纯色画（采样到 1×1 白色纹理），表现是"文字整片糊成方块"。

**Files:**
- Create: `src/main/java/com/bingbaihanji/xuan/renderer/Material.java`
- Modify: `src/main/java/com/bingbaihanji/xuan/renderer/DrawCommand.java`
- Modify: `src/main/java/com/bingbaihanji/xuan/renderer/VertexWriter.java`
- Modify: `src/main/java/com/bingbaihanji/xuan/renderer/RenderBatch.java`
- Modify: `src/test/java/com/bingbaihanji/xuan/renderer/VertexFormatTest.java`（`new DrawCommand(...)` 的构造调用要跟着改）
- Test: `src/test/java/com/bingbaihanji/xuan/renderer/VertexWriterTest.java`（末尾追加 3 条）

- [ ] **Step 1: 加 Material 枚举**

创建 `src/main/java/com/bingbaihanji/xuan/renderer/Material.java`：

```java
package com.bingbaihanji.xuan.renderer;

/**
 * 绘制命令使用的材质，决定 {@link RenderBatch} 用哪个片段着色器。
 *
 * <p>它是<strong>合批判据的一部分</strong>：只有相邻且材质相同（以及纹理、裁剪都相同）
 * 的绘制才会合并成一条命令。漏判的表现是文本被当成纯色画——采样到 1×1 白色纹理，
 * 整片文字糊成方块，而且不报错。
 *
 * <p>为什么是枚举而不是布尔：材质将来还会长（渐变 LUT、描边填充的覆盖度图、
 * 图像），多一个枚举常量比多一个布尔字段清楚得多。
 */
public enum Material {

    /**
     * 纯色 / 普通纹理：采样结果与顶点色相乘。
     *
     * <p>纯色绘制时绑定的是 1×1 白色纹理，于是恰好退化成顶点色本身。
     */
    COLOR,

    /**
     * SDF 文本：把图集的红通道当有符号距离用。
     *
     * <p>0 = 远在字形外，128 = 边界，255 = 远在字形内。片段着色器用屏幕空间的
     * 导数（{@code fwidth}）把边缘重新求一遍，因此同一个字形在任意字号下都清晰。
     */
    SDF_TEXT
}
```

- [ ] **Step 2: DrawCommand 加材质组件**

把 `DrawCommand.java` 换成：

```java
package com.bingbaihanji.xuan.renderer;

/**
 * 单条绘制命令，描述一段连续顶点及其所需的 GL 状态。
 * <p>
 * 命令按提交顺序执行，顺序即 2D 的 z 序，不可重排。
 *
 * @param textureId    要绑定到 0 号纹理单元的纹理 ID
 * @param material     用哪个片段着色器，见 {@link Material}
 * @param firstVertex  起始顶点索引
 * @param vertexCount  顶点数量
 * @param scissorX     裁剪矩形左边缘 x（帧缓冲像素坐标）
 * @param scissorY     裁剪矩形<strong>上边缘</strong> y（y 向下，与 Gc 的用户坐标一致）
 * @param scissorWidth 裁剪矩形宽度
 * @param scissorHeight 裁剪矩形高度
 *
 * <p><strong>注意 y 方向</strong>：这里的 {@code scissorY} 是矩形<strong>上边缘</strong>，
 * 因为 {@code Gc} 使用"原点左上、y 向下"的像素坐标。而 {@code glScissor} 的原点在帧缓冲
 * <strong>左下角</strong>、y 向上，因此 {@code RenderBatch} 在提交时必须换算：
 * {@code glY = viewportHeight - scissorY - scissorHeight}。两处约定不同，改动任一侧都要同步另一侧。
 *
 * <p><strong>注意 {@code material} 的位置</strong>：它紧跟 {@code textureId}，
 * 与 {@code VertexWriter} 里"状态变了就切命令"的判据顺序一致。
 * 加组件会让所有构造点失效——编译器会全部指出来，这是好事，别绕过它。
 */
public record DrawCommand(int textureId, Material material, int firstVertex, int vertexCount,
                          int scissorX, int scissorY, int scissorWidth, int scissorHeight) {
}
```

- [ ] **Step 3: VertexWriter 加材质参数**

在 `VertexWriter.java` 里改三处。

**3a. 加字段**（放在 `textureId` 字段之前）：

```java
    /** 当前状态的材质。 */
    private Material material = Material.COLOR;
```

**3b. 替换 `setState` 方法**（原来的五参数版本整段换成下面这两个）：

```java
    /**
     * 设置当前状态为纯色材质。等价于
     * {@code setState(Material.COLOR, textureId, ...)}。
     *
     * <p>保留这个重载是为了让纯色绘制的调用点不必每处都写一遍
     * {@code Material.COLOR}——它同时把"纯色绘制用 COLOR"这条约定固定在一个地方，
     * 而不是散落在几十个调用点上。
     *
     * @param textureId     纹理 ID
     * @param scissorX      裁剪矩形左边缘 x
     * @param scissorY      裁剪矩形上边缘 y（y 向下；glScissor 的 y 换算见 {@link DrawCommand}）
     * @param scissorWidth  裁剪矩形宽度
     * @param scissorHeight 裁剪矩形高度
     */
    public void setState(int textureId, int scissorX, int scissorY,
                         int scissorWidth, int scissorHeight) {
        setState(Material.COLOR, textureId, scissorX, scissorY, scissorWidth, scissorHeight);
    }

    /**
     * 设置当前状态。与上一条命令状态不同时会结束当前命令。
     *
     * <p><strong>材质是合批判据的一部分</strong>：只有相邻且材质、纹理、裁剪都相同的
     * 绘制才会合并。漏判的表现是文本被当成纯色画（采样到 1×1 白色纹理，
     * 整片文字糊成方块），而且不报错。
     *
     * @param material      材质，决定用哪个片段着色器
     * @param textureId     纹理 ID
     * @param scissorX      裁剪矩形左边缘 x
     * @param scissorY      裁剪矩形上边缘 y（y 向下；glScissor 的 y 换算见 {@link DrawCommand}）
     * @param scissorWidth  裁剪矩形宽度
     * @param scissorHeight 裁剪矩形高度
     */
    public void setState(Material material, int textureId, int scissorX, int scissorY,
                         int scissorWidth, int scissorHeight) {
        if (currentFirstVertex != NO_COMMAND
                && this.material == material
                && this.textureId == textureId
                && this.scissorX == scissorX && this.scissorY == scissorY
                && this.scissorWidth == scissorWidth && this.scissorHeight == scissorHeight) {
            return;
        }
        // 状态变了：把上一条命令定稿（顶点数 = 已写顶点数 − 它的起始顶点索引）。
        // 只有相邻且同状态的绘制才会落进同一条命令，绝不跨命令合并——绘制顺序即 z 序。
        sealCurrentCommand();

        this.material = material;
        this.textureId = textureId;
        this.scissorX = scissorX;
        this.scissorY = scissorY;
        this.scissorWidth = scissorWidth;
        this.scissorHeight = scissorHeight;
        this.currentFirstVertex = vertexCount;
    }
```

**3c. `currentCommand()` 带上材质**：

```java
    private DrawCommand currentCommand() {
        return new DrawCommand(textureId, material, currentFirstVertex,
                vertexCount - currentFirstVertex,
                scissorX, scissorY, scissorWidth, scissorHeight);
    }
```

（`reset()` 不动：它和其它状态字段一样，只负责把写入器带回"状态未设置"，
不负责把字段重置成默认值——`setState` 会在下一次绘制前把每一样都重新设上。）

**3d. 修 `VertexFormatTest`**：`new DrawCommand(...)` 少一个参数的编译错误会由编译器指出。
把 `src/test/java/com/bingbaihanji/xuan/renderer/VertexFormatTest.java` 里那条测试换成：

```java
    @Test
    void DrawCommand保存全部状态() {
        // 材质刻意用 SDF_TEXT 而不是 COLOR：用默认值做断言的话，
        // "忘了把材质传进命令"这个变异会存活。
        DrawCommand c = new DrawCommand(7, Material.SDF_TEXT, 12, 34, 1, 2, 3, 4);
        assertEquals(7, c.textureId());
        assertEquals(Material.SDF_TEXT, c.material(),
                "材质进了合批判据，就必须进命令——否则文本会被当成纯色画");
        assertEquals(12, c.firstVertex());
        assertEquals(34, c.vertexCount());
        assertEquals(1, c.scissorX());
        assertEquals(2, c.scissorY());
        assertEquals(3, c.scissorWidth());
        assertEquals(4, c.scissorHeight());
    }
```

- [ ] **Step 4: 追加 VertexWriterTest 的三条**

在 `src/test/java/com/bingbaihanji/xuan/renderer/VertexWriterTest.java` 末尾（最后一个 `}` 之前）追加：

```java
    @Test
    void 材质不同的相邻绘制不合并() {
        VertexWriter w = new VertexWriter(64);
        w.setState(Material.COLOR, 1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 0);
        // 只有材质不同，纹理与裁剪完全一样
        w.setState(Material.SDF_TEXT, 1, 0, 0, 100, 100);
        w.vertex(1f, 1f, 0f, 0f, 0xFFFFFFFF, 0);

        assertEquals(2, w.commandCount(),
                "材质不同必须切命令：否则文本与纯色会被同一条命令画出来，"
                        + "采样的是白色纹理，文字糊成方块");
    }

    @Test
    void 材质相同的相邻绘制合并() {
        VertexWriter w = new VertexWriter(64);
        w.setState(Material.SDF_TEXT, 1, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 0);
        w.setState(Material.SDF_TEXT, 1, 0, 0, 100, 100);
        w.vertex(1f, 1f, 0f, 0f, 0xFFFFFFFF, 0);

        assertEquals(1, w.commandCount(),
                "材质、纹理、裁剪都相同就该合并——一段连续文本必须是一条 draw call");
    }

    @Test
    void 材质记进命令且五参数重载落成纯色() {
        VertexWriter w = new VertexWriter(64);
        w.setState(Material.SDF_TEXT, 9, 0, 0, 100, 100);
        w.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 0);

        assertEquals(Material.SDF_TEXT, w.command(0).material());
        assertEquals(9, w.command(0).textureId(), "加材质不该挤掉纹理");

        VertexWriter plain = new VertexWriter(64);
        plain.setState(1, 0, 0, 100, 100);      // 五参数重载
        plain.vertex(0f, 0f, 0f, 0f, 0xFFFFFFFF, 0);

        assertEquals(Material.COLOR, plain.command(0).material(),
                "五参数重载是给纯色绘制的，必须落成 COLOR 而不是未定义值");
    }
```

如果 `VertexWriterTest.java` 里没有 `import static org.junit.jupiter.api.Assertions.*;`，补上。

Run: `mvn -o test -Dtest=VertexWriterTest`
Expected: `Tests run: 21, Failures: 0, Errors: 0`（原有 18 + 新 3）

- [ ] **Step 5: RenderBatch —— SDF 着色器与按材质切程序**

**5a.** 在 `RenderBatch.java` 的 `PICK_FRAGMENT_SHADER` 常量之后加：

```java
    /**
     * SDF 文本的片段着色器。
     *
     * <p>把图集的红通道当有符号距离（0 = 远在字形外，128 = 边界，255 = 远在字形内），
     * 用屏幕空间导数把边缘重新求一遍——<strong>平滑宽度必须由屏幕空间决定</strong>，
     * 预先烘进纹理是不可能的，因为同一个字形会在不同字号下被绘制。
     * 这正是 SDF 相对位图拉伸的全部价值。
     *
     * <p>{@code fwidth} 是 GLSL 3.3 core 的内建函数，不需要扩展。
     *
     * <p><strong>最后一行必须是 {@code vColor * a}，不能写成
     * {@code vec4(vColor.rgb, vColor.a * a)}。</strong>
     * 顶点色是<strong>预乘</strong>的，乘一个标量不破坏预乘性；
     * 而后者会破坏它——被覆盖的像素里 {@code rgb} 不再随 alpha 衰减，
     * 结果是每个被覆盖的像素都饱和到全白，边缘的过渡带整个消失
     * （与渲染管线文档里那条"混合因子顺序不能调换"是同一类问题）。
     */
    private static final String SDF_FRAGMENT_SHADER = """
            #version 330 core
            in vec2 vUV;
            in vec4 vColor;
            uniform sampler2D uTex;
            out vec4 fragColor;
            void main() {
                float d  = texture(uTex, vUV).r;
                float sd = d - 0.5;
                float w  = fwidth(d);
                float a  = smoothstep(-w, w, sd);
                fragColor = vColor * a;
            }
            """;
```

**5b.** 在 `whiteTexture` 字段之后加三个字段：

```java
    /** SDF 文本使用的着色器程序。 */
    private final ShaderProgram sdfShader;

    /** 字形来源：字体 + 光栅化器 + 距离场。 */
    private final FontGlyphSource glyphSource;

    /** 字形图集，与颜色 pass 用同一张纹理。 */
    private final GlyphAtlas glyphAtlas;
```

补 import：`com.bingbaihanji.xuan.text.FontFile`、`com.bingbaihanji.xuan.text.FontGlyphSource`、
`com.bingbaihanji.xuan.text.GlyphAtlas`、`com.bingbaihanji.xuan.text.GlyphRasterizer`。

**5c.** 构造函数末尾（`createVao();` 之前）加：

```java
        this.sdfShader = gl.createShader(VERTEX_SHADER, SDF_FRAGMENT_SHADER);
        // 字体在启动时就加载并解析：规格 §7 要求"字体缺失 / 无法解析"在启动时抛异常，
        // 而不是退化成"一个字都画不出来"——后者的表现是屏幕一片空白，
        // 排查方向会指向 GL 而不是字体。
        //
        // 这一步会分配 9.7 MB 的堆外内存并在 dispose 里归还，见 FontFile 的说明。
        this.font = FontFile.loadClasspath(FontFile.DEFAULT_RESOURCE);
        this.glyphSource = new FontGlyphSource(font, new GlyphRasterizer(font));
        this.glyphAtlas = new GlyphAtlas(gl, glyphSource);
```

字段区同时加：

```java
    /** 字体。与 glyphSource 同时创建，同时释放。 */
    private final FontFile font;
```

**5d.** `beginFrame(int, int)` 的末尾（`pickBufferValid = false;` 之后）加：

```java
        // 图集的帧边界重置钩子：只有在上一帧判定过"货架装不下"时才会真的重置，
        // 因此正常帧是零开销。整体重置只能发生在帧边界——见 GlyphAtlas 的类说明。
        glyphAtlas.beginFrame();
```

**5e.** 把 `submit` 换成（改动集中在"按材质切程序"那一段）：

```java
    public void submit(VertexWriter writer) {
        if (writer.vertexCount() == 0) {
            return;
        }
        vertexBuffer.upload(writer.buffer());

        gl.bindVao(vao);
        gl.bindVbo(vertexBuffer.id());

        // 无条件重新配置属性指针。原因见 configureVaoAttributes 的说明：
        // 扩容会替换底层 VBO，而 GL 名字会被回收，无法靠比较 ID 可靠地检测到替换。
        // 必须在这里调用：此时 VAO 与新 VBO 都已绑定，且紧接着就是绘制。
        configureVaoAttributes();

        gl.enableBlend();
        // 顶点色是预乘的，混合因子必须配套；这一行必须在 enableBlend() 之后，
        // 因为 GLAbstraction.enableBlend() 会顺手把混合因子设成非预乘的那一组。
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        // LWJGL 3 没有 glScissorTest 这个便捷函数，只有 glEnable/glDisable(GL_SCISSOR_TEST)。
        glEnable(GL_SCISSOR_TEST);

        // 材质按命令切换。连续文本是一片相邻的同材质命令，因此通常只切两次
        // （进入文本、离开文本），不会退化成每个字形一次切换。
        ShaderProgram current = shader;
        current.use();

        List<DrawCommand> commands = writer.commands();
        for (DrawCommand command : commands) {
            if (command.vertexCount() == 0) {
                continue;
            }
            ShaderProgram wanted = command.material() == Material.SDF_TEXT ? sdfShader : shader;
            if (wanted != current) {
                current.unuse();
                wanted.use();
                current = wanted;
            }
            // uTex 采样的是 0 号纹理单元：sampler 默认值就是 0，本类从不改动它，
            // 这里显式 glActiveTexture(GL_TEXTURE0) 是为了保证下面这行 glBindTexture
            // 绑定到的确实是 0 号单元（纹理单元的"当前"状态是全局的，不能假设它没被别人动过）。
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, command.textureId());
            applyScissor(command);
            glDrawArrays(GL_TRIANGLES, command.firstVertex(), command.vertexCount());
        }

        // 颜色 pass 画完后再走 ID pass：复用同一份 VBO、同一张命令表，只换程序。
        // 放在这里而不是另起一趟，是因为此刻 VAO/VBO/属性指针与 scissor 都正好是
        // 绘制所需的状态。
        if (writer.hasPickableVertices()) {
            // ID pass 自己会切程序并在结束时解绑；先把颜色 pass 的程序解绑，
            // 免得留下"已绑定但随后被换掉"的悬空状态。
            current.unuse();
            drawPickPass(commands);
        }

        glDisable(GL_SCISSOR_TEST);
        gl.bindVao(0);
        gl.disableBlend();
    }
```

**5f.** 加两个访问器（放在 `whiteTextureId()` 之后）：

```java
    /**
     * 返回字形图集。
     *
     * <p>{@code Gc.drawText} 需要它来取 uv 与绑定纹理。
     *
     * @return 字形图集
     */
    public GlyphAtlas glyphAtlas() {
        return glyphAtlas;
    }

    /**
     * 返回字形来源。
     *
     * <p>{@code Gc} 需要它来查字形索引与度量（{@code measureText} 走的是字体度量，
     * 不生成字形、不碰图集）。
     *
     * @return 字形来源
     */
    public FontGlyphSource glyphSource() {
        return glyphSource;
    }
```

**5g.** `dispose()` 里补上三个资源的释放（放在 `pickBuffer.dispose();` 之后）：

```java
        sdfShader.dispose();
        glyphAtlas.dispose();
        font.dispose();
```

**5h.** 类文档里补一段，说清"文本的可拾取范围":

```java
 * <h2>文本与拾取</h2>
 * <p>ID pass 用的是同一张命令表，因此文本天然可拾取。但要注意
 * {@link #drawPickPass} <strong>不看 alpha</strong>（对整数附件而言，颜色没有意义），
 * 而文本的四边形覆盖的是整个 SDF 位图矩形——包含四周各 {@code SdfGenerator.SPREAD}
 * 像素的外扩。所以<strong>文本的可拾取范围比墨迹大一圈</strong>，
 * 这跟"全透明图元仍可拾取"是同一类行为，是刻意的、有测试钉着的，
 * 不要当成 bug"顺手修好"。
```

- [ ] **Step 6: 编译并跑全量测试**

Run: `mvn -o compile && mvn -o test`
Expected: BUILD SUCCESS；`Tests run: 205, Failures: 0, Errors: 0, Skipped: 2`（202 + 3）

- [ ] **Step 7: 变异验证**（每条做完立刻还原，共 3 条）

| 变异 | 应失败的测试 |
|------|--------------|
| `setState` 的合批判据漏掉材质（删掉 `this.material == material &&`） | `材质不同的相邻绘制不合并` |
| `currentCommand()` 里写死 `Material.COLOR` | **只有** `材质记进命令且五参数重载落成纯色`。实测：`DrawCommand保存全部状态` **不会失败**——它直接 `new DrawCommand(7, Material.SDF_TEXT, ...)`，根本不经过 `VertexWriter.currentCommand()`，这个变异碰不到它。想验证那条测试，要改的是 record 本身（例如给 `material()` 加一个返回 `COLOR` 的实现），实测那样它确实会失败 |
| `submit` 的循环里所有命令都用颜色着色器（去掉 `wanted` 那句，恒用 `shader`） | **无单测**（要真 GL 上下文）→ 由 Task 9 的 `TextVerifier` 覆盖：文字会采样到 1×1 白色纹理，墨迹变成整块四边形，推进/基线/过渡带断言全部失败 |

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/bingbaihanji/xuan/renderer/Material.java \
        src/main/java/com/bingbaihanji/xuan/renderer/DrawCommand.java \
        src/main/java/com/bingbaihanji/xuan/renderer/VertexWriter.java \
        src/main/java/com/bingbaihanji/xuan/renderer/RenderBatch.java \
        src/test/java/com/bingbaihanji/xuan/renderer/VertexFormatTest.java \
        src/test/java/com/bingbaihanji/xuan/renderer/VertexWriterTest.java
git commit -F - <<'EOF'
feat(render): 材质选择位与 SDF 片段着色器

这是本期唯一改动现有管线契约的地方：DrawCommand 多一个组件、setState 多一个
参数、合批判据多一项。三处必须一起改——漏掉任何一处都不报错，只会让文本被
当成纯色画（采样到 1×1 白色纹理），表现是整片文字糊成方块。

setState 保留五参数重载并落成 Material.COLOR：把"纯色绘制用 COLOR"这条约定
固定在一个地方，而不是散落在几十个调用点上。

SDF 着色器最后一行是 vColor * a，不是 vec4(vColor.rgb, vColor.a * a)：
顶点色是预乘的，乘标量不破坏预乘性，而后者会——被覆盖的像素里 rgb 不再随
alpha 衰减，于是每个像素都饱和到全白，过渡带整个消失。这与渲染管线文档里
"混合因子顺序不能调换"是同一类问题。

按材质切程序放在命令循环内：连续文本是一片相邻的同材质命令，通常只切两次，
不会退化成每个字形一次切换。

文本的四边形覆盖整个 SDF 位图矩形（含四周外扩），而 ID pass 不看 alpha，
所以文本的可拾取范围比墨迹大一圈——与"全透明图元仍可拾取"同类，是刻意的，
类文档里写明了不要顺手"修好"。

3 条 VertexWriterTest + VertexFormatTest 那条改造；第 3 条变异（着色器选择）
要真 GL，由 TextVerifier 覆盖。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 8: Gc 的 fontSize / drawText / measureText

**Files:**
- Modify: `src/main/kotlin/com/bingbaihanji/xuan/renderer/Gc.kt`

本任务**不新增单测**：`Gc` 持有 `RenderBatch`，构造它需要真 GL 上下文。
它的验证全部落在 Task 9 的 `TextVerifier` 上——包括下面那个"三处下标算术"的坑。

- [ ] **Step 1: `FLOATS_PER_STYLE_LEVEL` 由 2 变 3 —— 三处一起看**

这是子项目 C 里 `pickId` 进整数栈时**同一个坑**：样式栈的每层宽度变了，
`save` / `restore` / `ensureStyleCapacity` **三处的下标算术必须同步**。
漏改不会报错，只会让相邻两层互相覆盖（`restore` 后读回上一层的值）。

**1a. 常量**：把 `companion object` 里的

```kotlin
        /** 样式栈每层占用的 float 个数：lineWidth、globalAlpha。 */
        private const val FLOATS_PER_STYLE_LEVEL = 2
```

改成

```kotlin
        /** 样式栈每层占用的 float 个数：lineWidth、globalAlpha、fontSize。 */
        private const val FLOATS_PER_STYLE_LEVEL = 3
```

**1b. 字段 KDoc**：

```kotlin
    /** 样式栈的浮点部分：每层 [FLOATS_PER_STYLE_LEVEL] 个值（lineWidth、globalAlpha、fontSize）。 */
    private var styleFloats = FloatArray(INITIAL_STACK_LEVELS * FLOATS_PER_STYLE_LEVEL)
```

**1c. `save()`**：在 `styleFloats[floatBase + 1] = globalAlpha` 之后加一行

```kotlin
        styleFloats[floatBase + 2] = fontSize
```

并把 `save()` 的 KDoc 改成："压入当前的**全部绘制状态**：变换、裁剪、填充色、描边色、线宽、全局不透明度、拾取 ID、字号。"

**1d. `restore()`**：在 `globalAlpha = styleFloats[floatBase + 1]` 之后加一行

```kotlin
        fontSize = styleFloats[floatBase + 2]
```

**1e. `ensureStyleCapacity` 看一眼就好，不用改**：它算的是
`capacityLevels * FLOATS_PER_STYLE_LEVEL`，用的是常量而不是硬编码的字面量，
所以跟着常量自动跟上。**但必须真的看一眼确认它没有把 2 写死**——
本项目的教训是"三处一起看"，而"第三处其实不用改"这个结论只有看过才能下。

> **实测确认（2026-09-20）**：判断规则可以比"数三处"更准——
> **凡是出现 `base + N` 偏移的地方都要改**（`save` 与 `restore`，它们逐项硬写下标）；
> **凡是用 `× FLOATS_PER_STYLE_LEVEL` 算总量的地方自动跟上**（`ensureStyleCapacity`、
> 字段初始化）。用这条规则去读，结论就唯一了，不必依赖记忆。
>
> **还有一处计划漏了**：`ensureStyleCapacity` 的 **KDoc 里写死了「浮点部分每层 2 个
> （lineWidth、globalAlpha）」**——那是散文里的字面量，不是算术，改常量它不会自动跟上。
> 不改的话常量是 3、文档说 2，正是本项目反复吃亏的那种自相矛盾。实施时已一并改掉。

- [ ] **Step 2: 加 fontSize 属性**

在 `globalAlpha` 属性之后加：

```kotlin
    /**
     * 当前字号（像素），默认 16f。
     *
     * <p>它和 [fill]、[stroke] 一样属于绘制状态，会被 [save] / [restore] 存取；
     * 进的是样式栈的**浮点**部分。**改样式栈的每层宽度时必须同步改三处**，
     * 见 [save] 的说明。
     *
     * <p>字号只影响 [drawText] / [measureText]：字形本身是在固定的 em 尺寸下
     * 光栅化的（[com.bingbaihanji.xuan.text.GlyphRasterizer.EM_SIZE]），
     * 字号只是发射顶点时的一个缩放因子，因此改字号不会触发任何重新光栅化。
     */
    var fontSize: Float = 16f
```

- [ ] **Step 3: `syncState` 加材质参数**

`syncState` 现在只传纹理。给它加一个**带默认值**的材质参数，这样
`emitTriangles` 那处调用点不必改动：

```kotlin
    /**
     * 把当前变换、裁剪状态与材质同步给顶点写入器。
     *
     * <p>**裁剪 y 传的是顶边**：[ViewTransform.clipDeviceY] 已是 y 向下的设备像素顶边，
     * 与 [DrawCommand] 的 `scissorY` 约定一致；翻成 `glScissor` 的 y 向上坐标是
     * [RenderBatch.applyScissor] 的职责，这里<strong>不能再翻一次</strong>。
     *
     * <p>[VertexWriter.setState] 在状态未变时是空操作，所以每个图元调用一次没有额外开销；
     * 而帧中途 flush 之后 [VertexWriter.reset] 会把写入器带回"未设置状态"，
     * 下一次调用本方法正好重新设上——见 [VertexWriter.isFlushRequested] 的消费方契约。
     *
     * @param textureId 要绑定的纹理 ID
     * @param material  材质，默认 [Material.COLOR]（纯色绘制的全部调用点都用默认值）
     */
    internal fun syncState(textureId: Int, material: Material = Material.COLOR) {
        writer.setState(
            material,
            textureId,
            state.clipDeviceX,
            state.clipDeviceY,
            state.clipDeviceWidth,
            state.clipDeviceHeight
        )
    }
```

- [ ] **Step 4: 加 drawText / measureText**

补 import：`import com.bingbaihanji.xuan.text.GlyphSlot`、`import com.bingbaihanji.xuan.text.TextLayout`（放在现有的 `com.bingbaihanji.xuan.geom.*` 一组之后）。

在 [pickRect] 之后加：

```kotlin
    // ------------------------------------------------------------------
    // 文本
    // ------------------------------------------------------------------

    /**
     * 绘制一行文本。
     *
     * **`(x, y)` 是基线的起点，不是文本框的左上角。**
     * `y` 是文字**基线**所在的像素行，`x` 是第一个字形的笔位置。
     * 选基线而不是左上角，是因为只有基线是排版的稳定参照——图表的刻度文字要沿轴线
     * 对齐时基线对齐才是想要的；而"左上角对齐"会让不同高度的字符视觉上跳来跳去。
     * **这条是最容易猜错、且猜错后"看起来只是位置偏了一点"的那类约定。**
     *
     * <p>文本是一类普通图元：它走**现有的**批处理管线，所以裁剪、z 序、合批、
     * GPU 拾取全部自动成立。连续的一段文本通常合并成一条 draw call。
     *
     * <p>词法上按**码点**处理（用 [Character.codePointAt]，一次跳过整个代理对），
     * 因此增补平面上的字符不会被拆成两个豆腐块。
     *
     * <p>字体里没有的码点会画成 `.notdef`（通常是个方框），**不会静默跳过**——
     * 静默跳过的表现是"这段文字少了几个字"，用户会以为是排版 bug。
     *
     * <p>空格这类没有轮廓的字形只推进笔，不发顶点，这是正常的。
     *
     * <p>**限制**：单次调用发射的顶点数（每字形 6 个）必须放得进当前顶点缓冲的剩余容量。
     * 在硬上限（[VertexWriter.MAX_VERTEX_CAPACITY] = 1<<20 顶点，约 17 万个字形）以下
     * [VertexWriter] 会自动扩容，因此实际不可达；真要画超长文本，分多次调用即可。
     *
     * @param text 文本，可以包含任意 Unicode 码点
     * @param x    起点 x（用户坐标）：第一个字形的笔位置
     * @param y    起点 y（用户坐标）：**基线**所在的像素行
     * @return 推进宽度（像素），即笔最终走到 `x + 返回值`
     */
    fun drawText(text: String, x: Float, y: Float): Float {
        if (text.isEmpty()) {
            return 0f
        }
        val glyphSource = batch.glyphSource()
        val atlas = batch.glyphAtlas()
        // 槽位里的偏移/尺寸/推进全是 em 像素，这里换成当前字号下的缩放。
        // 字号因此只是发射顶点时的一个乘法，不会触发任何重新光栅化。
        val scale = fontSize / glyphSource.pixelHeight()

        val slots = ArrayList<GlyphSlot>(text.codePointCount(0, text.length))
        var index = 0
        while (index < text.length) {
            val codepoint = text.codePointAt(index)
            // 码点不在字体里时 glyphIndex 返回 0（.notdef）——照常画它，不要跳过。
            slots.add(atlas.acquire(glyphSource.glyphIndex(codepoint)))
            // 必须一次跳过整个代理对：中文有增补平面字符，
            // 用 charAt 逐 char 走会把一个字符拆成两个豆腐块。
            index += Character.charCount(codepoint)
        }

        // 顺序与 emitTriangles 一致：先 flushIfNeeded 再 syncState——
        // reset() 会把写入器带回"尚未设置状态"，必须在它之后重新设上。
        flushIfNeeded()
        syncState(atlas.textureId(), Material.SDF_TEXT)
        return TextLayout.layout(slots, x, y, scale, packColor(fill), pickId, writer(), state)
    }

    /**
     * 量出一行文本的推进宽度（像素）。**不绘制任何东西，也不生成字形。**
     *
     * 走的是字体度量而不是图集，因此可以放心地用于布局计算——测量没有副作用，
     * 也不会因为"量了一下"就把图集填满。
     *
     * <p>与 [drawText] 的返回值**在数学上相等**（两者都是
     * `字体单位 * scaleForPixelHeight(字号)` 的累加），只差浮点舍入。
     *
     * @param text 文本
     * @return 推进宽度（像素）
     */
    fun measureText(text: String): Float {
        if (text.isEmpty()) {
            return 0f
        }
        val glyphSource = batch.glyphSource()
        var width = 0f
        var index = 0
        while (index < text.length) {
            val codepoint = text.codePointAt(index)
            width += glyphSource.advancePixels(glyphSource.glyphIndex(codepoint), fontSize)
            index += Character.charCount(codepoint)
        }
        return width
    }
```

- [ ] **Step 5: 编译并跑全量测试**

Run: `mvn -o compile && mvn -o test`
Expected: BUILD SUCCESS；`Tests run: 205, Failures: 0, Errors: 0, Skipped: 2`（没有新增单测）

- [ ] **Step 6: 变异验证**（每条做完立刻还原，共 4 条）

**这四条都要靠 Task 9 的 `TextVerifier` 判定**，所以请在 Task 9 跑通之后回来做。
注入变异 → 重跑校验器 → 确认失败 → 还原 → 再跑一次确认恢复全绿。

| 变异 | 应失败 |
|------|--------|
| `FLOATS_PER_STYLE_LEVEL` 改回 `2`（只改常量，`save`/`restore` 的两行不动） | 校验器的「嵌套 save 后 restore 的 fontSize」——最外层那次 `restore` 会读回 `lineWidth` 的值 |
| `save()` 里删掉 `styleFloats[floatBase + 2] = fontSize` | 同上（`restore` 读回的是槽位里的残留值） |
| `restore()` 的浮点下标改成 `(styleDepth + 1) * FLOATS_PER_STYLE_LEVEL` | 同上 |
| `drawText` / `measureText` 改用 `charAt` 逐 char 迭代（`index++` 且不处理代理对） | 校验器的「推进宽度：增补平面字符算一个码点」 |

**第一条的机制值得写清楚**（`restore` 之后为什么是"上一层的值"而不是崩溃）：

每层占 3 个 float 时，第 k 层（从 0 起）的 `fontSize` 落在 `styleFloats[3k + 2]`。
把每层宽度改成 2 之后，第 1 层会去写 `[2..4]`——**盖住第 0 层的 `fontSize` 槽**。
于是第一次 `restore` 读 `[4]` 恰好还是对的（第 1 层刚写过），
**第二次 `restore` 读 `[2]` 才暴露**：那里此刻是第 1 层的 `lineWidth`。
所以校验器必须**嵌套两层**再逐层 `restore`，只做一层是抓不住的。

- [ ] **Step 7: 提交**

```bash
git add src/main/kotlin/com/bingbaihanji/xuan/renderer/Gc.kt
git commit -F - <<'EOF'
feat(text): Gc 的 fontSize / drawText / measureText

(x, y) 是基线的起点而不是文本框左上角，这条写进了 KDoc：
只有基线是排版的稳定参照，刻度文字沿轴线对齐要靠它；当成左上角的话
画面"只是位置偏了一点"，最难查。

逐码点处理（Character.codePointAt + charCount）：中文有增补平面字符，
用 charAt 逐 char 走会把一个字符拆成两个豆腐块。

measureText 走字体度量而不是图集：测量无副作用，不会因为"量了一下"
就把图集填满，可以放心用于布局。

fontSize 进样式栈的浮点部分，FLOATS_PER_STYLE_LEVEL 由 2 变 3——
与子项目 C 里 pickId 进整数栈是同一个坑：save / restore 两处的下标算术
必须同步（第三处 ensureStyleCapacity 用的是常量，跟着常量走，但要看一眼
确认它没把 2 写死）。漏改不报错，只会让相邻两层互相覆盖，而且要到第二次
restore 才暴露——所以校验器里嵌套两层逐层验。

4 条变异验证都靠 TextVerifier 判定（Gc 要真 GL 上下文），见 Task 9。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 9: TextVerifier —— 像素级端到端校验器

**Files:**
- Create: `src/main/kotlin/com/bingbaihanji/xuan/example/TextVerifier.kt`

与 `PipelineVerifier` / `PickVerifier` **同构**：画一个每个字符位置都已知的场景，
回读帧缓冲，逐条断言，失败以非零码退出。骨架（`@JvmName("main")` 那段、
`justRendered` 的帧划分、局部的 `report` 函数、退出方式）**照抄 `PickVerifier.kt`**，
下面直接给出完整文件。

**为什么必须新建一个而不是扩展现有的**：`PipelineVerifier` 断言"画面只有 6 种颜色"
与若干精确包围盒，`PickVerifier` 断言 ID pass 的执行次数——往它们的场景里加文字
会把这些断言全部推翻。

- [ ] **Step 1: 写校验器**

创建 `src/main/kotlin/com/bingbaihanji/xuan/example/TextVerifier.kt`：

```kotlin
package com.bingbaihanji.xuan.example

import com.bingbaihanji.xuan.glview.FXGLTransfer
import com.bingbaihanji.xuan.renderer.Gc
import com.bingbaihanji.xuan.view.MainView
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import org.lwjgl.opengl.GL11.*
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * SDF 文本的端到端**像素级校验器**。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>文本子系统的缺陷几乎全部属于「静默错误输出」：字形画歪了、被裁了、
 * 字距不对、过渡带糊了——**没有一个会报错**，只会让画面悄悄不对。
 * 单元测试能覆盖纯算术的那两块（`SdfGenerator`、`TextLayout`），
 * 但"这些顶点进了 GL 之后栅格化出来是什么样"只有回读像素才能回答。
 *
 * <h2>核心验收点：过渡带宽度不随缩放变宽</h2>
 *
 * <p>同一个字以 24px 与 192px 绘制，**边缘过渡带宽度应大致恒定**（约 1~2 个屏幕像素）。
 * 位图拉伸时过渡带会随缩放线性变宽（24→192 是 8 倍缩放，过渡带也宽 8 倍）——
 * **这是唯一能把"SDF 生效"与"位图被放大"区分开的断言**，
 * 也是这个子系统的存在理由本身。
 *
 * <h2>运行</h2>
 *
 * ```
 * mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
 *     "-Dexec.args=-cp %classpath com.bingbaihanji.xuan.example.TextVerifierKt"
 * ```
 *
 * <p>退出码 0 = 全部通过，1 = 有断言失败。它自己关窗退出，不需要手动关闭。
 *
 * <p>**不要用 `mvn exec:java`**：本项目里它必崩（openglfx 会链接到另一份
 * `com.sun.prism.GraphicsPipeline`）。必须 `exec:exec` fork 独立 JVM。
 * 某些 shell 会把 `-D` 前缀吃掉，所以每个 `-D...` 参数都加引号。
 */
private const val SCENE_W = 800
private const val SCENE_H = 600

/** 背景亮度：`glClearColor(0.2f, ...)` 对应的 0x33。 */
private const val BG = 0x33

/** 墨迹判定阈值：亮度超过背景这么多就算被覆盖。 */
private const val INK_THRESHOLD = BG + 8

/** 饱和阈值：达到它就算"完全在字形内部"。 */
private const val FULL_THRESHOLD = 235

/** 文本的拾取 ID。0 恒定表示"什么都没命中"。 */
private const val INK_ID = 1

/**
 * 校验器启动入口。
 *
 * <p>函数名不叫 `main`：同包已有顶层 `main()`（[PipelineExample]），
 * 两个同名顶层函数会让 `import ...example.main` 报重载歧义，而同包内无法用别名区分。
 * 用 `@JvmName("main")` 把 JVM 方法名钉回 `main`，文档里的命令行因此照常可用。
 */
@JvmName("main")
fun textVerifyMain() {
    Application.launch(TextVerifierApp::class.java)
}

class TextVerifierApp : Application() {

    private var transfer: FXGLTransfer? = null

    /**
     * **刚刚渲染完的那一帧**的序号（从 0 开始）。
     *
     * <p>语义是"已完成"而不是"进行中"：`onFrame` 里 [drawScene] 读到的值与
     * 随后 `onRender` 里 [verifyOnce] 读到的值相同，两边对"现在是第几帧"没有分歧。
     */
    private var rendered = 0

    /** 帧 2 量到的"中文测试"墨迹包围盒，供帧 3 比对位置稳定性。 */
    private var inkAtFrameTwo: Ink? = null

    /** `drawText("")` 的返回值，在 [drawScene] 里记下来——校验时不能重画。 */
    private var emptyAdvance = Float.NaN

    /** `drawText("国", ...)` 的返回值。 */
    private var guoDrawAdvance = Float.NaN

    // —— 场景坐标（用户坐标；校验器依赖"用户坐标 1:1 映射到设备像素"这一前提）——

    /** "国"：64px，用来量推进宽度、基线、拾取。 */
    private val guoX = 40f
    private val guoBaseline = 100f
    private val guoSize = 64f

    /** "一"：与"国"同字号同基线，用来验证不同高度的字共用一条基线。 */
    private val yiX = 160f

    /** "中文测试"：48px，用来验证"新字形第二次出现时位置稳定"。 */
    private val lineX = 40f
    private val lineBaseline = 210f
    private val lineSize = 48f

    /** 过渡带的小尺寸端。 */
    private val smallX = 40f
    private val smallBaseline = 320f
    private val smallSize = 24f

    /** 过渡带的大尺寸端。24 → 192 是 8 倍缩放，两个极端更容易拉开差距。 */
    private val bigX = 40f
    private val bigBaseline = 560f
    private val bigSize = 192f

    /** 空串的绘制点：这一带必须一个非背景像素都没有。 */
    private val emptyX = 600f
    private val emptyY = 100f
    private val emptySize = 64f

    // —— 扫描区域（用户坐标，左闭右开）——
    // 每个区域只覆盖一个字符串，彼此的墨迹不会串进来。
    private val regionGuo = intArrayOf(0, 20, 150, 140)
    private val regionYi = intArrayOf(150, 20, 300, 140)
    private val regionLine = intArrayOf(0, 140, 320, 270)
    private val regionSmall = intArrayOf(0, 280, 130, 350)
    private val regionBig = intArrayOf(0, 370, 300, 598)
    private val regionEmpty = intArrayOf(560, 30, 760, 140)

    override fun start(stage: Stage) {
        val bridge = FXGLTransfer()
        bridge.onFrame { gc -> drawScene(gc) }
        bridge.onRender { verifyOnce() }
        transfer = bridge

        val mainView = MainView().apply { center = bridge.createGlFXView() }
        stage.title = "Xuan Text Verifier"
        stage.scene = Scene(mainView.createMainView(), SCENE_W.toDouble(), SCENE_H.toDouble())
        stage.show()
    }

    /**
     * 画场景。
     *
     * <p>帧 0、1 只画一句预热文本（在画面右下方，与任何扫描区域都不重叠）：
     * 让 stb、图集首次分配与 JIT 先热起来。目标字符串在帧 2 才**第一次**出现，
     * 这样帧 2 → 帧 3 的比对才是在测"新字形第二次出现时位置稳定"，
     * 而不是"一直就在那儿的字形没动"。
     */
    private fun drawScene(gc: Gc) {
        gc.fill = 0xFFFFFFFF.toInt()
        gc.pickId = 0

        if (rendered < 2) {
            gc.fontSize = 48f
            gc.drawText("预热", 500f, 480f)
            return
        }

        // "国"：量推进宽度、基线、拾取都用它
        gc.fontSize = guoSize
        gc.pickId = INK_ID
        guoDrawAdvance = gc.drawText("国", guoX, guoBaseline)
        gc.pickId = 0

        // "一"：与"国"同基线，验证相对高度符合度量
        gc.fontSize = guoSize
        gc.drawText("一", yiX, guoBaseline)

        // "中文测试"：帧 2 首次出现，帧 3 再画一遍，比对包围盒
        gc.fontSize = lineSize
        gc.drawText("中文测试", lineX, lineBaseline)

        // 过渡带的小尺寸端
        gc.fontSize = smallSize
        gc.drawText("口", smallX, smallBaseline)

        // 过渡带的大尺寸端
        gc.fontSize = bigSize
        gc.drawText("口", bigX, bigBaseline)

        // 空串：一个顶点都不该发
        gc.fontSize = emptySize
        emptyAdvance = gc.drawText("", emptyX, emptyY)
    }

    private fun verifyOnce() {
        val bridge = transfer ?: return
        val justRendered = rendered
        rendered++

        if (justRendered < 2) {
            return
        }
        val gc = bridge.gc() ?: return

        if (justRendered == 2) {
            // 只记下帧 2 的墨迹包围盒，不做任何断言——校验统一放在帧 3，
            // 这样"位置稳定"才能和其余断言用同一次回读。
            inkAtFrameTwo = inkIn(frameOf(bridge), regionLine)
            return
        }

        verifyAll(bridge, gc)
    }

    private fun frameOf(bridge: FXGLTransfer): Frame {
        val w = bridge.scaledWidth
        val h = bridge.scaledHeight
        return Frame(w, h)
    }

    private fun verifyAll(bridge: FXGLTransfer, gc: Gc) {
        val frame = frameOf(bridge)

        println("=== Xuan 文本像素校验（帧缓冲 ${frame.w}x${frame.h}）===")

        val failures = ArrayList<String>()

        fun report(label: String, ok: Boolean, detail: String) {
            println("  [${if (ok) "PASS" else "FAIL"}] $label — $detail")
            if (!ok) failures.add(label)
        }

        val inkGuo = inkIn(frame, regionGuo)
        val inkYi = inkIn(frame, regionYi)
        val inkLine = inkIn(frame, regionLine)
        val inkSmall = inkIn(frame, regionSmall)
        val inkBig = inkIn(frame, regionBig)

        // —— 1. 推进宽度 ——
        println("\n-- 推进宽度 --")
        gc.fontSize = guoSize
        val measureGuo = gc.measureText("国")
        report("空串的推进宽度为 0", emptyAdvance == 0f,
            "drawText(\"\") 返回 $emptyAdvance")
        report("空串的 measureText 为 0", gc.measureText("") == 0f,
            "measureText(\"\")=${gc.measureText("")}")
        report("全角汉字的推进宽度约等于一个字号", abs(measureGuo - guoSize) <= guoSize * 0.1f,
            "measureText(\"国\")=$measureGuo，期望≈$guoSize（汉字是全角，1 em = 1 字号）")
        report("drawText 的返回值与 measureText 一致", abs(guoDrawAdvance - measureGuo) < 0.5f,
            "drawText=$guoDrawAdvance，measureText=$measureGuo——两者都应当等于" +
                "度量×缩放，只差浮点舍入")
        report("两个字的推进宽度是单个的两倍",
            abs(gc.measureText("国国") - 2f * measureGuo) < 0.01f,
            "measureText(\"国国\")=${gc.measureText("国国")}，期望 ${2f * measureGuo}")
        report("墨迹不超出推进宽度",
            inkGuo.count > 0 && inkGuo.minX >= guoX.toInt()
                    && inkGuo.maxX < (guoX + measureGuo).toInt(),
            "墨迹 x∈[${inkGuo.minX},${inkGuo.maxX}]，推进区间 " +
                "[${guoX.toInt()}, ${(guoX + measureGuo).toInt()})")

        // 代理对：一个增补平面字符是**一个**码点。用 charAt 逐 char 走会得到两倍。
        // 两个串在字体里都不存在，因此都是 1 个 .notdef 字形——只要码点数相同，
        // 推进宽度就必然相同，与 .notdef 的具体宽度无关。
        //
        // 两个串都用转义写、不用字面量：U+FFFF 是非字符，emoji 也不是每个编辑器
        // 都能安全保存，而这条断言的正确性不该取决于源文件的编码。
        val pairAdvance = gc.measureText("\uD83D\uDE00")   // 代理对，U+1F600
        val singleMissing = gc.measureText("\uFFFF")         // 单个 BMP 缺失码点
        report("增补平面字符按一个码点计算", abs(pairAdvance - singleMissing) < 0.01f,
            "代理对(U+1F600)=$pairAdvance，单个缺失码点(U+FFFF)=$singleMissing——" +
                "用 charAt 逐 char 迭代会得到两倍")

        // —— 2. 基线位置 ——
        println("\n-- 基线位置 --")
        report("「国」的墨迹整体在基线之上",
            inkGuo.count > 0 && inkGuo.maxY <= guoBaseline.toInt() + 2,
            "墨迹 y∈[${inkGuo.minY},${inkGuo.maxY}]，基线 y=${guoBaseline.toInt()}——" +
                "把 (x,y) 当成文本框左上角的话，整块墨迹会落到基线之下")
        report("「一」的墨迹整体在基线之上",
            inkYi.count > 0 && inkYi.maxY <= guoBaseline.toInt() + 2,
            "墨迹 y∈[${inkYi.minY},${inkYi.maxY}]")
        report("「一」比「国」矮（相对高度符合度量）",
            inkYi.count > 0 && inkGuo.count > 0
                    && inkYi.minY > inkGuo.minY && inkYi.maxY < inkGuo.maxY,
            "「一」y∈[${inkYi.minY},${inkYi.maxY}] vs 「国」y∈[${inkGuo.minY},${inkGuo.maxY}]")

        // —— 3. ★ 核心验收点：过渡带宽度不随缩放变宽 ——
        println("\n-- ★ SDF：过渡带宽度不随缩放变宽（核心验收点）--")
        val smallBand = bandIn(frame, regionSmall, (inkSmall.minY + inkSmall.maxY) / 2)
        val bigBand = bandIn(frame, regionBig, (inkBig.minY + inkBig.maxY) / 2)
        report("大字号处存在可测的边缘过渡带", bigBand[0] >= 1,
            "192px 的过渡带=${bigBand[0]}px。测出 0 说明边缘是硬跳变——" +
                "SDF 着色器的 smoothstep 没生效（例如写成了 vec4(vColor.rgb, vColor.a * a)，" +
                "那会让每个被覆盖的像素都饱和）")
        report("★ 大字号笔画内部饱和（证明上面的测量没有空转）", bigBand[1] >= 4,
            "192px 的饱和连续段=${bigBand[1]}px，期望 ≥4")
        if (smallBand[0] < 1) {
            report("小字号处存在可测的过渡带", false,
                "24px 的过渡带测出 0px：笔画只有约 2px 宽，整条都在过渡区内，" +
                    "或边缘恰好落在像素中心上。这是**测量方法**的局限而不是缺陷——" +
                    "请把小字号端换成 32px 重测，并如实记录改了什么，" +
                    "不要直接把这条断言删掉")
        } else {
            // 24 → 192 是 8 倍缩放。SDF 的过渡带由屏幕空间的 fwidth 决定，
            // 与缩放无关；位图拉伸时它会随缩放线性变宽。
            report("★ 192px 的过渡带不随 8 倍缩放变宽",
                bigBand[0] <= smallBand[0] * 2 + 1,
                "24px=${smallBand[0]}px，192px=${bigBand[0]}px，阈值 ${smallBand[0] * 2 + 1}px。" +
                    "位图被放大时过渡带会宽约 8 倍——这条断言是唯一的区分手段")
        }

        // —— 4. 空串 ——
        println("\n-- 空串 --")
        val inkEmpty = inkIn(frame, regionEmpty)
        report("空串不画任何东西", inkEmpty.count == 0,
            "空串位置有 ${inkEmpty.count} 个非背景像素")

        // —— 5. 新字形首帧后的位置稳定 ——
        println("\n-- 新字形第二次出现时位置稳定 --")
        val stored = inkAtFrameTwo
        report("帧 3 与帧 2 的墨迹包围盒逐值一致",
            stored != null && stored.minX == inkLine.minX && stored.minY == inkLine.minY
                    && stored.maxX == inkLine.maxX && stored.maxY == inkLine.maxY,
            "帧2=$stored 帧3=$inkLine")
        // 说明：这条断言的区分力有限——命中缓存与重新分配在"uv 指向同一份位图"时
        // 会得到相同的像素。它拦得住的是 uv 漂移、图集被写坏、字形被重新光栅化成
        // 不同的形状这几类。真的"每帧重新分配"要断言的是槽位计数，那需要另外的探针。

        // —— 6. 拾取 ——
        println("\n-- 拾取 --")
        val onInk = gc.pick((inkGuo.minX + 2).toFloat(), (inkGuo.minY + 2).toFloat())?.id() ?: 0
        report("文本墨迹上可拾取", onInk == INK_ID, "实际=$onInk 期望=$INK_ID")
        val outside = gc.pick((inkGuo.minX - 30).toFloat(), (inkGuo.minY + 2).toFloat())?.id() ?: 0
        report("SDF 外扩矩形之外不可拾取", outside == 0, "实际=$outside 期望=0")
        // ID pass 不看 alpha（对整数附件而言颜色没有意义），而文本的四边形覆盖的是
        // 整个 SDF 位图矩形——含四周各 SPREAD 个 em 像素的外扩。
        // 所以文本的可拾取范围比墨迹大一圈：这是刻意的，与"全透明图元仍可拾取"同类。
        val onPadding = gc.pick((inkGuo.minX - 4).toFloat(), (inkGuo.minY + 2).toFloat())?.id() ?: 0
        report("文本的拾取范围包含 SDF 外扩（比墨迹大一圈）", onPadding == INK_ID,
            "墨迹左缘 ${inkGuo.minX} 左侧 4px 处实际=$onPadding 期望=$INK_ID")

        // —— 7. fontSize 必须进样式栈的浮点部分 ——
        println("\n-- 样式栈：fontSize --")
        gc.fontSize = 20f
        gc.save()
        gc.fontSize = 40f
        gc.save()
        gc.fontSize = 80f
        report("内层 fontSize 生效", gc.fontSize == 80f, "实际=${gc.fontSize}")
        gc.restore()
        report("restore 一层回到中间层 40", gc.fontSize == 40f,
            "实际=${gc.fontSize}——每层宽度错成 2 时这一层可能偶然正确，下一句才是关键")
        gc.restore()
        report("再 restore 回到最外层 20", gc.fontSize == 20f,
            "实际=${gc.fontSize}——每层宽度错成 2 时，第 0 层的 fontSize 槽会被第 1 层盖掉，" +
                "这里会读回 lineWidth 的值")

        // 深层嵌套：同时覆盖 ensureStyleCapacity 的扩容路径（初始 8 层 → 21 层）
        val beforeDeep = gc.fontSize
        gc.save()
        for (i in 1..20) {
            gc.fontSize = 10f + i
            gc.save()
        }
        var deepProblem: String? = null
        for (i in 20 downTo 1) {
            gc.restore()
            if (deepProblem == null && gc.fontSize != 10f + i) {
                deepProblem = "restore 到第 $i 层时 fontSize=${gc.fontSize}，期望 ${10f + i}"
            }
        }
        gc.restore()
        if (deepProblem == null && gc.fontSize != beforeDeep) {
            deepProblem = "全部 restore 之后 fontSize=${gc.fontSize}，期望 $beforeDeep"
        }
        report("21 层嵌套的 save/restore 逐层正确", deepProblem == null,
            deepProblem ?: "21 层逐层 restore 全部正确")

        println()
        if (failures.isEmpty()) {
            println("=== 全部通过 ===")
        } else {
            println("=== 失败 ${failures.size} 项：${failures.joinToString("；")} ===")
        }

        Platform.exit()
        exitProcess(if (failures.isEmpty()) 0 else 1)
    }

    override fun stop() {
        transfer?.dispose()
    }
}

/**
 * 一帧的回读像素。
 *
 * <p>只读红通道：场景是纯白文字画在 0x333333 的背景上，红通道的灰度就是覆盖度，
 * 比再算一次亮度更省事也更可预测。
 */
private class Frame(val w: Int, val h: Int) {

    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(w * h * 4)

    init {
        // glReadPixels 的行序自下而上，所以 lum() 里要翻转行号。
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        buffer.position(0)
    }

    /** 用户坐标 (x, y) 处的红通道值。越界返回背景值。 */
    fun lum(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return BG
        }
        return buffer.get(((h - 1 - y) * w + x) * 4).toInt() and 0xFF
    }
}

/** 一块区域里墨迹的包围盒与像素数。 */
private data class Ink(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val count: Int)

/** 扫描一块区域，返回墨迹（亮度超过噪声阈值的像素）的包围盒与数量。 */
private fun inkIn(frame: Frame, region: IntArray): Ink {
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var maxY = Int.MIN_VALUE
    var count = 0
    for (y in region[1] until minOf(region[3], frame.h)) {
        for (x in region[0] until minOf(region[2], frame.w)) {
            if (frame.lum(x, y) > INK_THRESHOLD) {
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    return Ink(minX, minY, maxX, maxY, count)
}

/**
 * 沿一条水平扫描线量"过渡带"：从区域左边缘往右扫，遇到第一个墨迹像素记为 `enter`，
 * 遇到第一个饱和像素记为 `full`。
 *
 * @return `[过渡带宽度, 饱和连续段长度]`；`enter` 或 `full` 找不到时返回 `[0, 0]`
 */
private fun bandIn(frame: Frame, region: IntArray, y: Int): IntArray {
    val x1 = minOf(region[2], frame.w)
    var enter = -1
    var full = -1
    for (x in region[0] until x1) {
        val value = frame.lum(x, y)
        if (enter < 0 && value > INK_THRESHOLD) {
            enter = x
        }
        if (enter >= 0 && full < 0 && value >= FULL_THRESHOLD) {
            full = x
        }
    }
    if (enter < 0 || full < 0) {
        return intArrayOf(0, 0)
    }
    var run = 0
    var x = full
    while (x < x1 && frame.lum(x, y) >= FULL_THRESHOLD) {
        run++
        x++
    }
    return intArrayOf(full - enter, run)
}
```

- [ ] **Step 2: 编译并运行**

Run:
```bash
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.xuan.example.TextVerifierKt"
```
Expected: 全部 PASS，退出码 0。

**任何一条 FAIL 都要先查清原因再继续——不要为了让校验器通过而放宽断言。**

三条要预先知道的：
- **"过渡带不随缩放变宽"若在小字端测出 0px**：那不是缺陷，是 24px 下笔画太细、
  测量方法失效。按代码里那段提示把小字端换成 32px 重测，**并把实际数值如实报上来**。
- **"全角汉字的推进宽度约等于一个字号"若偏差超过 10%**：先打印实际值再决定是
  改容差还是改断言。**不要**直接放宽到能容下任何值。
- **"文本的拾取范围包含 SDF 外扩"若失败**：先确认外扩的像素数
  （`SdfGenerator.SPREAD × fontSize / GlyphRasterizer.EM_SIZE`），
  再把探测点挪到外扩环内、墨迹外的那一小段里。这条断言记录的是**行为**，
  不是"应该修好的 bug"。

- [ ] **Step 3: 变异验证一 —— SDF 编码反相**

把 `SdfGenerator.generate` 里的

```java
            double signed = Math.sqrt(distOut[i]) - Math.sqrt(distIn[i]);
```

临时改成

```java
            double signed = Math.sqrt(distIn[i]) - Math.sqrt(distOut[i]);
```

重跑校验器。
Expected: **多处 FAIL**——字形内部变成"远在字形外"，墨迹整体反相：包围盒、基线、
推进宽度（墨迹超出推进区间）、过渡带全部报错。退出码 1。

确认失败后**改回来**。

- [ ] **Step 4: 变异验证二 —— SDF 着色器破坏预乘**

把 `RenderBatch.SDF_FRAGMENT_SHADER` 的最后一行

```glsl
                fragColor = vColor * a;
```

临时改成

```glsl
                fragColor = vec4(vColor.rgb, vColor.a * a);
```

重跑校验器。
Expected: **"大字号处存在可测的边缘过渡带"必须失败**。
顶点色是预乘的，被覆盖的像素里 `rgb` 不再随 alpha 衰减，于是每个被覆盖的像素都
饱和到全白，过渡带整个消失（测出 0px）。

**若实测这条不失败**（例如 MSAA 在四边形边界上又造出了中间值），
**如实记为「未能覆盖」并说明原因**——不要假装覆盖了。这条与规格 §5.5 那一条
是同一个缺陷，Task 7 的代码注释里已经写明它是刻意的。

确认后**改回来**。

- [ ] **Step 5: 变异验证三 —— uv 去掉半点偏移**

把 `GlyphAtlas.allocate` 里的 `(x + 0.5f)` / `(x + w - 0.5f)` 改回 `x` / `(x + w)`。

重跑校验器。
Expected: **很可能全部通过**。这条变异的影响只有 1/4096 的 uv 偏移，
在本校验器的口径下基本不可见。

**所以它的第一道防线是 `GlyphAtlasTest.uv带半点偏移且归一化在0到1之间`**
（那条断言对 1/4096 是敏感的）。**如果校验器确实不失败，就如实写"校验器未能覆盖，
由 GlyphAtlasTest 覆盖"**——两条一起看才构成覆盖，单看任何一条都会得出错误的结论。

确认后**改回来**。

- [ ] **Step 6: 变异验证四 —— 字形上下颠倒**

把 `GlyphRasterizer.rasterize` 里的 `y0 - SdfGenerator.SPREAD` 改成 `y0 + SdfGenerator.SPREAD`。

重跑校验器。
Expected: **「基线位置」那一组必须失败**——字形整体下移 16 个 em 像素，
墨迹会落到基线之下。

（派单时这一格写的是"LRU 允许淘汰本帧用过的槽"。**本期已经没有 LRU 了**：
`GlyphAtlas` 改成了"只增不减 + 帧边界整体重置"，那条变异在结构上不可能注入。
换成上面这条等价力度的几何变异，并在报告里记明替换原因。）

确认后**改回来**。

- [ ] **Step 7: 变异验证五 —— 上传前不设 GL_UNPACK_ALIGNMENT**

**这条要连常量一起改，否则观察不到。** 把 `GlyphAtlas.SIZE` 临时从 `4096` 改成
`4098`（**不是 4 的倍数**），再把 Task 2 里 `LwjglGLAbstraction.uploadR8SubImage`
的

```java
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
```

临时注释掉。

重跑校验器。
Expected: **墨迹包围盒与过渡带断言必须失败**——每行错位，字形被斜切。

**若不改 `SIZE` 直接注释掉那一行，是观察不到的**：4096 行字节数恰好是 4 的倍数，
`GL_UNPACK_ALIGNMENT` 取默认值 4 也不会错位。**如实记录这一点**，
不要把"没观察到"当成"这条变异无效"。

确认后**改回来**（`SIZE` 与那一行都要还原，然后 `git diff` 确认干净）。

- [ ] **Step 8: 确认回滚干净并跑全量测试**

Run:
```bash
git status --porcelain
mvn -o test
```
Expected: 工作区只有 `TextVerifier.kt` 一个新增文件（以及本任务之前的提交）；
`Tests run: 205, Failures: 0, Errors: 0, Skipped: 2`。

- [ ] **Step 9: 提交**

```bash
git add src/main/kotlin/com/bingbaihanji/xuan/example/TextVerifier.kt
git commit -F - <<'EOF'
test(text): SDF 文本的像素级端到端校验器 + 五条变异验证

核心验收点是第 3 条：同一个字以 24px 与 192px 绘制，边缘过渡带宽度应大致恒定。
位图拉伸时过渡带会随缩放线性变宽，而 SDF 的过渡带由屏幕空间的 fwidth 决定、
与缩放无关——这是唯一能把"SDF 生效"与"位图被放大"区分开的断言，也是这个
子系统存在的理由。断言还配了两条护栏：过渡带必须存在（下界 ≥1px，
它同时抓"预乘被破坏、每个像素都饱和"这个缺陷），以及笔画内部必须饱和
（证明测量没有空转）。

另五条：推进宽度（含代理对算一个码点、墨迹不超出推进区间）、基线位置
（不同高度的字共用一条基线、墨迹在基线之上）、空串不画、新字形第二次出现
时位置稳定、文本可拾取（含"SDF 外扩也在拾取范围内"这条行为记录）。

第七组是 fontSize 进样式栈：嵌套两层逐层 restore，再加 21 层深度嵌套
覆盖扩容路径。每层宽度错成 2 时，第 0 层的 fontSize 槽会被第 1 层盖掉，
要到第二次 restore 才暴露——只做一层是抓不住的。

五条变异：SDF 编码反相、着色器破坏预乘、uv 去掉半点偏移（如实记录校验器
覆盖不到、由 GlyphAtlasTest 兜底）、offsetY 符号写反（替换了不存在的 LRU
一条）、GL_UNPACK_ALIGNMENT（必须连 SIZE 一起改成非 4 倍数才观察得到）。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

### Task 9 实施期的四处更正（都已实测钉死）

**实测结果先说**：校验器 **22 PASS / 0 FAIL / 退出码 0**。
核心那条——**24px 过渡带 = 1px，192px 过渡带 = 1px（阈值 3px）**。
位图拉伸的话 192px 会到约 4px。**SDF 管线确实生效**，这是唯一能证明它的证据。

1. **「国」的基线断言在本字体下不可能成立。** 计划写 `maxY <= 基线 + 2`，实际「国」墨迹
   y∈[50,107] 而基线是 100。**这不是排版错误——simhei 的「国」字形本身就低于基线**。
   两条独立证据：stb 探针给出 `glyphPixelBox(em=48) = [4,-38,44,5]`（底边在基线之下
   5/48 em）；用 fontTools 直接读 `simhei.ttf` 的 `glyf` 表，`国` 的 `yMin = -26`，
   `unitsPerEm = 256`，即下探 0.102 em。已把上界改成字号的 0.2 em（真实 0.102，
   「当成左上角」的误读是 0.78 em，区分力不减）；**「一」那条保持原样不加宽**——
   它才是抓「当成左上角」的那条。

2. **Step 5 的变异 3 按字面写出来是整数除法，不是 uv 偏移。**
   `float u0 = x / SIZE;` 里两个操作数都是 `int`，**整除**了：u0=v0=u1=v1=0，
   所有四边形塌到一个纹素，实测 **9 条失败**，与计划预期的「很可能全部通过」正相反。
   真正的浮点版（`(float) x / SIZE`）才如计划所料**存活**，并如兜底所说被
   `GlyphAtlasTest.uv带半点偏移且归一化在0到1之间` 抓住。**计划对归属的判断是对的，
   对"怎么写"的描述是错的。**

3. **Step 7 说「不改 SIZE 就观察不到 `GL_UNPACK_ALIGNMENT`」——错的，理由也是错的。**
   `GL_UNPACK_ALIGNMENT` 管的是**客户端缓冲的行距**，行字节数是**字形位图的宽度**
   （50/54/56…），**与图集 `SIZE` 无关**。实测：只注释掉那一行、`SIZE` 保持 4096，
   结果与改成 4098 **一字不差**（同样 1 FAIL / exit 1）。计划里那句
   "4096 行字节数恰好是 4 的倍数"是无关论证。
   这也解释了为什么这条变异的失败面很窄：**位图宽度是 4 的倍数的字形根本不受影响**
   （「国」56、「一」60 都整除 4），只有「口」50、「中」54 这类被斜切。

4. **Step 7 预期「墨迹包围盒与过渡带断言必须失败」说得过重。** 实测只有 1 条失败，
   且 192px 那条是 `3 <= 3` **擦线通过**。若 24px 端当时测出 ≥1px，这条变异就会存活。

### 另有一处与计划无关、但必须记的发现

**校验器会以退出码撒谎。** 断言块跑在 GL 线程上，若某个断言**抛异常**（而不是报告失败），
线程死掉、汇总行与 `exitProcess` 都执行不到，JVM 因「最后一个非守护线程结束」**以 0 退出**。
实测触发：把样式栈常量改错，`Gc.save()` 越界抛 `ArrayIndexOutOfBoundsException`，
校验器**打印了 FAIL 却 `EXIT=0`**。

**一个假报成功的校验器比没有校验器更危险**——盲区只是漏检，假绿灯会让后来者和自动化
都以为跑过了。`PipelineVerifier` 与 `PickVerifier` 当时**也都没有**这层保护，
已一并加固（断言块外包 try/catch，异常时打印堆栈并以 1 退出），
并对各自注入了「故意抛异常」的探针确认退出码确实是 1。

---

## Task 10: 文档更新

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

**这一节的每一条都是"写进文档的行为声明"，必须对着代码核实过再写。**
下面给的是**已经对着当前代码核实过**的文本；若实现过程中有偏离（例如 Task 4 的
探针失败、改用了薄接口），**照着实际改动调整文字**，不要照抄这里有出入的句子。

- [ ] **Step 1: CLAUDE.md —— 把 SDF 文本从「未实现」移到「已实现」**

**1a.** 删掉「未实现 / 待办」里的这一行：

```markdown
- **SDF 文本**（子项目 B）：目前完全没有文本绘制能力。需支持中文与任意缩放清晰度。
```

**1b.** 在「可用（纯计算，无需 GL，最适合写测试）」那段末尾追加：

```markdown
`text/SdfGenerator`（覆盖度位图 → 有符号距离场）、`text/TextLayout`（槽位序列 → 四边形顶点）
```

**1c.** 在「可用（依赖 GL 上下文）」那段末尾追加：

```markdown
`text/FontFile`（stb 的字体与度量封装）、`text/GlyphRasterizer`、`text/GlyphAtlas`（R8 图集）、
`Gc` 的 `fontSize` / `drawText` / `measureText`
```

**1d.** 「声明了但完全没用到的依赖」那句里，**删掉 `lwjgl-stb`**——
它现在真的在用了（`FontFile` 的 `stbtt_*`）。改成：

```markdown
JOML（数学全是手写的）、`lwjgl-glfw`、jspecify、logback、byte-buddy(+agent)、JNA。
```

- [ ] **Step 2: CLAUDE.md —— 测试一节**

把「### 测试」整段替换为（**数字以实跑为准**，这里是预期值 205）：

```markdown
```
src/test/java/com/bingbaihanji/xuan/geom/       PathTest、FlattenerTest、TessellatorTest、
                                                TessellatorHoleTest、TessellatorRegressionTest、
                                                StrokeGeneratorTest、StrokeDashTest、
                                                GeomPackageIsolationTest
src/test/java/com/bingbaihanji/xuan/renderer/   VertexFormatTest、VertexWriterTest、ViewTransformTest、
                                                PickRegistryTest、PickBufferTest
src/test/java/com/bingbaihanji/xuan/gl/         FramebufferTest、LwjglGLAbstractionTest
src/test/java/com/bingbaihanji/xuan/text/       SdfGeneratorTest、GlyphAtlasTest、FontFileTest、
                                                GlyphRasterizerTest、TextLayoutTest
```

当前 **205 个测试，0 失败，2 跳过**。单测命令：`mvn test -Dtest=类名`。

`geom/`、`math/`、`util/`、`ViewTransform`、`text/{SdfGenerator, TextLayout}` 都是纯计算、
不依赖 GL 上下文，最适合写单测。`gl/Framebuffer`、`renderer/PickBuffer`、`text/GlyphAtlas`
只依赖 `GLAbstraction` **接口**，用 `src/test/.../gl/FakeGLAbstraction` 这个假实现也能
零 GL 上下文单测。`text/{FontFile, GlyphRasterizer}` 依赖 stb 的本地库。
`RenderBatch` 的着色器与 `Gc` 则必须靠校验器。
```

**写最后一句之前先去确认 Task 4 探针的实际结果**：
- 探针通过 → 把"依赖 stb 的本地库"展开成"依赖 stb 的本地库（已实测能在 surefire 里加载）"。
- 探针失败、改用了薄接口 → 写成"stb 调用被隔离在 `text/StbBackend` 后面，
  单测用假实现；真 stb 路径由 `TextVerifier` 覆盖"。

**不要写一句你没验证过的话。**

- [ ] **Step 3: CLAUDE.md —— 架构图加一层**

把「单一批处理管线」那个代码块里的 L0 两行换成：

```
L0  几何            geom.Path / Flattener / Tessellator / StrokeGenerator   纯计算，零 GL 依赖
    文本            text.SdfGenerator / text.TextLayout                     纯计算；
                    text.FontFile（stb）/ text.GlyphAtlas（R8 图集）        依赖 stb 与 GL
    GL 抽象         gl.*                                             LWJGL 3.3.6 + openglfx
```

- [ ] **Step 4: CLAUDE.md —— 验证一节加 TextVerifier**

在第 1 条里 `PickVerifier` 那段之后追加：

```markdown
   改**文本**路径后跑 `TextVerifier`（退出码 0/1）。它的核心断言是：
   同一个字以 24px 与 192px 绘制时，**边缘过渡带宽度大致恒定**——
   位图被放大时过渡带会随缩放线性变宽，**这是唯一能把"SDF 生效"与
   "位图被放大"区分开的断言**：

   ```bash
   mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
       -Dexec.args="-cp %classpath com.bingbaihanji.xuan.example.TextVerifierKt"
   ```
```

- [ ] **Step 5: CLAUDE.md —— 补一节「文本」（与既有的「拾取」一节同构）**

在「### 拾取」之后插入：

```markdown
### 文本

`gc.fontSize = 32f` 设字号（状态字段，进 save/restore 栈），
`gc.drawText(text, x, y)` 绘制并返回推进宽度，`gc.measureText(text)` 只量不画。

- **`(x, y)` 是基线的起点，不是文本框左上角。** `y` 是文字**基线**所在的像素行。
  选基线是因为只有它是排版的稳定参照——刻度文字沿轴线对齐靠的就是它；
  当成左上角的话画面"只是位置偏了一点"，最难查。**这条最容易被调用方猜错**。
- **文本是又一类普通图元**：走现有的批处理管线，因此裁剪、z 序、合批、GPU 拾取
  全部自动成立。连续的一段文本通常合并成一条 draw call。
- **任意缩放清晰**：字形只在 48px em 下光栅化一次，之后由距离场在屏幕空间重算边缘。
  改 `fontSize` 不触发任何重新光栅化——字形按字形缓存，不按 (字形, 字号)。
- **缺字不跳过**：字体里没有的码点画成 `.notdef`（豆腐块）。静默跳过会让人
  以为排版出了 bug。
- **文本的可拾取范围比墨迹大一圈**：ID pass 不看 alpha，而文本的四边形覆盖的是
  整个 SDF 位图矩形（含四周各 `SPREAD` 像素的外扩）。与"全透明图元仍可拾取"同类，
  **是刻意的，有测试钉着，不要当成 bug 修**。
- **字体**：默认从 classpath 的 `/fonts/simhei.ttf` 加载，启动时解析失败会**抛异常**
  （不退化成"一个字都画不出来"）。换字体见 `src/main/resources/fonts/README.md`
  ——**这个仓库公开分发前必须换掉它**，黑体是微软/中易的专有字体。
- 本期**不做**字距/连字/bidi、多行与对齐、富文本、多字体回退、MSDF。这些是刻意
  不做，不是漏了。
```

- [ ] **Step 6: CLAUDE.md —— 顺手修一处与本次无关但已经过期的说法**

「绘制模型」一节里这一行是子项目 C 之前写的，现在不成立了：

```markdown
- `id` 属性（location 3）是为将来的 GPU 拾取预留的。
```

换成：

```markdown
- `id` 属性（location 3）用于 GPU 拾取，见「拾取」一节。
```

- [ ] **Step 7: README.md —— 运行一节加 TextVerifier**

在「运行」一节的 `# 拾取` 那段之后加：

```bash
# 文本
mvn -o compile exec:exec -Dexec.executable=java -Dexec.classpathScope=runtime \
    -Dexec.args="-cp %classpath com.bingbaihanji.xuan.example.TextVerifierKt"
```

并把下面那句「**改哪条路径就跑哪个校验器**」改成：

```markdown
**改哪条路径就跑哪个校验器**：动了顶点/几何/描边跑 `PipelineVerifier`，
动了拾取（`pickId`、ID pass、`PickBuffer`）跑 `PickVerifier`，
动了文本（`text/`、`fontSize`、`drawText`）跑 `TextVerifier`。
三个都过不代表没漏——它们只证明自己断言过的那些点，见文末「验证」一节。
```

- [ ] **Step 8: README.md —— API 一节加「文本」**

在「### 拾取」之后插入：

```markdown
### 文本

```kotlin
gc.fontSize = 32f                    // 状态字段，进 save/restore 栈
gc.fill = 0xFFFFFFFF.toInt()

// (x, y) 是**基线**的起点，不是文本框左上角
val advance = gc.drawText("中文 Hello", 40f, 100f)

// 量宽度：不绘制、也不生成字形，可以放心用于布局
val width = gc.measureText("中文 Hello")

// 文本天然可拾取
gc.pickId = id
gc.drawText("可点的标签", 40f, 200f)
```

文本走的是**现有**批处理管线，所以裁剪、z 序、合批、GPU 拾取全部自动成立；
连续的一段文本通常合并成一条 draw call。

- **基线对齐**：`y` 是基线所在的像素行。刻度文字沿轴线对齐时，基线对齐才是
  想要的；"左上角对齐"会让不同高度的字符视觉上跳来跳去。
- **任意缩放清晰**：字形只在 48px 的 em 尺寸下光栅化一次，之后由距离场在屏幕空间
  重算边缘——同一个字在 10px 和 200px 下都不会是拉伸的糊图。
- **缺字不跳过**：字体里没有的码点画成 `.notdef`（豆腐块），不静默省略。
- **字体**：默认用 `/fonts/simhei.ttf`（黑体）。换字体见
  `src/main/resources/fonts/README.md`——**公开分发前必须换掉它**。
- 本期不支持字距/连字/bidi、多行与对齐、多字体回退。这些是刻意不做，不是漏了。
```

- [ ] **Step 9: README.md —— 项目结构与依赖**

`src/main/java/...` 的树里加一行（放在 `renderer/` 之后）：

```
├── text/        # SDF 文本：FontFile(stb)、GlyphRasterizer、SdfGenerator、GlyphAtlas、TextLayout
```

再在代码块之后补一行：

```markdown
`src/main/resources/fonts/` 放着字体文件与它的授权/换字体说明（见该目录的 README）。
```

「依赖」一节加一条：

```markdown
- lwjgl-stb 3.3.6（字形光栅化）
```

「测试」一节的这句：

```markdown
**改渲染路径后跑 `PipelineVerifier`，改拾取路径后跑 `PickVerifier`**（见上面「运行」一节）。
```

改成：

```markdown
**改渲染路径跑 `PipelineVerifier`，改拾取跑 `PickVerifier`，改文本跑 `TextVerifier`**（见上面「运行」一节）。
```

- [ ] **Step 10: 跑全量测试与三个校验器**

Run:
```bash
mvn -o test
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.xuan.example.PipelineVerifierKt"
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.xuan.example.PickVerifierKt"
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.xuan.example.TextVerifierKt"
```
Expected: 测试 205 通过 / 0 失败 / 2 跳过；三个校验器都退出码 0。

**三个校验器都要跑**：文本子系统改动了 `DrawCommand` 与 `VertexWriter` 的合批判据，
那是**所有**绘制路径共用的东西。`PipelineVerifier` 是唯一能证明"没把原有图形画坏"
的网——它加了新材质之后仍然断言"画面只有 6 种颜色"，正好能抓住合批出错。

- [ ] **Step 11: 提交**

```bash
git add CLAUDE.md README.md
git commit -F - <<'EOF'
docs: 补 SDF 文本的用法与验证方式

CLAUDE.md：SDF 文本从「未实现」移到「已实现」；测试计数与目录清单更新到
text/ 这一层；架构图加 text 一行；验证一节加 TextVerifier，并写明它的核心
断言（过渡带宽度不随缩放变宽）——那是唯一能把"SDF 生效"与"位图被放大"
区分开的判据。新增「文本」一节，重点写死 (x,y) 是基线而不是左上角、
缺字画 .notdef 不跳过、以及"文本的可拾取范围比墨迹大一圈"是刻意行为。

顺手修了一处与本次无关但已经过期的说法：id 属性不再是"为将来的 GPU 拾取
预留"，拾取早就做完了。

README.md：运行一节加 TextVerifier，API 一节加文本用法与那几条约定，
项目结构补 text/ 与 fonts/，依赖补 lwjgl-stb。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## 完成标准

- [ ] `mvn -o test` → **205 通过 / 0 失败 / 2 跳过**（若实际数字不同，以实跑为准并更新本文档顶部那张计数表）
- [ ] `PipelineVerifier` 退出码 0（**原有回归网未被破坏**——本次动了 `DrawCommand` 与 `VertexWriter`，全管线的合批都受影响）
- [ ] `PickVerifier` 退出码 0（ID pass 与文本共用同一条命令表）
- [ ] `TextVerifier` 退出码 0，其中「192px 的过渡带不随 8 倍缩放变宽」必须 PASS
- [ ] 每个任务的变异验证都**实际注入并确认失败**过，且已回滚（`git status --porcelain` 只剩预期的改动）
- [ ] 任何**存活**的变异都在报告里如实写明"未能覆盖"及原因，**没有**用放宽断言的方式掩盖
- [ ] `CLAUDE.md` / `README.md` 已更新，且写进去的每一句行为声明都对着代码核实过
- [ ] 未使用 `mvn exec:java`

## 已知遗留（不在本计划范围）

- **换字体的运行时入口**：规格 §6 说"`Gc` 提供替换字体的入口，换字体不应改代码"，
  本期**没有实现**。当前换字体的办法是替换 `src/main/resources/fonts/simhei.ttf`
  这个资源文件再重新构建（见该目录的 README）。
  不做的原因：`GlyphAtlas` 的槽位缓存与 `FontFile` 的生命周期要一起换掉，
  而"帧中途换了字体、本帧已发射的 uv 立刻失效"正是规格 §5.3 那类静默缺陷的
  又一个入口。要做就该连同"只能在帧边界换"的守卫一起做，那是独立的一小步。
- **小字号质量**：规格 §9 的 R1。单桶（48px em）缩到 10–12px 时，SDF 的双线性
  插值会丢掉一些笔画细节。`TextVerifier` 的过渡带断言会给出客观数据；
  **先实测，不要预先优化**。
- 字距（kerning）、多行与对齐、富文本、多字体回退、MSDF、斜体/粗体的合成。
- 异步字形生成（规格 §9 的 R2）：首帧遇到大量新字时会有一次同步卡顿。
  CPU 侧（光栅化 + 距离场）本就是纯计算，搬到工作线程不需要改任何 GL 代码，
  代价主要在"字形未就绪时这一帧画什么"的取舍上。
- **单次 `drawText` 的顶点数上限**：每字形 6 个顶点，在 `VertexWriter` 的硬上限
  （1<<20 顶点 ≈ 17 万个字形）以下会自动扩容，因此实际不可达；
  真要画超长文本，分多次调用即可。
- 文本的可见边界（墨迹）与可拾取边界（整个 SDF 位图矩形）不一致，
  外扩那一圈是"隐形热区"。要精确到墨迹需要 alpha 测试，不在范围内。
- 没有黄金图像测试。