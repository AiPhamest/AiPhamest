// THIS FILE NAME Fuzzy.kt
package com.example.AiPhamest.llm

object Fuzzy {

    /** Returns [n] list entries with the smallest edit distance to [target]. */
    fun closest(target: String, list: List<String>, n: Int = 3): List<String> =
        list.asSequence()
            .map { it to distance(target.lowercase(), it.lowercase()) }
            .sortedBy { it.second }
            .take(n)
            .map { it.first }
            .toList()

    /* simple iterative Levenshtein */
    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val prev = IntArray(b.length + 1) { it } // 0..bLen
        val curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            System.arraycopy(curr, 0, prev, 0, prev.size)
        }
        return prev[b.length]
    }
}
