// ═══════════════════════════════════════════════════════════════════
// Phase 5 · XMP 边车(sidecar)的读写
//
// 依赖面红线(docs「共同的前置约束」第 8 条 + 会话 ⓪ 已定):
//   · **不引入 XML 解析库, 也不引入 regex** —— regex 根本不在 [dependencies] 里,
//     "用一下"就等于新增第三方依赖。所以这里是**手写状态机 + 切片拼接**。
//   · 只增改 `<照片同目录>/<stem>.xmp`, **绝不改照片本身, 绝不删除任何文件**。
//
// 与文档 §5.1 的两处偏差(已记进 docs 会话 ④):
//   1. 原文"字符串扫描 + 正则即可"不成立(见上), 改手写扫描;
//   2. 命令由 2 个变 3 个 —— 多了 `probe_xmp_target`。§5.3 红线要求"写卡前必须确认
//      目标可写", 不单独探测就只能"先写一次试试", 那就已经碰卡了。
//
// 三条不变式(改动前先读):
// 1. **除被替换的属性值 / 新插入的属性片段外, 输出与输入逐字节相同。**
//    实现 = 收集 splice 区间 + 切片重建 + 长度等式自检 + 重新解析自检。
// 2. **写入目标路径一律由源照片路径推导**, 命令签名里没有任何"目标路径"参数;
//    且源路径必须是 SUPPORTED_EXTENSIONS 里的照片文件(不接受任意路径)。
// 3. **看不懂的文件一律不碰**: 缺少 `<x:xmpmeta>`/`<rdf:RDF>`、元素形式属性、
//    UTF-16、超过 1MB —— 全部返回错误, 一个字节都不改。这是"绝不覆盖"契约的延伸。
// ═══════════════════════════════════════════════════════════════════

use serde::{Deserialize, Serialize};
use std::io::Write as _;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

/// 边车大小上限: 超限就不碰(改 1MB 以上的边车不属于本功能范围, 宁可明确报错)
pub const XMP_SIZE_LIMIT: u64 = 1024 * 1024;
/// Adobe XMP 命名空间
const XMP_NS: &str = "http://ns.adobe.com/xap/1.0/";
/// RDF 命名空间
const RDF_NS: &str = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
/// 可写性探测用的临时文件名。扩展名不是照片格式 → 不会被 scan_directory 收进网格,
/// 崩溃残留也不会在界面上冒出来(下次探测同名覆盖)。
const PROBE_FILE: &str = ".imagefilter-xmp-probe";
/// 同一目标并发写的进程内闸门。前端本来就按 path 单飞, 这里是纵深防御。
static WRITING: OnceLock<Mutex<Vec<PathBuf>>> = OnceLock::new();

// ── 错误分类 ────────────────────────────────────────────────────────
// code 是给前端 i18n 用的闭集字符串(前端白名单校验后拼 `xmp.err.<code>`),
// detail 只用于状态行/控制台, 不再往 toast 里塞(避免长句 + 中文硬编码进 Rust)。

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum XmpError {
    WriteProtect,
    ReadOnly,
    Permission,
    DiskFull,
    Busy,
    NotXmp,
    UnsupportedForm,
    TooLarge,
    Encoding,
    PathTooLong,
    Unknown,
}

impl XmpError {
    pub fn code(self) -> &'static str {
        match self {
            XmpError::WriteProtect => "writeProtect",
            XmpError::ReadOnly => "readOnly",
            XmpError::Permission => "permission",
            XmpError::DiskFull => "diskFull",
            XmpError::Busy => "busy",
            XmpError::NotXmp => "notXmp",
            XmpError::UnsupportedForm => "unsupportedForm",
            XmpError::TooLarge => "tooLarge",
            XmpError::Encoding => "encoding",
            XmpError::PathTooLong => "pathTooLong",
            XmpError::Unknown => "unknown",
        }
    }
}

impl std::fmt::Display for XmpError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.code())
    }
}

/// 把 io 错误翻成闭集错误码。
///
/// Windows 的裸错误码是这里唯一的可靠依据(用户最关心的"写保护卡"正好是 19):
///   19 ERROR_WRITE_PROTECT / 112+39 磁盘满 / 32+33 共享冲突 / 206 名字过长 /
///   5 ERROR_ACCESS_DENIED —— 只读属性与 ACL 拒绝都是 5, 所以**再看一眼文件属性位**
///   把"只读属性"与"权限不足"分开, 两者的用户处置方式完全不同。
fn classify_io(e: &std::io::Error, path: Option<&Path>) -> XmpError {
    match e.raw_os_error() {
        Some(19) => XmpError::WriteProtect,
        Some(112) | Some(39) => XmpError::DiskFull,
        Some(32) | Some(33) => XmpError::Busy,
        Some(206) => XmpError::PathTooLong,
        Some(5) => {
            let readonly = path
                .and_then(|p| std::fs::metadata(p).ok())
                .map(|m| m.permissions().readonly())
                .unwrap_or(false);
            if readonly {
                XmpError::ReadOnly
            } else {
                XmpError::Permission
            }
        }
        _ => match e.kind() {
            std::io::ErrorKind::PermissionDenied => XmpError::Permission,
            std::io::ErrorKind::AlreadyExists => XmpError::Busy,
            _ => XmpError::Unknown,
        },
    }
}

/// 诊断用的中文细节(只进 detail, 不进 i18n 文案)
fn detail_of(e: XmpError) -> &'static str {
    match e {
        XmpError::WriteProtect => "存储卡或卷处于写保护状态",
        XmpError::ReadOnly => "文件/目录带只读属性",
        XmpError::Permission => "权限不足",
        XmpError::DiskFull => "磁盘空间不足",
        XmpError::Busy => "文件被其它程序占用",
        XmpError::NotXmp => "已存在的 .xmp 不是可识别的 XMP",
        XmpError::UnsupportedForm => "该 .xmp 使用了不支持的属性写法",
        XmpError::TooLarge => "边车超过 1MB 上限",
        XmpError::Encoding => "边车不是 UTF-8 编码",
        XmpError::PathTooLong => "路径过长",
        XmpError::Unknown => "未分类的读写失败",
    }
}

