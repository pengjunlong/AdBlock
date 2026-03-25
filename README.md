# Android Framework Sample

一个基于 **Kotlin + Coroutines + ViewBinding** 的 Android 多模块框架项目，开箱即用，方便后续各业务项目快速复用。

## 目录

- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [设计思路](#设计思路)
- [模块说明](#模块说明)
- [接入新项目](#接入新项目)
- [开发指南](#开发指南)
- [构建与发布](#构建与发布)

---

## 技术栈

| 类别 | 库 | 版本 |
|------|----|------|
| 语言 | Kotlin | 2.1.0 |
| 异步 | Kotlin Coroutines | 1.9.0 |
| 崩溃上报 | ACRA | 5.11.4 |
| 日志 | Timber | 5.0.1 |
| 网络 | OkHttp + Retrofit + Gson | 4.12.0 / 2.11.0 |
| 存储 | MMKV | 2.2.2 |
| UI | AndroidX + Material | — |
| 构建 | AGP + Version Catalog | 8.7.3 |

---

## 项目结构

```
android_sample/
├── gradle/
│   └── libs.versions.toml        # 统一版本目录（Version Catalog）
├── settings.gradle.kts           # 模块注册
├── build.gradle.kts              # 根构建（plugin 声明）
├── gradle.properties             # Gradle 全局配置
│
├── framework-core/               # 基础核心库（其他所有模块的基石）
│   └── src/main/java/.../core/
│       ├── AppContext.kt         # 全局 Application/Context 持有
│       ├── BaseApplication.kt    # Application 基类
│       └── initializer/
│           ├── IInitializer.kt         # 初始化器接口
│           └── FrameworkInitializer.kt # 统一按优先级调度初始化
│       └── utils/
│           ├── AppUtils.kt       # 应用版本/包名/Debug 判断
│           ├── ProcessUtils.kt   # 进程工具
│           └── ThreadUtils.kt    # 主线程/IO 线程调度
│
├── framework-crash/              # 异常上报模块（ACRA 封装）
│   └── src/main/java/.../crash/
│       ├── CrashConfig.kt        # 崩溃配置（Builder 模式）
│       ├── CrashListener.kt      # 崩溃回调接口
│       └── CrashReporter.kt      # ACRA 初始化 + 手动上报入口
│
├── framework-logger/             # 日志模块（Timber 封装）
│   └── src/main/java/.../logger/
│       ├── L.kt                  # 日志快捷入口（L.d / L.e / L.i ...）
│       ├── LoggerInitializer.kt  # Debug=DebugTree / Release=ReleaseTree
│       └── ReleaseTree.kt        # Release 日志树（过滤低级别，上报 ERROR）
│
├── framework-network/            # 网络模块（OkHttp + Retrofit）
│   └── src/main/java/.../network/
│       ├── NetworkConfig.kt      # 网络配置（baseUrl/超时/拦截器）
│       ├── NetworkManager.kt     # OkHttp + Retrofit 构建与管理
│       ├── ApiResult.kt          # 统一请求结果（Success/Error/Loading）
│       └── NetworkExt.kt         # safeApiCall{} 协程安全调用扩展
│
├── framework-storage/            # 存储模块（MMKV）
│   └── src/main/java/.../storage/
│       ├── StorageInitializer.kt # MMKV 全局初始化
│       └── KVStore.kt            # 类型安全的 KV 读写门面
│
├── framework-ui/                 # 基础 UI 模块
│   └── src/main/java/.../ui/
│       ├── base/
│       │   ├── BaseViewModel.kt  # 统一 loading/error/request 封装
│       │   ├── BaseActivity.kt   # ViewBinding + 生命周期安全协程
│       │   └── BaseFragment.kt   # ViewBinding（onDestroyView 自动释放）
│       └── ext/
│           └── ViewExt.kt        # View 扩展（visible/gone/防抖点击/toast）
│
└── app/                          # 示例应用（演示框架各模块用法）
    └── src/main/java/.../
        ├── SampleApplication.kt          # 演示框架完整接入
        ├── data/
        │   ├── model/Post.kt             # 数据模型
        │   ├── remote/PostApiService.kt  # Retrofit API 接口
        │   └── repository/PostRepository.kt
        └── ui/main/
            ├── MainViewModel.kt          # 继承 BaseViewModel
            └── MainActivity.kt           # 继承 BaseActivity
```

---

## 设计思路

### 1. 模块化分层

框架按职责拆分为独立 AAR 库模块，依赖关系单向流动：

```
app
 └── framework-ui
 └── framework-network
 └── framework-storage
 └── framework-crash
 └── framework-logger
      └── framework-core  ← 所有模块的基础依赖
```

业务项目可按需引入，不需要的模块完全不依赖。

### 2. 统一初始化机制

所有模块实现 `IInitializer` 接口，通过 `priority()` 控制执行顺序：

```kotlin
// 优先级从小到大执行
CrashReporter  → priority = Int.MIN_VALUE  // 最先：保证崩溃上报第一个就绪
LoggerInitializer → priority = -100
StorageInitializer → priority = -80
NetworkInitializer → priority = -50
// 业务初始化（onAppCreate）最后执行
```

`FrameworkInitializer` 统一排序调度，`BaseApplication` 只需一次调用：

```kotlin
override fun onCreate() {
    AppContext.init(this)
    registerInitializers()      // 注册各模块
    FrameworkInitializer.init(this) // 按优先级执行
    onAppCreate()               // 业务层初始化
}
```

### 3. 异常上报（ACRA）

- **自动捕获**：ACRA 接管 `UncaughtExceptionHandler`，崩溃自动收集
- **HTTP 上报**：可配置 POST 到自建服务器（JSON 格式）
- **Debug Toast**：调试时崩溃弹 Toast 提示，不污染生产数据
- **自定义回调**：`CrashListener` 可用于写本地日志文件
- **手动上报**：`CrashReporter.report(e)` 上报 try-catch 捕获的非致命异常
- **自定义字段**：`CrashReporter.putCustomData("user_id", uid)` 附加上下文信息

### 4. 网络层（ApiResult）

用 `sealed class ApiResult<T>` 替代 try-catch，让错误处理回到类型系统：

```
Repository → safeApiCall{} → ApiResult<T>
                                 ├── Success(data)
                                 ├── Error(message, code, cause)
                                 └── Loading
ViewModel → request{} → 自动处理 Loading 状态 + 错误转发到 errorEvent
```

### 5. UI 基类

`BaseViewModel.request{}` 自动管理 loading 状态，无需在每个 ViewModel 重复写：

```kotlin
fun loadData() = request(
    block = { repo.fetchData() },   // 只关心业务逻辑
    onSuccess = { _data.value = it },
    // loading 自动开关，error 自动发送到 errorEvent
)
```

`BaseActivity/BaseFragment` 使用 `launchWhenStarted{}` 安全收集 Flow，页面不可见时自动暂停，避免内存泄漏和无效更新。

---

## 模块说明

### framework-core

所有其他模块的基础依赖，对外通过 `api` 传递 `androidx.core-ktx`、`appcompat`、`Timber`、Coroutines。

| 类 | 说明 |
|----|------|
| `AppContext` | 全局 `Context` 持有，`AppContext.context` 随时取用 |
| `BaseApplication` | Application 基类，子类重写 `registerInitializers()` 和 `onAppCreate()` |
| `FrameworkInitializer` | 初始化调度器，`register()` + `init()` |
| `AppUtils` | 版本名、版本号、包名、是否 Debug |
| `ThreadUtils` | 主线程/IO 线程/CPU 线程池调度 |

### framework-crash

| 类 | 说明 |
|----|------|
| `CrashReporter.initializer(config)` | 创建初始化器，注册到 `FrameworkInitializer` |
| `CrashReporter.report(e)` | 手动上报可捕获异常 |
| `CrashReporter.reportSilent(e)` | 静默上报（不触发通知） |
| `CrashReporter.putCustomData(k, v)` | 附加自定义字段到崩溃报告 |
| `CrashConfig.Builder` | 配置 `reportUrl`、`enableInDebug`、`toastEnabled`、`crashListener` |

### framework-logger

| 类 | 说明 |
|----|------|
| `L.d/i/w/e/v(msg)` | 日志快捷方法 |
| `L.tag("TAG").d(msg)` | 自定义 Tag |
| `L.e(throwable)` | 上报异常 |
| `ReleaseTree(errorReporter)` | Release 日志树，可注入崩溃上报回调 |

### framework-network

| 类 | 说明 |
|----|------|
| `NetworkManager.initializer(config)` | 创建初始化器 |
| `NetworkManager.createService(Class)` | 创建 Retrofit Service |
| `safeApiCall { }` | 协程安全调用，异常转为 `ApiResult.Error` |
| `ApiResult.onSuccess { }.onError { }` | 链式结果处理 |

### framework-storage

| 类 | 说明 |
|----|------|
| `KVStore.putString/getString(key, default)` | 字符串读写 |
| `KVStore.putBoolean/getBoolean(key, default)` | 布尔读写 |
| `KVStore.of("scope_name")` | 获取独立隔离的 MMKV 实例 |
| `KVStore.remove(key)` / `KVStore.clearAll()` | 删除 |

### framework-ui

| 类 | 说明 |
|----|------|
| `BaseActivity<VB>(VB::inflate)` | ViewBinding 基类 Activity |
| `BaseFragment<VB>(VB::inflate)` | ViewBinding 基类 Fragment |
| `BaseViewModel.request{}` | 封装网络请求，自动 loading/error |
| `launchWhenStarted {}` | 生命周期安全的 Flow 收集 |
| `View.setOnSingleClickListener {}` | 防抖点击（默认 500ms） |
| `View.visible()` / `View.gone()` | 可见性扩展 |

---

## 接入新项目

### 方式一：直接复制模块（推荐）

1. **复制所需模块**到新项目根目录（至少复制 `framework-core`，其他按需）

2. **在 `settings.gradle.kts` 中注册模块：**

```kotlin
include(":framework-core")
include(":framework-crash")
include(":framework-logger")
include(":framework-network")
include(":framework-storage")
include(":framework-ui")
```

3. **修改各模块 `build.gradle.kts` 中的 `namespace`**（统一替换包名前缀）：

```kotlin
// 将 com.example.framework 改为你的包名，如：
namespace = "com.yourcompany.framework.core"
```

4. **在 `app/build.gradle.kts` 中引入模块：**

```kotlin
dependencies {
    implementation(project(":framework-core"))
    implementation(project(":framework-crash"))
    implementation(project(":framework-logger"))
    implementation(project(":framework-network"))
    implementation(project(":framework-storage"))
    implementation(project(":framework-ui"))
}
```

5. **创建 Application 类继承 `BaseApplication`：**

```kotlin
class MyApp : BaseApplication() {

    override fun registerInitializers() {
        // 崩溃上报（最高优先级，建议必接）
        FrameworkInitializer.register(
            CrashReporter.initializer(
                CrashConfig.Builder()
                    .reportUrl("https://your-server.com/acra/report") // 上报地址
                    .enableInDebug(false)
                    .toastEnabled(true)
                    .crashListener { _, throwable ->
                        // 可选：写本地日志
                    }
                    .build()
            )
        )
        // 日志
        FrameworkInitializer.register(LoggerInitializer())
        // 存储
        FrameworkInitializer.register(StorageInitializer())
        // 网络
        FrameworkInitializer.register(
            NetworkManager.initializer(
                NetworkConfig(
                    baseUrl = "https://api.yourserver.com/",
                    enableLogging = BuildConfig.DEBUG,
                    interceptors = listOf(AuthInterceptor()),  // 注入 Token 拦截器
                )
            )
        )
    }

    override fun onAppCreate() {
        // 框架全部就绪，在此做业务初始化
        // 如：初始化推送 SDK、路由框架等
    }
}
```

6. **在 `AndroidManifest.xml` 中声明 Application：**

```xml
<application
    android:name=".MyApp"
    ... >
```

### 方式二：发布为 Maven 本地/远程依赖（适合多项目共用）

在各框架模块的 `build.gradle.kts` 中添加 `maven-publish` 插件：

```kotlin
plugins {
    id("maven-publish")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.yourcompany.framework"
            artifactId = "core"           // 各模块不同
            version = "1.0.0"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven { url = uri("../local-maven-repo") }  // 本地 Maven 仓库
    }
}
```

执行 `./gradlew publishReleasePublicationToMavenLocal` 发布到本地，然后在业务项目中：

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("/path/to/local-maven-repo") }
    }
}

// app/build.gradle.kts
implementation("com.yourcompany.framework:core:1.0.0")
implementation("com.yourcompany.framework:crash:1.0.0")
```

---

## 开发指南

### 添加新的业务 Activity

```kotlin
class UserProfileActivity : BaseActivity<ActivityUserProfileBinding>(
    ActivityUserProfileBinding::inflate
) {
    private val viewModel: UserProfileViewModel by viewModels()

    override fun initViews() {
        binding.btnEdit.setOnSingleClickListener {
            // 防抖点击，500ms 内只响应一次
        }
    }

    override fun initObservers() {
        launchWhenStarted {
            viewModel.user.collect { user ->
                binding.tvName.text = user.name
            }
        }
        launchWhenStarted {
            viewModel.isLoading.collect { showLoading(it) }
        }
        launchWhenStarted {
            viewModel.errorEvent.collect { showError(it) }
        }
    }

    override fun showLoading(show: Boolean) {
        binding.progressBar.isVisible = show
    }
}
```

### 添加新的 ViewModel

```kotlin
class UserProfileViewModel : BaseViewModel() {

    private val repo = UserRepository()

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    fun loadUser(id: String) = request(
        block = { repo.fetchUser(id) },
        onSuccess = { _user.value = it },
        onError = { error ->
            // 可选：自定义错误处理，否则 error 自动发送到 errorEvent
            if (error.code == 401) navigateToLogin()
        }
    )
}
```

### 添加网络请求

```kotlin
// 1. 定义 API 接口
interface UserApiService {
    @GET("users/{id}")
    suspend fun getUser(@Path("id") id: String): UserResponse

    @POST("users")
    suspend fun createUser(@Body body: CreateUserRequest): UserResponse
}

// 2. 在 Repository 中使用 safeApiCall
class UserRepository {
    private val api = NetworkManager.createService(UserApiService::class.java)

    suspend fun fetchUser(id: String): ApiResult<User> = safeApiCall {
        api.getUser(id).toUser()
    }
}
```

### 自定义 Token 拦截器

```kotlin
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = KVStore.getString("access_token", "")
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()
        return chain.proceed(request)
    }
}

