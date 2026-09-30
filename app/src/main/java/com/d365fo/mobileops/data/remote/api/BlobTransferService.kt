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
    @BlobClient private val client: OkHttpClient,
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
