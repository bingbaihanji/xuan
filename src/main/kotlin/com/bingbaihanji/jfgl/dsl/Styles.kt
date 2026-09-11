package com.bingbaihanji.jfgl.dsl

import com.bingbaihanji.jfgl.style.FillStyle
import com.bingbaihanji.jfgl.style.StrokeStyle
import com.bingbaihanji.jfgl.style.TextStyle
import com.bingbaihanji.jfgl.style.Style
import com.bingbaihanji.jfgl.util.Color

// ── Color constants ───────────────────────────────────────────────────────

/** Predefined color constants for convenient access. */
val RED: Color   = Color.RED
val GREEN: Color = Color.GREEN
val BLUE: Color  = Color.BLUE
val WHITE: Color = Color.WHITE
val BLACK: Color = Color.BLACK

// ── Color factory functions ───────────────────────────────────────────────

/**
 * Creates a [Color] from RGBA components in the range 0.0 – 1.0.
 */
fun color(r: Float, g: Float, b: Float, a: Float = 1.0f): Color =
    Color(r.coerceIn(0.0f, 1.0f), g.coerceIn(0.0f, 1.0f), b.coerceIn(0.0f, 1.0f), a.coerceIn(0.0f, 1.0f))

/**
 * Creates a [Color] from a hex string.
 * Accepted formats: `"#RRGGBB"`, `"RRGGBB"`.
 */
fun color(hex: String): Color = Color.fromHex(hex)

/**
 * Creates an opaque [Color] from integer RGB components in the range 0 – 255.
 */
fun rgb(r: Int, g: Int, b: Int): Color = Color.fromRGB(r, g, b)

// ── Style factory functions ───────────────────────────────────────────────

/**
 * Creates a [FillStyle] with the given [color] and optional [opacity].
 */
fun fill(color: Color = BLACK, opacity: Float = 1.0f): FillStyle =
    FillStyle.of(color, opacity)

/**
 * Creates a [StrokeStyle] with the given [color] and [width].
 */
fun stroke(color: Color = BLACK, width: Float = 1.0f): StrokeStyle =
    StrokeStyle.of(color, width)

/**
 * Creates a [TextStyle] with the given [family], [size], and [color].
 */
fun text(family: String = "SansSerif", size: Float = 16f, color: Color = BLACK): TextStyle =
    TextStyle.of(family, size, color)

// ── StyleBuilder ──────────────────────────────────────────────────────────

/**
 * DSL builder for composing a [Style] incrementally.
 *
 * Usage:
 * ```kotlin
 * val myStyle = style {
 *     fill(RED, opacity = 0.8f)
 *     stroke(BLUE, width = 2.0f)
 *     text("Consolas", 16f, BLACK)
 * }
 * ```
 */
class StyleBuilder {

    private var fillStyle: FillStyle? = null
    private var strokeStyle: StrokeStyle? = null
    private var textStyle: TextStyle? = null

    /** Sets or overrides the fill style. */
    fun fill(color: Color = BLACK, opacity: Float = 1.0f) {
        fillStyle = FillStyle.of(color, opacity)
    }

    /** Sets or overrides the stroke style. */
    fun stroke(color: Color = BLACK, width: Float = 1.0f) {
        strokeStyle = StrokeStyle.of(color, width)
    }

    /** Sets or overrides the text style. */
    fun text(family: String = "SansSerif", size: Float = 16f, color: Color = BLACK) {
        textStyle = TextStyle.of(family, size, color)
    }

    /** Returns the composed [Style] from the current builder state. */
    fun build(): Style {
        val s = Style()
        fillStyle?.let { s.setFill(it) }
        strokeStyle?.let { s.setStroke(it) }
        textStyle?.let { s.setText(it) }
        return s
    }
}

// ── Top-level DSL entry point ─────────────────────────────────────────────

/**
 * Creates a [Style] using the DSL builder.
 *
 * Example:
 * ```kotlin
 * val myStyle = style {
 *     fill(GREEN, 0.5f)
 *     stroke(RED, 3.0f)
 *     text("Monospaced", 12f, WHITE)
 * }
 * ```
 */
fun style(block: StyleBuilder.() -> Unit): Style =
    StyleBuilder().apply(block).build()