// ── 载荷类型 ────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DecisionRead {
    /// 回传源照片的绝对路径, 让前端按 path 合并(不依赖数组顺序)
    pub path: String,
    /// Some(0..=5) = 边车里明确写了星级; None = 没有边车/没有该属性/值不认识
    pub rating: Option<u8>,
    /// 已归一化为我们的小写枚举(red..purple); 其它工具写的本地化标签不猜 → None
    pub label: Option<String>,
    /// 实际读到的边车路径(状态行/调试用)
    pub sidecar: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DecisionWrite {
    /// 源照片绝对路径。**边车路径由它推导, 前端传不了目标路径**(不变式 2)
    pub path: String,
    /// None = 不动这个属性; Some(0..=5) = 写 xmp:Rating(0 即"清除星级")
    pub rating: Option<u8>,
    /// None 且 clear_label=false = 不动; Some = 写 xmp:Label
    pub label: Option<String>,
    /// true = 删除 xmp:Label 属性。**必须与"不动"区分**: 否则 Ctrl+Z 撤掉标签后
    /// 边车里的旧标签会在下次读盘时把它复活。
    #[serde(default)]
    pub clear_label: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WriteFailure {
    pub path: String,
    pub code: String,
    pub detail: String,
}

#[derive(Debug, Clone, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct WriteSummary {
    pub written: u32,
    pub skipped: u32,
    pub failed: u32,
    pub failures: Vec<WriteFailure>,
    /// true = 至少有一次写入退化成"非原子直写"(SMB/CIFS 不支持 rename 覆盖)
    pub degraded: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct XmpProbe {
    pub dir: String,
    pub writable: bool,
    pub code: Option<String>,
    pub detail: Option<String>,
    /// UNC 路径(\\server\share): 网络位置写入可能很慢
    pub network: bool,
}

// ── 极简 XML 扫描 ───────────────────────────────────────────────────
// 只做两件事: ①把每个 start tag 的属性名/值区间找出来; ②找出 rdf:Description 的
// 插入点。注释/CDATA/PI/DOCTYPE 一律跳过 —— 它们里面的 `xmp:Rating="5"` 不是属性。

#[inline]
fn is_ws(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\r' | b'\n')
}

#[derive(Debug, Clone)]
struct Attr {
    name: String,
    /// 属性名之前那段空白(含)的起点 —— 删除属性时要连它一起删
    start: usize,
    /// 值(不含引号)的区间
    value_start: usize,
    value_end: usize,
    /// 属性结束位置(含闭引号)
    end: usize,
    /// 引号字符, 0 = 没有引号(不合法 XML, 顺手修成双引号)
    quote: u8,
}

#[derive(Debug, Clone)]
struct Tag {
    name: String,
    /// 插入点: 非自闭合时是 `>` 的下标, 自闭合时是 `/` 的下标 —— 两种都在语法上合法,
    /// 所以**不需要**单独的 self_closing 标记(插在它之前都对)。
    end: usize,
    attrs: Vec<Attr>,
    /// 本标签声明了 xmlns:xmp
    declares_xmp_ns: bool,
    /// 本标签把**默认**命名空间声明成了 xmp(裸属性 `Rating=` 才算我们的)
    declares_default_xmp: bool,
}

fn parse_start_tag(text: &str, start: usize) -> Option<(Tag, usize)> {
    let b = text.as_bytes();
    let mut i = start + 1;
    let name_start = i;
    while i < b.len() && !is_ws(b[i]) && b[i] != b'>' && b[i] != b'/' {
        i += 1;
    }
    if i == name_start {
        return None;
    }
    let name = text[name_start..i].to_string();
    let mut attrs: Vec<Attr> = Vec::new();
    loop {
        let ws_start = i;
        while i < b.len() && is_ws(b[i]) {
            i += 1;
        }
        if i >= b.len() {
            return None;
        }
        if b[i] == b'>' {
            return Some((
                Tag { name, end: i, attrs, declares_xmp_ns: false, declares_default_xmp: false },
                i + 1,
            ));
        }
        if b[i] == b'/' {
            if i + 1 < b.len() && b[i + 1] == b'>' {
                return Some((
                    Tag { name, end: i, attrs, declares_xmp_ns: false, declares_default_xmp: false },
                    i + 2,
                ));
            }
            i += 1;
            continue;
        }
        // 属性名
        let an_start = i;
        while i < b.len() && !is_ws(b[i]) && b[i] != b'=' && b[i] != b'>' && b[i] != b'/' {
            i += 1;
        }
        if i == an_start {
            i += 1; // 看不懂的字节: 前进一格, 别死循环
            continue;
        }
        let aname = text[an_start..i].to_string();
        while i < b.len() && is_ws(b[i]) {
            i += 1;
        }
        let mut value_start = i;
        let mut value_end = i;
        let mut quote = 0u8;
        if i < b.len() && b[i] == b'=' {
            i += 1;
            while i < b.len() && is_ws(b[i]) {
                i += 1;
            }
            if i < b.len() && (b[i] == b'"' || b[i] == b'\'') {
                quote = b[i];
                i += 1;
                value_start = i;
                while i < b.len() && b[i] != quote {
                    i += 1;
                }
                value_end = i;
                if i < b.len() {
                    i += 1;
                }
            } else {
                // 不带引号的值(不合法 XML): 读到空白/'='/'>' 为止
                value_start = i;
                while i < b.len() && !is_ws(b[i]) && b[i] != b'>' && b[i] != b'/' {
                    i += 1;
                }
                value_end = i;
            }
        }
        attrs.push(Attr {
            start: if ws_start < an_start { ws_start } else { an_start },
            name: aname,
            value_start,
            value_end,
            end: i,
            quote,
        });
    }
}

fn parse_tags(text: &str) -> Vec<Tag> {
    let b = text.as_bytes();
    let mut tags: Vec<Tag> = Vec::new();
    let mut i = 0usize;
    while i < b.len() {
        if b[i] != b'<' {
            i += 1; // 只在 ASCII '<' 上停, 不会切进多字节字符中间
            continue;
        }
        let rest = &text[i..];
        if rest.starts_with("<!--") {
            match rest[4..].find("-->") {
                Some(k) => {
                    i = i + 4 + k + 3;
                    continue;
                }
                None => break,
            }
        }
        if rest.starts_with("<![CDATA[") {
            match rest[9..].find("]]>") {
                Some(k) => {
                    i = i + 9 + k + 3;
                    continue;
                }
                None => break,
            }
        }
        if rest.starts_with("<?") {
            match rest[2..].find("?>") {
                Some(k) => {
                    i = i + 2 + k + 2;
                    continue;
                }
                None => break,
            }
        }
        if rest.starts_with("<!") {
            match rest[2..].find('>') {
                Some(k) => {
                    i = i + 2 + k + 1;
                    continue;
                }
                None => break,
            }
        }
        if i + 1 < b.len() && b[i + 1] == b'/' {
            match rest[2..].find('>') {
                Some(k) => {
                    i = i + 2 + k + 1;
                    continue;
                }
                None => break,
            }
        }
        match parse_start_tag(text, i) {
            Some((mut tag, next)) => {
                tag.declares_default_xmp = tag
                    .attrs
                    .iter()
                    .any(|a| a.name == "xmlns" && &text[a.value_start..a.value_end] == XMP_NS);
                tag.declares_xmp_ns = tag
                    .attrs
                    .iter()
                    .any(|a| a.name == "xmlns:xmp" || tag.declares_default_xmp);
                tags.push(tag);
                i = next;
            }
            None => break,
        }
    }
    tags
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Prop {
    Rating,
    Label,
}

impl Prop {
    fn prefixed(self) -> &'static str {
        match self {
            Prop::Rating => "xmp:Rating",
            Prop::Label => "xmp:Label",
        }
    }
    fn bare(self) -> &'static str {
        match self {
            Prop::Rating => "Rating",
            Prop::Label => "Label",
        }
    }
}

