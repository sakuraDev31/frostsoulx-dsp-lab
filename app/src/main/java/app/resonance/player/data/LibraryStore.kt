package app.resonance.player.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Scans MediaStore for music and keeps the result (including file paths) in library.json. */
object LibraryStore {
    private const val FILE = "library.json"

    fun load(context: Context): List<Track> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Track(
                    id = o.getLong("id"),
                    uri = o.getString("uri"),
                    path = o.optString("path"),
                    title = o.getString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    albumId = o.optLong("albumId"),
                    durationMs = o.optLong("duration"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, tracks: List<Track>) {
        val arr = JSONArray()
        for (t in tracks) {
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("uri", t.uri)
                    .put("path", t.path)
                    .put("title", t.title)
                    .put("artist", t.artist)
                    .put("album", t.album)
                    .put("albumId", t.albumId)
                    .put("duration", t.durationMs)
            )
        }
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(context.filesDir, FILE))
    }

    @Suppress("DEPRECATION")
    fun scan(context: Context): List<Track> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
        val order = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val out = ArrayList<Track>()
        context.contentResolver.query(collection, projection, selection, null, order)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out.add(
                    Track(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        path = c.getString(iData) ?: "",
                        title = c.getString(iTitle) ?: "Unknown",
                        artist = cleanTag(c.getString(iArtist), "Unknown artist"),
                        album = cleanTag(c.getString(iAlbum), "Unknown album"),
                        albumId = c.getLong(iAlbumId),
                        durationMs = c.getLong(iDur),
                    )
                )
            }
        }
        return out
    }

    private fun cleanTag(v: String?, fallback: String): String =
        if (v.isNullOrBlank() || v == "<unknown>") fallback else v
}
