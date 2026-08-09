# PixelFlow

SD 卡照片智能导入工具。插入存储卡，快速预览、筛选、导入，全程本地处理。

基于 Tauri 2 / React 19 / Rust 构建，Windows 平台。

## 功能

- **设备识别**：自动检测 SD 卡与移动存储设备，支持安全弹出
- **快速浏览**：文件夹结构即时加载，照片计数后台统计，千张照片流畅滚动
- **RAW 支持**：Sony / Canon / Nikon / Adobe DNG 等主流格式，DNG 内置专用解码器
- **照片筛选**：模糊 / 过曝 / 欠曝 / 连拍重复检测，星级评分，键盘流操作
- **智能导入**：自定义命名规则（日期 / 相机 / 序号），MD5 校验，增量导入
- **图片查看**：双击打开原图，渐进加载，缩放平移旋转，查看器中直接评分

## 安装

从 [Releases](https://github.com/XUTENGXIANG/PixelFlow/releases) 下载：

- `PixelFlow_x64-setup.exe` — NSIS 安装包
- `PixelFlow_x64_en-US.msi` — MSI 安装包

## 快捷键

| 按键 | 功能 |
|------|------|
| J | 保留（3 星） |
| X | 废弃（0 星） |
| 1–5 | 星级评分 |
| Ctrl+点击 / Shift+点击 | 多选 / 范围选择 |

查看器内：← → 切换照片，R 旋转，0 重置视图。

## 开发

环境要求：Node.js 18+、Rust stable、MSVC Build Tools。

```bash
npm install
npx tauri dev        # 开发模式
npx tauri build      # 构建安装包
```

## 技术栈

- Tauri 2 + React 19 + TypeScript
- Tailwind CSS 4 + shadcn/ui
- rawler / WIC / tinydng（RAW 解码）
- imgfprint（感知哈希）

## 许可

MIT
