// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
// src/updater.ts 的纯逻辑断言 —— 检查更新那一行背后的全部判断都在这里。
//
// 跑法(项目既有约定: esbuild + Node, 不引 vitest):
//   npx esbuild docs/evidence/scripts/update-check.test.ts --bundle --platform=node --format=esm --outfile=.design-audit/_probe/update-check.mjs
//   node .design-audit/_probe/update-check.mjs
// (产物落在 gitignore 的 .design-audit/ 下, 不入库)
//
// 这里假的是 fetch 这一层, 真的那一层(WebView 的 fetch / CSP / 权限)由
// 真机验收与 verify-update-check.mjs 负责 —— 单测证明不了网络能通。
import {
  checkForUpdate,
  fetchLatestRelease,
  isNewer,
  parseVersion,
  LATEST_RELEASE_API,
  RELEASES_PAGE,
  type FetchLike,
} from "../../../src/updater";

let fail = 0;
let pass = 0;
function eq(name: string, got: unknown, want: unknown) {
  const g = JSON.stringify(got);
  const w = JSON.stringify(want);
  if (g === w) { pass++; console.log("  ok   " + name); }
  else { fail++; console.log("  FAIL " + name + "\n        got  " + g + "\n        want " + w); }
}

/** 返回一个固定响应的桩 */
function stub(status: number, body: unknown): FetchLike {
  return async () => new Response(
    typeof body === "string" ? body : JSON.stringify(body),
    { status, headers: { "content-type": "application/json" } },
  );
}

/** GitHub 正常返回 */
function release(tag: string, url = "https://github.com/x/y/releases/tag/" + tag): FetchLike {
  return stub(200, { tag_name: tag, html_url: url });
}

console.log("parseVersion:");
eq("1.1.1", parseVersion("1.1.1"), { core: [1, 1, 1], pre: "" });
eq("v1.1.1 去前缀", parseVersion("v1.1.1"), { core: [1, 1, 1], pre: "" });
eq("V1.1.1 大写前缀也认(GitHub 上两种 tag 都有)", parseVersion("V1.1.1"), { core: [1, 1, 1], pre: "" });
eq("1.1 段数不固定", parseVersion("1.1"), { core: [1, 1], pre: "" });
eq("v2", parseVersion("v2"), { core: [2], pre: "" });
eq("1.2.3-beta.1 带预发布", parseVersion("1.2.3-beta.1"), { core: [1, 2, 3], pre: "beta.1" });
eq("1.2.3+build.7 带构建元数据", parseVersion("1.2.3+build.7"), { core: [1, 2, 3], pre: "build.7" });
eq("两边空白无所谓", parseVersion(" 1.1.1 "), { core: [1, 1, 1], pre: "" });
eq("空串 → null", parseVersion(""), null);
eq("没有数字 → null", parseVersion("latest"), null);
eq("段里有非数字 → null", parseVersion("1.1.x"), null);

console.log("isNewer(最新版, 本机版):");
eq("1.1.2 > 1.1.1", isNewer("1.1.2", "1.1.1"), true);
eq("1.1.1 = 1.1.1 → 不是更新", isNewer("1.1.1", "1.1.1"), false);
// 这条是"别用字符串比大小"的护栏: "1.1.10" < "1.1.9" 按字典序是成立的
eq("1.1.10 > 1.1.9(逐段数值, 不是字典序)", isNewer("1.1.10", "1.1.9"), true);
eq("1.1.9 vs 1.1.10 → 不是更新", isNewer("1.1.9", "1.1.10"), false);
eq("v1.2.0 > 1.1.9", isNewer("v1.2.0", "1.1.9"), true);
eq("2.0.0 > 1.99.99", isNewer("2.0.0", "1.99.99"), true);
eq("1.1.0 = 1.1(缺段按 0)", isNewer("1.1.0", "1.1"), false);
eq("1.1 = 1.1.0(反向也相等)", isNewer("1.1", "1.1.0"), false);
eq("0.9.9 vs 1.0.0 → 不是更新", isNewer("0.9.9", "1.0.0"), false);
eq("1.2.0 > 1.2.0-rc1(预发布转正算更新)", isNewer("1.2.0", "1.2.0-rc1"), true);
eq("1.2.0-rc1 vs 1.2.0 → 不算(不替用户降级)", isNewer("1.2.0-rc1", "1.2.0"), false);
eq("解析不了的一律 false(最新版是垃圾)", isNewer("garbage", "1.1.1"), false);
eq("解析不了的一律 false(本机版本是空的)", isNewer("1.1.1", ""), false);

