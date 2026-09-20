package com.bingbaihanji.jfgl.chart;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * nice number 刻度生成：给一个数据范围与像素长度，给出主/中/次三级刻度。
 *
 * <h2>全是 double 算术</h2>
 * <p>规格 §6 点名 fxcharts 的 {@code Axis.java:1399} 用 {@code BigDecimal} 算刻度：
 * 高刷新率下它是热点，而且没必要。刻度是给眼睛看的，6 位有效数字足够了。
 *
 * <h2>三级刻度的包含关系是"格"层面的</h2>
 * <p>步长取 {@code step / step/2 / step/4}（取 /4 而不是 /5，因为 step/2 的格未必包含
 * step 的格：5 与 2.5 就是反例）。<strong>一个值只发射一次，取最粗的级别</strong>，
 * 所以主刻度的值不会同时出现在中刻度的列表里——包含关系体现在"主刻度的值都落在
 * 中刻度的格上"，而不是"列表里有重复项"。
 *
 * <h2>标签只有主刻度有</h2>
 * <p>中/次刻度是给眼睛看密度的，都写标签会糊成一片。需要更密的标签时，
 * 调用方把 {@code pixelLength} 报小一点即可（{@link #TARGET_SPACING_PX} 是固定的）。
 *
 * <p>纯函数、零依赖、无任何 GL 调用，因此可以彻底单测。
 */
public final class TickGenerator {

    /** 主刻度之间期望的像素间距。像素长度除以它，就是期望的刻度格数。 */
    public static final double TARGET_SPACING_PX = 80.0;

    /** 一道硬上限：防止边界情形下（步长与范围不匹配）产出爆炸数量的刻度。 */
    private static final int MAX_TICKS = 4096;

    /** 浮点比较用的相对容差。 */
    private static final double EPS = 1e-9;

    /**
     * 时间轴的候选步长（秒），从 1 秒到 10 年。
     *
     * <p>月按 30 天、年按 365 天<strong>近似</strong>：图表的刻度不需要日历精度。
     * 真要"每月 1 号"这种日历对齐，需要另一套逻辑（按日历字段而不是秒数递推），
     * 本期不做——而末尾那两个"年"量级的步长是必须有的，
     * 否则 {@link #FORMAT_YEAR} 那条分支永远不可达（跨度再大也只会走到 30 天）。
     */
    private static final double[] TIME_STEPS_SECONDS = {
            1, 2, 5, 10, 15, 30,
            60, 120, 300, 600, 900, 1800,
            3600, 7200, 10800, 21600, 43200,
            86400, 172800, 604800, 1209600, 2592000,
            31536000, 315360000
    };

    private static final DateTimeFormatter FORMAT_SECOND =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FORMAT_MINUTE =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FORMAT_DAY =
            DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FORMAT_YEAR =
            DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT).withZone(ZoneOffset.UTC);

    private TickGenerator() {
    }

    /**
     * 生成三级刻度。
     *
     * @param type           轴类型
     * @param range          数据范围（退化与非正范围在这里被稳定化）
     * @param pixelLength    轴在屏幕上的像素长度；非有限或 ≤1 时按 1 处理
     * @param dataToDisplay  值 → 位置的映射，通常就是 {@link Axis#dataToDisplay(double)}
     * @return 按值升序排列的刻度；同一个值只出现一次（取最粗的级别）
     */
    public static Tick[] generate(AxisType type, AxisRange range, double pixelLength,
                                  DoubleUnaryOperator dataToDisplay) {
        if (type == null) {
            throw new IllegalArgumentException("type 不能为 null");
        }
        if (range == null) {
            throw new IllegalArgumentException("range 不能为 null");
        }
        if (dataToDisplay == null) {
            throw new IllegalArgumentException("dataToDisplay 不能为 null");
        }
        // Math.max(1.0, NaN) 是 NaN，得先挡掉非有限值，否则整条链路都会变成 NaN
        double length = (Double.isFinite(pixelLength) && pixelLength > 1.0) ? pixelLength : 1.0;
        return switch (type) {
            case LINEAR -> linear(range.withMinimumSpan(), length, dataToDisplay);
            case LOGARITHMIC -> logarithmic(range.withPositiveMin(), length, dataToDisplay);
            case TIME -> time(range.withMinimumSpan(), length, dataToDisplay);
            case TEXT -> text(range.withMinimumSpan(), length, dataToDisplay);
        };
    }

    /**
     * 主刻度步长：1/2/5 × 10ⁿ 里最接近"每 {@link #TARGET_SPACING_PX} 像素一格"的那个。
     *
     * <p>公开是为了让渲染后端选网格间距时用<strong>同一个函数</strong>——
     * 网格与刻度各自算一遍步长，迟早会错开半格。
     *
     * @param span        数据跨度；非法或 ≤0 时返回 1
     * @param pixelLength 像素长度
     * @return 步长
     */
    public static double niceStep(double span, double pixelLength) {
        if (!(span > 0) || !Double.isFinite(span)) {
            return 1.0;
        }
        double length = (Double.isFinite(pixelLength) && pixelLength > 0) ? pixelLength : 1.0;
        double cells = Math.max(1.0, length / TARGET_SPACING_PX);
        double rough = span / cells;
        double magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
        double normalized = rough / magnitude;
        double nice = normalized <= 1.0 ? 1.0 : normalized <= 2.0 ? 2.0 : normalized <= 5.0 ? 5.0 : 10.0;
        return nice * magnitude;
    }

    private static Tick[] linear(AxisRange range, double length, DoubleUnaryOperator map) {
        double step = niceStep(range.span(), length);
        List<Tick> out = new ArrayList<>();
        // 主/中/次 = step / step/2 / step/4。取 2 与 4（不是 2 与 5）是为了让包含关系
        // 在构造上成立：step/4 的格必然包含 step/2 的格，也必然包含 step 的格。
        addGrid(out, range, step, Tick.MAJOR, map);
        addGrid(out, range, step / 2.0, Tick.MEDIUM, map);
        addGrid(out, range, step / 4.0, Tick.MINOR, map);
        return finish(out);
    }

    private static void addGrid(List<Tick> out, AxisRange range, double step, int level,
                                DoubleUnaryOperator map) {
        if (!(step > 0) || !Double.isFinite(step)) {
            return;
        }
        double tolerance = EPS * Math.max(1.0, Math.abs(step));
        // 减 EPS 再向上取整：不减的话，min/step 恰好是整数时浮点误差会把它推到 2.0000000000000004，
        // ceil 之后变成 3，于是范围起点上的那个刻度凭空消失。
        long first = (long) Math.ceil(range.min() / step - EPS);
        for (long k = first, guard = 0; guard < MAX_TICKS; k++, guard++) {
            double value = clean(k * step, step);
            if (value > range.max() + tolerance) {
                return;
            }
            out.add(new Tick(value, map.applyAsDouble(value), label(value, level), level));
        }
    }

    private static Tick[] logarithmic(AxisRange range, double length, DoubleUnaryOperator map) {
        double logMin = Math.log10(range.min());
        double logMax = Math.log10(range.max());
        if (!(logMax - logMin >= 1.0)) {
            // 跨度不足一个数量级：10 的整数次幂里至多落进一个（常常一个都没有）。
            // 退化成同一区间上的线性 nice 刻度——定位仍然走对数映射，
            // 画面不至于是一条什么都没有的空轴。
            return linear(range, length, map);
        }
        List<Tick> out = new ArrayList<>();
        int firstExponent = (int) Math.ceil(logMin - EPS);
        int lastExponent = (int) Math.floor(logMax + EPS);
        int decadeStep = (int) Math.max(1.0, Math.rint(niceStep(logMax - logMin, length)));
        for (int exponent = firstExponent; exponent <= lastExponent; exponent += decadeStep) {
            addLogTick(out, range, exponent, 1, Tick.MAJOR, map);
        }
        if (decadeStep == 1) {
            // 一个数量级之内：1-2-5 是中刻度、1..9 是次刻度。
            // 包含关系天然成立（{1} ⊂ {1,2,5} ⊂ {1..9}）。
            int firstMantissaExponent = firstExponent - 1;
            for (int exponent = firstMantissaExponent; exponent <= lastExponent; exponent++) {
                addLogTick(out, range, exponent, 2, Tick.MEDIUM, map);
                addLogTick(out, range, exponent, 5, Tick.MEDIUM, map);
                for (int mantissa = 1; mantissa <= 9; mantissa++) {
                    addLogTick(out, range, exponent, mantissa, Tick.MINOR, map);
                }
            }
        }
        return finish(out);
    }

    private static void addLogTick(List<Tick> out, AxisRange range, int exponent, int mantissa,
                                   int level, DoubleUnaryOperator map) {
        double magnitude = Math.pow(10, exponent);
        double value = clean(mantissa * magnitude, magnitude);
        if (value < range.min() - EPS * Math.max(1.0, value)
                || value > range.max() + EPS * Math.max(1.0, value)) {
            return;
        }
        out.add(new Tick(value, map.applyAsDouble(value), label(value, level), level));
    }

    private static Tick[] time(AxisRange range, double length, DoubleUnaryOperator map) {
        double rough = range.span() / Math.max(1.0, length / TARGET_SPACING_PX);
        double step = TIME_STEPS_SECONDS[TIME_STEPS_SECONDS.length - 1];
        for (double candidate : TIME_STEPS_SECONDS) {
            if (candidate >= rough) {
                step = candidate;
                break;
            }
        }
        // 时间步长不总能被 4 整除（15 秒的四分之一是 3.75 秒，不是个"整齐"的时刻）。
        // 能整除时才细分：不能整除时三级用同一个步长，包含关系因此永远成立。
        double mediumStep = (step % 4.0 == 0.0) ? step / 2.0 : step;
        double minorStep = (step % 4.0 == 0.0) ? step / 4.0 : step;
        List<Tick> out = new ArrayList<>();
        addTimeGrid(out, range, step, Tick.MAJOR, map);
        addTimeGrid(out, range, mediumStep, Tick.MEDIUM, map);
        addTimeGrid(out, range, minorStep, Tick.MINOR, map);
        return finish(out);
    }

    private static void addTimeGrid(List<Tick> out, AxisRange range, double step, int level,
                                    DoubleUnaryOperator map) {
        if (!(step > 0)) {
            return;
        }
        long first = (long) Math.ceil(range.min() / step - EPS);
        for (long k = first, guard = 0; guard < MAX_TICKS; k++, guard++) {
            // 整秒的乘加在 double 里是精确的（这些数远小于 2⁵³），不会有二进制尾数
            double value = k * step;
            if (value > range.max()) {
                return;
            }
            out.add(new Tick(value, map.applyAsDouble(value), timeLabel(value, step, level), level));
        }
    }

    private static Tick[] text(AxisRange range, double length, DoubleUnaryOperator map) {
        long first = (long) Math.ceil(range.min() - EPS);
        long last = (long) Math.floor(range.max() + EPS);
        long count = last - first + 1;
        if (count <= 0) {
            return new Tick[0];
        }
        int maxLabels = (int) Math.max(1.0, Math.floor(length / TARGET_SPACING_PX));
        int stride = (int) Math.max(1.0, Math.ceil((double) count / maxLabels));
        List<Tick> out = new ArrayList<>();
        for (long index = first; index <= last; index += stride) {
            double value = index;
            out.add(new Tick(value, map.applyAsDouble(value), Long.toString(index), Tick.MAJOR));
        }
        return out.toArray(new Tick[0]);
    }

    /**
     * 按值排序并去重：同一个值只留一个，取最粗的级别（排序已保证它排在最前）。
     */
    private static Tick[] finish(List<Tick> ticks) {
        ticks.sort(Comparator.comparingDouble(Tick::value).thenComparingInt(Tick::level));
        List<Tick> deduped = new ArrayList<>(ticks.size());
        boolean hasPrevious = false;
        double previous = 0;
        for (Tick tick : ticks) {
            if (hasPrevious && tick.value() == previous) {
                continue;
            }
            deduped.add(tick);
            previous = tick.value();
            hasPrevious = true;
        }
        return deduped.toArray(new Tick[0]);
    }

    private static String label(double value, int level) {
        return level == Tick.MAJOR ? formatValue(value) : "";
    }

    private static String timeLabel(double epochSeconds, double step, int level) {
        if (level != Tick.MAJOR) {
            return "";
        }
        Instant instant = Instant.ofEpochSecond((long) epochSeconds);
        if (step >= 86400.0 * 365.0) {
            return FORMAT_YEAR.format(instant);
        }
        if (step >= 86400.0) {
            return FORMAT_DAY.format(instant);
        }
        if (step >= 60.0) {
            return FORMAT_MINUTE.format(instant);
        }
        return FORMAT_SECOND.format(instant);
    }

    /**
     * 把数值打印成人类可读的短字符串。
     *
     * <p><strong>必须显式指定 {@link Locale#ROOT}</strong>：某些区域用逗号做小数点，
     * 不指定的话同一段代码会给出 {@code "0,5"}，而测试只会在别人的机器上红。
     */
    private static String formatValue(double value) {
        if (value == 0.0) {
            return "0";
        }
        double abs = Math.abs(value);
        if (abs >= 1e7 || abs < 1e-4) {
            return trim(String.format(Locale.ROOT, "%.4e", value));
        }
        return trim(String.format(Locale.ROOT, "%.6f", value));
    }

    /** 去掉尾部多余的 0 与孤立的小数点，科学计数法的指数部分原样保留。 */
    private static String trim(String text) {
        int exponentAt = text.indexOf('e');
        String mantissa = exponentAt < 0 ? text : text.substring(0, exponentAt);
        String exponent = exponentAt < 0 ? "" : text.substring(exponentAt);
        if (mantissa.indexOf('.') >= 0) {
            int end = mantissa.length();
            while (end > 0 && mantissa.charAt(end - 1) == '0') {
                end--;
            }
            if (end > 0 && mantissa.charAt(end - 1) == '.') {
                end--;
            }
            mantissa = mantissa.substring(0, end);
        }
        return mantissa + exponent;
    }

    /**
     * 抹掉浮点乘法的二进制尾数：{@code 3 * 0.1} 是 {@code 0.30000000000000004}，
     * 直接进标签就会打印成 {@code "0.30000000000000004"}。
     *
     * <p>做法是按 step 的量级定出"该保留几位小数"，走一次十进制字符串往返。
     * <strong>不能写成"乘一个 pow(10, k) 再除回来"</strong>——那会把误差原样带回来
     * （{@code 3.0 * 0.1} 依旧是 {@code 0.30000000000000004}）。
     *
     * @param value 待清理的值
     * @param step  该级刻度的步长，决定保留几位小数
     * @return 清理后的值
     */
    private static double clean(double value, double step) {
        if (!Double.isFinite(value) || value == 0.0 || !(Math.abs(step) > 0)) {
            return value;
        }
        double magnitude = Math.floor(Math.log10(Math.abs(step)));
        int decimals = (int) Math.max(0, Math.min(12, 6 - magnitude));
        return Double.parseDouble(String.format(Locale.ROOT, "%." + decimals + "f", value));
    }
}
