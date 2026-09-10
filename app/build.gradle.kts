import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.smartledger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smartledger"
        minSdk = 26
        targetSdk = 35
        // 1.1.0：AI 财务顾问 / 自然语言与语音记账 / 动态预算 / UI 重构（次版本号升级）
        // 应用内更新靠 GitHub Release 的 tag 与 versionName 比对，发版时 tag 请用 v1.1.0
        // 无障碍真机调试包用 `-debug.N` 后缀：UpdateChecker 会剥掉**远端 tag** 的 '-' 之后
        // 部分，且 isNewerVersion 把 "0-debug" 这类段解析为 0 —— 两条合起来使调试包不会被
        // 已装版本判定为「更高版本」而误报更新（43bcb4b 已修）。
        // ⚠ N 必须与已发布的 tag 错开：v1.1.0-debug.4 已指向 43bcb4b（versionCode 32），
        //   本包是它之后的第一个改动，故取 debug.5 / versionCode 33
        versionCode = 33
        versionName = "1.1.0-debug.5"
    }

    // ═══ 签名配置（从 local.properties 安全读取）═══
    signingConfigs {
        create("release") {
            val props = Properties()
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) {
                props.load(localPropsFile.inputStream())
            }
            storeFile = file(props.getProperty("KEY_STORE_PATH", "smartledger.jks"))
            storePassword = props.getProperty("KEY_STORE_PASSWORD", "")
            keyAlias = props.getProperty("KEY_ALIAS", "smartledger")
            keyPassword = props.getProperty("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // 单测里用到 SharedPreferences 等 Android API 时返回默认值而不是抛异常，
            // 避免为了跑纯逻辑测试而引入 Robolectric。
            isReturnDefaultValues = true
            isIncludeAndroidResources = false
        }
    }
}

// 导出 Room schema JSON，用于人工校验手写 Migration 与 Room 期望的表结构
// 是否逐列一致（列名 / 类型 / NOT NULL / DEFAULT / 索引名）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ═══ AI 真链路集成测试 ═══
//
// 默认**排除**：这类测试会真的请求 DeepSeek，消耗用户额度，
// 不能在每次 ./gradlew test 时静默跑掉。
// 需要时显式开启：
//   ./gradlew testDebugUnitTest -PaiIntegration=true
//
// API Key 从 local.properties 读（已在 .gitignore 中），
// **不写进任何会进版本库的文件**。缺失时测试用 Assume 自动跳过。
val aiIntegrationEnabled =
    (project.findProperty("aiIntegration") as? String)?.toBoolean() ?: false

val aiTestProps = run {
    // 注意：在 Gradle Kotlin DSL 里不能写 `java.util.Properties()` ——
    // `java` 会被解析成 project 的 JavaPluginExtension 而不是包名。
    // 文件顶部已经 `import java.util.Properties`，直接用类名即可。
    val p = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
    p
}

tasks.withType<Test>().configureEach {
    if (!aiIntegrationEnabled) {
        exclude("**/DeepSeekIntegrationTest.class")
    }
    systemProperty("ai.api.key", aiTestProps.getProperty("deepseek.api.key", ""))
    systemProperty("ai.base.url", aiTestProps.getProperty("deepseek.base.url", ""))
    systemProperty("ai.model", aiTestProps.getProperty("deepseek.model", ""))
    // 真链路测试比普通单测慢，适当放宽超时
    systemProperty("ai.timeout.ms", aiTestProps.getProperty("deepseek.timeout.ms", "90000"))
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ═══ 单元测试 ═══
    // 只加这两个，不引入 MockWebServer / Robolectric：
    // 被测逻辑一律设计成不依赖 Android 框架的纯函数，
    // HTTP 层通过 HttpEngine 接口注入 Fake 实现来测。
    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 是运行期抛 "Stub!" 的桩，
    // 单测需要真实实现；放在 testImplementation 会优先于 mockable android.jar。
    testImplementation("org.json:json:20240303")
}
