package com.pocketnotebook.app.core.hf

import com.pocketnotebook.app.core.db.ModelSlot

/**
 * Curated catalog — every entry was verified against the HF API at commit
 * time (repo id, file name, exact size). The manager also accepts ANY HF
 * GGUF repo via search, so nothing is locked to these picks.
 */
object ModelCatalog {

    data class Entry(
        val id: String,
        val title: String,
        val blurb: String,
        val repoId: String,
        val fileName: String,
        val sizeBytes: Long,
        val slot: String,
        val license: String,
        val verified: Boolean,
        val experimental: Boolean = false,
        val minRamGb: Int = 6,
    )

    val entries = listOf(
        Entry(
            id = "lfm3-1.7b-qadq4",
            title = "LFM3-1.7B · QAD-Q4_0",
            blurb = "Default brain. Fastest in-class on-device text model, IFEval 96.66, 256K context. ~1.0 GB.",
            repoId = "LiquidAI/LFM3-1.7B-GGUF",
            fileName = "LFM3-1.7B-QAD-Q4_0.gguf",
            sizeBytes = 1_049_615_680L,
            slot = ModelSlot.CHAT,
            license = "LFM Open License v1.0",
            verified = true,
        ),
        Entry(
            id = "lfm3-1.7b-q4km",
            title = "LFM3-1.7B · Q4_K_M",
            blurb = "Same brain, Q4_K_M quant — slightly higher fidelity, a bit slower on ARM.",
            repoId = "LiquidAI/LFM3-1.7B-GGUF",
            fileName = "LFM3-1.7B-Q4_K_M.gguf",
            sizeBytes = 1_099_627_808L,
            slot = ModelSlot.CHAT,
            license = "LFM Open License v1.0",
            verified = true,
        ),
        Entry(
            id = "bge-small-q8",
            title = "bge-small-en-v1.5 · Q8_0",
            blurb = "Embeddings (384-d) for semantic retrieval. Tiny — 35 MB. Install with the brain.",
            repoId = "CompendiumLabs/bge-small-en-v1.5-gguf",
            fileName = "bge-small-en-v1.5-q8_0.gguf",
            sizeBytes = 36_806_944L,
            slot = ModelSlot.EMBED,
            license = "MIT",
            verified = true,
            minRamGb = 4,
        ),
        Entry(
            id = "lfm3-audio-2b-q4",
            title = "LFM3-Audio-2B · Q4_0",
            blurb = "On-device speech (ASR + TTS). The app pre-downloads it ahead of the Phase 0 native wiring; voice features activate after that lands.",
            repoId = "LiquidAI/LFM3-Audio-2B-GGUF",
            fileName = "LFM3-Audio-2B-Q4_0.gguf",
            sizeBytes = 1_049_613_856L,
            slot = ModelSlot.AUDIO,
            license = "LFM Open License v1.0",
            verified = true,
            experimental = true,
        ),
    )

    fun byId(id: String): Entry? = entries.firstOrNull { it.id == id }
}
