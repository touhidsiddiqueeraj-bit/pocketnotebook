package com.pocketnotebook.app.core.llm

import com.pocketnotebook.app.core.db.ChatRepo
import com.pocketnotebook.app.core.db.Citation
import com.pocketnotebook.app.core.db.NotebookRepo
import com.pocketnotebook.app.core.engine.LlmRuntime
import com.pocketnotebook.app.core.retrieval.Retriever
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Grounded chat: hybrid retrieve → numbered-snippet prompt → repair citations. */
class ChatService(
    private val retriever: Retriever,
    private val chatRepo: ChatRepo,
    private val notebookRepo: NotebookRepo,
    private val runtime: LlmRuntime,
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class AskResult(
        val answer: String,
        val citations: List<Citation>,
        val retrievalPresent: Boolean,
    )

    suspend fun ask(
        notebookId: Long,
        question: String,
        onToken: (String) -> Unit,
    ): AskResult {
        val hits = retriever.retrieve(notebookId, question)
        val context = Retriever.formatContext(hits)
        val history = chatRepo.recent(notebookId, limit = 6)
            .filter { it.role == "user" || it.role == "assistant" }

        val roles = ArrayList<String>()
        val contents = ArrayList<String>()
        roles += "system"
        contents += Prompts.chatSystem(hits.size.coerceAtLeast(1))
        for (m in history) {
            roles += m.role
            contents += m.content
        }
        roles += "user"
        contents += Prompts.chatUser(question, context, hits.isNotEmpty())

        // Keep the prompt inside the context window: drop oldest history while too big.
        val budgetTokens = runtimeChatBudget()
        var prompt = runtime.chatTemplate(roles, contents)
        while (prompt.isNotEmpty() && runtime.tokenCount(prompt) > budgetTokens && roles.size > 3) {
            roles.removeAt(1) // keep system
            roles.removeAt(1)
            contents.removeAt(1)
            contents.removeAt(1)
            prompt = runtime.chatTemplate(roles, contents)
        }

        chatRepo.add(notebookId, "user", question, null)

        val raw = runtime.complete(
            prompt = prompt,
            temperature = 0.3f,
            maxTokens = 700,
            onToken = onToken,
        )
        if (raw.isBlank()) {
            // Never persist an empty "answer" (it used to become a bare
            // "Sources: [1] [2]…" bubble). Surface the reason instead.
            throw IllegalStateException(
                runtime.state.value.chatError ?: "model produced no output",
            )
        }

        val cleaned = CitationRepair.stripInvalid(raw.trim(), hits.size)
        val finalText = CitationRepair.ensureFooter(cleaned, hits.size, hits.isNotEmpty())
        val citations = Retriever.citations(hits)
        chatRepo.add(notebookId, "assistant", finalText, json.encodeToString(citations))
        notebookRepo.touch(notebookId)

        return AskResult(finalText, citations, hits.isNotEmpty())
    }

    private suspend fun runtimeChatBudget(): Int {
        val ctx = runtime.state.value.chatMeta?.nCtxTrain ?: 8192
        return (ctx.coerceAtMost(16384)) - 900
    }
}
