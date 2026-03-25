# AdBlock — Android 开屏广告自动跳过工具

基于 **Android 无障碍服务（AccessibilityService）** 实现的常驻后台工具，自动检测并点击各类 App 的开屏广告跳过按钮。

---

## 功能特性

- 🚀 **自动跳过开屏广告** — 监听窗口变化事件，BFS 遍历节点树，匹配关键词后自动点击
- 🎯 **黑名单精准模式** — 仅对加入黑名单的 App 生效；黑名单为空时对所有非系统应用全局生效
- 🔑 **关键词自定义** — 内置常用关键词（跳过 / SKIP / 关闭广告等），支持增删和一键恢复默认
- 🛡️ **系统应用保护** — 多重过滤机制（包名前缀 + `FLAG_SYSTEM` 兜底），不干预系统设置/权限页
- 📊 **今日统计** — 实时显示当日跳过次数与最近处理的 App
- 🔔 **前台服务常驻** — 通知栏显示运行状态和统计信息，防止被系统回收
- ⚡ **开机自启** — 监听 `BOOT_COMPLETED` 广播，重启后自动拉起前台服务
- ⚙️ **参数可调** — 点击延迟（默认 300 ms）、Toast 提示开关

---

## 截图 / 使用流程

1. 安装 APK → 打开应用
2. 点击「前往开启无障碍权限」，在系统设置中为「广告跳过无障碍服务」授权
3. （可选）在「黑名单」卡片点击「选择应用」，精确指定需要处理的 App
4. 保持广告跳过开关开启，正常使用手机即可

---

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.1.0 |
| 异步 | Kotlin Coroutines | 1.9.0 |
| 架构 | MVVM + StateFlow | — |
| 视图绑定 | ViewBinding | — |
| 存储 | MMKV | 2.2.2 |
| 序列化 | Gson | 2.11.0 |
| 网络（检查更新） | OkHttp + Retrofit | 4.12.0 / 2.11.0 |
| 崩溃上报 | ACRA | 5.13.1 |
| 日志 | Timber | 5.0.1 |
| UI | AndroidX + Material Components | — |
| 构建 | AGP + Version Catalog | 8.7.3 |
| 最低 SDK | Android 7.0 (API 24) | — |
| 目标 SDK | Android 15 (API 35) | — |

---

## 项目结构

```
AdBlock/
├── gradle/libs.versions.toml          # 统一版本目录（Version Catalog）
├── settings.gradle.kts                # 模块注册
├── build.gradle.kts                   # 根构建
│
├── framework-core/                    # 基础核心库
├── framework-crash/                   # 崩溃上报（ACRA 封装）
├── framework-logger/                  # 日志（Timber 封装）
├── framework-network/                 # 网络（OkHttp + Retrofit）
├── framework-storage/                 # 存储（MMKV 封装）
├── framework-ui/                      # 基础 UI（BaseActivity / BaseViewModel）
│
└── app/
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── layout/activity_main.xml        # 主控制面板布局
        │   ├── layout/dialog_app_picker.xml    # 应用选择器对话框
        │   ├── layout/item_app.xml             # 应用列表单项
        │   └── xml/accessibility_service_config.xml
        └── java/com/pengjunlong/adblock/
            ├── SampleApplication.kt            # Application 初始化
            ├── service/
            │   ├── AdBlockService.kt           # 无障碍服务（核心）
            │   ├── AdBlockConfig.kt            # 配置持久化（MMKV）
            │   ├── AdBlockForegroundService.kt # 前台服务（保活 + 通知）
            │   └── BootReceiver.kt             # 开机自启广播接收器
            └── ui/main/
                ├── MainActivity.kt             # 主界面
                ├── MainViewModel.kt            # 数据与业务逻辑
                └── AppPickerAdapter.kt         # 应用选择器 RecyclerView 适配器
```

---

## 核心设计

### 无障碍服务工作原理（`AdBlockService`）

```
窗口变化事件
(TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_CONTENT_CHANGED)
        │
        ▼
    过滤判断
  ┌─────────────────────────────────────────┐
  │ 1. 服务总开关关闭？→ 直接返回            │
  │ 2. 系统 UI / Launcher / Settings？→ 跳过 │
  │ 3. FLAG_SYSTEM 系统应用？→ 跳过（兜底）  │
  │ 4. 不在黑名单内（黑名单非空时）？→ 跳过  │
  └─────────────────────────────────────────┘
        │
        ▼ 延迟 clickDelayMs（默认 300ms）
    BFS 遍历节点树
        │
        ▼ 匹配关键词（text / contentDescription）
    向上查找可点击父节点（最多 6 层）
        │
        ▼ 冷却检查（同一 windowId 2s 内只触发一次）
    performAction(ACTION_CLICK)
        │
        ▼
  统计 +1 / Toast / 通知栏刷新
```

