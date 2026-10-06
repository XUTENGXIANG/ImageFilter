// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import "./i18n"; // 初始化 i18n (必须在组件渲染前)
import "./index.css";
import { loadOsCapabilities } from "./os-capability";

// 先探测系统能力、再渲染。
//
// 为什么必须 await 在 render 之前: useState 的初始化函数是**同步**的，而 invoke 是异步的。
// 若先用「支持 Mica」渲染、再异步纠正，Win10 全新安装上会出现一帧「玻璃开着」然后又关掉的
// 闪烁。本地 IPC 在毫秒级，这点等待可以接受；探测有 500ms 超时兜底（见 os-capability.ts），
// 超时也只会退回「保持今天的行为」，不会阻塞窗口出现。
//
// 用 async IIFE 而不是顶层 await: 顶层 await 要求构建目标的 module 语义是 es2022+，
// 而本项目的 tsconfig target 是 ES2021 —— 换个写法少一个构建目标的坑。
void (async () => {
  const osCapabilities = await loadOsCapabilities();
  ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
    <React.StrictMode>
      <App osCapabilities={osCapabilities} />
    </React.StrictMode>,
  );
})();
