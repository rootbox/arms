# Play Store 정식 릴리즈 계획 — NAS 뮤직 전용 앱

> 목표: 라디오 수신 기능을 제외하고 **Synology NAS 음악 재생**만 담은 앱을 Google Play Store에 정식 출시.
> 작성: 2026-07-26. 이 문서는 검토용 계획서이며, 아래 "결정 필요" 항목을 확정해야 코드 리팩터링을 시작할 수 있습니다.

---

## 0. 요약 (TL;DR)

- **가능합니다.** 다만 "라디오 제거"는 단순 삭제가 아니라 **큰 리팩터링**입니다. 라디오와 NAS가 메인 화면·미니플레이어·전체화면 플레이어·MediaSession·차량 서비스·저장소를 **공유**하고 있어서, 공유 코드에서 라디오 분기만 걷어내는 작업이 필요합니다.
- **실제 출시는 사용자만 할 수 있는 외부 단계에 막혀 있습니다**: Google Play 개발자 계정($25), 개인정보처리방침 호스팅, Play Console의 데이터 보안/콘텐츠 등급 양식, 그리고 (차량 지원을 유지한다면) **Android Auto 앱 품질 심사**. 코드가 아무리 빨라도 이 단계들이 병목입니다.
- 그래서 이번에 자율로 진행한 것: **(a) 이 계획서, (b) 개인정보처리방침 초안, (c) 스토어 등재 문안 초안, (d) AAB(Play 제출 포맷) 빌드 경로 검증** — 모두 코드 동작을 바꾸지 않는 준비 작업이라 `playstore-prep` 브랜치에 담았습니다.
- **다음 단계**: 아래 §2 "결정 필요" 4가지를 확정해 주시면, 그에 맞춰 코드 리팩터링(§4)을 진행합니다.

---

## 1. 현재 구조 진단 (왜 단순 삭제가 안 되는가)

| 영역 | 라디오 결합 상태 |
|---|---|
| `MainActivity.kt` (약 4,000줄 중 라디오 참조 ~110곳) | 최상위 컴포저블이 `RadioPlayerScreen`이고 그 안에 라디오/내음악 **두 탭**이 함께 있음. `MiniPlayerBar`·`NowPlayingDetailScreen`은 라디오·NAS **공용**(`ActivePlayback` sealed class로 분기) |
| `ARMSMediaLibraryService.kt` (라디오 참조 ~73곳) | `LiveRadioPlayer` 래퍼, SBS 라우드니스 보정(`LoudnessEnhancer`), 브라우즈 트리의 `[FAVORITES]`·`[ALL_RADIO]` 폴더, 편성정보 fetch, 채널 전환 로직 |
| `core/data` | `StationRepository`·`StationDao`·`StationEntity`·`AppDatabase`(라디오 방송국 DB), `PlaybackStateStore.LastPlayed.Radio` |
| `core/network` | `RadioApi.kt`(방송사 스트림/편성/커버 조회) |
| `core/model` | `Station` 모델 |
| 빈 모듈 | `core/radio`, `core/streaming` = **소스 0개**(레거시). settings에서 제거 가능 |

**결론**: NAS 기능(`ui/nas/*`, `auto/NasBrowseTree`, `NasMusicRepository`, `NasPlaylistRepository`, `SynologyMusicApi`, `PlaylistDatabase`)은 비교적 독립적이지만, **앱의 뼈대(메인 화면·플레이어 UI·서비스)가 라디오 중심**이라 그 뼈대를 NAS 중심으로 재구성해야 합니다.

---

## 2. 결정 필요 (⚠️ 이걸 정해야 리팩터링 시작 가능)

### 결정 1 — 제품 구조: 기존 라디오 앱을 어떻게 할 것인가?
- **(A) 빌드 플레이버 분리 (권장)**: 한 코드베이스에 두 빌드.
  - `full` = 라디오+NAS, 사이드로드(현재 `com.arms.androidauto`), 인앱 자동업데이트 유지.
  - `nas` = NAS 전용, Play Store용, 별도 applicationId, 자동업데이트/라디오 권한 제거.
  - 장점: 개인용 라디오 앱을 **잃지 않음**. 단점: 공유 코드에 라디오 분기를 플래그(`BuildConfig`)/소스셋으로 갈라야 해서 초기 작업량↑·유지보수 부담.
- **(B) 완전 전환(pivot)**: 앱 전체를 NAS 전용으로 바꾸고 라디오 코드 삭제.
  - 장점: 코드가 가장 깔끔. 단점: **개인용 라디오 기능을 잃음**(지금 SBS/KBS/KPOP 자주 쓰심).
- 👉 라디오를 계속 쓰신다면 **(A)**, 라디오를 접어도 된다면 **(B)**. 제 추천은 **(A)**.

