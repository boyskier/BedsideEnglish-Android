package com.example.medvoicetrainer.voice

/**
 * Short-lived in-memory cache for Listening Lab's cloud-synthesized narration clips. Regenerating
 * the same line costs a real TTS API call, but "Play again"/"Ask to repeat" always want the exact
 * same audio the learner just heard — so a synthesis result is kept for [TTL_MILLIS] and reused by
 * every replay within that window instead of calling the API again. Entries older than the TTL (or
 * beyond [MAX_ENTRIES]) are dropped lazily on the next access; there is no background sweep, since
 * the cache is small and short-lived by design.
 */
object ListeningAudioCache {
    private const val TTL_MILLIS = 6 * 60 * 1000L
    private const val MAX_ENTRIES = 24

    data class Key(val drillId: String, val accent: String, val genderPreference: String)
    data class Clip(val pcm: ByteArray, val sampleRate: Int, val voiceName: String)

    private data class Entry(val clip: Clip, val expiresAt: Long)

    private val entries = LinkedHashMap<Key, Entry>()

    @Synchronized
    fun get(key: Key, now: Long = System.currentTimeMillis()): Clip? {
        val entry = entries[key] ?: return null
        if (now > entry.expiresAt) {
            entries.remove(key)
            return null
        }
        return entry.clip
    }

    @Synchronized
    fun put(key: Key, clip: Clip, now: Long = System.currentTimeMillis()) {
        entries.entries.removeAll { now > it.value.expiresAt }
        while (entries.size >= MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
        entries[key] = Entry(clip, now + TTL_MILLIS)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
