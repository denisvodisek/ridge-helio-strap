package app.strap.api

import app.strap.pairing.ServerLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Reads from the server's API. Every call returns the parsed JSON or throws [ApiException]. */
class ApiClient(private val link: ServerLink) {
    suspend fun summary(day: LocalDate): JSONObject = get("/v1/day/$day/summary")

    suspend fun daySeries(day: LocalDate, metrics: String): JSONObject = get("/v1/day/$day/series?metrics=$metrics")

    suspend fun buckets(name: String, from: LocalDate, to: LocalDate, bucket: String): JSONObject =
        get("/v1/series/$name?from=$from&to=$to&bucket=$bucket")

    suspend fun daily(metrics: String, from: LocalDate, to: LocalDate): JSONObject = get("/v1/daily?metrics=$metrics&from=$from&to=$to")

    suspend fun workouts(from: LocalDate, to: LocalDate): JSONArray = JSONArray(request("GET", "/v1/workouts?from=$from&to=$to", null))

    suspend fun journal(from: LocalDate, to: LocalDate): JSONArray = JSONArray(request("GET", "/v1/journal?from=$from&to=$to", null))

    suspend fun hydration(day: LocalDate): JSONObject = get("/v1/journal/hydration?day=$day")

    suspend fun setHome(day: LocalDate, lat: Double, lon: Double): JSONObject =
        JSONObject(request("PUT", "/v1/journal/home?day=$day", JSONObject().put("lat", lat).put("lon", lon).toString()))

    suspend fun clearHome(day: LocalDate): JSONObject = JSONObject(request("DELETE", "/v1/journal/home?day=$day", null))

    suspend fun addJournal(entry: JSONObject): JSONObject = JSONObject(request("POST", "/v1/journal", entry.toString()))

    suspend fun updateJournal(id: String, entry: JSONObject): JSONObject = JSONObject(request("PUT", "/v1/journal/entry/$id", entry.toString()))

    suspend fun sessions(from: LocalDate, to: LocalDate): JSONArray = JSONArray(request("GET", "/v1/sessions?from=$from&to=$to", null))

    suspend fun addSession(session: JSONObject): JSONObject = JSONObject(request("POST", "/v1/sessions", session.toString()))

    suspend fun updateSession(id: String, patch: JSONObject): JSONObject = JSONObject(request("PUT", "/v1/sessions/$id", patch.toString())) // the server takes PUT: no PATCH here

    suspend fun deleteSession(id: String) {
        request("DELETE", "/v1/sessions/$id", null)
    }

    suspend fun suggestions(day: LocalDate): JSONArray = JSONArray(request("GET", "/v1/sessions/suggestions?day=$day", null))

    suspend fun dismissSuggestion(start: String) {
        request("POST", "/v1/sessions/suggestions/dismiss", JSONObject().put("start", start).toString())
    }

    /**
     * Ask your data, streamed: one JSON event per line (status while the model looks something
     * up, delta for each piece of the reply, then done or error), read as they arrive.
     */
    fun chatStream(messages: JSONArray): Flow<JSONObject> = flow {
        val conn = URL(link.baseUrl + "/v1/chat/stream").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 120_000 // between events; a slow tool round is the longest gap
            conn.setRequestProperty("Authorization", "Bearer ${link.token}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("messages", messages).toString().toByteArray(Charsets.UTF_8)) }
            when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.forEach { emit(JSONObject(it)) } }
                401 -> throw ApiException("The server refused the token.")
                else -> throw ApiException("The server answered HTTP $code.")
            }
        } catch (e: IOException) {
            throw ApiException("Could not reach the server.", e)
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** Ask your data: the server runs the model's tool calls, which can take a while. */
    suspend fun chat(messages: JSONArray): JSONObject =
        JSONObject(request("POST", "/v1/chat", JSONObject().put("messages", messages).toString(), readTimeoutMs = 120_000))

    suspend fun profile(): JSONObject = get("/v1/profile")

    suspend fun saveProfile(profile: JSONObject): JSONObject = JSONObject(request("PUT", "/v1/profile", profile.toString()))

    suspend fun deleteJournal(id: String) {
        request("DELETE", "/v1/journal/$id", null)
    }

    private suspend fun get(path: String): JSONObject = JSONObject(request("GET", path, null))

    private suspend fun request(method: String, path: String, body: String?, readTimeoutMs: Int = 60_000): String = withContext(Dispatchers.IO) {
        val conn = URL(link.baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = readTimeoutMs // a weight or profile change re-derives before answering
            conn.setRequestProperty("Authorization", "Bearer ${link.token}")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                401 -> throw ApiException("The server refused the token.")
                503 -> throw ApiException(runCatching { JSONObject(conn.errorStream.bufferedReader().readText()).getString("detail") }.getOrNull()
                    ?: "The server can't answer that right now.")
                else -> throw ApiException("The server answered HTTP $code.")
            }
        } catch (e: IOException) {
            throw ApiException("Could not reach the server.", e)
        } finally {
            conn.disconnect()
        }
    }
}

class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
