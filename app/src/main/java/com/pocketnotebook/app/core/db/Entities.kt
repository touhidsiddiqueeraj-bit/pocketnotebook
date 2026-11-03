package com.pocketnotebook.app.core.db

import kotlinx.serialization.Serializable

data class Notebook(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Source(
    val id: Long,
    val notebookId: Long,
    val type: String,      // pdf | docx | txt | md | html | url | pasted | transcript
    val title: String,
    val status: String,    // pending | processing | ready | failed
    val error: String?,
    val charCount: Int,
    val wordCount: Int,
    val chunkCount: Int,
    val summary: String?,  // map-reduce cache for digests / audio overviews
    val addedAt: Long,
)

data class ChunkRow(
    val id: Long,
    val sourceId: Long,
    val notebookId: Long,
    val ordinal: Int,
    val text: String,
)

data class ChatMessage(
    val id: Long,
    val notebookId: Long,
    val role: String,      // user | assistant
    val content: String,
    val cites: String?,    // JSON array of Citation
    val createdAt: Long,
)

data class Note(
    val id: Long,
    val notebookId: Long,
    val pinned: Boolean,
    val text: String,
    val createdAt: Long,
)

data class Artifact(
    val id: Long,
    val notebookId: Long,
    val kind: String,      // study_guide | briefing | faq | timeline | mindmap | flashcards | quiz | overview
    val title: String,
    val status: String,    // pending | running | ready | failed
    val payload: String?,
    val audioDir: String?,
    val error: String?,
    val createdAt: Long,
)

data class ModelRecord(
    val id: Long,
    val slot: String,      // chat | audio | embed
    val repoId: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val sha256: String?,
    val verified: Boolean,
    val addedAt: Long,
)

/** Provenance for a cited snippet — first-class, not a regex afterthought. */
@Serializable
data class Citation(
    val n: Int,
    val chunkId: Long,
    val sourceId: Long,
    val sourceTitle: String,
    val snippet: String,
)

object ArtifactKind {
    const val STUDY_GUIDE = "study_guide"
    const val BRIEFING = "briefing"
    const val FAQ = "faq"
    const val TIMELINE = "timeline"
    const val MINDMAP = "mindmap"
    const val FLASHCARDS = "flashcards"
    const val QUIZ = "quiz"
    const val OVERVIEW = "overview"
    val REPORTS = listOf(STUDY_GUIDE, BRIEFING, FAQ, TIMELINE)
    val ALL = listOf(STUDY_GUIDE, BRIEFING, FAQ, TIMELINE, MINDMAP, FLASHCARDS, QUIZ, OVERVIEW)
}

object ModelSlot {
    const val CHAT = "chat"
    const val AUDIO = "audio"
    const val EMBED = "embed"
}

object SourceStatus {
    const val PENDING = "pending"
    const val PROCESSING = "processing"
    const val READY = "ready"
    const val FAILED = "failed"
}

object ArtifactStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val READY = "ready"
    const val FAILED = "failed"
}
