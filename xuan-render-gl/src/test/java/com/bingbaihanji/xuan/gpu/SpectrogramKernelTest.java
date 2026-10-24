package com.bingbaihanji.xuan.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SpectrogramKernel} 的零 GL 单测——只测它生成的那段着色器源码
 * （构造它需要真的 GL 上下文：{@code ComputeShader} 直通 LWJGL；"写进去的像素对不对"
 * 归真上下文那支校验器，那正是 {@code readTextureImage} 存在的理由）。
 *
 * <p>前五组与 {@code FftKernelTest} / {@code FftBatchKernelTest} 同源（不看字符串就
 * 发现不了的坑：裸 {@code %} 被当格式符、{@code %n} 这类说明符被静默吃掉、uniform 声明成
 * {@code uint} 后 {@code glUniform1i} 静默失效、GLSL 保留字 {@code half}）。
 *
 * <p>后六组是本类独有的，钉的是声谱图特有的三类静默错误：
 * ① <strong>着色器里自带一份颜色公式</strong>——两个视图的配色会分家，而画面各自都正常；
 * ② <strong>写错列或翻错行</strong>——时间轴错位、频率轴上下颠倒，画面只是一张"形状挺像"的图；
 * ③ <strong>少写一次越界挡板</strong>——{@code imageStore} 越界是未定义行为，不报错。
 */
class SpectrogramKernelTest {

    /** 模板里 {@code %d} 的个数，必须与 {@code shaderSource()} 的实参表一致。 */
    private static final int PLACEHOLDER_COUNT = 1;

    /**
     * 模板里 {@code %%} 的个数。
     *
     * <p>本核一处 GLSL 取模都没有，所以是 0。将来加了取模必须写成 {@code %%} 并把这个数
     * 改成 1——那是一处<strong>可见的</strong>改动，而不是让模板默默长出一个会抛异常的裸 {@code %}。
     */
    private static final int MODULO_ESCAPES = 0;

