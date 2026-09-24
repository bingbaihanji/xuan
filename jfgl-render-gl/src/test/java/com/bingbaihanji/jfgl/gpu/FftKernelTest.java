package com.bingbaihanji.jfgl.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FftKernel} 的<strong>零 GL 单测</strong>——只测它生成的那段着色器源码的字符串。
 *
 * <h2>为什么这些断言值得写</h2>
 * <p>它们钉住的是本轮实施期<b>真踩过</b>的三个坑，而每一个的共同点是：
 * <b>不看字符串就发现不了</b>。
 *
 * <ul>
 *   <li><b>裸 {@code %} 被当成格式符</b>：GLSL 里的 {@code k % halfLen} 会让
 *       {@code String.formatted} 抛 {@code UnknownFormatConversionException}，
 *       着色器一个字符都提交不到驱动。已改成 {@code %%}。</li>
 *   <li><b>不消耗实参的说明符</b>：{@code %n} 这类<b>不会抛异常</b>，
 *       它被静默替换成换行符、着色器照样编译通过。
 *       <b>所以"能跑"证明不了它不存在</b>——只有扫描能。</li>
 *   <li><b>共享内存数组长度与 {@code MAX_N} 脱钩</b>：抬大 {@code MAX_N} 而数组没跟着变，
 *       就是越界写共享内存，而<b>实测驱动既不报错也不崩</b>（只静默腐坏数值）。</li>
 * </ul>
 *
 * <h2>★ 断言为什么必须落在 {@code shaderTemplate()} 上，而不只是 {@code shaderSource()}</h2>
 * <p><b>这一条是变异验证逼出来的</b>：最初两处只断言<b>生成之后</b>的源码，
 * 结果把缺陷注入回模板时，两条断言<b>照样全绿</b>——
 *
 * <ul>
 *   <li>模板里写死的 {@code sRe[4096]}，在 {@code MAX_N == 4096} 时与 {@code sRe[%d]}
 *       生成的串<b>逐字符相同</b>，光看生成串分不出是"算出来的"还是"抄上去的"；</li>
 *   <li>{@code %n} 会被 {@code formatted} <b>吃掉</b>（换成换行符），
 *       生成串里<b>根本不会留下</b> {@code %n} 这三个字符。</li>
 * </ul>
 *
 * <p>所以判据是：<b>缺陷在格式化之前，就必须在格式化之前断言</b>。
 * 两组断言（模板 + 生成串）缺一不可：模板那组保证"值是从常量来的"，
 * 生成串那组保证"实参顺序没串位"。
 *
 * <p>这些全是 {@code shaderSource()} / {@code shaderTemplate()} 这一处的纯字符串运算——
 * 按本项目的判据（"能不能脱离 GL 上下文跑测试"），它们没有理由留给校验器。
 * 校验器管"这段源码在真 GL 上算得对不对"，这里管"这段源码生成得对不对"。
 */
class FftKernelTest {

    /** 模板里 {@code %d} 的个数，必须与 {@code shaderSource()} 的实参表一致。 */
    private static final int PLACEHOLDER_COUNT = 3;

    @Test
    void 着色器源码生成时不抛异常() {
        assertDoesNotThrow(FftKernel::shaderSource,
                "生成源码抛异常 = 着色器一个字符都提交不到驱动");
    }

    @Test
    void 共享内存数组长度必须跟着_MAX_N_走() {
        // ★ 这条钉的是"数组长度与常量脱钩"。抬大 MAX_N 而不改这一处，
        //    就是越界写共享内存——实测驱动既不报错也不崩，只静默腐坏数值。
        String template = FftKernel.shaderTemplate();
        assertTrue(template.contains("sRe[%d]"), "模板里的 sRe 长度必须是 %d 占位符，不能写死");
        assertTrue(template.contains("sIm[%d]"), "模板里的 sIm 长度必须是 %d 占位符，不能写死");
        // 写死的字面量：与 sRe[%d] 生成同样的串，却再也不跟着 MAX_N 走。
        assertFalse(template.contains("sRe[4096]"),
                "模板里把共享内存长度写死成了 4096——那样 MAX_N 一改就脱钩");
        assertFalse(template.contains("sIm[4096]"), "sIm 同上");

        // 生成串这一半钉的是"实参顺序"：%d 的顺序串位会让 local_size_x 拿到 MAX_N。
        String src = FftKernel.shaderSource();
        assertTrue(src.contains("sRe[" + FftKernel.MAX_N + "]"),
                "共享内存数组长度必须等于 MAX_N，实际源码里找不到 sRe[" + FftKernel.MAX_N + "]");
        assertTrue(src.contains("sIm[" + FftKernel.MAX_N + "]"),
                "sIm 同上");
    }

    @Test
    void 线程组大小必须跟着_LOCAL_SIZE_走() {
        assertTrue(FftKernel.shaderTemplate().contains("local_size_x = %d"),
                "模板里的 local_size_x 必须是 %d 占位符，不能写死");
        String src = FftKernel.shaderSource();
        assertTrue(src.contains("local_size_x = " + FftKernel.localSize()),
                "local_size_x 必须由 LOCAL_SIZE 填入");
    }

    @Test
    void 循环步长不得写回字面量() {
        // ★ 步长写死成 1024 的话，它与 local_size_x 就成了两个来源——
        //    而 GLSL 不做隐式转换，改了布局忘了改步长会静默少算。
        String src = FftKernel.shaderSource();
        assertFalse(src.contains("+= 1024"),
                "循环步长必须用 gl_WorkGroupSize.x 推导，不能写回字面量");
        // 正向控制：上面那条"没有 += 1024"在"三处循环整个被删掉"时也成立，
        // 所以必须同时钉住"推导这个动作还在"。
        assertTrue(src.contains("gl_WorkGroupSize.x"),
                "三处循环的步长必须由 gl_WorkGroupSize.x 推导");
    }

    @Test
    void 模板里每一个百分号都必须是占位符或转义() {
        // ★ 这条最重要，而且它是**执行验证覆盖不到的那一层**：
        //    formatted 一次性求值让"能跑"证明了"没有会抛异常的 %"，
        //    但证明不了"没有不消耗实参的说明符"——%n 会被静默换成换行符，
        //    着色器照样提交、照样编译通过。
        //
        //    断言必须打在**模板**上：%n 在生成串里已经被吃掉了，扫生成串等于扫空气
        //    （实测：只扫生成串时，往模板里塞 %n 的变异**全绿**）。
        String template = FftKernel.shaderTemplate();
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
                        + "它们会被静默替换，着色器照样编译通过。"
                        + "（GLSL 的取模必须写成 %% 。）");
            }
            i++; // 成对跳过
        }
        assertEquals(PLACEHOLDER_COUNT, placeholders,
                "模板里的 %d 个数与 shaderSource() 的实参表必须一致——"
                        + "少了会抛 MissingFormatArgumentException，多了则多出来的实参被**静默忽略**"
                        + "（那正是「写死字面量」那类缺陷的入口）");
        assertEquals(1, escapes,
                "GLSL 的取模 % 必须写成 %% 且只此一处");
    }
}
