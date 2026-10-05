// 四个原生下拉换成自定义下拉后的取证。
//
// 判据:
//   1. 页面上不能再有原生 <select>
//   2. 每个下拉都能开; 弹层是圆角(10px) —— 这是"二级菜单要圆角"的落点
//   3. 弹层有入场过渡(不透明度从 0 涨上来, 不是直接以终态出现)
//   4. 真的能选中(选完触发器上的文字要变)
//   5. 触发器是可访问名称齐全的 combobox(原生 select 那四个以前是**没有名字**的)
//
// 跑法: node verify-dropdown.mjs
//   先决条件: npm run tauri dev 在跑(或任意一个能被 Vite 服务的实例)
//   APP_URL 覆盖地址(默认 http://localhost:1420); PW_BASE / CHROME 覆盖 playwright 与浏览器路径
// 退出码: 0 = 通过, 1 = 失败
import { createRequire } from "node:module";
const require = createRequire(process.env.PW_BASE || "file:///C:/Users/11/.dsh/profiles/desktop/");
const { chromium } = require("playwright-core");

const EXE = process.env.CHROME || "C:\\Users\\11\\AppData\\Local\\ms-playwright\\chromium-1234\\chrome-win64\\chrome.exe";
const BASE = process.env.APP_URL || "http://localhost:1420";

let failed = 0, passed = 0;
function check(ok, label, detail) {
  if (ok) { passed++; console.log(`  ok   ${label}`); }
  else { failed++; console.log(`  FAIL ${label}${detail ? "  " + detail : ""}`); }
}

const browser = await chromium.launch({ executablePath: EXE });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
const errs = [];
page.on("pageerror", (e) => errs.push(String(e)));
page.on("console", (m) => { if (m.type() === "error" || m.type() === "warning") errs.push(`[${m.type()}] ${m.text()}`); });
await page.goto(BASE + "/.design-audit/harness.html?photos=1&count=12", { waitUntil: "domcontentloaded" });
await page.waitForSelector("#root > *");
await page.waitForTimeout(2500);

check(await page.evaluate(() => document.querySelectorAll("select").length) === 0,
  "页面上没有原生 <select> 了");

/** 弹层的识别: **必须是打开的那个**(带 data-open) + 圆角 10px + min-width 160px(menu-styles 的 MENU_CONTENT) 且含 option。
 *  为什么必须限定 data-open: base-ui 关闭后**不卸载**弹层, 直接 find 会抓到上一个已关闭的旧弹层,
 *  于是采样到的是它(早就 opacity:1)、"已关闭"也会误判成未关闭。 */
const POPUP_FINDER = `[...document.querySelectorAll('div[data-open]')].find((d) => {
  const cs = getComputedStyle(d);
  return cs.borderRadius === '10px' && cs.minWidth === '160px' && d.querySelector('[role="option"]');
})`;

async function openContainer(btnText) {
  await page.evaluate((t) => {
    const btns = [...document.querySelectorAll("button")];
    const b = btns.find((x) => x.textContent.trim() === t) || btns.find((x) => x.textContent.includes(t));
    if (b) b.click();
  }, btnText);
  await page.waitForTimeout(500);
}

