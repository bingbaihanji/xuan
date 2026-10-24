package com.bingbaihanji.xuan.audio;

import com.bingbaihanji.xuan.example.audio.WavFileSource;
import kotlin.Unit;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAV 音频源的单测。
 *
 * <p>WAV 全部**在内存里合成**再写进临时文件（{@code AudioSystem.write} 造一个已知样本的
 * PCM WAV），所以这里不需要任何音频资源，也就没有"跑在别的机器上没声卡"这回事。
 *
 * <p>期望值一律写成**精确相等**而不是"收个容差"：16 位 PCM 的归一化除数是 32768 = 2^15，
 * 除 2 的幂是精确运算，浮点那条链上没有任何一步会舍入。容差只会藏住
 * "除数写成了 32767"这类真缺陷（它只差 0.003%，肉眼与波形都看不出来）。
 *
 * <p>没有测的东西，写在这里免得下一个人以为漏了：
 * <ul>
 *   <li><b>麦克风</b>（{@code MicrophoneSource}）。开真设备会让 CI 与无声卡机器直接失败，
 *       而"拿不到设备就抛异常"那条分支在本机**够不到**——这台机器有采集线，
 *       且实测用不支持的采样率也拿得到（{@code isLineSupported} 对采样率不设防）。
 *       所以麦克风的验证放在真实设备上的手工探针里，不在这里。</li>
 *   <li><b>节流之外的时间行为</b>。只钉"100 ms 吐不出 10 秒的文件"这一条。</li>
 * </ul>
 */
class WavFileSourceTest {

    /** ① 解出来的 float 逐点正确（16 位有符号：`x / 32768.0`）。 */
    @Test
    void decodesPcm16MonoSampleBySample(@TempDir Path dir) throws Exception {
        short[] raw = {0, 1, -1, 1000, -1000, 32767, -32768, 12345, -23456};
        Path file = wav(dir, "mono16.wav", pcm(44100, 16, 1), pcm16(raw), raw.length);

        WavFileSource src = new WavFileSource(file.toFile(), false);
        assertEquals(44100, src.getSampleRate(), "采样率该照抄文件里的");
        assertEquals(raw.length, src.getFrameCount(), "整个文件都该被解码");

        src.start();
        float[] got = drainAll(src);
        assertEquals(raw.length, got.length, "drainInto 该吐出全部样本");
        for (int i = 0; i < raw.length; i++) {
            assertEquals(raw[i] / 32768.0f, got[i], 0.0f, "第 " + i + " 个样本");
        }

        // 文件放完就停：再 drain 不该产出任何东西
        assertArrayEquals(new float[0], drainAll(src), "文件放完之后 drainInto 该什么都不吐");

        // 重新 start = 从头再来一遍（换源时旧源会被 stop 掉，这条保证 stop→start 可复用）
        src.start();
        assertArrayEquals(got, drainAll(src), "重新 start 该从头重放");
    }

    /** ② 立体声降混成单声道 = 两声道取平均。 */
    @Test
    void downmixesStereoToMonoByAveraging(@TempDir Path dir) throws Exception {
        // 刻意带上"两声道之和不整除 2"的一组（1234 + 4321 = 5555）：整数除法的写法会在这里露馅。
        short[][] pairs = {{0, 0}, {1000, -1000}, {32767, 32767}, {-32768, -32768}, {1234, 4321}};
        Path file = wav(dir, "stereo16.wav", pcm(44100, 16, 2), pcm16Interleaved(pairs), pairs.length);
        assertEquals(2, readBackChannels(file), "前提：写出来的确实是双声道（降混是解码器干的活）");

        WavFileSource src = new WavFileSource(file.toFile(), false);
        src.start();
        float[] got = drainAll(src);

        assertEquals(pairs.length, got.length, "降混之后帧数不变");
        for (int i = 0; i < pairs.length; i++) {
            float expected = (pairs[i][0] + pairs[i][1]) / 2.0f / 32768.0f;
            assertEquals(expected, got[i], 0.0f, "第 " + i + " 帧的降混结果");
        }
    }

