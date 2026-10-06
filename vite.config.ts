// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "path";
import { readFileSync } from "node:fs";

// 检查更新那一行要显示版本号。真机走 Tauri 的 getVersion()(读的是二进制自己的版本),
// 这里注入的是 package.json 的版本, 只作为浏览器(纯 vite / 探针)下的兜底 —— 见 src/updater.ts。
const pkg = JSON.parse(
  readFileSync(new URL("./package.json", import.meta.url), "utf8"),
) as { version: string };

// https://v2.tauri.app/start/frontend/vite/
const host = process.env.TAURI_DEV_HOST;

export default defineConfig(async () => ({
  plugins: [react(), tailwindcss()],
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  resolve: { alias: { "@": path.resolve(__dirname, "./src") } },
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    hmr: host
      ? { protocol: "ws", host, port: 1421 }
      : undefined,
    // 忽略 src-tauri(由 tauri CLI 自己监听), 以及"原子写入"产生的临时文件/目录:
    // 某些编辑器/工具保存文件时会先写 .xxx.<pid>.<uuid>.tmpdir/xxx.tmp 再改名,
    // Vite 的 watcher 一旦去 watch 这种正被占用的临时文件会抛未捕获的 EBUSY,
    // 直接终止 dev 进程(表现为 beforeDevCommand terminated with a non-zero status code)。
    watch: { ignored: ["**/src-tauri/**", "**/.*.tmpdir/**", "**/*.tmp"] },
  },
}));
