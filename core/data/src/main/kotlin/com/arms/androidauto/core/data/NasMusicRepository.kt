package com.arms.androidauto.core.data

import android.content.Context
import com.arms.androidauto.core.model.NasAlbum
import com.arms.androidauto.core.model.NasSong
import com.arms.androidauto.core.model.NasTrack
import com.arms.androidauto.core.network.NetworkClient
import com.arms.androidauto.core.network.SongListResult
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

    // 저장해둔 sid를 아직 메모리로 안 올렸는지 표시. 앱을 새로 켠 직후 한 번만 읽는다.
    private var restoredPersistedSession = false

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

    private fun invalidateBlocking() {
        // 자격증명이 바뀌면 기존 세션/목록은 즉시 무의미해진다. 저장은 동기 API라
        // 락을 잡지 않고 참조만 끊는다 (경합해도 최악이 재조회 1회).
        // 저장해둔 sid도 지운다 - 다른 계정의 sid를 재사용하면 안 된다.
        cachedSession = null
        cachedSongs = null
        credentialsStore.clearSid()
        restoredPersistedSession = true
    }

    // 로그인 세션(sid)을 얻는다.
    //
    // 예전에는 세션을 메모리에만 두고 짧은 TTL로 캐시했는데, 그러면 앱을 껐다 켤 때마다
    // 반드시 새로 로그인하게 되고 NAS가 그때마다 "새 로그인"으로 감지해 알림을 보냈다.
    // 이제는 sid를 암호화 저장소에 남겨 앱 재시작 후에도 재사용하고, 실제로 요청이
    // 인증 오류로 실패했을 때만(fetchSongs 참고) 새로 로그인한다.
    private suspend fun session(forceFresh: Boolean): CachedSession? {
        val credentials = credentialsStore.get() ?: return null

        if (!forceFresh) {
            cachedSession?.let { if (it.credentials == credentials) return it }
            // 앱을 새로 켠 직후: 저장해둔 sid가 있으면 그대로 살려 쓴다(로그인 없음).
            // 죽은 sid라면 첫 요청이 인증 오류로 돌아오고, 그때 새로 로그인한다.
            if (!restoredPersistedSession) {
                restoredPersistedSession = true
                credentialsStore.getSid()?.let { saved ->
                    return CachedSession(credentials, saved, System.currentTimeMillis())
                        .also { cachedSession = it }
                }
            }
        }

        val sid = withContext(Dispatchers.IO) { api.login(credentials) } ?: return null
        credentialsStore.saveSid(sid)
        restoredPersistedSession = true
        return CachedSession(credentials, sid, System.currentTimeMillis()).also { cachedSession = it }
    }

    // 세션이 더 이상 유효하지 않다고 판단됐을 때 버린다. 다음 요청이 새로 로그인한다.
    // 스트리밍 URL에 박힌 sid가 만료돼 재생이 실패한 경우, 호출부가 이걸 부른 뒤 큐를
    // 다시 만들어야 낡은 sid로 무한히 재시도하지 않는다.
    suspend fun invalidateSession() = mutex.withLock { discardSession() }

    private fun discardSession() {
        cachedSession = null
        credentialsStore.clearSid()
        restoredPersistedSession = true
    }

    // 앨범은 서버에 고유 ID가 없어 전체 곡을 받아 클라이언트에서 묶는 구조라, 브라우징할
    // 때마다 736곡을 다시 받아오면 낭비가 크다. 곡 목록 자체는 잘 바뀌지 않으므로 길게 캐시한다.
    private suspend fun songs(): List<NasSong> {
        cachedSongs?.let {
            if (System.currentTimeMillis() - it.fetchedAtMs < SONGS_TTL_MS) return it.songs
        }
        val songs = fetchSongs()
        cachedSongs = CachedSongs(songs, System.currentTimeMillis())
        return songs
    }

    // 저장된(혹은 캐시된) 세션으로 먼저 시도하고, 그 세션이 죽었을 때만 새로 로그인해서
    // 한 번 더 시도한다. 이 경로가 세션 유효성을 확인해주는 역할도 하기 때문에, 여기서
    // 성공한 직후에 만들어지는 스트리밍 URL은 살아있는 sid를 쓰게 된다.
    private suspend fun fetchSongs(): List<NasSong> {
        val first = requestSongs(forceFreshSession = false)
        if (first != null) return first
        // 인증 오류였다 - 낡은 sid를 버리고 새로 로그인해서 재시도
        discardSession()
        return requestSongs(forceFreshSession = true) ?: emptyList()
    }

    // 인증 실패면 null, 그 밖의 실패면 빈 목록을 돌려준다.
    // (네트워크 오류에 재로그인으로 대응해봐야 의미가 없으므로 구분한다)
    private suspend fun requestSongs(forceFreshSession: Boolean): List<NasSong>? {
        val session = session(forceFresh = forceFreshSession) ?: return emptyList()
        return when (val result = withContext(Dispatchers.IO) {
            api.getAllSongs(session.credentials, session.sid)
        }) {
            is SongListResult.Success ->
                result.songs.map { NasSong(it.id, it.title, it.artist, it.album, it.albumArtist) }
            SongListResult.AuthFailure -> null
            SongListResult.Failure -> emptyList()
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
    suspend fun getAlbumStreamUrls(album: NasAlbum): List<NasTrack> = mutex.withLock {
        attachStreamUrls(songs().filter { it.matches(album) })
    }

    // 플레이리스트 재생용. 앨범과 달리 여러 앨범의 곡이 섞이므로 곡 ID 목록을 그대로 받는다.
    //
    // 넘긴 ID 순서를 그대로 유지하는 것이 중요하다. 캐시를 filter로 거르면 캐시에 담긴
    // 순서(=NAS가 준 순서)로 나와버려서, 사용자가 담은 순서와 다르게 재생된다.
    suspend fun getStreamUrlsForSongIds(songIds: List<String>): List<NasTrack> =
        mutex.withLock {
            if (songIds.isEmpty()) return@withLock emptyList()
            attachStreamUrls(orderSongsByIds(songs(), songIds))
        }

    // 주의: 호출부가 이미 mutex를 잡고 있다는 전제. 이 안에서 public 함수를 부르면 데드락이다
    // (Mutex는 재진입을 허용하지 않는다).
    private suspend fun attachStreamUrls(targetSongs: List<NasSong>): List<NasTrack> {
        if (targetSongs.isEmpty()) return emptyList()
        // 예전에는 여기서 항상 새로 로그인했다(스트리밍 URL에 sid가 박혀 앨범을 다 듣는 동안
        // 유효해야 한다는 이유). 그런데 이 함수는 songs()가 성공한 직후에만 불리므로 그 세션은
        // 방금 실제 요청으로 살아있음이 확인된 것이고, 매번 로그인하면 NAS가 "새 로그인"으로
        // 감지해 알림을 보낸다. 그래서 살아있는 세션을 그대로 재사용한다.
        // 재생 도중 만료되면 호출부가 invalidateSession() 후 큐를 다시 만들어 복구한다.
        val session = session(forceFresh = false) ?: return emptyList()
        // 스트림 URL과 커버 URL을 같은 세션(sid)으로 함께 서명한다. 커버도 sid가 유효한 동안만
        // 로드되므로, 재생 URL과 수명을 맞춰야 앨범 아트가 중간에 깨지지 않는다.
        return targetSongs.map { song ->
            NasTrack(
                song = song,
                streamUrl = api.getStreamUrl(session.credentials, session.sid, song.id),
                artworkUrl = api.getCoverUrl(session.credentials, session.sid, song.id)
            )
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
