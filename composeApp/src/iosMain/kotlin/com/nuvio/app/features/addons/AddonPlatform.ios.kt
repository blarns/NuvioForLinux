package com.nuvio.app.features.addons

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import platform.Foundation.NSUserDefaults

actual object AddonStorage {
    private const val addonUrlsKey = "installed_manifest_urls"

    actual fun loadInstalledAddonUrls(profileId: Int): List<String> =
        NSUserDefaults.standardUserDefaults
            .stringForKey("${addonUrlsKey}_$profileId")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        NSUserDefaults.standardUserDefaults.setObject(
            urls.joinToString(separator = "\n"),
            forKey = "${addonUrlsKey}_$profileId",
        )
    }
}

private val addonHttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 10_000
    }
    expectSuccess = false
}

actual suspend fun httpGetText(url: String): String =
    addonHttpClient
        .get(url) {
            accept(ContentType.Application.Json)
        }
        .let { response ->
            val payload = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error("Request failed with HTTP ${response.status.value}")
            }
            if (payload.isBlank()) {
                throw IllegalStateException("Empty response body")
            }
            payload
        }

actual suspend fun httpPostJson(url: String, body: String): String =
    addonHttpClient
        .post(url) {
            accept(ContentType.Application.Json)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        .let { response ->
            val payload = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error("Request failed with HTTP ${response.status.value}")
            }
            if (payload.isBlank()) {
                throw IllegalStateException("Empty response body")
            }
            payload
        }
