package com.bingbaihanji.xuan.example

import com.bingbaihanji.xuan.text.FontFile
import java.io.File

/**
 * 示例与校验器共用的**字体入口**。
 *
 * <h2>为什么要有这么一个东西</h2>
 * <p>2026-09-28 起本库**不再自带字体**——原先那份 `simhei.ttf` 是微软/中易的专有字体，
 * 与项目的 MIT 声明冲突，开源前必须移除。而字体去掉之后，**凡是要画字或量字的地方都得
 * 自己有一个**：示例要，校验器也要（`ChartVerifier` 经 `ChartPainter.width`、
 * `AxisVerifier` 经它自己的 `GcMetrics.measureText`、`ClickVerifier` 要画标签…）。
 *
 * <p>所以这里收成**一个判定、一个系统属性**：
 *
 * <pre>
 * -Dxuan.text.font=/path/to/NotoSansSC-Regular.ttf
 * </pre>
 *
 * <p>⚠️ **不是每个调用点各读一次属性**——那正是本仓库吃过亏的形态
 * （"共用属性名 ≠ 共用判定"，见 `CLAUDE.md` 里那次被推翻的"间歇性缺陷"）。
 *
 * <h2>没设属性时各处的处置**不一样，是刻意的**</h2>
 * <ul>
 *   <li><b>示例</b>（`PipelineExample` / `ClickExample` / `ClickDslExample` / `XuanDemo`）：
 *       传 null 进去。此时文本相关的调用会**抛出**并指明怎么补——示例本来就要人看着跑，
 *       一句明确的异常比一个"文字没画出来"的画面有用。</li>
 *   <li><b>校验器</b>：**明确失败并以 1 退出**。它们没有"降级跑"这个选项——
 *       跳掉文本那几条断言却报"全部通过"，正是本仓库最忌讳的那种静默的绿。</li>
 * </ul>
 *
 * @return 属性指向的文件；**没设属性时为 null**（不校验存在性——那交给 [FontFile.load]，
 *         它的失败信息里有路径）
 */
/** 字体文件的系统属性名。 */
const val FONT_PROPERTY = "xuan.text.font"

fun textFontFile(): File? = System.getProperty(FONT_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)

/** [textFontFile] 的 `FontFile` 形式（给 `FXGLTransfer(font = …)` 用）；没设属性时为 null。 */
fun textFont(): FontFile? = textFontFile()?.let { FontFile.load(it.toPath()) }

/**
 * 校验器在**没设 [FONT_PROPERTY] 时**该打的那句话。
 *
 * <p>写成常量是为了它只有一份：所有校验器必须给出**一模一样**的指引，
 * 否则下一个人要按 N 份措辞各猜一次。
 */
const val FONT_PROPERTY_HINT: String =
    "本校验器需要字体：请用 -D$FONT_PROPERTY=<某个 .ttf 的路径> 指定。" +
            "（本库不再自带字体——原先那份 simhei.ttf 是专有字体，与 MIT 声明冲突。）" +
            "建议用 OFL 授权的静态版 Noto Sans SC；避开可变字体与 OTF/CFF，理由见 fonts/README.md。"

