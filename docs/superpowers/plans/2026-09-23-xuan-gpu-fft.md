# Xuan GPU FFT 与频谱 实施计划（子项目 D-③-1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 GPU 上算 FFT，把结果作为一个图表系列画出来——数据不出显存。

**Architecture:** 单个 compute dispatch 干完全部（加窗 → 位反转 → `log2(N)` 级蝶形 → 幅度），全在 shared memory 里做。产物写进一个**既绑 SSBO 又绑 VBO** 的缓冲，于是 ② 的实例化渲染机制**原样复用**——零新增渲染机制、零 GPU 侧拷贝。

**Tech Stack:** Java 21 + Kotlin 2.3、LWJGL 3.3.6、GL 4.6 compatibility（实测）、JUnit 5

---

## 开工前必读

1. `docs/superpowers/specs/2026-09-23-xuan-gpu-fft-design.md` —— 本计划的规格。
2. `CLAUDE.md` 的「怎么验证改动」与「前置事实」两节。
3. 记忆 `xuan-silent-output-defects.md` —— **本项目九次栽在同一个根因上**：
   断言的观测点不是守卫保护的那个量。

**两条派单纪律（出过真实事故）：**

> **一、只 `git add` 你 `Files:` 里列出的确切路径。** 不要用 `git add -A` / `git add .`。
> **二、禁止 `git reset` / `git rebase` / `git commit --amend`。** 发现提交被污染，停下来报告。

**派子代理时不要传 `model` 参数**（见记忆 `subagent-model-override-breaks.md`——
传枚举值会解析到没额度的模型名）。

**命令里的 `-D` 必须加引号。永远不要用 `mvn exec:java`。**

---

## ⚠️ 本计划要避开的三条线（`GPUFFT` 实测出来的三层缺陷）

| | 缺陷 | 本计划怎么避开 |
|---|---|---|
| ① | **`half` 是 GLSL 保留字** → 默认着色器编译不过，**从未成功运行过一次** | 用 `halfLen`；**校验器里有一条"编译成功"的断言** |
| ② | uniform 声明成 `uint` 却用 `glUniform1i` 赋值 → **静默不生效** | **一律声明 `int`**（同一个坑 ② 在 `uPickId` 上踩过） |
| ③ | DIT 要求输入先位反转，而算出的 `rev` **从没被引用** | **位反转是着色器内部的第一步**，不依赖任何外部步骤 |
| ④ | **（本计划写规格时自己踩出来的）** 位反转写成 `int(bitfieldReverse(...)) >> k` —— 转成 `int` 后是**负数**，而 GLSL 对**有符号**左操作数的 `>>` 是**算术右移**，得到负下标、**越界写共享内存** | **移位在 `uint` 域里做完再转 `int`**（Task 3 的代码里已修正并写明） |
| ⑤ | **（Task 3 实施期自查发现）** `MAX_N` 与着色器里 `shared float sRe[4096]` 的字面量**没有机械绑定**——抬大 `MAX_N` 构造器会放行，而数组仍是 4096 → 越界写共享内存 | **着色器源码由常量生成**（`SHADER_TEMPLATE.formatted(...)`），且 GLSL 里的循环步长改用 `gl_WorkGroupSize.x` |

**这五条不是背景知识，是验收标准**：Task 4 的四条变异里有两条直接对应 ②③。

> ### ★ 由 ⑤ 推出来的一条通用规律：**着色器源码也是"能脱离 GL 上下文测"的东西**
>
> Task 3 的复核指出：`FftKernel.shaderSource()` 是**纯字符串运算**，
> 按本项目自己的判据（"**能不能脱离 GL 上下文跑测试**"），**它完全该进单测**。
>
> 而这几条断言恰好把本轮踩过的三个坑**从"跑一次才知道"变成"回归即红"**，且零 GL 依赖：
>
> | 断言 | 钉住 |
> |---|---|
> | `!src.contains("%n")`（以及 `%<`、`%1$`） | **不消耗实参的格式符**——它**不抛异常**，被静默换成换行符，着色器照样编译通过 |
> | `src.contains("sRe[" + MAX_N + "]")` | ⑤：共享内存数组长度与常量脱钩 |
> | `!src.contains("+= 1024")` | 循环步长退回字面量 |
>
> **⚠️ 特别记一条我自己的错误推理**：我曾说"它现在能跑，说明已经证明了全部 `%` 都正确"。
> **那是错的**——`String.formatted` 一次性求值只证明了"**没有会抛异常的 `%`**"，
> **证明不了"没有不消耗实参的说明符"**。`%n` 会被静默替换、着色器照样提交、照样编译通过。
> **静态扫描是唯一能关掉这条静默通道的证据。**
>
> **这又是"用'能跑'替代了'对'"** ——与 `GPUFFT` 那次同一个毛病。
>
> ### ⚠️⚠️ 实施期最重的一次更正：**我给的 5 条断言里有 2 条是橡皮图章**
>
> 实施者**按本计划的要求跑变异**，回来报告其中 **2 条全绿、一条都没抓住**：
>
> | 变异 | 结果 |
> |---|---|
> | 把模板里的 `sRe[%d]` 写死成 `sRe[4096]` | **5 条全绿** |
> | 在 GLSL 注释里塞一个 `%n` | **5 条全绿** |
>
> **根因：那两条断言扫的是 `formatted(...)` **之后**的成品源码，而缺陷在**格式化之前**的模板里。**
>
> 1. 写死的 `sRe[4096]` 在 `MAX_N == 4096` 时与 `sRe[%d]` 生成**逐字符相同**的串——
>    `assertTrue(src.contains("sRe[4096]"))` **分不出"算出来的"与"抄上去的"**。
> 2. `%n` **会被 `formatted` 吃掉**（换成换行符），成品里**根本不会剩下** `%n` 三个字符——
>    `assertFalse(src.contains("%n"))` **是在扫空气**。
>
> **修法：断言改打在模板上**（`shaderTemplate()` 访问器），并把三条具体断言升级成
> **逐字符扫描**（`%` 后面必须是 `d` 或 `%`，且 `%d` 恰好 3 个——**多余的实参是被静默忽略的**，
> 那正是"写死字面量"那类缺陷的入口）。
>
> **再加一条正向控制**：`assertTrue(contains("gl_WorkGroupSize.x"))`——
> 因为只断言"没有 `+= 1024`"的话，**三处循环整个被删掉时它照样成立**。
>
> **规则：断言要打在被检查的那个东西本身上，而不是它经过某次变换之后的产物。**
> 当变换会**消灭**证据（吃掉转义符）或**抹平**差异（常量恰好等于字面量）时，
> 打在产物上的断言恒真。**这与"断言观测的量不是守卫保护的那个量"同源，
> 但机制是新的：不是选错了量，是选错了变换阶段。**

> **④ 的实测症状值得单独记**：Task 3 把它做成变异跑了一遍——
> **峰值变成 0.5 而非 1.0，不崩、无 GL 错误**。
> 我原以为越界写会崩，**实际 NVIDIA 上它是完全静默的**。
> ⑤ 与它同源：都是"共享内存的下标/尺寸出界，而驱动一声不吭"。

---

## 文件结构

**新建**

| 文件 | 职责 |
|---|---|
| `gpu/FftWindow.java` | 窗枚举 + 系数 + **相干增益补偿**。纯算术，可单测 |
| `gpu/FftKernel.java` | compute 着色器 + 参数 + dispatch。**新核，不碰 `GPUFFT`** |
| `chartrender/SpectrumSeriesRenderer.java` | 跑 FFT + 用实例化机制画出频谱 |
| `src/test/java/com/bingbaihanji/xuan/gpu/FftWindowTest.java` | 窗与归一化的纯算术测试 |
| `src/main/kotlin/.../example/FftVerifier.kt` | 像素/数值校验器（**由 `GpuFftVerifier.kt` 改造而来**） |

**修改**

- `gl/GLAbstraction.java`、`gl/LwjglGLAbstraction.java`、`test/gl/FakeGLAbstraction.java` —— SSBO 抽象
- `test/gl/FakeGLAbstractionGuardTest.java` —— 新方法进清单
- `chart/ChartType.java` —— 加 `SPECTRUM`
- `chartrender/ChartRenderer.java` —— 分派 `SPECTRUM`
- `CLAUDE.md`、`README.md`

**不动**：`gpu/GPUFFT.java` —— 它是那三层缺陷的记录。

---

## Task 1: SSBO 抽象

**Files:**
- Modify: `src/main/java/com/bingbaihanji/xuan/gl/GLAbstraction.java`
- Modify: `src/main/java/com/bingbaihanji/xuan/gl/LwjglGLAbstraction.java`
- Modify: `src/test/java/com/bingbaihanji/xuan/gl/FakeGLAbstraction.java`
- Modify: `src/test/java/com/bingbaihanji/xuan/gl/FakeGLAbstractionGuardTest.java`

- [ ] **Step 1: 在 `GLAbstraction` 里加六个方法**

在 `deleteVbo` 之后插入：

