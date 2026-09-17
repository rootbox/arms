package com.arms.androidauto.desktop.storage

import com.arms.androidauto.core.network.SynologyCredentials

// NAS 접속 정보 + 로그인 세션(sid) 저장. 안드로이드의 NasCredentialsStore와 같은 역할.
// 데스크톱 인터페이스로 두어, Phase 4에서 파일 구현을 OS 키체인 구현으로 교체할 수 있게 한다.
interface CredentialStore {
    fun get(): SynologyCredentials?
    fun save(credentials: SynologyCredentials)
    fun clear()
    fun getSid(): String?
    fun saveSid(sid: String)
    fun clearSid()
}
