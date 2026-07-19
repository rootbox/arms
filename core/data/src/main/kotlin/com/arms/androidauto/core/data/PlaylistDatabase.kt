package com.arms.androidauto.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// 라디오 방송국을 담는 arms_database와 파일을 분리한다.
//
// 기존 DB에 테이블을 추가하려면 마이그레이션을 써야 하는데, 그 DB는 exportSchema가 꺼져 있어
// 스키마 JSON이 없고 따라서 마이그레이션을 자동 검증할 방법이 없다. Room은 마이그레이션 결과가
// 엔티티 정의와 조금이라도 다르면 앱 실행 즉시 예외를 던지므로, 검증할 수 없는 변경을 기존
// 데이터(즐겨찾기)가 들어있는 파일에 가하는 것은 위험하다.
//
// 두 도메인은 서로 다른 백엔드에서 오는 데이터라 조인할 일도 없다. 대신 여기서는 처음부터
// exportSchema를 켜서, 나중에 이 DB의 스키마가 바뀔 때는 마이그레이션을 제대로 검증할 수 있게 한다.
@Database(
    entities = [PlaylistEntity::class, PlaylistTrackEntity::class],
    version = 1,
    exportSchema = true
)
abstract class PlaylistDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: PlaylistDatabase? = null

        fun getDatabase(context: Context): PlaylistDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PlaylistDatabase::class.java,
                    "arms_playlists"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
