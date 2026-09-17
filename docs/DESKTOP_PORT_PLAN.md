# 데스크톱 포팅 계획 — macOS / Windows (Compose Multiplatform)

> 목표: 라디오(KBS·SBS) · K-POP 24/7 · NAS 음악을 **macOS와 Windows 데스크톱 앱**에서 재생.
> UI는 현재 안드로이드 폰 화면과 동일해도 무방. 차량(Android Auto) 요소는 대상 아님.
> 작성: 2026-09-17. 착수 전 검토용 계획서.

---

## 0. 요약

- **한 코드베이스로 mac+win이 가능하다.** UI가 이미 Jetpack Compose이고 네트워크 계층이 이미
  순수 JVM(`kotlin("jvm")`, 안드로이드 의존 0)이라, **Compose Multiplatform Desktop**으로 옮기면
  UI와 네트워크를 거의 그대로 재사용한다.
- **새로 만들 것은 딱 두 계층**: 재생 엔진(ExoPlayer → 데스크톱 오디오)과 저장소
  (Room/EncryptedSharedPreferences → 데스크톱 DB/키체인). iOS 검토 때 지목된 것과 같은 두 곳이다.
- **웹과 달리 CORS·브라우저 자격증명 노출 문제가 없다** — 데스크톱은 브라우저가 아니고, NAS
  비밀번호는 OS 보안 저장소에 둔다. 현재 앱의 보안 모델을 그대로 유지한다.
- 실측으로 3개 소스 모두 데스크톱에서 재생 가능함을 확인(HLS·MP3·NAS 스트림).

---

## 1. 재사용 판정 (실측 근거)

| 계층 | 현재 | 데스크톱 |
|---|---|---|
| `core/model` | `kotlin("jvm")`, 안드로이드 의존 0 | ✅ 그대로 |
| `core/network` (632줄, RadioApi·SynologyMusicApi·게이트웨이) | `kotlin("jvm")`, OkHttp | ✅ 그대로 (OkHttp는 데스크톱 JVM에서도 동작) |
| 폰 UI (Compose, MainActivity 등) | `app` 모듈에 결합 | 🔁 Compose Multiplatform 공통 UI로 이동. 컴포저블 대부분 재사용 |
| `core/media` (ExoPlayer 래퍼, 150줄) | Android 라이브러리 | 🔁 인터페이스 유지 + 데스크톱 구현 신규 |
| `core/data` (Room ×2, 암호화 prefs) | Android 라이브러리 | 🔁 저장소 인터페이스 + 데스크톱 구현 신규 |
| `ARMSMediaLibraryService` (차량) | Android/차량 전용 | ⛔ 데스크톱 대상 아님 (제외) |

폰 UI가 안드로이드 API에 닿는 지점은 제한적(`Context`/`LocalContext`, `Intent`+`startActivity`
1곳, `getSystemService` 1곳, `UpdateChecker` 1곳)이라, 이들을 플랫폼 추상화 뒤로 넣으면 UI 공유가 된다.

---

## 2. 결정 사항 (착수 전 확인)

### 결정 1 — UI 공유 방식 → **Compose Multiplatform 공통 UI** (권장)
현재 Compose 자산을 최대한 재사용. `app`(안드로이드)과 `desktop`이 같은 `ui` 코드를 공유한다.
대안(데스크톱 UI를 따로 작성)은 UI 재사용 이득을 버리므로 비권장.

### 결정 2 — 재생 엔진 → **VLCJ(libVLC)** (권장)
- HLS(라디오)·Ogg/Opus·MP3(K-POP)·FLAC(NAS)을 한 엔진으로 모두 처리. 코덱 걱정이 사라진다.
- 대가: OS별 libVLC 네이티브를 번들 → 설치 파일이 커지고(수십 MB) 로딩 코드가 붙는다.
- 대안: GStreamer(gst1-java-core) — 더 가볍지만 Windows 배포 설정이 번거롭다. JavaFX MediaPlayer는
  HLS·Ogg 지원이 부실해 탈락.
