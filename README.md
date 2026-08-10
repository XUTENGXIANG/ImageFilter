<p align="center">
  <img src="src-tauri/icons/128x128.png" alt="PixelFlow" width="96">
</p>

<h1 align="center">PixelFlow</h1>

<p align="center">
  SD 卡照片智能导入工具 — 快速预览、筛选、导入，全程本地处理
</p>

<p align="center">
  <a href="https://github.com/XUTENGXIANG/PixelFlow/releases"><img src="https://img.shields.io/badge/release-v1.0--beta-1f883d" alt="Release"></a>
  <a href="#"><img src="https://img.shields.io/badge/platform-Windows%2010%2F11-lightgrey" alt="Platform"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="License"></a>
  <a href="https://github.com/XUTENGXIANG/PixelFlow/releases/download/v1.0-beta/PixelFlow_0.1.0_x64-setup.exe"><img src="https://img.shields.io/badge/download-7.5MB-green" alt="Download"></a>
</p>

---

## 简介

摄影师的痛点是每次从 SD 卡倒照片都要经历漫长的等待和繁琐的手动整理。PixelFlow 把整个流程压缩为三步：**插入存储卡 → 快速筛选 → 一键导入**。

- 文件夹结构即时加载，数千张照片流畅浏览
- 主流 RAW 格式原生支持，DNG 内置专用解码器
- 模糊 / 过曝 / 连拍重复自动检测，星级评分辅助筛选
- 命名规则自动归档，MD5 校验保证数据完整
- 所有处理在本地完成，照片不会上传到任何服务器

## 界面

```
┌─────────────┬─────────────────────────────┬─────────────┐
│  设备        │                             │  照片信息    │
│  ├ U 盘 (D:)│   照片网格（缩略图+角标）     │  EXIF 详情  │
│  ├ 系统 (C:)│                             │  相机参数    │
│  └ 磁盘 (F:)│                             │  日期尺寸    │
│             │                             │             │
│  文件夹      │  [全选] [排序] [星级筛选]     │             │
│  └ 根目录    │                             │             │
└─────────────┴─────────────────────────────┴─────────────┘
```

双击照片打开全屏查看器：滚轮缩放、拖动平移、旋转，查看器中直接评分。

## 安装

从 [Releases](https://github.com/XUTENGXIANG/PixelFlow/releases) 页面下载对应安装包：

| 文件 | 说明 |
|------|------|
| `PixelFlow_x64-setup.exe` | NSIS 安装包，推荐 |
| `PixelFlow_x64_en-US.msi` | MSI 安装包 |

双击运行，按提示完成安装。无需额外运行时（WebView2 系统自带）。

## 快速上手

1. 将 SD 卡或相机连接到电脑，左侧自动识别设备
2. 点击设备，浏览文件夹，点进目标文件夹查看照片
3. 单击选中照片，`Ctrl` 加选 / `Shift` 连选
4. 底部选择导入目标文件夹，点击"导入"

### 快捷键

| 按键 | 功能 |
|------|------|
| `J` | 保留（3 星） |
| `X` | 废弃（0 星） |
| `1`–`5` | 星级评分 |
| `←` / `→` | 查看器切换照片 |
| `R` | 查看器旋转 |
| `0` | 查看器重置视图 |

### 命名规则

导入时可按模板自动组织文件：

- `{date}` — 拍摄日期（2026-08-10）
- `{camera}` — 相机型号（Sony_A7M4）
- `{seq}` — 序号（0001）
- `{original}` — 原文件名

示例：`{date}/{camera}/{seq}.{ext}` 生成 `2026-08-10/Sony_A7M4/0001.ARW`

## 技术栈

| 层 | 技术 |
|----|------|
| 框架 | Tauri 2, React 19, TypeScript |
| UI | Tailwind CSS 4, shadcn/ui, IconPark |
| RAW 解码 | rawler, WIC, tinydng, zune-jpeg |
| 图像分析 | imgfprint, 纯算法（拉普拉斯/直方图） |
| 存储 | SQLite |

## 开发

环境要求：Node.js 18+、Rust stable、MSVC Build Tools。

```bash
# 克隆仓库
git clone https://github.com/XUTENGXIANG/PixelFlow.git
cd PixelFlow

# 安装依赖
npm install

# 开发模式（热重载）
npx tauri dev

# 构建安装包
npx tauri build
```

构建产物输出到 `src-tauri/target/release/bundle/`。

## 项目结构

```
src/                  # React 前端
  App.tsx             # 主界面（三栏布局）
  useScanner.ts       # 状态管理与业务逻辑
  viewer.tsx          # 图片查看器
  contextmenu.tsx     # 右键菜单
  panel.tsx           # 浮窗面板
src-tauri/            # Rust 后端
  src/
    scanner.rs        # 扫描/EXIF/缩略图/弹出设备
    analyzer.rs       # 模糊/曝光/重复检测
    importer.rs       # 导入引擎
    win_wic.rs        # Windows WIC 解码
    tinydng.rs        # DNG 解码（FFI）
  third_party/tinydng # DNG 解码器 C++ 源码
```

## 许可

[MIT](LICENSE)
