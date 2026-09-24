package com.bingbaihanji.jfgl.util;

/**
 * 不可变的 RGBA 颜色表示，使用 0-1 范围的浮点分量。
 */
public record Color(float r, float g, float b, float a) {

    /** 白色 */
    public static final Color WHITE = new Color(1f, 1f, 1f);

    /** 黑色 */
    public static final Color BLACK = new Color(0f, 0f, 0f);

    /** 红色 */
    public static final Color RED = new Color(1f, 0f, 0f);

    /** 绿色 */
    public static final Color GREEN = new Color(0f, 1f, 0f);

    /** 蓝色 */
    public static final Color BLUE = new Color(0f, 0f, 1f);

    /**
     * 使用 RGB 分量创建颜色，alpha 默认为 1。
     *
     * @param r 红色分量（0-1）
     * @param g 绿色分量（0-1）
     * @param b 蓝色分量（0-1）
     */
    public Color(float r, float g, float b) {
        this(r, g, b, 1f);
    }

    /**
     * 从 0-255 的整数 RGB 分量创建颜色。
     *
     * @param r 红色分量（0-255）
     * @param g 绿色分量（0-255）
     * @param b 蓝色分量（0-255）
     * @return 新的颜色
     */
    public static Color fromRGB(int r, int g, int b) {
        return new Color(r / 255f, g / 255f, b / 255f);
    }

    /**
     * 从十六进制字符串创建颜色。
     * <p>
     * 接受 "#RRGGBB"、"#RRGGBBAA"、"RRGGBB" 或 "RRGGBBAA" 格式。
     *
     * @param hex 十六进制颜色字符串
     * @return 新的颜色
     * @throws IllegalArgumentException 如果字符串格式无效
     */
    public static Color fromHex(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("十六进制字符串不能为 null");
        }
        hex = hex.startsWith("#") ? hex.substring(1) : hex;
        if (hex.length() == 6) {
            int rgb = Integer.parseInt(hex, 16);
            return new Color(
                    ((rgb >> 16) & 0xFF) / 255f,
                    ((rgb >> 8) & 0xFF) / 255f,
                    (rgb & 0xFF) / 255f
            );
        } else if (hex.length() == 8) {
            int rgba = (int) Long.parseLong(hex, 16);
            return new Color(
                    ((rgba >> 24) & 0xFF) / 255f,
                    ((rgba >> 16) & 0xFF) / 255f,
                    ((rgba >> 8) & 0xFF) / 255f,
                    (rgba & 0xFF) / 255f
            );
        } else {
            throw new IllegalArgumentException("十六进制字符串必须为 6 或 8 个字符（可选前导 '#'）");
        }
    }

    /**
     * 返回具有指定 alpha 值的新颜色。
     *
     * @param alpha 新的 alpha 值（0-1）
     * @return 新颜色
     */
    public Color withAlpha(float alpha) {
        return new Color(r, g, b, alpha);
    }

    /**
     * 将颜色分量作为浮点数组返回 [r, g, b, a]。
     *
     * @return 包含颜色分量的浮点数组
     */
    public float[] toArray() {
        return new float[]{r, g, b, a};
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Color(float r1, float g1, float b1, float a1))) {
            return false;
        }
        return Float.compare(r, r1) == 0
                && Float.compare(g, g1) == 0
                && Float.compare(b, b1) == 0
                && Float.compare(a, a1) == 0;
    }

    @Override
    public String toString() {
        return "Color[r=%.4f, g=%.4f, b=%.4f, a=%.4f]".formatted(r, g, b, a);
    }
}