/// 这个属性是不是我们这两个之一。
/// 只认 `xmp:` 前缀; 裸名**仅当同一个 start tag 把默认命名空间声明成 xmp** 时才算
/// (Lightroom 写的是带前缀的, 但手写/别家工具可能用默认 ns —— 认它, 才能"替换"
/// 而不是"再插一个同名不同前缀的属性")。
fn attr_is_prop(tag: &Tag, a: &Attr, prop: Prop) -> bool {
    a.name == prop.prefixed() || (a.name == prop.bare() && tag.declares_default_xmp)
}

fn hits<'a>(tags: &'a [Tag], prop: Prop) -> Vec<(&'a Tag, &'a Attr)> {
    let mut v = Vec::new();
    for t in tags {
        for a in &t.attrs {
            if attr_is_prop(t, a, prop) {
                v.push((t, a));
            }
        }
    }
    v
}

/// 元素形式(`<xmp:Rating>4</xmp:Rating>`)一律拒绝: 我们再插一个同名**属性**,
/// 文件里就同时存在同一 property 的两种写法, 合并结果取决于读取器 —— 宁可不碰。
fn has_element_form(tags: &[Tag], prop: Prop) -> bool {
    let default_xmp = tags.iter().any(|t| t.declares_default_xmp);
    tags.iter()
        .any(|t| t.name == prop.prefixed() || (default_xmp && t.name == prop.bare()))
}

/// 合法 XMP 的门槛(刻意宽松但足够挡住"这不是 XMP"): 必须同时有 xmpmeta 与 RDF。
fn looks_like_xmp(text: &str) -> bool {
    text.contains("<x:xmpmeta") && text.contains("<rdf:RDF")
}

/// 值 → 星级。0..=5 之外一律 None。
///
/// `-1` 是 Lightroom 的"拒绝"(rejected), **刻意不解释成 0**: 我们的模型没有这个状态,
/// 解释过来再回写就会把别人的拒绝标记抹成 0(破坏用户数据)。代价是"LR 里标了拒绝"
/// 在我们这边看不出来, 已知并记进遗留。
fn parse_rating(raw: &str) -> Option<u8> {
    let v: i64 = raw.trim().parse().ok()?;
    if (0..=5).contains(&v) {
        Some(v as u8)
    } else {
        None
    }
}

/// 值 → 标签。只认五色的英文名(大小写不敏感); Adobe 写的是 `Red` 这种首字母大写。
/// 别的工具写的**本地化**标签(如"红")不猜 —— 会把开放字符串灌进闭集枚举。
fn parse_label(raw: &str) -> Option<String> {
    let v = raw.trim().to_ascii_lowercase();
    match v.as_str() {
        "red" | "yellow" | "green" | "blue" | "purple" => Some(v),
        _ => None,
    }
}

/// 我们的小写枚举 → Adobe 写的规范写法(写出时用这个, 保证别家软件认)
fn xmp_label_of(label: &str) -> Option<&'static str> {
    match label.trim().to_ascii_lowercase().as_str() {
        "red" => Some("Red"),
        "yellow" => Some("Yellow"),
        "green" => Some("Green"),
        "blue" => Some("Blue"),
        "purple" => Some("Purple"),
        _ => None,
    }
}

/// 从 XMP 文本里读两个属性(**文档顺序第一个**, 重复属性理论上冲突, 这条写死在这里)
pub fn read_decision_from_text(text: &str) -> (Option<u8>, Option<String>) {
    let tags = parse_tags(text);
    let rating = hits(&tags, Prop::Rating)
        .first()
        .and_then(|(_, a)| parse_rating(&text[a.value_start..a.value_end]));
    let label = hits(&tags, Prop::Label)
        .first()
        .and_then(|(_, a)| parse_label(&text[a.value_start..a.value_end]));
    (rating, label)
}

// ── 写入: 只替换/插入这两个属性 ─────────────────────────────────────

struct Edit {
    start: usize,
    end: usize,
    replacement: String,
}

/// 最小合法 XMP 模板(文件不存在 / 0 字节时用)
pub fn minimal_template(rating: Option<u8>, label: Option<&str>) -> String {
    let mut attrs = String::new();
    if let Some(n) = rating {
        attrs.push_str(&format!("\n   xmp:Rating=\"{}\"", n));
    }
    if let Some(l) = label {
        if let Some(v) = xmp_label_of(l) {
            attrs.push_str(&format!("\n   xmp:Label=\"{}\"", v));
        }
    }
    format!(
        "<?xpacket begin=\"\u{feff}\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n\
         <x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"ImageFilter\">\n \
         <rdf:RDF xmlns:rdf=\"{rdf}\">\n  \
         <rdf:Description rdf:about=\"\"\n   \
         xmlns:xmp=\"{xmp}\"{attrs}/>\n \
         </rdf:RDF>\n\
         </x:xmpmeta>\n\
         <?xpacket end=\"w\"?>",
        rdf = RDF_NS,
        xmp = XMP_NS,
        attrs = attrs
    )
}

