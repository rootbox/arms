# Play Store 등재 문안 & 콘솔 답변 (초안)

> ⚠️ 초안입니다. 앱 최종 이름 확정 후 이름 부분을 교체하세요. 이름 자리표시자: **{APP_NAME}**.
> 카테고리: **음악 및 오디오(Music & Audio)** / 콘텐츠 등급: 전체이용가 예상.

---

## 1. 앱 이름 후보
- 홈뮤직 (HomeMusic)
- NAS 뮤직 (NAS Music)
- 1319 Music
- 홈서버 뮤직 플레이어

## 2. 짧은 설명 (Short description, ≤80자)
- **KR**: 내 NAS에 담긴 음악을 폰과 차량(Android Auto)에서 바로 재생하세요.
- **EN**: Play music from your own NAS on your phone and car (Android Auto).

## 3. 전체 설명 (Full description)

**KR**
```
{APP_NAME}는 집에 있는 Synology NAS(Audio Station)에 저장된 내 음악을
스트리밍으로 재생하는 플레이어입니다. 스트리밍 구독 없이, 내가 소유한
음원을 어디서나 들을 수 있습니다.

• 아티스트·앨범별로 정리된 라이브러리 탐색
• 원하는 곡만 모아 나만의 플레이리스트 구성
• 앨범 아트·아티스트·앨범 정보 표시
• 앱을 껐다 켜도 마지막 트랙·재생 위치부터 이어듣기
• Android Auto 지원 — 차량 화면에서 안전하게 탐색·재생
• 개인정보 수집 없음 · 광고 없음 · 내 NAS하고만 통신

* 본 앱을 쓰려면 사용자 소유의 Synology NAS(Audio Station 활성화)와
  접속 계정이 필요합니다. Synology는 해당 상표권자의 상표이며 본 앱은
  비공식 클라이언트입니다.
```

**EN**
```
{APP_NAME} is a player that streams your own music stored on your
Synology NAS (Audio Station) at home. No streaming subscription — just
listen to the music you already own, anywhere.

• Browse your library organized by artist and album
• Build your own playlists from any tracks
• Album art, artist, and album info
• Resume from the last track and position after restarting
• Android Auto support — browse and play safely on your car screen
• No data collection · no ads · talks only to your NAS

* Requires your own Synology NAS with Audio Station enabled and an
  account. Synology is a trademark of its owner; this is an unofficial
  client.
```

## 4. 데이터 보안(Data safety) 답변 초안
- 데이터 수집(Collected): **없음(None)** — 앱은 데이터를 개발자/서버로 전송하지 않음.
- 데이터 공유(Shared): **없음(None)**.
- 기기 내 저장(On-device only): NAS 자격증명·플레이리스트·재생상태(암호화 저장소/로컬 DB).
- 보안: 전송 중 암호화(사용자 NAS가 HTTPS면 HTTPS 사용), 앱 삭제 시 데이터 삭제.
- ※ 콘솔 문항은 "수집/공유 안 함"으로 답하고, 자격증명은 "기기 내에만 저장, 전송 안 함"으로 설명.

## 5. 권한 사유 (콘솔/심사 설명용)
- `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: NAS 음악 스트리밍 및 백그라운드/차량 재생 유지.
- (제거 예정) `REQUEST_INSTALL_PACKAGES`: 사이드로드용 인앱 업데이트에만 쓰였음 — Play 버전에서 제거.
- (제거 검토) `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: 민감 권한 — 미디어 포그라운드 서비스로 대체 가능하면 제거.

## 6. 스토어 자산 규격 (준비 목록)
- 앱 아이콘: 512×512 PNG(32비트).
- 피처 그래픽: 1024×500 PNG/JPG.
- 폰 스크린샷: 최소 2장(권장 4~8장), 16:9 또는 9:16, 최소 320px.
  - 추천 컷: 앨범 목록 / 앨범 상세(모두 재생) / 전체화면 플레이어(앨범아트) / 플레이리스트.
- (차량 포함 시) Android Auto 스크린샷.
- 아이콘/그래픽에 ‘Synology’ 로고·상표 사용 금지.

## 7. 심사 대응 메모
- NAS 로그인이 필요한 앱이라, 심사자가 실제 재생을 확인하기 어려울 수 있음.
  → 데모/샘플 모드 또는 시연 영상·설명을 "앱 액세스(App access)" 항목에 제공 검토.
