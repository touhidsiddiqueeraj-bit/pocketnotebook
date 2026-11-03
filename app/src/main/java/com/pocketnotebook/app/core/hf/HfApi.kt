package com.pocketnotebook.app.core.hf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Hugging Face Hub client (public API, no token needed for open repos).
 * Users can point the model manager at ANY GGUF repo — verified catalog
 * entries are just curated conveniences.
 */
class HfApi(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ModelSearchHit(
        val id: String,
        val likes: Int = 0,
        val downloads: Int = 0,
        val pipeline_tag: String? = null,
    )

    @Serializable
    data class ModelInfo(
        val id: String,
        val likes: Int = 0,
        val downloads: Int = 0,
        val siblings: List<Sibling> = emptyList(),
        val cardData: CardData? = null,
    ) {
        @Serializable
        data class Sibling(val rfilename: String)

        @Serializable
        data class CardData(val license: String? = null)
    }

    fun resolveUrl(repoId: String, fileName: String, revision: String = "main") =
        "https://huggingface.co/$repoId/resolve/$revision/$fileName"

    /** HEAD request → exact file size (Content-Length), -1 if unknown. */
    fun fileSize(repoId: String, fileName: String): Long = try {
        val resp = client.newCall(
            Request.Builder()
                .head()
                .url(resolveUrl(repoId, fileName))
                .header("User-Agent", "PocketNotebook/0.1")
                .build(),
        ).execute()
        resp.use {
            if (it.isSuccessful) it.header("Content-Length")?.toLongOrNull() ?: -1 else -1
        }
    } catch (_: Throwable) {
        -1
    }

    suspend fun search(query: String): List<ModelSearchHit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://huggingface.co/api/models?search=${enc(query)}&filter=gguf&limit=30&sort=downloads&direction=-1"
        runCatchingApi(url) { body ->
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(ModelSearchHit.serializer()),
                body,
            )
        }.getOrElse { emptyList() }
    }

    suspend fun modelInfo(repoId: String): ModelInfo? = withContext(Dispatchers.IO) {
        val url = "https://huggingface.co/api/models/${enc(repoId)}"
        runCatchingApi(url) { body ->
            json.decodeFromString(ModelInfo.serializer(), body)
        }.getOrNull()
    }

    fun ggufFiles(info: ModelInfo): List<String> =
        info.siblings.map { it.rfilename }
            .filter { it.endsWith(".gguf", ignoreCase = true) }
            .sorted()

    private fun <T> runCatchingApi(
        url: String,
        parse: (String) -> T,
    ): Result<T> = try {
        val resp = client.newCall(Request.Builder().url(url).header("User-Agent", "PocketNotebook/0.1").build()).execute()
        resp.use {
            if (it.isSuccessful) {
                Result.success(parse(it.body?.string() ?: ""))
            } else Result.failure(IllegalStateException("HTTP ${it.code}"))
        }
    } catch (t: Throwable) {
        Result.failure(t)
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
