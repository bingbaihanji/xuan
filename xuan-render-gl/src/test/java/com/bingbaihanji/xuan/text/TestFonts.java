package com.bingbaihanji.xuan.text;

import org.junit.jupiter.api.Assumptions;

import java.nio.file.Path;

/**
 * 字体相关的单测**共用的那一个入口**。
 *
 * <p>2026-09-28 起本库**不再自带字体**（原先的 `simhei.ttf` 是专有字体，与 MIT 声明冲突），
 * 于是这些测试也需要一个外部的：
 *
 * <pre>
 * mvn -o test -Dxuan.text.font=/path/to/NotoSansSC-Regular.ttf
 * </pre>
 *
 * <p><strong>没设属性时用 {@link Assumptions#assumeTrue} 让它可见地跳过</strong>，
 * 而不是失败也不是静默通过：
 * <ul>
 *   <li>**不是失败**——一个全新 clone 里没有字体是正常的，不该让 `mvn test` 变红；</li>
 *   <li>**也不是静默通过**——surefire 的汇总里会计入 `Skipped`，那是一个**看得见**的分类
 *       （本仓库本来就有 2 条 `@Disabled` 在那一栏）。</li>
 * </ul>
 * <p>⚠️ 代价照实说：**不设属性时，文本子系统的单测覆盖是 0**（跳过 N 条），
 * 而它本来有 `FontFileTest` / `GlyphRasterizerTest` 两组。
 */
final class TestFonts {

    /** 与 `xuan-javafx` 侧 `TextFont.kt` 的 `FONT_PROPERTY` 是**同一个名字**，故意如此。 */
    static final String PROPERTY = "xuan.text.font";

    private TestFonts() {
    }

    /**
     * 取字体路径；**没设属性时跳过当前测试类**。
     *
     * @return 属性指向的路径（不校验存在性——那交给 {@link FontFile#load}）
     */
    /** 没设属性时抛断言失败（**只在 {@code @BeforeEach} 里调**，理由见那里）。 */
    static void require() {
        Assumptions.assumeTrue(isSet(), HINT);
    }

    /** 设了属性就加载，没设返回 null。**给 `@BeforeAll` 用**——它不该自行跳过。 */
    static FontFile loadOrNull() {
        return isSet() ? FontFile.load(Path.of(System.getProperty(PROPERTY))) : null;
    }

    /** 属性指向的路径。**调用方必须已经被 {@link #require()} 守过**。 */
    static Path path() {
        return Path.of(System.getProperty(PROPERTY));
    }

    static boolean isSet() {
        String p = System.getProperty(PROPERTY);
        return p != null && !p.isBlank();
    }

    /** 没设属性时该说的那句话。 */
    static final String HINT =
            "需要字体：-D" + PROPERTY + "=<某个 .ttf 的路径>"
                    + "（本库不再自带字体——原先那份 simhei.ttf 是专有字体，与 MIT 声明冲突）";
}
