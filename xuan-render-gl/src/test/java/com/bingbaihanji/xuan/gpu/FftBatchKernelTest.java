package com.bingbaihanji.xuan.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FftBatchKernel} 的<strong>零 GL 单测</strong>——只测它生成的那段着色器源码。
 *
 * <p>前六组与 {@code FftKernelTest} / {@code ComplexFftTest} 同源（不看字符串就发现不了的坑）。
 * <strong>后三组是本类独有的</strong>，它们钉的是"数组块 + batch"这一招特有的两个静默错误：
 *
 * <ul>
 *   <li><strong>输出绑定点与输入数组块撞了</strong>：数组块 {@code ch[32]} 占了绑定点
 *       0..31，输出若也写在 0..31 之内，就会**覆盖掉某一路的输入**——而输出形状看着完全正常。
 *       所以输出必须绑在 {@code MAX_BATCH} 上，且这个数字必须跟着常量走；</li>
 *   <li><strong>输出切片的基址写错</strong>：第 c 路必须写 {@code c * binCount} 处，
 *       写成 0 的话每一路都覆盖同一段，<b>画面上每条谱线都正常，只是内容都是最后写的那一路</b>。</li>
 * </ul>
 */
class FftBatchKernelTest {

    /** 模板里 {@code %d} 的个数，必须与 {@code shaderSource()} 的实参表一致。 */
    private static final int PLACEHOLDER_COUNT = 5;

    @Test
    void 着色器源码生成时不抛异常() {
        assertDoesNotThrow(FftBatchKernel::shaderSource,
                "生成源码抛异常 = 着色器一个字符都提交不到驱动");
    }

    @Test
    void 共享内存数组长度必须跟着_MAX_N_走() {
        String template = FftBatchKernel.shaderTemplate();
        assertTrue(template.contains("sRe[%d]"), "模板里的 sRe 长度必须是 %d 占位符");
        assertTrue(template.contains("sIm[%d]"), "模板里的 sIm 长度必须是 %d 占位符");
        String src = FftBatchKernel.shaderSource();
        assertTrue(src.contains("sRe[" + FftBatchKernel.MAX_N + "]"), "sRe 长度必须等于 MAX_N");
        assertTrue(src.contains("sIm[" + FftBatchKernel.MAX_N + "]"), "sIm 同上");
    }

    @Test
    void 线程组大小必须跟着_LOCAL_SIZE_走() {
        assertTrue(FftBatchKernel.shaderTemplate().contains("local_size_x = %d"),
                "模板里的 local_size_x 必须是 %d 占位符");
        assertTrue(FftBatchKernel.shaderSource().contains("local_size_x = " + FftBatchKernel.localSize()),
                "local_size_x 必须由 LOCAL_SIZE 填入");
    }

    @Test
    void 循环步长不得写回字面量() {
        String src = FftBatchKernel.shaderSource();
        assertFalse(src.contains("+= 1024"), "循环步长必须用 gl_WorkGroupSize.x 推导");
        assertTrue(src.contains("gl_WorkGroupSize.x"), "三处循环的步长必须由 gl_WorkGroupSize.x 推导");
    }

    @Test
    void 模板里每一个百分号都必须是占位符或转义() {
        String template = FftBatchKernel.shaderTemplate();
        int placeholders = 0;
        int escapes = 0;
        for (int i = 0; i < template.length(); i++) {
            if (template.charAt(i) != '%') {
                continue;
            }
            assertTrue(i + 1 < template.length(), "模板末尾有一个落单的 '%'");
            char next = template.charAt(i + 1);
            if (next == 'd') {
                placeholders++;
            } else if (next == '%') {
                escapes++;
            } else {
                fail("模板里出现 '%" + next + "'——既不是 %d 也不是 %%。%n 这类说明符不会抛异常，"
                        + "会被静默替换，着色器照样编译通过。（GLSL 的取模必须写成 %% 。）");
            }
            i++;
        }
        assertEquals(PLACEHOLDER_COUNT, placeholders,
                "模板里的 %d 个数与 shaderSource() 的实参表必须一致——多了会被静默忽略");
        assertEquals(1, escapes, "GLSL 的取模 % 必须写成 %% 且只此一处");
    }

    @Test
    void uniform_不得声明成_uint() {
        assertFalse(FftBatchKernel.shaderTemplate().contains("uniform uint"),
                "uniform 一律 int / float，与 glUniform1i / glUniform1f 配对（否则值静默不生效）");
    }

    @Test
    void 不得使用_GLSL_保留字_half_当标识符() {
        String code = FftBatchKernel.shaderSource()
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "");
        assertFalse(code.matches("(?s).*\\bhalf\\b.*"), "half 是 GLSL 保留字（用 halfLen 这类名字）");
    }

    // ── 本类独有的三组 ────────────────────────────────────────────────────

    @Test
    void 数组块的长度必须跟着_MAX_BATCH_走() {
        // ★ 写死成 ch[8] 而 MAX_BATCH 改成 32 的话，第 8 路往后根本读不到——
        //   而"读不到"在 GL 里是**未定义行为**，不报错。
        assertTrue(FftBatchKernel.shaderTemplate().contains("ch[%d]"),
                "数组块的长度必须是 %d 占位符，不能写死");
        assertTrue(FftBatchKernel.shaderSource().contains("ch[" + FftBatchKernel.MAX_BATCH + "]"),
                "数组块长度必须等于 MAX_BATCH");
    }

    @Test
    void 输出绑定点必须避开输入数组块占的那段绑定点() {
        // ★ 输入数组块 ch[MAX_BATCH] 占了绑定点 0..MAX_BATCH-1。
        //   输出若也落在那个区间，就会覆盖掉某一路的输入——而输出的形状看着完全正常。
        String src = FftBatchKernel.shaderSource();
        assertTrue(src.contains("binding = " + FftBatchKernel.MAX_BATCH),
                "输出必须绑在绑定点 MAX_BATCH 上（= " + FftBatchKernel.MAX_BATCH + "），"
                        + "否则会与输入数组块占的 0..MAX_BATCH-1 撞车");
        assertTrue(FftBatchKernel.shaderTemplate().contains("binding = %d"),
                "输出绑定点必须是 %d 占位符，跟着 MAX_BATCH 走");
    }

    @Test
    void batch_维度由_gl_WorkGroupID_x_选路_且输出切片基址跟着它走() {
        String template = FftBatchKernel.shaderTemplate();
        assertTrue(template.contains("gl_WorkGroupID.x"),
                "batch 维度必须由 gl_WorkGroupID.x 选取，不能写死");
        // 切片的基址必须是 c 的函数。写成常量 0 的话每一路都覆盖同一段，
        // 画面上每条谱线都正常、只是内容都是最后写的那一路。
        assertTrue(template.matches("(?s).*gl_WorkGroupID\\.x\\).*"), "同上的正则形式");
        assertTrue(template.contains("outBase"), "输出切片基址必须显式算出来（= c * binCount）");
    }
}
