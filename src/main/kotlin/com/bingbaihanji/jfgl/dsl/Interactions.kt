package com.bingbaihanji.jfgl.dsl

import com.bingbaihanji.jfgl.engine.DrawEngine
import com.bingbaihanji.jfgl.event.EventDispatcher
import com.bingbaihanji.jfgl.event.MouseEvent
import com.bingbaihanji.jfgl.event.DragEvent
import com.bingbaihanji.jfgl.event.ScrollEvent
import com.bingbaihanji.jfgl.scene.Node

// ---------------------------------------------------------------------------
// DrawEngine extension functions
// ---------------------------------------------------------------------------

/**
 * Registers a mouse-click handler for the given [node].
 *
 * The [handler] is invoked for every [MouseEvent] dispatched to [node].
 * Use [MouseEvent.getType] to filter for [MouseEvent.Type.CLICKED] if only
 * actual clicks (press + release) are desired.
 *
 * @param node    the scene-graph node to listen on
 * @param handler the callback invoked with each mouse event
 */
fun DrawEngine.onMouseClick(node: Node, handler: (MouseEvent) -> Unit) {
    eventDispatcher.addMouseListener(node, EventDispatcher.MouseHandler { handler(it) })
}

/**
 * Registers a drag handler for the given [node].
 *
 * The [handler] is invoked for every [DragEvent] dispatched to [node],
 * including [DragEvent.Type.STARTED], [DragEvent.Type.DRAGGING], and
 * [DragEvent.Type.ENDED].
 *
 * @param node    the scene-graph node to listen on
 * @param handler the callback invoked with each drag event
 */
fun DrawEngine.onMouseDrag(node: Node, handler: (DragEvent) -> Unit) {
    eventDispatcher.addDragListener(node, EventDispatcher.DragHandler { handler(it) })
}

/**
 * Registers a global scroll handler on the scene's root node.
 *
 * The [handler] receives every [ScrollEvent] dispatched to the root
 * group of the current scene, making it suitable for panning or
 * other viewport-level scroll behaviour.
 *
 * @param handler the callback invoked with each scroll event
 * @throws IllegalStateException if no scene is set on this engine
 */
fun DrawEngine.onScroll(handler: (ScrollEvent) -> Unit) {
    val root = scene?.root
        ?: throw IllegalStateException("Cannot register scroll handler: no scene set on DrawEngine")
    eventDispatcher.addScrollListener(root, EventDispatcher.ScrollHandler { handler(it) })
}

/**
 * Registers a global zoom handler on the scene's root node.
 *
 * The [handler] is called with the current [DrawEngine.cameraZoom] and
 * the vertical scroll delta each time a [ScrollEvent] arrives at the root
 * node. Callers typically adjust [DrawEngine.setCameraZoom] inside the
 * handler to implement scroll-to-zoom.
 *
 * Usage:
 * ```kotlin
 * engine.onZoom { currentZoom, deltaY ->
 *     val newZoom = (currentZoom - deltaY.toFloat() * 0.001f).coerceIn(0.1f, 10f)
 *     engine.cameraZoom = newZoom
 * }
 * ```
 *
 * @param handler callback receiving the current zoom factor and vertical delta
 * @throws IllegalStateException if no scene is set on this engine
 */
fun DrawEngine.onZoom(handler: (currentZoom: Float, deltaY: Double) -> Unit) {
    val root = scene?.root
        ?: throw IllegalStateException("Cannot register zoom handler: no scene set on DrawEngine")
    eventDispatcher.addScrollListener(root, EventDispatcher.ScrollHandler { event ->
        handler(cameraZoom, event.deltaY)
    })
}

// ---------------------------------------------------------------------------
// InteractionBuilder
// ---------------------------------------------------------------------------

/**
 * DSL builder for declaratively attaching interaction handlers to a [Node].
 *
 * Use the [Node.interact] extension function to obtain an [InteractionBuilder]
 * context in which [onClick] and [onDrag] calls register handlers that are
 * bulk-registered with the engine's [EventDispatcher] when the block exits.
 *
 * ```kotlin
 * myNode.interact(engine) {
 *     onClick { event ->
 *         println("Clicked at ${event.position}")
 *     }
 *     onDrag { event ->
 *         println("Dragged: ${event.delta}")
 *     }
 * }
 * ```
 */
class InteractionBuilder internal constructor(
    private val engine: DrawEngine,
    private val node: Node
) {
    private val mouseHandlers = mutableListOf<(MouseEvent) -> Unit>()
    private val dragHandlers = mutableListOf<(DragEvent) -> Unit>()

    /**
     * Adds a mouse-click handler for the target node.
     *
     * Multiple handlers may be registered; they are invoked in the order
     * they were added.
     *
     * @param handler the callback invoked with each mouse event
     */
    fun onClick(handler: (MouseEvent) -> Unit) {
        mouseHandlers.add(handler)
    }

    /**
     * Adds a drag handler for the target node.
     *
     * Multiple handlers may be registered; they are invoked in the order
     * they were added.
     *
     * @param handler the callback invoked with each drag event
     */
    fun onDrag(handler: (DragEvent) -> Unit) {
        dragHandlers.add(handler)
    }

    /**
     * Registers all accumulated handlers with the engine's event dispatcher.
     * Called internally by [Node.interact] after the DSL block completes.
     */
    internal fun register() {
        val dispatcher = engine.eventDispatcher
        mouseHandlers.forEach { h ->
            dispatcher.addMouseListener(node, EventDispatcher.MouseHandler { h(it) })
        }
        dragHandlers.forEach { h ->
            dispatcher.addDragListener(node, EventDispatcher.DragHandler { h(it) })
        }
    }
}

// ---------------------------------------------------------------------------
// Node extension
// ---------------------------------------------------------------------------

/**
 * Configures interaction handlers for this node using a Kotlin DSL block.
 *
 * Creates an [InteractionBuilder] scoped to this node and the supplied
 * [engine], executes [block] to collect handler registrations, and then
 * bulk-registers them with the engine's [EventDispatcher].
 *
 * ```kotlin
 * val rect = ShapeNode(/* ... */)
 * scene.add(rect)
 *
 * rect.interact(engine) {
 *     onClick { evt ->
 *         if (evt.type == MouseEvent.Type.CLICKED) {
 *             println("Rect clicked!")
 *         }
 *     }
 *     onDrag { evt ->
 *         val pos = evt.position
 *         rect.transform.setPosition(pos.x, pos.y)
 *     }
 * }
 * ```
 *
 * @param engine the [DrawEngine] whose event dispatcher will receive the handlers
 * @param block  DSL block executed with an [InteractionBuilder] as receiver
 */
fun Node.interact(engine: DrawEngine, block: InteractionBuilder.() -> Unit) {
    val builder = InteractionBuilder(engine, this)
    builder.block()
    builder.register()
}
