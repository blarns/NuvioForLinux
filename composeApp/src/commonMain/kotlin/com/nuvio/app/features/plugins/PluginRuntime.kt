package com.nuvio.app.features.plugins

import co.touchlab.kermit.Logger
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.select.Elements
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

private const val PLUGIN_TIMEOUT_MS = 60_000L
private const val MAX_FETCH_BODY_CHARS = 256 * 1024
private const val MAX_FETCH_HEADER_VALUE_CHARS = 8 * 1024
private const val FETCH_TRUNCATION_SUFFIX = "\n...[truncated]"

internal object PluginRuntime {
    private val log = Logger.withTag("PluginRuntime")
    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val containsRegex = Regex(""":contains\([\"']([^\"']+)[\"']\)""")

    suspend fun executePlugin(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, Any> = emptyMap(),
    ): List<PluginRuntimeResult> = withContext(Dispatchers.Default) {
        withTimeout(PLUGIN_TIMEOUT_MS) {
            executePluginInternal(
                code = code,
                tmdbId = tmdbId,
                mediaType = mediaType,
                season = season,
                episode = episode,
                scraperId = scraperId,
                scraperSettings = scraperSettings,
            )
        }
    }

    private suspend fun executePluginInternal(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, Any>,
    ): List<PluginRuntimeResult> {
        val documentCache = mutableMapOf<String, Document>()
        val elementCache = mutableMapOf<String, Element>()
        var idCounter = 0
        var resultJson = "[]"

        try {
            quickJs(Dispatchers.Default) {
                define("console") {
                    function("log") { args ->
                        log.d { "Plugin:$scraperId ${args.joinToString(" ") { it?.toString() ?: "null" }}" }
                        null
                    }
                    function("error") { args ->
                        log.e { "Plugin:$scraperId ${args.joinToString(" ") { it?.toString() ?: "null" }}" }
                        null
                    }
                    function("warn") { args ->
                        log.w { "Plugin:$scraperId ${args.joinToString(" ") { it?.toString() ?: "null" }}" }
                        null
                    }
                    function("info") { args ->
                        log.i { "Plugin:$scraperId ${args.joinToString(" ") { it?.toString() ?: "null" }}" }
                        null
                    }
                    function("debug") { args ->
                        log.d { "Plugin:$scraperId ${args.joinToString(" ") { it?.toString() ?: "null" }}" }
                        null
                    }
                }

                function("__native_fetch") { args ->
                    val url = args.getOrNull(0)?.toString() ?: ""
                    val method = args.getOrNull(1)?.toString() ?: "GET"
                    val headersJson = args.getOrNull(2)?.toString() ?: "{}"
                    val body = args.getOrNull(3)?.toString() ?: ""
                    try {
                        performNativeFetch(url, method, headersJson, body)
                    } catch (t: Throwable) {
                        log.e(t) { "Fetch bridge error for $method $url" }
                        JsonObject(
                            mapOf(
                                "ok" to JsonPrimitive(false),
                                "status" to JsonPrimitive(0),
                                "statusText" to JsonPrimitive(t.message ?: "Fetch failed"),
                                "url" to JsonPrimitive(url),
                                "body" to JsonPrimitive(""),
                                "headers" to JsonObject(emptyMap()),
                            ),
                        ).toString()
                    }
                }

                function("__parse_url") { args ->
                    parseUrl(args.getOrNull(0)?.toString() ?: "")
                }

                function("__cheerio_load") { args ->
                    val html = args.getOrNull(0)?.toString() ?: ""
                    val docId = "doc_${idCounter++}_${Random.nextInt(0, Int.MAX_VALUE)}"
                    documentCache[docId] = Ksoup.parse(html)
                    docId
                }

                function("__cheerio_select") { args ->
                    val docId = args.getOrNull(0)?.toString() ?: ""
                    var selector = args.getOrNull(1)?.toString() ?: ""
                    val doc = documentCache[docId] ?: return@function "[]"
                    try {
                        selector = selector.replace(containsRegex, ":contains($1)")
                        val elements = if (selector.isEmpty()) Elements() else doc.select(selector)
                        val ids = elements.mapIndexed { index, el ->
                            val id = "$docId:$index:${el.hashCode()}"
                            elementCache[id] = el
                            id
                        }
                        "[" + ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
                    } catch (_: Exception) {
                        "[]"
                    }
                }

                function("__cheerio_find") { args ->
                    val docId = args.getOrNull(0)?.toString() ?: ""
                    val elementId = args.getOrNull(1)?.toString() ?: ""
                    var selector = args.getOrNull(2)?.toString() ?: ""
                    val element = elementCache[elementId] ?: return@function "[]"
                    try {
                        selector = selector.replace(containsRegex, ":contains($1)")
                        val elements = element.select(selector)
                        val ids = elements.mapIndexed { index, el ->
                            val id = "$docId:find:$index:${el.hashCode()}"
                            elementCache[id] = el
                            id
                        }
                        "[" + ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
                    } catch (_: Exception) {
                        "[]"
                    }
                }

                function("__cheerio_text") { args ->
                    val elementIds = args.getOrNull(1)?.toString() ?: ""
                    elementIds.split(",")
                        .filter { it.isNotEmpty() }
                        .mapNotNull { elementCache[it]?.text() }
                        .joinToString(" ")
                }

                function("__cheerio_html") { args ->
                    val docId = args.getOrNull(0)?.toString() ?: ""
                    val elementId = args.getOrNull(1)?.toString() ?: ""
                    if (elementId.isEmpty()) {
                        documentCache[docId]?.html() ?: ""
                    } else {
                        elementCache[elementId]?.html() ?: ""
                    }
                }

                function("__cheerio_inner_html") { args ->
                    val elementId = args.getOrNull(1)?.toString() ?: ""
                    elementCache[elementId]?.html() ?: ""
                }

                function("__cheerio_attr") { args ->
                    val elementId = args.getOrNull(1)?.toString() ?: ""
                    val attrName = args.getOrNull(2)?.toString() ?: ""
                    val value = elementCache[elementId]?.attr(attrName)
                    if (value.isNullOrEmpty()) "__UNDEFINED__" else value
                }

                function("__cheerio_next") { args ->
                    val docId = args.getOrNull(0)?.toString() ?: ""
                    val elementId = args.getOrNull(1)?.toString() ?: ""
                    val element = elementCache[elementId] ?: return@function "__NONE__"
                    val next = element.nextElementSibling() ?: return@function "__NONE__"
                    val nextId = "$docId:next:${next.hashCode()}"
                    elementCache[nextId] = next
                    nextId
                }

                function("__cheerio_prev") { args ->
                    val docId = args.getOrNull(0)?.toString() ?: ""
                    val elementId = args.getOrNull(1)?.toString() ?: ""
                    val element = elementCache[elementId] ?: return@function "__NONE__"
                    val prev = element.previousElementSibling() ?: return@function "__NONE__"
                    val prevId = "$docId:prev:${prev.hashCode()}"
                    elementCache[prevId] = prev
                    prevId
                }

                function("__capture_result") { args ->
                    resultJson = args.getOrNull(0)?.toString() ?: "[]"
                    null
                }

                val settingsJson = toJsonElement(scraperSettings).toString()
                val polyfillCode = buildPolyfillCode(scraperId, settingsJson)
                evaluate<Any?>(polyfillCode)

                val wrappedCode = """
                    var module = { exports: {} };
                    var exports = module.exports;
                    (function() {
                        $code
                    })();
                """.trimIndent()
                evaluate<Any?>(wrappedCode)

                val seasonArg = season?.toString() ?: "undefined"
                val episodeArg = episode?.toString() ?: "undefined"
                val callCode = """
                    (async function() {
                        try {
                            var getStreams = module.exports.getStreams || globalThis.getStreams;
                            if (!getStreams) {
                                console.error("getStreams function not found on module.exports or globalThis");
                                __capture_result(JSON.stringify([]));
                                return;
                            }
                            var result = await getStreams("$tmdbId", "$mediaType", $seasonArg, $episodeArg);
                            __capture_result(JSON.stringify(result || []));
                        } catch (e) {
                            console.error("getStreams error:", e && e.message ? e.message : e, e && e.stack ? e.stack : "");
                            __capture_result(JSON.stringify([]));
                        }
                    })();
                """.trimIndent()
                evaluate<Any?>(callCode)
            }

            return parseJsonResults(resultJson)
        } finally {
            documentCache.clear()
            elementCache.clear()
        }
    }