console.log("fetchLatestRelease · 正常路径:");
{
  let seen: { url: string; init?: RequestInit } | null = null;
  const spy: FetchLike = async (url, init) => {
    seen = { url, init };
    return new Response(JSON.stringify({ tag_name: "v1.1.2", html_url: "https://g/r/1.1.2" }), { status: 200 });
  };
  const got = await fetchLatestRelease(spy);
  eq("打到 /releases/latest", seen!.url, LATEST_RELEASE_API);
  eq("带 GitHub 的 media type", (seen!.init!.headers as Record<string, string>).Accept, "application/vnd.github+json");
  eq("带 AbortSignal(超时兜底)", !!seen!.init!.signal, true);
  eq("tag 去掉 v", got, { version: "1.1.2", url: "https://g/r/1.1.2" });
}
eq("缺 html_url 时兜底到发布页", await fetchLatestRelease(stub(200, { tag_name: "v1.1.2" })),
  { version: "1.1.2", url: RELEASES_PAGE });

console.log("checkForUpdate · 五态:");
eq("本机 1.1.1 / 最新 v1.1.1 → latest",
  await checkForUpdate("1.1.1", release("v1.1.1")), { kind: "latest", version: "1.1.1" });
eq("本机 1.1.0 / 最新 v1.1.2 → available(带发布页)",
  await checkForUpdate("1.1.0", release("v1.1.2")),
  { kind: "available", version: "1.1.2", url: "https://github.com/x/y/releases/tag/v1.1.2" });
eq("最新版更旧(本地包比线上新) → latest, 不报错",
  await checkForUpdate("1.2.0", release("v1.1.1")), { kind: "latest", version: "1.2.0" });

console.log("checkForUpdate · 失败分队(失败也要分得清是哪一类):");
eq("403(匿名限额/被挡) → http 403",
  await checkForUpdate("1.1.1", stub(403, { message: "rate limit" })),
  { kind: "error", detail: { code: "http", status: 403 } });
eq("404(仓库没发布过) → http 404",
  await checkForUpdate("1.1.1", stub(404, { message: "Not Found" })),
  { kind: "error", detail: { code: "http", status: 404 } });
eq("网络层失败(TypeError) → network",
  await checkForUpdate("1.1.1", async () => { throw new TypeError("Failed to fetch"); }),
  { kind: "error", detail: { code: "network" } });
eq("非 JSON 响应 → unexpected",
  await checkForUpdate("1.1.1", stub(200, "<html>502</html>")),
  { kind: "error", detail: { code: "unexpected" } });
eq("没有 tag_name → unexpected",
  await checkForUpdate("1.1.1", stub(200, { message: "weird" })),
  { kind: "error", detail: { code: "unexpected" } });
eq("tag_name 解析不出数字 → unexpected",
  await checkForUpdate("1.1.1", stub(200, { tag_name: "nightly" })),
  { kind: "error", detail: { code: "unexpected" } });
eq("本机版本读不到(空) → unexpected, 且不发请求", await (async () => {
  let called = 0;
  const spy: FetchLike = async () => { called++; return new Response("{}", { status: 200 }); };
  const st = await checkForUpdate("", spy);
  return { state: st, called };
})(), { state: { kind: "error", detail: { code: "unexpected" } }, called: 0 });

console.log("checkForUpdate · 超时(真的挂住 20ms):");
{
  const hanging: FetchLike = (_url, init) => new Promise((_res, rej) => {
    init?.signal?.addEventListener("abort", () => rej(new DOMException("aborted", "AbortError")));
  });
  eq("不响应 → timeout(而不是一直转圈)",
    await checkForUpdate("1.1.1", hanging, 20), { kind: "error", detail: { code: "timeout" } });
}

console.log("\n" + (fail === 0 ? "ALL PASS" : "FAILURES") + ": " + pass + " passed, " + fail + " failed");
process.exit(fail === 0 ? 0 : 1);
