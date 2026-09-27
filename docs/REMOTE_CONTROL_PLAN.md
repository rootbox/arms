# 원격 제어(호스트/게스트) 계획 — 벽걸이 태블릿을 폰으로 조작 (2026-09-27)

## 0. 요구사항(사용자 정의)
- 폰(게스트)에서 태블릿(호스트)의 Simple Radio를 제어: 무엇이 재생 중인지 보고 재생/정지/이전/다음.
- 태블릿의 블루투스 스피커 연결이 끊기면 폰에서 재연결.
- 기기별로 호스트/게스트 역할을 설정.
- 화면 미러링(VNC류) 없음 — **기능 UI만** 원격.
- 같은 Wi-Fi가 아니어도(외부망) 제어 가능.
- 주고받는 정보는 호스트/게스트만 복호화 가능해야 하고, **제어에 필요한 것 외의 정보는 절대 오가지 않는다.**

## 1. 결정 1 — Simple Radio에 통합 (별도 앱 아님)

| 관점 | 통합 | 별도 호스트/게스트 앱 |
|---|---|---|
| 호스트 쪽 재생 제어 | 이미 있는 `ARMSMediaLibraryService` 세션에 직접 붙는다(상태·명령·커버 모두 내부 호출) | 별도 앱은 `MediaController`로 붙어야 하고, 채널 목록·즐겨찾기·NAS 앨범 같은 앱 내부 개념은 다시 IPC로 노출해야 한다 |
| 게스트 UI | 지금의 재생 상세 패널·채널 목록 컴포저블을 **그대로 재사용**(데이터 소스만 원격) | 같은 화면을 두 번 만든다 |
| 배포·업데이트 | 하나의 APK, 하나의 rc/정식 채널, 인앱 업데이트 그대로 | 앱 2개 릴리즈·버전 호환 관리 |
| 보안 저장소 | NAS 자격증명과 같은 `EncryptedSharedPreferences` 경로 재사용 | 새로 구성 |
| 분리가 나은 경우 | — | "Simple Radio 외의 앱도 제어하는 범용 리모컨"이 목표일 때. 지금 목표가 아님 |

→ **통합**한다. 단, 프로토콜·암호·전송은 앱과 무관한 `core/remote` 모듈(순수 Kotlin/JVM, 단위 테스트)로 두어
나중에 데스크톱(맥에서 태블릿 제어)이나 별도 앱으로 떼어낼 수 있게 한다.
역할은 설정 화면의 **"이 기기의 역할: 홈 플레이어(호스트) / 리모컨(게스트) / 사용 안 함"**으로 정한다.
한 기기가 두 역할을 동시에 갖지는 않는다(태블릿=호스트, 폰들=게스트).

## 2. 결정 2 — 전송: NAS의 MQTT 브로커(WSS+TLS) + 종단간 암호화

외부망에서 폰→태블릿으로 가려면 **둘 다 접속하는 중계점**이 필요하다(둘 다 NAT 뒤). 후보:

| 후보 | 장점 | 단점 | 판단 |
|---|---|---|---|
| **NAS Mosquitto(Docker) + 리버스 프록시 WSS** | 이미 외부 노출된 NAS·LE 인증서·도메인 재사용, 제3자 없음, 유지 메시지(retained)로 "지금 상태" 즉시 수신, 라이브러리 성숙 | 새 서브도메인 + LE 인증서 바인딩 필요(리버스 프록시는 호스트 단위 라우팅만 됨), Container Manager 필요 | **채택** |
| Firebase(RTDB/FCM) | 인프라 0 | Google 경유(암호문이라도 메타데이터 노출), 프로젝트/키 관리, 정책상 "제어 외 정보 없음" 설명이 어려움 | 보류 |
| WebRTC 데이터채널(P2P) | 중계 없이 직접 | 시그널링 서버는 어차피 필요, NAT 통과 실패 시 TURN 필요, 복잡도 최고 | 기각 |
| Tailscale 등 VPN | 앱 코드 단순(LAN처럼) | 양쪽에 VPN 앱 상시 필요, 태블릿 상시 VPN 유지 리스크 | 기각 |

동작 원리:
- 토픽 `sr/<pairId>/state`(호스트→게스트, retained, QoS1), `sr/<pairId>/cmd`(게스트→호스트, QoS1), `sr/<pairId>/ack`.
- 브로커는 **암호문만** 본다. 페이로드는 페어링 키로 AES-256-GCM(또는 XChaCha20-Poly1305) 봉인. 브로커 계정(TLS+ID/PW)은 추가 방어선일 뿐 비밀의 근거가 아니다.
- 같은 Wi-Fi일 때 별도 LAN 직결 경로는 만들지 않는다(브로커 경유가 LAN에서도 수십 ms). 경로가 하나면 검증도 하나다.

