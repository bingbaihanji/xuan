# 字体资源

## simhei.ttf

- **来源**：本机 `C:\Windows\Fonts\simhei.ttf`，Windows 自带的「黑体」（SimHei）。
- **大小**：9745792 字节（9.7 MB）。
- **为什么会在这里**：SDF 文本子系统需要一份能覆盖 CJK 常用区的字体，
  而它必须随仓库走，否则 `TextVerifier` 换台机器就跑不起来。

### ⚠️ 授权

**黑体是微软 / 中易（ZhongYi）的专有字体，不是自由字体。**

- 本机自用、内部开发、跑测试：没有问题。
- **若这个仓库要公开分发**（开源、发布 jar、镜像到别的平台）：**必须换掉它**。
  可选的 OFL 授权替代品：`NotoSansSC-VF.ttf`（同目录下就有，但它是可变字体，
  见下）、或从网上取静态版 Noto Sans SC / 思源黑体。

### 换字体的方法

替换本目录下的 `simhei.ttf` 文件即可（资源路径 `GlyphRasterizer` 一侧是写死的
`/fonts/simhei.ttf`，见 `FontFile.DEFAULT_RESOURCE`）。

选字体时有两条硬性约束：

1. **优先选 TTF（`glyf` 轮廓），避开 OTF（`CFF`/PostScript 轮廓）。**
   `stb_truetype` 对 CFF 的支持较弱，这是本期直接选 `simhei.ttf` 的原因。
2. **避开可变字体（带 `fvar`/`gvar` 表的 `.ttf`）。**
   `stb` 会忽略变体轴、只渲染默认实例——原则上能用，但第一次实现时不该同时
   跟可变字体较劲。`NotoSansSC-VF.ttf` 就属于这一类。

换完之后必须重跑 `TextVerifier`（见 `CLAUDE.md` 的「怎么验证改动」）：
不同的字体度量不同，`TextVerifier` 里凡是与具体字体相关的期望值都要重新核对。

### 本文件的核对结果

上面的两条约束不是照抄的猜测，是对入库的这份文件实测过的（读 sfnt 表目录）：

```
sfnt version: 0x00010000   ← TrueType（不是 'OTTO'）
numTables = 20
has glyf?  -> True         ← glyf 轮廓，stb 的主场
has CFF ?  -> False        ← 不是 PostScript 轮廓
has fvar?  -> False        ← 不是可变字体
has gvar?  -> False
```
