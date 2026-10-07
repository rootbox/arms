# Simple Radio — SmartThings Edge LAN 드라이버

벽걸이 태블릿에서 도는 Android 앱 **Simple Radio**(호스트 모드)를 SmartThings 허브가 같은 와이파이에서 직접 제어하는 Edge 드라이버다.
클라우드를 거치지 않고 허브 ↔ 태블릿이 HTTP/1.1 + SSE로 통신한다. 계약(프로토콜)은 [`../LAN_API.md`](../LAN_API.md)이고, 이 드라이버는 그 문서를 그대로 따른다.

- 패키지 키 `simpleradio-lan`, 프로파일 `simpleradio`(카테고리 Speaker)
- 발견: mDNS `_simpleradio._tcp` → TXT `id`가 기기 네트워크 ID(DNI)
- 인증: 태블릿(또는 폰 리모컨의 "연결 허용 (10분)")에서 "스마트싱스 연결 허용"을 누른 뒤 10분 동안만 `POST /api/v1/pair`가 토큰을 발급 → 허브에 영구 저장
- 상태: `GET /api/v1/events`(SSE)로 실시간 수신, 끊기면 2/5/10/30초 백오프로 재연결, SSE가 계속 안 되면 10초 폴링

## 폴더 구조

```
edge-simpleradio/
├── config.yml                 # packageKey simpleradio-lan, 권한 lan + discovery
├── search-parameters.yml      # mDNS 서비스 _simpleradio._tcp
├── profiles/simpleradio.yml   # 기기 프로파일(능력 목록)
├── src/
│   ├── init.lua               # Driver 템플릿(발견·라이프사이클·명령 핸들러 등록)
│   └── simpleradio/
│       ├── sse.lua            # [순수] SSE 파서
│       ├── http.lua           # [순수] HTTP/1.1 요청 생성·응답 파싱(Content-Length/chunked/종료까지)
│       ├── state_map.lua      # [순수] 상태 JSON → 능력 속성 값, 변경분만 계산
│       ├── commands.lua       # [순수] 능력 명령 → /api/v1/command 본문, 낙관적 상태
│       ├── mdns_parse.lua     # [순수] st.mdns 응답 → {id, ip, port, name}
│       ├── policy.lua         # [순수] 재시도·백오프·헬스 규칙과 시간 상수
│       ├── client.lua         # [ST 비의존] HTTP 클라이언트(소켓·JSON 주입: 허브=cosock+st.json)
│       ├── transport.lua      # 허브용 소켓/JSON 바인딩
│       ├── api.lua            # 공용 클라이언트 + 기기 필드(IP/포트/토큰) 조회
│       ├── session.lua        # 기기별 백그라운드 작업(주소 확인→페어링→SSE/폴링→헬스)
│       ├── emitter.lua        # 상태 → capability 이벤트(변경분만)
│       ├── discovery.lua      # mDNS 발견 → LAN 기기 생성
│       ├── handlers.lua       # 명령·새로고침·라이프사이클 핸들러
│       └── fields.lua         # 기기 필드 이름
└── tests/                     # 패키징 시 CLI가 tests/** 를 제외함
    ├── run.sh                 # 전체 테스트 실행
    ├── fetch_lua_libs.sh      # 공식 lua_libs(integration_test 포함) 내려받기
    ├── mock_host.py           # LAN_API.md를 구현한 가짜 태블릿(파이썬 표준 라이브러리만)
    ├── lib/                   # 아주 작은 테스트 러너 + 가짜 소켓 리더
    ├── unit/                  # 순수 모듈 단위 테스트
    └── integration/           # 가짜 태블릿 상대 통합 테스트 + 공식 프레임워크 e2e
```

## 능력 매핑

