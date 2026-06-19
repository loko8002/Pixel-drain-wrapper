package com.pixeldrain.wrapper

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.net.URLDecoder

/**
 * Routes WebView download requests through the system [DownloadManager] so files
 * land in the public Downloads folder with a progress notification, honouring
 * scoped storage on modern Android. All filename parsing (Content-Disposition,
 * URL fallback) lives here.
 */
object DownloadHelper {

    /**
     * Enqueues a download. Returns the resolved, user-facing file name so the
     * caller can show a confirmation snackbar.
     */
    fun enqueue(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        val fileName = resolveFileName(url, contentDisposition, mimeType)

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            // Carry the WebView's auth cookies so private/login-gated files work.
            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrEmpty()) addRequestHeader("Cookie", cookies)
            if (!userAgent.isNullOrEmpty()) addRequestHeader("User-Agent", userAgent)

            setMimeType(mimeType)
            setTitle(fileName)
            setDescription(context.getString(R.string.download_in_progress))
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            // Scoped-storage friendly: writes to the public Downloads collection.
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        return fileName
    }

    /**
     * Derives the best file name from the Content-Disposition header, falling
     * back to the URL path and finally a generic name with a guessed extension.
     */
    private fun resolveFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        parseContentDisposition(contentDisposition)?.let { return sanitize(it) }

        // URLUtil handles the common cases from the URL itself.
        val guessed = runCatching {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        }.getOrNull()
        if (!guessed.isNullOrBlank()) return sanitize(guessed)

        val extension = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.let { ".$it" } ?: ""
        return "pixeldrain_download$extension"
    }

    /**
     * Parses both the RFC 5987 `filename*=UTF-8''...` and the plain
     * `filename="..."` forms of the Content-Disposition header.
     */
    private fun parseContentDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null

        // Prefer the encoded RFC 5987 variant when present.
        Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;\\n]+)", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)?.let { encoded ->
                return runCatching { URLDecoder.decode(encoded.trim(), "UTF-8") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }

        // Standard quoted or bare filename.
        Regex("filename\\s*=\\s*\"?([^\";\\n]+)\"?", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)?.let { name ->
                return name.trim().takeIf { it.isNotBlank() }
            }
        return null
    }

    /** Strips path separators and illegal characters from a candidate file name. */
    private fun sanitize(name: String): String =
        name.substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "pixeldrain_download" }
}
