# broker-verify — MQTT-over-WebSocket 브로커 교차 검증

Synology NAS의 DSM 리버스 프록시 뒤에 있는 Mosquitto 2 브로커(`wss://mqtt.1319.space/`)가
앱이 기대하는 대로 동작하는지 한 번에 확인한다.

```
폰/CLI ──wss:443──▶ DSM 리버스 프록시(Let's Encrypt) ──ws──▶ localhost:9001 Mosquitto 2
                                                         (allow_anonymous false, password_file,
                                                          ACL "pattern readwrite sr/#")
```

## 1. 자격증명 파일 만들기 (비밀번호를 채팅/히스토리에 남기지 않기)

터미널에서 직접 실행한다. `read -rs`는 입력을 화면에 표시하지 않고, 셸 히스토리에도
비밀번호가 남지 않는다. 비밀번호를 채팅창에 붙여 넣지 말 것.

```bash
read -rs P && printf 'simpleradio\n%s\n' "$P" > ~/.sr-mqtt-cred && chmod 600 ~/.sr-mqtt-cred && unset P
```

- 명령을 실행하면 프롬프트 없이 입력을 기다린다. 비밀번호를 치고 Enter.
- 형식: 1행 사용자(`simpleradio`), 2행 비밀번호. 권한이 600이 아니면 스크립트가 경고한다.
- 사용자/비밀번호에 공백이 있으면 mosquitto 클라이언트를 쓰는 검사(4·5의 CONNACK 확인, 7 ACL)는
  SKIP 또는 CLI 판정으로 대체된다.

## 2. 운영 브로커 검증

프로젝트 루트(`arms-android-auto/`)에서:

```bash
tools/broker-verify/verify.sh --cred-file ~/.sr-mqtt-cred
```

다른 호스트명: `--host <name>` 또는 `HOST=<name> tools/broker-verify/verify.sh …`.
일부만: `--only 1,2,3` (자격증명 없이 DNS·TLS·WebSocket만 먼저 확인할 때 유용).
종료 코드: FAIL이 하나도 없으면 0, 있으면 1.

## 3. 검사 항목

| # | 항목 | PASS 조건 | 대표 FAIL 힌트 |
|---|------|-----------|----------------|
| 1 | DNS | `dig @8.8.8.8`, `@1.1.1.1` 모두 A = 211.208.166.136 | A 레코드/전파 |
| 2 | TLS | 발급자 Let's Encrypt, CN/SAN에 호스트(와일드카드 포함), 체인·호스트명 검증 OK, 만료일 출력(14일 미만 WARN) | `자체서명(Synology) 인증서 — DSM 인증서 바인딩 확인` |
| 3 | WS-101 | `Upgrade: websocket` + `Sec-WebSocket-Protocol: mqtt` 요청에 HTTP 101, 응답 헤더 `Sec-WebSocket-Protocol: mqtt`, `Sec-WebSocket-Accept` 일치 | 200/400/502 → `리버스 프록시 사용자 지정 헤더(WebSocket) 누락` (502는 Mosquitto 미기동일 수도) |
| 4 | ANON-REJECT | 자격증명 없는 mosquitto_sub가 CONNACK 5, 엉터리 계정 CLI guest가 12 s 내 접속 못 함 | 익명 접속 허용 |
| 5 | BADPASS-REJECT | 정상 사용자 + 틀린 비밀번호가 CONNACK 5, CLI도 접속 못 함 | password_file |
| 6 | E2E | CLI `host` + `guest --cmd next`: `guest: connection CONNECTED`, `guest: state …`, `guest: ack for seq=N ok=true`, host 로그 `host: command seq=N next`. 접속 지연·명령 왕복(ms) 출력 | 자격증명/ACL |
| 7 | ACL | `test/x` MQTT v5 QoS1 publish가 PUBACK RC 135(Not authorized), `test/#` 구독자에게 전달 없음, 같은 구독자가 `sr/…` 대조군은 수신 | `acl_file`/패턴 미적용 |
| 8 | RETAINED | host를 내린 뒤 새 guest가 CONNECTED 후 5 s 안에 retained state 수신 | persistence/retain |

