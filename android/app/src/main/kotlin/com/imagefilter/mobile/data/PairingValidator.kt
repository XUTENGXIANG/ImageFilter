// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 「测试连接」的结论。三种, 不许再压成"成功/失败"两态。
 *
 * 压成两态会丢掉两件真实会发生的事: ① 地址通了但电脑上一个共享根都没有(那是服务端的
 * 安全默认, 不是故障); ② 同一个 401 有三种完全不同的成因(见 [PairingCheck.Failed])。
 */
sealed interface PairingCheck {
    /** 本地校验就没过 —— **[ApiClient] 一个请求都没发**。 */
    data class BadInput(val field: PairingField, val problem: PairingInputProblem) : PairingCheck

    /**
     * 地址可达, 而且 token 被服务端接受了。
     *
     * [shares] **可能为空** —— 电脑上一个共享根都没配。这是有意的安全默认(没共享目录 =
     * 谁都没有权限), 不是故障, 所以它**不是**失败: 配对信息是好的, 用户可以先去电脑上
     * 加共享根, 不必为此重扫一遍配对码。
     */
    data class Connected(val ping: PingDto, val shares: List<String>) : PairingCheck {
        val hasShares: Boolean get() = shares.isNotEmpty()
    }

    /**
     * 失败。[code] 是**服务端码或手机合成码**(文案与诊断都用它)。
     *
     * [unauthorizedCause] 只在 [ApiException.isUnauthorized] 时非空 —— 那是 Task 2 的
     * [ApiClient.classifyUnauthorized] 的结论。**绝不允许**在第一次 401 上就把它当成
     * `TOKEN_ROTATED`: 隧道断了也会 401 得莫名其妙, 而"去重新配对"这个动作对网络问题
     * 毫无用处(规格 §5.3 的纪律: 每条码必须落到"用户下一步做什么")。
     */
    data class Failed(val code: String, val unauthorizedCause: UnauthorizedCause? = null) : PairingCheck
}

/**
 * 配对向导的**状态机**(纯逻辑, 不含任何 Compose/Android 类型, 可 JVM 单测)。
 *
 * 一次 [check] 的动作序列(顺序是刻意的):
 *   1. **本地校验**地址与配对码 —— 不合格就返回 [PairingCheck.BadInput], 请求数保持 0;
 *   2. `GET /api/v1/ping` —— 公开端点(规格 §5.1), 用它把"服务不可达"与"token 不对"分开;
 *   3. `GET /api/v1/shares` —— 受保护端点, 它才真正验证 token;
 *   4. 拿到 401 时**必须**走 [ApiClient.classifyUnauthorized] 消歧, 不许自己下结论。
 *
 * 为什么第 2 步不能省: ping 是公开的, 所以"ping 通了但 shares 401"是"服务在跑、token 不对"
 * 的确凿证据; 反过来, ping 就连不上时给用户看"配对码不对"是**撒谎** —— 电脑根本没应答。
 */
class PairingValidator(
    /**
     * 注入点: 生产路径就是默认值(`ApiClient(url, token)`, 它在内部用 [buildHttpClient])。
     *
     * ⚠️ 覆盖它时**必须**用 [buildHttpClient] 造 client(Task 2 的纪律: 鉴权头只装在那里),
     * 否则"token 有没有发出去"会变成假绿。测试用它来关掉 `retryOnConnectionFailure`,
     * 好让"复探打不通"这条路径能被**确定地**走一遍。
     */
    private val clientFactory: (url: String, token: String) -> ApiClient = { url, token ->
        ApiClient(url, token)
    },
) {

    suspend fun check(input: PairingInput): PairingCheck {
        val local = when (val r = PairingInputFormat.check(input)) {
            is LocalCheck.Bad -> return PairingCheck.BadInput(r.field, r.problem)
            is LocalCheck.Ok -> r
        }

        val api = clientFactory(local.url, local.token)

        val ping = try {
            api.ping()
        } catch (e: ApiException) {
            return failed(api, e)
        } catch (e: IOException) {
            // 协程被取消时 OkHttp 抛的也是 IOException(InterruptedIOException) ——
            // 把取消说成"网络故障"会让人去修一条没坏的网线。
            currentCoroutineContext().ensureActive()
            return failed(api, ApiError.synthesized(apiCodeFor(e)))
        }

        val shares = try {
            api.shares()
        } catch (e: ApiException) {
            return failed(api, e)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            return failed(api, ApiError.synthesized(apiCodeFor(e)))
        }

        return PairingCheck.Connected(ping, shares)
    }

    /** 一次失败 → [PairingCheck.Failed]; 只有 401 才有资格触发消歧复探。 */
    private suspend fun failed(api: ApiClient, ex: ApiException): PairingCheck.Failed {
        val cause = if (ex.isUnauthorized) api.classifyUnauthorized() else null
        return PairingCheck.Failed(ex.code, cause)
    }
}

/**
 * "根本没有 HTTP 响应"的 IOException → 手机合成的码(规格 §5.3 第二张表)。
 *
 * 三档分开的理由是**用户动作不同**, 不是分类癖:
 *   · `timeout` —— 电脑没回应(睡眠/隧道半死) → 检查电脑与网络;
 *   · `serviceOff` —— 连都连不上(没进程在听/域名解析不了/没有路由) → 去电脑上开服务;
 *   · `network` —— 连上了但中途断(隧道抖动) → 重试。
 *
 * 未知的 IOException 一律落到 `network`(最保守的一档), 绝不落到 `unauthorized` ——
 * 把一个说不出所以然的 IO 错误说成"配对码不对"会让用户白换一遍 token。
 */
internal fun apiCodeFor(e: IOException): String = when (e) {
    is SocketTimeoutException -> ApiErrorCodes.TIMEOUT
    is ConnectException, is UnknownHostException, is NoRouteToHostException -> ApiErrorCodes.SERVICE_OFF
    else -> ApiErrorCodes.NETWORK
}
