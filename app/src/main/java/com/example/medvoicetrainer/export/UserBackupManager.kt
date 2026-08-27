package com.example.medvoicetrainer.export

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.medvoicetrainer.BuildConfig
import com.example.medvoicetrainer.db.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalSerializationApi::class)
class UserBackupManager(
    context: Context,
    private val repository: Repository,
) {
    private val appContext = context.applicationContext
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** One learner-audio file with its size read exactly once (each `length()` is a stat syscall). */
    private class AudioFile(val file: File, val bytes: Long)

    /**
     * Rate-limits progress callbacks. A backup can hold thousands of clips, and one state emission
     * per clip would recompose the dialog far more often than a person can read it; the final
     * counter still lands because callers report the terminal stage unconditionally.
     */
    private class ProgressThrottle(private val report: (UserBackupProgress) -> Unit) {
        private var lastReportedAt = 0L

        fun offer(progress: UserBackupProgress) {
            val now = System.currentTimeMillis()
            if (now - lastReportedAt < MIN_INTERVAL_MS) return
            lastReportedAt = now
            report(progress)
        }

        fun force(progress: UserBackupProgress) {
            lastReportedAt = System.currentTimeMillis()
            report(progress)
        }

        private companion object {
            const val MIN_INTERVAL_MS = 200L
        }
    }

    suspend fun exportTo(
        uri: Uri,
        password: String,
        includeAudio: Boolean,
        onProgress: (UserBackupProgress) -> Unit = {},
    ): UserBackupSummary = withContext(Dispatchers.IO) {
        requirePassword(password)
        val reporter = ProgressThrottle(onProgress)
        reporter.force(UserBackupProgress(UserBackupStage.PREPARING))

        var payload = repository.createUserBackupPayload(
            exportedAt = OffsetDateTime.now().toString(),
            appVersion = BuildConfig.VERSION_NAME,
            includesLearnerAudio = includeAudio,
        )
        if (!includeAudio) {
            payload = payload.copy(sessions = payload.sessions.map { session ->
                session.copy(rawTranscript = removeAudioReferences(session.rawTranscript))
            })
        }

        val audioFiles = if (includeAudio) learnerAudioFiles() else emptyList()
        val totalAudioBytes = audioFiles.sumOf { it.bytes }
        if (audioFiles.size > MAX_AUDIO_FILES || totalAudioBytes > MAX_TOTAL_AUDIO_BYTES ||
            audioFiles.any { it.bytes > MAX_AUDIO_FILE_BYTES }
        ) {
            throw UserBackupException(
                UserBackupFailure.TOO_MUCH_AUDIO,
                "Saved audio is too large to back up",
            )
        }

        var completed = false
        var destinationOpened = false
        try {
            val output = openTruncatingOutput(uri)
            destinationOpened = true
            output.use { destination ->
                BackupCrypto.encrypt(password.toCharArray(), destination) { encrypted ->
                    ZipOutputStream(encrypted).use { zip ->
                        reporter.force(
                            UserBackupProgress(UserBackupStage.WRITING_DATA, totalFiles = audioFiles.size)
                        )
                        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                        zip.putNextEntry(ZipEntry(DATA_ENTRY))
                        // Streamed rather than encodeToString(): the string form is UTF-16 and the
                        // byte array a third copy, so a large history used to need several times its
                        // own size in heap on a process with no largeHeap.
                        json.encodeToStream(
                            payload,
                            BoundedOutputStream(zip, MAX_JSON_BYTES, UserBackupFailure.TOO_LARGE),
                        )
                        zip.closeEntry()

                        // Learner audio is already-compressed AAC/M4A (LearnerAudioStore). Deflating
                        // it burns the whole CPU budget of the export for no size win.
                        zip.setLevel(Deflater.NO_COMPRESSION)
                        val root = learnerAudioRoot()
                        var filesDone = 0
                        var bytesDone = 0L
                        audioFiles.forEach { audio ->
                            val relative = audio.file.relativeTo(root).invariantSeparatorsPath
                            zip.putNextEntry(ZipEntry("$AUDIO_PREFIX$relative"))
                            audio.file.inputStream().use { it.copyTo(zip, COPY_BUFFER_BYTES) }
                            zip.closeEntry()
                            filesDone += 1
                            bytesDone += audio.bytes
                            val step = UserBackupProgress(
                                stage = UserBackupStage.WRITING_AUDIO,
                                completedFiles = filesDone,
                                totalFiles = audioFiles.size,
                                completedBytes = bytesDone,
                                totalBytes = totalAudioBytes,
                            )
                            if (filesDone == audioFiles.size) reporter.force(step) else reporter.offer(step)
                        }

                        // Last entry, so a stream that stopped early can never look complete. See
                        // UserBackupPayload.TRAILER_FORMAT_VERSION.
                        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                        zip.putNextEntry(ZipEntry(TRAILER_ENTRY))
                        zip.write(TRAILER_MARKER.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            }
            completed = true
            UserBackupSummary(
                sessionCount = payload.sessions.size,
                errorItemCount = payload.errorItems.size,
                listeningAttemptCount = payload.listeningAttempts.size,
                commitmentCount = payload.commitments.size,
                audioFileCount = audioFiles.size,
            )
        } catch (cancelled: CancellationException) {
            // The write itself is blocking and has already run to completion, so the file on disk is
            // valid; only the result delivery was cancelled. Leave it alone.
            throw cancelled
        } catch (error: Throwable) {
            // Whatever the picker created is now empty or half-written. Removing it stops a learner
            // from keeping a .bsebackup that looks real and cannot be restored.
            if (!completed && destinationOpened) {
                runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
            }
            throw error
        }
    }

    /**
     * Save a new backup directly in the user's Downloads collection.  MediaStore gives the app a
     * write URI without broad storage permission; keeping the item pending prevents a half-written
     * encrypted file from appearing in file managers until [exportTo] has completed successfully.
     *
     * Android 10 introduced this scoped-storage API. Older devices keep the explicit location
     * picker, where the provider grants an equivalent write URI.
     */
    suspend fun exportToDownloads(
        password: String,
        includeAudio: Boolean,
        onProgress: (UserBackupProgress) -> Unit = {},
    ): UserBackupSummary = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UserBackupException(
                UserBackupFailure.UNKNOWN,
                "Automatic Downloads backups require Android 10 or later",
            )
        }

        val resolver = appContext.contentResolver
        val destination = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, defaultFileName())
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: throw UserBackupException(
            UserBackupFailure.UNKNOWN,
            "Could not create a backup file in Downloads",
        )

        try {
            val summary = exportTo(destination, password, includeAudio, onProgress)
            val published = resolver.update(
                destination,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            if (published != 1) {
                throw UserBackupException(
                    UserBackupFailure.UNKNOWN,
                    "Could not publish the completed backup in Downloads",
                )
            }
            summary
        } catch (cancelled: CancellationException) {
            // There is no completion result to show, so do not leave an incomplete or invisible
            // backup behind if the process/job is stopped while this operation is in flight.
            runCatching { resolver.delete(destination, null, null) }
            throw cancelled
        } catch (error: Throwable) {
            // exportTo already attempts deletion for a picker URI. MediaStore items are not
            // DocumentsContract documents, so always remove the pending item here as well.
            runCatching { resolver.delete(destination, null, null) }
            throw error
        }
    }

    suspend fun restoreFrom(
        uri: Uri,
        password: String,
        onProgress: (UserBackupProgress) -> Unit = {},
    ): UserBackupSummary = withContext(Dispatchers.IO) {
        requirePassword(password)
        val reporter = ProgressThrottle(onProgress)
        reporter.force(UserBackupProgress(UserBackupStage.PREPARING))
        requireRestoreSpace(uri)

        val stagingRoot = File(appContext.cacheDir, "backup_restore_${UUID.randomUUID()}")
        val stagedAudio = File(stagingRoot, "audio")
        if (!stagingRoot.mkdirs()) {
            throw UserBackupException(UserBackupFailure.UNKNOWN, "Could not prepare backup restore")
        }
        try {
            var payload: UserBackupPayload? = null
            var audioFileCount = 0
            var expandedBytes = 0L
            var sawTrailer = false
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw UserBackupException(
                    UserBackupFailure.UNKNOWN,
                    "Could not open the selected backup file",
                )
            input.use { source ->
                BackupCrypto.decrypt(source, password.toCharArray()) { decrypted ->
                    // Do not close this wrapper: BackupCrypto must keep reading the underlying
                    // GCM stream to EOF after ZIP parsing so authentication is finalized.
                    val zip = ZipInputStream(decrypted)
                    val seenEntries = mutableSetOf<String>()
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        if (!seenEntries.add(name)) {
                            throw UserBackupException(UserBackupFailure.DAMAGED, "Duplicate backup entry")
                        }
                        if (entry.isDirectory && !name.startsWith(AUDIO_PREFIX)) {
                            throw UserBackupException(UserBackupFailure.DAMAGED, "Invalid backup entry")
                        }
                        when {
                            name == DATA_ENTRY -> {
                                reporter.force(UserBackupProgress(UserBackupStage.READING_DATA))
                                payload = json.decodeFromStream<UserBackupPayload>(
                                    BoundedInputStream(zip, MAX_JSON_BYTES, UserBackupFailure.TOO_LARGE)
                                )
                            }

                            name == TRAILER_ENTRY -> sawTrailer = true

                            name.startsWith(AUDIO_PREFIX) && !entry.isDirectory -> {
                                val relative = name.removePrefix(AUDIO_PREFIX)
                                val target = safeAudioTarget(stagedAudio, relative)
                                target.parentFile?.mkdirs()
                                target.outputStream().use { output ->
                                    val copied = copyBounded(zip, output, MAX_AUDIO_FILE_BYTES)
                                    expandedBytes += copied
                                    if (expandedBytes > MAX_TOTAL_AUDIO_BYTES) {
                                        throw UserBackupException(
                                            UserBackupFailure.TOO_MUCH_AUDIO,
                                            "Backup audio is too large",
                                        )
                                    }
                                }
                                audioFileCount += 1
                                if (audioFileCount > MAX_AUDIO_FILES) {
                                    throw UserBackupException(
                                        UserBackupFailure.TOO_MUCH_AUDIO,
                                        "Backup contains too many audio files",
                                    )
                                }
                                reporter.offer(
                                    UserBackupProgress(
                                        stage = UserBackupStage.READING_AUDIO,
                                        completedFiles = audioFileCount,
                                        completedBytes = expandedBytes,
                                    )
                                )
                            }

                            entry.isDirectory -> Unit
                            else -> throw UserBackupException(
                                UserBackupFailure.NEWER_VERSION,
                                "Unknown backup entry",
                            )
                        }
                        zip.closeEntry()
                    }
                }
            }
            val restored = payload
                ?: throw UserBackupException(UserBackupFailure.DAMAGED, "Backup data is missing")
            if (restored.formatVersion > UserBackupPayload.CURRENT_FORMAT_VERSION) {
                throw UserBackupException(
                    UserBackupFailure.NEWER_VERSION,
                    "This backup was created by a newer app version",
                )
            }
            if (restored.formatVersion < 1) {
                throw UserBackupException(UserBackupFailure.DAMAGED, "Unsupported backup format")
            }
            if (restored.formatVersion >= UserBackupPayload.TRAILER_FORMAT_VERSION && !sawTrailer) {
                throw UserBackupException(
                    UserBackupFailure.DAMAGED,
                    "This backup file is incomplete and cannot be restored",
                )
            }
            if (!restored.includesLearnerAudio && audioFileCount > 0) {
                throw UserBackupException(
                    UserBackupFailure.DAMAGED,
                    "Backup audio metadata is inconsistent",
                )
            }
            reporter.force(UserBackupProgress(UserBackupStage.APPLYING))
            replaceAudioAndData(stagedAudio, restored)
            UserBackupSummary(
                sessionCount = restored.sessions.size,
                errorItemCount = restored.errorItems.size,
                listeningAttemptCount = restored.listeningAttempts.size,
                commitmentCount = restored.commitments.size,
                audioFileCount = audioFileCount,
            )
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    /**
     * Open the destination in truncating mode.
     *
     * Plain `"w"` is not required to truncate. Overwriting a longer previous backup that way leaves
     * its tail bytes past the new ciphertext, and GCM then reads that tail as part of the message —
     * the file decrypts to an authentication failure and the backup is unrecoverable. Try both
     * documented truncating modes, but reject providers that implement neither rather than claim a
     * successful export whose ciphertext still has the previous file's tail.
     */
    private fun openTruncatingOutput(uri: Uri): OutputStream {
        val resolver = appContext.contentResolver
        var lastError: Throwable? = null
        // Both modes promise truncation. Never fall back to plain "w": providers are allowed to
        // preserve the old tail in that mode, which produces a file that export reports as valid
        // but AES-GCM will reject when it is restored.
        for (mode in TRUNCATING_WRITE_MODES) {
            try {
                resolver.openOutputStream(uri, mode)?.let { return it }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
            }
        }
        throw UserBackupException(
            UserBackupFailure.UNKNOWN,
            "The selected storage provider cannot safely replace this backup file",
            lastError,
        )
    }

    private fun requirePassword(password: String) {
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw UserBackupException(
                UserBackupFailure.WEAK_PASSWORD,
                "Backup password must contain at least $MIN_PASSWORD_LENGTH characters",
            )
        }
    }

    private fun defaultFileName(): String =
        "bedside-english-${java.time.LocalDate.now()}.$FILE_EXTENSION"

    /**
     * Refuse a restore that plainly cannot fit before unpacking anything.
     *
     * Staging lives in the cache directory while the live `learner_audio` tree is still on disk
     * under its rollback name, so a restore transiently needs about twice the archive's size. Since
     * format 2 stores audio uncompressed, the document's own size is a good proxy for that.
     */
    private fun requireRestoreSpace(uri: Uri) {
        val documentBytes = documentSizeBytes(uri) ?: return
        val available = runCatching { StatFs(appContext.cacheDir.path).availableBytes }.getOrNull() ?: return
        if (available < documentBytes * 2 + FREE_SPACE_HEADROOM_BYTES) {
            throw UserBackupException(
                UserBackupFailure.NOT_ENOUGH_SPACE,
                "Not enough free space on this device to restore the backup",
            )
        }
    }

    private fun documentSizeBytes(uri: Uri): Long? = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it > 0 } else null
            }
    }.getOrNull()

    private suspend fun replaceAudioAndData(stagedAudio: File, payload: UserBackupPayload) {
        val live = learnerAudioRoot()
        val old = File(appContext.filesDir, "learner_audio_before_restore_${UUID.randomUUID()}")
        if (live.exists() && !live.renameTo(old)) throw IOException("Could not prepare existing audio for restore")
        try {
            if (payload.includesLearnerAudio && stagedAudio.exists()) {
                if (!stagedAudio.renameTo(live)) {
                    stagedAudio.copyRecursively(live, overwrite = true)
                }
            } else {
                live.mkdirs()
            }
            repository.restoreUserBackupPayload(payload)
            old.deleteRecursively()
        } catch (error: Exception) {
            live.deleteRecursively()
            if (old.exists()) old.renameTo(live)
            throw error
        }
    }

    private fun learnerAudioRoot() = File(appContext.filesDir, "learner_audio")

    private fun learnerAudioFiles(): List<AudioFile> {
        val root = learnerAudioRoot()
        if (!root.exists()) return emptyList()
        return root.walkTopDown().filter(File::isFile).map { AudioFile(it, it.length()) }.toList()
    }

    private fun safeAudioTarget(root: File, relative: String): File {
        if (relative.isBlank()) {
            throw UserBackupException(UserBackupFailure.DAMAGED, "Invalid audio path")
        }
        val canonicalRoot = root.canonicalFile
        val target = File(root, relative).canonicalFile
        if (!target.path.startsWith(canonicalRoot.path + File.separator)) {
            throw UserBackupException(UserBackupFailure.DAMAGED, "Unsafe audio path")
        }
        return target
    }

    private fun removeAudioReferences(rawTranscript: String): String {
        return try {
            val array = JSONArray(rawTranscript)
            for (index in 0 until array.length()) removeAudioKeys(array.opt(index))
            array.toString()
        } catch (_: Exception) {
            try {
                JSONObject(rawTranscript).also(::removeAudioKeys).toString()
            } catch (_: Exception) {
                rawTranscript
            }
        }
    }

    private fun removeAudioKeys(value: Any?) {
        when (value) {
            is JSONObject -> {
                value.remove("learner_audio_path")
                value.remove("learner_audio_duration_ms")
                value.keys().asSequence().toList().forEach { removeAudioKeys(value.opt(it)) }
            }
            is JSONArray -> for (index in 0 until value.length()) removeAudioKeys(value.opt(index))
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) {
                throw UserBackupException(UserBackupFailure.TOO_MUCH_AUDIO, "Backup entry is too large")
            }
            output.write(buffer, 0, read)
        }
        return total
    }

    /** Fails the write the moment it would exceed [limit]; never closes the stream below it. */
    private class BoundedOutputStream(
        out: OutputStream,
        private val limit: Long,
        private val failure: UserBackupFailure,
    ) : FilterOutputStream(out) {
        private var written = 0L

        override fun write(b: Int) {
            bump(1L)
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            bump(len.toLong())
            out.write(b, off, len)
        }

        override fun close() = flush()

        private fun bump(count: Long) {
            written += count
            if (written > limit) {
                throw UserBackupException(failure, "Backup data is too large to write")
            }
        }
    }

    /** Mirror of [BoundedOutputStream] for reads; never closes the stream below it. */
    private class BoundedInputStream(
        source: InputStream,
        private val limit: Long,
        private val failure: UserBackupFailure,
    ) : FilterInputStream(source) {
        private var read = 0L

        override fun read(): Int = `in`.read().also { if (it >= 0) bump(1L) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            `in`.read(b, off, len).also { if (it > 0) bump(it.toLong()) }

        override fun close() = Unit

        private fun bump(count: Long) {
            read += count
            if (read > limit) {
                throw UserBackupException(failure, "Backup data is too large to read")
            }
        }
    }

    companion object {
        const val MIME_TYPE = "application/octet-stream"
        const val FILE_EXTENSION = "bsebackup"
        const val MIN_PASSWORD_LENGTH = 8
        internal const val DATA_ENTRY = "user-data.json"
        internal const val TRAILER_ENTRY = "backup-complete"
        internal const val TRAILER_MARKER = "BSEBACKUP-END"
        internal val TRUNCATING_WRITE_MODES = listOf("wt", "rwt")
        private const val AUDIO_PREFIX = "audio/"
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /**
         * Ceilings sized for what this app actually produces (32 kbps mono AAC turn clips), not for
         * what a ZIP could theoretically hold. The previous 128 MB / 256 MB / 2 GB values were far
         * past the point where a restore would run the process out of heap or the cache partition
         * out of space, so they never fired — the crash did instead. Both sides enforce the same
         * limits, so an export can never produce a file this app then refuses to restore.
         */
        internal const val MAX_JSON_BYTES = 64L * 1024L * 1024L
        private const val MAX_AUDIO_FILE_BYTES = 32L * 1024L * 1024L
        private const val MAX_TOTAL_AUDIO_BYTES = 1L * 1024L * 1024L * 1024L
        private const val MAX_AUDIO_FILES = 20_000
        private const val FREE_SPACE_HEADROOM_BYTES = 64L * 1024L * 1024L
    }
}
