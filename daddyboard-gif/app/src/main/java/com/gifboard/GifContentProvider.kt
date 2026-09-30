package com.gifboard

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import android.os.Build
import android.webkit.WebView
import android.webkit.CookieManager

class GifContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "GifContentProvider"
        const val AUTHORITY = "com.gifboard.provider"
    }

    private lateinit var client: OkHttpClient
    private lateinit var gifProvider: GifProvider
    private var headlessWebView: WebView? = null

    override fun onCreate(): Boolean {
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        
        // Ensure webview operations run on main thread if needed
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                headlessWebView = WebView(context!!)
                headlessWebView?.settings?.javaScriptEnabled = true
                headlessWebView?.settings?.domStorageEnabled = true
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cookieManager.setAcceptThirdPartyCookies(headlessWebView, true)
                }
                cookieManager.setCookie(".google.com", GoogleConsentCookies.buildConsentCookie())
                cookieManager.setCookie(".google.com", GoogleConsentCookies.buildSocsCookie())
                
                gifProvider = GoogleGifFetcher(headlessWebView!!)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize WebView for GifProvider", e)
                gifProvider = JsonApiGifProvider()
            }
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri.path == "/search") {
            val q = uri.getQueryParameter("q") ?: return null
            val page = uri.getQueryParameter("page")?.toIntOrNull() ?: 0
            val safeSearch = uri.getQueryParameter("safeSearch") ?: "active"
            val timeout = uri.getQueryParameter("timeoutMs")?.toLongOrNull() ?: 5000L

            val cursor = MatrixCursor(arrayOf("_id", "url", "thumbnail_url", "aspect_ratio"))
            
            // Wait for gifProvider to be initialized
            while (!::gifProvider.isInitialized) {
                Thread.sleep(100)
            }

            try {
                val results = runBlocking {
                    gifProvider.search(q, page, safeSearch, timeout)
                }
                
                results.forEachIndexed { index, item ->
                    // Wrap remote URLs in our content provider URI
                    val proxyUrl = Uri.Builder()
                        .scheme("content")
                        .authority(AUTHORITY)
                        .path("/download")
                        .appendQueryParameter("url", item.url)
                        .build().toString()
                        
                    val proxyThumbUrl = item.thumbnailUrl?.let { thumb ->
                        Uri.Builder()
                            .scheme("content")
                            .authority(AUTHORITY)
                            .path("/download")
                            .appendQueryParameter("url", thumb)
                            .build().toString()
                    }

                    cursor.addRow(arrayOf(
                        index.toLong(),
                        proxyUrl,
                        proxyThumbUrl,
                        item.aspectRatio
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
            }
            return cursor
        }
        return null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uri.path == "/download") {
            val remoteUrl = uri.getQueryParameter("url") ?: return null
            
            val pipes = try {
                ParcelFileDescriptor.createReliablePipe()
            } catch (e: IOException) {
                return null
            }

            val readFd = pipes[0]
            val writeFd = pipes[1]

            thread {
                var outputStream: FileOutputStream? = null
                try {
                    val request = Request.Builder().url(remoteUrl).build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val inputStream = response.body?.byteStream()
                        outputStream = FileOutputStream(writeFd.fileDescriptor)
                        inputStream?.copyTo(outputStream)
                    } else {
                        writeFd.closeWithError("HTTP error ${response.code}")
                    }
                } catch (e: Exception) {
                    writeFd.closeWithError(e.message)
                } finally {
                    try { outputStream?.close() } catch (e: Exception) {}
                    try { writeFd.close() } catch (e: Exception) {}
                }
            }
            return readFd
        }
        return null
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
