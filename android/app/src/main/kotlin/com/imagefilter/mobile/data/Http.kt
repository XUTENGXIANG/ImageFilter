// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局唯一的 OkHttpClient 工厂。
 *
 * 为什么只有一个: Coil 与 [ApiClient] 必须共用它 —— 否则鉴权头要装两遍(迟早漏一处),
 * 而且连接池/线程池会翻倍。给 Coil 的是**同一个实例**(见 M1′ Task 5 的 `GridScreen`)。
 *
 * ⚠️ **鉴权头只在这里装一次**。所以自己造 `OkHttpClient()` 传给 [ApiClient] 就等于
 * 把 token 丢了 —— 注入 client 时必须用本函数(测试也一样)。
 *
 * 超时按"最慢的真实操作"定: 服务端首次预览 RAW 要做全解码(数秒) + 降采样,
 * 所以 readTimeout 给到 60s; 连接超时短(隧道断了要尽快报错, 别让用户干等)。
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
