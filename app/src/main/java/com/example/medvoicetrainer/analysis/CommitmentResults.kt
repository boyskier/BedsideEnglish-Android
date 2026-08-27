package com.example.medvoicetrainer.analysis

/**
 * Port of the pure selection half of app/analysis/feedback_engine.py's
 * `_record_commitment_results` — the anti-hallucination guard around debrief
 * commitment verdicts.
 *
 * The evaluator LLM is asked to report a `commitment_results` array (kept/missed/
 * not_applicable per open commitment). Python only accepts ids that were *actually
 * sent to the evaluator this session* (`valid_ids = {c["id"] for c in
 * open_commitments}`), so a hallucinated or stale id can never touch another
 * commitment's streak. The persistence itself (`record_commitment_check`, which
 * normalizes/validates the result string and updates the kept-streak/graduation)
 * lives in `Repository.recordCommitmentCheck` — this object is just the id filter,
 * kept as a pure, unit-testable function.
 *
 * Not golden-vector mapped: the Python counterpart is DB-coupled (calls
 * `record_commitment_check` inline), so there is no pure `(dict) -> dict` Python
 * function to freeze — covered by `CommitmentResultsTest` instead. See
 * MIGRATION_MASTER.md's "DB-coupled" exclusion note.
 */
object CommitmentResults {

    /**
     * Filter a parsed `commitment_results` array to the (id, rawResult) pairs whose
     * id was among [validIds] (the open commitments actually shown to the evaluator).
     * Order is preserved. The raw result string is passed through unchanged — the
     * caller hands it to [com.example.medvoicetrainer.db.Repository.recordCommitmentCheck],
     * which does the normalize/validate exactly like Python's `record_commitment_check`.
     */
    fun plannedChecks(
        commitmentResults: List<Map<String, Any?>>,
        validIds: Set<Int>,
    ): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        for (item in commitmentResults) {
            val id = (item["id"] as? Number)?.toInt() ?: continue
            if (id !in validIds) continue
            val result = (item["result"] as? String) ?: ""
            out.add(id to result)
        }
        return out
    }
}
