// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
pluginManagement {
    repositories {
        // 国内镜像优先: dl.google.com / repo1.maven.org 在境内不稳定, 会把构建卡在下载上。
        // 顺序刻意如此 —— 镜像命中就完全不碰境外源; 缺包时自动回落到官方源。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "ImageFilterMobile"
include(":app")