- 👉 코덱 범위 때문에 VLCJ 권장. 최종 확정은 **Phase 0에서 실증 후** 한다.

### 결정 3 — 저장소
- 플레이리스트/재생상태: **SQLDelight**(멀티플랫폼) 또는 데스크톱 로컬 SQLite. MVP는 단순 파일도 가능.
- NAS 자격증명: **OS 보안 저장소** — macOS Keychain / Windows Credential Manager
  (예: `com.microsoft.credential-secure-storage` 류 또는 각 OS API 호출). 브라우저 localStorage 같은
  평문 노출 없음. 이게 안 되면 최소한 파일 암호화로 폴백.

### 결정 4 — 배포/서명 (⚠️ 사용자 액션 필요)
- 산출물: Compose Desktop의 네이티브 배포 — macOS **.dmg**, Windows **.msi/.exe**.
- **macOS**: Gatekeeper 때문에 서명·공증(notarization)이 없으면 "확인되지 않은 개발자" 경고.
  정식 공증에는 **Apple Developer($99/년)**. 없으면 경고를 감수하고 배포(개인용은 가능).
- **Windows**: 코드 서명 인증서가 없으면 SmartScreen 경고. 개인용은 감수 가능.
- 배포 채널: **GitHub Releases**(안드로이드와 동일). 인앱 업데이트는 데스크톱에선 복잡하므로 MVP는
  "새 버전 알림 + 다운로드 페이지 열기" 수준으로.
- 👉 mac/win 각각 "서명 함(비용 발생) / 경고 감수" 중 선택.

---

## 3. 목표 구조

```
core/model        (그대로, kotlin multiplatform: commonMain)
core/network      (그대로, OkHttp; 필요 시 나중에 Ktor로 교체 검토)
core/playback     신규 인터페이스 모듈: AudioPlayer(play/queue/pause/seek/shuffle/repeat/callbacks)
   ├─ android      ExoPlayer 구현 (현 core/media 이식)
   └─ desktop      VLCJ 구현 (신규)
core/storage      신규 인터페이스: PlaylistStore, CredentialStore, PlaybackStateStore
   ├─ android      Room + EncryptedSharedPreferences (현 core/data 이식)
   └─ desktop      SQLDelight/SQLite + OS 키체인 (신규)
ui                Compose Multiplatform 공통 화면 (현 app UI 이식)
app               안드로이드 진입점 (얇게)
desktop           데스크톱 진입점 (신규, main() + 윈도우)
```

현재 `core/media`의 `MediaPlayer`가 이미 `play/playQueue/pause/resume/next/prev/position/shuffle/repeat`
+ 콜백으로 **좁고 깨끗한 인터페이스**라, 이걸 그대로 `AudioPlayer` 계약으로 승격하면 된다.

---

## 4. 단계 (각 단계 독립 검증)

### Phase 0 — 검증 게이트 ✅ macOS 통과 (2026-09-17)
착수 전, **가장 위험한 두 가지를 데스크톱에서 실증**한다. 나머지는 이게 되면 다 된다.

> **결과(macOS)**: 두 게이트 모두 통과.
> - core/network를 **수정 없이** JVM 데스크톱에서 링크해 KBS·SBS·K-POP 실제 편성·커버·스트림
>   URL 조회 성공.
> - VLCJ(libVLC=설치된 VLC.app)로 KBS/SBS(HLS·AAC), K-POP(Opus), NAS 대역(HTTP FLAC)을
>   모두 디코딩·재생 확인(native-discovery=true, 각 소스 오디오 트랙+재생시간 진행).
> - 부수 확인: K-POP `/kpop/stream`은 Opus인데 VLCJ가 네이티브 재생 → 데스크톱은 MP3 폴백 불필요.
> - **Windows는 미검증**(이 환경에 Windows 없음). 같은 VLCJ+libVLC·동일 코덱이라 위험은 낮으나,
>   Windows 실기 확인은 사용자 몫으로 남는다.
1. **재생 실증**: 최소 JVM 데스크톱 프로그램으로 VLCJ를 써서 (a) SBS/KBS HLS, (b) LISTEN.moe
   MP3, (c) NAS 스트림 URL을 **macOS와 Windows 양쪽에서** 재생. FLAC 포함.
