package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.di.BlobClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raw PUT/GET against an Azure Blob Storage SAS URL - the DMF package upload/download for
 * counting-journal lines. See `CountingJournalRepository` and [com.d365fo.mobileops.di.BlobClient]'s
 * own doc comment on why this uses a bare client rather than the shared, AOS-authenticated one.
 *
 * Plain OkHttp rather than Retrofit: both calls move an opaque zip byte array with no JSON body
 * on either side, which is exactly the case Retrofit's converter-factory machinery adds nothing
 * for.
 */
@Singleton
class BlobTransferService @Inject constructor(
    @param:BlobClient private val client: OkHttpClient,
) {
    /**
     * Uploads [bytes] as a single Azure block blob. `x-ms-blob-type: BlockBlob` is required by
     * Azure Storage's own PUT-blob contract, not optional metadata - the SAS URL alone does not
     * imply a blob type.
     */
    suspend fun upload(sasUrl: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(sasUrl)
            .header("x-ms-blob-type", "BlockBlob")
            .put(bytes.toRequestBody("application/zip".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Blob upload failed: HTTP ${response.code}")
            }
        }
    }

    /**
     * Streams the blob at [sasUrl] into [target] - never all in memory - and returns its size and
     * SHA-256 (lower-case hex), worked out on the way. The item snapshot parts (performance plan,
     * phase 5). [target] is removed when the download fails.
     */
    suspend fun downloadTo(sasUrl: String, target: java.io.File): Pair<Long, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(sasUrl).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Blob download failed: HTTP ${response.code}")
                val body = response.body ?: throw IOException("Blob download returned no body")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var size = 0L
                body.byteStream().use { input ->
                    target.outputStream().buffered(64 * 1024).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            size += read
                        }
                    }
                }
                size to digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    suspend fun download(sasUrl: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(sasUrl).get().build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Blob download failed: HTTP ${response.code}")
            }
            response.body?.bytes() ?: throw IOException("Blob download returned no body")
        }
    }
}