## 3. 보안 설계
- **페어링**: 호스트(태블릿)가 QR 표시 → 게스트(폰)가 스캔. QR 내용 = `pairId`, 32바이트 대칭키, 브로커 URL, 브로커 계정. 한 번만, 같은 자리에서. 이후 QR은 폐기(화면 닫으면 재생성 불가, 재페어링 시 새 키).
- **저장**: 키·계정은 `EncryptedSharedPreferences`(NAS 자격증명과 동일). 채팅/로그/문서에 평문 노출 금지(기존 규칙 그대로).
- **메시지**: `{v, type, seq, ts, body}`를 봉인. `seq` 단조 증가 + `ts` ±2분 창으로 재전송 방지. 복호 실패·창 밖 메시지는 조용히 폐기하고 카운트만 남긴다.
- **정보 최소화(화이트리스트)**:
  - cmd: `play`, `pause`, `stop`, `next`, `prev`, `select{mediaId}`, `bt_reconnect`, `refresh`, `volume{0..100}`(선택).
  - state: `mediaId`, `title`, `artist`, `isPlaying`, `playbackState`, `artworkRef`(채널 아트 id 또는 공개 커버 URL — NAS 커버는 URL 대신 sid 없는 해시만), `bt{connected, deviceName}`, `battery%`, `updatedAt`.
  - 그 외(위치·네트워크·계정·NAS 자격증명·파일 목록)는 스키마에 존재하지 않는다. 스키마는 `core/remote`의 sealed class로 고정하고 직렬화 테스트로 필드 추가를 막는다.
- **권한 분리**: 게스트는 호스트의 NAS 앨범을 *이름*으로만 고른다(`select{mediaId}`). 스트림 URL·sid는 호스트 안에서만 만들어진다.
- **해제**: 양쪽 어디서든 "페어링 해제" → 키 삭제 + retained 상태 삭제(빈 메시지 publish).

## 4. 호스트(태블릿) 쪽 동작
- `RemoteHostService`(포그라운드, `connectedDevice` 타입 — Play 미배포라 타입 제약 여유 있음): 브로커 상시 연결(keepalive 60s, 자동 재접속 백오프), 세션 상태 변화 시 `state` publish(retained), `cmd` 수신 시 세션 호출. 알림 1개 "리모컨 대기 중"(상태 변화로 재게시하지 않음 — 0.7.1의 알림 억제 정책 재사용).
- 태블릿은 상시 전원·배터리 예외(이미 적용 절차 있음 §PROJECT 6.17)이므로 도즈 영향은 Phase 0에서 24시간 실측.
- **블루투스 재연결**: 앱이 A2DP 연결을 직접 명령하는 공개 API는 없다(`BluetoothA2dp.connect`는 시스템 권한). Phase 0에서 태블릿(Android 14, Samsung)으로 아래를 순서대로 실측해 되는 것을 채택:
  1. `MediaRouter2.transferTo(블루투스 라우트)` — 페어링된 미연결 기기가 라우트 목록에 뜨는지.
  2. 숨김 API 우회 호출(`BluetoothA2dp.connect`) — 권한 예외 여부.
  3. 안 되면 "재연결" 버튼은 **수신기 쪽 자동 재접속을 유도**하는 대체 동작으로: 상태를 게스트에 정확히 알리고(끊김 시각·기기명), 호스트가 무음 재생으로 A2DP 유휴 끊김 자체를 막는 "연결 유지" 옵션 제공. 어떤 결과든 게스트 화면에는 실제 가능한 동작만 노출한다.

## 5. 게스트(폰) 쪽 동작
- 하단 탭에 **"리모컨"** 추가(게스트 역할일 때만). 화면 = 지금의 재생 상세 패널 + 채널 목록(원격 데이터 소스) + 블루투스 상태 카드(연결됨/끊김·기기명·재연결 버튼).
- 화면이 열려 있을 때만 브로커 연결(백그라운드 상시 연결 없음). 열면 retained `state`로 즉시 채워지고, 명령 후 `ack`/새 `state`로 반영. 5초 내 응답 없으면 "태블릿 응답 없음(마지막 갱신 n분 전)".
- 폰 자체 재생과는 완전히 별개(폰의 라디오 탭은 그대로 폰 재생).
- 게스트는 여러 대 가능(S22·S24 모두). 각각 별도 페어링(키 공유하지 않음) 또는 같은 pairId에 게스트별 키 — Phase 1에서 "페어링 1회 = 게스트 1대"로 단순화.

