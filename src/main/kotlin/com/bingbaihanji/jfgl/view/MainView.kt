package com.bingbaihanji.jfgl.view

import javafx.scene.Node
import javafx.scene.layout.BorderPane

/**
 * 应用程序主布局视图，基于 BorderPane 封装。
 * 通过属性设置各区域节点，内部自动更新 UI，外部通过 [createMainView] 获取根节点。
 */
class MainView {

    // 私有根布局，外部无法直接修改其子节点
    private val root = BorderPane().apply {
        style = "-fx-background-color: #fafafa; -fx-padding: 5;"
    }

    var menu: Node? = null
        set(value) {
            field = value
            root.top = value
        }

    var center: Node? = null
        set(value) {
            field = value
            root.center = value
        }

    var bottom: Node? = null
        set(value) {
            field = value
            root.bottom = value
        }

    var left: Node? = null
        set(value) {
            field = value
            root.left = value
        }

    var right: Node? = null
        set(value) {
            field = value
            root.right = value
        }

    /**
     * 返回主布局的根节点（BorderPane 实例），可直接添加到场景图。
     * 返回类型明确为 BorderPane，满足 Scene 构造器对 Parent 的要求。
     */
    fun createMainView(): BorderPane = root
}