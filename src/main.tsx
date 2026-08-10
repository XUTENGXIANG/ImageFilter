import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import "./i18n"; // 初始化 i18n (必须在组件渲染前)
import "./index.css";

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
