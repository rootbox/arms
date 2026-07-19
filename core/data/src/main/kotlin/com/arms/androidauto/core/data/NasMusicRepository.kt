package com.arms.androidauto.core.data

import android.content.Context
import com.arms.androidauto.core.model.NasAlbum
import com.arms.androidauto.core.model.NasSong
import com.arms.androidauto.core.network.NetworkClient
import com.arms.androidauto.core.network.SynologyCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 요청한 ID 순서를 그대로 유지해서 곡을 골라낸다.
//
// filter를 쓰면 캐시에 담긴 순서(NAS가 준 순서)로 나와버려서, 사용자가 플레이리스트에 담은
// 순서와 다르게 재생된다. 조용히 틀리는 종류의 버그라 별도 함수로 두고 테스트로 고정한다.
// 목록에 없는 ID(NAS에서 삭제된 곡 등)는 건너뛴다.
internal fun orderSongsByIds(songs: List<NasSong>, songIds: List<String>): List<NasSong> {
    val byId = songs.associateBy { it.id }
    return songIds.mapNotNull { byId[it] }
}

class NasMusicRepository private constructor(context: Context) {
    private val appContext = context.applicationContext

    // EncryptedSharedPreferences는 첫 접근에 Keystore 초기화 + 디스크 I/O가 붙는다.
    // 차량 서비스 onCreate(메인 스레드)에서 저장소를 만들자마자 그 비용을 물지 않도록
    // 실제로 자격증명이 필요할 때까지 미룬다.
    private val credentialsStore by lazy { NasCredentialsStore(appContext) }
    private val api = NetworkClient.synologyMusicApi

    private val mutex = Mutex()
    private var cachedSession: CachedSession? = null
    private var cachedSongs: CachedSongs? = null

    private data class CachedSession(
        val credentials: SynologyCredentials,
        val sid: String,
        val obtainedAtMs: Long
    )

    private data class CachedSongs(
        val songs: List<NasSong>,
        val fetchedAtMs: Long
    )

    fun hasCredentials(): Boolean = credentialsStore.get() != null

    fun saveCredentials(baseUrl: String, account: String, password: String) {
        credentialsStore.save(SynologyCredentials(baseUrl.trim(), account.trim(), password))
        invalidateBlocking()
    }

    fun clearCredentials() {
        credentialsStore.clear()
        invalidateBlocking()
    }

    // 설정 화면에서 "연결 테스트" 용도로 사용. 로그인 성공 여부만 확인한다.
    suspend fun testConnection(baseUrl: String, account: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            val sid = api.login(SynologyCredentials(baseUrl.trim(), account.trim(), password))
            sid != null
        }

    suspend fun invalidate() = mutex.withLock {
        cachedSession = null
        cachedSongs = null
    }

    private fun invalidateBlocking() {
        // 자격증명이 바뀌면 기존 세션/목록은 즉시 무의미해진다. 저장은 동기 API라
        // 락을 잡지 않고 참조만 끊는다 (경합해도 최악이 재조회 1회).
        cachedSession = null
        cachedSongs = null
    }

    // 세션(sid)은 만료될 수 있어 짧게 캐시한다. 재생 URL을 만들 때는 그 URL 안에 sid가
    // 박히기 때문에, 앨범 한 장을 다 듣는 동안 만료되지 않도록 forceFresh로 새로 받는다.
    private suspend fun session(forceFresh: Boolean): CachedSession? {
        if (!forceFresh) {
            cachedSession?.let {
                if (System.currentTimeMillis() - it.obtainedAtMs < SESSION_TTL_MS) return it
            }
        }
        val credentials = credentialsStore.get() ?: return null
        val sid = withContext(Dispatchers.IO) { api.login(credentials) } ?: return null
        return CachedSession(credentials, sid, System.currentTimeMillis()).also { cachedSession = it }
    }

    // 앨범은 서버에 고유 ID가 없어 전체 곡을 받아 클라이언트에서 묶는 구조라, 브라우징할
    // 때마다 736곡을 다시 받아오면 낭비가 크다. 곡 목록 자체는 잘 바뀌지 않으므로 길게 캐시한다.
    private suspend fun songs(): List<NasSong> {
        cachedSongs?.let {
            if (System.currentTimeMillis() - it.fetchedAtMs < SONGS_TTL_MS) return it.songs
        }
        val fetched = fetchSongs(forceFreshSession = false)
        // 빈 목록은 "라이브러리가 비었다"와 "세션이 만료됐다"를 구분할 수 없다. 후자라면
        // 그대로 두면 차량에 "앨범 없음"으로 보이므로, 새 세션으로 딱 한 번 더 시도한다.
        val songs = fetched.ifEmpty { fetchSongs(forceFreshSession = true) }
        cachedSongs = CachedSongs(songs, System.currentTimeMillis())
        return songs
    }

