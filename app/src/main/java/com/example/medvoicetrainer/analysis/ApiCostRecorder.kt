package com.example.medvoicetrainer.analysis

import android.content.Context
import com.example.medvoicetrainer.db.ApiUsageEventEntity
import com.example.medvoicetrainer.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Durable ledger for billable API calls that are not the main session analysis or Live socket. */
object ApiCostRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var database: AppDatabase? = null

    fun initialize(context: Context) {
        if (database == null) {
            synchronized(this) {
                if (database == null) database = AppDatabase.getDatabase(context.applicationContext)
            }
        }
    }

    fun record(
        provider: String,
        model: String,
        operation: String,
        inputTokens: Int,
        outputTokens: Int,
        costUsd: Double,
        estimated: Boolean,
    ) {
        val db = database ?: return
        scope.launch {
            db.appDao().insertApiUsageEvent(
                ApiUsageEventEntity(
                    createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                    provider = provider,
                    model = model,
                    operation = operation,
                    inputTokens = inputTokens.coerceAtLeast(0),
                    outputTokens = outputTokens.coerceAtLeast(0),
                    costUsd = costUsd.coerceAtLeast(0.0),
                    estimated = estimated,
                )
            )
        }
    }
}