2. **코어 재사용 실증**: 그 프로그램에서 `core/network`를 **수정 없이** 링크해 실제 편성/곡/커버를
   받아오기.
- 통과 기준: 두 OS에서 3소스가 소리가 나고 커버 URL이 온다. → 통과하면 Phase 1로.

### Phase 1 — 멀티플랫폼 골격
- Compose Multiplatform 도입(compose 플러그인 전환), `core/model`·`core/network`를 common으로.
- `core/playback`·`core/storage` 인터페이스 정의 + 안드로이드 구현을 기존 코드에서 이식.
- **안드로이드 앱이 그대로 빌드·동작하는지 회귀 확인**(기존 사용자 보호).

### Phase 2 — 데스크톱 재생/저장소 구현
- `desktop` AudioPlayer(VLCJ), CredentialStore(키체인), PlaylistStore(SQLite).
- 헤드리스 수준에서 3소스 재생 + NAS 로그인/곡목록/플레이리스트 CRUD 확인.

### Phase 3 — 데스크톱 UI
- 공통 Compose UI를 데스크톱 창에 띄우기. `Context`/`Intent`/`getSystemService` 의존부를 플랫폼
  추상화로 대체. 라디오/K-POP/NAS 화면, 미니플레이어, 전체화면 플레이어, 커버 재시도.
- 데스크톱에 맞는 창 크기/키보드/미디어키(선택) 대응.

### Phase 4 — 패키징/배포
- Compose Desktop `packageDmg`/`packageMsi`. 아이콘·앱 이름.
- 서명/공증(결정 4)에 따라 처리. GitHub Actions에 mac+win 러너로 빌드 매트릭스 추가.

3·2는 1 이후 병행 가능(서로 독립). 4는 마지막.

---

## 5. 리스크

| 리스크 | 심각도 | 대응 |
|---|---|---|
| VLCJ 네이티브 번들/로딩(OS별) | 중 | Phase 0에서 mac+win 둘 다 실증 후 확정 |
| NAS FLAC 코덱 | 중 | VLCJ가 FLAC 지원 → Phase 0에 포함. 안 되면 Audio Station 트랜스코딩 |
| Compose Multiplatform 전환 중 안드로이드 회귀 | 중 | Phase 1에서 안드로이드 빌드/기존 테스트 전수 통과를 게이트로 |
| macOS 공증/Windows 서명 비용 | 낮~중 | 개인용은 경고 감수 배포 가능(결정 4) |
| OkHttp를 common으로 못 옮김(순수 KMP화 시) | 낮 | 데스크톱도 JVM이라 OkHttp 그대로 사용 가능. Ktor 전환은 선택 |
| 라디오 비공식 API 변경 | 상존 | 안드로이드와 코드 공유라 한 번 고치면 양쪽 반영(오히려 이득) |

## 6. 규모(개략)
- Phase 0: 작음(1~2일). **여기서 방향이 확정된다.**
- Phase 1~3: 중간(멀티플랫폼 리팩터 + 데스크톱 구현/UI).
- Phase 4: 작~중(서명/공증 절차 포함).

## 7. 내가 할 수 있는 것 / 사용자만 할 수 있는 것
- **내가**: 전 코드 작업, Phase 0 실증(이 Mac에서 macOS분 검증), 빌드·패키징 스크립트, GitHub Actions.
- **사용자만**: Windows 실기 검증(이 환경엔 Windows가 없음), Apple Developer/코드서명 인증서 준비(서명 택할 경우), 최종 배포 승인.

## 8. 다음 액션
결정 1~4 확인(대부분 권장안 그대로면 됨) → **Phase 0 실증**부터 진행. Phase 0이 통과해야
나머지가 의미 있으므로, 여기에 먼저 착수하는 것을 권장.
