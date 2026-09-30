package helium314.keyboard.latin.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.KtxKt.prefs
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.LayoutUtils

class LayoutProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val path = uri.path ?: return null
        return when {
            path.startsWith("/prefs") -> getPrefsCursor()
            path.startsWith("/json") -> getJsonCursor()
            else -> null
        }
    }

    private fun getPrefsCursor(): Cursor {
        val cursor = MatrixCursor(arrayOf("key", "type", "value"))
        val prefs = context?.prefs() ?: return cursor
        
        for ((key, value) in prefs.all) {
            if (key.startsWith("theme_") || 
                key.startsWith("user_colors_") ||
                key.startsWith("user_all_colors_") ||
                key.startsWith("user_more_colors_") ||
                key.startsWith("keyboard_height_scale") ||
                key.startsWith("bottom_padding_scale") ||
                key.startsWith("side_padding_scale") ||
                key.startsWith("key_gap_scale") ||
                key.startsWith("bottom_row_scale") ||
                key.startsWith("font_") ||
                key.startsWith("layout_")
            ) {
                val type = when (value) {
                    is String -> "string"
                    is Boolean -> "boolean"
                    is Int -> "int"
                    is Float -> "float"
                    is Long -> "long"
                    else -> continue
                }
                cursor.addRow(arrayOf(key, type, value.toString()))
            }
        }
        return cursor
    }

    private fun getJsonCursor(): Cursor {
        val cursor = MatrixCursor(arrayOf("layout"))
        val ctx = context ?: return cursor
        val layoutName = Settings.readDefaultLayoutName(LayoutType.MAIN, ctx.prefs()) ?: "qwerty"
        val content = try {
            LayoutUtils.getContent(LayoutType.MAIN, layoutName, ctx)
        } catch (e: Exception) {
            ""
        }
        cursor.addRow(arrayOf(content))
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