/// 计算新的文本。`existing = None` 表示文件不存在/为空 → 生成模板。
/// 返回 (新文本, 是否真的有变化)。`changed = false` 时调用方**不要写盘**。
pub fn apply_decision(
    existing: Option<&str>,
    rating: Option<u8>,
    label: Option<&str>,
    clear_label: bool,
) -> Result<(String, bool), XmpError> {
    // clear_label 优先于 label(两个都传是调用方的 bug, 取"删"这一支)
    let label_set = if clear_label { None } else { label };

    let Some(text) = existing else {
        return Ok((minimal_template(rating, label_set), true));
    };
    if !looks_like_xmp(text) {
        return Err(XmpError::NotXmp);
    }
    let tags = parse_tags(text);
    if has_element_form(&tags, Prop::Rating) || has_element_form(&tags, Prop::Label) {
        return Err(XmpError::UnsupportedForm);
    }

    let mut edits: Vec<Edit> = Vec::new();
    let mut inserts: Vec<String> = Vec::new();

    if let Some(n) = rating {
        let v = n.to_string();
        let found = hits(&tags, Prop::Rating);
        if found.is_empty() {
            inserts.push(format!("xmp:Rating=\"{}\"", v));
        } else {
            for (_, a) in found {
                // 已经双引号并且值相同 → 一个字都不动(幂等; 也是"不白摸一次卡"的关键)
                if a.quote != 0 && &text[a.value_start..a.value_end] == v.as_str() {
                    continue;
                }
                let (s, e) = if a.quote != 0 {
                    (a.value_start - 1, a.value_end + 1)
                } else {
                    (a.value_start, a.value_end)
                };
                edits.push(Edit { start: s, end: e, replacement: format!("\"{}\"", v) });
            }
        }
    }

    if clear_label {
        for (_, a) in hits(&tags, Prop::Label) {
            edits.push(Edit { start: a.start, end: a.end, replacement: String::new() });
        }
    } else if let Some(l) = label_set {
        let v = xmp_label_of(l).ok_or(XmpError::Unknown)?;
        let found = hits(&tags, Prop::Label);
        if found.is_empty() {
            inserts.push(format!("xmp:Label=\"{}\"", v));
        } else {
            for (_, a) in found {
                if a.quote != 0 && &text[a.value_start..a.value_end] == v {
                    continue;
                }
                let (s, e) = if a.quote != 0 {
                    (a.value_start - 1, a.value_end + 1)
                } else {
                    (a.value_start, a.value_end)
                };
                edits.push(Edit { start: s, end: e, replacement: format!("\"{}\"", v) });
            }
        }
    }

    if !inserts.is_empty() {
        let needs_ns = !tags.iter().any(|t| t.declares_xmp_ns);
        let mut attr_text = String::new();
        if needs_ns {
            attr_text.push_str(&format!(" xmlns:xmp=\"{}\"", XMP_NS));
        }
        for ins in &inserts {
            attr_text.push(' ');
            attr_text.push_str(ins);
        }
        if let Some(t) = tags.iter().find(|t| t.name == "rdf:Description") {
            edits.push(Edit { start: t.end, end: t.end, replacement: attr_text });
        } else if let Some(t) = tags.iter().find(|t| t.name == "rdf:RDF") {
            // 有 RDF 但没有 Description: 造一个
            edits.push(Edit {
                start: t.end,
                end: t.end,
                replacement: format!("<rdf:Description{}/>", attr_text),
            });
        } else {
            return Err(XmpError::NotXmp);
        }
    }

    if edits.is_empty() {
        return Ok((text.to_string(), false));
    }

    // 从后往前拼接: 保证前面的偏移不失效
    edits.sort_by_key(|e| e.start);
    let mut prev_end = 0usize;
    for e in &edits {
        if e.end < e.start || e.start < prev_end {
            return Err(XmpError::Unknown); // 区间重叠 = 我们算错了, 宁可不写
        }
        prev_end = e.end;
    }
    let mut out = String::with_capacity(text.len());
    let mut cursor = 0usize;
    for e in &edits {
        out.push_str(&text[cursor..e.start]);
        out.push_str(&e.replacement);
        cursor = e.end;
    }
    out.push_str(&text[cursor..]);

    // 自检 1: 长度等式 —— 证明"只动了该动的那些字节"
    let expected = text.len() as i64
        + edits
            .iter()
            .map(|e| e.replacement.len() as i64 - (e.end - e.start) as i64)
            .sum::<i64>();
    if out.len() as i64 != expected {
        return Err(XmpError::Unknown);
    }

    // 自检 2: 重新解析一遍, 确认目标真的达成了(比字符串 contains 更准 —— 兼容裸属性写法)
    let re = parse_tags(&out);
    if let Some(n) = rating {
        let v = n.to_string();
        let h = hits(&re, Prop::Rating);
        if h.is_empty() || h.iter().any(|(_, a)| out[a.value_start..a.value_end].trim() != v) {
            return Err(XmpError::Unknown);
        }
    }
    if clear_label && !hits(&re, Prop::Label).is_empty() {
        return Err(XmpError::Unknown);
    }
    if let Some(l) = label_set {
        if let Some(v) = xmp_label_of(l) {
            let h = hits(&re, Prop::Label);
            if h.is_empty() || h.iter().any(|(_, a)| out[a.value_start..a.value_end].trim() != v) {
                return Err(XmpError::Unknown);
            }
        }
    }

    Ok((out, true))
}

// ── 路径: 边车名字一律由照片路径推导 ────────────────────────────────

/// 边车候选: ①`stem.xmp`(RAW 约定, 也是我们写的那份) ②`file_name.xmp`
/// (Lightroom 对非 RAW 的写法)。与 importer.rs 的 `sidecar_source` 是同一套规则。
pub fn sidecar_candidates(photo: &Path) -> Vec<PathBuf> {
    let mut v = Vec::new();
    if let (Some(parent), Some(stem)) = (photo.parent(), photo.file_stem()) {
        let mut p = parent.to_path_buf();
        p.push(format!("{}.xmp", stem.to_string_lossy()));
        v.push(p);
    }
    if let Some(name) = photo.file_name() {
        let mut p = photo.to_path_buf();
        p.set_file_name(format!("{}.xmp", name.to_string_lossy()));
        v.push(p);
    }
    v
}

/// 读: 第一个存在的候选
pub fn existing_sidecar(photo: &Path) -> Option<PathBuf> {
    sidecar_candidates(photo).into_iter().find(|p| p.is_file())
}

/// 写: 跟随用户既有习惯 —— 已经有 `full.xmp` 就写它, 否则写 `stem.xmp`。
/// 绝不两个都写(否则别家软件只看其中一个, 会出现"两个边车各说各话")。
pub fn write_target(photo: &Path) -> Result<PathBuf, XmpError> {
    let cands = sidecar_candidates(photo);
    if cands.is_empty() {
        return Err(XmpError::PathTooLong);
    }
    if cands.len() > 1 && !cands[0].exists() && cands[1].is_file() {
        return Ok(cands[1].clone());
    }
    Ok(cands[0].clone())
}

