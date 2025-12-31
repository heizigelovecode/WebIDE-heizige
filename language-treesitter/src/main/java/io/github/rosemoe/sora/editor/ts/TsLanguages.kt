package io.github.rosemoe.sora.editor.ts

import com.itsaky.androidide.treesitter.TSLanguage

/**
 * HTML 语言绑定
 * 构造函数参数 1: 语言名称 "html"
 * 构造函数参数 2: Native 指针
 */
class HtmlLanguage : TSLanguage("html", tree_sitter_html()) {
    private companion object {
        init {
            System.loadLibrary("tree-sitter-html")
        }
        @JvmStatic external fun tree_sitter_html(): Long
    }
}

/**
 * CSS 语言绑定
 */
class CssLanguage : TSLanguage("css", tree_sitter_css()) {
    private companion object {
        init {
            System.loadLibrary("tree-sitter-css")
        }
        @JvmStatic external fun tree_sitter_css(): Long
    }
}

/**
 * JavaScript 语言绑定
 */
class JavaScriptLanguage : TSLanguage("javascript", tree_sitter_javascript()) {
    private companion object {
        init {
            System.loadLibrary("tree-sitter-javascript")
        }
        @JvmStatic external fun tree_sitter_javascript(): Long
    }
}

/**
 * JSON 语言绑定
 */
class JsonLanguage : TSLanguage("json", tree_sitter_json()) {
    private companion object {
        init {
            try {
                System.loadLibrary("tree-sitter-json")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }
        @JvmStatic external fun tree_sitter_json(): Long
    }
}