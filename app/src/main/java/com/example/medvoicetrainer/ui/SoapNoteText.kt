package com.example.medvoicetrainer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Applies the "Not elicited" emphasis to an already-formatted SOAP note.
 *
 * The colour is decided here, never by the model: the evaluator is told to emit the plain marker
 * text and no markup, so a provider cannot invent (or forget) the visual signal that separates
 * "the learner never asked" from "the patient answered no".
 */
internal fun annotateSoapNote(note: String, markerColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (range in notElicitedRanges(note)) {
        // Overlapping matches are impossible for this pattern, but a defensive skip keeps the
        // substring arithmetic total rather than throwing on an unexpected range.
        if (range.first < cursor) continue
        append(note.substring(cursor, range.first))
        withStyle(SpanStyle(color = markerColor, fontWeight = FontWeight.Bold)) {
            append(note.substring(range.first, range.last + 1))
        }
        cursor = range.last + 1
    }
    append(note.substring(cursor))
}

/** SOAP note body with every unasked-history marker rendered in the theme's error colour. */
@Composable
internal fun SoapNoteText(note: String, modifier: Modifier = Modifier) {
    Text(annotateSoapNote(note, MaterialTheme.colorScheme.error), modifier = modifier)
}
