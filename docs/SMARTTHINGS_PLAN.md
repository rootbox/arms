# Simple Radio × SmartThings — 1-pager (2026-10-07)

**목표**: 벽걸이 태블릿(홈 플레이어)을 SmartThings의 기기 하나로 등록해, SmartThings 앱·루틴·빅스비로 켜기/끄기, 재생/일시정지/정지, 이전/다음, 볼륨·음소거, 채널 선택을 한다.
**예전 연동과 다른 점**: 눈치(Nunchi)는 "SmartThings를 *제어하는* 앱"(REST API·OAuth)이었다. 이번은 반대로 "SmartThings가 *제어하는* 기기"다 → API 호출이 아니라 **기기 통합(device integration)** 방식을 골라야 한다.

## 1. 선택지 비교 (공식 문서·커뮤니티 확인, 2026-10)
| 방식 | 구조 | 필요 조건 | 인증 없이 내 계정 사용 | 판단 |
|---|---|---|---|---|
| **A. Edge LAN 드라이버** | 집의 SmartThings 허브에서 도는 Lua 드라이버가 같은 와이파이의 태블릿과 직접 통신 | 집마다 Edge 지원 허브(Station·Hub v2/v3·Aeotec) | 가능(개인 채널 배포) | **1순위** — 로컬(0.1~0.3초), 인터넷 끊겨도 루틴 동작, 비용·방화벽 변경 없음 |
| **B. Schema(클라우드 연동)** | ST 클라우드 → NAS 웹훅 → MQTT로 태블릿 | NAS에 OAuth 서버+커넥터, ST 호출 허용 | 가능(Console 드래프트 + 개발자 모드) | **2순위** — 허브 없이 두 집 커버. 단 ST 호출이 도쿄 AWS에서 올 가능성 높음 → 현재 "국내만 허용" 방화벽과 충돌 |
| C. 웹훅 SmartApp 소유 기기 | SmartApp이 만든 기기의 명령을 웹훅으로 받음 | 공개 HTTPS | 가능 | 비추천 — 웹훅 SmartApp 존속 불확실(2026 커뮤니티) |
| D. 가상 기기(Virtual Device) | 상태만 바뀌고 명령이 밖으로 안 나옴 | — | 가능 | 비추천 — 다음/이전 같은 명령 전달 불가 |
| E. Matter | 태블릿을 Matter 기기로 | Matter 컨트롤러 허브, NDK 빌드 | 불확실 | 비추천 — 프리셋·곡 정보 없음, 작업 큼 |
| F. Device SDK(직접 연결) | 태블릿이 ST 클라우드에 직접 | SoftAP 온보딩·기기 인증서 | 사실상 불가 | 비추천 |

## 2. 제안: "로컬 제어 API 하나 + 얇은 어댑터"
1. **앱 안(호스트 기능)에 LAN 제어 API를 하나 둔다.** 명령·상태 스키마는 원격 제어(`RemoteCommand`/`HostState`)를 그대로 쓴다 → 리모컨·스마트싱스·향후 다른 플랫폼이 같은 계약을 공유.
   - `GET /state`, `POST /command`, `GET /events`(SSE 실시간 상태), mDNS `_simpleradio._tcp` 광고, 페어링 토큰 인증(태블릿 화면에 6자리 코드 → ST 기기 설정에 입력), LAN 전용.
   - 덤: 집 안에서는 폰 리모컨도 브로커 없이 LAN으로 바로 붙을 수 있다(더 빠르고, 인터넷 장애에도 동작).
2. **어댑터 A — Edge 드라이버(권장)**: `smartthings/edge-simpleradio/`(Lua). mDNS로 태블릿 발견 → 기기 생성, 명령 → `POST /command`, `/events` → capability 이벤트.
3. **어댑터 B — Schema 브리지(허브가 없는 집용, 선택)**: NAS 컨테이너가 MQTT 게스트로 붙어(기존 QR 페어링·종단 암호화 재사용) ST 명령을 중계. ST 출발 IP 실측 후 해당 AWS 대역만 443 허용.
4. **별도 앱이 아니라 Simple Radio의 기능**으로 둔다(재생 세션·채널 목록·호스트 서비스를 그대로 써야 함). ST 쪽 산출물(드라이버·브리지)만 저장소 안 별도 디렉터리.

## 3. SmartThings 기기 모양 (공식 Sonos 드라이버와 같은 조합)
- 카테고리 **Speaker**, capability: `switch` · `mediaPlayback`(play/pause/stop) · `mediaTrackControl`(next/previous) · `audioVolume` · `audioMute` · **`mediaPresets`**(채널 5개 + NAS 즐겨찾기, `playPreset`) · `audioTrackData`(제목·아티스트·커버 URL) · healthCheck.
- **켜기/끄기 의미**: ON = 직전 채널 재생 + 화면 켜기(액자 모드), OFF = 정지 + 화면 끄기(기기 관리자 권한 `lockNow`, 사용자 동의 1회). 태블릿 전원 자체는 앱이 켤 수 없다.
- **빅스비(한국)**: "거실 라디오 켜줘/꺼줘"(switch)는 될 가능성 높음. "다음 채널"은 불확실 → 채널별 장면(Scene) 등록 후 "OOO 틀어줘"로 대체.
- **루틴 예**: 양평 도착 시 라디오 켜기, 외출 시 끄기, 아침 7시 KBS, 취침 시 음량 낮추고 30분 뒤 끄기.

