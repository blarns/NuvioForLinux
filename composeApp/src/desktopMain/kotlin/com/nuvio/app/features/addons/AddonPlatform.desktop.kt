package com.nuvio.app.features.addons

import com.nuvio.app.desktop.DesktopPrefs
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess

private val addonHttpClient = HttpClient(Java) {
    engine {
        pipelining = true
    }
}

internal actual object AddonStorage {
    actual fun loadInstalledAddonUrls(profileId: Int): List<String> {
        val str = DesktopPrefs.getString("addons", "installed_$profileId") ?: ""
        return if (str.isEmpty()) emptyList() else str.split(",")
    }

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        DesktopPrefs.putString("addons", "installed_$profileId", urls.joinToString(","))
    }

    actual fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean> {
        val str = DesktopPrefs.getString("addons", "enabled_$profileId") ?: ""
        if (str.isEmpty()) return emptyMap()
        return str.split(",").associate {
            val parts = it.split("=")
            parts[0] to parts[1].toBoolean()
        }
    }

    actual fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>) {
        val str = states.map { "${it.key}=${it.value}" }.joinToString(",")
        DesktopPrefs.putString("addons", "enabled_$profileId", str)
    }
}

actual suspend fun httpGetText(url: String): String =
    addonHttpClient.get(url).bodyAsText()

actual suspend fun httpPostJson(url: String, body: String): String =
    addonHttpClient.post(url) {
        header("Content-Type", "application/json")
        setBody(body)
    }.bodyAsText()

actual suspend fun httpGetTextWithHeaders(url: String, headers: Map<String, String>): String =
    addonHttpClient.get(url) {
        headers.forEach { (k, v) -> header(k, v) }
    }.bodyAsText()

actual suspend fun httpPostJsonWithHeaders(url: String, body: String, headers: Map<String, String>): String =
    addonHttpClient.post(url) {
        headers.forEach { (k, v) -> header(k, v) }
        header("Content-Type", "application/json")
        setBody(body)
    }.bodyAsText()

actual suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean
): RawHttpResponse {
    val response = addonHttpClient.request(url) {
        this.method = HttpMethod.parse(method)
        headers.forEach { (k, v) -> header(k, v) }
        if (body.isNotEmpty()) setBody(body)
    }
    
    val responseHeaders = mutableMapOf<String, String>()
    response.headers.entries().forEach {
        responseHeaders[it.key] = it.value.joinToString(", ")
    }
    
    return RawHttpResponse(
        status = response.status.value,
        statusText = response.status.description,
        url = url,
        body = response.bodyAsText(),
        headers = responseHeaders
    )
}
