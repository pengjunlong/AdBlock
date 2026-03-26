package com.pengjunlong.adblock.service

/**
 * 坐标点击规则
 *
 * 用户在「区域标记」界面用手指划定一个矩形区域后，记录该矩形的中心点像素坐标。
 * 服务检测到对应包名进入前台，并满足时间条件后，直接对该坐标执行手势点击，
 * 优先级高于关键词扫描，适用于无障碍节点无法识别按钮文字的场景。
 *
 * ## 触发时机
 *
 * 开屏广告只在 App **冷启动或长时间后台恢复**时出现，因此需要两个时间条件共同判断：
 *
 * 1. **重新进入间隔（reEnterGapSec）**：上次该 App 切到后台后，再次进入前台的间隔
 *    必须 ≥ 此值（默认 30s），才认为可能有广告。若间隔太短（App 内跳转），跳过。
 *
 * 2. **广告窗口（adWindowSec）**：App 进入前台后，只在最初 N 秒内触发一次点击
 *    （默认 10s）。超过这个时间窗口后，认为广告已过，不再点击。
 *
 * @param cx             点击点 X 坐标（屏幕像素，中心点）
 * @param cy             点击点 Y 坐标（屏幕像素，中心点）
 * @param label          用户备注（如「百度地图跳过」），仅用于界面展示
 * @param adWindowSec    广告出现时间窗口（秒），App 切到前台后超过此时间不再触发，默认 10s
 * @param reEnterGapSec  重新进入前台的最小间隔（秒），小于此值认为是 App 内跳转，默认 30s
 */
data class RegionRule(
    val cx: Float,
    val cy: Float,
    val label: String = "",
    val adWindowSec: Int = 10,
    val reEnterGapSec: Int = 30,
)

