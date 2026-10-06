# Copyright (c) 2026 XUTENGXIANG
# SPDX-License-Identifier: MIT
#
# 这里目前**故意是空的**：`app/build.gradle.kts` 的 release 构建类型引用了这个文件，
# 而 R8 只在开混淆(`isMinifyEnabled = true`)时才读它 —— 文件不存在虽然今天不报错，
# 但一旦 M4′ 发版任务打开混淆就会变成硬失败。放一个空占位比重构构建脚本更省事。
# 真要开混淆时，把需要保留的规则写在下面（Compose/kotlinx.serialization 通常需要几条）。
