package com.bingbaihanji.jfgl.chart;

/**
 * 图表 hover 交互的外观与行为配置。
 *
 * <p>该配置不依赖 JavaFX 或 OpenGL，可以在应用线程创建并安全替换到
 * {@link ChartInteraction}。颜色采用项目统一的 {@code 0xAARRGGBB}。
 */
public record ChartInteractionConfig(
        boolean enabled,
        boolean crosshairVisible,
        boolean tooltipVisible,
        float snapRadius,
        int crosshairColor,
        float crosshairWidth,
        float dashLength,
        int tooltipBackground,
        int tooltipBorder,
        int tooltipText,
        float tooltipFontSize,
        float tooltipPadding,
        float tooltipOffset,
        ChartValueFormatter formatter
) {

    public ChartInteractionConfig {
        if (!Float.isFinite(snapRadius) || snapRadius < 0f
                || !Float.isFinite(crosshairWidth) || crosshairWidth <= 0f
                || !Float.isFinite(dashLength) || dashLength <= 0f
                || !Float.isFinite(tooltipFontSize) || tooltipFontSize <= 0f
                || !Float.isFinite(tooltipPadding) || tooltipPadding < 0f
                || !Float.isFinite(tooltipOffset) || tooltipOffset < 0f) {
            throw new IllegalArgumentException("图表交互尺寸必须是有限的非负/正数");
        }
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

    public ChartInteractionConfig enabled(boolean value) {
        return new ChartInteractionConfig(value, crosshairVisible, tooltipVisible, snapRadius,
                crosshairColor, crosshairWidth, dashLength, tooltipBackground, tooltipBorder,
                tooltipText, tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    public ChartInteractionConfig crosshairVisible(boolean value) {
        return new ChartInteractionConfig(enabled, value, tooltipVisible, snapRadius,
                crosshairColor, crosshairWidth, dashLength, tooltipBackground, tooltipBorder,
                tooltipText, tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    public ChartInteractionConfig tooltipVisible(boolean value) {
        return new ChartInteractionConfig(enabled, crosshairVisible, value, snapRadius,
                crosshairColor, crosshairWidth, dashLength, tooltipBackground, tooltipBorder,
                tooltipText, tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    public ChartInteractionConfig snapRadius(float value) {
        return copy(enabled, crosshairVisible, tooltipVisible, value, crosshairColor,
                crosshairWidth, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    public ChartInteractionConfig crosshairColor(int value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, value,
                crosshairWidth, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    public ChartInteractionConfig crosshairWidth(float value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                value, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    public ChartInteractionConfig dashLength(float value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                crosshairWidth, value, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    public ChartInteractionConfig tooltipColors(int background, int border, int text) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                crosshairWidth, dashLength, background, border, text,
                tooltipFontSize, tooltipPadding, tooltipOffset, formatter);
    }

    public ChartInteractionConfig tooltipFontSize(float value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                crosshairWidth, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                value, tooltipPadding, tooltipOffset, formatter);
    }

    public ChartInteractionConfig tooltipPadding(float value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                crosshairWidth, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, value, tooltipOffset, formatter);
    }

    public ChartInteractionConfig tooltipOffset(float value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                crosshairWidth, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, tooltipPadding, value, formatter);
    }

    public ChartInteractionConfig formatter(ChartValueFormatter value) {
        return copy(enabled, crosshairVisible, tooltipVisible, snapRadius, crosshairColor,
                crosshairWidth, dashLength, tooltipBackground, tooltipBorder, tooltipText,
                tooltipFontSize, tooltipPadding, tooltipOffset, value);
    }

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