```java
    /**
     * 创建一个通用缓冲对象。
     *
     * <p>与 {@link #createVbo()} 分开，是因为用途不同：那个固定绑 {@code GL_ARRAY_BUFFER}
     * 当顶点缓冲用，这个可以绑 {@code GL_SHADER_STORAGE_BUFFER} 给 compute 用。
     *
     * <p><strong>一个缓冲对象可以同时绑到多个靶子上</strong>——本项目的 FFT 正是靠这一点
     * 让 compute 写的缓冲直接被实例属性读走，不需要任何 GPU 侧拷贝。
     *
     * @return 缓冲对象的名字
     */
    int createBuffer();

    /** 把缓冲绑到 {@code GL_SHADER_STORAGE_BUFFER} 靶子。 */
    void bindShaderStorageBuffer(int buffer);

    /**
     * 为当前绑定的缓冲分配存储。
     *
     * <p>用法固定为 {@code GL_DYNAMIC_COPY}（compute 写、compute 读）。
     *
     * @param sizeBytes 字节数
     */
    void allocateBufferStorage(long sizeBytes);

    /**
     * 写到当前绑定的缓冲的指定偏移处，<strong>不重新分配</strong>。
     *
     * <p>与 {@code uploadVboSubData} 同理：越界写入是未定义行为。
     */
    void uploadBufferSubData(long offsetBytes, ByteBuffer data);

    /**
     * 把缓冲绑到某个 SSBO 绑定点（{@code glBindBufferBase}）。
     *
     * @param bindingIndex 着色器里 {@code layout(std430, binding = N)} 的那个 N
     */
    void bindBufferBase(int bindingIndex, int buffer);

    /** 删除缓冲对象。重复删除同一名字是未定义行为，调用方负责只删一次。 */
    void deleteBuffer(int buffer);
```

- [ ] **Step 2: `LwjglGLAbstraction` 加转发**

import 区加：

```java
import org.lwjgl.opengl.GL43;
```

在 VBO 那组转发之后插入：

```java
    @Override
    public int createBuffer() {
        return GL15.glGenBuffers();
    }

    @Override
    public void bindShaderStorageBuffer(int buffer) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
    }

    @Override
    public void allocateBufferStorage(long sizeBytes) {
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, sizeBytes, GL15.GL_DYNAMIC_COPY);
    }

    @Override
    public void uploadBufferSubData(long offsetBytes, ByteBuffer data) {
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, offsetBytes, data);
    }

    @Override
    public void bindBufferBase(int bindingIndex, int buffer) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, bindingIndex, buffer);
    }

    @Override
    public void deleteBuffer(int buffer) {
        GL15.glDeleteBuffers(buffer);
    }
```

- [ ] **Step 3: `FakeGLAbstraction` 跟上接口 —— 但**不要放开任何一个**

这六个方法**现在没有任何单元测试会用到**（Task 2 是纯算术，不碰 GL；Task 3 起都要真 GL）。
所以**全部写进文件末尾的抛异常区**：

```java
    @Override public int createBuffer() { throw new UnsupportedOperationException(); }
    @Override public void bindShaderStorageBuffer(int buffer) { throw new UnsupportedOperationException(); }
    @Override public void allocateBufferStorage(long sizeBytes) { throw new UnsupportedOperationException(); }
    @Override public void uploadBufferSubData(long offsetBytes, ByteBuffer data) { throw new UnsupportedOperationException(); }
    @Override public void bindBufferBase(int bindingIndex, int buffer) { throw new UnsupportedOperationException(); }
    @Override public void deleteBuffer(int buffer) { throw new UnsupportedOperationException(); }
```

> **这是「按需放开」纪律的直接应用**（Task 1 的先例）：每多放开一个方法，
> 就少一处"走偏了就报错"的护栏，而收益是零。**后面哪个任务真的需要，那时再放开那一个。**

- [ ] **Step 4: `FakeGLAbstractionGuardTest` 的两张清单都要更新**

把六个新方法加进 `MUST_THROW`（**不是** `IMPLEMENTED`）：

```java
            "createBuffer()", "bindShaderStorageBuffer(int)",
            "allocateBufferStorage(long)", "uploadBufferSubData(long,ByteBuffer)",
            "bindBufferBase(int,int)", "deleteBuffer(int)",
```

> **注意**：测试里的签名是 `getSimpleName()` 拼出来的。`long` 的 `getSimpleName()` 是 `"long"`，
> `ByteBuffer` 是 `"ByteBuffer"`。**跑一遍，以实际打印出来的签名串为准修正**——
> 这张清单本来就是被它自己钉着的。

- [ ] **Step 5: 编译并跑全量**

Run: `mvn -o test`
Expected: `Tests run: 309, Failures: 0, Errors: 0, Skipped: 2`
（308 + 保守估计 1；**以实际为准**——守卫测试可能只是改动既有方法而不新增测试）

若守卫测试失败，**按它报出来的实际签名修正清单**，不要改弱断言。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bingbaihanji/xuan/gl/GLAbstraction.java \
        src/main/java/com/bingbaihanji/xuan/gl/LwjglGLAbstraction.java \
        src/test/java/com/bingbaihanji/xuan/gl/FakeGLAbstraction.java \
        src/test/java/com/bingbaihanji/xuan/gl/FakeGLAbstractionGuardTest.java
git commit -F - <<'EOF'
feat(gl): 补 SSBO 抽象六个方法

compute 要用，而 gl/ 里此前一处 SSBO 都没有——它只以裸 LWJGL 调用存在于
gpu/GPUFFT.java 里，没有任何可复用抽象。

一个缓冲对象可以同时绑到多个靶子上，这是本子项目的关键手法：compute 写的
缓冲直接被实例属性读走，不需要任何 GPU 侧拷贝。

FakeGLAbstraction 里这六个全部继续抛异常——现在没有任何单测会用到它们，
按"按需放开"的纪律不预先卸护栏。守卫测试的两张清单同步更新。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 2: `FftWindow` —— 窗与相干增益（纯算术，TDD）

**Files:**
- Create: `src/main/java/com/bingbaihanji/xuan/gpu/FftWindow.java`
- Test: `src/test/java/com/bingbaihanji/xuan/gpu/FftWindowTest.java`

### ⚠️ 这一节的核心：**不要抄参考项目的系数**

规格 §6.3 与 R4 记着：参考项目 ngscopeclient **有一个被测试掩盖的 BH 窗公式 bug**
（它把 `cos(3*num)` 写成 `cos(6*num)`，而它的单元测试**照抄了同一个公式**，所以抓不到）。

它的窗增益系数是 **2.013 / 1.862 / 2.805**——而教科书值应当是 **2.0 / 1.852 / …**
（Hann 的相干增益恰好是 `0.5`，补偿就是 `2.0`）。
**两者对不上，而我不知道原因。** 所以本任务：

- **系数一律从题目推导**（`coherentGain = mean(w)`，`compensation = 1/coherentGain`）
- **绝不在代码或测试里写死 2.013 这类数字**
- **断言落在物理性质上**，见 Step 1 的 `补偿后峰值与不加窗时一致`

- [ ] **Step 1: 写测试**

创建 `src/test/java/com/bingbaihanji/xuan/gpu/FftWindowTest.java`：