    /**
     * 剥掉注释、把空白折叠成单空格的源码。
     *
     * <p>剥注释是必需的：模板里那句"不要用 half 当变量名"的注释会让保留字断言恒假
     * （实测踩过）。折叠空白则让下面的子串断言写起来是人话（{@code uniform int u_N;}），
     * 而不是与一段列对齐的空白强耦合。
     */
    private static String code() {
        return SpectrogramKernel.shaderSource()
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    @Test
    void 着色器源码生成时不抛异常() {
        assertDoesNotThrow(SpectrogramKernel::shaderSource,
                "生成源码抛异常 = 着色器一个字符都提交不到驱动");
    }

    @Test
    void 线程组大小必须跟着_LOCAL_SIZE_走() {
        assertTrue(SpectrogramKernel.shaderTemplate().contains("local_size_x = %d"),
                "模板里的 local_size_x 必须是 %d 占位符，不能写死");
        assertTrue(SpectrogramKernel.shaderSource().contains("local_size_x = " + SpectrogramKernel.localSize()),
                "local_size_x 必须由 LOCAL_SIZE 填入");
    }

    @Test
    void 模板里每一个百分号都必须是占位符或转义() {
        // 断言必须打在模板上：%n 在生成串里已经被吃掉，扫生成串等于扫空气。
        String template = SpectrogramKernel.shaderTemplate();
        int placeholders = 0;
        int escapes = 0;
        for (int i = 0; i < template.length(); i++) {
            if (template.charAt(i) != '%') {
                continue;
            }
            assertTrue(i + 1 < template.length(),
                    "模板末尾有一个落单的 '%'（会被 formatted 抛异常）");
            char next = template.charAt(i + 1);
            if (next == 'd') {
                placeholders++;
            } else if (next == '%') {
                escapes++;
            } else {
                fail("模板里出现 '%" + next + "'——既不是 %d 也不是 %%。%n/%</%1$ 这类说明符"
                        + "不会抛异常，会被静默替换，着色器照样编译通过。（GLSL 的取模必须写成 %% 。）");
            }
            i++;
        }
        assertEquals(PLACEHOLDER_COUNT, placeholders,
                "模板里的 %d 个数与 shaderSource() 的实参表必须一致——多了会被静默忽略");
        assertEquals(MODULO_ESCAPES, escapes,
                "本核没有 GLSL 取模：模板里出现 %% 说明新增了一处，请同时改这个期望值");
    }

    @Test
    void uniform_不得声明成_uint() {
        assertFalse(SpectrogramKernel.shaderTemplate().contains("uniform uint"),
                "uniform 一律 int / float / sampler2D，与 glUniform1i / glUniform1f 配对"
                        + "（声明成 uint 会得到 GL_INVALID_OPERATION 且值静默不生效）");
    }

    @Test
    void 不得使用_GLSL_保留字_half_当标识符() {
        assertFalse(code().matches("(?s).*\\bhalf\\b.*"),
                "half 是 GLSL 保留字（要写也只能写在注释里，这里比的是剥掉注释之后的源码）");
    }

    @Test
    void 不声明共享内存数组() {
        // 本核一次只做"读一个幅度、查一次色带、写一个像素"，不需要 shared memory。
        // 钉住它是因为 FftKernel 那套 shared 数组的长度必须跟 MAX_N 走——照抄进来而忘了
        // 那个占位符，就是越界写共享内存，而驱动既不报错也不崩。
        assertFalse(code().contains("shared "),
                "SpectrogramKernel 不声明共享内存；真要加，长度必须跟着 MAX_N 走（见 FftKernel）");
    }

    @Test
    void 上色只走LUT纹理不得自带颜色公式() {
        String code = code();
        // 正向：颜色确实来自那张 1×256 的色带。
        assertTrue(code.contains("uniform sampler2D u_Lut;"), "必须声明色带采样器");
        assertTrue(code.contains("texture(u_Lut, vec2(t, 0.5))"),
                "颜色必须由 LUT 采样得到（chart/ColorMapping 是颜色公式唯一的一份）");
        // 反面两条：抄一份颜色公式就会引入 vec3 常量或 RGB 三元组。
        assertFalse(code.contains("vec3("),
                "着色器里出现 vec3 基本就是自己算了一份颜色——颜色公式只许在 chart/ColorMapping 那一份 LUT 里");
        assertFalse(code.matches("(?s).*\\b0\\.\\d+\\s*,\\s*0\\.\\d+\\s*,\\s*0\\.\\d+.*"),
                "不得出现 RGB 常量三元组——那是抄了一份颜色公式");
    }

    @Test
    void 写入的列是_headColumn_而行是唯一的翻转点() {
        String code = code();
        // 列必须是 u_HeadColumn：写死成 0 的话每一列都落在同一处，而画面只是"时间轴不动了"。
        assertTrue(code.contains("int row = u_BinCount - 1 - k;"),
                "行号必须由 u_BinCount - 1 - k 算出来（这是唯一一处频率轴翻转）");
        assertTrue(code.contains("ivec2(u_HeadColumn, row)"),
                "imageStore 必须写 (u_HeadColumn, row) 处");
        assertTrue(code.contains("texture(u_Lut, vec2(t, 0.5))"),
                "写进去的颜色必须来自 LUT 采样");
        // 只许翻一次：翻两处就是恒等（画面上下颠倒），而它看起来只是一张"形状挺像"的图。
        assertEquals(1, count(code, "u_BinCount - 1 -"),
                "频率轴的翻转只许有一处");
    }

    @Test
    void 幅度换算成dB用log2不用底数有歧义的log() {
        String code = code();
        assertTrue(code.contains("log2("), "必须用 log2 换算（GLSL 的 log 是自然对数）");
        assertTrue(code.contains("log2(10.0)"), "20*log10(x) 必须写成 20*log2(x)/log2(10)");
        assertFalse(code.contains("log("),
                "log( 的底数是读者要去猜的；一律写成 log2 的换算");
    }

    @Test
    void 每个bin一个_invocation_越界由_u_BinCount_与_u_ImageWidth_挡住() {
        String code = code();
        assertTrue(code.contains("int k = int(gl_GlobalInvocationID.x);"),
                "一个 invocation 一个 bin（dispatch 的 invocation 数就是 bin 数）");
        assertTrue(code.contains("if (k >= u_BinCount) return;"),
                "dispatch 会多出几个 invocation（向上取整到 workgroup），必须挡住");
        assertTrue(code.contains("if (u_HeadColumn < 0 || u_HeadColumn >= u_ImageWidth) return;"),
                "imageStore 越界是未定义行为、不报错，列号必须在着色器里再挡一次");
    }

    @Test
    void 五个uniform与两个绑定点的声明齐全() {
        String code = code();
        // 声明的形状也是契约：image 必须是 rgba8 + writeonly + binding 0（与
        // glBindImageTexture 那边的访问模式、readTextureImage 的读法一致）。
        assertTrue(code.contains("layout(rgba8, binding = 0) uniform writeonly image2D u_Spectrogram;"),
                "输出 image 必须是 rgba8、writeonly、binding 0");
        assertTrue(code.contains("layout(std430, binding = 0) readonly buffer Spectrum { float mag[]; };"),
                "输入必须是 FftKernel 的输出缓冲（只读）");
        for (String declaration : new String[]{
                "uniform sampler2D u_Lut;", "uniform int u_ImageWidth;", "uniform int u_HeadColumn;",
                "uniform int u_BinCount;", "uniform float u_DbFloor;"}) {
            assertTrue(code.contains(declaration), "缺少 uniform 声明：" + declaration);
        }
    }

    @Test
    void dB下限的默认值是负的120() {
        // 这条是纯 Java 的：默认值取 0 的话归一化会除零，采到 NaN 坐标的颜色是未定义的。
        assertEquals(-120f, SpectrogramKernel.DEFAULT_DB_FLOOR);
    }
}
