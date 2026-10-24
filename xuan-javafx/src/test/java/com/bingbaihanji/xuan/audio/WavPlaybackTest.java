package com.bingbaihanji.xuan.audio;

import com.bingbaihanji.xuan.example.audio.PcmOutputKt;
import com.bingbaihanji.xuan.example.audio.WavFileSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 音频**播放**这一层的单测。
 *
 * <p>这里一条都不碰真实声卡：{@link WavFileSource} 的构造器收一个"打开输出设备"的工厂，
 * 单测传一个总是抛的替身进去，于是"没有可用播放设备"这条分支被测到，而 CI 与无声卡机器
 * 上跑的是同一段代码。**打开真设备**的验证只能靠 demo 的冒烟跑（{@code -Dxuan.audio.smoke}），
 * 那台机器上有没有扬声器是环境事实，不是代码事实。
 *
 * <p>三条纯算术各有一条断言，因为它们的错法都是**静默**的：
 * <ul>
 *   <li>格式的帧长/帧率弄反 → {@code getLine} 抛 IllegalArgumentException 或
 *       {@code isLineSupported} 全 false，看起来像"这台机器没有声卡"；</li>
 *   <li>播放头的加法错了 → 播放头整段偏一个固定量，画面完全正常；</li>
 *   <li>浮点 → PCM 的刻度与解码侧不一致 → 两个源音量差 0.003%，听不出来。</li>
 * </ul>
 */
class WavPlaybackTest {

    /**
     * 输出格式必须是 16 位单声道、**帧长 2 字节**、帧率等于采样率。
     *
     * <p>这一条钉的是踩过的坑：第 5 个参数是帧长（声道 × 每样本字节数），第 6 个才是帧率。
     * 把第 5 个写成"采样率 × 2"（那是**字节率**，16 位单声道下恰好等于 帧率 × 帧长）
     * 构造出来的 AudioFormat **不会抛**——它只会在 {@code getLine} 那儿失败，
     * 表现得像"本机没有声卡"。
     */
    @Test
    void outputFormatIsSixteenBitMonoWithTwoByteFrames() {
        int rate = 44100;
        AudioFormat format = PcmOutputKt.pcmPlaybackFormat(rate);

        assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding(), "编码");
        assertEquals(rate, (int) format.getSampleRate(), "采样率该照抄");
        assertEquals(16, format.getSampleSizeInBits(), "位宽");
        assertEquals(1, format.getChannels(), "声道数（本 demo 统一单声道）");
        assertEquals(2, format.getFrameSize(), "帧长 = 声道 × 位宽/8 = 2 字节");
        assertEquals(rate, (int) format.getFrameRate(), "帧率 = 采样率");
        assertFalse(format.isBigEndian(), "PCM WAV 的常规形状是小端");

