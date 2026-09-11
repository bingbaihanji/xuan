export const meta = {
  name: 'jfgl-drawing-engine',
  description: 'Implement JFGL 2D drawing engine with Java + Kotlin DSL',
  phases: [
    { title: 'Foundation', detail: 'Math utilities and utility classes' },
    { title: 'GL Abstraction', detail: 'OpenGL abstraction layer' },
    { title: 'Renderer', detail: 'Rendering layer with batching' },
    { title: 'Style System', detail: 'Style classes for shapes and text' },
    { title: 'Scene Graph', detail: 'Node hierarchy and spatial indexing' },
    { title: 'Event System', detail: 'Event types and dispatcher' },
    { title: 'Engine Core', detail: 'DrawEngine and RenderScheduler' },
    { title: 'Kotlin DSL', detail: 'Kotlin DSL API for shapes, styles, interactions' },
    { title: 'Charts', detail: 'Statistical chart components' },
    { title: 'GPU Algorithms', detail: 'Compute shaders and FFT' },
    { title: 'Integration', detail: 'Update App.kt and FXGLTransfer' }
  ]
}

log('Starting JFGL Drawing Engine implementation')

// Phase 1: Foundation
phase('Foundation')
log('Creating math utilities and utility classes...')

const foundationFiles = await parallel([
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/math/Vec2.java

Requirements:
- Package: com.bingbaihanji.jfgl.math
- Fields: public float x, y
- Constructors: Vec2(), Vec2(float x, float y)
- Methods: add(Vec2), sub(Vec2), scale(float), dot(Vec2), length(), normalize(), distanceTo(Vec2)
- Override equals() and toString()
- Use Java 17 conventions

Write the complete file content.`, {label: 'Vec2', phase: 'Foundation'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/math/Mat3.java

Requirements:
- Package: com.bingbaihanji.jfgl.math
- Fields: private final float[] m = new float[9] (3x3 matrix, column-major)
- Methods: identity(), static translation(float tx, float ty), static scale(float sx, float sy), static rotation(float radians), multiply(Mat3), transform(Vec2), toArray()
- Use Java 17 conventions

Write the complete file content.`, {label: 'Mat3', phase: 'Foundation'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/math/Transform.java

Requirements:
- Package: com.bingbaihanji.jfgl.math
- Fields: Vec2 position, float rotation, Vec2 scale
- Dirty flag pattern for matrix caching (private Mat3 matrix, boolean dirty)
- Methods: getPosition/setPosition, getRotation/setRotation, getScale/setScale, getMatrix(), transformPoint(Vec2), inverseTransform(Vec2)
- Use Java 17 conventions

Write the complete file content.`, {label: 'Transform', phase: 'Foundation'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/util/Color.java

Requirements:
- Package: com.bingbaihanji.jfgl.util
- Fields: public final float r, g, b, a (0-1 range)
- Constants: public static final WHITE, BLACK, RED, GREEN, BLUE
- Constructors: Color(float r, float g, float b, float a), Color(float r, float g, float b)
- Static methods: fromRGB(int r, int g, int b), fromHex(String hex)
- Methods: withAlpha(float), toArray()
- Override equals()
- Use Java 17 conventions

Write the complete file content.`, {label: 'Color', phase: 'Foundation'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/util/Rect.java

Requirements:
- Package: com.bingbaihanji.jfgl.util
- Fields: public float x, y, width, height
- Constructor: Rect(float x, float y, float width, float height)
- Methods: getPosition(), getSize(), getRight(), getBottom(), getCenter(), contains(Vec2), contains(float px, float py), intersects(Rect), expand(float margin)
- Use Java 17 conventions

Write the complete file content.`, {label: 'Rect', phase: 'Foundation'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/util/Disposable.java

Requirements:
- Package: com.bingbaihanji.jfgl.util
- Interface with single method: void dispose()
- Use Java 17 conventions

Write the complete file content.`, {label: 'Disposable', phase: 'Foundation'})
])

log(`Foundation complete: ${foundationFiles.length} files created`)

// Phase 2: GL Abstraction
phase('GL Abstraction')
log('Creating OpenGL abstraction layer...')

const glFiles = await parallel([
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/gl/ShaderProgram.java

Requirements:
- Package: com.bingbaihanji.jfgl.gl
- Implements com.bingbaihanji.jfgl.util.Disposable
- Constructor takes String vertexSource, String fragmentSource
- Uses LWJGL OpenGL bindings: import static org.lwjgl.opengl.GL20.* and GL30.*
- Private method: compileShader(int type, String source)
- Methods: use(), unuse(), getUniformLocation(String name), setUniform(String name, float value), setUniform(String name, float x, float y), setUniform(String name, float[] matrix), setUniform(String name, int value)
- dispose() deletes the program
- Use Java 17 conventions

Write the complete file content.`, {label: 'ShaderProgram', phase: 'GL Abstraction'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/gl/Texture.java

Requirements:
- Package: com.bingbaihanji.jfgl.gl
- Implements com.bingbaihanji.jfgl.util.Disposable
- Constructor takes int width, int height, int[] pixels (RGBA format)
- Uses LWJGL OpenGL bindings: import static org.lwjgl.opengl.GL11.* and GL13.*
- Methods: bind(int unit), unbind(), getTextureId(), getWidth(), getHeight()
- dispose() deletes the texture
- Use Java 17 conventions

Write the complete file content.`, {label: 'Texture', phase: 'GL Abstraction'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/gl/GLAbstraction.java

Requirements:
- Package: com.bingbaihanji.jfgl.gl
- Interface extending com.bingbaihanji.jfgl.util.Disposable
- Methods: initialize(), clear(com.bingbaihanji.jfgl.util.Color color), setViewport(int x, int y, int width, int height), createVao(), createVbo(), bindVao(int vao), bindVbo(int vbo), uploadVboData(float[] data), uploadVboData(int[] data), deleteVao(int vao), deleteVbo(int vbo), drawArrays(int mode, int offset, int count), drawElements(int mode, int count), enableBlend(), disableBlend(), setBlendFunc(int srcFactor, int dstFactor), createShader(String vertexSource, String fragmentSource), createTexture(int width, int height, int[] pixels)
- Use Java 17 conventions

Write the complete file content.`, {label: 'GLAbstraction', phase: 'GL Abstraction'})
])

log(`GL Abstraction complete: ${glFiles.length} files created`)

// Phase 3: Renderer
phase('Renderer')
log('Creating rendering layer...')

const rendererFiles = await parallel([
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/renderer/RenderContext.java

Requirements:
- Package: com.bingbaihanji.jfgl.renderer
- Fields: private final GLAbstraction gl, private ShaderProgram currentShader, private final Mat3 viewProjectionMatrix
- Constructor: RenderContext(GLAbstraction gl)
- Methods: getGl(), useShader(ShaderProgram shader), getCurrentShader(), getViewProjectionMatrix(), setViewProjectionMatrix(Mat3 matrix)
- Import com.bingbaihanji.jfgl.gl.GLAbstraction, com.bingbaihanji.jfgl.gl.ShaderProgram, com.bingbaihanji.jfgl.math.Mat3
- Use Java 17 conventions

Write the complete file content.`, {label: 'RenderContext', phase: 'Renderer'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/renderer/BatchRenderer.java

Requirements:
- Package: com.bingbaihanji.jfgl.renderer
- Implements com.bingbaihanji.jfgl.util.Disposable
- Constants: MAX_QUADS=10000, FLOATS_PER_VERTEX=7, VERTICES_PER_QUAD=4, INDICES_PER_QUAD=6
- Fields: GLAbstraction gl, ShaderProgram shader, float[] vertexBuffer, int[] indexBuffer, int vao/vbo/ebo, int quadCount
- Default vertex shader (GLSL 330): position + color + texId attributes, uViewProjection uniform
- Default fragment shader (GLSL 330): outputs vColor
- Constructor initializes buffers and shader
- Methods: begin(), drawQuad(float x, float y, float width, float height, Color color), flush(), end()
- Uses LWJGL OpenGL bindings
- Use Java 17 conventions

Write the complete file content.`, {label: 'BatchRenderer', phase: 'Renderer'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/renderer/Path.java

Requirements:
- Package: com.bingbaihanji.jfgl.renderer
- Builder pattern for 2D paths
- Inner enum Type: MOVE_TO, LINE_TO, QUAD_TO, CUBIC_TO, CLOSE
- Inner record PathCommand(Type type, Vec2... points)
- Methods: moveTo(float x, float y), lineTo(float x, float y), quadTo(float cx, float cy, float x, float y), cubicTo(float cx1, float cy1, float cx2, float cy2, float x, float y), close()
- Method: toVertices(int segments) - converts path to list of Vec2 vertices
- Import com.bingbaihanji.jfgl.math.Vec2
- Use Java 17 conventions

Write the complete file content.`, {label: 'Path', phase: 'Renderer'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/renderer/TextRenderer.java

Requirements:
- Package: com.bingbaihanji.jfgl.renderer
- Implements com.bingbaihanji.jfgl.util.Disposable
- Uses Java AWT for font rendering
- Inner class GlyphInfo with fields: float u, v, u2, v2; int width, height, advance
- Fields: GLAbstraction gl, Map<Character, GlyphInfo> glyphCache, Texture fontTexture
- Constructor generates font texture atlas (512x512)
- Method: drawText(BatchRenderer batch, String text, float x, float y, Color color)
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'TextRenderer', phase: 'Renderer'})
])

log(`Renderer complete: ${rendererFiles.length} files created`)

// Phase 4: Style System
phase('Style System')
log('Creating style classes...')

const styleFiles = await parallel([
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/style/FillStyle.java

Requirements:
- Package: com.bingbaihanji.jfgl.style
- Fields: private Color color, private float opacity
- Constructors: FillStyle(Color color), FillStyle(Color color, float opacity)
- Methods: getColor/setColor, getOpacity/setOpacity, getEffectiveColor()
- Static factories: of(Color), of(Color, float)
- Import com.bingbaihanji.jfgl.util.Color
- Use Java 17 conventions

Write the complete file content.`, {label: 'FillStyle', phase: 'Style System'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/style/StrokeStyle.java

Requirements:
- Package: com.bingbaihanji.jfgl.style
- Inner enums: LineCap (BUTT, ROUND, SQUARE), LineJoin (MITER, ROUND, BEVEL)
- Fields: Color color, float width, LineCap lineCap, LineJoin lineJoin, float[] dashPattern
- Constructors: StrokeStyle(Color, float), StrokeStyle(Color, float, LineCap, LineJoin, float[])
- Getters for all fields
- Static factory: of(Color, float)
- Import com.bingbaihanji.jfgl.util.Color
- Use Java 17 conventions

Write the complete file content.`, {label: 'StrokeStyle', phase: 'Style System'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/style/TextStyle.java

Requirements:
- Package: com.bingbaihanji.jfgl.style
- Fields: String fontFamily, float fontSize, Color color, boolean bold, boolean italic
- Constructors: TextStyle(String, float, Color), TextStyle(String, float, Color, boolean, boolean)
- Getters for all fields
- Static factory: of(String, float, Color)
- Import com.bingbaihanji.jfgl.util.Color
- Use Java 17 conventions

Write the complete file content.`, {label: 'TextStyle', phase: 'Style System'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/style/Style.java

Requirements:
- Package: com.bingbaihanji.jfgl.style
- Fields: FillStyle fill, StrokeStyle stroke, TextStyle text
- Default constructor
- Constructor: Style(FillStyle, StrokeStyle)
- Getters and setters
- Static method: builder() returning StyleBuilder
- Inner class StyleBuilder with methods: fill(FillStyle), stroke(StrokeStyle), text(TextStyle), build()
- Use Java 17 conventions

Write the complete file content.`, {label: 'Style', phase: 'Style System'})
])

log(`Style System complete: ${styleFiles.length} files created`)

// Phase 5: Scene Graph
phase('Scene Graph')
log('Creating scene graph hierarchy...')

const sceneFiles = await parallel([
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/scene/Node.java

Requirements:
- Package: com.bingbaihanji.jfgl.scene
- Abstract class implementing com.bingbaihanji.jfgl.util.Disposable
- Fields: protected final Transform transform, protected Style style, protected boolean visible=true, protected boolean dirty=true, protected String id, protected Node parent
- Abstract methods: void render(RenderContext context), Rect getBounds()
- Methods: getTransform(), getStyle/setStyle(), isVisible/setVisible(), getId/setId(), getParent/setParent(), isDirty(), markDirty()
- Coordinate conversion: localToParent(Vec2), parentToLocal(Vec2), localToScene(Vec2), sceneToLocal(Vec2)
- Hit testing: boolean hitTest(Vec2 point)
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'Node', phase: 'Scene Graph'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/scene/ShapeNode.java

Requirements:
- Package: com.bingbaihanji.jfgl.scene
- Extends Node
- Fields: Path path, FillStyle fillStyle, StrokeStyle strokeStyle
- Constructors: ShapeNode(Path), ShapeNode(Path, FillStyle, StrokeStyle)
- Override render(RenderContext) and getBounds()
- Getters/setters for fillStyle and strokeStyle
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'ShapeNode', phase: 'Scene Graph'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/scene/TextNode.java

Requirements:
- Package: com.bingbaihanji.jfgl.scene
- Extends Node
- Fields: String text, TextStyle textStyle
- Constructor: TextNode(String text, TextStyle style)
- Override render(RenderContext) and getBounds()
- Getters/setters for text and textStyle
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'TextNode', phase: 'Scene Graph'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/scene/GroupNode.java

Requirements:
- Package: com.bingbaihanji.jfgl.scene
- Extends Node
- Fields: List<Node> children
- Methods: add(Node), remove(Node), getChildren()
- Override render(RenderContext) - renders all children
- Override getBounds() - union of children bounds
- Override hitTest(Vec2) - checks children in reverse order
- Override dispose() - disposes all children
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'GroupNode', phase: 'Scene Graph'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/scene/QuadTree.java

Requirements:
- Package: com.bingbaihanji.jfgl.scene
- Generic class QuadTree<T>
- Constants: MAX_OBJECTS=10, MAX_LEVELS=5
- Fields: int level, List<T> objects, Rect bounds, QuadTree<T>[] nodes (size 4)
- Constructor: QuadTree(int level, Rect bounds)
- Methods: clear(), split(), getIndex(Rect), insert(T object, Rect rect), retrieve(List<T> returnList, Rect rect)
- Import com.bingbaihanji.jfgl.util.Rect
- Use Java 17 conventions

Write the complete file content.`, {label: 'QuadTree', phase: 'Scene Graph'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/scene/Scene.java

Requirements:
- Package: com.bingbaihanji.jfgl.scene
- Implements com.bingbaihanji.jfgl.util.Disposable
- Fields: GroupNode root, List<Node> allNodes
- Methods: getRoot(), add(Node), remove(Node), render(RenderContext), getAllNodes()
- Override dispose()
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'Scene', phase: 'Scene Graph'})
])

log(`Scene Graph complete: ${sceneFiles.length} files created`)

// Phase 6: Event System
phase('Event System')
log('Creating event types and dispatcher...')

const eventFiles = await parallel([
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/event/MouseEvent.java

Requirements:
- Package: com.bingbaihanji.jfgl.event
- Inner enum Type: PRESSED, RELEASED, MOVED, CLICKED, ENTERED, EXITED
- Fields: Type type, Vec2 position, int button, int clickCount, boolean consumed
- Constructor: MouseEvent(Type, Vec2, int button, int clickCount)
- Methods: getType(), getPosition(), getButton(), getClickCount(), isConsumed(), consume()
- Import com.bingbaihanji.jfgl.math.Vec2
- Use Java 17 conventions

Write the complete file content.`, {label: 'MouseEvent', phase: 'Event System'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/event/DragEvent.java

Requirements:
- Package: com.bingbaihanji.jfgl.event
- Inner enum Type: STARTED, DRAGGING, ENDED
- Fields: Type type, Vec2 position, Vec2 delta, int button, boolean consumed
- Constructor: DragEvent(Type, Vec2 position, Vec2 delta, int button)
- Methods: getType(), getPosition(), getDelta(), getButton(), isConsumed(), consume()
- Import com.bingbaihanji.jfgl.math.Vec2
- Use Java 17 conventions

Write the complete file content.`, {label: 'DragEvent', phase: 'Event System'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/event/ScrollEvent.java

Requirements:
- Package: com.bingbaihanji.jfgl.event
- Fields: Vec2 position, double deltaX, double deltaY, boolean consumed
- Constructor: ScrollEvent(Vec2, double deltaX, double deltaY)
- Methods: getPosition(), getDeltaX(), getDeltaY(), isConsumed(), consume()
- Import com.bingbaihanji.jfgl.math.Vec2
- Use Java 17 conventions

Write the complete file content.`, {label: 'ScrollEvent', phase: 'Event System'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/event/EventDispatcher.java

Requirements:
- Package: com.bingbaihanji.jfgl.event
- Functional interfaces: MouseHandler, DragHandler, ScrollHandler (each with void handle method)
- Fields: Map<Node, List<MouseHandler>> mouseHandlers, Map<Node, List<DragHandler>> dragHandlers, Map<Node, List<ScrollHandler>> scrollHandlers
- Methods: addMouseListener(Node, MouseHandler), addDragListener(Node, DragHandler), addScrollListener(Node, ScrollHandler), removeMouseListener(Node, MouseHandler), dispatchMouseEvent(MouseEvent, Node), dispatchDragEvent(DragEvent, Node), dispatchScrollEvent(ScrollEvent, Node), clear(Node), clearAll()
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'EventDispatcher', phase: 'Event System'})
])

log(`Event System complete: ${eventFiles.length} files created`)

// Phase 7: Engine Core
phase('Engine Core')
log('Creating engine core...')

const engineFiles = await parallel([
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/engine/DrawEngine.java

Requirements:
- Package: com.bingbaihanji.jfgl.engine
- Implements com.bingbaihanji.jfgl.util.Disposable
- Fields: GLAbstraction gl, BatchRenderer batchRenderer, TextRenderer textRenderer, RenderContext renderContext, EventDispatcher eventDispatcher, RenderScheduler renderScheduler, Scene scene, Color clearColor, int width/height, Vec2 cameraPosition, float cameraZoom/cameraRotation
- Constructor: DrawEngine(GLAbstraction gl, int width, int height)
- Methods: initialize(), render(), resize(int w, int h), getScene/setScene(), getEventDispatcher(), camera controls (get/set position/zoom/rotation), coordinate conversion (screenToWorld, worldToScreen), getters for renderers
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'DrawEngine', phase: 'Engine Core'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/engine/RenderScheduler.java

Requirements:
- Package: com.bingbaihanji.jfgl.engine
- Fields: boolean dirty=true, boolean continuous=false
- Constructor: RenderScheduler()
- Methods: execute(Runnable renderFunction), markDirty(), markClean(), isDirty(), setContinuous(boolean), isContinuous(), requestRepaint()
- Use Java 17 conventions

Write the complete file content.`, {label: 'RenderScheduler', phase: 'Engine Core'})
])

log(`Engine Core complete: ${engineFiles.length} files created`)

// Phase 8: Kotlin DSL
phase('Kotlin DSL')
log('Creating Kotlin DSL API...')

const dslFiles = await parallel([
  () => agent(`Create the file src/main/kotlin/com/bingbaihanji/jfgl/dsl/Shapes.kt

Requirements:
- Package com.bingbaihanji.jfgl.dsl
- Import DrawEngine, Path, Vec2, ShapeNode, TextNode, GroupNode, Scene, FillStyle, StrokeStyle, TextStyle, Color
- Extension function on DrawEngine: scene(block: SceneBuilder.() -> Unit): Scene
- SceneBuilder class with methods: rect(x, y, width, height), circle(cx, cy, radius), line(x1, y1, x2, y2), polygon(points), text(content, x, y), group(block)
- ShapeBuilder class with methods: fill(color, opacity), stroke(color, width), position(x, y), rotate(degrees), scale(s)
- TextBuilder class with methods: font(family, size, color), position(x, y)
- GroupBuilder class
- Use Kotlin DSL patterns

Write the complete file content.`, {label: 'Shapes DSL', phase: 'Kotlin DSL'}),
  () => agent(`Create the file src/main/kotlin/com/bingbaihanji/jfgl/dsl/Styles.kt

Requirements:
- Package com.bingbaihanji.jfgl.dsl
- Import Color, FillStyle, StrokeStyle, TextStyle, Style
- Color constants: RED, GREEN, BLUE, WHITE, BLACK
- Color factory functions: color(r, g, b, a), color(hex), rgb(r, g, b)
- Style factory functions: fill(color, opacity), stroke(color, width), text(family, size, color)
- StyleBuilder class with methods: fill(), stroke(), text(), build()
- Use Kotlin DSL patterns

Write the complete file content.`, {label: 'Styles DSL', phase: 'Kotlin DSL'}),
  () => agent(`Create the file src/main/kotlin/com/bingbaihanji/jfgl/dsl/Interactions.kt

Requirements:
- Package com.bingbaihanji.jfgl.dsl
- Import DrawEngine, EventDispatcher, MouseEvent, DragEvent, ScrollEvent, Vec2, Node
- Extension functions on DrawEngine: onMouseClick(node, handler), onMouseDrag(node, handler), onScroll(handler), onZoom(handler)
- InteractionBuilder class with methods: onClick(handler), onDrag(handler)
- Extension function on Node: interact(engine, block)
- Use Kotlin DSL patterns

Write the complete file content.`, {label: 'Interactions DSL', phase: 'Kotlin DSL'})
])

log(`Kotlin DSL complete: ${dslFiles.length} files created`)

// Phase 9: Charts
phase('Charts')
log('Creating chart components...')

const chartFiles = await parallel([
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/chart/Chart.java

Requirements:
- Package com.bingbaihanji.jfgl.chart
- Extends GroupNode
- Abstract class
- Fields: DrawEngine engine, Rect area, List<ChartSeries> series, String title, String xAxisLabel, String yAxisLabel
- Inner class ChartSeries: String name, List<double[]> data, Color color
- Constructor: Chart(DrawEngine, Rect)
- Methods: addSeries(ChartSeries), setTitle(), setXAxisLabel(), setYAxisLabel()
- Abstract method: rebuild()
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'Chart Base', phase: 'Charts'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/chart/LineChart.java

Requirements:
- Package com.bingbaihanji.jfgl.chart
- Extends Chart
- Constructor: LineChart(DrawEngine, Rect)
- Implements rebuild() to create line paths for each series
- Calculates data bounds and maps to screen coordinates
- Creates ShapeNode for each series line with appropriate stroke style
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'LineChart', phase: 'Charts'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/chart/BarChart.java

Requirements:
- Package com.bingbaihanji.jfgl.chart
- Extends Chart
- Constructor: BarChart(DrawEngine, Rect)
- Implements rebuild() to create bars for each data point
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'BarChart', phase: 'Charts'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/chart/PieChart.java

Requirements:
- Package com.bingbaihanji.jfgl.chart
- Extends Chart
- Inner class Slice: String label, double value, Color color
- Constructor: PieChart(DrawEngine, Rect)
- Implements rebuild() to create pie slices using arcs
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'PieChart', phase: 'Charts'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/chart/ScatterChart.java

Requirements:
- Package com.bingbaihanji.jfgl.chart
- Extends Chart
- Constructor: ScatterChart(DrawEngine, Rect)
- Implements rebuild() to create circles for each data point
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'ScatterChart', phase: 'Charts'}),
  () => agent(`Create the file src/main/kotlin/com/bingbaihanji/jfgl/dsl/Charts.kt

Requirements:
- Package com.bingbaihanji.jfgl.dsl
- Import DrawEngine, LineChart, BarChart, PieChart, ScatterChart, Rect, Color, Chart.ChartSeries
- Extension functions on DrawEngine: lineChart(x, y, width, height, block), barChart(x, y, width, height, block), pieChart(cx, cy, radius, block), scatterChart(x, y, width, height, block)
- Builder classes: LineChartBuilder, BarChartBuilder, PieChartBuilder, ScatterChartBuilder
- Methods for title, axis labels, adding series/data
- Use Kotlin DSL patterns

Write the complete file content.`, {label: 'Charts DSL', phase: 'Charts'})
])

log(`Charts complete: ${chartFiles.length} files created`)

// Phase 10: GPU Algorithms
phase('GPU Algorithms')
log('Creating GPU compute shaders...')

const gpuFiles = await parallel([
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/gpu/ComputeShader.java

Requirements:
- Package com.bingbaihanji.jfgl.gpu
- Implements com.bingbaihanji.jfgl.util.Disposable
- Uses LWJGL OpenGL 4.3 bindings: import static org.lwjgl.opengl.GL43.*
- Constructor takes String source (GLSL compute shader source)
- Methods: use(), unuse(), dispatch(int numGroupsX, int numGroupsY, int numGroupsZ), memoryBarrier(), getUniformLocation(String), setUniform(String, int), setUniform(String, float)
- dispose() deletes the program
- Use Java 17 conventions

Write the complete file content.`, {label: 'ComputeShader', phase: 'GPU Algorithms'}),
  () => agent(`Create the file src/main/java/com/bingbaihanji/jfgl/gpu/GPUFFT.java

Requirements:
- Package com.bingbaihanji.jfgl.gpu
- Implements com.bingbaihanji.jfgl.util.Disposable
- Uses ComputeShader for GPU-accelerated FFT
- Default GLSL shader string for Cooley-Tukey FFT algorithm
- Fields: ComputeShader shader, int inputBuffer, outputBuffer
- Constructor: GPUFFT()
- Method: execute(float[] real, float[] imag) - performs FFT on GPU
- Handles SSBO creation, shader execution, result readback
- Import necessary classes
- Use Java 17 conventions

Write the complete file content.`, {label: 'GPUFFT', phase: 'GPU Algorithms'})
])

log(`GPU Algorithms complete: ${gpuFiles.length} files created`)

// Phase 11: Integration
phase('Integration')
log('Updating integration files...')

const integrationFiles = await parallel([
  () => agent(`Update the file src/main/kotlin/com/bingbaihanji/jfgl/glview/FXGLTransfer.kt

Current content should be read first, then add:
- Import DrawEngine
- Add property: private var drawEngine: DrawEngine? = null
- In glInit block: create and initialize DrawEngine with LWJGL GL abstraction
- In glRender block: call drawEngine?.render()
- In glReshape block: call drawEngine?.resize(scaledWidth.toInt(), scaledHeight.toInt())
- In glDispose block: call drawEngine?.dispose()
- Add public method: fun getDrawEngine(): DrawEngine? = drawEngine
- Preserve all existing functionality

Write the complete updated file content.`, {label: 'Update FXGLTransfer', phase: 'Integration'}),
  () => agent(`Update the file src/main/kotlin/com/bingbaihanji/jfgl/App.kt

Current content should be read first, then add:
- Import DSL functions from com.bingbaihanji.jfgl.dsl.*
- Import Color constants
- After stage.show(), add demo scene creation using DSL:
  - Draw a rectangle with fill and stroke
  - Draw a circle with semi-transparent fill
  - Draw text
  - Draw a line chart with sample data
- Set stage title to "JFGL Drawing Engine"
- Preserve existing structure

Write the complete updated file content.`, {label: 'Update App', phase: 'Integration'})
])

log(`Integration complete: ${integrationFiles.length} files created`)

// Summary
const totalFiles = foundationFiles.length + glFiles.length + rendererFiles.length +
  styleFiles.length + sceneFiles.length + eventFiles.length + engineFiles.length +
  dslFiles.length + chartFiles.length + gpuFiles.length + integrationFiles.length

log(`\n========================================`)
log(`Implementation complete!`)
log(`Total files created: ${totalFiles}`)
log(`========================================`)
log(`Next steps:`)
log(`1. Run tests: mvn test`)
log(`2. Run application: mvn javafx:run`)
log(`========================================`)

return {
  success: true,
  filesCreated: totalFiles,
  phases: 11
}
