package com.bingbaihanji.jfgl.dsl

import com.bingbaihanji.jfgl.engine.DrawEngine
import com.bingbaihanji.jfgl.math.Vec2
import com.bingbaihanji.jfgl.renderer.Path
import com.bingbaihanji.jfgl.scene.GroupNode
import com.bingbaihanji.jfgl.scene.Node
import com.bingbaihanji.jfgl.scene.Scene
import com.bingbaihanji.jfgl.scene.ShapeNode
import com.bingbaihanji.jfgl.scene.TextNode
import com.bingbaihanji.jfgl.style.FillStyle
import com.bingbaihanji.jfgl.style.StrokeStyle
import com.bingbaihanji.jfgl.style.TextStyle
import com.bingbaihanji.jfgl.util.Color

// ---------------------------------------------------------------------------
// DrawEngine extension
// ---------------------------------------------------------------------------

/**
 * Creates a new [Scene] using a declarative DSL block and assigns it as the
 * active scene on this [DrawEngine].
 *
 * ```kotlin
 * engine.scene {
 *     rect(10, 10, 200, 100) {
 *         fill(Color.RED)
 *         stroke(Color.BLACK, 2f)
 *     }
 *     text("Hello", 50, 50) {
 *         font("Arial", 24f, Color.WHITE)
 *     }
 * }
 * ```
 */
fun DrawEngine.scene(block: SceneBuilder.() -> Unit): Scene {
    val builder = SceneBuilder()
    builder.block()
    val scene = builder.build()
    setScene(scene)
    return scene
}

// ---------------------------------------------------------------------------
// SceneBuilder
// ---------------------------------------------------------------------------

/**
 * Top-level DSL builder for constructing a [Scene] and its child nodes.
 *
 * Shape factories ([rect], [circle], [line], [polygon]) accept an optional
 * trailing [ShapeBuilder] lambda for styling and transforms.  [text] accepts
 * a [TextBuilder] lambda, and [group] accepts another [SceneBuilder] lambda
 * for nesting.
 */
class SceneBuilder {

    private val children = mutableListOf<Node>()

    // -- shape factories ----------------------------------------------------

    /**
     * Adds a filled / stroked rectangle to the scene.
     *
     * @param x      left edge x-coordinate
     * @param y      top edge y-coordinate
     * @param width  rectangle width
     * @param height rectangle height
     * @param block  optional configuration block
     */
    fun rect(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        block: (ShapeBuilder.() -> Unit)? = null
    ) {
        val path = Path.builder()
            .moveTo(x, y)
            .lineTo(x + width, y)
            .lineTo(x + width, y + height)
            .lineTo(x, y + height)
            .close()
            .build()
        addShape(path, block)
    }

