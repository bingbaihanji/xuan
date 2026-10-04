package com.bingbaihanji.xuan.chart;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TickGenerator} 的单测：**纯算术，零依赖**。
 *
 * <p>刻度错了不会报错，只会让坐标轴上的数字看起来"莫名其妙"——间距不整齐、
 * 数不出格数、或者标签是 3.0000000000000004。这些都得靠断言钉住。
 */
class TickGeneratorTest {

    /** 测试用的"映射"：位置就等于值本身。用它断言"位置确实来自映射"。 */
    private static final DoubleUnaryOperator IDENTITY = v -> v;

    /** 把像素长度的映射当成线性轴用：position = (v - min) / span * length。 */
    private static DoubleUnaryOperator linearMap(AxisRange range, double length) {
        return v -> (v - range.min()) / range.span() * length;
    }

    private static List<Tick> ofLevel(Tick[] ticks, int level) {
        List<Tick> out = new ArrayList<>();
        for (Tick t : ticks) {
            if (t.level() == level) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 某一级刻度的步长 = "该级与更粗级别的值"的最小间距。
     *
     * <p><strong>不能直接取"该级列表里相邻两项之差"</strong>：一个值只发射一次
     * （取最粗的级别），所以中刻度的列表里缺了那些同时也是主刻度的值，
     * 相邻两项之间会隔着整整 2 倍步长。取并集之后，最小间距才是这一级真正的格宽。
     */
    private static double stepOf(Tick[] ticks, int level) {
        List<Double> values = new ArrayList<>();
        for (Tick t : ticks) {
            if (t.level() <= level) {
                values.add(t.value());
            }
        }
        java.util.Collections.sort(values);
        assertTrue(values.size() >= 2, "到第 " + level + " 级为止只有 " + values.size()
                + " 个刻度，数不出步长——换一个范围再测");
        double min = Double.MAX_VALUE;
        for (int i = 1; i < values.size(); i++) {
            min = Math.min(min, values.get(i) - values.get(i - 1));
        }
        return min;
    }

    private static boolean isMultipleOf(double value, double step) {
        double k = value / step;
        return Math.abs(k - Math.rint(k)) < 1e-6;
    }

    private static String majorStepKind(double step) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(Math.abs(step))));
        double normalized = step / magnitude;
        return normalized + "e" + (int) Math.floor(Math.log10(magnitude));
    }

    @Test
    void 主刻度步长只取1_2_5的十进制倍数() {
        double[] spans = {0.3, 1.0, 7.0, 42.0, 100.0, 1234.0, 98765.0};
        for (double span : spans) {
            AxisRange range = AxisRange.of(0, span);
            Tick[] ticks = TickGenerator.generate(AxisType.LINEAR, range, 800, IDENTITY);
            double step = stepOf(ticks, Tick.MAJOR);
            double magnitude = Math.pow(10, Math.floor(Math.log10(step)));
            double normalized = Math.round(step / magnitude * 1e6) / 1e6;
            assertTrue(normalized == 1.0 || normalized == 2.0 || normalized == 5.0,
                    "跨度 " + span + " 的主刻度步长是 " + step + "（归一化后 " + normalized
                            + "），不是 1/2/5 × 10ⁿ");
        }
    }

    @Test
    void 八百像素的0到100得到步长10() {
        AxisRange range = AxisRange.of(0, 100);
        Tick[] ticks = TickGenerator.generate(AxisType.LINEAR, range, 800, IDENTITY);

        // 800 / 80 = 10 格，跨度 100 → 步长 10
        assertEquals(10.0, stepOf(ticks, Tick.MAJOR), 1e-9);
        List<Tick> majors = ofLevel(ticks, Tick.MAJOR);
        assertEquals(11, majors.size(), "0..100 步长 10 应当有 11 个刻度");
        assertEquals(0.0, majors.get(0).value(), 1e-9);
        assertEquals(100.0, majors.get(10).value(), 1e-9);
        assertEquals("0", majors.get(0).label());
        assertEquals("100", majors.get(10).label(), "末刻度的值不能被浮点尾数污染成 100.00000000000001");
    }

    @Test
    void 刻度位置由映射函数给出() {
        AxisRange range = AxisRange.of(0, 100);
        Tick[] ticks = TickGenerator.generate(AxisType.LINEAR, range, 800, linearMap(range, 800));
        for (Tick t : ticks) {
            assertEquals(t.value() * 8.0, t.position(), 1e-6,
                    "位置必须来自传入的映射函数，不能由生成器自己算一遍——两份换算迟早会不一致");
        }
    }

    @Test
    void 所有刻度都落在声明范围之内() {
        AxisRange range = AxisRange.of(-3.7, 12.4);
        Tick[] ticks = TickGenerator.generate(AxisType.LINEAR, range, 640, IDENTITY);
        assertTrue(ticks.length > 0, "这个范围应当能生成刻度");
        for (Tick t : ticks) {
            assertTrue(t.value() >= range.min() - 1e-9 && t.value() <= range.max() + 1e-9,
                    "刻度 " + t.value() + " 跑到范围 " + range.min() + ".." + range.max() + " 外面去了");
        }
    }

    @Test
    void 主中次三级的格层层包含() {
        AxisRange range = AxisRange.of(0, 100);
        Tick[] ticks = TickGenerator.generate(AxisType.LINEAR, range, 800, IDENTITY);
        double major = stepOf(ticks, Tick.MAJOR);
        double medium = stepOf(ticks, Tick.MEDIUM);
        double minor = stepOf(ticks, Tick.MINOR);

        // 注意：一个值只发射一次（取最粗的级别），所以"主刻度必然也是中刻度"这句话
        // 不能指望在中刻度列表里找到重复项。包含关系体现在**格**上：
        // 主刻度的每一个值都落在中刻度的格上，中刻度的每一个值都落在次刻度的格上。
        // 这就是这个表示法的全部含义。
        assertEquals(major / 2.0, medium, 1e-9, "中刻度步长必须是主刻度的一半");
        assertEquals(major / 4.0, minor, 1e-9, "次刻度步长必须是主刻度的四分之一");
        for (Tick t : ofLevel(ticks, Tick.MAJOR)) {
            assertTrue(isMultipleOf(t.value(), medium), t.value() + " 不在中刻度的格上");
            assertTrue(isMultipleOf(t.value(), minor), t.value() + " 不在次刻度的格上");
        }
        for (Tick t : ofLevel(ticks, Tick.MEDIUM)) {
            assertTrue(isMultipleOf(t.value(), minor), t.value() + " 不在次刻度的格上");
        }
        // 反证：中次两级必须真的存在，否则上面两个循环一个都不跑，断言恒真
        assertFalse(ofLevel(ticks, Tick.MEDIUM).isEmpty(), "中刻度一个都没有");
        assertFalse(ofLevel(ticks, Tick.MINOR).isEmpty(), "次刻度一个都没有");
    }

    @Test
    void 退化范围不产生NaN且有刻度() {
        AxisRange range = AxisRange.of(5.0, 5.0);
        // 映射必须按**稳定化之后**的窗口来构造——这正是 Axis 的做法（它在构造时就把窗口
        // 稳定化了，ticks() 传给生成器的也是稳定化之后的窗口）。用未稳定化的范围自己
        // 造一个映射会在这里除零，那是测试的错，不是生成器的错。
        AxisRange stable = range.withMinimumSpan();
        Tick[] ticks = assertDoesNotThrow(
                () -> TickGenerator.generate(AxisType.LINEAR, range, 800, linearMap(stable, 800)),
                "退化范围不能抛异常");
        assertTrue(ticks.length > 0, "退化范围也应当有刻度：跨度被扩成最小可视跨度之后就该有");
        for (Tick t : ticks) {
            assertFalse(Double.isNaN(t.value()), "退化范围产生了 NaN 刻度值");
            assertFalse(Double.isNaN(t.position()), "退化范围产生了 NaN 刻度位置");
        }
    }

    @Test
    void 对数轴的主刻度是10的整数次幂() {
        AxisRange range = AxisRange.of(1.0, 1000.0);
        Tick[] ticks = TickGenerator.generate(AxisType.LOGARITHMIC, range, 800, IDENTITY);
        List<Tick> majors = ofLevel(ticks, Tick.MAJOR);
        assertEquals(4, majors.size(), "1..1000 有 10⁰/10¹/10²/10³ 四个数量级");
        double[] expected = {1, 10, 100, 1000};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], majors.get(i).value(), 1e-9 * expected[i],
                    "第 " + i + " 个主刻度不是 10 的整数次幂");
        }
        assertEquals("1", majors.get(0).label());
        assertEquals("10", majors.get(1).label());
        assertEquals("100", majors.get(2).label());
    }

    @Test
    void 对数轴上出现非正值时钳到下限且不产生NaN() {
        AxisRange range = AxisRange.of(0.0, 100.0);
        Tick[] ticks = assertDoesNotThrow(
                () -> TickGenerator.generate(AxisType.LOGARITHMIC, range, 800,
                        linearMap(range.withPositiveMin(), 800)),
                "对数轴遇到 0 不能抛异常，也不能返回 NaN");
        assertTrue(ticks.length > 0, "钳位之后仍然要有刻度");
        for (Tick t : ticks) {
            assertTrue(t.value() > 0, "对数轴上出现了非正的刻度值：" + t.value());
            assertFalse(Double.isNaN(t.position()), "对数轴产生了 NaN 位置");
        }

        // 整段都 ≤ 0：给一个占位范围，同样一个 NaN 都不能有
        Tick[] allNonPositive = assertDoesNotThrow(
                () -> TickGenerator.generate(AxisType.LOGARITHMIC, AxisRange.of(-5, -1), 800, IDENTITY));
        for (Tick t : allNonPositive) {
            assertTrue(t.value() > 0, "整段非正时应当退回占位范围 [1,10]，实际给出 " + t.value());
        }
    }

    @Test
    void 对数轴跨度不足一个数量级时退化成线性刻度() {
        AxisRange range = AxisRange.of(1.2, 1.8);
        Tick[] ticks = TickGenerator.generate(AxisType.LOGARITHMIC, range, 800, IDENTITY);
        assertTrue(ticks.length > 0,
                "1.2..1.8 里一个 10 的整数次幂都没有，但退化成线性刻度之后必须有刻度，否则是一条空轴");
        for (Tick t : ticks) {
            assertTrue(t.value() >= 1.2 - 1e-9 && t.value() <= 1.8 + 1e-9,
                    "退化后的刻度 " + t.value() + " 跑到范围外面去了");
        }
    }

    @Test
    void 时间轴按量级切换标签格式() {
        // 2026-09-20T12:34:56Z
        double base = 1789907696.0;
        String[][] cases = {
                // {"跨度（秒）", "期望的标签形状", "为什么是这个形状"}
                {"30", "\\d{2}:\\d{2}:\\d{2}", "800 像素 10 格 → 步长 5 秒 → HH:mm:ss"},
                {"600", "\\d{2}:\\d{2}", "步长 60 秒 → HH:mm"},
                {"2592000", "\\d{2}-\\d{2}", "步长 7 天 → MM-dd"},
                {"63072000", "\\d{4}-\\d{2}", "步长 365 天 → yyyy-MM"},
        };
        for (String[] c : cases) {
            double span = Double.parseDouble(c[0]);
            AxisRange range = AxisRange.of(base, base + span);
            Tick[] ticks = TickGenerator.generate(AxisType.TIME, range, 800, IDENTITY);
            List<Tick> majors = ofLevel(ticks, Tick.MAJOR);
            assertFalse(majors.isEmpty(), "跨度 " + span + " 秒一个刻度都没有");
            for (Tick t : majors) {
                assertTrue(t.label().matches(c[1]),
                        "跨度 " + span + " 秒（" + c[2] + "）的标签格式不对：" + t.label()
                                + "，期望匹配 " + c[1]);
            }
        }
    }

    @Test
    void 时间轴标签用UTC而不是系统时区() {
        // 2026-09-20T12:34:56Z 起 30 秒：步长是 5 秒，所以标签是 HH:mm:ss 级别，
        // 秒数必须精确对得上——时区一偏，秒也会跟着偏（偏移量未必是整分钟）
        double base = 1789907696.0;
        AxisRange range = AxisRange.of(base, base + 30);
        Tick[] ticks = TickGenerator.generate(AxisType.TIME, range, 800, IDENTITY);
        List<Tick> majors = ofLevel(ticks, Tick.MAJOR);
        assertFalse(majors.isEmpty(), "30 秒跨度一个刻度都没有");

        DateTimeFormatter utc = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
                .withZone(ZoneOffset.UTC);
        for (Tick t : majors) {
            String expected = utc.format(Instant.ofEpochSecond((long) t.value()));
            assertEquals(expected, t.label(),
                    "标签必须按 UTC 格式化。按系统时区格式化的话，同一段代码在两台机器上"
                            + "给出不同标签——测试只会在别人的机器上红");
        }
    }

    @Test
    void 文本轴在像素不够时按步长抽稀() {
        // 100 个类目只有 160 像素宽：80 像素一个标签的话最多放 2 个
        AxisRange range = AxisRange.of(0, 99);
        Tick[] ticks = TickGenerator.generate(AxisType.TEXT, range, 160, IDENTITY);
        assertTrue(ticks.length <= 3,
                "160 像素宽放不下 " + ticks.length + " 个类目标签，抽稀的步长没起作用");
        assertTrue(ticks.length >= 2, "抽稀过头了：" + ticks.length + " 个刻度");
        for (Tick t : ticks) {
            assertEquals(Math.rint(t.value()), t.value(), 1e-9, "类目刻度必须落在整数下标上");
            assertEquals(String.valueOf((long) t.value()), t.label(),
                    "类目轴的标签是下标本身；类目名归数据侧，① 不知道它");
        }
        // 像素足够时不该抽稀
        Tick[] all = TickGenerator.generate(AxisType.TEXT, AxisRange.of(0, 4), 800, IDENTITY);
        assertEquals(5, all.length, "800 像素放下 5 个类目，不该抽稀");
    }
}
