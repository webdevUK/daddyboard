package helium314.keyboard.latin.gif

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GifSearcher(private val context: Context) {
    companion object {
        private const val TAG = "GifSearcher"
        private val PROVIDER_URI = Uri.parse("content://com.gifboard.provider/search")
    }

    suspend fun searchGifs(query: String, page: Int = 0): List<GifItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<GifItem>()
        try {
            val uri = PROVIDER_URI.buildUpon()
                .appendQueryParameter("q", query)
                .appendQueryParameter("page", page.toString())
                .build()

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex("_id")
                val urlIdx = cursor.getColumnIndex("url")
                val thumbIdx = cursor.getColumnIndex("thumbnail_url")
                val aspectIdx = cursor.getColumnIndex("aspect_ratio")

                while (cursor.moveToNext()) {
                    val url = if (urlIdx >= 0) cursor.getString(urlIdx) else ""
                    val thumbUrl = if (thumbIdx >= 0) cursor.getString(thumbIdx) else null
                    val aspectRatio = if (aspectIdx >= 0) cursor.getFloat(aspectIdx) else 1.0f

                    if (url.isNotEmpty()) {
                        results.add(GifItem(url, thumbUrl, (aspectRatio * 100).toInt(), 100))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query GifContentProvider", e)
        }
        return@withContext results
    }
}
