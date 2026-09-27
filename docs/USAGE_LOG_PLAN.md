# 실사용 로그 기반 개선 계획 (2026-09-27, S22 rc23 로그)

> 대상 로그: Galaxy S22(무선 Android Auto, XM) 2026-09-27 12:01~19:40 logcat(main/system/events).
> 앱 pid 373이 전 구간 생존, ANR·크래시·LMK·프로세스 재시작 0, 스톨/언더런 0, 재생 기동 0.85s.
> 즉 "동작"은 문제없고, 남은 개선은 **자원 낭비**와 **차량 첫 화면**이다.

## 1. 로그에서 확인된 사실

| # | 현상 | 수치 | 근거 |
|---|---|---|---|
| F1 | 재생 중 미디어 알림이 **내용 변화 없이 6초마다 재게시** | 166회/약 15분 재생, 시간당 ≈600회, 매회 `startForeground` 재호출(SecFgs `isForeground:true` 50회) | `notification_enqueue` id=1001 동일 flags/actions; Media3 1.3.1 `MediaNotificationManager.onEvents`가 `EVENT_TIMELINE_CHANGED`에도 알림을 다시 그림(바이트코드 확인). HLS 라이브 플레이리스트 갱신 = 타임라인 변경 |
| F2 | 세션 PlaybackState 3초 주기 재발행, 레거시 Metadata 2초 주기 갱신 | 89회 / 150회 | Media3 periodic position update(3000ms) + 라이브 duration 변동 |
| F3 | KBS 편성 API **8초 폴링**, 매회 18.9KB | 35회/5.5분 → 시간당 ≈450회·8.5MB(셀룰러) | `okhttp GET pprogramapi.kbs.co.kr … 18948-byte body`; 실제 프로그램 변경은 세션당 1회 |
| F4 | **릴리즈 빌드에서 OkHttp BODY 로깅 활성** | pid 373 로그 1,344줄 중 866줄이 okhttp, 최대 4,100자/줄 | `core/network/NetworkClient.kt` 로깅 인터셉터가 빌드 타입 무관 |
| F5 | AA 연결 직후 세션 메타데이터가 비어 카드가 빈 상태 | 재생 전 3초간 17회 경고 | `GH.MediaPlaybackMonitor: Invalid metadata, no title and subtitle.` |
| F6 | Cursor 미해제 경고(앱 프로세스) | GC 직후 2회씩 2번 | `A resource failed to call AbstractCursor.close.` 스택 없음; 앱 코드에 Cursor 직접 사용 없음 |
| F7 | 모든 세션이 사용자 정지가 아닌 **AA 프로젝션 종료**로 끝남, 짧은 세션(1~2분) 반복 | 5세션 중 2건 | `PROJECTION_ENDED_BYEBYE_BY_CAR`, LOSS→NONE 1초 이내(정상) |
| F8 | 내비 TTS 덕킹 잦음 | 49분간 73회 | `unfadeOutUid uid:10336`(네이버맵 `LOSS_TR_CAN_DUCK`) — 앱 이상 아님 |

배터리(dumpsys batterystats): 앱 웨이크락 없음(오디오 서버 `AudioMix`만), 스크린 오프 wakelock 16s, CPU 소량. 절대량은 작지만 F1·F3은 시스템 전체(SystemUI·런처·AA 파서)를 6초마다 깨우는 구조적 낭비다.

## 2. 결정: 정식 v20은 rc28 그대로, 개선은 0.7.1(versionCode 21)

rc28은 태블릿 검증이 끝났고 실차 재확인만 남았다. 여기에 알림·폴링 변경을 얹으면 검증을 다시 해야 하므로
**v20 = rc28(실차 OK 후)**, 아래 항목은 **0.7.1 rc 채널**에서 진행한다.

## 3. 작업 항목 (우선순위 순)

