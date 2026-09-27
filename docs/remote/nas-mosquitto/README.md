# NAS에 원격 제어 브로커 올리기 (Synology DSM)

목표: `wss://mqtt.<도메인>/` 로 외부에서 접속되는 Mosquitto. 앱은 이 URL·계정을 QR 페어링으로 나눠 갖는다.
**계정·비밀번호는 이 저장소·채팅·로그 어디에도 적지 않는다.** 아래 절차에서 `<...>`는 직접 채운다.

## 1. 파일 배치
DSM File Station에서 `docker/sr-mqtt/` 폴더를 만들고 이 디렉터리의 `docker-compose.yml`, `config/mosquitto.conf`, `config/acl`을 올린다.
`config/passwd`는 아래 3단계에서 만든다. `data/`, `log/` 빈 폴더도 만든다.

## 2. Container Manager → 프로젝트 → 생성
- 프로젝트 이름 `sr-mqtt`, 경로 `docker/sr-mqtt`, 소스 "docker-compose.yml 업로드/사용".
- 처음 한 번은 `passwd`가 없어 컨테이너가 바로 죽는다. 3단계 후 다시 시작한다.

## 3. 계정 만들기 (컨테이너 터미널)
Container Manager → 컨테이너 `sr-mosquitto` → 터미널(또는 SSH가 켜져 있다면 `sudo docker exec -it sr-mosquitto sh`):
```sh
mosquitto_passwd -c /mosquitto/config/passwd <계정이름>
# 비밀번호 입력(화면에 안 보임). 추가 계정은 -c 없이.
```
`-c`는 파일을 새로 만든다. 컨테이너를 재시작하면 인증이 켜진다.
(볼륨이 `:ro`라 터미널에서 쓰기가 막히면, 잠시 compose에서 `:ro`를 빼고 만든 뒤 되돌린다.)

## 4. 리버스 프록시 (제어판 → 로그인 포털 → 고급 → 역방향 프록시)
- 소스: HTTPS, 호스트 이름 `mqtt.<도메인>`, 포트 443
- 대상: HTTP, `localhost`, 포트 `9001`
- **사용자 지정 헤더 → 생성 → WebSocket** (Upgrade/Connection 헤더 2줄이 자동 추가된다. 이게 없으면 연결이 바로 끊긴다.)
- 프록시 연결 시간 제한을 넉넉히(예: 300초). MQTT keepalive 60초보다 길어야 한다.
- DSM 리버스 프록시는 호스트 이름 단위 라우팅만 되므로 경로(`/mqtt`) 없이 **서브도메인 하나를 전용**으로 쓴다.

## 5. 인증서 (제어판 → 보안 → 인증서)
- `mqtt.<도메인>`에 대한 Let's Encrypt 인증서를 **새로 발급**하고, **"설정"에서 위 리버스 프록시 항목에 그 인증서를 바인딩**한다.
- 바인딩을 빼먹으면 자체 서명 인증서가 나가서 앱이 TLS 오류로 접속하지 못한다(이 NAS에서 이미 겪은 실수).
- DNS: `mqtt.<도메인>` A/CNAME 레코드를 NAS 공인 IP/DDNS로.

## 6. 확인
외부망(LTE) 폰이나 맥에서:
```bash
# 저장소의 CLI로 페어링 문자열을 만들고(계정은 프롬프트로만) 왕복 확인
./gradlew :core:remote:cli --args="gen --broker wss://mqtt.<도메인>/ --user <계정> --pass <비밀번호>"
./gradlew :core:remote:cli --args="host --pairing <위 출력>"     # 터미널 1: 가짜 호스트
./gradlew :core:remote:cli --args="guest --pairing <위 출력> --cmd next"  # 터미널 2: 게스트
```
게스트 터미널에 state와 ack가 찍히면 브로커·프록시·인증서가 모두 맞는 것이다.

## 7. 운영 메모
- 브로커가 보는 것은 `sr/<pairId>/{state,cmd,ack}` 토픽 이름과 암호문뿐이다. 키는 QR로만 오간다.
- 계정 비밀번호를 바꾸면 양쪽 앱에서 페어링을 해제하고 다시 QR 페어링한다(페어링에 계정이 들어 있다).
- 로그는 `log/mosquitto.log`(에러·경고만).
