package com.bingbaihanji.xuan.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ComplexFft} 的<strong>零 GL 单测</strong>——只测它生成的那段着色器源码，
 * 以及归一化算式这个纯算术。
 *
 * <h2>为什么这些断言值得写</h2>
 * <p>前半组与 {@code FftKernelTest} 逐条同源（"不看字符串就发现不了"的三个格式/脱钩坑），
 * 后半组是本类特有的——它们钉的正是当年把 {@code GPUFFT} 埋掉的那三条线，
 * 以及本规格新增的两处设计：
 *
 * <ul>
 *   <li><strong>缺陷①</strong>：GLSL <b>保留字</b>当标识符（{@code half}）⇒ 编译不过，
 *       失败晚到很久才暴露；</li>
 *   <li><strong>缺陷②</strong>：{@code uniform uint} 配 {@code glUniform1i} ⇒
 *       {@code GL_INVALID_OPERATION} <b>且值不生效</b>（静默）；</li>
 *   <li><strong>批量维度</strong>：必须由 {@code gl_WorkGroupID.x} 选取——
 *       写成常量 0 的话每一路都算成第一路，<b>画面完全正常</b>；</li>
 *   <li><strong>实数快通道</strong>：{@code u_InStride} 必须在——它决定"一个缓冲两种含义"，
 *       是本规格最容易静默出错的设计。</li>
 * </ul>
 *
 * <p>判据为什么必须同时落在 {@code shaderTemplate()} 与 {@code shaderSource()} 上，
 * 见 {@code FftKernelTest} 的类文档（{@code %n} 会被 {@code formatted} 吃掉）。
 */
class ComplexFftTest {

    /** 模板里 {@code %d} 的个数，必须与 {@code shaderSource()} 的实参表一致。 */
    private static final int PLACEHOLDER_COUNT = 3;

    @Test
    void 着色器源码生成时不抛异常() {
        assertDoesNotThrow(ComplexFft::shaderSource,
                "生成源码抛异常 = 着色器一个字符都提交不到驱动");
    }

    @Test
    void 共享内存数组长度必须跟着_MAX_N_走() {
        String template = ComplexFft.shaderTemplate();
        assertTrue(template.contains("sRe[%d]"), "模板里的 sRe 长度必须是 %d 占位符，不能写死");
        assertTrue(template.contains("sIm[%d]"), "模板里的 sIm 长度必须是 %d 占位符，不能写死");
        assertFalse(template.contains("sRe[4096]"),
                "模板里把共享内存长度写死成了 4096——那样 MAX_N 一改就脱钩");
        assertFalse(template.contains("sIm[4096]"), "sIm 同上");

        String src = ComplexFft.shaderSource();
        assertTrue(src.contains("sRe[" + ComplexFft.MAX_N + "]"),
                "共享内存数组长度必须等于 MAX_N");
        assertTrue(src.contains("sIm[" + ComplexFft.MAX_N + "]"), "sIm 同上");
    }

    @Test
    void 线程组大小必须跟着_LOCAL_SIZE_走() {
        assertTrue(ComplexFft.shaderTemplate().contains("local_size_x = %d"),
                "模板里的 local_size_x 必须是 %d 占位符，不能写死");
        assertTrue(ComplexFft.shaderSource().contains("local_size_x = " + ComplexFft.localSize()),
                "local_size_x 必须由 LOCAL_SIZE 填入");
    }

    @Test
    void 循环步长不得写回字面量() {
        String src = ComplexFft.shaderSource();
        assertFalse(src.contains("+= 1024"),
                "循环步长必须用 gl_WorkGroupSize.x 推导，不能写回字面量");
        assertTrue(src.contains("gl_WorkGroupSize.x"),
                "循环的步长必须由 gl_WorkGroupSize.x 推导");
    }

    @Test
    void 模板里每一个百分号都必须是占位符或转义() {
        // %n 这类不消耗实参的说明符**不会抛异常**，会被静默替换成换行符，着色器照样编译通过。
        // 所以"能跑"证明不了它不存在——只有扫描模板能（生成串里它已经被吃掉了）。
        String template = ComplexFft.shaderTemplate();
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
                fail("模板第 " + i + " 个字符处的 '%' 后面跟着 '" + next + "'——"
                        + "既不是占位符 %d 也不是转义 %%。%n/%</%1$ 这类说明符不会抛异常，"
                        + "它们会被静默替换，着色器照样编译通过。（GLSL 的取模必须写成 %% 。）");
            }
            i++; // 成对跳过
        }
        assertEquals(PLACEHOLDER_COUNT, placeholders,
                "模板里的 %d 个数与 shaderSource() 的实参表必须一致——"
                        + "多了会被**静默忽略**（那正是「写死字面量」那类缺陷的入口）");
        assertEquals(1, escapes, "GLSL 的取模 % 必须写成 %% 且只此一处");
    }

    @Test
    void uniform_不得声明成_uint() {
        // ★ 缺陷②（当年 GPUFFT 就死在这一条上，而且是静默的）：
        //   声明成 uint 而用 glUniform1i 赋值 ⇒ GL_INVALID_OPERATION 且**值不生效**。
        assertFalse(ComplexFft.shaderTemplate().contains("uniform uint"),
                "uniform 一律声明成 int / float，与 setUniform 的 glUniform1i / glUniform1f 配对");
        assertFalse(ComplexFft.shaderTemplate().contains("uniform uvec"),
                "同上：不许出现任何 unsigned 类型的 uniform");
    }

    @Test
    void 不得使用_GLSL_保留字_half_当标识符() {
        // ★ 缺陷①（当年 GPUFFT 的默认着色器就编译不过）。
        //   两个技术点，都是这条断言本身踩出来的：
        //   ① 用词边界匹配——`halfLen` 里含 half 不算违规，独立的 half 才算；
        //   ② **必须先剥注释**——着色器里那句"不要用 half 当变量名"的注释本身含 half，
        //      不剥的话这条断言恒假（实测：第一版就是这么红的）。
        String code = ComplexFft.shaderSource()
                .replaceAll("(?s)/\\*.*?\\*/", "")   // 块注释
                .replaceAll("//[^\\n]*", "");        // 行注释
        assertFalse(code.matches("(?s).*\\bhalf\\b.*"),
                "half 是 GLSL 保留字，当标识符会让着色器**编译不过**（用 halfLen 这类名字）");
    }

    @Test
    void 批量维度必须由_gl_WorkGroupID_x_选取() {
        // ★ 钉住"batch 偏移写成常量 0"那类缺陷：写成 0 的话每一路都算成第一路，
        //   而**画面完全正常**（每条谱线形状都对，只是内容都是第 0 路）。
        assertTrue(ComplexFft.shaderTemplate().contains("gl_WorkGroupID.x"),
                "批量维度必须由 gl_WorkGroupID.x 选取，不能写死");
    }

    @Test
    void 实数快通道的_stride_uniform_必须在() {
        assertTrue(ComplexFft.shaderTemplate().contains("u_InStride"),
                "u_InStride 是实数快通道的开关；丢了它，实数输入就得靠调用方自己补零虚部");
    }

    @Test
    void 归一化_正向不归一_逆向乘_n_分之一() {
        // ★ 只允许一种约定（原规格 R5 批评过"两套约定差 3 dB"）。
        assertEquals(1.0f, ComplexFft.scaleFor(2048, false), 0f,
                "正向不归一：单位幅度余弦的谱峰是 N/2");
        assertEquals(1.0f / 2048f, ComplexFft.scaleFor(2048, true), 0f,
                "逆向乘 1/N，往返才回得到原信号");
    }
}
