// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 配对向导里那三个输入框的**原始文本**(用户敲进去/粘进来的那一份, 没做过任何加工)。
 *
 * 归一化在 [PairingInputFormat] 里做, 而且只在**一个地方**做 —— 界面、校验、存储三处
 * 各自 trim 一次是这类代码最典型的漂移来源。
 */
data class PairingInput(
    val url: String = "",
    val token: String = "",
    /** 可选备注(纯粹给用户自己认机器用, 不参与任何协议)。 */
    val note: String = "",
)

/** 出错的是哪一个框 —— 界面据此把红框标在对的地方, 而不是笼统地说"填错了"。 */
enum class PairingField { URL, TOKEN }

/**
 * 本地就能判定、**一个请求都不用发**的输入问题。
 *
 * 为什么不干脆直接发请求让服务端回 400: 用户输错地址/少抄一位配对码是**最常见**的失败,
 * 让它退化成一次连接超时(10s)再回一句"出错了", 是最容易让人放弃配对的做法。
 */
enum class PairingInputProblem { EMPTY_URL, BAD_URL, EMPTY_TOKEN, BAD_TOKEN_SHAPE }

/** 本地校验的结论。 */
sealed interface LocalCheck {
    /**
     * 通过: 带回**归一化后**的地址与配对码 —— 界面与存储都必须用这一份,
     * 不许再拿输入框里的原串去发请求或落盘。
     */
    data class Ok(val url: String, val token: String, val note: String) : LocalCheck

    data class Bad(val field: PairingField, val problem: PairingInputProblem) : LocalCheck
}

/**
 * 地址与配对码的**归一化 + 本地校验**(纯函数, 全部可 JVM 单测)。
 *
 * 两条纪律:
 *   · 只做**不可能改变语义**的加工(补缺省 scheme、去空白、去末尾斜杠);
 *   · 校验规则与服务端自己的规则**同源**, 不自己另立一套。
 */
object PairingInputFormat {

    /**
     * 配对码的长度 —— 与 `src-tauri/src/server/config.rs::token_is_valid` 逐字一致:
     * `token.len() == 64 && token.bytes().all(|b| b.is_ascii_hexdigit())`。
     *
     * 所以这条本地检查**不可能**拒掉一枚服务端会接受的 token: 服务端自己的 `start()`
     * 拿同一条规则做 fail-closed 门禁(`token 不可用 → 服务不起`), 任何**在跑的**服务
     * 手上都是一枚 64 位十六进制 token。
     */
    const val TOKEN_LENGTH = 64

    /** 三个框的本地校验。返回 [LocalCheck.Ok] 时, 里面的值就是后面该用的那一份。 */
    fun check(input: PairingInput): LocalCheck {
        val url = normalizeUrl(input.url)
            ?: return LocalCheck.Bad(
                PairingField.URL,
                if (input.url.isBlank()) PairingInputProblem.EMPTY_URL else PairingInputProblem.BAD_URL,
            )
        val token = normalizeToken(input.token)
            ?: return LocalCheck.Bad(
                PairingField.TOKEN,
                if (input.token.isBlank()) PairingInputProblem.EMPTY_TOKEN else PairingInputProblem.BAD_TOKEN_SHAPE,
            )
        return LocalCheck.Ok(url, token, input.note.trim())
    }

    /**
     * 地址归一化。失败返回 `null`(由 [check] 决定该报哪一档问题)。
     *
     *   · 两端空白去掉;
     *   · **缺 scheme 时补 `http://`** —— 服务端不做 TLS(规格 §4.3), 桌面端设置面板里
     *     显示的也是 `http://<ip>:<端口>`(`server/mod.rs` 的 `format!("http://{}:{}", …)`),
     *     所以用户抄下来的常常是 `192.168.1.5:8765` 这种没有 scheme 的一串。注意这是
     *     **补一个缺省值, 不是猜**: 端口与路径一个字都不猜;
     *   · 末尾斜杠去掉 —— 与 [ApiClient] / [PairingStore] 的既有约定一致
     *     (Task 2 的 `ApiClient` 自己也会 `trimEnd('/')`)。
     *
     * `ftp://` 这类非 http(s) 的 scheme 会被 OkHttp 判成非法(返回 `null`) ——
     * 这正是我们要的: 手机端只会说 HTTP。
     */
    fun normalizeUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val withScheme = if (t.contains("://")) t else "http://$t"
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        return parsed.toString().trimEnd('/')
    }

    /**
     * 配对码归一化。失败返回 `null`。
     *
     *   · **去掉所有空白**: 桌面端的"复制"按钮可能带出换行(用户从聊天窗口转发时更常见),
     *     而十六进制里本来就没有空白字符, 所以这一步**不可能**把一枚错的 token 变成对的
     *     (服务端 `routes.rs::bearer_token` 对 Bearer 凭证也做同样的 OWS 裁剪, 理由同源)。
     *     ⚠️ 这一步是**必须**的而不是顺手: 带换行的值一旦进了 `Authorization` 头,
     *     OkHttp 会当场抛 `IllegalArgumentException`(头值里不许有控制字符);
     *   · 长度必须**恰好** 64 且每个字符都是 **ASCII** 十六进制。这里刻意不用
     *     `Char.isDigit()` —— 它对阿拉伯-印度数字(`٣`)等非 ASCII 数字也返回 true,
     *     那会让一枚服务端必然拒绝的 token 在这里被判成"形状正确";
     *   · **大小写原样保留**: 服务端是逐字节常量时间比较(`auth::eq_constant_time`),
     *     把用户给的大写折成小写就可能改掉一枚盘上真实存在的 token。
     */
    fun normalizeToken(raw: String): String? {
        val t = raw.filterNot { it.isWhitespace() }
        if (t.isEmpty()) return null
        if (t.length != TOKEN_LENGTH) return null
        if (!t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return t
    }
}
