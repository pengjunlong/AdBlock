package com.pengjunlong.adblock.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pengjunlong.adblock.databinding.ItemAppBinding

/**
 * 应用选择器 Adapter
 *
 * 支持：
 * - 搜索过滤（对外暴露 [filter] 方法）
 * - 多选（通过 [selectedPackages] 维护选中集合）
 * - 点击行切换选中状态
 */
class AppPickerAdapter(
    /** 已在黑名单中的包名（初始勾选） */
    initialSelected: Set<String> = emptySet(),
    /** 每次勾选状态变化时回调，参数为当前选中数量 */
    private val onSelectionChanged: ((count: Int) -> Unit)? = null,
) : ListAdapter<AppInfo, AppPickerAdapter.ViewHolder>(DIFF_CALLBACK) {

    /** 当前选中的包名集合（可读） */
    val selectedPackages: MutableSet<String> = initialSelected.toMutableSet()

    /** 完整列表（用于搜索过滤的原始数据） */
    private var fullList: List<AppInfo> = emptyList()

    fun setFullList(list: List<AppInfo>) {
        fullList = list
        submitList(list)
    }

    /** 按关键词过滤（应用名或包名包含 query 则保留） */
    fun filter(query: String) {
        val trimmed = query.trim().lowercase()
        val filtered = if (trimmed.isEmpty()) {
            fullList
        } else {
            fullList.filter { app ->
                app.appName.lowercase().contains(trimmed) ||
                        app.packageName.lowercase().contains(trimmed)
            }
        }
        submitList(filtered)
    }

    inner class ViewHolder(
        private val binding: ItemAppBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppInfo) {
            binding.tvAppName.text     = app.appName
            binding.tvPackageName.text = app.packageName
            binding.ivAppIcon.setImageDrawable(app.icon)
            binding.cbSelected.isChecked = selectedPackages.contains(app.packageName)

            binding.root.setOnClickListener {
                val pkg = app.packageName
                if (selectedPackages.contains(pkg)) {
                    selectedPackages.remove(pkg)
                    binding.cbSelected.isChecked = false
                } else {
                    selectedPackages.add(pkg)
                    binding.cbSelected.isChecked = true
                }
                onSelectionChanged?.invoke(selectedPackages.size)
            }
            // checkbox 本身也可以点击，与行点击保持一致
            binding.cbSelected.setOnClickListener {
                binding.root.performClick()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(old: AppInfo, new: AppInfo) =
                old.packageName == new.packageName

            override fun areContentsTheSame(old: AppInfo, new: AppInfo) =
                old.packageName == new.packageName && old.appName == new.appName
        }
    }
}

