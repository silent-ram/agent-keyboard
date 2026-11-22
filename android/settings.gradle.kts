pluginManagement {
    includeBuild("../third_party/fcitx5-android/build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // M2:requery sqlite-android(SupportFactory + SQLite 自定义扩展,挂 sqlite-vec vec0)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AgentKeyboard"
include(":codegen")
project(":codegen").projectDir =
    file("../third_party/fcitx5-android/codegen")
include(":lib")
project(":lib").projectDir =
    file("../third_party/fcitx5-android/lib")
include(":lib:common")
project(":lib:common").projectDir =
    file("../third_party/fcitx5-android/lib/common")
include(":lib:fcitx5")
project(":lib:fcitx5").projectDir =
    file("../third_party/fcitx5-android/lib/fcitx5")
include(":lib:fcitx5-lua")
project(":lib:fcitx5-lua").projectDir =
    file("../third_party/fcitx5-android/lib/fcitx5-lua")
include(":lib:libime")
project(":lib:libime").projectDir =
    file("../third_party/fcitx5-android/lib/libime")
include(":lib:fcitx5-chinese-addons")
project(":lib:fcitx5-chinese-addons").projectDir =
    file("../third_party/fcitx5-android/lib/fcitx5-chinese-addons")
include(":lib:plugin-base")
project(":lib:plugin-base").projectDir =
    file("../third_party/fcitx5-android/lib/plugin-base")
include(":app")
