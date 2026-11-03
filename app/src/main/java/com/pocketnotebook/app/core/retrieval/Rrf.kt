package com.pocketnotebook.app.core.retrieval

import kotlin.math.sqrt

/** Reciprocal Rank Fusion + vector math — pure functions, unit-tested. */
object Rrf {

    const val K = 60

    /**
     * Fuses ranked id lists (best first) into a single ranking.
     * score(id) = Σ 1/(k + rank_i), k = [K]. Ties break toward the first list
     * that mentioned the id (BM25, since lexical hits usually matter more).
     */
    fun fuse(rankLists: List<List<Long>>, k: Int = K): List<Pair<Long, Double>> {
        data class Acc(var score: Double, var firstRank: Int)
        val acc = HashMap<Long, Acc>()
        for (list in rankLists) {
            list.forEachIndexed { rank, id ->
                val a = acc.getOrPut(id) { Acc(0.0, Int.MAX_VALUE) }
                a.score += 1.0 / (k + rank + 1)
                if (a.firstRank == Int.MAX_VALUE) a.firstRank = rank
            }
        }
        return acc.entries
            .map { it.key to it.value.score }
            .sortedWith(compareByDescending<Pair<Long, Double>> { it.second }.thenBy { it.first })
    }
}

object Vectors {

    fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0f) 0f else dot / d
    }

    fun normalize(a: FloatArray): FloatArray {
        var norm = 0f
        for (v in a) norm += v * v
        norm = sqrt(norm)
        if (norm == 0f) return a
        val out = FloatArray(a.size)
        for (i in a.indices) out[i] = a[i] / norm
        return out
    }
}
