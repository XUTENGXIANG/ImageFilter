// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局唯一的 OkHttpClient 工厂。
 *
 * 为什么只有一个: Coil 与 [ApiClient] 必须共用它 —— 否则鉴权头要装两遍(迟早漏一处),
 * 而且连接池/线程池会翻倍。生产路径上**取实例**用 [SharedHttpClient.forToken]
 * (它保证同一枚 token 只有**一个**实例), 见 M1′ Task 5 的 `GridScreen`/`rememberAppImageLoader`。
 *
 * ⚠️ **鉴权头只在这里装一次**。所以自己造 `OkHttpClient()` 传给 [ApiClient] 就等于
 * 把 token 丢了 —— 注入 client 时必须用本函数(测试也一样)。
 *
 * 超时按"最慢的真实操作"定: 服务端首次预览 RAW 要做全解码(数秒) + 降采样,
 * 所以 readTimeout 给到 60s; 连接超时短(网络断了要尽快报错, 别让用户干等)。
 */
fun buildHttpClient(token: String?): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        val b = chain.request().newBuilder()
        if (!token.isNullOrEmpty()) b.header("Authorization", "Bearer $token")
        chain.proceed(b.build())
    }
    .build()

/**
 * **按 token 记忆**的客户端 —— [ApiClient] 与 Coil 拿到的必须是**同一个实例**。
 *
 * 为什么不是每处各调一次 [buildHttpClient]: 那样"共用客户端"就只是一句注释,
 * 每个调用点各拿一个实例(连接池/线程池翻倍), 而且"JSON 接口能通、图片 401"
 * 这类分裂极难查。同一枚 token 永远返回同一个实例; token 变了(**重新配对**)
 * 才换一个新的 —— 所以旧 token 绝不会被复用到新的配对会话上。
 *
 * 只记忆**最近一个** token: 本 App 同时只存一份配对信息([PairingStore] 的单份语义),
 * 不需要一张会无限增长的 map。
 */
object SharedHttpClient {
    private val lock = Any()
    private var memoToken: String? = null
    private var memo: OkHttpClient? = null

    fun forToken(token: String?): OkHttpClient = synchronized(lock) {
        val cached = memo
        if (cached != null && memoToken == token) return cached
        buildHttpClient(token).also {
            memoToken = token
            memo = it
        }
    }
}
