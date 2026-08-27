package com.example.medvoicetrainer.ui.theme

import androidx.compose.ui.graphics.Color

// Palette locked to the design spec (docs/design/android-ui-spec.html §0).
val PrimaryBlue = Color(0xFF1E40AF)
val PrimaryDark = Color(0xFF1E3A8A)
val PrimaryBright = Color(0xFF2563EB)
val BrandTint = Color(0xFFDDE6FB) // primaryContainer — action-zone / hero cards
val AccentAmber = Color(0xFFF59E0B)
val AccentAmberHover = Color(0xFFD97706)
val OnAccentInk = Color(0xFF1C1917) // required text-on-amber; never white on amber (fails contrast)
val SuccessGreen = Color(0xFF16A34A)
val SuccessGreenStrong = Color(0xFF15803D) // "aim" corrections, positive deltas
val DangerRed = Color(0xFFEF4444)
val DangerRedStrong = Color(0xFFDC2626) // SRS due, recording state, destructive actions
val DangerContainer = Color(0xFFFEE2E2)
val InfoBg = Color(0xFFEFF6FF)
val InfoInk = Color(0xFF1D4ED8)

val LightBackground = Color(0xFFF9FAFB)
val LightSurface = Color(0xFFFFFFFF)
val TextDark = Color(0xFF111827)
val TextMuted = Color(0xFF4B5563)
val TextMutedFloor = Color(0xFF6B7280) // floor for text on #F9FAFB (4.6:1)
val TextSubtle = Color(0xFF9CA3AF) // decoration only, never body text
val BorderGray = Color(0xFFE5E7EB)

// App dark theme (spec §0 "App dark theme" row) — normative, not per-screen invention.
val DarkBackground = Color(0xFF0F1420)
val DarkSurface = Color(0xFF1A2130)
val DarkPrimary = Color(0xFF93B4FF) // brand-bright text-on-dark, 8.2:1
val DarkBrandTint = Color(0x382563EB) // rgba(37,99,235,.22) container with light ink
val TextLight = Color(0xFFEAEEF5)
val TextMutedDark = Color(0xFF98A2B3)
val TextSubtleDark = Color(0xFF6B7280)
val BorderDark = Color(0xFF232C39)