### 결정 2 — 패키지명(applicationId)과 앱 이름
- applicationId는 **출시 후 영구 고정**(변경 불가). Play Store용으로 새 id 권장.
  - 후보: `space.n1319.nasmusic`, `com.arms.nasmusic`, `com.arms.nasplayer`.
- 앱 이름 후보(‘Synology’ 상표는 이름/아이콘에 사용 금지):
  - "홈뮤직", "NAS 뮤직", "MyNAS Music", "1319 Music", "홈서버 뮤직 플레이어".
- 👉 applicationId 1개, 표시 이름 1개를 골라주세요.

### 결정 3 — Android Auto(차량) 지원을 Play 버전에 포함할지
- 이 앱의 핵심 가치가 "차에서 NAS 음악 듣기"라 **포함 권장**. 단, 포함하면 Google의 **Android Auto 앱 품질 심사(Cars 카테고리)** 를 통과해야 하고, 일반 앱보다 심사가 까다롭고 오래 걸립니다(§5 별도 체크리스트).
- 포함 안 하면 심사가 훨씬 단순해지지만 차량에서 안 뜹니다.
- 👉 "차량 포함(권장)" / "폰 전용" 중 선택.

### 결정 4 — 인앱 자동 업데이트(UpdateChecker) 처리
- Play Store 정책상 **앱이 스스로 APK를 내려받아 설치하는 기능은 금지**입니다(`REQUEST_INSTALL_PACKAGES`). Play 버전에서는 **반드시 제거**해야 합니다(업데이트는 Play가 담당).
- 결정 1이 (A)면 `full`에는 남기고 `nas`에서만 제거, (B)면 완전 제거.
- 👉 결정 1에 종속. 별도 입력 불필요.

---

## 3. Play Store 요구사항 체크리스트 (게이트)

| 항목 | 상태/조치 | 담당 |
|---|---|---|
| Google Play 개발자 계정($25 1회) | 필요 — 계정 생성은 제가 못 함 | **사용자** |
| AAB(Android App Bundle) 제출 | ✅ 빌드 검증 완료(`bundleRelease` → app-release.aab) | 완료 |
| Play App Signing | 업로드 키 = 지금 만든 릴리즈 키. Play가 실제 서명키 관리 | 사용자(콘솔 설정) |
| targetSdk 35 | ✅ 이미 충족(Play 최소 요건 이상) | 완료 |
| 개인정보처리방침 URL(공개) | 초안 작성함(`docs/PRIVACY_POLICY.md`). **공개 URL로 호스팅 필요**(1319.space WordPress 등) | 사용자(호스팅) |
| 데이터 보안(Data safety) 양식 | 문안 초안 제공(`docs/STORE_LISTING.md`). 콘솔에서 제출 | 사용자(콘솔) |
| 콘텐츠 등급 설문 | 전체이용가 예상. 콘솔에서 설문 | 사용자(콘솔) |
| 권한 정리 | `REQUEST_INSTALL_PACKAGES` 제거(정책), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 제거 검토(민감 권한) | 나(코드) |
| 포그라운드 서비스(mediaPlayback) 선언 | 콘솔 FGS 선언 필요(미디어 앱이라 승인 가능) | 사용자(콘솔) |
| 스토어 등재 자산 | 아이콘512, 피처그래픽1024×500, 폰 스크린샷, (차량 시)차량 스크린샷 | 나(초안/가이드)+사용자(최종) |
| 앱 아이콘/이름에 상표 미사용 | ‘Synology’ 미사용 확인 | 나 |
| Android Auto 품질 심사(차량 포함 시) | §5 체크리스트 | 나(코드)+사용자(제출) |

---

## 4. 코드 리팩터링 계획 (결정 확정 후 내가 진행)

> 아래는 결정 1=(A) 플레이버 분리, 결정 3=차량 포함 가정. (B)면 "플레이버" 대신 "삭제"로 읽으면 됩니다.

**단계 R1 — 빌드 구성**
- `settings.gradle.kts`에서 빈 모듈 `core:radio`, `core:streaming` 제거.
- `app/build.gradle.kts`에 `flavorDimensions("edition")` + `full`/`nas` 프로덕트 플레이버. `nas`는 별도 `applicationId`·`resValue`(앱 이름)·`buildConfigField("Boolean","ENABLE_RADIO",...)`.
- `buildFeatures { buildConfig = true }` 활성화.

