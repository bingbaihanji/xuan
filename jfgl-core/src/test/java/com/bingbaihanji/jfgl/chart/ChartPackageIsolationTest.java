package com.bingbaihanji.jfgl.chart;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 机械地守住 {@code chart/} 的两条边界。
 *
 * <p>本项目没有静态分析插件（{@code pom.xml} 里没有 checkstyle / archunit 之类），
 * 所以把规则写成测试——这也是 {@code GeomPackageIsolationTest} 的做法。
 *
 * <p>边界为什么值钱：① 的全部价值在于"能脱离 GL 上下文单测"。一旦有一个文件
 * {@code import} 了 GL 相关的东西，它就不再可测，而**这个代价是逐渐累积的**——
 * 第一个越界的人会觉得"就一次，无伤大雅"。
 */
class ChartPackageIsolationTest {

    /** chart 包的源码目录（相对项目根，surefire 的工作目录就是项目根）。 */
    private static final Path CHART_SOURCE_DIR =
            Path.of("src", "main", "java", "com", "bingbaihanji", "jfgl", "chart");

    /** chart/ 只允许依赖这些兄弟包。 */
    private static final Set<String> ALLOWED = Set.of("chart", "math", "util");

    private static final Pattern JFGL_PACKAGE =
            Pattern.compile("com\\.bingbaihanji\\.jfgl\\.([A-Za-z_][A-Za-z0-9_]*)");

    @Test
    void chart包只依赖math与util() throws IOException {
        assertTrue(Files.isDirectory(CHART_SOURCE_DIR),
                "找不到 chart 源码目录：" + CHART_SOURCE_DIR.toAbsolutePath()
                        + "（工作目录应为项目根，找错了目录会让本测试形同虚设）");

        List<Path> sources;
        // 必须递归：chart/ 下允许再分子包，非递归的话整棵子树对守卫不可见，
        // 子包里随便 import renderer/ 都不会响（Task 9 用探针实测过）。
        try (Stream<Path> files = Files.walk(CHART_SOURCE_DIR)) {
            sources = files.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
        }
        assertTrue(sources.size() >= 16,
                "chart 包应至少有 16 个源文件（规格 §4 列了 15 个，外加 RenderContext），实际 "
                        + sources.size() + " 个");

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String[] lines = Files.readString(source, StandardCharsets.UTF_8).split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = JFGL_PACKAGE.matcher(lines[i]);
                while (matcher.find()) {
                    if (!ALLOWED.contains(matcher.group(1))) {
                        violations.add(CHART_SOURCE_DIR.relativize(source) + ":" + (i + 1)
                                + " -> " + lines[i].strip());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "chart/ 是零 GL 依赖的纯计算层，只能依赖 math/ 与 util/。"
                        + "以下位置引用了别的包：\n" + String.join("\n", violations)
                        + "\n若发现必须引用 gl/、renderer/、text/、geom/ 才能写下去，"
                        + "说明 ① 的分界线画错了，应当停下来重新讨论");
    }

    @Test
    void RenderContext是空接口() {
        Method[] methods = RenderContext.class.getDeclaredMethods();
        assertEquals(0, methods.length,
                "RenderContext 不该有任何方法，实际有 " + methods.length + " 个："
                        + java.util.Arrays.toString(methods)
                        + "。它属于 ②（渲染后端），① 只声明这个类型存在。"
                        + "② 要加东西请定义子接口（GLRenderContext extends RenderContext），"
                        + "在原地给它加方法等于把 ② 的东西漏进了 ①");
        assertEquals(0, RenderContext.class.getDeclaredFields().length,
                "RenderContext 也不该有任何字段");
        assertEquals(0, RenderContext.class.getDeclaredClasses().length,
                "RenderContext 也不该有任何嵌套类型，实际有 "
                        + java.util.Arrays.toString(RenderContext.class.getDeclaredClasses())
                        + " 个。嵌套类型是把 ② 的概念（比如一个 Slot 载体）偷运进 ① 的现成路子："
                        + "它既不是方法也不是字段，只有断言 getDeclaredClasses() 才拦得住。"
                        + "② 要放东西请定义子接口（GLRenderContext extends RenderContext）");
    }
}
