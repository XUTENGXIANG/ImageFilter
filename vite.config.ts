import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "path";

// https://v2.tauri.app/start/frontend/vite/
const host = process.env.TAURI_DEV_HOST;

export default defineConfig(async () => ({
  plugins: [react(), tailwindcss()],
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