### P0 — 0.7.1에 넣는다
| 항목 | 내용 | 검증 지표 |
|---|---|---|
| **A. 알림 재게시 억제** (F1) | `MediaSessionService.onUpdateNotification(session, startInForegroundRequired)` 오버라이드. (playWhenReady, playbackState, mediaId, title, artist, 커버 해시, startInForegroundRequired) 지문이 직전과 같으면 super 호출 생략. 지문이 바뀌거나 첫 게시·포그라운드 전환이 필요하면 항상 통과(FGS 시작 누락 금지). 순수 함수 `NotificationUpdatePolicy`로 떼어 단위 테스트 | KBS 재생 10분간 `notification_enqueue` ≤ 프로그램/상태 변경 횟수(+1); `GH.MsgNotifParser` 경고 0; SecFgs `isForeground:true` 세션당 1회 |
| **B. 편성 API 스케줄 폴링** (F3) | KBS `program_etime`, SBS `endtime`이 있으므로 "현재 프로그램 종료 시각 + 15~45s 지터"에 다음 조회. 상한 10분(편성 변경 대비), 실패 시 30s→60s→120s 백오프. 채널 전환·재생 시작 시 즉시 1회(현행 유지). K-POP(ICY 인밴드·20s 캐시)은 그대로 | 시간당 편성 요청 ≤ 12회, 응답 반영 지연 ≤ 1분(프로그램 경계에서 측정) |
| **C. 로깅 인터셉터 debug 한정** (F4) | `core/network`에 `enableHttpLogging` 플래그를 두고 앱이 `BuildConfig.DEBUG`로 주입. 릴리즈에서 okhttp 로그 0줄 | 릴리즈 APK logcat에 `okhttp.OkHttpClient` 0줄 |
| **D. AA 연결 직후 세션 메타 선세팅** (F5) | 서비스 onCreate에서 마지막 재생 채널을 `quickPlayableItem`으로 `setMediaItem`만(prepare 없음) 해 두어 세션에 title/artist/채널 아트가 보이게. `MainActivity` 열기 동기화는 `hasItems`만 보지 말고 `playWhenReady/isPlaying`까지 확인(정지 항목을 "재생 중"으로 오인 금지). `onPlaybackResumption` 경로와 충돌 없는지 회귀 | AA 연결 후 재생 전 `Invalid metadata` 경고 0; 앱 열기 시 정지 상태 정확 표시 |

### P1 — 조사 후 결정
| 항목 | 내용 |
|---|---|
| **E. 3초 상태·2초 메타 재발행** (F2) | `MediaSession.Builder.setPeriodicPositionUpdateEnabled(false)`는 NAS 진행바(AA·BT)에 영향 → 라디오만 끄는 옵션이 없으므로 실측 후 결정. 라이브 duration을 레거시 메타에 싣지 않는 방법(Media3 버전별 동작)도 함께 조사 |
| **F. Cursor 누수 지점** (F6) | debug 빌드에 `StrictMode.VmPolicy.detectLeakedClosableObjects().penaltyLog()`를 켜 스택 확보. 앱 코드가 아니면(이미지 로더/Media3 레거시 스텁 추정) 라이브러리 업그레이드 항목으로 이관 |
| **G. AA 재연결 자동 재개** (F7) | 13:42 해제→13:44 재생이 수동인지 AA `onPlaybackResumption`인지 ARMS 로그로 확인(현재 재개 경로 로그 추가). 수동이면 "N분 이내 재연결 시 자동 재개"를 `BluetoothModePolicy`와 같은 순수 정책으로 추가 |

### P2 — 별도 릴리즈(0.8)
| 항목 | 내용 |
|---|---|
| **H. Media3 1.3.1 → 1.8.x** | Android 15+ 백그라운드 재개 강건성, 알림 갱신 이벤트 축소, 라이브 메타 처리 개선. HLS/MP3 양쪽 실기기 회귀 + 태블릿·S24 BT 회귀 필수 |
| I. 덕킹 볼륨 (F8) | ExoPlayer 기본 0.2 대신 0.35~0.5 검토. 사용자 체감 문제 제기 시에만 |

## 4. 진행 순서
1. 실차 재확인(XM AA·MINI BT) → **v20 정식 태그**(rc28 기준).
2. 0.7.1-rc1: C(로깅) + A(알림) → S22에서 KBS 10분 재생 지표 측정.
3. 0.7.1-rc2: B(폴링) + D(세션 선세팅) → S22·태블릿 회귀(재생 시작·채널 전환·앱 열기 동기화·AA 카드).
4. E·F·G 조사 결과에 따라 0.7.1 포함 여부 결정 → 실차 확인 → v21.
5. H는 0.8 브랜치에서 별도.

## 5. 측정 방법(재현 절차)
```bash
adb -s <serial> logcat -c
# KBS 10분 재생 후
adb -s <serial> logcat -d -b events | grep -c "notification_enqueue.*com.arms"
adb -s <serial> logcat -d | grep -c "okhttp.OkHttpClient"
adb -s <serial> logcat -d | grep -c "GH.MsgNotifParser.*com.arms"
adb -s <serial> logcat -d | grep -c "GH.MediaPlaybackMonitor: Invalid metadata"
```

## 6. 진행 상태
- **0.7.1-rc1 (2026-09-27, v21-rc1)**: A(알림 억제) + C(로깅 한정) 적용. S22에서 KBS 10분 재생 측정: `notification_enqueue` 0회(rc23은 같은 조건에서 약 100회), SecFgs `isForeground:true` 재호출 0, okhttp 로그 0줄, 크래시 0, 재생 유지. 시작 시 게시 3회(버퍼링→재생 상태 전환·정보 반영)는 의도한 동작. 정식 v20은 `release/0.7.0` 브랜치(rc28 코드, versionName만 0.7.0으로) 에서 태그한다.