### 黑名单逻辑（`AdBlockConfig.shouldHandle`）

```kotlin
// 黑名单为空  → 全局模式，对所有非系统应用生效
// 黑名单非空 → 精准模式，只处理名单内的应用
fun shouldHandle(packageName: String): Boolean {
    val list = getTargetList()
    return list.isEmpty() || list.contains(packageName)
}
```

### 防误触策略

| 策略 | 实现方式 |
|------|----------|
| 系统包名前缀过滤 | `SYSTEM_PACKAGES` Set，`startsWith` 匹配 |
| 系统应用兜底 | `ApplicationInfo.FLAG_SYSTEM` 标志位判断 |
| 同窗口冷却 | 相同 `windowId` 2000 ms 内只点击一次 |
| 短关键词精确匹配 | 长度 ≤ 2 的关键词（×、✕）做精确匹配，避免误触正文 |
| 延迟执行 | 收到事件后延迟 `clickDelayMs` 再扫描，等待布局渲染完成 |

---

## 内置默认关键词

```
跳过 / 跳过广告 / 跳过片头 / 跳过片尾 / 立即跳过
关闭广告 / 关闭 / × / ✕
skip / SKIP / Skip / skip ad / SKIP AD / close ad
```

在应用主界面可以自由增删，或一键恢复默认。

---

## 构建与安装

### 环境要求

- Android Studio Ladybug 以上
- JDK 17
- Gradle 8.9

### 本地构建

```bash
# 编译 Debug 包（免签名，可直接安装调试）
./gradlew assembleDebug

# 编译 Release 包（需配置签名，见下方）
./gradlew assembleRelease

# 清理构建缓存
./gradlew clean
```

### 配置签名

在项目根目录创建 `keystore.properties`（**不要提交到 Git**）：

```properties
storeFile=../release.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

### GitHub Actions 自动发布

| 工作流 | 触发方式 | 功能 |
|--------|---------|------|
| `ci.yml` | 推送 `v*` Tag 或向 `main`/`develop` 发起 PR | 编译检查 + Lint |
| `release.yml` | 推送 `v*` Tag（如 `v1.0.0`） | 构建签名 APK + 创建 GitHub Release |

在 GitHub → Settings → Secrets → Actions 中配置：

| Secret | 说明 |
|--------|------|
| `KEYSTORE_BASE64` | `base64 -i release.jks \| pbcopy` 的输出 |
| `KEYSTORE_PASSWORD` | KeyStore 密码 |
| `KEY_ALIAS` | Key 别名 |
| `KEY_PASSWORD` | Key 密码 |

---

## 权限说明

| 权限 | 用途 |
|------|------|
| `BIND_ACCESSIBILITY_SERVICE` | 无障碍服务核心权限（系统授予） |
| `FOREGROUND_SERVICE` | 前台服务保活 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ 特殊用途前台服务 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `POST_NOTIFICATIONS` | Android 13+ 显示前台服务通知 |
| `INTERNET` | 检查 GitHub Release 更新 |

---

## 常见问题

**Q: 为什么有些 App 的广告跳不过？**
> 部分 App 的跳过按钮使用自定义 View 绘制，没有设置 `text`/`contentDescription`，无障碍服务无法识别文字节点。可以尝试在「关键词管理」中添加该 App 使用的特殊关键词。

**Q: 为什么跳过了系统设置页面的按钮？**
> 已通过 `SYSTEM_PACKAGES` 包名前缀过滤 + `FLAG_SYSTEM` 兜底机制解决。如仍有问题，可将该 App 包名手动加入黑名单之外（即黑名单非空时，只处理名单内的应用，不在名单内的系统 App 不会被干预）。

**Q: 黑名单为空和不为空有什么区别？**
> - **黑名单为空（默认）**：全局模式，对所有非系统用户 App 生效
> - **黑名单非空**：精准模式，只对名单内的 App 执行跳过操作，其余 App 一律不干预

**Q: 如何排查服务是否正常运行？**
> 主界面顶部会显示「无障碍服务：已开启 ✅」，通知栏也会有持续的「广告跳过助手」通知。如果这两项都存在，说明服务正常运行。

---

## 开源协议

MIT License