- 4·5번: CLI(HiveMQ)는 거부되면 아무 출력 없이 재시도만 하므로, 거부 사유는 mosquitto_sub의
  CONNACK 코드로 확정한다. mosquitto 클라이언트를 못 쓰면 6번 정상 접속이 성공할 때만 PASS
  (연결 불가와 인증 거부를 구별하기 위해).
- 6번 지연: "시작부터"는 JVM 기동 포함, "순수 접속"은 `guest: connecting` → `CONNECTED`
  (MQTT 접속 + 구독). 왕복은 `guest: sent next` → 해당 seq ack.
- 끝나면 6번에서 만든 검증용 retained 토픽(`sr/<pairId>/state`, 암호문)을 빈 retained로
  지운다. 남기려면 `--keep-retained`.

## 4. 비밀 취급

- 비밀번호는 bash 내장 `read`로만 읽고 출력하지 않는다. `set +x`.
- JVM CLI는 `gradlew :core:remote:cli --args=…` 대신 `java @argfile`로 직접 실행한다.
  argfile은 600 권한 임시 디렉터리에 있고 JVM이 읽은 직후 지운다 → `ps`에는 `java @/…/x.args`만
  보인다. (같은 사용자가 `jcmd <pid> VM.command_line`으로 보면 펼쳐진 인자가 보일 수 있다.)
  Gradle은 클래스패스·java 경로를 얻는 데만 쓴다(`cli-classpath.init.gradle`, 빌드 파일 수정 없음).
  덤으로 매 호출마다 Gradle 기동 시간이 지연 측정에 섞이지 않는다.
- mosquitto_pub/sub는 `-P` 대신 `-o <옵션 파일>`(600)로 자격증명을 넘긴다.
  `XDG_CONFIG_HOME`을 임시 디렉터리로 바꿔 `~/.config/mosquitto_sub` 같은 기본 설정이 섞이지 않게 한다.
- 페어링 텍스트(`SR1.…`)에는 비밀번호가 base64로 들어 있으므로 화면에 내지 않는다.
  실패 시 보여 주는 로그 꼬리에서도 비밀번호·`SR1.` 이후를 가린다.
- 임시 디렉터리(`$TMPDIR/broker-verify.XXXXXX`)는 정상 종료·오류·Ctrl-C 모두에서 지운다.

## 5. 로컬 브로커로 스크립트 자체 검증 (`--local`)

`--local`은 `ws://127.0.0.1:${LOCAL_WS_PORT:-19001}/`을 대상으로 하며 1·2번은 SKIP,
3번은 평문 `http://127.0.0.1:19001/`로 101을 확인한다. 이미 1883/9001에서 도는
다른 mosquitto와 겹치지 않게 18830/19001을 쓴다.

```bash
D=$(mktemp -d)
/opt/homebrew/bin/mosquitto_passwd -b -c "$D/passwd" simpleradio testpass
echo 'pattern readwrite sr/#' > "$D/acl"
cat > "$D/mosquitto.conf" <<EOF
allow_anonymous false
password_file $D/passwd
acl_file $D/acl
persistence false
listener 18830 127.0.0.1
listener 19001 127.0.0.1
protocol websockets
EOF
/opt/homebrew/sbin/mosquitto -c "$D/mosquitto.conf" -d
printf 'simpleradio\ntestpass\n' > "$D/cred"; chmod 600 "$D/cred"

tools/broker-verify/verify.sh --local --cred-file "$D/cred"

pkill -f "mosquitto -c $D/mosquitto.conf"; rm -rf "$D"
```

## 파일

- `verify.sh` — 검증 스크립트 (macOS 기본 bash 3.2 호환, GNU `timeout` 대신 `perl alarm`)
- `cli-classpath.init.gradle` — `:core:remote` 런타임 클래스패스와 JDK 17 `java` 경로 출력용 Gradle init 스크립트