## 6. 단계
| 단계 | 내용 | 산출/게이트 |
|---|---|---|
| **Phase 0 검증(1~2일)** | ① NAS에 Mosquitto(WSS+TLS, 계정) 올리고 외부망 폰에서 접속 ② 태블릿 BT 재연결 3가지 실측 ③ 태블릿 상시 브로커 연결 24h(재접속 횟수·배터리) ④ MQTT 라이브러리 선정(HiveMQ MQTT Client vs Paho) | 세 가지 모두 "가능/대체안" 확정 후 진행. ②는 기능 범위를 정한다 |
| Phase 1 코어 | `core/remote`: 메시지 스키마·봉인/개봉·재전송 방지·페어링 페이로드(순수 Kotlin, 테스트) + MQTT 전송 어댑터 | 단위 테스트, 브로커 왕복 스모크 |
| Phase 2 호스트 | 역할 설정, QR 페어링 화면, `RemoteHostService`(세션 브릿지·상태 publish·명령 처리·BT 상태 감시) | 태블릿에 설치, 폰 CLI(스크립트)로 명령 왕복 |
| Phase 3 게스트 | 리모컨 탭 UI, 응답 없음 처리, 페어링 해제 | S22·S24에서 외부망(LTE) 제어 실측 |
| Phase 4 마감 | 키 회전/재페어링, 로그·문서, 데스크톱 게스트(선택) | rc 채널 검증 → 정식 |

릴리즈: **0.8.0(versionCode 22~)**, 0.7.1(자원 개선)과 별개 rc 채널. 순서는 v20 정식 → 0.7.1 → 0.8.0.
브랜치 `feature/remote`에서 진행하고 0.7.1 병합 후 main에 합친다.

## 7. 리스크
- NAS 컨테이너·서브도메인·인증서 작업은 사용자 손이 필요(리버스 프록시·LE 바인딩은 DSM UI). 절차는 문서화하되 자격증명은 문서·채팅에 남기지 않는다.
- BT 재연결이 API로 불가하면 "재연결" 요구는 "연결 유지 + 정확한 상태 표시"로 축소된다 — Phase 0에서 결정.
- 태블릿 상시 연결이 Samsung 절전에 걸리면 호스트가 사라진 것처럼 보인다 → retained 상태에 `updatedAt`을 넣어 게스트가 "오래된 상태"를 구분.

## 8. 진행 상태
- **2026-09-27 · 0.8.0-rc1 (v22-rc1, feature/remote)**: Phase 1~3 코드 완료. `core/remote`(Pairing/JsonRemoteCodec/AesGcmSealedBox/ReplayGuard/MqttRemoteTransport(HiveMQ)/RemoteChannel/RemoteCli, 테스트 50), 호스트(`remote/host/`: RemoteHostService·BluetoothOutputMonitor·BluetoothReconnector·HostPairingScreen·HostStateBuilder·StatePublishPolicy, 테스트 23), 게스트(`remote/guest/`: RemoteGuestClient·GuestStatusPolicy·RemoteControlScreen·GuestPairingScreen, 테스트 13), 설정 메뉴·역할 다이얼로그·리모컨 탭·부팅 리시버 배선. 로컬 Mosquitto(ws://127.0.0.1:9001)에서 CLI 호스트↔게스트 왕복 확인(retained 상태 → next → ack → 갱신 상태).
- 교훈: HiveMQ의 WebSocket은 `io.netty:netty-codec-http`가 선택 의존성이라 빠지면 접속 시 `NoClassDefFoundError`. 단위 테스트(인메모리 전송)로는 안 잡히고 실제 브로커 왕복에서만 드러난다 → CLI 스모크를 릴리즈 게이트에 포함.
- Phase 0 잔여: ① NAS Mosquitto(docs/remote/nas-mosquitto) 사용자 작업 대기 ② 태블릿 BT 재연결 실측(`BluetoothReconnector` 로그로 판정) ③ 태블릿 24h 상시 연결.
- **rc2·rc3 (2026-09-27, S22 실기기)**: 게스트 — 코드 직접 입력 페어링, 리모컨 탭 상태 수신(≤1s), NEXT/항목 선택/재연결/새로고침 ack, 앱 재진입 retained 복원 모두 PASS. rc1 결함(연결 직후 새로고침 ack 누락→"응답 없음", 큰 커버로 컨트롤이 화면 밖, 리모컨 탭에 폰 미니플레이어 겹침)은 rc2에서 수정·재검증. 호스트 — 역할 전환·QR 생성·BLUETOOTH_CONNECT 요청·connectedDevice 포그라운드 서비스·브로커 retained publish PASS, 로그에 키/비밀번호/URL 노출 0건. Mac CLI 게스트로 폰 호스트에 select(KBS 재생)→next(SBS 전환 상태 수신)→stop(정지 상태 수신) 왕복 PASS, 크래시 0. rc3: 채널 준비 전 publish 시도(실패 로그 2건) 차단. S22는 rc3 설치·역할 "사용 안 함"으로 원복.
- 남은 것: 태블릿(호스트) 설치·BT 재연결 실측·24h 상시 연결(Phase 0 ②③), NAS 브로커(①), 그 뒤 S22·S24를 태블릿과 실제 QR 페어링. `feature/remote`는 Phase 0 완료 후 main에 합친다.
