package com.bingbaihanji.jfgl.chart;

/**
 * 图表 hover 交互的外观与行为配置。
 *
 * <p>该配置不依赖 JavaFX 或 OpenGL，可以在应用线程创建并安全替换到
 * {@link ChartInteraction}。颜色采用项目统一的 {@code 0xAARRGGBB}。
 */
public record ChartInteractionConfig(
        boolean enabled, // 是否启用 hover 交互
        boolean crosshairVisible, // 是否显示十字线
        boolean tooltipVisible, // 是否显示提示框
        float snapRadius, // 吸附半径（像素）
        int crosshairColor, // 十字线颜色，0xAARRGGBB
        float crosshairWidth, // 十字线宽度
        float dashLength, // 虚线长度
        int tooltipBackground, // 提示框背景色，0xAARRGGBB
        int tooltipBorder, // 提示框边框色，0xAARRGGBB
        int tooltipText, // 提示框文字色，0xAARRGGBB
        float tooltipFontSize, // 提示框字体大小
        float tooltipPadding, // 提示框内边距
        float tooltipOffset, // 提示框偏移
        ChartValueFormatter formatter // 值格式化器
) {

    public ChartInteractionConfig {
        // 校验尺寸参数必须是有限数，且符合非负/正数要求
        if (!Float.isFinite(snapRadius) || snapRadius < 0f
                || !Float.isFinite(crosshairWidth) || crosshairWidth <= 0f
                || !Float.isFinite(dashLength) || dashLength <= 0f
                || !Float.isFinite(tooltipFontSize) || tooltipFontSize <= 0f
                || !Float.isFinite(tooltipPadding) || tooltipPadding < 0f
                || !Float.isFinite(tooltipOffset) || tooltipOffset < 0f) {
            throw new IllegalArgumentException("图表交互尺寸必须是有限的非负/正数");
        }
        // 格式化器不能为空
        if (formatter == null) {
            throw new IllegalArgumentException("formatter 不能为 null");
        }
    }

    /** 默认：开启吸附、十字线和提示框。 */
    public static ChartInteractionConfig defaults() {
        return new ChartInteractionConfig(true, true, true,
                18f, 0xAA9CA8B8, 1f, 6f,
                0xEE20252C, 0xFF64748B, 0xFFF1F5F9,
                12f, 8f, 12f, ChartValueFormatter.DEFAULT);
    }

    /** 返回设置是否启用交互后的新配置。 */
    public ChartInteractionConfig enabled(boolean value) {
        return new ChartInteractionConfig(value, crosshairVisible, tooltipVisible, snapRadius,
                crosshairColor, crosshairWidth, dashLength, tooltipBackground, tooltipBorder,
                tooltipText, tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    /** 返回设置是否显示十字线后的新配置。 */
    public ChartInteractionConfig crosshairVisible(boolean value) {
        return new ChartInteractionConfig(enabled, value, tooltipVisible, snapRadius,
                crosshairColor, crosshairWidth, dashLength, tooltipBackground, tooltipBorder,
                tooltipText, tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    /** 返回设置是否显示提示框后的新配置。 */
    public ChartInteractionConfig tooltipVisible(boolean value) {
        return new ChartInteractionConfig(enabled, crosshairVisible, value, snapRadius,
                crosshairColor, crosshairWidth, dashLength, tooltipBackground, tooltipBorder,
                tooltipText, tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    /** 返回设置吸附半径后的新配置。 */
    public ChartInteractionConfig snapRadius(float value) {
        return copy(enabled, crosshairVisible, tooltipVisible, value, crosshairColor,
                crosshairWidth, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    /** 返回设置十字线颜色后的新配置。 */
    public ChartInteractionConfig crosshairColor(int value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, value,
                crosshairWidth, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    /** 返回设置十字线宽度后的新配置。 */
    public ChartInteractionConfig crosshairWidth(float value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                value, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    /** 返回设置虚线长度后的新配置。 */
    public ChartInteractionConfig dashLength(float value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                crosshairWidth, value, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    /** 返回一次性设置提示框背景色、边框色、文字色后的新配置。 */
    public ChartInteractionConfig tooltipColors(int background, int border, int text) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                crosshairWidth, dashLength, background, border, text,
                tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    /** 返回设置提示框字体大小后的新配置。 */
    public ChartInteractionConfig tooltipFontSize(float value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                crosshairWidth, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                value, tooltipPadding, tooltipOffset, formatter);
    }

    /** 返回设置提示框内边距后的新配置。 */
    public ChartInteractionConfig tooltipPadding(float value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                crosshairWidth, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, value, tooltipOffset, formatter);
    }

    /** 返回设置提示框偏移后的新配置。 */
    public ChartInteractionConfig tooltipOffset(float value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                crosshairWidth, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, tooltipPadding, value, formatter);
    }

    /** 返回设置值格式化器后的新配置。 */
    public ChartInteractionConfig formatter(ChartValueFormatter value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                crosshairWidth, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, tooltipPadding, tooltipOffset, value);
    }

    /** 内部统一复制入口，避免各 with 方法重复写构造逻辑。 */
    private ChartInteractionConfig copy(boolean enabled, boolean crosshairVisible,
                                        boolean tooltipVisible, float snapRadius,
                                        int crosshairColor, float crosshairWidth,
                                        float dashLength, int tooltipBackground,
                                        int tooltipBorder, int tooltipText,
                                        float tooltipFontSize, float tooltipPadding,
                                        float tooltipOffset, ChartValueFormatter formatter) {
        return new ChartInteractionConfig(enabled, crosshairVisible, tooltipVisible, snapRadius,
                crosshairColor, crosshairWidth, dashLength, tooltipBackground, tooltipBorder,
                tooltipText, tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }
}
