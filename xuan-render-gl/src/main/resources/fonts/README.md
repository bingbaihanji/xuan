# 字体资源

**本目录现在（2026-09-28 起）是空的——本库不再自带任何字体。**

## 为什么去掉

原先这里放着一份 `simhei.ttf`，实测与 `C:\Windows\Fonts\simhei.ttf`
**逐字节相同**（md5 `4093871a7f48e43b9ce7c38da0c34809`）。它是**微软 / 中易（ZhongYi）
的专有字体**，与项目的 MIT 声明**直接冲突**——把专有字体按 MIT 分发是不成立的。
公开开源之前必须移除，于是移除了。

## 那要画字怎么办：自己指定一个

字体现在是**可选能力**，由调用方在**构造时**给：

```kotlin
// DSL
xuan {
    font = File("/path/to/NotoSansSC-Regular.ttf")
    onRender { drawText("你好", 20f, 40f) }
}

// 或者直接用桥接器
FXGLTransfer(font = FontFile.load(Path.of("/path/to/font.ttf")))
```

**没给字体时**：`drawText` / `measureText` 会**抛 `IllegalStateException`**，消息里
写着怎么补；其余绘制一切照常。

> 为什么是抛而不是"画不出来就算了"：静默什么都不画，与"这一帧在文字那段抛了异常"
> 在画面上**逐像素相同**——而本项目最防的就是这种静默错误输出。

## 选字体的两条硬约束

1. **优先 TTF（`glyf` 轮廓），避开 OTF（`CFF`/PostScript 轮廓）。**
   `stb_truetype` 对 CFF 的支持较弱。
2. **避开可变字体（带 `fvar`/`gvar` 表的 `.ttf`）。**
   `stb` 会忽略变体轴、只渲染默认实例。Windows 自带的
   `C:\Windows\Fonts\NotoSansSC-VF.ttf` 就属于这一类——**不能用**。

推荐 **OFL 授权的静态版 Noto Sans SC / 思源黑体**（需要联网下载静态版；
Google Fonts 的下载包里给的就是静态实例）。

## ⚠️ 字体还牵动两处验收

- **`TextVerifier`**（像素校验器）**需要字体才能跑**：
  ```bash
  mvn -o compile exec:exec "-Dexec.executable=java" "-Dexec.classpathScope=runtime" \
      "-Dexec.args=-Dstdout.encoding=UTF-8 -Dxuan.text.font=<路径> -cp %classpath com.bingbaihanji.xuan.example.TextVerifierKt"
  ```
  **没给就明确失败并以 1 退出**（不是静默跳过——跳掉文本断言却报"全部通过"，
  正是本项目最忌讳的那种静默的绿）。
- **单测里的 `FontFileTest` / `GlyphRasterizerTest` 共 9 条**同理：
  不给属性时它们**记成 `Skipped`**（surefire 汇总里看得见），
  而不是消失、也不是报错。跑法：
  ```bash
  mvn -o test -Dxuan.text.font=/path/to/font.ttf
  ```
- **其余七个校验器也有几条断言要量文字**（`ChartVerifier` 经 `ChartPainter.width`、
  `AxisVerifier` 经它自己的度量、`ClickVerifier` 要画标签…），所以统一建议：
  **跑任何校验器都带上 `-Dxuan.text.font=<路径>`**。

## 系统的属性名只有一份

`xuan.text.font`——`xuan-javafx` 侧的 `example/TextFont.kt` 与
`xuan-render-gl` 测试侧的 `text/TestFonts.java` 都用它。
**共用属性名 ≠ 共用判定**是本仓库吃过的亏，所以两边都是"一个判定函数"，
不是各读一次属性。
