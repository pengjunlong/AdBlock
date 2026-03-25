package com.example.framework.ui.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.example.framework.logger.L
import kotlinx.coroutines.launch

/**
 * Fragment 基类（ViewBinding + ViewModel 模式）
 *
 * 注意：ViewBinding 在 [onDestroyView] 中置空，以避免内存泄漏。
 * 不要在 [onDestroyView] 之后访问 [binding]。
 *
 * ### 使用示例
 * ```kotlin
 * class HomeFragment : BaseFragment<FragmentHomeBinding>(FragmentHomeBinding::inflate) {
 *
 *     private val viewModel: HomeViewModel by viewModels()
 *
 *     override fun initViews() {
 *         binding.refreshButton.setOnClickListener { viewModel.refresh() }
 *     }
 *
 *     override fun initObservers() {
 *         launchWhenStarted {
 *             viewModel.items.collect { binding.adapter.submitList(it) }
 *         }
 *     }
 * }
 * ```
 */
abstract class BaseFragment<VB : ViewBinding>(
    private val inflate: (LayoutInflater, ViewGroup?, Boolean) -> VB
) : Fragment() {

    private var _binding: VB? = null

    /** ViewBinding 实例，仅在 [onCreateView] ~ [onDestroyView] 期间有效 */
    protected val binding: VB
        get() = _binding ?: error("Binding is accessed after onDestroyView or before onCreateView.")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        L.d("${this::class.java.simpleName} onViewCreated")
        initViews()
        initObservers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        L.d("${this::class.java.simpleName} onDestroyView")
    }

    /**
     * 初始化 View，此时 [binding] 已可用
     */
    protected open fun initViews() {}

    /**
     * 订阅 ViewModel 数据，此时 [binding] 已可用
     */
    protected open fun initObservers() {}

    /**
     * 在 [Lifecycle.State.STARTED] 生命周期范围内安全收集 Flow
     * （使用 viewLifecycleOwner，Fragment 不可见时自动暂停）
     */
    protected fun launchWhenStarted(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                block()
            }
        }
    }

    /**
     * 展示 loading
     */
    protected open fun showLoading(show: Boolean) {}

    /**
     * 展示错误信息
     */
    protected open fun showError(message: String) {
        L.w("${this::class.java.simpleName} error: $message")
    }
}