```java
package com.bingbaihanji.xuan.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FftWindow} 的单元测试。**纯算术，零 GL 依赖。**
 *
 * <h2>为什么断言落在物理性质上，而不是一组魔数</h2>
 * <p>参考项目 ngscopeclient 的窗增益系数是 2.013 / 1.862 / 2.805，而教科书值应当是
 * 2.0 / 1.852 / …。<b>两者对不上，原因不明</b>——而那份代码里确实有一个被测试掩盖的
 * BH 窗公式 bug（它和它的黄金模型犯了同一个错）。
 *
 * <p>所以这里不写死任何系数：<b>系数由定义推导，断言检验它的物理含义</b>——
 * 「加窗会让谱峰变矮，乘上补偿之后应当回到不加窗的高度」。
 * 一个错的窗公式会同时改变这两边，这条断言因此抓得住它。
 */
class FftWindowTest {

    private static final int N = 512;

    /**
     * 单频信号的谱峰幅度（未归一化）：对单位幅度余弦做 DFT，峰在 k0 处为 N/2。
     *
     * <p>这里的 {@code re} / {@code im} 是 {@code X[k0] = Σ w·x·exp(−i·angle)} 的实虚部，
     * 其中 {@code x = cos(angle)}、{@code angle = 2πk₀i/n}。
     */
    private static double peakOf(FftWindow window, int n, double k0) {
        double re = 0, im = 0;
        for (int i = 0; i < n; i++) {
            double w = window.coefficient(i, n);
            double angle = 2.0 * Math.PI * k0 * i / n;
            double x = Math.cos(angle);
            re += w * x * Math.cos(angle);
            im -= w * x * Math.sin(angle);
        }
        return Math.hypot(re, im);
    }

    @Test
    void 每个窗的系数都在零到一之间() {
        for (FftWindow w : FftWindow.values()) {
            for (int i = 0; i < N; i++) {
                double c = w.coefficient(i, N);
                assertTrue(c >= 0.0 && c <= 1.0,
                        w + " 在 i=" + i + " 处的系数越界：" + c);
            }
        }
    }

    @Test
    void 每一个窗都关于中心对称() {
        // 对称性是窗函数的定义性质之一。写错分母（N 与 N-1 之分会破坏它，
        // 而那种错在谱上只表现为"主瓣略宽"，肉眼看不出来。
        for (FftWindow w : FftWindow.values()) {
            for (int i = 0; i < N / 2; i++) {
                assertEquals(w.coefficient(i, N), w.coefficient(N - 1 - i, N), 1e-12,
                        w + " 不对称：i=" + i);
            }
        }
    }

    @Test
    void 矩形窗处处为一() {
        for (int i = 0; i < N; i++) {
            assertEquals(1.0, FftWindow.RECTANGULAR.coefficient(i, N), 1e-12);
        }
    }

    @Test
    void 相干增益与补偿互为倒数() {
        for (FftWindow w : FftWindow.values()) {
            double gain = w.coherentGain(N);
            assertEquals(1.0, gain * w.compensation(N), 1e-12,
                    w + " 的增益与补偿不互为倒数");
            assertTrue(gain > 0.0, w + " 的相干增益必须为正");
        }
    }

    @Test
    void 相干增益在常量信号上精确成立() {
        // 常量信号的能量全在 bin 0，而 bin 0 就是系数之和——**没有任何泄漏**，
        // 所以这条可以钉得很紧（1e-9）。它精确地把"相干增益"这个量本身钉住。
        for (FftWindow w : FftWindow.values()) {
            double sum = 0;
            for (int i = 0; i < N; i++) {
                sum += w.coefficient(i, N);
            }
            assertEquals(sum, w.coherentGain(N) * N, 1e-9,
                    w + " 的相干增益不是系数之和除以长度");
            assertEquals(sum, peakOf(w, N, 0.0), 1e-9,
                    w + " 在常量信号上的 bin 0 应当精确等于系数之和");
        }
    }

    @Test
    void 补偿后谱峰回到不加窗的高度() {
        // ★ 这是把"窗写对了"与"窗写错了"分开的断言。
        //
        // ⚠️ **容差是量出来的，不是推出来的。** 这个量展开是：
        //
        //     peakOf(w, N, k0) = |Σ w·cos(θ)·e^{-iθ}| = ½·|W[0] + W[2k0]|
        //
        // 第二项**不是零**——它是窗函数自身在 bin 2k₀ 处的谱值。所以补偿之后
        // 会留下一个残差。**这个残差有多大，不要靠"旁瓣是多少 dB"去推**：
        // 那样会把它当成该窗的**最高**旁瓣（Hann 是 −31 dB），
        // 而 bin 2k₀ 落在旁瓣裙边的深处，实际低得多。
        //
        // **实测的残差（N=512, k0=7，逐条跑出来的）**：
        //     矩形 0%   Hann 0.0010%   Hamming 0.0009%   BH 0.0001%
        //
        // 所以取 **1e-4（0.01%）**：比实测的最大残差宽 10 倍，
        // 又比"补偿写成 1/(1+g) 之类"的误差小两个数量级，两头都不擦边。
        //
        // ⚠️ **不要把它改回 1e-9**——那个值**每条都会失败**（实测残差比它大 4 个数量级），
        // 而失败信息会指向"窗增益补偿写错了"，把人引到完全错误的方向。
        int k0 = 7;
        double unwindowed = peakOf(FftWindow.RECTANGULAR, N, k0);
        for (FftWindow w : FftWindow.values()) {
            double compensated = peakOf(w, N, k0) * w.compensation(N);
            assertEquals(unwindowed, compensated, unwindowed * 1e-4,
                    w + " 补偿后的谱峰与不加窗时差得太远——窗增益补偿写错了");
        }
    }

    @Test
    void 布莱克曼哈里斯的旁瓣比汉宁低得多() {
        // 规格选 BH 作默认，理由是"大载波旁边看小谐波"。这条把它钉住：
        // 若有人把 BH 的系数抄错（例如抄成参考项目那个 cos(6*num)），
        // 旁瓣会显著抬高，这条会失败。
        double bh = sidebandFloor(FftWindow.BLACKMAN_HARRIS);
        double hann = sidebandFloor(FftWindow.HANN);
        assertTrue(bh < hann * 0.1,
                "BH 的旁瓣应当比 Hann 低至少一个量级：BH=" + bh + " Hann=" + hann);
    }

    /** 取 k0=17 时，离主瓣足够远处的最大谱幅度（近似旁瓣底）。 */
    private static double sidebandFloor(FftWindow w) {
        int n = 1024;
        double peak = 0;
        for (int k = 40; k <= n / 2; k++) {
            double re = 0, im = 0;
            for (int i = 0; i < n; i++) {
                double c = w.coefficient(i, n);
                double a = 2.0 * Math.PI * (17 - k) * i / n;
                re += c * Math.cos(a);
                im += c * Math.sin(a);
            }
            peak = Math.max(peak, Math.hypot(re, im));
        }
        return peak / (n / 2.0);
    }

    @Test
    void 退化长度明确报错而不是返回无穷大() {
        // ★ 原稿这条写的是 `assertTrue(w.compensation(2) > 0.0)`——**对 HANN 是恒真的**：
        //    它在 n=2 时两个系数都是 0，相干增益为 0，compensation 返回 Infinity，
        //    而 `Infinity > 0.0` 照样成立。那是一条橡皮图章。
        //
        // 现在改成断言"它会响亮地失败"——因为静默返回一个无穷大，
        // 那个值会一路传到顶点位置上，而画面只是"有点怪"。
        for (FftWindow w : FftWindow.values()) {
            assertThrows(IllegalArgumentException.class, () -> w.coherentGain(1),
                    w + " 在 n=1 上应当明确报错");
        }

        // HANN 在 n=2 上两个系数都是 0（0.5-0.5·cos(0) 与 0.5-0.5·cos(2π)），
        // 是会退化的那一类；其余窗在这个长度上仍然是正增益。
        assertThrows(IllegalArgumentException.class, () -> FftWindow.HANN.coherentGain(2),
                "HANN 在 n=2 上零增益，必须报错而不是返回 Infinity");
        for (FftWindow w : FftWindow.values()) {
            if (w != FftWindow.HANN) {
                assertTrue(w.compensation(2) > 0.0 && Double.isFinite(w.compensation(2)),
                        w + " 在 n=2 上应当给出正有限增益");
            }
        }
    }

    @Test
    void 枚举序号必须与着色器的窗口编号一致() {
        // ★ 这条钉的是一处**跨语言的位置耦合**：Java 侧用 ordinal() 传参，
        //    GLSL 侧（FftKernel 的 windowAt）用硬编码的 0/1/2/3 分支。
        //
        // 一旦有人往枚举**中间**插一个窗，Java 侧 compensation 算的是新序号的窗，
        // 而着色器乘的是另一个窗——**幅度读数差一点，画面上完全看不出来**。
        //
        // ⚠️ 而那条"换窗不改变读数"的断言**抓不到这个**：它观测的是"换窗后读数
        //    是否相同"，**两边一起错的时候反而自洽**。那条防的是"补偿写错"，
        //    防不了"两边一起错"。所以这里必须单独钉一次顺序。
        assertEquals(0, FftWindow.RECTANGULAR.ordinal(), "着色器里 0 是矩形窗");
        assertEquals(1, FftWindow.HANN.ordinal(), "着色器里 1 是 Hann");
        assertEquals(2, FftWindow.HAMMING.ordinal(), "着色器里 2 是 Hamming");
        assertEquals(3, FftWindow.BLACKMAN_HARRIS.ordinal(), "着色器里 3 是 BH");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o test -Dtest=FftWindowTest`
Expected: 编译失败（`FftWindow` 不存在）。

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/bingbaihanji/xuan/gpu/FftWindow.java`：

```java
package com.bingbaihanji.xuan.gpu;

/**
 * FFT 的窗函数，以及它的<strong>相干增益补偿</strong>。
 *
 * <h2>为什么必须补偿</h2>
 * <p>加窗会让谱峰变矮——变矮的比例就是窗的<b>相干增益</b>（系数的平均值）。
 * 不补偿的话，<b>用户换一个窗、幅度读数就变了</b>，而画面上完全看不出来：
 * 谱的形状是对的，只是整体矮了一截。这是典型的静默错误。
 *
 * <h2>为什么这里不写死具体系数</h2>
 * <p>参考项目 ngscopeclient 的系数是 2.013 / 1.862 / 2.805，而教科书值应当是
 * 2.0 / 1.852 / …——<b>两者对不上，原因不明</b>。而那份代码里确实有一个被它的单元测试
 * 掩盖的 BH 窗公式 bug（测试的黄金模型照抄了同一个公式）。
 *
 * <p>所以本类<b>一律从定义推导</b>：{@code coherentGain = mean(w)}，
 * {@code compensation = 1 / coherentGain}。<b>不要替换成任何写死的魔数</b>。
 *
 * <h2>默认为什么是 Blackman-Harris</h2>
 * <p>示波器场景常要在<b>大载波旁边看小谐波</b>。BH 的旁瓣比 Hann 低一个量级以上，
 * 而旁瓣高看起来像"底噪抬起来了"，不像 bug。
 */
public enum FftWindow {

