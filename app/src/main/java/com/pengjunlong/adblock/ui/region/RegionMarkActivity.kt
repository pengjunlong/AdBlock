package com.pengjunlong.adblock.ui.region

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.example.framework.ui.ext.toast
import com.pengjunlong.adblock.R
import com.pengjunlong.adblock.databinding.ActivityRegionMarkBinding
import com.pengjunlong.adblock.service.AdBlockConfig
import com.pengjunlong.adblock.service.RegionRule
import com.pengjunlong.adblock.ui.region.RegionMarkActivity.Companion.EXTRA_APP_NAME
import com.pengjunlong.adblock.ui.region.RegionMarkActivity.Companion.EXTRA_PACKAGE
import com.pengjunlong.adblock.ui.region.RegionMarkActivity.Companion.start

/**
 * 区域标记界面
 *
 * 用户在此全屏页面拖拽选取「跳过广告」按钮的矩形区域，
 * 松手后保存中心点坐标为该 App 的 [RegionRule]。
 *
 * 如果 [AdBlockConfig] 中已有该包名的截图，则将截图铺满全屏作为背景，
 * 方便用户在真实截图上精准定位按钮位置。
 *
 * 启动方式：[start]
 *
 * 额外数据（通过 Intent Extra 传入）：
 * - [EXTRA_PACKAGE]  目标 App 包名（必填）
 * - [EXTRA_APP_NAME] 目标 App 名称（用于展示，可选）
 */
class RegionMarkActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE  = "extra_package"
        const val EXTRA_APP_NAME = "extra_app_name"

        /**
         * 从 [context] 启动区域标记界面，[packageName] 为目标 App 包名。
         * 会自动从 [AdBlockConfig] 查询该包名的截图路径并作为背景。
         */
        fun start(context: Context, packageName: String, appName: String = packageName) {
            context.startActivity(
                Intent(context, RegionMarkActivity::class.java)
                    .putExtra(EXTRA_PACKAGE, packageName)
                    .putExtra(EXTRA_APP_NAME, appName)
            )
        }
    }

    private lateinit var binding: ActivityRegionMarkBinding

    private var targetPackage = ""
    private var targetAppName = ""

    /** 当前选定的矩形中心坐标（未确定时为 null） */
    private var selectedCx: Float? = null
    private var selectedCy: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸，不显示状态栏/导航栏，让用户看到完整屏幕
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.hide(
                android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )
        }

        binding = ActivityRegionMarkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: run {
            finish(); return
        }
        targetAppName = intent.getStringExtra(EXTRA_APP_NAME) ?: targetPackage

        binding.tvPkg.text = getString(R.string.region_mark_pkg_label, targetAppName)

        loadScreenshotBackground()
        setupDrawView()
        setupButtons()
    }

    // ─── 截图背景 ──────────────────────────────────────────────────────────────

    /**
     * 尝试从 [AdBlockConfig] 加载该包名对应的截图文件，作为背景图显示。
     *
     * 有截图：显示截图 + 半透明遮罩，并更新提示文字。
     * 无截图：保持黑色半透明背景，提示用户去悬浮窗截图。
     */
    private fun loadScreenshotBackground() {
        val screenshotPath = AdBlockConfig.getScreenshotPath(targetPackage)

        if (!screenshotPath.isNullOrEmpty()) {
            val file = java.io.File(screenshotPath)
            if (file.exists()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        binding.ivScreenshot.setImageBitmap(bitmap)
                        binding.ivScreenshot.visibility = View.VISIBLE
                        binding.viewDimOverlay.visibility = View.VISIBLE
                        // 有截图时，把 FrameLayout 背景去掉（截图本身就是背景）
                        binding.root.setBackgroundColor(android.graphics.Color.BLACK)
                        // 提示用户有截图
                        binding.tvPkg.text = buildString {
                            append(getString(R.string.region_mark_pkg_label, targetAppName))
                            append("\n")
                            append(getString(R.string.region_mark_has_screenshot))
                        }
                        return
                    }
                } catch (_: Exception) {
                    // 解码失败，降级为纯色背景
                }
            }
        }

        // 无截图，显示提示
        binding.tvPkg.text = buildString {
            append(getString(R.string.region_mark_pkg_label, targetAppName))
            append("\n")
            append(getString(R.string.region_mark_no_screenshot))
        }
    }

    // ─── 绘制区域 & 按钮 ───────────────────────────────────────────────────────

    private fun setupDrawView() {
        binding.regionDrawView.onRegionSelected = { cx, cy, _ ->
            selectedCx = cx
            selectedCy = cy
            // 更新坐标提示
            binding.tvCoord.text = getString(R.string.region_mark_coord_label, cx, cy)
            binding.tvCoord.visibility = View.VISIBLE
            // 显示确认 / 重划按钮
            binding.btnConfirm.visibility = View.VISIBLE
            binding.btnRedo.visibility    = View.VISIBLE
        }
    }

    private fun setupButtons() {
        binding.btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        binding.btnRedo.setOnClickListener {
            binding.regionDrawView.clearRegion()
            selectedCx = null
            selectedCy = null
            binding.tvCoord.visibility    = View.GONE
            binding.btnConfirm.visibility = View.GONE
            binding.btnRedo.visibility    = View.GONE
        }

        binding.btnConfirm.setOnClickListener {
            val cx = selectedCx
            val cy = selectedCy
            if (cx == null || cy == null) {
                toast(getString(R.string.region_mark_no_region))
                return@setOnClickListener
            }
            // 保存规则
            AdBlockConfig.setRegionRule(
                targetPackage,
                RegionRule(cx = cx, cy = cy, label = targetAppName),
            )
            toast(getString(R.string.region_rule_saved_toast, targetAppName))
            setResult(Activity.RESULT_OK)
            finish()
        }
    }
}

