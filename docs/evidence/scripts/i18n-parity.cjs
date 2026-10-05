const fs = require("fs");
function leaves(obj, prefix, out) {
  for (const [k, v] of Object.entries(obj)) {
    const p = prefix ? prefix + "." + k : k;
    if (v && typeof v === "object" && !Array.isArray(v)) leaves(v, p, out);
    else out.push(p);
  }
  return out;
}
function load(file) {
  let t = fs.readFileSync(file, "utf8");
  // strip the TS wrapper: export default { ... };
  const i = t.indexOf("{");
  const j = t.lastIndexOf("}");
  let body = t.slice(i, j + 1);
  return eval("(" + body + ")");
}
const zh = leaves(load("src/i18n/zh.ts"), "", []).sort();
const en = leaves(load("src/i18n/en.ts"), "", []).sort();
const onlyZh = zh.filter(k => !en.includes(k));
const onlyEn = en.filter(k => !zh.includes(k));
console.log("zh leaves:", zh.length, " en leaves:", en.length);
console.log("only in zh:", onlyZh.length ? onlyZh : "(none)");
console.log("only in en:", onlyEn.length ? onlyEn : "(none)");
console.log("PARITY:", onlyZh.length === 0 && onlyEn.length === 0 ? "OK" : "MISMATCH");
