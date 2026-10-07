# Simple Radio 로컬 제어 API v1 (호스트 태블릿 ↔ SmartThings Edge 드라이버)

같은 와이파이 안에서만 쓰는 HTTP/1.1 API. 호스트(홈 플레이어) 역할 기기의 앱이 제공한다.
원격 제어(MQTT)와 같은 실행 경로(`RemoteHostService`의 세션 컨트롤러)를 쓰며, 이 문서가 계약이다.

## 발견
- mDNS 서비스 타입 `_simpleradio._tcp`, 포트 **8765**(고정, TXT에도 표기).
- 인스턴스 이름: `Simple Radio <기기모델>`.
- TXT: `id=<기기 고유 UUID(앱 설치 단위로 고정)>`, `v=1`, `port=8765`, `name=<표시 이름>`.

## 인증
- `Authorization: Bearer <token>` (아래 `/pair`로 발급). 토큰은 앱의 암호화 저장소에 보관, 앱 설정에서 해제 가능.
- **연결 허용 창**: 태블릿에서 "스마트싱스 연결 허용"을 누르거나 폰 리모컨의 "연결 허용 (10분)"(MQTT `st_pair`)을 누르면 10분 동안만 `/pair`가 토큰을 발급한다. 창이 닫혀 있으면 403.

## 엔드포인트
| 메서드 | 경로 | 인증 | 내용 |
|---|---|---|---|
| GET | `/api/v1/info` | 없음 | `{"id","name","model","appVersion","apiVersion":1,"pairingOpen":bool}` |
| POST | `/api/v1/pair` | 없음 | 요청 `{"client":"smartthings-edge","label":"..."}` → 200 `{"token":"..."}` / 403 `{"error":"pairing_closed"}` |
| GET | `/api/v1/state` | 필요 | 상태 JSON(아래) |
| POST | `/api/v1/command` | 필요 | 요청 `{"command":"<이름>","value":<선택>}` → 200 `{"ok":true}` / 4xx·5xx `{"ok":false,"message":"..."}`. 실행 후 응답(최대 5초) |
| GET | `/api/v1/events` | 필요 | SSE. 상태가 바뀔 때마다 `event: state` + `data: <상태 JSON>`; 25초마다 `: ping`. 연결 직후 현재 상태 1회 전송 |
| GET | `/art/station/<id>.png` | 없음 | 채널 아트(600px PNG). 공개 커버 URL이 없을 때 `albumArtUrl`로 쓴다 |

인증 실패 401 `{"error":"unauthorized"}`. 알 수 없는 명령·값 400.

## 상태 JSON
```json
{
  "power": "on",                 // on = 재생 의도 있음(재생·버퍼링·오류 복구 대기), off = 정지
  "playback": "playing",         // playing | paused | stopped | buffering
  "mediaId": "1",
  "title": "KBS Cool FM - 프로그램명",
  "artist": "프로그램명",
  "albumArtUrl": "https://… 또는 http://<태블릿IP>:8765/art/station/1.png",
  "volume": 40,                  // 0..100 (STREAM_MUSIC)
  "muted": false,
  "presets": [{"id":"1","name":"KBS Cool FM","imageUrl":"http://<태블릿IP>:8765/art/station/1.png"}],
  "updatedAtMs": 1791358000000
}
```

## 명령
| command | value | 동작 |
|---|---|---|
| `on` | – | 재생 중이 아니면 직전 채널 재생(큐가 비면 마지막 채널) |
| `off` | – | **라디오만 정지**(화면·다른 기능 무관) |
| `play` / `pause` / `stop` | – | 재생 / 일시정지 / 정지 |
| `next` / `previous` | – | 다음 / 이전 채널(서비스의 채널 순환) |
| `setVolume` | 0..100 | 미디어 볼륨 |
| `volumeUp` / `volumeDown` | – | 한 단계 |
| `mute` / `unmute` | – | 미디어 음소거 |
| `playPreset` | 프리셋 id | 해당 채널 재생 |

## SmartThings 매핑(드라이버)
switch(on/off) · mediaPlayback(play/pause/stop, `supportedPlaybackCommands`=[play,pause,stop]) · mediaTrackControl(nextTrack/previousTrack) ·
audioVolume(setVolume/volumeUp/volumeDown) · audioMute(mute/unmute/setMute) · mediaPresets(playPreset, `presets`) · audioTrackData(title/artist/albumArtUrl) · refresh. 카테고리 Speaker.
playback → mediaPlayback.playbackStatus: playing→playing, paused→paused, stopped→stopped, buffering→playing.
