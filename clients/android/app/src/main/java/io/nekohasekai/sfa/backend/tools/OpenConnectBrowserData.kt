package io.nekohasekai.sfa.backend.tools

import io.nekohasekai.libbox.OpenConnectBrowserRequest
import io.nekohasekai.sfa.ktx.toList

// OpenConnect browser sign-in data (WebView, stays Kotlin). The endpoint status
// itself is kept by UiEndpointStatus (openConnectChallenge).

enum class OpenConnectBrowserCompletionMode {
    COOKIE,
    HEADER,
    CALLBACK,
    INVALID,
}

data class OpenConnectBrowserRequestData(
    val url: String,
    val finalURL: String,
    val cacheID: String,
    val cookieNames: List<String>,
    val earlyCookieNames: List<String>,
    val headerNames: List<String>,
    val callbackURLPrefixes: List<String>,
) {
    val completionMode: OpenConnectBrowserCompletionMode
        get() {
            val callbackValid = callbackURLPrefixes.isNotEmpty() && callbackURLPrefixes.all(String::isNotEmpty) &&
                callbackURLPrefixes.toSet().size == callbackURLPrefixes.size
            val cookieNamesValid = cookieNames.isNotEmpty() && cookieNames.all(String::isNotEmpty) &&
                cookieNames.toSet().size == cookieNames.size
            val earlyCookieNamesValid = earlyCookieNames.all(String::isNotEmpty) &&
                earlyCookieNames.toSet().size == earlyCookieNames.size &&
                cookieNames.toSet().intersect(earlyCookieNames.toSet()).isEmpty()
            val headerNamesValid = headerNames.isNotEmpty() && headerNames.all(String::isNotEmpty) &&
                headerNames.map(String::lowercase).toSet().size == headerNames.size
            return when {
                callbackValid && finalURL.isEmpty() && cookieNames.isEmpty() && earlyCookieNames.isEmpty() && headerNames.isEmpty() -> OpenConnectBrowserCompletionMode.CALLBACK
                callbackURLPrefixes.isEmpty() && finalURL.isNotEmpty() && cookieNamesValid && earlyCookieNamesValid && headerNames.isEmpty() -> OpenConnectBrowserCompletionMode.COOKIE
                callbackURLPrefixes.isEmpty() && finalURL.isEmpty() && cookieNames.isEmpty() && earlyCookieNames.isEmpty() && headerNamesValid -> OpenConnectBrowserCompletionMode.HEADER
                else -> OpenConnectBrowserCompletionMode.INVALID
            }
        }
}

data class OpenConnectBrowserCookieData(
    val name: String,
    val value: String,
)

data class OpenConnectBrowserResultData(
    val finalURL: String,
    val cookies: List<OpenConnectBrowserCookieData>,
)

fun OpenConnectBrowserRequest.toData(): OpenConnectBrowserRequestData = OpenConnectBrowserRequestData(
    url = url,
    finalURL = finalURL,
    cacheID = cacheID,
    cookieNames = cookieNames().toList(),
    earlyCookieNames = earlyCookieNames().toList(),
    headerNames = headerNames().toList(),
    callbackURLPrefixes = callbackURLPrefixes().toList(),
)