    /**
     * Int overload for [rect].
     */
    fun rect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        block: (ShapeBuilder.() -> Unit)? = null
    ) = rect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), block)

    /**
     * Adds a circle (approximated polygon) to the scene.
     *
     * @param cx     center x-coordinate
     * @param cy     center y-coordinate
     * @param radius circle radius
     * @param block  optional configuration block
     */
    fun circle(
        cx: Float,
        cy: Float,
        radius: Float,
        block: (ShapeBuilder.() -> Unit)? = null
    ) {
        val segments = 64
        val builder = Path.builder()
        for (i in 0..segments) {
            val angle = (2.0 * Math.PI * i / segments).toFloat()
            val px = cx + radius * kotlin.math.cos(angle)
            val py = cy + radius * kotlin.math.sin(angle)
            if (i == 0) builder.moveTo(px, py) else builder.lineTo(px, py)
        }
        builder.close()
        addShape(builder.build(), block)
    }

    /**
     * Int overload for [circle].
     */
    fun circle(
        cx: Int,
        cy: Int,
        radius: Int,
        block: (ShapeBuilder.() -> Unit)? = null
    ) = circle(cx.toFloat(), cy.toFloat(), radius.toFloat(), block)

    /**
     * Adds a straight line segment to the scene.
     *
     * @param x1    start x-coordinate
     * @param y1    start y-coordinate
     * @param x2    end x-coordinate
     * @param y2    end y-coordinate
     * @param block optional configuration block
     */
    fun line(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        block: (ShapeBuilder.() -> Unit)? = null
    ) {
        val path = Path.builder()
            .moveTo(x1, y1)
            .lineTo(x2, y2)
            .build()
        addShape(path, block)
    }

    /**
     * Int overload for [line].
     */
    fun line(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        block: (ShapeBuilder.() -> Unit)? = null
    ) = line(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), block)

    /**
     * Adds a closed polygon defined by a list of (x, y) pairs.
     *
     * @param points flat list of alternating x/y coordinates (size must be even)
     * @param block  optional configuration block
     */
    fun polygon(
        points: List<Float>,
        block: (ShapeBuilder.() -> Unit)? = null
    ) {
        require(points.size >= 4 && points.size % 2 == 0) {
            "Polygon requires at least 2 points (4 coordinates), got ${points.size / 2}"
        }
        val builder = Path.builder()
        builder.moveTo(points[0], points[1])
        var i = 2
        while (i < points.size) {
            builder.lineTo(points[i], points[i + 1])
            i += 2
        }
        builder.close()
        addShape(builder.build(), block)
    }

    /**
     * Adds a closed polygon from a list of [Vec2] points.
     */
    fun polygon(
        points: Array<Vec2>,
        block: (ShapeBuilder.() -> Unit)? = null
    ) = polygon(points.flatMap { listOf(it.x(), it.y()) }, block)

    // -- text ---------------------------------------------------------------

    /**
     * Adds a text node to the scene.
     *
     * @param content the text string
     * @param x       x-coordinate
     * @param y       y-coordinate
     * @param block   optional configuration block
     */
    fun text(
        content: String,
        x: Float,
        y: Float,
        block: (TextBuilder.() -> Unit)? = null
    ) {
        val builder = TextBuilder()
        builder.position(x, y)
        block?.invoke(builder)
        val style = builder.buildStyle()
        val node = TextNode(x, y, content, style)
        builder.applyPosition(node)
        children += node
    }

    /**
     * Int overload for [text].
     */
    fun text(
        content: String,
        x: Int,
        y: Int,
        block: (TextBuilder.() -> Unit)? = null
    ) = text(content, x.toFloat(), y.toFloat(), block)

    // -- group --------------------------------------------------------------

    /**
     * Adds a [GroupNode] whose children are defined by a nested
     * [SceneBuilder] block.
     *
     * @param block child-building block
     */
    fun group(block: SceneBuilder.() -> Unit) {
        val inner = SceneBuilder()
        inner.block()
        children += inner.buildGroup()
    }

    // -- build --------------------------------------------------------------

    /**
     * Constructs the [Scene] with all accumulated children attached to the
     * root group.
     */
    internal fun build(): Scene {
        val scene = Scene()
        for (child in children) {
            scene.add(child)
        }
        return scene
    }

    /**
     * Constructs a [GroupNode] containing all accumulated children.
     * Used by [group] to create nested groups.
     */
    internal fun buildGroup(): GroupNode {
        val groupNode = GroupNode()
        for (child in children) {
            groupNode.add(child)
        }
        return groupNode
    }

    // -- internal helpers ---------------------------------------------------

    private fun addShape(path: Path, block: (ShapeBuilder.() -> Unit)?) {
        val builder = ShapeBuilder()
        block?.invoke(builder)
        val node = ShapeNode(path, builder.buildFill(), builder.buildStroke())
        builder.applyTransform(node)
        children += node
    }
}

// ---------------------------------------------------------------------------
// ShapeBuilder
// ---------------------------------------------------------------------------

/**
 * DSL builder for configuring a [ShapeNode] -- fill, stroke, and transform.
 */
class ShapeBuilder {

    private var fillColor: Color? = null
    private var fillOpacity: Float = 1.0f

    private var strokeColor: Color? = null
    private var strokeWidth: Float = 1.0f

    private var posX: Float = 0f
    private var posY: Float = 0f
    private var rotation: Float = 0f
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f

    // -- styles -------------------------------------------------------------

    /**
     * Sets the fill color and optional opacity for this shape.
     *
     * @param color   the fill [Color]
     * @param opacity opacity in `[0.0, 1.0]` (default `1.0`)
     */
    fun fill(color: Color, opacity: Float = 1.0f) {
        fillColor = color
        fillOpacity = opacity
    }

