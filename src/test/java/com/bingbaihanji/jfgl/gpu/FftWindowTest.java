package com.bingbaihanji.jfgl.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FftWindow} 的单元测试。**纯算术，零 GL 依赖。**
 *
 * <h2>为什么断言落在物理性质上，而不是一组魔数</h2>
 * <p>参考项目 ngscopeclient 的窗增益系数是 2.013 / 1.862 / 2.805，而教科书值应当是
 * 2.0 / 1.852 / …。<b>两者对不上，原因不明</b>——而那份代码里确实有一个被它自己的
 * 单元测试掩盖的 BH 窗公式 bug（测试的黄金模型照抄了同一个公式）。
 *
 * <p>所以这里不写死任何系数：<b>系数由定义推导，断言检验它的物理含义</b>。
 */
class FftWindowTest {

    private static final int N = 512;

    /**
     * 单频信号的谱峰幅度（未归一化）。
     *
     * <p>{@code re} / {@code im} 是 {@code X[k0] = Σ w·x·exp(−i·angle)} 的实虚部，
     * 其中 {@code x = cos(angle)}、{@code angle = 2πk₀i/n}。
     * 对矩形窗与整周期信号，这个值是 {@code n/2}。
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
        // 对称性是窗函数的定义性质之一。写错分母（N 与 N-1 之分）会破坏它，
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
        // ⚠️ 容差是**量出来的，不是推出来的**：
        //
        //     peakOf(w, N, k0) = |Σ w·cos(θ)·e^{-iθ}| = ½·|W[0] + W[2k0]|
        //
        // 第二项**不是零**——它是窗函数自身在 bin 2k₀ 处的谱值，所以补偿后会留下残差。
        // **不要靠"这个窗的旁瓣是多少 dB"去估它**：那样会把它当成该窗的**最高**旁瓣
        // （Hann 是 −31 dB），而 bin 2k₀ 落在旁瓣裙边的深处，实际低得多。
        //
        // **实测残差（N=512, k0=7）**：矩形 0% · Hann 0.0010% · Hamming 0.0009% · BH 0.0001%
        //
        // 取 1e-4（0.01%）：比实测最大残差宽 10 倍，又比结构性错误小两个数量级。
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
    void 长度为一与二时不炸() {
        for (FftWindow w : FftWindow.values()) {
            w.coefficient(0, 1);
            w.coefficient(0, 2);
            w.coefficient(1, 2);
            assertTrue(w.compensation(2) > 0.0);
        }
    }
}
