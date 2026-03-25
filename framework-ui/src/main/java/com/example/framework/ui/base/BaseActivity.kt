package com.example.framework.ui.base

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.example.framework.logger.L
import kotlinx.coroutines.launch

/**
 * Activity 基类（ViewBinding + ViewModel 模式）
 *
 * 提供：
 * - ViewBinding 自动绑定（通过泛型 + [inflate] lambda）
 * - 统一 loading / error 处理钩子
 * - [launchWhenStarted] / [launchWhenResumed] 生命周期安全协程
 *
 * ### 使用示例
 * ```kotlin
 * class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {
 *
 *     private val viewModel: MainViewModel by viewModels()
 *
 *     override fun initViews() {
 *         binding.button.setOnClickListener { viewModel.loadData() }
 *     }
 *
 *     override fun initObservers() {
 *         launchWhenStarted {
 *             viewModel.data.collect { showData(it) }
 *         }
 *         launchWhenStarted {
 *             viewModel.isLoading.collect { showLoading(it) }
 *         }
 *         launchWhenStarted {
 *             viewModel.errorEvent.collect { showError(it) }
 *         }
 *     }
 * }
 * ```
 */
abstract class BaseActivity<VB : ViewBinding>(
    private val inflate: (LayoutInflater) -> VB
) : AppCompatActivity() {

    private var _binding: VB? = null

    /** ViewBinding 实例，仅在 [onCreate] ~ [onDestroy] 期间有效 */
    protected val binding: VB
        get() = _binding ?: error("Binding is accessed after onDestroy or before onCreate.")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = inflate(layoutInflater)
        setContentView(binding.root)
        L.d("${this::class.java.simpleName} onCreate")
        initViews()
        initObservers()
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
        L.d("${this::class.java.simpleName} onDestroy")
    }

    /**
     * 初始化 View（设置点击事件、Adapter 等），此时 [binding] 已可用
     */
    protected open fun initViews() {}

    /**
     * 初始化数据观察（订阅 ViewModel 的 Flow/LiveData）
     */
    protected open fun initObservers() {}

    /**
     * 在 [Lifecycle.State.STARTED] 状态下安全收集 Flow（页面不可见时自动暂停）
     */
    protected fun launchWhenStarted(block: suspend () -> Unit) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                block()
            }
        }
    }

    /**
     * 在 [Lifecycle.State.RESUMED] 状态下安全收集 Flow
     */
    protected fun launchWhenResumed(block: suspend () -> Unit) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                block()
            }
        }
    }

    /**
     * 展示 loading（子类按需重写）
     */
    protected open fun showLoading(show: Boolean) {}

    /**
     * 展示错误信息（子类按需重写，默认无操作）
     */
    protected open fun showError(message: String) {
        L.w("${this::class.java.simpleName} error: $message")
    }
}

