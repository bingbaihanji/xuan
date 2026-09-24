package com.bingbaihanji.jfgl.gpu;

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
 * {@code compensation = 1 / coherentGain}。<b>不要替换成任何写死的魔数。</b>
 *
 * <h2>默认为什么是 Blackman-Harris</h2>
 * <p>示波器场景常要在<b>大载波旁边看小谐波</b>。BH 的旁瓣比 Hann 低一个量级以上，
 * 而旁瓣高看起来像"底噪抬起来了"，不像 bug。
 */
public enum FftWindow {

    /** 矩形窗（等价于不加窗）。主瓣最窄、旁瓣最高。 */
    RECTANGULAR {
        @Override
        public double coefficient(int i, int n) {
            return 1.0;
        }
    },

    /** Hann 窗。旁瓣约 −31 dB。 */
    HANN {
        @Override
        public double coefficient(int i, int n) {
            return 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1.0));
        }
    },

    /** Hamming 窗。旁瓣约 −43 dB。 */
    HAMMING {
        @Override
        public double coefficient(int i, int n) {
            return 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (n - 1.0));
        }
    },

    /**
     * Blackman-Harris 窗（四项），<b>默认</b>。旁瓣约 −92 dB。
     *
     * <p><strong>三项余弦必须依次是 {@code cos(x)} / {@code cos(2x)} / {@code cos(3x)}，
     * 其中 {@code x = 2πi/(N−1)}。</strong> 参考项目把它写成了 {@code cos(6·num)}
     * （其中 {@code num = 2πi/N}），于是实际算的是 {@code cos(12πi/N)}——
     * <b>而它的单元测试照抄了同一个公式，抓不到</b>。
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
     * @throws IllegalArgumentException {@code n < 2}，或该窗在这个长度上退化成零增益
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
