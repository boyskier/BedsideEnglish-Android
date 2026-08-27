package com.example.medvoicetrainer.api

/**
 * Normalized token usage from an analysis call, matching the shape
 * app/analysis/analysis_providers.py's `call_analysis` returns as its `usage` dict
 * ({"input_tokens", "output_tokens", "cached_tokens"} + a "model_used" the Python source also
 * carries for logging). Lets CostTracker.kt price a call without provider-specific branches.
 */
data class LlmUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val modelUsed: String = ""
)
