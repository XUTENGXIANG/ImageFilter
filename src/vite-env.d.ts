// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
/// <reference types="vite/client" />

/** 构建期由 vite.config.ts 的 define 注入: package.json 的 version。
 *  真机显示的是 Tauri getVersion() 的返回值, 这个只在浏览器上下文里兜底。 */
declare const __APP_VERSION__: string;
