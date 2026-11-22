import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.Sync

plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "org.fcitx.fcitx5.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.agentkeyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // M2:真实 embedding 模型冒烟(仪器测试)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                targets(
                    "native-lib",
                    "agentime",
                    "copy-fcitx5-modules",
                    "androidfrontend",
                    "androidkeyboard",
                    "androidnotification",
                )
                arguments(
                    "-DVERSION_NAME=0.1.3",
                    "-DPREBUILT_DIR=${rootDir.parentFile.resolve("third_party/fcitx5-android/lib/fcitx5/src/main/cpp/prebuilt").absolutePath}",
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            resValue("mipmap", "app_icon", "@mipmap/agent_keyboard_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/agent_keyboard_launcher")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            resValue("mipmap", "app_icon", "@mipmap/agent_keyboard_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/agent_keyboard_launcher")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = true
        resValues = true
    }
    val fcitxApp = rootDir.parentFile.resolve("third_party/fcitx5-android/app/src/main")
    sourceSets.getByName("main") {
        kotlin.directories.add(fcitxApp.resolve("java").absolutePath)
        res.srcDir(layout.buildDirectory.dir("generated/fcitx/res").get().asFile)
        assets.srcDir(layout.buildDirectory.dir("generated/fcitx/assets").get().asFile)
    }
    sourceSets.getByName("androidTest") {
        res.srcDir(layout.buildDirectory.dir("generated/fcitx/androidTestRes").get().asFile)
    }
    testOptions {
        unitTests {
            // Robolectric(Room 真实写路径测试)需要资源与清单
            isIncludeAndroidResources = true
            // RAG 管线含 android.util.Log 埋点;JVM 单测下返回默认值而非抛异常
            isReturnDefaultValues = true
        }
    }

    externalNativeBuild {
        cmake {
            path = rootDir.parentFile.resolve(
                "third_party/fcitx5-android/app/src/main/cpp/CMakeLists.txt",
            )
            version = "3.31.6"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

val syncFcitxResources by tasks.registering(Sync::class) {
    from(fileTree(rootDir.parentFile.resolve("third_party/fcitx5-android/app/src/main/res")) {
        exclude(
            "resources.properties",
            "drawable/ic_launcher*.xml",
            "mipmap*/ic_launcher*",
            "xml/data_extraction_rules.xml",
            "xml/full_backup_content.xml",
        )
    })
    into(layout.buildDirectory.dir("generated/fcitx/res"))
}

val syncFcitxAssets by tasks.registering(Sync::class) {
    from(rootDir.parentFile.resolve("third_party/fcitx5-android/app/src/main/assets"))
    from(rootDir.parentFile.resolve("third_party/fcitx5-android/LICENSE")) {
        rename { "LICENSE-Fcitx5-Android.txt" }
    }
    from(rootDir.parentFile.resolve("docs/privacy.md"))
    into(layout.buildDirectory.dir("generated/fcitx/assets"))
}

val syncFcitxTestMipmaps by tasks.registering(Sync::class) {
    val fcitxApp = rootDir.parentFile.resolve("third_party/fcitx5-android/app/src/main")
    from(fileTree(fcitxApp.resolve("res")) {
        include("mipmap*/**", "drawable/ic_launcher*")
    })
    into(layout.buildDirectory.dir("generated/fcitx/androidTestRes"))
}

ksp {
    arg(
        "room.schemaLocation",
        rootDir.parentFile.resolve("third_party/fcitx5-android/app/schemas").absolutePath,
    )
}

tasks.named("preBuild").configure {
    dependsOn(syncFcitxResources, syncFcitxAssets, syncFcitxTestMipmaps)
}

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn("generateBuildMetadataDebug")
}

fcitxComponent {
    includeLibs = listOf("fcitx5", "fcitx5-lua", "libime", "fcitx5-chinese-addons")
    installPrebuiltAssets = true
    excludeFiles = listOf("cangjie", "erbi", "qxm", "wanfeng").map {
        "usr/share/fcitx5/inputmethod/$it.conf"
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    listOf(
        "PINYIN_BENCH_DICTIONARY" to "pinyinBenchmarkDictionary",
        "PINYIN_BENCH_COMPARE_DICTIONARY" to "pinyinBenchmarkCompareDictionary",
    ).forEach { (environmentName, inputName) ->
        val dictionaryPath = providers.environmentVariable(environmentName).orElse("")
        inputs.property(inputName, dictionaryPath)
        dictionaryPath.orNull
            ?.takeIf(String::isNotBlank)
            ?.let { inputs.file(it) }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

tasks.register("verifyReleasePinyinDictionaryPackaging") {
    dependsOn("assembleRelease")
    doLast {
        val releaseDirectory = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val releaseApks = releaseDirectory.listFiles { file ->
            file.isFile && file.extension.equals("apk", ignoreCase = true)
        }.orEmpty()
        check(releaseApks.isNotEmpty()) { "No Release APKs were produced in $releaseDirectory" }
        releaseApks.forEach { releaseApk ->
            ZipFile(releaseApk).use { apk ->
                check(apk.getEntry("assets/pinyin_dict.txt") != null) {
                    "Production pinyin dictionary is missing from $releaseApk"
                }
                check(apk.getEntry("assets/THIRD_PARTY_DATA_NOTICE.txt") != null) {
                    "Third-party data notice is missing from $releaseApk"
                }
                check(apk.getEntry("assets/pinyin-dictionary-ab-baseline.tsv") == null) {
                    "Test-only historical dictionary leaked into $releaseApk"
                }
            }
        }
    }
}

dependencies {
    ksp(project(":codegen"))

    implementation(project(":lib:common"))
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
    implementation(project(":lib:libime"))
    implementation(project(":lib:fcitx5-chinese-addons"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.8")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.8")
    implementation("androidx.paging:paging-runtime-ktx:3.5.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.room:room-paging:2.8.4")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("io.arrow-kt:arrow-core:2.2.3")
    implementation("io.arrow-kt:arrow-functions:2.2.3")
    implementation("com.vanniktech:android-image-cropper:4.7.0")
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("org.mechdancer:dependency:0.1.2")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.louiscad.splitties:splitties-bitflags:3.0.0")
    implementation("com.louiscad.splitties:splitties-dimensions:3.0.0")
    implementation("com.louiscad.splitties:splitties-resources:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-appcompat:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-constraintlayout:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-coordinatorlayout:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-dsl-recyclerview:3.0.0")
    implementation("com.louiscad.splitties:splitties-views-recyclerview:3.0.0")
    implementation("com.mikepenz:aboutlibraries-core:14.2.1")
    ksp("androidx.room:room-compiler:2.8.4")

    // Compose(为 M0-T2 键盘 UI 预置,版本由 BOM 统一管理)
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.commonmark:commonmark:0.24.0")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

    // M0-T3:配置存储 + LLM 调用
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.openai:openai-java:4.101.0")
    // M1 批次 3:模型配置 JSON 存储(10 号设计 §3:DataStore + kotlinx.serialization)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // M1-T1:Room 会话/授权/活动日志(与 DataStore 共存:DataStore 管模型配置,Room 管业务表)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // 本地知识库 RAG:
    // - requery sqlite-android:Room openHelperFactory,支持经 SQLiteCustomExtension 加载 sqlite-vec vec0 扩展
    //   (framework SQLite 禁用 load_extension,必须换捆绑 SQLite;vec0.so 以 assets 分发,运行时复制到 filesDir)
    implementation("com.github.requery:sqlite-android:3.49.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // 单测环境提供 org.json 实现(Android 平台类在 JVM 单测不可用)
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // P0-2:Room 真实写路径单测(Robolectric 内存库)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    // M2:真机/模拟器仪器测试(embedding 冒烟:向量非零且确定性)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