## 4. 단계와 일정 (예상)
| 단계 | 내용 | 기간 | 게이트 |
|---|---|---|---|
| **P0 확인** | ① 양평·서울에 Edge 지원 허브가 있는지(모델명; TV 내장 허브는 LAN 드라이버 불확실) ② 모의 기기 드라이버로 한국 ST 앱에서 Speaker 카드·프리셋 표시 확인 | 0.5일 | 허브 없으면 B로 전환 |
| P1 앱 | 호스트 LAN API(SSE·mDNS·토큰), 화면 켜기/끄기, 단위 테스트 | 2~3일 | 태블릿 실측 |
| P2 드라이버 | Edge 드라이버(발견·명령·상태·healthCheck), 개인 채널 배포 | 2~3일 | ST 앱·루틴 실측 |
| P3 마감 | 빅스비·장면·두 집 구성, 문서, 정식 릴리즈 | 1일 | 실사용 1주 |
| (B 경로) | NAS Schema 브리지 + OAuth 미니 서버 + ST 출발 IP 실측·방화벽 | +3~4일 | 허브 없을 때만 |

## 5. 리스크
- 허브 부재(→ B 경로, 방화벽 예외 필요), TV 내장 허브의 LAN 드라이버 지원 불확실, 한국 ST 앱의 미디어 카드 렌더링·빅스비 문구 지원 범위(P0에서 확인), 앨범 아트 URL은 인터넷에서 접근 가능해야 표시됨(앱 내장 채널 아트는 공개 URL로 제공 필요).
- 2025-08 이후 Developer Workspace에서 Schema 편집 불가(Console·CLI 사용), Workspace 폐지 진행 중 → Edge·CLI 기반이 장기적으로 안전.

## 6. 결정 요청
1. 각 집의 SmartThings 허브 모델(없다면 B 경로 진행 여부).
2. OFF 시 화면 끄기(기기 관리자 권한) 포함 여부.
3. 진행 순서: 현재 원격 제어 실기기 검증 완료 후 P0 착수 제안.

출처: SmartThings 개발자 문서(cloud-connected get-started·auth-server·schema-app·interaction-types), SmartThingsEdgeDrivers(sonos·matter-media 프로필), smartthings-core-sdk(virtualdevices), 커뮤니티(Supported Edge hubs, webhook SmartApp의 미래, PAT 변경, TV 허브).

## 7. 진행 상태 (2026-10-07)
- 결정: Edge LAN 드라이버 경로, 앱 내 기능, 양평 허브("스마트 홈 허브", V4, LAN/Edge 지원)만 사용, 끄기=라디오만 정지.
- **구현 완료**: 앱 0.9.0-rc2(`feature/smartthings`, 호스트 로컬 제어 API v1 `smartthings/LAN_API.md` + 폰 리모컨에서 "스마트싱스 연결 허용"), Edge 드라이버 `smartthings/edge-simpleradio`(개인 채널 `rootbox-private`에 배포, 허브 설치).
- **실허브 검증**: 주변 기기 검색 → "Simple Radio" 기기 생성 → 태블릿 허용 창에서 자동 페어링 → ONLINE(상태·프리셋 5개·곡 정보·커버). ST→태블릿 명령 10종(on/off/next/prev/volume/mute/unmute/preset/pause/play) 모두 반영, 상태 4초 내 동기화. **앱 강제 종료·재시작, 드라이버 재설치 뒤에도 재승인 없이 유지**(토큰은 태블릿 암호화 저장소와 허브 영구 필드에 보관). 창(10분)은 최초 토큰 발급에만 쓰인다.
- 실허브에서 잡은 결함: `device:get_field`가 없는 필드에 값을 0개 돌려줘 `tostring()` 인자 오류로 세션이 5초마다 죽음(테스트 더블은 nil 반환이라 미검출) → 수정. 교훈: 허브 Lua API의 "값 없음"은 nil이 아닐 수 있다.
- 관찰: 프리셋 전환 직후 볼륨이 27→13으로 바뀜(앱은 명령 외에 볼륨을 만지지 않음; 블루투스 수신기의 절대 볼륨 동기화로 추정, 추후 확인).
- 남은 것: 폰(S25)에서 리모컨 "스마트싱스" 카드 실측 후 정식 v25, 빅스비·루틴 실사용 확인, 태블릿 MQTT 브로커 설정을 NAS로 전환(사용자 비밀번호 입력), 집 밖 커버 이미지(태블릿 LAN URL은 집 안에서만 보임).
- **2026-10-07 정식 v25 = 0.9.0 발행(latest)**: S25에서 리모컨 "스마트싱스" 카드 실측(허브 1대 표시, 폰에서 허용 창 열기→태블릿 창 열림·카운트다운, NEXT가 태블릿·ST 양쪽 반영). 두 기기 모두 NAS 브로커 사용 확인(Mac 개발 브로커 종료). 태블릿은 rc2(코드 동일, versionCode 같아 배너 없음) — 다음 USB 연결 때 정식 설치.
