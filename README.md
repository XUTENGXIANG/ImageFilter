# PixelFlow

📸 **SD 卡照片智能导入工具** — 插卡 → 秒级预览 → AI 筛废片 → 一键导入电脑

基于 Tauri 2 + React 19 + Rust 的跨平台桌面应用，为摄影师设计。

![license](https://img.shields.io/badge/license-MIT-blue) ![platform](https://img.shields.io/badge/platform-Windows-lightgrey)

## ✨ 功能特性

### 🖼️ 照片浏览
- **自动检测设备** — 插入 SD 卡/U 盘自动识别，可移动设备可安全弹出
- **文件夹树秒出** — 两段式加载：目录结构即时显示，照片数后台统计
- **快速缩略图** — 磁盘缓存 + 流式加载，数千张照片流畅滚动
- **RAW 原生支持** — Sony/Canon/Nikon/Adobe 等主流 RAW 格式，DNG 专用解码器

### 🔍 智能筛选
- **AI 分析** — 模糊检测（拉普拉斯方差）/ 过曝欠曝（直方图）/ 重复连拍（感知哈希）
- **星级评分** — Lightroom 风格 1-5 星，快捷键 `J` 保留 / `X` 废弃
- **排序筛选** — 按文件名/类型/日期排序，按星级过滤
- **键盘流** — 全键盘操作，快速筛选大量照片

### 📥 智能导入
- **多选** — Ctrl 加选 / Shift 连选（资源管理器同款）
- **命名规则** — 按日期/相机/序号/自定义文件夹自动组织
- **MD5 校验** — 导入后验证文件完整性
- **增量导入** — 已导入自动跳过

### 🖥️ 图片查看器
- 双击打开原图，渐进加载（缩略图秒显 → 全分辨率替换）
- 滚轮缩放 / 拖动平移 / `R` 旋转
- 查看器中直接评分，星级同步网格

## 📥 安装

从 [Releases](https://github.com/XUTENGXIANG/PixelFlow/releases) 下载：

| 文件 | 说明 |
|------|------|
| `PixelFlow_x64-setup.exe` | NSIS 安装包（推荐） |
| `PixelFlow_x64_en-US.msi` | MSI 安装包 |

## ⌨️ 快捷键

| 按键 | 功能 |
|------|------|
| `J` | 保留（3 星） |
| `X` | 废弃（0 星） |
| `1`-`5` | 星级评分 |
| `Ctrl+点击` | 多选 |
| `Shift+点击` | 范围选择 |
| 查看器中 | `←→` 切换 / `R` 旋转 / `0` 重置 |

## 🛠️ 技术栈

- **框架**: [Tauri 2](https://v2.tauri.app/) + React 19 + TypeScript
- **样式**: Tailwind CSS 4 + shadcn/ui + IconPark 图标
- **解码**: rawler / WIC / tinydng（DNG）/ zune-jpeg
- **分析**: imgfprint（感知哈希）/ 纯算法图像分析
- **存储**: SQLite

## 🏗️ 开发

```bash
# 环境要求: Node.js 18+, Rust stable, MSVC Build Tools
npm install
npx tauri dev      # 开发模式
npx tauri build    # 打包安装包
```

## 📄 许可

MIT
