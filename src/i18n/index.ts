// ═══════════════════════════════════════════════════════
// i18n 初始化 — 界面多语言 (zh / en)
// 语言选择持久化在 localStorage "imagefilter-lang"
// 新增翻译: 在 zh.ts / en.ts 对应位置添加 key
// ═══════════════════════════════════════════════════════
import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import zh from "./zh";
import en from "./en";

export type Lang = "zh" | "en";

const saved = (() => {
  try { return localStorage.getItem("imagefilter-lang") as Lang | null; }
  catch { return null; }
})();

i18n.use(initReactI18next).init({
  resources: {
    zh: { translation: zh },
    en: { translation: en },
  },
  lng: saved === "en" ? "en" : "zh", // 默认中文
  fallbackLng: "zh",
  // 翻译文件统一用单括号插值 {n}, 故自定义 prefix/suffix(i18next 默认为 {{}})
  interpolation: { escapeValue: false, prefix: "{", suffix: "}" }, // React 自带 XSS 保护
});

/** 切换界面语言并持久化 */
export function setLanguage(lng: Lang) {
  i18n.changeLanguage(lng);
  try { localStorage.setItem("imagefilter-lang", lng); } catch {}
}

export default i18n;
