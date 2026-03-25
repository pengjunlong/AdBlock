package com.example.framework.ui.ext

import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.framework.core.AppContext

/**
 * View 相关扩展函数
 */

fun View.visible() { visibility = View.VISIBLE }
fun View.invisible() { visibility = View.INVISIBLE }
fun View.gone() { visibility = View.GONE }

fun View.isVisible() = visibility == View.VISIBLE
fun View.isGone() = visibility == View.GONE

/** 防抖点击（默认 500ms 内只响应一次）*/
fun View.setOnSingleClickListener(intervalMs: Long = 500L, onClick: (View) -> Unit) {
    var lastClickTime = 0L
    setOnClickListener { v ->
        val now = System.currentTimeMillis()
        if (now - lastClickTime >= intervalMs) {
            lastClickTime = now
            onClick(v)
        }
    }
}

/** 全局 Toast */
fun toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(AppContext.context, message, duration).show()
}

/** Fragment 扩展 Toast */
fun Fragment.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(requireContext(), message, duration).show()
}

