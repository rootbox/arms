package com.arms.androidauto.desktop.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DesktopPlaylist(val id: Long, val name: String, val songIds: List<String>)

// 담은 곡의 순서가 곧 재생 순서다. JSON 파일로 보관한다(Phase 2 MVP; 추후 SQLite 전환 여지).
class DesktopPlaylistStore(dir: File = FileCredentialStore.defaultDir()) {
    private val file = File(dir.apply { mkdirs() }, "playlists.json")

    private fun readAll(): MutableList<DesktopPlaylist> {
        if (!file.exists()) return mutableListOf()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val ids = o.getJSONArray("songIds").let { a -> (0 until a.length()).map { i -> a.getString(i) } }
            DesktopPlaylist(o.getLong("id"), o.getString("name"), ids)
        }.toMutableList()
    }

    private fun writeAll(list: List<DesktopPlaylist>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name)
                .put("songIds", JSONArray(p.songIds)))
        }
        file.writeText(arr.toString())
    }

    fun getPlaylists(): List<DesktopPlaylist> = readAll()

    fun createPlaylist(name: String): Long {
        val list = readAll()
        val id = (list.maxOfOrNull { it.id } ?: 0L) + 1
        list.add(DesktopPlaylist(id, name, emptyList())); writeAll(list); return id
    }

    // 이미 담긴 곡은 건너뛰고 뒤에 붙인다(순서 보존, 중복 방지).
    fun addSongs(playlistId: Long, songIds: List<String>) {
        val list = readAll()
        val i = list.indexOfFirst { it.id == playlistId }.takeIf { it >= 0 } ?: return
        val cur = list[i]
        val merged = cur.songIds + songIds.filter { it !in cur.songIds }
        list[i] = cur.copy(songIds = merged); writeAll(list)
    }

    fun rename(playlistId: Long, name: String) {
        val list = readAll()
        val i = list.indexOfFirst { it.id == playlistId }.takeIf { it >= 0 } ?: return
        list[i] = list[i].copy(name = name); writeAll(list)
    }

    fun delete(playlistId: Long) = writeAll(readAll().filterNot { it.id == playlistId })
}