    /** ③ 非 PCM 编码必须明确抛异常，消息里带上实际拿到的编码名。 */
    @Test
    void rejectsNonPcmWithAClearMessage(@TempDir Path dir) throws Exception {
        // ULAW 是 JDK 自己就会**静默转码**的一种（AudioSystem 会把 ulaw 字节解开再交出来），
        // 正好用来钉"我们没有让它蒙混过关"。
        AudioFormat ulaw = new AudioFormat(AudioFormat.Encoding.ULAW, 8000f, 8, 1, 1, 8000f, false);
        Path file = wav(dir, "ulaw.wav", ulaw, new byte[800], 800);
        // 先钉住前提：写出来的**确实**是 ULAW。要是哪天 write 把它悄悄转成 PCM 了，
        // 下面那条断言就成了恒真的橡皮图章。
        assertEquals(AudioFormat.Encoding.ULAW, readBackEncoding(file), "合成出来的该是 ULAW 文件");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new WavFileSource(file.toFile()));
        assertTrue(ex.getMessage().contains("本 demo 只解码 PCM WAV"),
                "消息该说清只解码 PCM，实际是：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("ULAW"),
                "消息该带上实际拿到的编码名，实际是：" + ex.getMessage());
    }

    /** 8 位 WAV 惯例是**无符号**的（零点在 128），这条钉住那条零点换算。 */
    @Test
    void decodesPcmUnsigned8Bit(@TempDir Path dir) throws Exception {
        byte[] raw = {0, 64, (byte) 128, (byte) 192, (byte) 255};
        AudioFormat unsigned = new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, 8000f, 8, 1, 1, 8000f, false);
        Path file = wav(dir, "mono8u.wav", unsigned, raw, raw.length);
        // 8 位这一档 JDK 的写者会把"有符号"改写成"无符号"（WAV 惯例如此），
        // 所以这里必须指名道姓地要无符号，再把前提钉住。
        assertEquals(AudioFormat.Encoding.PCM_UNSIGNED, readBackEncoding(file), "合成出来的该是 PCM_UNSIGNED");

        WavFileSource src = new WavFileSource(file.toFile(), false);
        src.start();
        float[] got = drainAll(src);

        for (int i = 0; i < raw.length; i++) {
            float expected = ((raw[i] & 0xFF) - 128) / 128.0f;
            assertEquals(expected, got[i], 0.0f, "第 " + i + " 个样本");
        }
    }

    /** 24 位与 32 位：解码器的"按位宽符号扩展"在这一档才看得出对不对。 */
    @Test
    void decodesPcm24And32Bit(@TempDir Path dir) throws Exception {
        int[] raw24 = {0, 1, -1, 8388607, -8388608, 123456, -654321};
        Path f24 = wav(dir, "mono24.wav", pcm(48000, 24, 1), pcm24(raw24), raw24.length);
        WavFileSource s24 = new WavFileSource(f24.toFile(), false);
        s24.start();
        float[] got24 = drainAll(s24);
        for (int i = 0; i < raw24.length; i++) {
            assertEquals(raw24[i] / 8388608.0f, got24[i], 0.0f, "24 位第 " + i + " 个样本");
        }

        int[] raw32 = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 987654321};
        Path f32 = wav(dir, "mono32.wav", pcm(48000, 32, 1), pcm32(raw32), raw32.length);
        WavFileSource s32 = new WavFileSource(f32.toFile(), false);
        s32.start();
        float[] got32 = drainAll(s32);
        for (int i = 0; i < raw32.length; i++) {
            assertEquals(raw32[i] / 2147483648.0f, got32[i], 0.0f, "32 位第 " + i + " 个样本");
        }
    }

    /**
     * 文件是**实时回放**，不是全速灌。
     *
     * <p>判据刻意取得很宽（10 秒的文件，100 ms 后吐出的必须少于一半），
     * 因为放不开的是机器而不是人：真要卡到 5 秒才 drain 一次，这条也不该怪代码。
     * 但它拦得住"没有节流"那种实现——那会一口气吐完整整 10 秒。
     */
    @Test
    void throttlesPlaybackToRealtime(@TempDir Path dir) throws Exception {
        int frames = 44100 * 10;
        Path file = wav(dir, "ten-seconds.wav", pcm(44100, 16, 1), new byte[frames * 2], frames);

        WavFileSource src = new WavFileSource(file.toFile()); // 默认 realtime = true
        src.start();
        Thread.sleep(100);
        int emitted = (int) drainCount(src);

        assertTrue(emitted > 0, "节流不该把样本卡成 0，实际吐了 " + emitted);
        assertTrue(emitted < frames / 2,
                "100 ms 吐不出 5 秒的样本：实际 " + emitted + "（不节流的话是 " + frames + "）");

        src.stop();
        // 这一觉是**必须**的：不停这一段的话，"到现在该吐多少"本来就已经是 0，
        // 断言无论 stop() 有没有生效都会通过——恒真的断言比没有断言更坏。
        Thread.sleep(100);
        assertEquals(0, drainCount(src), "stop 之后不该再产出（这 100 ms 的样本也不该吐）");
    }

    /**
     * 跳转（{@code startAt}）：从第 k 个样本开始播，吐出来的头几个必须**正好**是文件里
     * 第 k 个往后的那些。
     *
     * <p>判据取的是"逐点相等"而不是"吐了点什么"：跳转最常见的错法是位置算错一位、
     * 或者没有把 {@code emitted} 归零——前者让整段偏一个样本（波形看起来完全正常），
     * 后者让头一段被"上次已经吐过的量"抵消掉（跳过去之后先卡住再加速）。
     * 只断言"有输出"两条都抓不住。
     */
    @Test
    void seeksToAnOffsetAndContinuesFromThere(@TempDir Path dir) throws Exception {
        short[] raw = {0, 1, -1, 1000, -1000, 32767, -32768, 12345, -23456};
        Path file = wav(dir, "seek.wav", pcm(44100, 16, 1), pcm16(raw), raw.length);
        WavFileSource src = new WavFileSource(file.toFile(), false);

        src.start();
        assertEquals(raw.length, drainAll(src).length, "前提：先正常放完一遍");
        assertEquals(raw.length, src.positionSamples(), "放完之后播放位置就是文件末尾");

        // 跳回中段：位置读数与吐出来的内容都要对得上。
        src.startAt(5);
        assertEquals(5L, src.positionSamples(), "跳转之后播放位置就是跳过去的那个样本号");
        float[] tail = drainAll(src);
        assertEquals(raw.length - 5, tail.length, "从第 5 个样本起，剩下的都该吐出来");
        for (int i = 0; i < tail.length; i++) {
            assertEquals(raw[5 + i] / 32768.0f, tail[i], 0.0f, "跳转后第 " + i + " 个样本");
        }

        // 越界夹回上界：等于"这一遍已经放完"，不抛异常，也吐不出东西。
        src.startAt(raw.length + 100);
        assertEquals(raw.length, src.positionSamples(), "越界的跳转该夹到文件末尾");
        assertArrayEquals(new float[0], drainAll(src), "夹到末尾之后没有样本可吐");
    }

    // ---------- 下面是合成 WAV 与收集样本的夹具 ----------

    /** 小端有符号 PCM 格式（WAV 的常规形状）。 */
    private static AudioFormat pcm(int rate, int bits, int channels) {
        return new AudioFormat(rate, bits, channels, true, false);
    }

    private static Path wav(Path dir, String name, AudioFormat format, byte[] data, int frames)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(data), format, frames),
                AudioFileFormat.Type.WAVE, out);
        Path file = dir.resolve(name);
        Files.write(file, out.toByteArray());
        return file;
    }

    /** 读回文件头里的编码。用来钉住"合成出来的前提"，免得 writer 自己改了主意。 */
    private static AudioFormat.Encoding readBackEncoding(Path file) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
            return in.getFormat().getEncoding();
        }
    }

    private static int readBackChannels(Path file) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
            return in.getFormat().getChannels();
        }
    }

    private static byte[] pcm16(short[] values) {
        byte[] out = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            out[i * 2] = (byte) values[i];
            out[i * 2 + 1] = (byte) (values[i] >> 8);
        }
        return out;
    }

    private static byte[] pcm16Interleaved(short[][] frames) {
        byte[] out = new byte[frames.length * 4];
        for (int i = 0; i < frames.length; i++) {
            for (int c = 0; c < 2; c++) {
                out[i * 4 + c * 2] = (byte) frames[i][c];
                out[i * 4 + c * 2 + 1] = (byte) (frames[i][c] >> 8);
            }
        }
        return out;
    }

    private static byte[] pcm24(int[] values) {
        byte[] out = new byte[values.length * 3];
        for (int i = 0; i < values.length; i++) {
            out[i * 3] = (byte) values[i];
            out[i * 3 + 1] = (byte) (values[i] >> 8);
            out[i * 3 + 2] = (byte) (values[i] >> 16);
        }
        return out;
    }

    private static byte[] pcm32(int[] values) {
        byte[] out = new byte[values.length * 4];
        for (int i = 0; i < values.length; i++) {
            out[i * 4] = (byte) values[i];
            out[i * 4 + 1] = (byte) (values[i] >> 8);
            out[i * 4 + 2] = (byte) (values[i] >> 16);
            out[i * 4 + 3] = (byte) (values[i] >> 24);
        }
        return out;
    }

    /** 走真实的 drainInto 路径把样本收全（顺带证它一次调用可以吐多次）。 */
    private static float[] drainAll(WavFileSource src) {
        List<Float> collected = new ArrayList<>();
        src.drainInto((buf, len) -> {
            for (int i = 0; i < len; i++) {
                collected.add(buf[i]);
            }
            return Unit.INSTANCE;
        });
        float[] out = new float[collected.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = collected.get(i);
        }
        return out;
    }

    private static long drainCount(WavFileSource src) {
        long[] count = new long[1];
        src.drainInto((buf, len) -> {
            count[0] += len;
            return Unit.INSTANCE;
        });
        return count[0];
    }
}