fn validate_photo_path(photo: &Path) -> Result<(), XmpError> {
    if !photo.is_absolute() {
        return Err(XmpError::Unknown);
    }
    let ext = photo
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    if !crate::scanner::SUPPORTED_EXTENSIONS.contains(&ext.as_str()) {
        return Err(XmpError::Unknown);
    }
    if !photo.is_file() {
        return Err(XmpError::Unknown);
    }
    if photo.file_stem().map(|s| s.is_empty()).unwrap_or(true) {
        return Err(XmpError::Unknown);
    }
    Ok(())
}

/// 读取(带 1MB 上限与编码判定)。0 字节/纯空白 → None(当成"不存在")
fn read_text_limited(p: &Path) -> Result<Option<String>, XmpError> {
    let md = std::fs::metadata(p).map_err(|e| classify_io(&e, Some(p)))?;
    if md.len() > XMP_SIZE_LIMIT {
        return Err(XmpError::TooLarge);
    }
    let bytes = std::fs::read(p).map_err(|e| classify_io(&e, Some(p)))?;
    if bytes.len() >= 2
        && ((bytes[0] == 0xFF && bytes[1] == 0xFE) || (bytes[0] == 0xFE && bytes[1] == 0xFF))
    {
        return Err(XmpError::Encoding);
    }
    let text = String::from_utf8(bytes).map_err(|_| XmpError::Encoding)?;
    if text.trim().is_empty() {
        return Ok(None);
    }
    Ok(Some(text))
}

/// 原子写: 同目录临时文件 → 写入 → sync → rename 覆盖。
/// rename 失败(SMB/CIFS 不支持覆盖)时退化成直写, 并回报 `degraded = true`。
/// 崩溃只会留下一个 `.xxx.xmp.tmp`(扩展名不是照片格式 → 不会被扫描到, 下次同名覆盖)。
fn atomic_write_once(target: &Path, bytes: &[u8]) -> Result<bool, XmpError> {
    let parent = target.parent().ok_or(XmpError::PathTooLong)?;
    let name = target
        .file_name()
        .and_then(|n| n.to_str())
        .ok_or(XmpError::PathTooLong)?;
    let tmp = parent.join(format!(".{}.tmp", name));
    {
        let mut f = std::fs::File::create(&tmp).map_err(|e| classify_io(&e, Some(target)))?;
        f.write_all(bytes).map_err(|e| classify_io(&e, Some(&tmp)))?;
        f.sync_all().map_err(|e| classify_io(&e, Some(&tmp)))?;
    }
    match std::fs::rename(&tmp, target) {
        Ok(()) => Ok(false),
        Err(_) => {
            let r = std::fs::write(target, bytes).map_err(|e| {
                let _ = std::fs::remove_file(&tmp);
                classify_io(&e, Some(target))
            });
            let _ = std::fs::remove_file(&tmp);
            r.map(|_| true)
        }
    }
}

/// 共享冲突重试 3 次(100/300/900ms) —— Lightroom 正打开边车是最常见的场景。
/// 在 spawn_blocking 里 sleep, 不占 tokio worker。
fn atomic_write(target: &Path, bytes: &[u8]) -> Result<bool, XmpError> {
    let mut last = XmpError::Busy;
    for attempt in 0u32..3 {
        match atomic_write_once(target, bytes) {
            Ok(d) => return Ok(d),
            Err(XmpError::Busy) => {
                last = XmpError::Busy;
                std::thread::sleep(std::time::Duration::from_millis(100 * (1 << attempt)));
            }
            Err(e) => return Err(e),
        }
    }
    Err(last)
}

// ── 单项读写 ────────────────────────────────────────────────────────

fn read_one(photo: &Path) -> DecisionRead {
    let path = photo.to_string_lossy().to_string();
    let Some(sidecar) = existing_sidecar(photo) else {
        return DecisionRead { path, rating: None, label: None, sidecar: None };
    };
    let sidecar_str = Some(sidecar.to_string_lossy().to_string());
    // 读失败(权限/超限/编码/不是 XMP)一律当"没有值" → 前端保留本地值。
    // 读的失败不弹错: 写失败才是红线要求可见的那类。
    match read_text_limited(&sidecar) {
        Ok(Some(text)) if looks_like_xmp(&text) => {
            let (rating, label) = read_decision_from_text(&text);
            DecisionRead { path, rating, label, sidecar: sidecar_str }
        }
        _ => DecisionRead { path, rating: None, label: None, sidecar: sidecar_str },
    }
}

enum Outcome {
    Written { degraded: bool },
    Skipped,
}

fn write_one(item: &DecisionWrite) -> Result<Outcome, XmpError> {
    let photo = Path::new(&item.path);
    validate_photo_path(photo)?;
    if item.rating.is_none() && item.label.is_none() && !item.clear_label {
        return Ok(Outcome::Skipped); // 什么都没要求写
    }
    if let Some(n) = item.rating {
        if n > 5 {
            return Err(XmpError::Unknown);
        }
    }
    let target = write_target(photo)?;

    // 同一目标的进程内闸门
    let gate = WRITING.get_or_init(|| Mutex::new(Vec::new()));
    {
        let mut g = gate.lock().map_err(|_| XmpError::Unknown)?;
        if g.iter().any(|p| p == &target) {
            return Err(XmpError::Busy);
        }
        g.push(target.clone());
    }

    let result = (|| -> Result<Outcome, XmpError> {
        let existing = if target.is_file() {
            read_text_limited(&target)?
        } else {
            None
        };
        let (new_text, changed) = apply_decision(
            existing.as_deref(),
            item.rating,
            item.label.as_deref(),
            item.clear_label,
        )?;
        if !changed {
            return Ok(Outcome::Skipped);
        }
        let degraded = atomic_write(&target, new_text.as_bytes())?;
        Ok(Outcome::Written { degraded })
    })();

    if let Some(g) = WRITING.get() {
        if let Ok(mut g) = g.lock() {
            g.retain(|p| p != &target);
        }
    }
    result
}

