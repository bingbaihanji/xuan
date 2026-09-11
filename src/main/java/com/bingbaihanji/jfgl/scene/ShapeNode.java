package com.bingbaihanji.jfgl.scene;

import com.bingbaihanji.jfgl.math.Vec2;
import com.bingbaihanji.jfgl.renderer.Path;
import com.bingbaihanji.jfgl.renderer.RenderContext;
import com.bingbaihanji.jfgl.style.FillStyle;
import com.bingbaihanji.jfgl.style.StrokeStyle;
import com.bingbaihanji.jfgl.util.Rect;

import java.util.List;
import java.util.Objects;

/**
 * A scene node that represents a 2D shape defined by a {@link Path}.
 *
 * <p>A shape node holds an immutable path geometry along with optional
 * {@link FillStyle} and {@link StrokeStyle} that control how the shape
 * is rendered.  When both styles are {@code null} the shape is invisible
 * but still occupies space for hit-testing and layout.
 *
 * <p>The bounding rectangle is computed lazily from the tessellated path
 * vertices and cached until the path changes.
 */
public class ShapeNode extends Node {

    private static final int DEFAULT_TESSELLATION_SEGMENTS = 16;

    private Path path;

    private FillStyle fillStyle;

    private StrokeStyle strokeStyle;

    private Rect cachedBounds;

    private boolean boundsDirty = true;

    /**
     * Creates a shape node with the given path and no styles.
     * The shape will not be visible until a fill or stroke style is assigned.
     *
     * @param path the path geometry for this shape; must not be {@code null}
     */
    public ShapeNode(Path path) {
        this(path, null, null);
    }

    /**
     * Creates a shape node with the given path and rendering styles.
     *
     * @param path        the path geometry for this shape; must not be {@code null}
     * @param fillStyle   the fill style, or {@code null} for no fill
     * @param strokeStyle the stroke style, or {@code null} for no stroke
     */
    public ShapeNode(Path path, FillStyle fillStyle, StrokeStyle strokeStyle) {
        this.path = Objects.requireNonNull(path, "path");
        this.fillStyle = fillStyle;
        this.strokeStyle = strokeStyle;
    }

    // ---- path --------------------------------------------------------------

    /**
     * Returns the path geometry of this shape.
     *
     * @return the path
     */
    public Path getPath() {
        return path;
    }

    /**
     * Replaces the path geometry and marks the cached bounds as stale.
     *
     * @param path the new path; must not be {@code null}
     */
    public void setPath(Path path) {
        this.path = Objects.requireNonNull(path, "path");
        this.boundsDirty = true;
    }

    // ---- fill style --------------------------------------------------------

    /**
     * Returns the current fill style, or {@code null} if no fill is applied.
     *
     * @return the fill style
     */
    public FillStyle getFillStyle() {
        return fillStyle;
    }

    /**
     * Sets the fill style.
     *
     * @param fillStyle the new fill style, or {@code null} to disable filling
     */
    public void setFillStyle(FillStyle fillStyle) {
        this.fillStyle = fillStyle;
    }

    // ---- stroke style ------------------------------------------------------

    /**
     * Returns the current stroke style, or {@code null} if no stroke is applied.
     *
     * @return the stroke style
     */
    public StrokeStyle getStrokeStyle() {
        return strokeStyle;
    }

    /**
     * Sets the stroke style.
     *
     * @param strokeStyle the new stroke style, or {@code null} to disable stroking
     */
    public void setStrokeStyle(StrokeStyle strokeStyle) {
        this.strokeStyle = strokeStyle;
    }

    // ---- rendering ---------------------------------------------------------

    /**
     * Renders this shape into the given render context.
     *
     * <p>If a fill style is set the path interior is filled; if a stroke style
     * is set the path outline is stroked.  Both operations may be performed
     * on a single call when both styles are present.
     *
     * @param renderContext the current render context
     */
    @Override
    public void render(RenderContext renderContext) {
        if (fillStyle == null && strokeStyle == null) {
            return;
        }

        List<Vec2> vertices = path.toVertices(DEFAULT_TESSELLATION_SEGMENTS);

        if (fillStyle != null) {
            renderFilled(renderContext, vertices);
        }

        if (strokeStyle != null) {
            renderStroked(renderContext, vertices);
        }
    }

    /**
     * Fills the tessellated path vertices using the current fill style.
     *
     * @param renderContext the render context
     * @param vertices      the tessellated path vertices
     */
    private void renderFilled(RenderContext renderContext, List<Vec2> vertices) {
        // Delegate to the GL abstraction for triangle-based fill rendering.
        // Concrete implementation depends on the renderer pipeline.
    }

    /**
     * Strokes the tessellated path vertices using the current stroke style.
     *
     * @param renderContext the render context
     * @param vertices      the tessellated path vertices
     */
    private void renderStroked(RenderContext renderContext, List<Vec2> vertices) {
        // Delegate to the GL abstraction for line-based stroke rendering.
        // Concrete implementation depends on the renderer pipeline.
    }

    // ---- bounds ------------------------------------------------------------

    /**
     * Returns the axis-aligned bounding rectangle of this shape in local
     * coordinates.
     *
     * <p>The bounds are lazily computed from the tessellated path vertices
     * and cached until the path changes.
     *
     * @return the bounding rectangle, or {@code null} if the path has no vertices
     */
    @Override
    public Rect getBounds() {
        if (boundsDirty) {
            cachedBounds = computeBounds();
            boundsDirty = false;
        }
        return cachedBounds;
    }

    /**
     * Computes the bounding rectangle by tessellating the path and finding
     * the minimum and maximum vertex coordinates.
     *
     * @return the computed bounds, or {@code null} if the path produces no vertices
     */
    private Rect computeBounds() {
        List<Vec2> vertices = path.toVertices(DEFAULT_TESSELLATION_SEGMENTS);

        if (vertices.isEmpty()) {
            return null;
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (Vec2 v : vertices) {
            if (v.x() < minX) {
                minX = v.x();
            }
            if (v.y() < minY) {
                minY = v.y();
            }
            if (v.x() > maxX) {
                maxX = v.x();
            }
            if (v.y() > maxY) {
                maxY = v.y();
            }
        }

        // Expand bounds by stroke width so the stroke is fully contained.
        float strokePadding = (strokeStyle != null) ? strokeStyle.getWidth() * 0.5f : 0f;

        return new Rect(
                minX - strokePadding,
                minY - strokePadding,
                (maxX - minX) + strokePadding * 2,
                (maxY - minY) + strokePadding * 2
        );
    }

    @Override
    public String toString() {
        return "ShapeNode{path=%s, fillStyle=%s, strokeStyle=%s}"
                .formatted(path, fillStyle, strokeStyle);
    }
}