| SmartThings | 태블릿 API |
|---|---|
| `switch` on / off | `on` / `off` (off = 라디오만 정지) · 상태 `power` |
| `mediaPlayback` play / pause / stop | `play` / `pause` / `stop` · 상태 `playback`(buffering은 playing으로 표시) · `supportedPlaybackCommands` = play, pause, stop |
| `mediaTrackControl` nextTrack / previousTrack | `next` / `previous` |
| `audioVolume` setVolume / volumeUp / volumeDown | `setVolume`(0..100) / `volumeUp` / `volumeDown` · 상태 `volume` |
| `audioMute` mute / unmute / setMute | `mute` / `unmute` · 상태 `muted` |
| `mediaPresets` playPreset(presetId) | `playPreset`(value = 프리셋 id) · 상태 `presets` [{id, name, imageUrl}] |
| `audioTrackData` | 상태 `title` / `artist` / `albumArtUrl` (라이브 라디오라 totalTime·elapsedTime은 보내지 않음) |
| `refresh` | `GET /api/v1/state` 후 모든 속성 다시 전송 |

- 이벤트는 **값이 바뀐 속성만** 보낸다(새로고침만 예외).
- 명령이 성공하면 결과가 확실한 것(on/off, play/pause/stop, setVolume, mute/unmute)은 바로 화면에 반영하고, 실제 상태는 SSE로 곧 다시 맞춰진다. 실패하면 로그만 남기고 상태는 그대로 둔다. 명령 타임아웃 5초.
- **온라인 판정**: 페어링되어 있고 (SSE 연결 중 또는 마지막 상태 수신이 60초 이내). 페어링 전이나 태블릿에 닿지 않으면 오프라인.

## 준비물

- SmartThings Edge를 지원하는 허브(같은 집, 태블릿과 같은 와이파이/서브넷)
- SmartThings CLI
  ```sh
  brew install smartthingscommunity/smartthings/smartthings   # 또는: npm install --global @smartthings/cli
  smartthings devices --type HUB                               # 처음 실행 시 브라우저 로그인, 허브 deviceId 확인
  ```

## 패키징 · 업로드 · 설치

모든 명령은 인자를 빼면 대화형으로 물어본다. 아래 `<...>`는 각 단계 출력에서 복사한다.

```sh
cd smartthings/edge-simpleradio

# 1) 채널 만들기(처음 한 번). 이름·설명·약관 URL을 물어본다.
smartthings edge:channels:create

# 2) 드라이버 패키징 + 업로드 → driverId, version 출력
smartthings edge:drivers:package .

# 3) 드라이버를 채널에 배정
smartthings edge:channels:assign <driverId> --channel <channelId>

# 4) 허브를 채널에 등록(처음 한 번)
smartthings edge:channels:enroll <hubId> --channel <channelId>

# 5) 허브에 드라이버 설치
smartthings edge:drivers:install <driverId> --hub <hubId> --channel <channelId>
```

한 번에 하기(2~5단계, 채널 등록이 끝난 뒤 업데이트할 때 편함):

```sh
smartthings edge:drivers:package . --channel <channelId> --hub <hubId>
```

- 업로드할 때마다 버전이 새로 붙는다. 이미 설치된 허브는 채널의 새 버전을 자동으로 받지만(수 시간 걸릴 수 있음) 바로 받으려면 위 한 번에 하기 명령이나 5단계를 다시 실행한다.
- 설치 확인: `smartthings edge:drivers:installed --hub <hubId>`
- `tests/` 폴더는 CLI가 자동으로 패키지에서 뺀다(`edgeDriverTestDirs` 기본값 `test/**`, `tests/**`).

## 기기 추가(온보딩)

