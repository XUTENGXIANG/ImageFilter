<p align="center">
  <img src="src-tauri/icons/128x128.png" alt="ImageFilter" width="96">
</p>

<h1 align="center">ImageFilter</h1>

<p align="center">
  A RAW culling &amp; import tool — fast preview, cull, and archive. Everything stays local.
</p>

<p align="center">
  <a href="https://github.com/XUTENGXIANG/ImageFilter/releases"><img src="https://img.shields.io/badge/release-v1.0-1f883d" alt="Release"></a>
  <a href="#"><img src="https://img.shields.io/badge/platform-Windows%2010%2F11%20%7C%20macOS-lightgrey" alt="Platform"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="License"></a>
  <a href="https://github.com/XUTENGXIANG/ImageFilter/releases/download/v1.0/ImageFilter_1.0.0_x64-setup.exe"><img src="https://img.shields.io/badge/download-7.5MB-green" alt="Download"></a>
</p>

<p align="center">
  <a href="https://tensyn.online/#download"><img src="https://img.shields.io/badge/Website-tensyn.online-8b5cf6" alt="Website"></a>
  &nbsp;·&nbsp;
  <a href="README.md"><img src="https://img.shields.io/badge/%E4%B8%AD%E6%96%87-%E4%B8%AD%E6%96%87%E7%89%88-3b82f6" alt="中文"></a>
</p>

---

## Overview

A complete RAW first-pass culling workflow for photographers — insert the card, preview, rate, and archive.

- Grid thumbnail preview with instant browsing; LrC-style star ratings for the first pass
- Native support for mainstream RAW formats, with a dedicated built-in DNG decoder
- Automatic blur / overexposure / burst-duplicate detection to assist rating
- Template-based naming and auto-archiving; MD5 verification keeps data intact
- Everything is processed locally — photos never leave your machine

---

> **Development status**
> - Still in active development — ideas and feedback are welcome!
> - macOS builds may have known issues due to a lack of build environment; please file an issue if you hit one.
> - Thanks to all contributors!

---

## Screenshots

![ImageFilter UI](assets/screenshot.png)

Double-click a photo to open the fullscreen viewer: scroll to zoom, drag to pan, rotate, and rate directly from the viewer. The UI supports dark / light themes, Chinese / English switching, and the Windows Mica glass background.

## Installation

Download the installer for your platform from [Releases](https://github.com/XUTENGXIANG/ImageFilter/releases):

| File | Description |
|------|-------------|
| `ImageFilter_x64-setup.exe` | NSIS installer (recommended) |
| `ImageFilter_x64_zh-CN.msi` | MSI installer |

Double-click to install. If downloads are slow, use the [official website](https://tensyn.online/#download) or the links on [Releases](https://github.com/XUTENGXIANG/ImageFilter/releases). macOS builds are available on [Releases](https://github.com/XUTENGXIANG/ImageFilter/releases).

## Quick Start

1. Connect an SD card or camera — the device is detected automatically in the left sidebar
2. Click the device, browse folders, and open the target folder to view photos
3. Single-click to select a photo (click again to deselect); `Shift`-click for range selection
4. Choose a destination folder at the bottom and click "Import"

### Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `J` | Keep (3 stars) |
| `X` | Reject (0 stars) |
| `1`–`5` | Star rating |
| `←` / `→` | Previous / next photo in viewer |
| `R` / `Shift+R` | Rotate in viewer |
| `0` | Reset view in viewer |

### Naming Rules

Organize files automatically on import with templates:

- `{date}` — date taken (2026-08-10)
- `{camera}` — camera model (Sony_A7M4)
- `{seq}` — sequence number (0001)
- `{original}` — original file name

Example: `{date}/{camera}/{seq}.{ext}` produces `2026-08-10/Sony_A7M4/0001.ARW`

## Tech Stack

| Layer | Technology |
|-------|------------|
| Framework | Tauri 2, React 19, TypeScript |
| UI | Tailwind CSS 4, shadcn/ui, IconPark |
| RAW decoding | rawler, WIC, tinydng, zune-jpeg |
| Thumbnails | Windows Shell (IShellItemImageFactory) |
| Image analysis | imgfprint, pure algorithms (Laplacian / histogram) |
| Storage | SQLite |

## Development

Requirements: Node.js 18+, stable Rust, MSVC Build Tools.

```bash
# Clone the repository
git clone https://github.com/XUTENGXIANG/ImageFilter.git
cd ImageFilter

# Install dependencies
npm install

# Development mode (hot reload)
npx tauri dev

# Build installers
npx tauri build
```

Build output goes to `src-tauri/target/release/bundle/`.

### Dev-mode notes (for developers)

`npx tauri dev` does three things in order:

1. Runs `beforeDevCommand` (`npm run dev`) to start the Vite dev server on port **1420**
2. Compiles the Rust backend (`src-tauri/`; 2–5 min on first run, incremental afterwards)
3. Launches `image-filter.exe` with the app window

Window config (`tauri.conf.json`): 1200×800, min 900×600, **borderless** (the title bar is a React component; drag via `data-tauri-drag-region`).

**Port 1420 occupied (common gotcha)**: stale vite processes survive window close and cause `beforeDevCommand terminated with non-zero status`.

```bash
netstat -ano | findstr ":1420" | findstr LISTEN   # find the PID
taskkill /PID <PID> /F                            # kill it, then restart
npx tauri dev
```

**Running in the background** (when the terminal is blocked):

```bash
npx tauri dev 2>&1 | tee dev.log
tasklist | findstr image-filter    # confirm the window process is up
```

**Hot reload**: frontend changes apply instantly via Vite HMR; Rust changes trigger an incremental rebuild and window restart. Before touching `viewer.tsx`'s loading state machine, read section 5 of `HANDOFF.md` (every branch exists to fix a visual bug — don't "simplify" them).

**Versioning**: bump the version in `package.json`, `tauri.conf.json` and `src-tauri/Cargo.toml` together; mark a pending release with `git tag vX.Y.Z` and push the tag. Installers are produced by the GitHub Actions workflow.

## Project Structure

```
src/                  # React frontend
  App.tsx             # Main UI (three-pane layout + floating toolbars)
  useScanner.ts       # State management and business logic
  viewer.tsx          # Image viewer (progressive loading)
  i18n/               # Chinese / English translations
  components/         # Floating panels / toolbars / photo cards
src-tauri/            # Rust backend
  src/
    scanner/          # Devices / browsing / EXIF / image decoding
    analyzer.rs       # Blur / exposure / duplicate detection
    importer.rs       # Import engine
    win_wic.rs        # Windows WIC decoding
    tinydng.rs        # DNG decoding (FFI)
  third_party/tinydng # DNG decoder C++ sources
```
