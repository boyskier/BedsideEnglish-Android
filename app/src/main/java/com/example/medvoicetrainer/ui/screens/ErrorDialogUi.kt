package com.example.medvoicetrainer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.medvoicetrainer.analysis.ErrorDialog
import com.example.medvoicetrainer.ui.LocalTranslate
import com.example.medvoicetrainer.ui.MainViewModel

/**
 * Compose UI for app/ui/error_dialog.py's friendly error dialog. Content/dedup logic lives in
 * ErrorDialog.kt; MainViewModel.handleUiException feeds this via the uiError StateFlow, the same
 * StateFlow-driven-overlay pattern PostSessionMomentDialogs.kt already uses.
 */
@Composable
fun ErrorDialogHost(viewModel: MainViewModel, onOpenPreferences: (provider: String?) -> Unit = {}) {
    val error by viewModel.uiError.collectAsStateWithLifecycle()
    error?.let { content ->
        ErrorDialogView(
            content,
            onDismiss = { viewModel.dismissUiError() },
            onOpenPreferences = {
                viewModel.dismissUiError()
                onOpenPreferences(content.provider)
            }
        )
    }
}

/**
 * §13 "Invalid / expired API key" — the generic error dialog would otherwise show the raw
 * exception with no obvious next step. When the failure text looks like an auth/key problem
 * (401/403/UNAUTHENTICATED/"API key"/etc., the shapes Gemini/OpenAI/Claude actually return for a
 * bad key), a friendlier headline replaces the generic one and a one-tap route to Preferences
 * is offered alongside "Continue" — never mid-session key entry, and the session's transcript is
 * already persisted regardless (see startSession's try/catch).
 */
private fun looksLikeApiKeyIssue(content: ErrorDialog.Content): Boolean {
    val haystack = (content.body + " " + content.detail)
    return listOf(
        "401", "403", "api key", "apikey", "unauthenticated", "unauthorized",
        "permission_denied", "invalid_api_key", "api_key_invalid"
    ).any { haystack.contains(it, ignoreCase = true) }
}

private fun providerDisplayName(provider: String?): String? = when (provider) {
    "gemini" -> "Gemini"
    "openai" -> "OpenAI"
    "claude" -> "Claude"
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ErrorDialogView(content: ErrorDialog.Content, onDismiss: () -> Unit, onOpenPreferences: () -> Unit = {}) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val t = LocalTranslate.current
    var copied by remember(content) { mutableStateOf(false) }
    val keyIssue = remember(content) { looksLikeApiKeyIssue(content) }
    val providerName = remember(content) { providerDisplayName(content.provider) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (keyIssue) {
                Button(onClick = onOpenPreferences) { Text(t("Open Preferences")) }
            } else {
                Button(onClick = onDismiss) { Text(t("Continue")) }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(content.detail))
                    copied = true
                }) {
                    Text(if (copied) t("Copied!") else t("Copy details"))
                }
                TextButton(onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${ErrorDialog.GITHUB_REPO}/issues/new"))
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        // No app can handle the link; silently ignore.
                    }
                }) {
                    Text(t("Report on GitHub"))
                }
            }
        },
        title = {
            Text(
                if (keyIssue) {
                    if (providerName != null) t("Check your %s API key").format(providerName) else t("Check your API key")
                } else content.headline,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                if (keyIssue) {
                    Text(
                        (if (providerName != null) {
                            t("This looks like an invalid or expired %s API key rather than a bug — your session content is already saved. Open Preferences to check the key.").format(providerName)
                        } else {
                            t("This looks like an invalid or expired API key rather than a bug — your session content is already saved. Open Preferences to check the key.")
                        }),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(content.body, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = content.detail,
                        modifier = Modifier
                            .padding(8.dp)
                            .heightIn(max = 140.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    )
}