/// 目标目录可写性探测: 建一个自己的小文件写 1 字节再删掉。
///
/// 被否决的备选"用写方式打开照片本身": 零新文件, 但漏判"目录可写而文件只读"与磁盘满,
/// 而且对几百 MB 的 RAW 拿写句柄容易触发杀软/占用告警。
/// 本探测**只能判到目录/卷一级**; 单文件只读属性会在真正写入时被抓到(readOnly)。
pub fn probe_dir(dir: &Path) -> XmpProbe {
    let dir_str = dir.to_string_lossy().to_string();
    let network = dir_str.starts_with("\\\\") || dir_str.starts_with("//");
    let mut out = XmpProbe { dir: dir_str, writable: false, code: None, detail: None, network };
    if !dir.is_dir() {
        out.code = Some(XmpError::Unknown.code().to_string());
        out.detail = Some("目录不存在或不可访问".to_string());
        return out;
    }
    let probe = dir.join(PROBE_FILE);
    let res = (|| -> Result<(), XmpError> {
        let mut f = std::fs::File::create(&probe).map_err(|e| classify_io(&e, Some(&probe)))?;
        f.write_all(b"1").map_err(|e| classify_io(&e, Some(&probe)))?;
        f.sync_all().map_err(|e| classify_io(&e, Some(&probe)))?;
        Ok(())
    })();
    let _ = std::fs::remove_file(&probe);
    match res {
        Ok(()) => out.writable = true,
        Err(e) => {
            out.code = Some(e.code().to_string());
            out.detail = Some(detail_of(e).to_string());
        }
    }
    out
}

// ── 命令 ────────────────────────────────────────────────────────────

/// 批量读。N 次 IPC 没有收益: 一次 spawn_blocking + 一份列表, 前端只做一次合并。
#[tauri::command]
pub async fn read_decisions(file_paths: Vec<String>) -> Result<Vec<DecisionRead>, String> {
    tokio::task::spawn_blocking(move || {
        file_paths
            .iter()
            .map(|p| read_one(Path::new(p)))
            .collect::<Vec<DecisionRead>>()
    })
    .await
    .map_err(|e| e.to_string())
}

/// 批量写。队列按 path 合并后提交(撤销风暴 = 一次 IPC 写多项)。
/// 单项失败**不影响其它项**, 全部收进 Summary.failures, 由前端按 code 决定
/// "整卷不可写就降级 off / 单文件问题就只标记不降级"。
#[tauri::command]
pub async fn write_decisions(items: Vec<DecisionWrite>) -> Result<WriteSummary, String> {
    tokio::task::spawn_blocking(move || {
        let mut summary = WriteSummary::default();
        for item in &items {
            match write_one(item) {
                Ok(Outcome::Written { degraded }) => {
                    summary.written += 1;
                    if degraded {
                        summary.degraded = true;
                    }
                }
                Ok(Outcome::Skipped) => summary.skipped += 1,
                Err(e) => {
                    summary.failed += 1;
                    summary.failures.push(WriteFailure {
                        path: item.path.clone(),
                        code: e.code().to_string(),
                        detail: detail_of(e).to_string(),
                    });
                }
            }
        }
        summary
    })
    .await
    .map_err(|e| e.to_string())
}

/// 写卡前必须确认目标可写(§5.3 红线)。前端只在 mode 变 on / 切到新目录时调一次。
#[tauri::command]
pub async fn probe_xmp_target(dir_path: String) -> Result<XmpProbe, String> {
    tokio::task::spawn_blocking(move || probe_dir(Path::new(&dir_path)))
        .await
        .map_err(|e| e.to_string())
}