        // 反面：帧长绝不能是"采样率 × 2"。这一条在"改成那个值"时才会红，
        // 而它是上面那个 assertEquals(2, …) 之外唯一能把"两个参数搬了家"讲清楚的一句。
        assertFalse(format.getFrameSize() == rate * 2,
                "帧长写成采样率×2（那是字节率）会构造出 getLine 找不到设备的格式");
        // 字节率 = 帧率 × 帧长：16 位单声道下就是 rate × 2，这正是上面那个坑的来源。
        assertEquals(rate * 2, (int) (format.getFrameRate() * format.getFrameSize()), "字节率");
    }

    /** 播放头 = 跳转基准 + 声卡帧位置。帧位置是"设备播了多少帧"，基准是"这次从哪个样本起"。 */
    @Test
    void playheadIsTheSeekBasePlusTheLineFramePosition() {
        assertEquals(5000L, PcmOutputKt.positionFromLineFrame(5000L, 0L), "刚开始播：设备报了 0 帧");
        assertEquals(5300L, PcmOutputKt.positionFromLineFrame(5000L, 300L), "播了 300 帧");
        assertEquals(300L, PcmOutputKt.positionFromLineFrame(0L, 300L), "从头播");
        // 帧位置是**设备自开线以来**的累计值，与跳转基准无关；基准一变，播放头整体平移。
        assertEquals(7L, PcmOutputKt.positionFromLineFrame(7L, 0L), "跳到第 7 个样本、设备还没播");
    }

    /** 退化模式（没有输出设备）的播放头按墙钟走：一秒 = 采样率那么多个样本。 */
    @Test
    void wallClockFallbackAdvancesWithElapsedTime() {
        int rate = 44100;
        assertEquals(100L, PcmOutputKt.positionFromWallClock(100, 0L, rate), "零时刻就是起点");
        assertEquals(100L + rate, PcmOutputKt.positionFromWallClock(100, 1_000_000_000L, rate), "一秒");
        assertEquals(100L + rate / 2, PcmOutputKt.positionFromWallClock(100, 500_000_000L, rate), "半秒");
        // 1 ms = 44.1 个样本，整数除法截掉小数部分。这里的**截断**是有意的：
        // 播放头必须是整数样本号，而"小数部分攒着"这件事由调用方用「播放头 − 已吐」处理
        // （见 WavFileSource.drainInto），不是在这里四舍五入。
        assertEquals(44L, PcmOutputKt.positionFromWallClock(0, 1_000_000L, rate), "1 ms = 44.1 个样本，截断");
    }

    /**
     * 拿不到输出设备时：{@code attachPlayback} 报 false + 一句人话，源**照常工作**，播放头退化成墙钟。
     *
     * <p>这条守的是"不留一块静默无声的画布"里"静默"那两个字：接不上设备是允许的，
     * 但它必须能被说出来（{@code playbackError}），而且画面不能因此停住。
     */
    @Test
    void withoutAnOutputDeviceTheSourceFallsBackToTheWallClock(@TempDir Path dir) throws Exception {
        Path file = wav(dir, "fallback.wav", new short[4410]);   // 0.1 秒 @44.1k
        WavFileSource src = new WavFileSource(file.toFile(), true, rate -> {
            throw new IllegalStateException("本机没有可用的播放设备：测试替身（单测不开真设备）");
        });

        assertFalse(src.attachPlayback(), "接不上设备时该返回 false，而不是抛出去让调用方自己猜");
        assertFalse(src.getPlaybackActive(), "退化模式下没有声卡在响");
        assertFalse(src.getPlaybackAttached(), "没接上过，播放按钮该是禁用的");
        assertEquals(-1L, src.getLineFramePosition(), "没有声卡时没有帧位置可读");
        assertTrue(src.getPlaybackError().contains("播放设备"),
                "原因该是一句给用户看的话，实际是：" + src.getPlaybackError());

        // 退化模式照常走：位置从跳转基准开始按墙钟涨，drainInto 照常吐样本。
        src.startAt(1000);
        assertEquals(1000L, src.positionSamples(), "退化模式的播放头起点就是跳转基准");
        assertTrue(src.getPlaybackError() != null, "接不上这件事不因为开始播放就被忘掉");

        Thread.sleep(100);
        long advanced = src.positionSamples();
        assertTrue(advanced > 1000, "退化模式仍然按墙钟走（画面照常动，只是没有声音），实际 " + advanced);
        assertTrue(drainCount(src) > 0, "退化模式下 drainInto 照样该吐出样本");

        // 停止之后播放头冻住：否则"已停止"的画面上那条竖线会一直往前爬。
        src.stop();
        long frozen = src.positionSamples();
        Thread.sleep(60);
        assertEquals(frozen, src.positionSamples(), "停止之后播放头不再走");

        // 停了还能再起，而且重新点的地方就是新的播放头（点波形跳转走的是同一条路）。
        src.startAt(7);
        assertEquals(7L, src.positionSamples(), "重新起播的位置");
    }

    /**
     * 浮点 → 16 位 PCM 的刻度与解码侧**同一条尺规**：满量程的一半是 32768。
     *
     * <p>所以"解码 → 播放"的往返是**逐位精确**的。乘 32767 的写法会让整条链差 0.003%，
     * 听不出来，但它是一条没有理由的偏差。
     */
    @Test
    void floatToPcm16RoundTripsTheDecodersScaleExactly() {
        int[] values = {0, 1, -1, 1000, -1000, 32767, -32768, 12345, -23456};
        for (int v : values) {
            assertEquals(v, PcmOutputKt.pcm16Value(v / 32768f), "第 " + v + " 个样本");
        }
        // 正的满量程在 int16 里放不下（+32768），夹到 32767；负的那一半是 -32768，不夹。
        assertEquals(32767, PcmOutputKt.pcm16Value(1f), "正的满量程夹到 32767");
        assertEquals(-32768, PcmOutputKt.pcm16Value(-1f), "负的满量程就是 -32768");
        assertEquals(32767, PcmOutputKt.pcm16Value(2f), "越界也夹住");

        // 写出去的字节必须是小端：源那侧的解码器按小端读，写反了两边都"正常"、声音是噪声。
        byte[] out = new byte[6];
        PcmOutputKt.encodePcm16(new float[]{0f, 1f, -1f}, 0, 3, out);
        assertArrayEquals(new byte[]{0x00, 0x00, (byte) 0xFF, 0x7F, 0x00, (byte) 0x80}, out, "小端 16 位");
    }

    /** 音量滑块（0..100）→ dB，对数刻度：100% 是 0 dB，50% 约 -6 dB，0% 是静音档。 */
    @Test
    void volumeSliderMapsToDecibelsLogarithmically() {
        assertEquals(0f, PcmOutputKt.sliderToDb(100), 1e-6f, "100% = 0 dB");
        assertEquals(PcmOutputKt.SILENT_DB, PcmOutputKt.sliderToDb(0), 1e-6f, "0% 给静音档，不是负无穷");
        assertEquals(-6.0206f, PcmOutputKt.sliderToDb(50), 0.01f, "50% ≈ -6 dB（电压减半）");
        assertEquals(-20f, PcmOutputKt.sliderToDb(10), 0.01f, "10% = -20 dB");
        assertEquals(PcmOutputKt.SILENT_DB, PcmOutputKt.sliderToDb(-5), 1e-6f, "负数当静音处理");

        // 单调：滑块往右推不会更轻。这条挡住"把 dB 与百分比直接相加"那类写法。
        float previous = Float.NEGATIVE_INFINITY;
        for (int percent = 0; percent <= 100; percent += 5) {
            float db = PcmOutputKt.sliderToDb(percent);
            assertTrue(db >= previous, percent + "% 比上一档更轻了");
            previous = db;
        }
    }

    // ---------- 夹具：内存里合成一个 PCM WAV（与 WavFileSourceTest 同一条尺规） ----------

    private static Path wav(Path dir, String name, short[] values) throws Exception {
        byte[] data = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            data[i * 2] = (byte) values[i];
            data[i * 2 + 1] = (byte) (values[i] >> 8);
        }
        AudioFormat format = new AudioFormat(44100, 16, 1, true, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(data), format, values.length),
                AudioFileFormat.Type.WAVE, out);
        Path file = dir.resolve(name);
        Files.write(file, out.toByteArray());
        return file;
    }

    private static long drainCount(WavFileSource src) {
        long[] count = new long[1];
        src.drainInto((buf, len) -> {
            count[0] += len;
            return kotlin.Unit.INSTANCE;
        });
        return count[0];
    }
}