1. 태블릿에서 Simple Radio 앱을 홈 플레이어(호스트) 모드로 켜 둔다. 허브와 같은 와이파이여야 한다.
2. SmartThings 앱 → **+ → 기기 추가 → 주변 기기 검색**. 몇 초 안에 **Simple Radio** 기기가 생긴다(처음엔 오프라인으로 보임: 아직 페어링 전).
3. **10분 안에 태블릿에서 "스마트싱스 연결 허용"을 누르거나, 폰 리모컨 화면의 "스마트싱스" 카드에서 "연결 허용 (10분)"을 누른다.** 드라이버는 기기가 생기자마자 10초마다 페어링을 시도하므로 버튼을 누르면 10초 안에 연결되고 온라인이 된다.
4. 5분 동안 버튼이 눌리지 않으면 드라이버는 **대기 모드**로 바뀌어 30초마다 `/api/v1/info`의 `pairingOpen`만 확인한다. 이때도 태블릿에서 버튼을 누르기만 하면 자동으로 연결된다. SmartThings 앱에서 기기 **새로고침**이나 **주변 기기 검색**을 다시 하면 10초 간격 5분 시도가 다시 시작된다.

태블릿 앱 설정에서 연결을 해제하면(토큰 폐기) 드라이버는 401을 받고 토큰을 지운 뒤 다시 페어링 대기에 들어간다 → 다시 "스마트싱스 연결 허용"을 누르면 된다.

## 로그 보기

```sh
smartthings edge:drivers:logcat <driverId> --hub-address <허브IP>
smartthings edge:drivers:logcat <driverId> --hub-address <허브IP> --log-level WARN   # 경고 이상만
```

허브 IP는 공유기 DHCP 목록이나 SmartThings 앱의 허브 정보에서 확인한다. 주요 메시지:

| 로그 | 의미 |
|---|---|
| `Simple Radio found: id=… ip=…` | mDNS로 새 태블릿 발견, 기기 생성 요청 |
| `페어링 대기 중: 태블릿에서 '스마트싱스 연결 허용'을 누르세요` | 토큰 없음, 10초마다 `/pair` 시도 중(1분마다 다시 안내) |
| `5분 동안 페어링되지 않음 -> 대기 모드` | 30초마다 `pairingOpen` 확인 중 |
| `페어링 완료: 토큰을 저장했습니다` | 토큰 영구 저장 |
| `이벤트 스트림 연결됨 (ip:port)` | SSE 연결, 온라인 |
| `이벤트 스트림 종료(…) -> N초 후 재연결` | 재연결 백오프 |
| `SSE를 유지할 수 없음 -> 10초 간격 상태 폴링으로 전환` | SSE 3회 연속 실패(또는 404), 60초마다 SSE 재시도 |
| `연결 실패 3회 -> mDNS로 IP 재확인` | 태블릿 IP가 바뀌었을 수 있어 TXT id로 다시 찾음 |
| `주소 a -> b` | 태블릿 IP 갱신(영구 저장) |
| `토큰이 거부됨(…401) -> 토큰 삭제 후 다시 페어링합니다` | 앱에서 연결 해제됨 |
| `명령 X 실패: …` | 명령 전송 실패(상태는 바꾸지 않음) |

## 문제 해결

- **주변 기기 검색에 안 나온다**
  - 허브와 태블릿이 같은 서브넷인지(게스트 와이파이, AP 격리, 다른 VLAN이면 mDNS가 안 넘어감).
  - 태블릿 앱이 서비스를 광고하는지 Mac에서 확인: `dns-sd -B _simpleradio._tcp` → `dns-sd -L "<인스턴스 이름>" _simpleradio._tcp` 로 TXT(`id=…`, `v=1`)까지 보이는지.
  - TXT `v`가 1이 아니거나 `id`가 없으면 드라이버가 무시한다(logcat에 debug 로그).
  - 배터리 최적화로 앱이 잠들어 있지 않은지.
- **기기가 계속 오프라인**
  - 페어링 전이면 정상이다 → 태블릿에서 "스마트싱스 연결 허용".
  - 페어링 후라면 logcat에서 연결 실패 사유 확인. 태블릿 IP가 자주 바뀌면 공유기에서 DHCP 고정 할당을 권장(드라이버가 mDNS로 다시 찾긴 하지만 그동안 오프라인).
  - Mac에서 직접 확인: `curl http://<태블릿IP>:8765/api/v1/info`
