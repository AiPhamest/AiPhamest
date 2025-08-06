// THIS FILE NAME DrugNormalizer.kt
package com.example.AiPhamest.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Normalises drug names using:
 *   • local fuzzy-match to shortlist candidates (no full list in prompt)
 *   • deterministic LLM choice among those candidates
 */
object DrugNormalizer {

    /* ───────────────────────── loads plain-text drug list ──────────────────── */

    suspend fun loadDrugList(
        ctx: Context,
        assetPath: String = "drug.txt"
    ): List<String> = withContext(Dispatchers.IO) {
        ctx.assets.open(assetPath).bufferedReader().useLines { seq ->
            seq.map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
    }

    /* ─────────────────────────- public API -───────────────────────────────── */

    suspend fun normalizeWithLLM(
        ctx: Context,
        extractedLines: String,
        drugs: List<String>,
        topN: Int = 3
    ): String {
        if (extractedLines.isBlank()) return ""
        val inputs = extractedLines.lines().filter { it.isNotBlank() }
        if (inputs.isEmpty()) return ""

        // 1. build (input line → shortlist) map
        val shortlist = inputs.associateWith { line ->
            val noisy = line.takeWhile { !it.isWhitespace() }
            Fuzzy.closest(noisy, drugs, n = topN)
        }

        // 2. craft tight, few-shot prompt
        val prompt = buildPrompt(shortlist)

        // 3. deterministic LLM call
        val raw = PrescriptionExtractor.textOnlyExtract(ctx, prompt).trim()

        // 4. TSV lines "orig | corrected". Parse + replace.
        val replacements = raw.lines()
            .mapNotNull { l ->
                val parts = l.split('\t')
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()

        // 5. Apply replacements
        val corrected = inputs.joinToString("\n") { line ->
            replacements[line] ?: line
        }
        return corrected
    }

    /* ───────────────────────── helpers ────────────────────────────────────── */

    private fun buildPrompt(shortlist: Map<String, List<String>>): String {
        val shot = """
<l>Paracitamol 500 mg TID</l>      # candidates: Paracetamol, Panadol, Propranolol
<l_correct>Paracetamol 500 mg TID</l_correct>

""".trimIndent()

        val body = shortlist.entries.joinToString("\n") { (line, cands) ->
            "<l>$line</l>\t# candidates: ${cands.joinToString()}"
        }

        return """
You are a clinical assistant who ONLY corrects drug names.

For every <l>…</l> line choose the SINGLE closest name from the candidate list and output a tab-separated line:
<l>original</l>	<l_correct>corrected</l_correct>

Do NOT change strength/dose/frequency text.
End your answer with the token ###END###.

$shot
$body

###END###
""".trimIndent()
    }
}