// 注册到 NetworkConfig
NetworkConfig(
    baseUrl = "...",
    interceptors = listOf(AuthInterceptor()),
)
```

### KV 存储最佳实践

建议用 `object` 封装业务相关的 Key 常量，避免 Key 字符串散落各处：

```kotlin
object UserPrefs {
    private val store = KVStore.of("user")   // 独立 scope，与其他数据隔离

    var userId: String
        get() = store.decodeString("user_id", "") ?: ""
        set(value) { store.encode("user_id", value) }

    var isLoggedIn: Boolean
        get() = store.decodeBool("is_logged_in", false)
        set(value) { store.encode("is_logged_in", value) }

    fun clear() = store.clearAll()
}
```

---

## 构建与发布

### 本地构建

```bash
# 编译 Debug 包
./gradlew assembleDebug

# 编译 Release 包（需配置签名）
./gradlew assembleRelease

# 运行单元测试
./gradlew test

# 运行所有检查（test + lint）
./gradlew check

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

在 `app/build.gradle.kts` 中读取签名配置：

```kotlin
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias = keystoreProps["keyAlias"] as String
            keyPassword = keystoreProps["keyPassword"] as String
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

### GitHub Actions

项目包含两个自动化工作流：

| 工作流 | 触发方式 | 功能 |
|--------|---------|------|
| `ci.yml` | 推送 `v*` Tag，或向 `main`/`develop` 发起 PR | 编译检查 + 单元测试 + Lint |
| `release.yml` | 推送 `v*` Tag（如 `v1.0.0`） | 构建签名 APK + 创建 GitHub Release |

**配置 Release 签名（Repository Secrets）：**

在 GitHub → Settings → Secrets → Actions 中添加：

| Secret 名称 | 说明 |
|------------|------|
| `KEYSTORE_BASE64` | `base64 release.jks` 的输出内容 |
| `KEYSTORE_PASSWORD` | KeyStore 密码 |
| `KEY_ALIAS` | Key 别名 |
| `KEY_PASSWORD` | Key 密码 |

生成 Base64：
```bash
base64 -i release.jks | pbcopy   # macOS，结果已复制到剪贴板