- **"연결 허용"을 눌렀는데 연결이 안 된다**
  - 10분 창이 닫힌 뒤였을 수 있다 → 다시 누르면 된다(대기 모드에서도 30초 안에 감지).
  - `curl -X POST http://<태블릿IP>:8765/api/v1/pair -d '{"client":"test"}'` 가 403이면 창이 닫힌 상태.
- **명령이 반응 없다**: logcat의 `명령 … 실패` 메시지 확인. 401이면 자동 재페어링, 400이면 앱 쪽이 그 명령/값을 거부한 것.
- **기기를 지우고 다시 추가**: 앱에서 기기 삭제 → 주변 기기 검색 → 연결 허용. (새 기기라 토큰도 새로 받는다. 태블릿 앱의 예전 토큰은 앱 설정에서 해제 가능.)

## 테스트

허브 런타임과 같은 **Lua 5.3**이 필요하다(Homebrew에는 `lua@5.3`이 없어 lua.org 소스로 빌드).

```sh
# Lua 5.3.6 + LuaRocks(5.3용) 설치 예시 (~/.local/lua53)
curl -fsSLO https://www.lua.org/ftp/lua-5.3.6.tar.gz && tar xzf lua-5.3.6.tar.gz
(cd lua-5.3.6 && make macosx && make install INSTALL_TOP=$HOME/.local/lua53)
curl -fsSLO https://luarocks.org/releases/luarocks-3.11.1.tar.gz && tar xzf luarocks-3.11.1.tar.gz
(cd luarocks-3.11.1 && ./configure --prefix=$HOME/.local/lua53 --with-lua=$HOME/.local/lua53 && make && make install)
~/.local/lua53/bin/luarocks install luasocket
~/.local/lua53/bin/luarocks install dkjson
~/.local/lua53/bin/luarocks install luasec OPENSSL_DIR=$(brew --prefix openssl@3)
~/.local/lua53/bin/luarocks install cosock

# 실행 (--fetch: 공식 lua_libs 릴리스를 tests/.lua_libs 에 내려받음, 처음 한 번)
tests/run.sh --fetch
tests/run.sh
```

| 단계 | 내용 | 필요 |
|---|---|---|
| syntax | 모든 .lua `luac -p` | Lua 5.3 |
| unit | sse / http / state_map / commands / mdns_parse / policy | Lua 5.3 |
| client vs mock | `client.lua`를 LuaSocket으로 `mock_host.py`에 붙여 info·pair(403→200)·state·command·SSE(무프레임/청크)·401·타임아웃 확인 | luasocket, dkjson, python3 |
| session vs mock | 실제 `session.lua`를 cosock 스케줄러로 돌려 mDNS 해석→페어링 재시도→SSE→변경분 전송→401 재페어링→404 폴링 전환→IP 재확인→오프라인/복구→정지 확인(시간 상수 축소) | + cosock, lua_libs |
| driver e2e | 공식 `integration_test` 프레임워크로 `init.lua` 전체 구동: 발견→기기 생성, 명령→HTTP 요청, 새로고침→이벤트, 401→토큰 삭제 | lua_libs |

가짜 태블릿만 따로 띄우기: `python3 tests/mock_host.py --port 8765 --pairing-open` (제어용 `/_mock/*` 엔드포인트는 파일 상단 설명 참고).

## 실허브 배포 기록 (2026-10-07)
- 채널 `rootbox-private`(DRIVER 타입, `edge:channels:create -i channel.json`에는 `"type":"DRIVER"` 필수), 허브 `스마트 홈 허브`(V4) 등록, 드라이버 설치.
- 로그: `smartthings edge:drivers:logcat --all --hub-address <허브IP> --log-level DEBUG` (드라이버 id 지정+기본 레벨에서는 아무것도 안 보였음). 첫 접속의 인증서 신뢰 질문은 `expect`로 `y`.
- 실허브에서만 드러난 버그: `get_field` 0개 반환 → `tostring` 인자 오류(세션 5초마다 재시작). 수정 후 페어링·SSE·명령 10종·재시작/재설치 유지 확인.
