package org.akanework.gramophone.logic.asmr

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** HTTPS artwork support for both the online library and Gramophone's existing player. */
class AsmrArtworkFetcher : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        if (data.scheme != "https") return null
        return Fetcher {
            withContext(Dispatchers.IO) {
                val connection = URL(data.toString()).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Referer", AsmrRepository.WEBSITE)
                    if (connection.responseCode !in 200..299) throw IOException("Artwork unavailable")
                    // Keep a malformed or oversized response from exhausting the image loader.
                    val bytes = connection.inputStream.use { it.readBytesLimited(8 * 1024 * 1024) }
                    coroutineContext.ensureActive()
                    SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem),
                        connection.contentType?.substringBefore(';'), DataSource.NETWORK)
                } finally { connection.disconnect() }
            }
        }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) throw IOException("Artwork too large")
        output.write(buffer, 0, count)
    }
}