    private suspend fun fetchSongs(forceFreshSession: Boolean): List<NasSong> {
        val session = session(forceFresh = forceFreshSession) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            api.getAllSongs(session.credentials, session.sid).map {
                NasSong(it.id, it.title, it.artist, it.album, it.albumArtist)
            }
        }
    }

    private fun NasSong.matches(album: NasAlbum): Boolean =
        (this.album ?: UNKNOWN_ALBUM) == album.name && (albumArtist ?: "") == album.albumArtist

    // 재생목록이 따로 없는 경우가 많아, 라이브러리에 이미 정리된 앨범을 보관함으로 사용한다.
    suspend fun getAlbums(): List<NasAlbum> = mutex.withLock {
        songs()
            .groupBy { (it.album ?: UNKNOWN_ALBUM) to (it.albumArtist ?: "") }
            .map { (key, songs) -> NasAlbum(name = key.first, albumArtist = key.second, songCount = songs.size) }
            .sortedBy { it.name }
    }

    suspend fun getAlbumSongs(album: NasAlbum): List<NasSong> = mutex.withLock {
        songs().filter { it.matches(album) }
    }

    // 재생 직전 호출. 곡 목록은 캐시를 재사용하고 세션만 새로 받아, 스트리밍 URL에 담기는
    // sid가 앨범 재생 내내 유효하도록 한다.
    suspend fun getAlbumStreamUrls(album: NasAlbum): List<Pair<NasSong, String>> = mutex.withLock {
        attachStreamUrls(songs().filter { it.matches(album) })
    }

    // 플레이리스트 재생용. 앨범과 달리 여러 앨범의 곡이 섞이므로 곡 ID 목록을 그대로 받는다.
    //
    // 넘긴 ID 순서를 그대로 유지하는 것이 중요하다. 캐시를 filter로 거르면 캐시에 담긴
    // 순서(=NAS가 준 순서)로 나와버려서, 사용자가 담은 순서와 다르게 재생된다.
    suspend fun getStreamUrlsForSongIds(songIds: List<String>): List<Pair<NasSong, String>> =
        mutex.withLock {
            if (songIds.isEmpty()) return@withLock emptyList()
            attachStreamUrls(orderSongsByIds(songs(), songIds))
        }

    // 곡 정보만 필요할 때 (재생하지 않으므로 세션이 필요 없다). 캐시만 사용.
    suspend fun findSongsByIds(songIds: List<String>): List<NasSong> = mutex.withLock {
        if (songIds.isEmpty()) return@withLock emptyList()
        orderSongsByIds(songs(), songIds)
    }

    // 주의: 호출부가 이미 mutex를 잡고 있다는 전제. 이 안에서 public 함수를 부르면 데드락이다
    // (Mutex는 재진입을 허용하지 않는다).
    private suspend fun attachStreamUrls(targetSongs: List<NasSong>): List<Pair<NasSong, String>> {
        if (targetSongs.isEmpty()) return emptyList()
        val session = session(forceFresh = true) ?: return emptyList()
        return targetSongs.map { song ->
            song to api.getStreamUrl(session.credentials, session.sid, song.id)
        }
    }

    companion object {
        private const val SESSION_TTL_MS = 10 * 60 * 1000L
        private const val SONGS_TTL_MS = 30 * 60 * 1000L
        private const val UNKNOWN_ALBUM = "알 수 없는 앨범"

        // 폰 화면과 차량 서비스가 같은 프로세스에서 돌기 때문에, 인스턴스를 공유하면
        // 캐시도 함께 공유되어 양쪽 모두 로그인/조회 횟수가 줄어든다.
        @Volatile
        private var instance: NasMusicRepository? = null

        fun get(context: Context): NasMusicRepository {
            return instance ?: synchronized(this) {
                instance ?: NasMusicRepository(context).also { instance = it }
            }
        }
    }
}