    /** 矩形窗（等价于不加窗）。主瓣最窄、旁瓣最高（−13 dB）。 */
    RECTANGULAR {
        @Override
        public double coefficient(int i, int n) {
            return 1.0;
        }
    },

    /** Hann 窗，系数 0.5 − 0.5·cos(2πi/(N−1))。旁瓣 −31 dB。 */
    HANN {
        @Override
        public double coefficient(int i, int n) {
            return 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1.0));
        }
    },

    /** Hamming 窗，系数 0.54 − 0.46·cos(2πi/(N−1))。旁瓣 −43 dB。 */
    HAMMING {
        @Override
        public double coefficient(int i, int n) {
            return 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (n - 1.0));
        }
    },

    /**
     * Blackman-Harris 窗（四项），<b>默认</b>。旁瓣 −92 dB。
     *
     * <p><strong>三项系数必须用教科书的 0.35875 / 0.48829 / 0.14128 / 0.01168，
     * 且第三项是 {@code cos(4πi/(N−1))}、第四项是 {@code cos(6πi/(N−1))}。</strong>
     * 参考项目把它写成了 {@code cos(6·num)}（其中 {@code num = 2πi/N}），
     * 于是实际算的是 {@code cos(12πi/N)}——<b>而它的单元测试照抄了同一个公式，抓不到</b>。
     */
    BLACKMAN_HARRIS {
        @Override
        public double coefficient(int i, int n) {
            double x = 2.0 * Math.PI * i / (n - 1.0);
            return 0.35875
                    - 0.48829 * Math.cos(x)
                    + 0.14128 * Math.cos(2.0 * x)
                    - 0.01168 * Math.cos(3.0 * x);
        }
    };

    /**
     * 第 {@code i} 个样本上的窗系数。
     *
     * @param i 样本下标，{@code [0, n)}
     * @param n 窗长
     */
    public abstract double coefficient(int i, int n);

    /**
     * 相干增益：系数的平均值。
     *
     * <p><b>由定义求和得到，不要替换成写死的常数。</b>
     *
     * @throws IllegalArgumentException {@code n < 2}，或该窗在这个长度上退化成非正/非有限的增益
     *         （例如 HANN 在 {@code n = 2} 时两个系数都是 0——<b>那种窗会把信号整个抹掉，
     *         补偿是无穷大，静默返回它会一路传到顶点位置上</b>）
     */
    public double coherentGain(int n) {
        if (n < 2) {
            throw new IllegalArgumentException(
                    "窗长必须 ≥ 2，实际 " + n + "（n<2 时窗在多数定义下退化，本类一律拒绝）");
        }
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += coefficient(i, n);
        }
        double gain = sum / n;
        if (!(gain > 0.0) || !Double.isFinite(gain)) {
            throw new IllegalArgumentException(
                    this + " 在 n=" + n + " 上的相干增益不是正有限数：" + gain
                            + "。这种窗会把信号整个抹掉，补偿没有意义——"
                            + "明确报错而不是返回无穷大（那会静默传到顶点位置上）。");
        }
        return gain;
    }

    /** 相干增益补偿：乘上它之后，谱峰回到不加窗时的高度。 */
    public double compensation(int n) {
        return 1.0 / coherentGain(n);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o test -Dtest=FftWindowTest`
Expected: 全部通过（7 条）。

> **若 `布莱克曼哈里斯的旁瓣比汉宁低得多` 失败**：先怀疑你写的 BH 系数，
> **不要**先去放宽这条断言。它存在的全部理由就是抓 BH 公式写错。

- [ ] **Step 5: 变异验证（逐条单独注入，**按条件不按函数**）**

| 变异 | 期望失败的断言 |
|---|---|
| HANN 的分母从 `n-1.0` 改成 `n` | `每一个窗都关于中心对称` |
| BH 的第三项 `Math.cos(2.0 * x)` 改成 `Math.cos(2.0 * x * 2)`（**复现参考项目那个 bug**） | `布莱克曼哈里斯的旁瓣比汉宁低得多` |
| `compensation` 改成 `coherentGain`（不取倒数） | `补偿后谱峰回到不加窗的高度` |
| BH 的系数 0.35875 改成 0.5 | **`每个窗的系数都在零到一之间`**（BH 在 i=214 处系数 1.0004268 > 1） |

> ### ⚠️ 实施期更正：第 4 格的预测我曾写错，而且**在数学上不可能成立**
>
> 原稿写的是"BH 的 0.35875 改成 0.5 → `补偿后谱峰回到不加窗的高度` 必须失败"。
> **那条断言不可能响**，理由两条，都是恒等式而非巧合（实施者给出，我核过）：
>
> 1. **旁瓣底一字不差**：把窗系数整体加常数 c，等价于 `W'[k] = W[k] + c·N·δ[k]`；
>    而 `sidebandFloor` 遍历的是 bin `(17−k)`（k 从 40 到 512），**没有一个等于 0**——
>    直流项碰不到这些 bin。
> 2. **补偿后谱峰也对得上**：`compensation` 是**从定义自洽推导**的（`1/mean(w)`），
>    **任何正系数集都满足"补偿后回到不加窗高度"**——那恰恰是本设计的**目标性质**，
>    不是漏洞。
>
> **这条变异仍然要注入**，但期望是**被系数越界断言抓住**。
>
> **教训**：写变异表时问的不该是"改这里会不会有问题"，而是
> **"这条断言观测的量，真的会因为这个变异而变吗"**。这一格我按前者写的，所以错了。

> ### 实施期补的一条：退化窗长必须明确报错
>
> 原稿的 `长度为一与二时不炸` 里写的是 `assertTrue(w.compensation(2) > 0.0)`——
> **对 HANN 恒真**：它在 `n=2` 时两个系数都是 0，相干增益为 0，
> `compensation` 返回 `Infinity`，而 `Infinity > 0.0` 照样成立。
>
> **那是一条橡皮图章**（实施者自查发现）。已改成两条：`coherentGain` 加前置条件
> （`n < 2` 或增益不是正有限数就抛），测试改成断言"**它会响亮地失败**"。
>
> 理由与本项目一贯的判据一致：**静默返回一个无穷大，那个值会一路传到顶点位置上，
> 而画面只是"有点怪"。**

> ### 实施期补的第二条：枚举序号是跨语言的位置耦合，没有东西钉住它
>
> `FftWindow.ordinal()` 传给着色器的 `u_WindowKind`，而着色器侧是**硬编码的 0/1/2/3 分支**。
> 现在对得上，**但两侧都没有任何东西钉住它**——往枚举中间插一个窗，
> Java 侧算的是新序号的窗、着色器乘的是另一个窗，
> **幅度读数差一点，画面上完全看不出来**。
>
> **⚠️ 而本计划 Task 4 那条"换窗不改变读数"的变异断言抓不到它**：
> 它观测的是"换窗后读数是否相同"，**两边一起错的时候反而自洽**。
> 那条防的是"补偿写错"，**防不了"两边一起错"**。
>
> 所以 `FftWindowTest` 里必须单独有一条顺序断言（见上面的代码块）。
> **变异验证**：往枚举中间插一个哑成员，那条必须失败。

**任何一条存活都要停下来如实报告。** 跑完务必还原，`git diff` 确认干净。

> 第二行那条变异**特别重要**：它复现的正是参考项目那个**被测试掩盖**的 bug。
> 如果你注入它而测试**没响**，说明本测试类是橡皮图章——**那比 BH 写错更严重**。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bingbaihanji/xuan/gpu/FftWindow.java \
        src/test/java/com/bingbaihanji/xuan/gpu/FftWindowTest.java
git commit -F - <<'EOF'
feat(gpu): FFT 窗函数与相干增益补偿（纯算术）

加窗会让谱峰变矮，变矮的比例就是窗的相干增益。不补偿的话，用户换一个窗、
幅度读数就变了——而画面上完全看不出来，谱的形状是对的，只是整体矮一截。

系数一律从定义推导（coherentGain = mean(w)，compensation = 1/它），
不写死任何魔数：参考项目 ngscopeclient 的系数是 2.013/1.862/2.805，
而教科书值应当是 2.0/1.852/…，两者对不上且原因不明——而那份代码里确实
有一个被它自己的单元测试掩盖的 BH 窗公式 bug（黄金模型照抄了同一公式）。

所以断言落在物理性质上：补偿后的谱峰必须回到不加窗时的高度。
一个错的窗公式会同时改变两边，这条因此抓得住它。

变异验证里专门有一条复现参考项目那个 bug，用来证明本测试不是橡皮图章。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 3: `FftKernel` —— compute 核

**Files:**
- Create: `src/main/java/com/bingbaihanji/xuan/gpu/FftKernel.java`

> **这个任务没有单测**——它要真 GL 上下文。验证渠道是 Task 4 的校验器。
> **不要为了"有测试"去写测试。**

- [ ] **Step 1: 创建 `FftKernel`**

```java
package com.bingbaihanji.xuan.gpu;

import com.bingbaihanji.xuan.gl.GLAbstraction;
import com.bingbaihanji.xuan.util.Disposable;

/**
 * GPU 上的 FFT：<strong>一次 dispatch 干完全部</strong>
 * （取数 → 加窗 → 位反转 → log2(N) 级蝶形 → 幅度）。
 *
 * <h2>三个设计决定，都来自 {@code GPUFFT} 实测出来的缺陷</h2>
 * <ol>
 *   <li><strong>位反转是着色器内部的第一步</strong>，不依赖任何外部步骤。
 *       {@code GPUFFT} 算了位反转索引却从没引用过它——DIT 蝶形要求输入先位反转，
 *       所以它的输出等于 {@code DFT(输入按位反转)}，峰值落在错误的 bin 上。</li>
 *   <li><strong>所有 uniform 声明成 {@code int}</strong>，与
 *       {@code ShaderProgram.setUniform(String,int)} 的 {@code glUniform1i} 配对。
 *       {@code GPUFFT} 声明成 {@code uint}，于是 {@code GL_INVALID_OPERATION}
 *       <strong>且值不生效</strong> → {@code u_N} 恒为 0 → 每次都提前返回，
 *       输出逐位等于输入。（同一个坑在 {@code uPickId} 上也踩过。）</li>
 *   <li><strong>不许用 GLSL 保留字当标识符</strong>。{@code GPUFFT} 用了
 *       {@code half}，着色器编译不过——<strong>它从未成功运行过一次</strong>，
 *       而这件事很久都没人发现。</li>
 * </ol>
 *
 * <h2>为什么是单个 workgroup</h2>
 * <p>全部在 shared memory 里做，{@code barrier()} 分隔各级。好处是位反转天然发生在
 * 着色器内部（见上）、且免去 {@code log2(N)} 次 dispatch 与内存屏障的 CPU 开销。
 * <b>代价是 N 有上限</b>：shared memory 要放 {@code 2×N} 个 float，
 * N=4096 时是 32 KB（安全），N=8192 就是 64 KB（超了）。<b>所以上限是 4096。</b>
 *
 * <h2>输入是环形缓冲</h2>
 * <p>时域样本存在 ② 的环形缓冲里，{@code N} 个样本可能跨过环的接缝，
 * 所以取数要 {@code & (cap-1)} 取模。<strong>这与 ② 的"槽位 0 镜像"是同一类问题</strong>
 * ——那里没处理，产出过一段"掉到 0 的假信号"。
 */
public final class FftKernel implements Disposable {

    /** 线程组大小。同时是 shared memory 里数组的长度上限。 */
    private static final int LOCAL_SIZE = 1024;

    /** 本核支持的最大变换长度。由 shared memory 决定，见类文档。 */
    public static final int MAX_N = 4096;

    /** 本核支持的最小变换长度。 */
    public static final int MIN_N = 256;

    private static final String SHADER = """
            #version 430
            layout(local_size_x = 1024) in;

            layout(std430, binding = 0) readonly  buffer InputBuffer  { float inY[]; };
            layout(std430, binding = 1) writeonly buffer OutputBuffer { float outMag[]; };

            // 全部是 int —— setUniform 走 glUniform1i。声明成 uint 会静默失效。
            uniform int   u_N;            // 变换长度（2 的幂，256..4096）
            uniform int   u_RingCapacity; // 环形缓冲容量（2 的幂）
            uniform int   u_RingStart;    // 第一个样本在环里的槽位
            uniform int   u_WindowKind;   // 0=矩形 1=Hann 2=Hamming 3=BH
            uniform float u_Scale;        // 2/N × 窗补偿

            // 注意：不要用 half 当变量名 —— 它是 GLSL 保留字。
            shared float sRe[4096];
            shared float sIm[4096];

            float windowAt(int i) {
                float x = 6.283185307179586 * float(i) / float(u_N - 1);
                if (u_WindowKind == 0) return 1.0;
                if (u_WindowKind == 1) return 0.5 - 0.5 * cos(x);
                if (u_WindowKind == 2) return 0.54 - 0.46 * cos(x);
                return 0.35875 - 0.48829 * cos(x) + 0.14128 * cos(2.0 * x) - 0.01168 * cos(3.0 * x);
            }

            void main() {
                int tid = int(gl_LocalInvocationID.x);
                int n   = u_N;
                int cap = u_RingCapacity;

                int logN = 0;
                for (int t = n; t > 1; t >>= 1) logN++;

                // ① 取数 + 加窗 + 位反转，一次做完。
                //    bitfieldReverse 反转全部 32 位，右移掉高位即得 logN 位的反转。
                //
                //    ⚠️ 移位必须在 **uint 域**里做完再转 int。
                //    写成 `int(bitfieldReverse(...)) >> (32 - logN)` 是错的：
                //    反转之后**最高位几乎总是 1**（i 的最低位变成了最高位），
                //    转成 int 就是负数，而 GLSL 对**有符号**左操作数的 >> 是**算术右移**
                //    （符号扩展）。例如 i=1 时得到 0xFFFFFC00 = **-1024** 而不是 1024，
                //    于是 sRe[rev] 用一个**负下标**写共享内存——那是**越界写**，
                //    驱动可能崩、也可能悄悄写坏别处。
                for (int i = tid; i < n; i += 1024) {
                    int src = (u_RingStart + i) & (cap - 1);
                    int rev = int(bitfieldReverse(uint(i)) >> uint(32 - logN));
                    sRe[rev] = inY[src] * windowAt(i);
                    sIm[rev] = 0.0;
                }
                barrier();

                // ② log2(N) 级蝶形（DIT，输入已位反转）
                for (int len = 2; len <= n; len <<= 1) {
                    int halfLen = len >> 1;
                    for (int k = tid; k < n / 2; k += 1024) {
                        int group = k / halfLen;
                        int pos   = k % halfLen;
                        int k1    = group * len + pos;
                        int k2    = k1 + halfLen;
                        float angle = -6.283185307179586 * float(pos) / float(len);
                        float wr = cos(angle);
                        float wi = sin(angle);
                        float tr = wr * sRe[k2] - wi * sIm[k2];
                        float ti = wr * sIm[k2] + wi * sRe[k2];
                        float ur = sRe[k1];
                        float ui = sIm[k1];
                        sRe[k1] = ur + tr;
                        sIm[k1] = ui + ti;
                        sRe[k2] = ur - tr;
                        sIm[k2] = ui - ti;
                    }
                    barrier();
                }

                // ③ 幅度（半谱，含 DC 与 Nyquist）
                for (int k = tid; k <= n / 2; k += 1024) {
                    float re = sRe[k];
                    float im = sIm[k];
                    outMag[k] = sqrt(re * re + im * im) * u_Scale;
                }
            }
            """;

    private final GLAbstraction gl;
    private final ComputeShader shader;
    private final int outputBuffer;
    private final int n;

    /**
     * @param gl           GL 抽象层
     * @param n            变换长度，<strong>必须是 2 的幂且落在 [256, 4096]</strong>
     * @param ringCapacity 源环形缓冲的容量，必须是 2 的幂
     * @throws IllegalArgumentException 参数不合法
     */
    public FftKernel(GLAbstraction gl, int n, int ringCapacity) {
        if (n < MIN_N || n > MAX_N || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException(
                    "FFT 长度必须是 2 的幂且落在 [" + MIN_N + ", " + MAX_N + "]：实际 " + n
                            + "。上限由 shared memory 决定（2×N 个 float）。");
        }
        if (ringCapacity <= 0 || (ringCapacity & (ringCapacity - 1)) != 0) {
            throw new IllegalArgumentException("环容量必须是 2 的幂，实际 " + ringCapacity);
        }
        this.gl = gl;
        this.n = n;
        this.shader = new ComputeShader(SHADER);
        this.outputBuffer = gl.createBuffer();
        gl.bindShaderStorageBuffer(outputBuffer);
        gl.allocateBufferStorage((long) (n / 2 + 1) * Float.BYTES);
        gl.bindShaderStorageBuffer(0);
    }

    /** FFT 输出的 bin 数（半谱，含 DC 与 Nyquist）。 */
    public int binCount() {
        return n / 2 + 1;
    }

    /** 输出缓冲的名字。它同时可以被绑成顶点缓冲，供实例化绘制读走。 */
    public int outputBufferId() {
        return outputBuffer;
    }

    /**
     * 对环形缓冲里从 {@code ringStart} 起的 {@code n} 个样本做 FFT。
     *
     * @param inputBuffer  源环形缓冲的名字（② 的 {@code SeriesBuffer} 的 VBO）
     * @param ringCapacity 环容量
     * @param ringStart    第一个样本在环里的槽位
     * @param window       窗函数
     */
    public void execute(int inputBuffer, int ringCapacity, int ringStart, FftWindow window) {
        shader.use();
        shader.setUniform("u_N", n);
        shader.setUniform("u_RingCapacity", ringCapacity);
        shader.setUniform("u_RingStart", ringStart);
        shader.setUniform("u_WindowKind", window.ordinal());
        // 半谱的归一化：单位幅度余弦的谱峰是 N/2，所以要 ×2/N 才能读回 1.0；
        // 再乘窗补偿，于是换窗不改变读数。
        shader.setUniform("u_Scale", (float) (2.0 / n * window.compensation(n)));
        gl.bindBufferBase(0, inputBuffer);
        gl.bindBufferBase(1, outputBuffer);
        shader.dispatch(1, 1, 1);
        shader.memoryBarrier();
        shader.unuse();
    }

    @Override
    public void dispose() {
        shader.dispose();
        gl.deleteBuffer(outputBuffer);
    }
}
```

> ### ⚠️ 硬约束：**必须建在 `GLAbstraction` 上，不许裸调 LWJGL**
>
> `gpu/GPUFFT.java` 是**静态导入 `org.lwjgl.opengl.GL43.*` 直接调 GL** 的——
> 那正是它"挂在抽象层之外、没人发现它编译不过"的原因之一。**不要重蹈。**
>
> 这条不只是洁癖：**它让 SSBO 那六个方法的执行验证变成免费的副产品。**
> Task 1 的复核算过，那六个里**只有两条能"编译通过且静默错"**——
> `bindBufferBase` 的 index/buffer 互换（两个都是 `int`，编译器无话可说）、
> 以及靶子常量用错。而只要 FFT **走抽象**，
> **这两条错了必然算出错的数值**，Task 4 的 CPU 参考 DFT 就会红。
>
> **若你改成裸调 LWJGL，这两条就再也没有任何执行验证了。**
>
> ### ⚠️ 同步：`memoryBarrier` 不能漏
>
> compute 写完到顶点属性读走之间**必须插一次 `glMemoryBarrier`**，
> 否则读到旧值——**数值错，不报任何 GL 错误**。
> **现成的够用**：`ComputeShader.memoryBarrier()` 走的是 `GL_ALL_BARRIER_BITS`，
> SSBO 与顶点属性两种屏障都覆盖，**不必给抽象补第七个方法**。
>
> **⚠️ 两处你要自己核实**（我只核过签名，没核过用法）：
> 1. **`ComputeShader` 的实际 API**——`use()` / `unuse()` / `dispatch(x,y,z)` /
>    `memoryBarrier()` / `setUniform(String,int)` / `dispose()` 我按 `gpu/ComputeShader.java`
>    的公开面写的，**但 `dispatch` 的参数是"组数"还是"线程数"要去读它的实现**。
> 2. **`bitfieldReverse` 的可用性**——它是 GLSL 4.00 的内建函数，`#version 430` 下应当可用，
>    但**编译一次确认**（这正是缺陷①的教训：编译不过的东西可以躺很久没人发现）。
>
> 若实际与上面不符，**以实际为准改，并在报告里说明**。

- [ ] **Step 2: 编译并确认着色器能编译**

Run: `mvn -o compile`
Expected: `BUILD SUCCESS`

> **编译通过不等于着色器能编译**——GLSL 是运行时编译的。
> `ComputeShader` 的构造在编译失败时应当抛异常；**真正的确认在 Task 4 的校验器**。

- [ ] **Step 3: 跑全量测试确认没碰坏别的**

Run: `mvn -o test`
Expected: 与 Task 2 结束时相同的数字，0 失败。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bingbaihanji/xuan/gpu/FftKernel.java
git commit -F - <<'EOF'
feat(gpu): FFT 计算核——一次 dispatch 干完全部

三个设计决定直接来自 GPUFFT 实测出来的三层缺陷：

1. 位反转是着色器内部的第一步，不依赖任何外部步骤。GPUFFT 算了位反转
   索引却从没引用它，于是输出等于 DFT(输入按位反转)，峰值落在错误的 bin。
2. 所有 uniform 声明成 int，与 glUniform1i 配对。GPUFFT 声明成 uint，
   于是 GL_INVALID_OPERATION 且值不生效，u_N 恒为 0，输出逐位等于输入。
3. 不用 GLSL 保留字当标识符——GPUFFT 用了 half 当变量名，编译不过，
   而它从未成功运行过一次却很久没人发现。

单个 workgroup 全在 shared memory 里做，省掉 log2(N) 次 dispatch；
代价是 N 上限 4096（2×N 个 float = 32 KB）。

归一化用 2/N 再乘窗补偿：单位幅度余弦的谱峰读回 1.0，且换窗不改变读数。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 4: 校验器 —— 把"算对了"变成断言

**Files:**
- Rename+Modify: `src/main/kotlin/com/bingbaihanji/xuan/example/GpuFftVerifier.kt` → `FftVerifier.kt`

> **改造而不是新写**：规格 §7.2 说清了理由——**一个预期永远红的校验器是负债**，
> 人一旦习惯"它红是正常的"，它就再也不起 gate 作用。`GPUFFT` 那三层缺陷的记录
> 已经落在 `CLAUDE.md` 的「未实现」一节与提交 `adab313` 里。
>
> **文件头要留一句指回它们的说明。**
>
> **保留它已经证明有效的判据**——尤其是"与 CPU 参考逐 bin 比对"，
> 以及那个"最小 kernel 只换 uniform 类型"的对照实验（它把缺陷②钉死过）。

> ## ⚠️ Task 3 实施期实测出来的四条，**直接决定 Task 4 的判据怎么写**
>
> ### 一、**不要照抄 `GpuFftVerifier` 的 `SIDE_LOBE_LIMIT = 1%`**
>
> 那条在**矩形窗**下成立（实测非峰 5.99e-08），但**对 Hann / Hamming / BH 会直接失败**：
> 窗本身把能量摊开，实测非峰最大是 **0.503 / 0.428 / 0.683**（相对峰高 1.0）——
> 而它们与 CPU 参考的偏差只有 **1e-7**。
>
> **那是窗的旁瓣，不是缺陷。**
>
> **所以窗下的判据只能是与 CPU 参考（同一套窗系数、同一个缩放）逐 bin 比对**，
> **不能用"非峰处应当接近零"这种绝对阈值**。照抄那条会得到一次莫名其妙的失败。
>
> ### 二、**"跨接缝一致性"是弱断言，不能单独用**
>
> Task 3 的探针里有一条"跨接缝（`ringStart=924`）与 `ringStart=0` 的谱一致"。
> 实测：**把 `bindBufferBase` 的 index/buffer 互换后，它照样 PASS**——
> 因为输出**全是 0**，而 `0 == 0` 恒真。
>
> **一致性判据在"整条路径都坏了但坏得一致"时恒真。**
> **它只有与 CPU 参考并行才有意义。**
>
> （这与 Task 2 那条"两边一起错时反而自洽"是**同一个形态**。）
>
> ### 三、`setUniform` 对**不存在的 uniform 名字是静默无效**
>
> `glGetUniformLocation` 返回 −1，而 `glUniform1i(-1, …)` 不报错。
> 本次靠"与 CPU 参考逐 bin 比对"兜住了（任何 uniform 失效都会让输出全 0
> 或退回矩形窗结果），**但这是个已知盲区**：
> 只做"峰值在正确的 bin"这类断言的话，**uniform 名字写错不会被发现**。
>
> ### 四、**共享内存越界写不会崩、也不报错**
>
> Task 3 的变异（把位反转的移位挪进有符号域，制造负下标越界写共享内存）实测：
> **峰值变成 0.5 而非 1.0，不崩、无 GL 错误。**
> NVIDIA 上越界写共享内存是**完全静默的**——比"崩溃"更难发现。
> 这是"共享内存尺寸必须与常量机械绑定"那条修复的理由。
>
> ### 五、**必须把 `MAX_N` 那个长度也测上**
>
> 上限处是**共享内存的边界**，而边界正是这类缺陷唯一会现形的地方。
> Task 3 实测：**`n=256` 时把绑定改坏了也看不出来**（那份数组够大，越界不发生），
> 而 `n=4096`（上限）时同样的破坏**立刻显形**（峰值跑到 k=0 或 k=4095、偏差 1.1~1.6）。
>
> **所以校验器不能只测默认长度**，至少要有 `MIN_N` 与 `MAX_N` 两个端点。

- [ ] **Step 1: 先改名（用 `git mv`，不要只 `git add` 新路径）**

```bash
git mv src/main/kotlin/com/bingbaihanji/xuan/example/GpuFftVerifier.kt \
       src/main/kotlin/com/bingbaihanji/xuan/example/FftVerifier.kt
```

> **必须用 `git mv`**：只写 `git add <新路径>` 的话，旧文件仍在工作区里，
> 而 Kotlin 里两个文件都定义顶层 `main` 会让编译炸（或更糟——取决于文件名与
> `@JvmName`，可能安静地留下一个旧入口）。

然后**完整读一遍它**（897 行）——它的结构、像素/数值读回方式、退出码怎么钉的、
以及那四条变异留下的痕迹，都值得沿用。

改成测新核 `FftKernel`。判据见规格 §7.2，逐条落实：

1. **已知频率的纯正弦 → 峰值落在正确的 bin**
   `N=2048`，三个频率（**100 / 400 / 900**，都必须 < N/2）。
   期望：`|X[k]|` 在 `k0` 处有**唯一的**峰。
   > ⚠️ **不要去找 `k = N-100` 那个共轭峰**——本核输出只有 `N/2+1 = 1025` 个 bin，
   > 实余弦的共轭峰在半谱里被折叠掉了。（旧 `GpuFftVerifier` 测的是 `N=64` 的完整输出，
   > 所以那里 `k=7` 与 `k=57` 都在——**那是另一回事，别照抄**。）
2. **与 CPU 参考逐 bin 比对**（最大相对误差）。
   > **只断言峰值位置的话，一个整体缩放错的实现照样能通过。**
   > CPU 侧自己写一个朴素 DFT 或 radix-2 —— **不要从别处抄黄金模型**
   > （参考项目正是那样掩盖了一个窗函数 bug）。
3. **单位幅度余弦的峰值读回 1.0**（容差内）。这条把归一化与窗补偿一起钉住。
4. **换窗不改变幅度读数**：同一个正弦用三种窗各跑一次，峰值在容差内一致。
5. **着色器编译成功**（缺陷①的教训——别让失败晚到很久才暴露）。
6. **常输入（全同一个值）→ 峰只在 bin 0。**
7. **场景会变**：输入频率中途改变 → 峰值跟着移动；幅度中途改变 → 峰值高度跟着变。
   （`PickVerifier` 出过真实盲区：**24 条全绿却漏掉一个真缺陷，因为场景每帧完全相同**。）
8. **跨环绕取样本**（规格 R3）：让 `ringStart` 落在环的接缝附近，使 N 个样本跨过接缝，
   断言频谱仍然正确。**这与 ② 的"槽位 0 镜像"是同一类问题**，而那里没处理过。

- [ ] **Step 2: 跑校验器**

```bash
mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
    "-Dexec.args=-cp %classpath com.bingbaihanji.xuan.example.FftVerifierKt"
```
Expected: 全部通过，**退出码 0**。

- [ ] **Step 3: 变异验证（四条，逐条单独注入）**

| 变异 | 期望失败的断言 |
|---|---|
| **去掉位反转**（把 `rev` 换成 `i`） | 峰值位置（**这正是 `GPUFFT` 的缺陷③**） |
| **把某个 uniform 改成 `uint`** | 编译失败或值不生效（**缺陷②**） |
| 去掉窗补偿（`u_Scale` 不乘 `compensation`） | "换窗不改变读数" |
| `u_Scale` 里的 `2.0 / n` 改成 `1.0 / n` | 与 CPU 参考的逐 bin 比对 + "峰值读回 1.0" |

**任何一条存活都要停下来如实报告。** 跑完务必还原，`git diff` 确认干净。

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/bingbaihanji/xuan/example/FftVerifier.kt \
        src/main/kotlin/com/bingbaihanji/xuan/example/GpuFftVerifier.kt
git commit -F - <<'EOF'
test(gpu): FftVerifier —— 把"FFT 算对了"变成断言

由 GpuFftVerifier 改造而来。改而不是新写，是因为一个预期永远红的校验器
是负债：人一旦习惯"它红是正常的"，它就再也不起 gate 作用了。
GPUFFT 那三层缺陷的记录已经在 CLAUDE.md 与提交 adab313 里，不会丢。

核心判据是"已知频率的纯正弦，峰值必须落在正确的 bin"，N=2048、三个频率。
注意本核输出只有 N/2+1 个 bin，实余弦的共轭峰在半谱里被折叠掉了——
不要去找 k=N-100，旧校验器测的是 N=64 的完整输出，那是另一回事。

另有"与 CPU 参考逐 bin 比对"（只看峰值位置的话，整体缩放错的实现照样通过）、
"单位幅度余弦峰值读回 1.0"、"换窗不改变读数"、"编译成功"、
"场景会变"、以及"跨环绕取样本"。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 5: `ChartType.SPECTRUM`

**Files:**
- Modify: `src/main/java/com/bingbaihanji/xuan/chart/ChartType.java`
- Test: `src/test/java/com/bingbaihanji/xuan/chart/ChartTest.java`（或既有测试类）

> **这是本子项目唯一破"`chart/` 冻结"的地方。** 规格 §4.4 写清了理由：频谱本质上是一类
> **图型**（x 轴是频率、数据是幅度），做成成员之后它被 `ChartRenderer` 正常调度，
> **拾取、z 序、`Layer` 分组全部免费**。
>
> **它不破任何边界**：加一个常量，`chart/` 仍然零 GL 依赖，`ChartPackageIsolationTest` 照样通过。

- [ ] **Step 1: 加枚举常量**

在 `ChartType` 里，`WATERFALL` 之后加：

```java
    /**
     * 频谱：数据是频域幅度，由 GPU 上的 FFT 算出（子项目 ③-1）。
     *
     * <p><strong>它不是"折线的一种画法"，是"顶点怎么来"的问题</strong>——
     * 与热力图、瀑布图同类，所以 {@link #polylineFamily()} 对它返回 {@code false}，
     * 由独立的 {@code SpectrumSeriesRenderer} 实现（见 {@code ChartType} 的类文档
     * 关于"属性组合管同一套顶点怎么画，独立渲染器管顶点怎么来"那条边界）。
     */
    SPECTRUM(0),
```

> **flags 用 `0`**，与 `HEATMAP` / `WATERFALL` 一致——`SPECTRUM` 的顶点不是
> "每个样本一个点"，属性组合表达不了它。

- [ ] **Step 2: 补一条测试**

在既有图表测试类里加（或新建，按项目惯例）：

```java
    @Test
    void 频谱不属于折线族() {
        // 这条把"频谱必须由独立渲染器实现"钉住。
        // 若有人把它归进折线族，ChartRenderer 会把它路由给 LineSeriesRenderer，
        // 而那会把频域数据当普通点连成折线——画面看起来像频谱，实际上轴的含义全错。
        assertFalse(ChartType.SPECTRUM.polylineFamily(),
                "频谱的顶点不来自逐样本的点，必须由独立渲染器实现");
        assertFalse(ChartType.SPECTRUM.connectsSamples());
        assertFalse(ChartType.SPECTRUM.drawsMarkers());
        assertFalse(ChartType.SPECTRUM.drawsBars());
    }
```

- [ ] **Step 3: 跑测试**

Run: `mvn -o test -Dtest=ChartTest`
Expected: 通过。（**若测试类名不同，按实际改**。）

再跑全量 `mvn -o test` —— 期望 **在上一任务基础上 +1**。

> **特别注意 `ChartPackageIsolationTest`**：它断言 `chart/` 只依赖 `chart`/`math`/`util`。
> 加一个枚举常量**不引入任何 import**，所以它必须仍然通过。**若它失败，说明改错了地方。**

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bingbaihanji/xuan/chart/ChartType.java \
        src/test/java/com/bingbaihanji/xuan/chart/ChartTest.java
git commit -F - <<'EOF'
feat(chart): 加 ChartType.SPECTRUM

频谱本质上是一类图型（x 轴是频率、数据是幅度），做成 ChartType 成员之后
它被 ChartRenderer 正常调度，拾取、z 序、Layer 分组全部免费。

flags 用 0，与 HEATMAP/WATERFALL 一致——它的顶点不是"每个样本一个点"，
属性组合表达不了，由独立的 SpectrumSeriesRenderer 实现。

这是本子项目唯一破"chart/ 冻结"的地方，但它不破任何边界：加一个常量、
不引入任何 import，ChartPackageIsolationTest 照样通过。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 6: `SpectrumSeriesRenderer`

**Files:**
- Create: `src/main/java/com/bingbaihanji/xuan/chartrender/SpectrumSeriesRenderer.java`
- Modify: `src/main/java/com/bingbaihanji/xuan/chartrender/ChartRenderer.java`

- [ ] **Step 1: 写渲染器**

结构与 `LineSeriesRenderer` 同构（**先完整读它**）：建 VAO + 单位四边形 VBO、
自己负责进出时的 GL 状态（scissor 的开关与还原、预乘混合因子、VAO 与程序的解绑）、
ID pass 的接线。

差别只有两处：

```java
        // 1) 先跑 FFT，把结果写进 kernel 的输出缓冲
        kernel.execute(buffer.vboId(), ringStart, window);   // ringCapacity 已单源化

        // 2) 数据源换成 FFT 的输出缓冲，用 bin 数而不是样本数算可见区间
        //
        // ⚠️ 实施期更正：**必须是线段版 `compute`，不是 `computePoints`。**
        //    频谱是"相邻 bin 连线"，所以实例数 = binCount - 1（线段数）。
        //    用 `computePoints` 会让上界变成 binCount，窗口覆盖到最后一个 bin 时
        //    最后一个实例会读偏移 `binCount * 4`——**正好在缓冲末尾之外**，
        //    而那是**越界读 SSBO，GL 不报错**。
        List<WindowRange.Segment> segments = WindowRange.compute(
                windowStart, windowEnd, kernel.binCount(), binCapacity);
```

> **`ringStart` 怎么定**：源环形缓冲里"最近 N 个样本"的起始槽位。它是
> `(writeCount - N) & (capacity - 1)`。**注意 N 个样本可能跨过接缝**——
> 着色器里用 `& (cap-1)` 取模处理了（Task 3）。
>
> **`binCapacity` 是什么**：它是**实例化绘制的环容量**——`WindowRange` 要用它做槽位算术
> （`数据下标 & (容量-1)`），所以**必须是 2 的幂**。
> 取 **`kernel.binCount()` 向上取到的下一个 2 的幂**（`N=2048` → `binCount=1025` → 容量 `2048`）。
>
> **但 FFT 的输出缓冲只分配了 `binCount` 个 float**（Task 3 的构造器里），
> **比 `binCapacity` 小**。所以：
> **要么把输出缓冲按 `binCapacity` 分配**（多出的一点是余量，与 ② 的"多留一个 float"
> 同一类做法），**要么让 `WindowRange` 的上界用 `binCount` 而不是容量**。
>
> **你选一个，并把理由写进注释**——这与 ② 的"容量在创建时定死、永不扩容"是同一条硬约束，
> 别让绘制读到一个比缓冲更大的下标。

**双绑定**：kernel 的输出缓冲要能被实例属性读走。做法是
`gl.bindVbo(kernel.outputBufferId())` 之后照 `LineSeriesRenderer.configureDataAttributes`
的模式配两个属性指针（偏移 0 与 4，divisor 1）——
**同一个缓冲，SSBO 写、VBO 读，零拷贝**（规格 §4.5）。

- [ ] **Step 2: `ChartRenderer` 分派**

`rendererFor` 里加：

```java
        if (type == ChartType.SPECTRUM) {
            return spectrumRenderer;
        }
```

`dispose` 里释放它。

> **顺序**：`polylineFamily()` 那条检查在前，而 `SPECTRUM` 的 `polylineFamily()` 是 false
> ——所以**必须把这条放在那条例外之前**，否则它会先被"本期还没有渲染器"那条抛掉。

- [ ] **Step 3: 编译并跑全量**

Run: `mvn -o compile && mvn -o test`
Expected: `BUILD SUCCESS`、0 失败。

- [ ] **Step 4: 跑四个既有校验器做回归**

```bash
for V in PipelineVerifier PickVerifier TextVerifier ChartVerifier; do
  mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
      "-Dexec.args=-cp %classpath com.bingbaihanji.xuan.example.${V}Kt" >/dev/null 2>&1 \
    && echo "$V EXIT=0" || echo "$V EXIT=$?"
done
```
Expected: **四个都 EXIT=0**。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bingbaihanji/xuan/chartrender/SpectrumSeriesRenderer.java \
        src/main/java/com/bingbaihanji/xuan/chartrender/ChartRenderer.java
git commit -F - <<'EOF'
feat(chartrender): 频谱渲染器

先跑 FFT 把结果写进 kernel 的输出缓冲，然后复用 ② 的实例化机制画出来——
baseInstance、双偏移属性、拾取 pass 全套照用。

关键是"同一个缓冲既绑 SSBO 又绑 VBO"：compute 写完不需要任何 GPU 侧拷贝。
缓冲对象本来就可以绑到多个靶子上，两个绑定互不干扰。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 7: 像素断言与文档

**Files:**
- Modify: `src/main/kotlin/com/bingbaihanji/xuan/example/FftVerifier.kt`（或 `ChartVerifier.kt`）
- Modify: `CLAUDE.md`、`README.md`

- [ ] **Step 1: 加一条"频谱真的画出来了"的像素断言**

放到 `ChartVerifier` 或 `FftVerifier` 里（**你判断哪个更合适，说明理由**）。

**必须包含一条能把"频谱画对了"与"画错了"分开的断言**：

- 画一个已知频率的正弦的频谱，**断言峰值出现在绘图区的预期 x 位置**
  （x 是 bin 索引 → `ChartRenderLayout.screenX` 能算出预期像素）
- **反证**：改输入频率，断言峰值位置**跟着移动**——只断言"有个峰"对画错了同样成立

> ### ⚠️ Task 4 报上来的两条遗留，**Task 7 必须一并处理**
>
> **一、`CLAUDE.md` 里关于 `GpuFftVerifier` 的说明已经过时了。**
>
> 文件里至少三处（约 115 / 438 / 448 行）写着「`GpuFftVerifier` **现在预期是红的**，退出码 1；
> 修 ③ 的 FFT 时它转绿就是验收」。而它**已经不存在了**（改名为 `FftVerifier`），**而且是绿的**。
>
> **这正是本仓库一路在防的"文档与代码脱钩"**——而且是我们自己上一轮刚写进去的。
> 改文档时**逐条去代码里核实**，不要照抄上面这段摘要。
>
> **二、控制台中文在 Windows 下是乱码，除非给 JVM 加 `-Dstdout.encoding=UTF-8`。**
>
> Windows 下 JVM 的 `stdout.encoding` 默认是 **GBK**，而 `mvn exec:exec` 不传它——
> **五个校验器全都受影响**（不是本次引入的）。失败清单里的人话读不出来，
> 而**失败信息可读**恰恰是这些校验器存在的一半理由。
>
> **建议在 `CLAUDE.md` / `README.md` 的运行命令里统一加上它**，即在 `-Dexec.args` 的
> `-cp` 之前插一个 `-Dstdout.encoding=UTF-8`。
> **先用一个校验器实测加与不加的差别**，确认它真的解决了乱码，再写进文档——
> **不要因为"听起来对"就写进去**。

- [ ] **Step 2: 更新文档**

**`CLAUDE.md`**：

- 「图表」一节的 `chartrender/` 描述里加上频谱渲染器
- 「怎么验证改动」加上 `FftVerifier` 的命令
- 测试计数
- **「已实现」里加 `gpu/FftKernel`、`gpu/FftWindow`**
- **「未实现」里那一条关于 `GPUFFT` 的说明要更新**——加上"新核 `FftKernel` 已实现，
  `GPUFFT` 保留为缺陷记录"
- **「前置事实」里那句"不要因为版本号写着 330 就以为 compute 用不了"仍然成立**，
  但**别再说 `GPUFFT` 可用**（那已经改过一次，见提交 `58e6e77`）

**`README.md`**：图表一节补频谱用法与 `FftVerifier` 命令。

> **文档里的每一句话都必须在代码里核实过。** 这个项目已经出过好几次
> "声称做过某件事而实际没有"——最近一次是 `GPUFFT` 被写成"一直可用"，
> 而它**连编译都过不了**。

- [ ] **Step 3: 完整验收**

```bash
mvn -o clean test
# 五个校验器全部 EXIT=0
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md README.md \
        src/main/kotlin/com/bingbaihanji/xuan/example/FftVerifier.kt
git commit -F - <<'EOF'
docs: 补 GPU FFT 与频谱的现状与用法

CLAUDE.md：图表一节加频谱渲染器；验证一节加 FftVerifier；测试计数；
已实现加 gpu/FftKernel 与 FftWindow；未实现里那条 GPUFFT 的说明更新为
"新核已实现，GPUFFT 保留为缺陷记录"。

README.md：图表一节补频谱用法。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## 自审记录

**规格覆盖检查**：

| 规格节 | 落在哪个任务 |
|---|---|
| §4.2 SSBO 抽象 | Task 1 |
| §4.3 FFT 计算核（三条避坑） | Task 3 |
| §4.4 `ChartType.SPECTRUM` | Task 5 |
| §4.5 复用实例化机制 + 双绑定 | Task 6 |
| §5 数据流与坐标 | Task 3（取数）、Task 6（bin 索引当 x） |
| §6.1 FFT 长度与上限 | Task 3（构造器校验） |
| §6.2 窗函数 | Task 2 |
| §6.3 窗增益补偿 | **Task 2**（从定义推导，不写死系数） |
| §6.4 幅度映射（线性计算、dB 显示） | Task 3（计算侧）；**显示侧的 dB 留给应用**，规格 §6.4 未要求本期做 |
| §7.1 单测 | Task 2 |
| §7.2 校验器六条判据 + 四条变异 | Task 4 |
| §7.3 场景会变 | Task 4 第 7 条 |
| §8 错误处理 | Task 3（构造器） |
| §9 R3 跨环绕 | Task 4 第 8 条 |
| §9 R4 窗系数不可信 | **Task 2 的核心** |
| §9 R5 归一化只允许一种 | Task 3（`2/N`）+ Task 4（读回 1.0） |
| §9 R6 算力预算未实测 | **未落在任务里**——见下 |

**规格里没有、由本计划补充的**：

1. **dB 显示层本期不做** —— 规格 §6.4 说"显示层默认 dBm@50Ω"，但那是**应用层**的事，
   `chart/` 与 `chartrender/` 都不该硬编码 50 Ω。本计划**不实现它**，
   只保证计算产出线性幅度。**若你需要频谱一打开就是 dBm 视图，说一声**，
   那要另加一个小任务。
2. **R6（算力预算）没有独立任务** —— 我把它并进 Task 4：校验器本来就每帧跑一次 FFT，
   **在那里顺手测一次耗时并打印出来**。若超过 16 ms/帧，**停下来报告**，
   那说明单 workgroup 的路线要重新考虑。

**已知的 plan-internal 风险**：

- Task 3 的 `ComputeShader` API 与 `bitfieldReverse` 可用性**我没有核实**，
  计划里明确要求实现者去读实际代码并以实际为准。
- Task 6 的 `ringStart` 与 `binCapacity` 两处算术**是从设计推出来的、没实测**。
  它们是这个任务最容易错的地方，**要求实现者写出推导**。
