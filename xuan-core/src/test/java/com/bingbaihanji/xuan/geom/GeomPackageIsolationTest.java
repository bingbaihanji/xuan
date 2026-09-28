package com.bingbaihanji.xuan.geom;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机械地守住 {@code geom/} 的包依赖规则：几何计算必须零 GL 依赖，
 * 这样平坦化、三角化、描边才能脱离窗口做单元测试，也才能在无 GL 上下文的机器上跑。
 *
 * <p>本项目没有静态分析插件（{@code pom.xml} 里没有 checkstyle/archunit 之类），
 * 所以把这条规则写成测试——加一个 {@code import com.bingbaihanji.xuan.gl.*}
 * 或在注释外提到 {@code xuan.gl} 都会在这里挂掉，而不是等到某个无头环境里才炸。
 *
 * <p>注意本测试读的是 {@code src/main} 下的源文件本身（不是编译产物），
 * 因此它检查的是「写下的依赖」；顺带一提，{@link Tessellator}/{@link StrokeGenerator}
 * 的类注释里那句「本类不依赖 gl 包」不会被误伤——那里写的是 {@code gl}，不是 {@code xuan.gl}。
 */
class GeomPackageIsolationTest {

    /** geom 包的源码目录（相对项目根，surefire 的工作目录就是项目根）。 */
    private static final Path GEOM_SOURCE_DIR =
            Path.of("src", "main", "java", "com", "bingbaihanji", "xuan", "geom");

    /** 禁止出现的依赖标记：任何对 {@code com.bingbaihanji.xuan.gl} 的引用都会包含它。 */
    private static final String FORBIDDEN = "xuan.gl";

    @Test
    void geom包不依赖gl包() throws IOException {
        assertTrue(Files.isDirectory(GEOM_SOURCE_DIR),
                "找不到 geom 源码目录：" + GEOM_SOURCE_DIR.toAbsolutePath()
                        + "（工作目录应为项目根，找错了目录会让本测试形同虚设）");

        List<Path> sources;
        try (Stream<Path> files = Files.list(GEOM_SOURCE_DIR)) {
            sources = files.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
        }
        assertTrue(sources.size() >= 4,
                "geom 包应至少有 4 个源文件（Path/Flattener/Tessellator/StrokeGenerator），实际 "
                        + sources.size() + " 个");

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            String[] lines = text.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(FORBIDDEN)) {
                    violations.add(GEOM_SOURCE_DIR.relativize(source) + ":" + (i + 1)
                            + " -> " + lines[i].strip());
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "geom/ 必须零 GL 依赖，以下位置引用了 " + FORBIDDEN + "：\n"
                        + String.join("\n", violations));
    }
}