async function testDropdown(name, ariaLabel, optionToPick) {
  console.log(`\n=== ${name} ===`);
  const info = await page.evaluate(({ label, finder }) => {
    const t = document.querySelector(`[aria-label="${label}"]`);
    if (!t) return { err: "找不到触发器" };
    return { role: t.getAttribute("role"), tag: t.tagName, text: t.textContent.trim() };
  }, { label: ariaLabel, finder: POPUP_FINDER });
  if (info.err) { check(false, `找到「${name}」`, info.err); return; }
  check(info.role === "combobox", "触发器是 combobox 语义", JSON.stringify(info));
  console.log(`  触发器: <${info.tag.toLowerCase()} role="${info.role}"> 文字 "${info.text}"`);

  // 展开 + 采样入场轨迹
  const traj = await page.evaluate(({ label, finder }) => new Promise((resolve) => {
    const t = document.querySelector(`[aria-label="${label}"]`);
    const t0 = performance.now();
    const out = [];
    t.click();
    const tick = () => {
      const pop = eval(finder);
      if (pop) out.push([Math.round(performance.now() - t0), +getComputedStyle(pop).opacity, getComputedStyle(pop).borderRadius]);
      if (performance.now() - t0 > 700) { resolve(out); return; }
      requestAnimationFrame(tick);
    };
    tick();
  }), { label: ariaLabel, finder: POPUP_FINDER });

  const seen = traj.filter(([, o]) => o < 0.99);
  const radius = traj.length ? traj[traj.length - 1][2] : null;
  console.log(`  弹层: 采样 ${traj.length} 帧, 未达终态 ${seen.length} 帧, 圆角 ${radius}`);
  if (seen.length) console.log("    头几帧: " + seen.slice(0, 5).map(([t, o]) => `${t}ms:${o}`).join("  "));
  check(radius === "10px", "弹层是圆角 10px", String(radius));
  check(seen.length > 1, "弹层有入场过渡(不是瞬现)", `未达终态 ${seen.length} 帧`);

  // 真正选中一项
  const picked = await page.evaluate(({ label, want }) => {
    const items = [...document.querySelectorAll('[role="option"]')];
    const it = items.find((i) => i.textContent.trim() === want);
    if (!it) return { err: "找不到选项 " + want + " 候选: " + items.map((i) => i.textContent.trim()).join("/") };
    it.click();
    return { ok: true };
  }, { label: ariaLabel, want: optionToPick });
  await page.waitForTimeout(400);
  if (picked.err) { check(false, `选中「${optionToPick}」`, picked.err); return; }
  const after = await page.evaluate((label) => {
    const t = document.querySelector(`[aria-label="${label}"]`);
    return { text: t.textContent.trim(), expanded: t.getAttribute("aria-expanded") };
  }, ariaLabel);
  console.log(`  选中后: 触发器文字 "${after.text}"  aria-expanded=${after.expanded}`);
  check(after.text.startsWith(optionToPick), `选中「${optionToPick}」生效`, after.text);
  check(await page.evaluate((f) => !eval(f), POPUP_FINDER), "选完弹层已关闭");

  // 关闭的弹层仍留在 DOM 里(base-ui 不卸载), 所以必须确认它**不可达** ——
  // 否则那些 option 会漏进 Tab 顺序与无障碍树(与收起的子树是同一个坑)。
  const closedReachable = await page.evaluate(() => {
    const pops = [...document.querySelectorAll("div")].filter((d) => {
      const cs = getComputedStyle(d);
      return cs.borderRadius === "10px" && cs.minWidth === "160px" && d.querySelector('[role="option"]');
    });
    return pops.map((p) => ({
      open: p.hasAttribute("data-open"),
      inert: p.hasAttribute("inert") || p.closest("[inert]") !== null,
      ariaHidden: p.getAttribute("aria-hidden") || (p.closest("[aria-hidden='true']") ? "true(祖先)" : null),
      visibility: getComputedStyle(p).visibility,
      display: getComputedStyle(p).display,
      // 真的能不能被 Tab 到: 看它里面有没有可见的、非 disabled 的 option
      focusableOptions: [...p.querySelectorAll('[role="option"]')].filter((o) => {
        const r = o.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      }).length,
    }));
  });
  const reachable = closedReachable.filter((p) => !p.open && p.focusableOptions > 0 && !p.inert && p.ariaHidden === null);
  console.log(`  关闭后仍留在 DOM 的弹层 ${closedReachable.filter((p) => !p.open).length} 个`);
  check(reachable.length === 0, "关闭的弹层不可达(有尺寸的 option 数为 0 或已 inert/aria-hidden)",
    JSON.stringify(reachable));
}

await testDropdown("排序方式", "排序方式", "日期");
await testDropdown("星级筛选", "星级筛选", "≥3★");

await openContainer("筛选");
await testDropdown("分析结果(在筛选面板里)", "分析结果", "模糊");

await openContainer("高级选项");
await testDropdown("命名方案(在高级选项里)", "命名方案", "自定义");

check(errs.length === 0, "控制台无 error / warning", JSON.stringify(errs.slice(0, 3)));

// 截图按需: SHOT=<path> node verify-dropdown.mjs
if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });
console.log(`\n${failed === 0 ? "ALL PASS" : "FAILURES"}: ${passed} passed, ${failed} failed`);
await browser.close();
process.exit(failed === 0 ? 0 : 1);