**단계 R2 — 라디오/공유 코드 분기**
- `MainActivity`: 진입 컴포저블을 NAS 중심으로. `ENABLE_RADIO=false`면 하단 탭에서 ‘라디오’ 제거, 시작 탭=내음악, `RadioTabContent` 경로 비활성.
- `MiniPlayerBar`/`NowPlayingDetailScreen`: `ActivePlayback.Radio` 분기 제거(nas 소스셋) 또는 플래그 가드.
- `ARMSMediaLibraryService`: `nas` 플레이버에서 `[FAVORITES]`·`[ALL_RADIO]`·`LiveRadioPlayer`·라우드니스·편성 fetch 제외. 루트 = 최근재생/플레이리스트/아티스트만.
- `PlaybackStateStore`: `LastPlayed.Radio` 경로는 nas에서 미사용(폴백을 "없음"으로).
- 자동 재개: 라디오 폴백 대신 "마지막 NAS 소스" 또는 무재생.

**단계 R3 — 라디오 전용 자산 제거(nas)**
- nas 소스셋/플레이버에서 `RadioApi`, `StationRepository/Dao/Entity`, 라디오 DB, `Station` 모델 의존 제거.
- `UpdateChecker` + `REQUEST_INSTALL_PACKAGES` 제거(nas). `FileProvider`(artwork/updater)도 nas에서 불필요하면 제거(현재 NAS 커버는 https 직접 로드라 content:// 프로바이더 불필요).

**단계 R4 — 매니페스트/권한 정리(nas)**
- `nas` 소스셋 `AndroidManifest.xml`: 라디오 관련 없음. 권한은 `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`만. 배터리 최적화 제외 권한 제거 검토.
- 앱 이름/아이콘: NAS 뮤직용으로.

**단계 R5 — 브랜딩/아이콘/테마**
- 앱 이름 문자열, 런처 아이콘(‘라디오’ 느낌 제거), 스플래시/색상 점검.

**단계 R6 — 빌드/검증**
- `./gradlew :app:bundleNasRelease` → 서명 AAB.
- 실기기 설치·NAS 연동·앨범아트·이어듣기·(차량 포함 시)Android Auto 스모크 테스트.

각 단계는 독립 커밋으로 나눠 검토 가능하게 합니다.

---

## 5. (차량 포함 시) Android Auto 품질 심사 체크리스트

- 미디어 앱 템플릿만 사용(커스텀 UI 금지) — 현재 Media3 `MediaLibraryService` 사용 ✅
- 운전 중 조작 최소화(브라우즈 깊이·탭 수 제한) — 현재 아티스트→앨범 2단계 ✅
- 콘텐츠 스타일/플레이 가능·브라우즈 가능 플래그 정확성 점검
- 에러 상태(로그인 실패/네트워크 없음) 처리 UX 점검
- Play Console에서 "Android Auto" 폼팩터 대상 지정 + 심사 제출 → **Google 수동 검수 통과 필요**
- 데모/테스트 계정 없이 검수 가능해야 함(NAS 로그인 필요 앱이라, 검수자에게 어떻게 시연할지 설명/영상 준비 필요) ⚠️ 리스크

> ⚠️ NAS 로그인이 필수라 심사자가 실제 재생을 확인하기 어려울 수 있음. 데모 모드/샘플 콘텐츠 또는 시연 영상 제공을 검토.

---

## 6. 저작권/정책 관점

- 사용자가 **본인 소유 NAS의 본인 음원**을 스트리밍하는 클라이언트 → 저작권 문제 없음(개인 파일 접근). 스토어 설명에 "개인 NAS의 음악을 재생하는 클라이언트"임을 명확히.
- ‘Synology/Audio Station’ 상표는 설명에 "호환" 정도로만 언급, 이름/아이콘엔 미사용.

---

## 7. 사용자만 할 수 있는 외부 단계 (요약)

1. Google Play 개발자 계정 생성/결제.
2. `docs/PRIVACY_POLICY.md`를 공개 URL로 호스팅(예: 1319.space WordPress 페이지).
3. Play Console: 앱 생성 → 앱 서명 설정(업로드 키 등록) → AAB 업로드 → 데이터 보안/콘텐츠 등급/FGS 선언/개인정보 URL 입력.
4. 스토어 등재: 아이콘/스크린샷/피처그래픽 업로드(제가 문안·규격 제공).
5. (차량 포함) Android Auto 대상 지정 + 심사 제출.
6. 내부 테스트 → 비공개 테스트 → 프로덕션 순 출시.

---

## 8. 이번 세션 산출물 (playstore-prep 브랜치)

- `docs/PLAYSTORE_RELEASE_PLAN.md` (이 문서)
- `docs/PRIVACY_POLICY.md` (개인정보처리방침 초안 KR/EN)
- `docs/STORE_LISTING.md` (스토어 문안·데이터 보안 답변·권한 사유 초안 KR/EN)
- AAB 빌드 경로 검증 완료(코드 변경 없음)

**다음 액션**: §2의 결정 1·2·3을 알려주시면 §4 리팩터링을 진행하겠습니다.
