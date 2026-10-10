// Copyright (c) 2026 XUTENGXIANG
// SPDX-License-Identifier: MIT
package com.imagefilter.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.imagefilter.mobile.data.SharedHttpClient

/**
 * 缩略图用的**全局唯一** ImageLoader。
 *
 * **必须与 [com.imagefilter.mobile.data.ApiClient] 共用同一个 OkHttpClient** ——
 * 取实例只用 [SharedHttpClient.forToken]: 同一枚 token 永远返回**同一个实例**, 所以
 * 缩略图与 JSON 走的是同一套鉴权拦截器(规格 §4.2/§5.2 的"鉴权头只装一处")。
 * 各建一个客户端会出现"JSON 接口能通、图片 401"这种极难查的分裂。
 *
 * 别在这里 `OkHttpClient()`: 那是一个**没有 Bearer 头**的客户端 —— 网格会安静地
 * 每格都失败, 而 JSON 那侧一切正常。
 *
 * `remember(token)` 是关键: 重新配对(token 变了)必须换掉整个 ImageLoader, 否则
 * Coil 会继续拿旧 token 去要图(而 JSON 那侧已经用新 token 了)。
 */
@Composable
fun rememberAppImageLoader(token: String): ImageLoader {
    val context = LocalContext.current
    val client = remember(token) { SharedHttpClient.forToken(token) }
    return remember(client) {
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .crossfade(true)
            .build()
    }
}