    /**
     * Sets the fill color from a hex string (e.g. `"#FF0000"`).
     */
    fun fill(hex: String, opacity: Float = 1.0f) {
        fill(Color.fromHex(hex), opacity)
    }

    /**
     * Sets the stroke color and width for this shape.
     *
     * @param color the stroke [Color]
     * @param width line width in pixels (default `1.0`)
     */
    fun stroke(color: Color, width: Float = 1.0f) {
        strokeColor = color
        strokeWidth = width
    }

    /**
     * Sets the stroke color from a hex string.
     */
    fun stroke(hex: String, width: Float = 1.0f) {
        stroke(Color.fromHex(hex), width)
    }

    // -- transform ----------------------------------------------------------

    /**
     * Sets the local position offset for this shape.
     */
    fun position(x: Float, y: Float) {
        posX = x
        posY = y
    }

    /**
     * Sets the rotation angle in degrees.
     */
    fun rotate(degrees: Float) {
        rotation = degrees
    }

    /**
     * Sets a uniform scale factor for both axes.
     */
    fun scale(s: Float) {
        scaleX = s
        scaleY = s
    }

    /**
     * Sets independent scale factors for each axis.
     */
    fun scale(sx: Float, sy: Float) {
        scaleX = sx
        scaleY = sy
    }

    // -- internal -----------------------------------------------------------

    internal fun buildFill(): FillStyle? =
        fillColor?.let { FillStyle(it, fillOpacity) }

    internal fun buildStroke(): StrokeStyle? =
        strokeColor?.let { StrokeStyle(it, strokeWidth) }

    internal fun applyTransform(node: ShapeNode) {
        node.getTransform().setPosition(posX, posY)
        if (rotation != 0f) {
            node.getTransform().setRotation(Math.toRadians(rotation.toDouble()).toFloat())
        }
        if (scaleX != 1f || scaleY != 1f) {
            node.getTransform().setScale(scaleX, scaleY)
        }
    }
}

// ---------------------------------------------------------------------------
// TextBuilder
// ---------------------------------------------------------------------------

/**
 * DSL builder for configuring a [TextNode] -- font style and position.
 */
class TextBuilder {

    private var fontFamily: String = "SansSerif"
    private var fontSize: Float = 16f
    private var fontColor: Color = Color.WHITE
    private var posX: Float = 0f
    private var posY: Float = 0f

    /**
     * Sets the font family, size, and color.
     *
     * @param family font family name (e.g. `"Arial"`, `"Monospaced"`)
     * @param size   font size in points
     * @param color  text [Color]
     */
    fun font(family: String, size: Float, color: Color) {
        fontFamily = family
        fontSize = size
        fontColor = color
    }

    /**
     * Sets the font using a hex color string.
     */
    fun font(family: String, size: Float, hex: String) {
        font(family, size, Color.fromHex(hex))
    }

    /**
     * Sets the position of the text node.
     */
    fun position(x: Float, y: Float) {
        posX = x
        posY = y
    }

    // -- internal -----------------------------------------------------------

    internal fun buildStyle(): TextStyle =
        TextStyle(fontFamily, fontSize, fontColor)

    internal fun applyPosition(node: TextNode) {
        node.getTransform().setPosition(posX, posY)
    }
}

// ---------------------------------------------------------------------------
// GroupBuilder
// ---------------------------------------------------------------------------

/**
 * DSL builder for configuring a [GroupNode].
 *
 * Groups are built implicitly via [SceneBuilder.group]; this class is
 * provided for symmetry and potential future extension (e.g. naming,
 * group-level transforms).
 */
class GroupBuilder {

    private var name: String = ""
    private var posX: Float = 0f
    private var posY: Float = 0f

    /**
     * Sets a human-readable name for this group.
     */
    fun name(value: String) {
        name = value
    }

    /**
     * Sets the local position offset for this group.
     */
    fun position(x: Float, y: Float) {
        posX = x
        posY = y
    }

    // -- internal -----------------------------------------------------------

    internal fun buildGroup(children: List<Node>): GroupNode {
        val group = GroupNode(name)
        children.forEach { group.add(it) }
        group.getTransform().setPosition(posX, posY)
        return group
    }
}