// ── 测试 ────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn test_root(tag: &str) -> PathBuf {
        std::env::temp_dir().join(format!("imagefilter_xmp_{}_{}", tag, std::process::id()))
    }

    /// 一份"像 Lightroom 写的"边车: 关键词 + 版权 + 星级 + 标签
    const SAMPLE: &str = r#"<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.6">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmp:Rating="4"
    xmp:Label="Red">
   <dc:description>
    <rdf:Alt><rdf:li xml:lang="x-default">版权 2026 张三</rdf:li></rdf:Alt>
   </dc:description>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"#;

    #[test]
    fn read_parses_rating_and_label() {
        let (r, l) = read_decision_from_text(SAMPLE);
        assert_eq!(r, Some(4));
        assert_eq!(l.as_deref(), Some("red"));
    }

    #[test]
    fn read_handles_single_quotes_and_spaces() {
        let t = r#"<x:xmpmeta><rdf:RDF><rdf:Description
             xmlns:xmp="http://ns.adobe.com/xap/1.0/"
             xmp:Rating = '3' xmp:Label = 'purple' /></rdf:RDF></x:xmpmeta>"#;
        let (r, l) = read_decision_from_text(t);
        assert_eq!(r, Some(3));
        assert_eq!(l.as_deref(), Some("purple"));
    }

    /// -1(Lightroom 的"拒绝")与越界值都不解释; 未知标签不猜(闭集枚举)
    #[test]
    fn read_ignores_unknown_label_and_out_of_range_rating() {
        let t = r#"<x:xmpmeta><rdf:RDF><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="-1" xmp:Label="红"/></rdf:RDF></x:xmpmeta>"#;
        let (r, l) = read_decision_from_text(t);
        assert_eq!(r, None);
        assert_eq!(l, None);

        let t2 = r#"<x:xmpmeta><rdf:RDF><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="9" xmp:Label="yelLOW"/></rdf:RDF></x:xmpmeta>"#;
        let (r2, l2) = read_decision_from_text(t2);
        assert_eq!(r2, None);
        assert_eq!(l2.as_deref(), Some("yellow"));
    }

    /// 核心契约: 改星级时**其余每一个字节都不许动**
    /// (用"把新值换回旧值 == 原文"来证明只动了那一处)
    #[test]
    fn replace_rating_keeps_every_other_byte() {
        let (out, changed) = apply_decision(Some(SAMPLE), Some(3), None, false).unwrap();
        assert!(changed);
        assert_eq!(out.replace("xmp:Rating=\"3\"", "xmp:Rating=\"4\""), SAMPLE);
        assert!(out.contains("版权 2026 张三"));
        let (r, l) = read_decision_from_text(&out);
        assert_eq!((r, l.as_deref()), (Some(3), Some("red")));
    }

    #[test]
    fn replace_label_keeps_every_other_byte() {
        let (out, changed) = apply_decision(Some(SAMPLE), None, Some("green"), false).unwrap();
        assert!(changed);
        assert_eq!(out.replace("xmp:Label=\"Green\"", "xmp:Label=\"Red\""), SAMPLE);
    }

    /// 单引号写法会被就地规范化成双引号, 且只动那一处
    #[test]
    fn single_quoted_value_is_replaced_with_double_quotes() {
        let t = r#"<x:xmpmeta><rdf:RDF><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating='3'/></rdf:RDF></x:xmpmeta>"#;
        let (out, changed) = apply_decision(Some(t), Some(5), None, false).unwrap();
        assert!(changed);
        assert!(out.contains(r#"xmp:Rating="5""#));
        assert_eq!(out.replace(r#"xmp:Rating="5""#, "xmp:Rating='3'"), t);
    }

    #[test]
    fn inserts_into_first_description_when_absent() {
        let t = r#"<x:xmpmeta><rdf:RDF><rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/"><dc:title/></rdf:Description></rdf:RDF></x:xmpmeta>"#;
        let (out, changed) = apply_decision(Some(t), Some(5), Some("blue"), false).unwrap();
        assert!(changed);
        assert!(out.contains(r#"xmp:Rating="5""#));
        assert!(out.contains(r#"xmp:Label="Blue""#));
        assert_eq!(read_decision_from_text(&out), (Some(5), Some("blue".to_string())));
        // 插入点必须在 start tag 之内
        let open = out.find("<rdf:Description").unwrap();
        let close = out[open..].find('>').unwrap() + open;
        assert!(out[open..close].contains("xmp:Rating"));
    }

    #[test]
    fn insert_adds_xmp_namespace_when_missing() {
        let t = r#"<x:xmpmeta><rdf:RDF><rdf:Description rdf:about=""/></rdf:RDF></x:xmpmeta>"#;
        let (out, _) = apply_decision(Some(t), Some(2), None, false).unwrap();
        assert!(out.contains(&format!(r#"xmlns:xmp="{}""#, XMP_NS)));
        assert!(out.contains(r#"xmp:Rating="2""#));
    }

    /// 自闭合的 Description 也能插(插在 '/' 之前, 不需要展开成开闭对)
    #[test]
    fn self_closing_description_gets_attribute() {
        let t = r#"<x:xmpmeta><rdf:RDF><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/"/></rdf:RDF></x:xmpmeta>"#;
        let (out, _) = apply_decision(Some(t), Some(1), None, false).unwrap();
        assert!(out.contains(r#"xmp:Rating="1"/>"#), "{}", out);
    }

    /// 没有 Description 但有 RDF → 造一个
    #[test]
    fn creates_description_when_only_rdf_exists() {
        let t = r#"<x:xmpmeta><rdf:RDF></rdf:RDF></x:xmpmeta>"#;
        let (out, _) = apply_decision(Some(t), Some(4), Some("red"), false).unwrap();
        assert!(out.contains("<rdf:Description"));
        assert_eq!(read_decision_from_text(&out), (Some(4), Some("red".to_string())));
    }

    /// 多个 rdf:Description: **已存在的属性全部统一**(不留第二个旧值);
    /// 只在"一个都没有"时才插入 —— 上面第一个 Description 没有 Label, 所以这里
    /// 期望恰好 1 处 Label(第二个被改)而不是 2 处(XMP 会把多个 Description 合并,
    /// 没必要为了"每个 Description 都齐全"去动没写过的那个)。
    #[test]
    fn multiple_descriptions_are_all_updated() {
        let t = r#"<x:xmpmeta><rdf:RDF>
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="4"/>
<rdf:Description rdf:about="x" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="2" xmp:Label="Red"/>
</rdf:RDF></x:xmpmeta>"#;
        let (out, _) = apply_decision(Some(t), Some(5), Some("purple"), false).unwrap();
        assert!(!out.contains(r#"xmp:Rating="4""#));
        assert!(!out.contains(r#"xmp:Rating="2""#));
        assert_eq!(out.matches(r#"xmp:Rating="5""#).count(), 2);
        assert_eq!(out.matches(r#"xmp:Label="Purple""#).count(), 1);
        assert!(out.contains(r#"rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="5"/>"#));
    }

    /// 清除标签 = 真删掉属性(连前面那段空白一起), 而不是留一个空值
    #[test]
    fn clear_label_removes_attribute() {
        let (out, changed) = apply_decision(Some(SAMPLE), None, None, true).unwrap();
        assert!(changed);
        assert!(!out.contains("xmp:Label"));
        assert!(out.contains(r#"xmp:Rating="4""#));
        assert!(out.contains("版权 2026 张三"));
        assert_eq!(read_decision_from_text(&out).1, None);
    }

    /// 值已经对了 → 一个字都不动(changed=false, 调用方不落盘)
    #[test]
    fn no_change_when_value_already_equal() {
        let (out, changed) = apply_decision(Some(SAMPLE), Some(4), None, false).unwrap();
        assert!(!changed);
        assert_eq!(out, SAMPLE);
        // 没有标签时"清标签"也是空操作
        let t = r#"<x:xmpmeta><rdf:RDF><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="1"/></rdf:RDF></x:xmpmeta>"#;
        let (_, changed2) = apply_decision(Some(t), None, None, true).unwrap();
        assert!(!changed2);
    }

    #[test]
    fn rejects_non_xmp_text() {
        assert_eq!(apply_decision(Some("<html><body>xmp:Rating=\"5\"</body></html>"), Some(3), None, false), Err(XmpError::NotXmp));
        assert_eq!(apply_decision(Some("{\"json\": true}"), Some(3), None, false), Err(XmpError::NotXmp));
    }

    /// 元素形式属性: 拒绝改写(不制造"同一 property 两种写法")
    #[test]
    fn rejects_element_form() {
        let t = r#"<x:xmpmeta><rdf:RDF><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/"><xmp:Rating>4</xmp:Rating></rdf:Description></rdf:RDF></x:xmpmeta>"#;
        assert_eq!(apply_decision(Some(t), Some(3), None, false), Err(XmpError::UnsupportedForm));
    }

    /// 注释/CDATA 里的 `xmp:Rating="5"` **不是属性**, 必须被跳过
    #[test]
    fn comment_and_cdata_are_not_attributes() {
        let t = r#"<x:xmpmeta><rdf:RDF><rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/"><!-- xmp:Rating="5" --><![CDATA[ xmp:Label="Blue" ]]></rdf:Description></rdf:RDF></x:xmpmeta>"#;
        let (r, l) = read_decision_from_text(t);
        assert_eq!((r, l), (None, None));
        let (out, _) = apply_decision(Some(t), Some(3), None, false).unwrap();
        assert!(out.contains(r#"<!-- xmp:Rating="5" -->"#));
        assert!(out.contains("CDATA"));
        assert_eq!(read_decision_from_text(&out).0, Some(3));
    }

    /// 默认命名空间就是 xmp 时的裸属性: 认它(替换), 而不是再插一个带前缀的
    #[test]
    fn bare_attribute_with_default_xmp_namespace_is_recognized() {
        let t = format!(
            r#"<x:xmpmeta><rdf:RDF><rdf:Description rdf:about="" xmlns="{}" Rating="4" Label="Red"/></rdf:RDF></x:xmpmeta>"#,
            XMP_NS
        );
        let (r, l) = read_decision_from_text(&t);
        assert_eq!((r, l.as_deref()), (Some(4), Some("red")));
        let (out, _) = apply_decision(Some(&t), Some(3), None, false).unwrap();
        assert_eq!(out.matches("Rating=").count(), 1, "不许出现第二个同名属性: {}", out);
        assert!(out.contains(r#"Rating="3""#));
    }

    #[test]
    fn template_is_valid_and_contains_both_fields() {
        let t = minimal_template(Some(5), Some("yellow"));
        assert!(looks_like_xmp(&t));
        assert_eq!(read_decision_from_text(&t), (Some(5), Some("yellow".to_string())));
        assert!(t.contains(XMP_NS) && t.contains(RDF_NS));
        // 模板自己也能被再次改写
        let (out, changed) = apply_decision(Some(&t), Some(0), None, false).unwrap();
        assert!(changed);
        assert_eq!(read_decision_from_text(&out).0, Some(0));
    }

    #[test]
    fn sidecar_candidates_and_write_target() {
        let root = test_root("paths");
        std::fs::create_dir_all(&root).unwrap();
        let photo = root.join("IMG_1234.ARW");
        std::fs::write(&photo, b"raw").unwrap();

        let cands = sidecar_candidates(&photo);
        assert_eq!(cands[0], root.join("IMG_1234.xmp"));
        assert_eq!(cands[1], root.join("IMG_1234.ARW.xmp"));
        // 都没有 → 写 stem.xmp
        assert_eq!(write_target(&photo).unwrap(), root.join("IMG_1234.xmp"));
        // 只有 full-name 边车存在 → 跟随它(不产生第二个边车)
        let full = root.join("IMG_1234.ARW.xmp");
        std::fs::write(&full, b"x").unwrap();
        assert_eq!(write_target(&photo).unwrap(), full);
        assert_eq!(existing_sidecar(&photo).unwrap(), full);

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn write_one_creates_then_updates_and_preserves_other_fields() {
        let root = test_root("write");
        std::fs::create_dir_all(&root).unwrap();
        let photo = root.join("IMG_0001.ARW");
        std::fs::write(&photo, b"raw").unwrap();
        let sidecar = root.join("IMG_0001.xmp");

        // 首次: 生成模板
        let item = DecisionWrite { path: photo.to_string_lossy().to_string(), rating: Some(4), label: Some("red".into()), clear_label: false };
        assert!(matches!(write_one(&item).unwrap(), Outcome::Written { .. }));
        let text = std::fs::read_to_string(&sidecar).unwrap();
        assert_eq!(read_decision_from_text(&text), (Some(4), Some("red".to_string())));

        // 用户手工加了关键词/版权 → 改星级必须原样保留其它字段
        let edited = text.replace(
            "  <rdf:Description",
            "  <rdf:Description\n    xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmp:Subject=\"旅行\" xmlns:xmp2=\"urn:x\"",
        );
        std::fs::write(&sidecar, &edited).unwrap();
        let item2 = DecisionWrite { path: photo.to_string_lossy().to_string(), rating: Some(2), label: None, clear_label: false };
        assert!(matches!(write_one(&item2).unwrap(), Outcome::Written { .. }));
        let after = std::fs::read_to_string(&sidecar).unwrap();
        assert_eq!(after.replace("xmp:Rating=\"2\"", "xmp:Rating=\"4\""), edited);
        assert!(after.contains("旅行"));

        // 值没变 → 不落盘(Skipped)
        assert!(matches!(write_one(&item2).unwrap(), Outcome::Skipped));

        // 清标签
        let item3 = DecisionWrite { path: photo.to_string_lossy().to_string(), rating: None, label: None, clear_label: true };
        assert!(matches!(write_one(&item3).unwrap(), Outcome::Written { .. }));
        let cleared = std::fs::read_to_string(&sidecar).unwrap();
        assert!(!cleared.contains("xmp:Label"));

        // 非照片路径必须被拒绝(不变式 2)
        let bad = DecisionWrite { path: root.join("note.txt").to_string_lossy().to_string(), rating: Some(1), label: None, clear_label: false };
        assert!(write_one(&bad).is_err());

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn write_refuses_oversize_and_utf16() {
        let root = test_root("limits");
        std::fs::create_dir_all(&root).unwrap();
        let photo = root.join("IMG_0002.ARW");
        std::fs::write(&photo, b"raw").unwrap();
        let sidecar = root.join("IMG_0002.xmp");

        // UTF-16 BOM → 拒绝, 且文件原样
        std::fs::write(&sidecar, [0xFFu8, 0xFE, b'<', 0x00]).unwrap();
        let item = DecisionWrite { path: photo.to_string_lossy().to_string(), rating: Some(1), label: None, clear_label: false };
        assert_eq!(write_one(&item).map(|_| ()), Err(XmpError::Encoding));
        assert_eq!(std::fs::read(&sidecar).unwrap(), vec![0xFFu8, 0xFE, b'<', 0x00]);

        // 超限 → 拒绝, 且不写临时文件
        let big = vec![b'x'; (XMP_SIZE_LIMIT + 1) as usize];
        std::fs::write(&sidecar, &big).unwrap();
        assert_eq!(write_one(&item).map(|_| ()), Err(XmpError::TooLarge));
        assert!(!root.join(".IMG_0002.xmp.tmp").exists());

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn probe_reports_writable_dir() {
        let root = test_root("probe");
        std::fs::create_dir_all(&root).unwrap();
        let p = probe_dir(&root);
        assert!(p.writable);
        assert!(p.code.is_none());
        assert!(!p.network);
        // 探测文件必须被清掉
        assert!(!root.join(PROBE_FILE).exists());

        let missing = probe_dir(&root.join("nope"));
        assert!(!missing.writable);
        assert!(missing.code.is_some());

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn error_codes_are_stable_strings() {
        // 前端按这些字符串拼 i18n key(白名单), 改了就是契约变更
        assert_eq!(XmpError::WriteProtect.code(), "writeProtect");
        assert_eq!(XmpError::NotXmp.code(), "notXmp");
        assert_eq!(XmpError::TooLarge.code(), "tooLarge");
        assert_eq!(XmpError::UnsupportedForm.code(), "unsupportedForm");
    }
}