    private fun performNativeFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
    ): String {
        return try {
            val headers = parseHeaders(headersJson).toMutableMap()
            if (!headers.containsKey("User-Agent")) {
                headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            }

            val response = runBlocking {
                httpRequestRaw(
                    method = method,
                    url = url,
                    headers = headers,
                    body = body,
                )
            }

            val responseHeaders = response.headers.mapValues { (_, value) ->
                truncateString(value, MAX_FETCH_HEADER_VALUE_CHARS)
            }
            val result = JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(response.status in 200..299),
                    "status" to JsonPrimitive(response.status),
                    "statusText" to JsonPrimitive(response.statusText),
                    "url" to JsonPrimitive(response.url),
                    "body" to JsonPrimitive(truncateString(response.body, MAX_FETCH_BODY_CHARS)),
                    "headers" to JsonObject(responseHeaders.mapValues { JsonPrimitive(it.value) }),
                ),
            )
            result.toString()
        } catch (error: Throwable) {
            log.e(error) { "Fetch error for $method $url" }
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(false),
                    "status" to JsonPrimitive(0),
                    "statusText" to JsonPrimitive(error.message ?: "Fetch failed"),
                    "url" to JsonPrimitive(url),
                    "body" to JsonPrimitive(""),
                    "headers" to JsonObject(emptyMap()),
                ),
            )
                .toString()
        }
    }

    private fun parseHeaders(headersJson: String): Map<String, String> {
        return runCatching {
            val obj = json.parseToJsonElement(headersJson) as? JsonObject ?: JsonObject(emptyMap())
            obj.entries
                .mapNotNull { (key, value) ->
                    value.jsonPrimitive.contentOrNull?.let { key to it }
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun parseUrl(urlString: String): String {
        return try {
            val parsed = io.ktor.http.Url(urlString)
            JsonObject(
                mapOf(
                    "protocol" to JsonPrimitive("${parsed.protocol.name}:"),
                    "host" to JsonPrimitive(
                        if (parsed.port != parsed.protocol.defaultPort) {
                            "${parsed.host}:${parsed.port}"
                        } else {
                            parsed.host
                        },
                    ),
                    "hostname" to JsonPrimitive(parsed.host),
                    "port" to JsonPrimitive(
                        if (parsed.port != parsed.protocol.defaultPort) parsed.port.toString() else "",
                    ),
                    "pathname" to JsonPrimitive(parsed.encodedPath.ifBlank { "/" }),
                    "search" to JsonPrimitive(parsed.encodedQuery?.let { "?$it" } ?: ""),
                    "hash" to JsonPrimitive(parsed.encodedFragment?.let { "#$it" } ?: ""),
                ),
            ).toString()
        } catch (_: Exception) {
            JsonObject(
                mapOf(
                    "protocol" to JsonPrimitive(""),
                    "host" to JsonPrimitive(""),
                    "hostname" to JsonPrimitive(""),
                    "port" to JsonPrimitive(""),
                    "pathname" to JsonPrimitive("/"),
                    "search" to JsonPrimitive(""),
                    "hash" to JsonPrimitive(""),
                ),
            ).toString()
        }
    }

    private fun truncateString(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        val end = maxChars - FETCH_TRUNCATION_SUFFIX.length
        if (end <= 0) return FETCH_TRUNCATION_SUFFIX.take(maxChars)
        return value.substring(0, end) + FETCH_TRUNCATION_SUFFIX
    }

    private fun parseJsonResults(rawJson: String): List<PluginRuntimeResult> {
        return runCatching {
            val array = json.parseToJsonElement(rawJson) as? JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = when (val urlValue = item["url"]) {
                    is JsonPrimitive -> urlValue.contentOrNull?.takeIf { it.isNotBlank() }
                    is JsonObject -> urlValue["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    else -> null
                } ?: return@mapNotNull null

                val headers = (item["headers"] as? JsonObject)
                    ?.mapNotNull { (key, value) ->
                        value.jsonPrimitive.contentOrNull?.let { key to it }
                    }
                    ?.toMap()
                    ?.takeIf { it.isNotEmpty() }

                PluginRuntimeResult(
                    title = item.stringOrNull("title") ?: item.stringOrNull("name") ?: "Unknown",
                    name = item.stringOrNull("name"),
                    url = url,
                    quality = item.stringOrNull("quality"),
                    size = item.stringOrNull("size"),
                    language = item.stringOrNull("language"),
                    provider = item.stringOrNull("provider"),
                    type = item.stringOrNull("type"),
                    seeders = item["seeders"]?.jsonPrimitive?.intOrNull,
                    peers = item["peers"]?.jsonPrimitive?.intOrNull,
                    infoHash = item.stringOrNull("infoHash"),
                    headers = headers,
                )
            }.filter { it.url.isNotBlank() }
        }.getOrElse { error ->
            log.e(error) { "Failed to parse plugin result json" }
            emptyList()
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && !it.contains("[object") }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        is Map<*, *> -> JsonObject(
            value.entries
                .filter { it.key is String }
                .associate { (it.key as String) to toJsonElement(it.value) },
        )
        is Iterable<*> -> JsonArray(value.map(::toJsonElement))
        else -> JsonPrimitive(value.toString())
    }

    private fun buildPolyfillCode(scraperId: String, settingsJson: String): String {
        return """
            globalThis.SCRAPER_ID = "$scraperId";
            globalThis.SCRAPER_SETTINGS = $settingsJson;
            if (typeof globalThis.global === 'undefined') globalThis.global = globalThis;
            if (typeof globalThis.window === 'undefined') globalThis.window = globalThis;
            if (typeof globalThis.self === 'undefined') globalThis.self = globalThis;

            var fetch = async function(url, options) {
                options = options || {};
                var method = (options.method || 'GET').toUpperCase();
                var headers = options.headers || {};
                var body = options.body || '';
                var result = __native_fetch(url, method, JSON.stringify(headers), body);
                var parsed = JSON.parse(result);
                return {
                    ok: parsed.ok,
                    status: parsed.status,
                    statusText: parsed.statusText,
                    url: parsed.url,
                    headers: {
                        get: function(name) {
                            return parsed.headers[name.toLowerCase()] || null;
                        }
                    },
                    text: function() { return Promise.resolve(parsed.body); },
                    json: function() {
                        try {
                            if (parsed.body === null || parsed.body === undefined || parsed.body === '') {
                                return Promise.resolve(null);
                            }
                            return Promise.resolve(JSON.parse(parsed.body));
                        } catch (e) {
                            return Promise.resolve(null);
                        }
                    }
                };
            };

            if (typeof AbortSignal === 'undefined') {
                var AbortSignal = function() { this.aborted = false; this.reason = undefined; this._listeners = []; };
                AbortSignal.prototype.addEventListener = function(type, listener) {
                    if (type !== 'abort' || typeof listener !== 'function') return;
                    this._listeners.push(listener);
                };
                AbortSignal.prototype.removeEventListener = function(type, listener) {
                    if (type !== 'abort') return;
                    this._listeners = this._listeners.filter(function(l) { return l !== listener; });
                };
                AbortSignal.prototype.dispatchEvent = function(event) {
                    if (!event || event.type !== 'abort') return true;
                    for (var i = 0; i < this._listeners.length; i++) {
                        try { this._listeners[i].call(this, event); } catch (e) {}
                    }
                    return true;
                };
                globalThis.AbortSignal = AbortSignal;
            }

            if (typeof AbortController === 'undefined') {
                var AbortController = function() { this.signal = new AbortSignal(); };
                AbortController.prototype.abort = function(reason) {
                    if (this.signal.aborted) return;
                    this.signal.aborted = true;
                    this.signal.reason = reason;
                    this.signal.dispatchEvent({ type: 'abort' });
                };
                globalThis.AbortController = AbortController;
            }

            if (typeof atob === 'undefined') {
                globalThis.atob = function(input) {
                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    var str = String(input).replace(/=+$/, '');
                    if (str.length % 4 === 1) throw new Error('InvalidCharacterError');
                    var output = '';
                    var bc = 0, bs, buffer, idx = 0;
                    while ((buffer = str.charAt(idx++))) {
                        buffer = chars.indexOf(buffer);
                        if (buffer === -1) continue;
                        bs = bc % 4 ? bs * 64 + buffer : buffer;
                        if (bc++ % 4) output += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6)));
                    }
                    return output;
                };
            }

            if (typeof btoa === 'undefined') {
                globalThis.btoa = function(input) {
                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    var str = String(input);
                    var output = '';
                    for (var block, charCode, idx = 0, map = chars;
                         str.charAt(idx | 0) || (map = '=', idx % 1);
                         output += map.charAt(63 & (block >> (8 - (idx % 1) * 8)))) {
                        charCode = str.charCodeAt(idx += 3 / 4);
                        if (charCode > 0xFF) throw new Error('InvalidCharacterError');
                        block = (block << 8) | charCode;
                    }
                    return output;
                };
            }

            var URL = function(urlString, base) {
                var fullUrl = urlString;
                if (base && !/^https?:\/\//i.test(urlString)) {
                    var b = typeof base === 'string' ? base : base.href;
                    if (urlString.charAt(0) === '/') {
                        var m = b.match(/^(https?:\/\/[^\/]+)/);
                        fullUrl = m ? m[1] + urlString : urlString;
                    } else {
                        fullUrl = b.replace(/\/[^\/]*$/, '/') + urlString;
                    }
                }
                var parsed = __parse_url(fullUrl);
                var data = JSON.parse(parsed);
                this.href = fullUrl;
                this.protocol = data.protocol;
                this.host = data.host;
                this.hostname = data.hostname;
                this.port = data.port;
                this.pathname = data.pathname;
                this.search = data.search;
                this.hash = data.hash;
                this.origin = data.protocol + '//' + data.host;
                this.searchParams = new URLSearchParams(data.search || '');
            };
            URL.prototype.toString = function() { return this.href; };

            var URLSearchParams = function(init) {
                this._params = {};
                var self = this;
                if (init && typeof init === 'object' && !Array.isArray(init)) {
                    Object.keys(init).forEach(function(key) { self._params[key] = String(init[key]); });
                } else if (typeof init === 'string') {
                    init.replace(/^\?/, '').split('&').forEach(function(pair) {
                        var parts = pair.split('=');
                        if (parts[0]) self._params[decodeURIComponent(parts[0])] = decodeURIComponent(parts[1] || '');
                    });
                }
            };
            URLSearchParams.prototype.toString = function() {
                var self = this;
                return Object.keys(this._params).map(function(key) {
                    return encodeURIComponent(key) + '=' + encodeURIComponent(self._params[key]);
                }).join('&');
            };
            URLSearchParams.prototype.get = function(key) { return this._params.hasOwnProperty(key) ? this._params[key] : null; };
            URLSearchParams.prototype.set = function(key, value) { this._params[key] = String(value); };
            URLSearchParams.prototype.append = function(key, value) { this._params[key] = String(value); };
            URLSearchParams.prototype.has = function(key) { return this._params.hasOwnProperty(key); };
            URLSearchParams.prototype.delete = function(key) { delete this._params[key]; };
            URLSearchParams.prototype.keys = function() { return Object.keys(this._params); };
            URLSearchParams.prototype.values = function() {
                var self = this;
                return Object.keys(this._params).map(function(k) { return self._params[k]; });
            };
            URLSearchParams.prototype.entries = function() {
                var self = this;
                return Object.keys(this._params).map(function(k) { return [k, self._params[k]]; });
            };
            URLSearchParams.prototype.forEach = function(callback) {
                var self = this;
                Object.keys(this._params).forEach(function(key) { callback(self._params[key], key, self); });
            };
            URLSearchParams.prototype.getAll = function(key) {
                return this._params.hasOwnProperty(key) ? [this._params[key]] : [];
            };
            URLSearchParams.prototype.sort = function() {
                var sorted = {};
                var self = this;
                Object.keys(this._params).sort().forEach(function(k) { sorted[k] = self._params[k]; });
                this._params = sorted;
            };

            var cheerio = {
                load: function(html) {
                    var docId = __cheerio_load(html);
                    var $ = function(selector, context) {
                        if (selector && selector._elementIds) return selector;
                        if (context && context._elementIds && context._elementIds.length > 0) {
                            var allIds = [];
                            for (var i = 0; i < context._elementIds.length; i++) {
                                var childIdsJson = __cheerio_find(docId, context._elementIds[i], selector);
                                var childIds = JSON.parse(childIdsJson);
                                allIds = allIds.concat(childIds);
                            }
                            return createCheerioWrapperFromIds(docId, allIds);
                        }
                        return createCheerioWrapper(docId, selector);
                    };
                    $.html = function(el) {
                        if (el && el._elementIds && el._elementIds.length > 0) {
                            return __cheerio_html(docId, el._elementIds[0]);
                        }
                        return __cheerio_html(docId, '');
                    };
                    return $;
                }
            };

            function createCheerioWrapper(docId, selector) {
                var elementIds;
                if (typeof selector === 'string') {
                    var idsJson = __cheerio_select(docId, selector);
                    elementIds = JSON.parse(idsJson);
                } else {
                    elementIds = [];
                }
                return createCheerioWrapperFromIds(docId, elementIds);
            }

            function createCheerioWrapperFromIds(docId, ids) {
                var wrapper = {
                    _docId: docId,
                    _elementIds: ids,
                    length: ids.length,
                    each: function(callback) {
                        for (var i = 0; i < ids.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                            callback.call(elWrapper, i, elWrapper);
                        }
                        return wrapper;
                    },
                    find: function(sel) {
                        var allIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var childIdsJson = __cheerio_find(docId, ids[i], sel);
                            var childIds = JSON.parse(childIdsJson);
                            allIds = allIds.concat(childIds);
                        }
                        return createCheerioWrapperFromIds(docId, allIds);
                    },
                    text: function() {
                        if (ids.length === 0) return '';
                        return __cheerio_text(docId, ids.join(','));
                    },
                    html: function() {
                        if (ids.length === 0) return '';
                        return __cheerio_inner_html(docId, ids[0]);
                    },
                    attr: function(name) {
                        if (ids.length === 0) return undefined;
                        var val = __cheerio_attr(docId, ids[0], name);
                        return val === '__UNDEFINED__' ? undefined : val;
                    },
                    first: function() { return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[0]] : []); },
                    last: function() { return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[ids.length - 1]] : []); },
                    next: function() {
                        var nextIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var nextId = __cheerio_next(docId, ids[i]);
                            if (nextId && nextId !== '__NONE__') nextIds.push(nextId);
                        }
                        return createCheerioWrapperFromIds(docId, nextIds);
                    },
                    prev: function() {
                        var prevIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var prevId = __cheerio_prev(docId, ids[i]);
                            if (prevId && prevId !== '__NONE__') prevIds.push(prevId);
                        }
                        return createCheerioWrapperFromIds(docId, prevIds);
                    },
                    eq: function(index) {
                        if (index >= 0 && index < ids.length) return createCheerioWrapperFromIds(docId, [ids[index]]);
                        return createCheerioWrapperFromIds(docId, []);
                    },
                    get: function(index) {
                        if (typeof index === 'number') {
                            if (index >= 0 && index < ids.length) return createCheerioWrapperFromIds(docId, [ids[index]]);
                            return undefined;
                        }
                        return ids.map(function(id) { return createCheerioWrapperFromIds(docId, [id]); });
                    },
                    map: function(callback) {
                        var results = [];
                        for (var i = 0; i < ids.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                            var result = callback.call(elWrapper, i, elWrapper);
                            if (result !== undefined && result !== null) results.push(result);
                        }
                        return {
                            length: results.length,
                            get: function(index) { return typeof index === 'number' ? results[index] : results; },
                            toArray: function() { return results; }
                        };
                    },
                    filter: function(selectorOrCallback) {
                        if (typeof selectorOrCallback === 'function') {
                            var filteredIds = [];
                            for (var i = 0; i < ids.length; i++) {
                                var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                                var result = selectorOrCallback.call(elWrapper, i, elWrapper);
                                if (result) filteredIds.push(ids[i]);
                            }
                            return createCheerioWrapperFromIds(docId, filteredIds);
                        }
                        return wrapper;
                    },
                    children: function(sel) { return this.find(sel || '*'); },
                    parent: function() { return createCheerioWrapperFromIds(docId, []); },
                    toArray: function() { return ids.map(function(id) { return createCheerioWrapperFromIds(docId, [id]); }); }
                };
                return wrapper;
            }

            var require = function(moduleName) {
                if (moduleName === 'cheerio' || moduleName === 'cheerio-without-node-native' || moduleName === 'react-native-cheerio') {
                    return cheerio;
                }
                throw new Error("Module '" + moduleName + "' is not available");
            };

            if (!Array.prototype.flat) {
                Array.prototype.flat = function(depth) {
                    depth = depth === undefined ? 1 : Math.floor(depth);
                    if (depth < 1) return Array.prototype.slice.call(this);
                    return (function flatten(arr, d) {
                        return d > 0
                            ? arr.reduce(function(acc, val) { return acc.concat(Array.isArray(val) ? flatten(val, d - 1) : val); }, [])
                            : arr.slice();
                    })(this, depth);
                };
            }

            if (!Array.prototype.flatMap) {
                Array.prototype.flatMap = function(callback, thisArg) { return this.map(callback, thisArg).flat(); };
            }

            if (!Object.entries) {
                Object.entries = function(obj) {
                    var result = [];
                    for (var key in obj) {
                        if (obj.hasOwnProperty(key)) result.push([key, obj[key]]);
                    }
                    return result;
                };
            }

            if (!Object.fromEntries) {
                Object.fromEntries = function(entries) {
                    var result = {};
                    for (var i = 0; i < entries.length; i++) {
                        result[entries[i][0]] = entries[i][1];
                    }
                    return result;
                };
            }

            if (!String.prototype.replaceAll) {
                String.prototype.replaceAll = function(search, replace) {
                    if (search instanceof RegExp) {
                        if (!search.global) throw new TypeError('replaceAll must be called with a global RegExp');
                        return this.replace(search, replace);
                    }
                    return this.split(search).join(replace);
                };
            }
        """.trimIndent()
    }
}
