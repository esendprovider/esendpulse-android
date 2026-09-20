package com.esendpulse.sdk

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executor

/**
 * HTTP, and nothing else.
 *
 * [HttpURLConnection] with a key header and JSON in both directions. No
 * dependency: an analytics SDK that drags OkHttp into somebody's app is making
 * their build slower and their dependency graph riskier to save itself sixty
 * lines — and on a modern Android, `HttpURLConnection` *is* OkHttp underneath.
 *
 * Every request runs on [network], never on the SDK's worker thread. On iOS
 * `URLSession` is asynchronous and the serial queue is never blocked by a
 * request; here the call blocks the thread it is on, and putting it on the
 * worker would mean a slow inbox fetch delaying an event flush — or, worse, a
 * delayed in-app message that is waiting on that same thread to be shown.
 */
internal class Transport(
    private val key: String,
    private val host: String,
    private val network: Executor
) {

    fun getJson(
        path: String,
        query: Map<String, String>,
        completion: (Result<JSONObject>) -> Unit
    ) {
        val encoded = query.entries.joinToString("&") { (name, value) ->
            URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        val url = host + path + if (encoded.isEmpty()) "" else "?$encoded"
        run(url, "GET", null) { result ->
            completion(
                result.mapCatching { text ->
                    try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        throw TransportException.decoding(e)
                    }
                }
            )
        }
    }

    fun post(path: String, body: JSONObject, completion: (Result<String>) -> Unit) {
        run(host + path, "POST", body.toString(), completion)
    }

    private fun run(
        url: String,
        method: String,
        payload: String?,
        completion: (Result<String>) -> Unit
    ) {
        network.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("X-Api-Key", key)
                    setRequestProperty("Accept", "application/json")
                    if (payload != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                }
                if (payload != null) {
                    connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val ok = status in 200..299
                val stream = if (ok) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                completion(
                    if (ok) Result.success(text)
                    else Result.failure(TransportException.http(status, text))
                )
            } catch (e: java.net.MalformedURLException) {
                // Our own bug, not the network's: never retried.
                completion(Result.failure(TransportException.badUrl(url)))
            } catch (e: Exception) {
                completion(Result.failure(TransportException.network(e)))
            } finally {
                connection?.disconnect()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
    }
}

/** Why a call did not produce an answer, and whether asking again could help. */
class TransportException private constructor(
    val kind: Kind,
    /** The HTTP status, or 0 when the request never reached a server. */
    val status: Int,
    /** The response body, when there was one. */
    val body: String,
    override val cause: Throwable?
) : Exception("[esendpulse] $kind ${if (status > 0) status else ""} $body".trim(), cause) {

    enum class Kind {
        BAD_URL,
        HTTP,
        /** A fault below HTTP: no route, DNS, TLS, a timeout. */
        NETWORK,
        /** A body this SDK could not read as JSON. */
        DECODING
    }

    /**
     * Whether trying again could plausibly work.
     *
     * A 4xx is the request being wrong, and repeating it repeats the mistake
     * forever while everything behind it waits. A network fault or a 5xx is
     * the other end having a moment.
     */
    val isWorthRetrying: Boolean
        get() = when (kind) {
            Kind.HTTP -> status >= 500 || status == 429
            Kind.NETWORK -> true
            Kind.BAD_URL, Kind.DECODING -> false
        }

    internal companion object {
        fun http(status: Int, body: String) = TransportException(Kind.HTTP, status, body, null)
        fun network(cause: Throwable) = TransportException(Kind.NETWORK, 0, "", cause)
        fun decoding(cause: Throwable) = TransportException(Kind.DECODING, 0, "", cause)
        fun badUrl(url: String) = TransportException(Kind.BAD_URL, 0, url, null)
    }
}

/** Reads through the `Throwable` the callbacks carry. */
internal val Throwable.isWorthRetrying: Boolean
    get() = (this as? TransportException)?.isWorthRetrying ?: true
