package com.pocketnotebook.app.core.study

/**
 * SM-2 spaced repetition (SuperMemo-2). Grades: 0 Again, 1 Hard, 2 Good, 3 Easy.
 * Pure functions — unit-tested.
 */
object Sm2 {

    data class Card(
        val ease: Float = 2.5f,
        val intervalDays: Float = 0f,
        val reps: Int = 0,
        val lapses: Int = 0,
    )

    /** Returns the updated card. Interval is in days (0 = due again today). */
    fun review(card: Card, grade: Int, nowMs: Long): Card {
        val g = grade.coerceIn(0, 3)
        if (g == 0) {
            // Lapse: re-learn immediately, soften the ease.
            val ease = (card.ease - 0.2f).coerceAtLeast(1.3f)
            return card.copy(ease = ease, intervalDays = 0f, reps = 0, lapses = card.lapses + 1)
        }

        val easeDelta = when (g) {
            1 -> -0.15f
            2 -> 0f
            else -> 0.1f
        }
        val ease = (card.ease + easeDelta).coerceAtLeast(1.3f)

        val newInterval = when {
            card.reps == 0 -> if (g == 3) 2f else 1f
            card.reps == 1 -> if (g == 3) 6f else 3f
            else -> card.intervalDays * ease
        }

        return card.copy(
            ease = ease,
            intervalDays = newInterval,
            reps = card.reps + 1,
        )
    }

    fun dueAtMs(card: Card, nowMs: Long): Long = nowMs + (card.intervalDays * 24 * 3600 * 1000).toLong()
}
