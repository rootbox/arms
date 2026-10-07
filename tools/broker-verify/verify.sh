#!/usr/bin/env bash
# MQTT-over-WebSocket 브로커 교차 검증 하네스 (Synology DSM 리버스 프록시 → Mosquitto 2).
#
#   운영:  tools/broker-verify/verify.sh --cred-file ~/.sr-mqtt-cred
#   로컬:  tools/broker-verify/verify.sh --local --cred-file <파일>   (ws://127.0.0.1:19001/)
#
# 옵션
#   --host <name>        대상 호스트 (기본: 환경변수 HOST, 없으면 mqtt.1319.space)
#   --cred-file <path>   1행 사용자, 2행 비밀번호. 4~8번 검사에 필요. 권한 600 권장.
#   --local              ws://127.0.0.1:${LOCAL_WS_PORT:-19001}/ 대상. 1·2번 SKIP, 3번은 평문 http.
#   --expect-ip <ip>     1번 DNS 기대값 (기본 211.208.166.136)
#   --only <list>        일부만 실행. 예: --only 1,2,3  (8번은 6번이 필요)
#   --keep-retained      검증용 retained state(sr/<pairId>/state)를 지우지 않고 남긴다.
#
# 비밀 취급
#   - 비밀번호는 출력하지 않는다. 자격증명 파일은 bash 내장 read로만 읽는다.
#   - JVM CLI에는 argv 대신 java @argfile(임시 디렉터리, 600)로 넘긴다 → ps에 안 보인다.
#   - mosquitto_pub/sub에는 -o 옵션 파일(600)로 넘긴다 → ps에 안 보인다.
#   - 페어링 텍스트(SR1.…)에는 비밀번호가 base64로 들어 있으므로 화면/로그 요약에 내보내지 않는다.
#   - 임시 디렉터리는 종료 시(정상/오류/Ctrl-C) 삭제한다.
#
# macOS 기본 /bin/bash 3.2 호환. GNU timeout 대신 perl alarm 사용.
set +x
set -o pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
MAIN_CLASS=com.arms.androidauto.core.remote.RemoteCliKt

HOST=${HOST:-mqtt.1319.space}
EXPECT_IP=211.208.166.136
CRED_FILE=
LOCAL=0
LOCAL_WS_PORT=${LOCAL_WS_PORT:-19001}
ONLY=
KEEP_RETAINED=0

usage() {
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --host) HOST=${2:?--host 값 필요}; shift 2 ;;
        --cred-file) CRED_FILE=${2:?--cred-file 값 필요}; shift 2 ;;
        --local) LOCAL=1; shift ;;
        --expect-ip) EXPECT_IP=${2:?--expect-ip 값 필요}; shift 2 ;;
        --only) ONLY=${2:?--only 값 필요}; shift 2 ;;
        --keep-retained) KEEP_RETAINED=1; shift ;;
        -h|--help) usage ;;
        *) echo "알 수 없는 옵션: $1" >&2; usage ;;
    esac
done

if [ "$LOCAL" = 1 ]; then
    HOST=127.0.0.1
    BROKER_URL="ws://127.0.0.1:${LOCAL_WS_PORT}/"
    MQ_URL_BASE="ws://127.0.0.1:${LOCAL_WS_PORT}"
    HTTP_URL="http://127.0.0.1:${LOCAL_WS_PORT}/"
else
    BROKER_URL="wss://${HOST}/"
    MQ_URL_BASE="wss://${HOST}:443"
    HTTP_URL="https://${HOST}/"
fi

MOSQ_SUB=/opt/homebrew/bin/mosquitto_sub
MOSQ_PUB=/opt/homebrew/bin/mosquitto_pub
CAFILE=
for f in /opt/homebrew/etc/openssl@3/cert.pem /etc/ssl/cert.pem; do
    [ -r "$f" ] && { CAFILE=$f; break; }
done

# ---------------------------------------------------------------- 공통 도구
TMPD=$(mktemp -d "${TMPDIR:-/tmp}/broker-verify.XXXXXX") || exit 1
chmod 700 "$TMPD"
# 사용자의 ~/.config/mosquitto_sub 등 기본 설정 파일이 섞이지 않게 격리한다.
export XDG_CONFIG_HOME="$TMPD/xdg"
mkdir -p "$XDG_CONFIG_HOME"

BG_PIDS=""
cleanup() {
    local p
    for p in $BG_PIDS; do kill -TERM "$p" 2>/dev/null; done
    sleep 0.3
    for p in $BG_PIDS; do kill -KILL "$p" 2>/dev/null; done
    rm -rf "$TMPD"
    unset BV_USER BV_PASS
}
trap cleanup EXIT
trap 'exit 130' INT TERM

with_timeout() { # <초> <명령...>
    local secs=$1; shift
    perl -e 'alarm shift; exec @ARGV or die "exec failed: $!\n"' "$secs" "$@"
}

now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time*1000'; }

# 각 줄 앞에 epoch ms 타임스탬프를 붙인다(지연 측정용).
ts_filter() { perl -MTime::HiRes=time -ne 'BEGIN{$|=1} printf "%d %s", time*1000, $_'; }

# 출력 전에 비밀번호·페어링 텍스트를 가린다(외부 프로세스에 비밀을 넘기지 않도록 bash 내장만 사용).
redact_stream() {
    local line
    while IFS= read -r line || [ -n "$line" ]; do
        if [ -n "${BV_PASS:-}" ]; then line=${line//"$BV_PASS"/***}; fi
        case "$line" in *SR1.*) line="${line%%SR1.*}SR1.(redacted)" ;; esac
        printf '%s\n' "$line"
    done
}

log_tail() { # <파일> [줄수] — 타임스탬프 제거 후 가려서 들여쓰기 출력
    [ -f "$1" ] || return 0
    tail -n "${2:-6}" "$1" | sed -E 's/^[0-9]{13} //' | redact_stream | sed 's/^/        | /'
}

# java @argfile 한 항목: 큰따옴표로 감싸고 \ 와 " 를 이스케이프한다.
argq() {
    local s=$1
    s=${s//\\/\\\\}
    s=${s//\"/\\\"}
    printf '"%s"\n' "$s"
}

selected() { # <번호>
    [ -z "$ONLY" ] && return 0
    case ",$ONLY," in *",$1,"*) return 0 ;; esac
    return 1
}

# ---------------------------------------------------------------- 결과 기록
R_ID=(); R_NAME=(); R_STATUS=(); R_REASON=()
record() { # <번호> <이름> <PASS|FAIL|SKIP|WARN> <사유>
    R_ID[${#R_ID[@]}]=$1
    R_NAME[${#R_NAME[@]}]=$2
    R_STATUS[${#R_STATUS[@]}]=$3
    R_REASON[${#R_REASON[@]}]=$4
    printf '  => [%s] %s. %s — %s\n' "$3" "$1" "$2" "$4"
}
set_result() { # <번호> <상태> <사유> — 이미 기록한 결과를 고친다(지연 판정)
    local i=0
    while [ $i -lt ${#R_ID[@]} ]; do
        if [ "${R_ID[$i]}" = "$1" ]; then
            R_STATUS[$i]=$2; R_REASON[$i]=$3
            printf '  => [%s] %s. %s — %s (재판정)\n' "$2" "$1" "${R_NAME[$i]}" "$3"
        fi
        i=$((i + 1))
    done
}
header() { printf '\n== %s. %s\n' "$1" "$2"; }

# ---------------------------------------------------------------- 자격증명
BV_USER=; BV_PASS=
need_creds=0
for n in 4 5 6 7 8; do selected $n && need_creds=1; done
if [ "$need_creds" = 1 ]; then
    if [ -z "$CRED_FILE" ]; then
        echo "4~8번 검사에는 --cred-file <path> 가 필요합니다 (1행 사용자, 2행 비밀번호)." >&2
        exit 2
    fi
    if [ ! -r "$CRED_FILE" ]; then
        echo "자격증명 파일을 읽을 수 없습니다: $CRED_FILE" >&2
        exit 2
    fi
    perm=$(stat -f '%Lp' "$CRED_FILE" 2>/dev/null || stat -c '%a' "$CRED_FILE" 2>/dev/null)
    case "$perm" in
        600|400) ;;
        *) echo "경고: $CRED_FILE 권한이 $perm 입니다. chmod 600 권장." >&2 ;;
    esac
    { IFS= read -r BV_USER; IFS= read -r BV_PASS; } < "$CRED_FILE"
    BV_USER=${BV_USER%$'\r'}; BV_PASS=${BV_PASS%$'\r'}
    if [ -z "$BV_USER" ] || [ -z "$BV_PASS" ]; then
        echo "자격증명 파일 형식 오류: 1행 사용자, 2행 비밀번호가 모두 있어야 합니다." >&2
        exit 2
    fi
fi

RUN_ID=$(openssl rand -hex 4)

# ---------------------------------------------------------------- mosquitto 클라이언트(WebSocket) 지원 여부
MQ_OK=0; MQ_WHY=; MQ_HELP=
[ -x "$MOSQ_SUB" ] && MQ_HELP=$("$MOSQ_SUB" --help 2>&1)
if [ ! -x "$MOSQ_SUB" ] || [ ! -x "$MOSQ_PUB" ]; then
    MQ_WHY="mosquitto_pub/sub 없음 ($MOSQ_SUB)"
elif ! printf '%s\n' "$MQ_HELP" | grep -q -- '--ws'; then
    MQ_WHY="mosquitto 클라이언트가 WebSocket 미지원 빌드"
elif ! printf '%s\n' "$MQ_HELP" | grep -q -- '-o options-file'; then
    MQ_WHY="mosquitto 클라이언트가 -o 옵션 파일 미지원(2.1 미만) — 비밀번호를 argv로 넘기지 않기 위해 사용 안 함"
elif [ "$LOCAL" = 0 ] && [ -z "$CAFILE" ]; then
    MQ_WHY="CA 번들(cert.pem) 없음"
else
    MQ_OK=1
fi
MQ_TLS=()
[ "$LOCAL" = 0 ] && MQ_TLS=(--cafile "$CAFILE")

MQ_OPTS_GOOD="$TMPD/mq-good.opts"
MQ_OPTS_BAD="$TMPD/mq-badpass.opts"
MQ_CRED_OK=0
if [ "$MQ_OK" = 1 ] && [ -n "$BV_PASS" ]; then
    case "$BV_USER$BV_PASS" in
        *[[:space:]]*) MQ_WHY="사용자/비밀번호에 공백이 있어 mosquitto 옵션 파일로 넘길 수 없음" ;;
        *)
            printf -- '-u %s\n-P %s\n' "$BV_USER" "$BV_PASS" > "$MQ_OPTS_GOOD"
            printf -- '-u %s\n-P %s\n' "$BV_USER" "wrong-$(openssl rand -hex 8)" > "$MQ_OPTS_BAD"
            MQ_CRED_OK=1 ;;
    esac
fi

# mq <타임아웃초> <sub|pub> <옵션파일|-> <토픽> [추가 인자...]   (- 는 자격증명 없음 = 익명)
mq() {
    local secs=$1 kind=$2 opts=$3 topic=$4; shift 4
    local bin=$MOSQ_SUB; [ "$kind" = pub ] && bin=$MOSQ_PUB
    local a=()
    [ "$opts" != - ] && a=(-o "$opts")
    with_timeout "$secs" "$bin" ${a[@]+"${a[@]}"} -L "$MQ_URL_BASE/$topic" ${MQ_TLS[@]+"${MQ_TLS[@]}"} "$@"
}

# mosquitto 출력에서 CONNACK 판정: 0=허용, 4/5=거부, 그 외=접속 실패
connack_of() { sed -n 's/.*received CONNACK (\([0-9]*\)).*/\1/p' "$1" | head -1; }

# 실패 원인 보강용: 정상 자격증명으로 mosquitto_sub 접속을 한 번 시도해 CONNACK을 설명으로 돌려준다.
explain_good_connect() {
    [ "$MQ_OK" = 1 ] && [ "$MQ_CRED_OK" = 1 ] || return 0
    mq 10 sub "$MQ_OPTS_GOOD" "sr/verify-$RUN_ID/probe" -E -d -i "bv-$RUN_ID-probe" > "$TMPD/probe.out" 2>&1
    case "$(connack_of "$TMPD/probe.out")" in
        0) printf ' / mosquitto_sub 정상 접속은 됨(CLI 경로 문제?)' ;;
        4|5) printf ' / mosquitto_sub도 CONNACK %s(not authorised): 자격증명 파일·NAS password_file 확인' "$(connack_of "$TMPD/probe.out")" ;;
        *) printf ' / mosquitto_sub도 접속 실패: %s' "$(grep -m1 -iE 'error|unable' "$TMPD/probe.out")" ;;
    esac
}

# ---------------------------------------------------------------- JVM CLI 준비
CLI_READY=0; CLI_WHY=
prepare_cli() {
    [ "$CLI_READY" = 1 ] && return 0
    [ -n "$CLI_WHY" ] && return 1
    echo "  (CLI 준비: Gradle로 :core:remote 컴파일 및 클래스패스 확인)"
    local out
    if ! out=$(cd "$PROJECT_DIR" && with_timeout 600 ./gradlew -q --no-configuration-cache \
            -I "$SCRIPT_DIR/cli-classpath.init.gradle" :core:remote:printCliRuntime 2>"$TMPD/gradle.err"); then
        CLI_WHY="Gradle 실패: $(tail -n 3 "$TMPD/gradle.err" | tr '\n' ' ')"
        return 1
    fi
    BV_JAVA=$(printf '%s\n' "$out" | sed -n 's/^BV_JAVA=//p')
    BV_CP=$(printf '%s\n' "$out" | sed -n 's/^BV_CP=//p')
    if [ ! -x "$BV_JAVA" ] || [ -z "$BV_CP" ]; then
        CLI_WHY="클래스패스/java 경로를 얻지 못함"
        return 1
    fi
    CLI_READY=1
}

# gen_pairing <user> <pass> <이름> → $TMPD/<이름>.qr 에 페어링 텍스트, PAIR_ID 설정
gen_pairing() {
    local name=$3 af="$TMPD/$3.gen.args"
    {
        argq -cp; argq "$BV_CP"; argq "$MAIN_CLASS"; argq gen
        argq --broker; argq "$BROKER_URL"; argq --user; argq "$1"; argq --pass; argq "$2"
    } > "$af"
    if ! with_timeout 60 "$BV_JAVA" "@$af" > "$TMPD/$name.gen.out" 2>"$TMPD/$name.gen.err"; then
        return 1
    fi
    rm -f "$af"
    PAIR_ID=$(sed -n 's/^pairId: //p' "$TMPD/$name.gen.out")
    sed -n 's/^qr([0-9]* chars): //p' "$TMPD/$name.gen.out" > "$TMPD/$name.qr"
    rm -f "$TMPD/$name.gen.out"
    [ -n "$PAIR_ID" ] && [ -s "$TMPD/$name.qr" ]
}

# start_cli <host|guest> <페어링이름> <로그> [--cmd x] → CLI_PID (java), PIPE_PID
start_cli() {
    local mode=$1 name=$2 log=$3; shift 3
    local af="$TMPD/$name.$mode.$RANDOM.args" pidf="$TMPD/$name.$mode.$RANDOM.pid" qr
    qr=$(cat "$TMPD/$name.qr")
    {
        argq -cp; argq "$BV_CP"; argq "$MAIN_CLASS"; argq "$mode"; argq --pairing; argq "$qr"
        while [ $# -gt 0 ]; do argq "$1"; shift; done
    } > "$af"
    qr=
    # perl을 직접 백그라운드로 띄우고(함수 아님 → 서브셸 없이 exec) alarm(안전 상한 180 s) 후
    # java로 exec → $! 가 곧 java pid. SIGTERM이 java에 바로 가서 shutdown hook이 돈다.
    ( perl -e 'alarm shift; exec @ARGV or die "exec failed: $!\n"' 180 "$BV_JAVA" "@$af" 2>&1 &
      echo $! > "$pidf"; wait ) | ts_filter > "$log" &
    PIPE_PID=$!
    local i=0
    while [ ! -s "$pidf" ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
    CLI_PID=$(cat "$pidf" 2>/dev/null)
    BG_PIDS="$BG_PIDS $CLI_PID $PIPE_PID"
    # argfile은 JVM이 읽은 뒤 바로 지운다(비밀 노출 시간 최소화).
    ( sleep 3; rm -f "$af" ) &
}

stop_cli() { # <java pid> <pipe pid>
    [ -n "$1" ] || return 0
    kill -TERM "$1" 2>/dev/null
    local i=0
    while kill -0 "$1" 2>/dev/null && [ $i -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
    kill -KILL "$1" 2>/dev/null
    wait "$2" 2>/dev/null
}

# wait_line <로그> <정규식> <타임아웃ms> → 성공 시 LINE_TS, LINE 설정
wait_line() {
    local log=$1 re=$2 deadline=$(( $(now_ms) + $3 )) l
    LINE=; LINE_TS=
    while :; do
        l=$(grep -E "^[0-9]{13} $re" "$log" 2>/dev/null | head -1)
        if [ -n "$l" ]; then
            LINE_TS=${l%% *}; LINE=${l#* }
            return 0
        fi
        [ "$(now_ms)" -ge "$deadline" ] && return 1
        sleep 0.1
    done
}

# ---------------------------------------------------------------- 1. DNS
check_dns() {
    header 1 "DNS ($HOST → $EXPECT_IP)"
    if [ "$LOCAL" = 1 ]; then record 1 DNS SKIP "--local 모드"; return; fi
    local ns got bad= detail=
    for ns in 8.8.8.8 1.1.1.1; do
        got=$(with_timeout 10 dig +short +time=3 +tries=2 @"$ns" A "$HOST" 2>/dev/null \
              | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' | sort -u | tr '\n' ' ' | sed 's/ $//')
        echo "  @$ns: ${got:-(응답 없음)}"
        detail="$detail @$ns=${got:-없음}"
        [ "$got" = "$EXPECT_IP" ] || bad=1
    done
    if [ -z "$bad" ]; then
        record 1 DNS PASS "8.8.8.8·1.1.1.1 모두 $EXPECT_IP"
    else
        record 1 DNS FAIL "기대 $EXPECT_IP, 실제:$detail (DNS A 레코드/전파 확인)"
    fi
}

# ---------------------------------------------------------------- 2. TLS
check_tls() {
    header 2 "TLS 인증서 ($HOST:443)"
    if [ "$LOCAL" = 1 ]; then record 2 TLS SKIP "--local 모드(평문 ws)"; return; fi
    local out cert txt subject issuer enddate san verify days
    out=$(with_timeout 20 openssl s_client -connect "$HOST:443" -servername "$HOST" \
          -verify_hostname "$HOST" ${CAFILE:+-CAfile "$CAFILE"} </dev/null 2>&1)
    cert=$(printf '%s\n' "$out" | sed -n '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/p')
    if [ -z "$cert" ]; then
        record 2 TLS FAIL "인증서를 받지 못함: $(printf '%s\n' "$out" | grep -iE 'error|refused|timeout' | head -1)"
        return
    fi
    subject=$(printf '%s\n' "$cert" | openssl x509 -noout -subject 2>/dev/null | sed 's/^subject= *//')
    issuer=$(printf '%s\n' "$cert" | openssl x509 -noout -issuer 2>/dev/null | sed 's/^issuer= *//')
    enddate=$(printf '%s\n' "$cert" | openssl x509 -noout -enddate 2>/dev/null | sed 's/^notAfter=//')
    txt=$(printf '%s\n' "$cert" | openssl x509 -noout -text 2>/dev/null)
    san=$(printf '%s\n' "$txt" | grep -A1 'Subject Alternative Name' | tail -1 | sed 's/^ *//')
    verify=$(printf '%s\n' "$out" | sed -n 's/^ *Verify return code: //p' | tail -1)
    days=$(perl -MTime::Piece -e '
        my $s = shift; $s =~ s/\s+/ /g; $s =~ s/ GMT$//;
        my $t = eval { Time::Piece->strptime($s, "%b %d %H:%M:%S %Y") };
        print $t ? int(($t->epoch - time) / 86400) : "?";' "$enddate")
    echo "  subject : $subject"
    echo "  issuer  : $issuer"
    echo "  SAN     : ${san:-(없음)}"
    echo "  만료    : $enddate (남은 ${days}일)"
    echo "  검증    : $verify"

    local parent=${HOST#*.} name_ok=0 sanlist
    sanlist=" $(printf '%s' "$san" | tr -d ' ' | tr ',' ' ') "
    case "$sanlist" in *" DNS:$HOST "*|*" DNS:*.$parent "*) name_ok=1 ;; esac
    case "$subject" in *"CN = $HOST"*|*"CN=$HOST"*|*"CN = *.$parent"*|*"CN=*.$parent"*) name_ok=1 ;; esac

    if printf '%s' "$issuer" | grep -qi synology; then
        record 2 TLS FAIL "자체서명(Synology) 인증서 — DSM 인증서 바인딩 확인 (issuer: $issuer)"
    elif ! printf '%s' "$issuer" | grep -q "Let's Encrypt"; then
        record 2 TLS FAIL "발급자가 Let's Encrypt 아님: $issuer"
    elif [ "$name_ok" != 1 ]; then
        record 2 TLS FAIL "인증서 CN/SAN에 $HOST 없음 (다른 서브도메인 인증서가 바인딩됨?)"
    elif [ "${verify%% *}" != 0 ]; then
        record 2 TLS FAIL "체인/호스트명 검증 실패: $verify"
    elif [ "$days" != "?" ] && [ "$days" -lt 0 ]; then
        record 2 TLS FAIL "인증서 만료됨 ($enddate)"
    elif [ "$days" != "?" ] && [ "$days" -lt 14 ]; then
        record 2 TLS WARN "Let's Encrypt, 이름 일치, 단 만료 임박 ${days}일 ($enddate)"
    else
        record 2 TLS PASS "Let's Encrypt, 이름 일치, 검증 OK, 만료 $enddate (${days}일)"
    fi
}

# ---------------------------------------------------------------- 3. WebSocket 101
check_ws_upgrade() {
    header 3 "WebSocket 업그레이드 ($HTTP_URL)"
    local key expect_accept hdr status proto accept
    key=$(openssl rand -base64 16)
    expect_accept=$(printf '%s' "${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11" | openssl dgst -sha1 -binary | openssl base64)
    # 101 이후 curl은 프레임을 기다리므로 --max-time으로 끊고 헤더만 본다(종료 코드 28은 정상).
    hdr=$(with_timeout 20 curl -sS --http1.1 -o /dev/null -D - --max-time 6 \
          -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Version: 13" \
          -H "Sec-WebSocket-Key: $key" -H "Sec-WebSocket-Protocol: mqtt" \
          "$HTTP_URL" 2>"$TMPD/curl.err" | tr -d '\r')
    status=$(printf '%s\n' "$hdr" | sed -n 's/^HTTP\/[0-9.]* \([0-9]*\).*/\1/p' | head -1)
    proto=$(printf '%s\n' "$hdr" | grep -i '^sec-websocket-protocol:' | sed 's/^[^:]*: *//' | head -1)
    accept=$(printf '%s\n' "$hdr" | grep -i '^sec-websocket-accept:' | sed 's/^[^:]*: *//' | head -1)
    echo "  HTTP ${status:-(응답 없음)}, Sec-WebSocket-Protocol: ${proto:-(없음)}"
    case "$status" in
        101)
            if [ "$proto" != mqtt ]; then
                record 3 WS-101 FAIL "101이지만 Sec-WebSocket-Protocol: mqtt 응답 없음 (프록시가 서브프로토콜 헤더 미전달)"
            elif [ "$accept" != "$expect_accept" ]; then
                record 3 WS-101 FAIL "101이지만 Sec-WebSocket-Accept 불일치 (중간 프록시가 핸드셰이크 변조?)"
            else
                record 3 WS-101 PASS "HTTP 101, Sec-WebSocket-Protocol: mqtt, Accept 일치"
            fi ;;
        200|400|502)
            record 3 WS-101 FAIL "HTTP $status — 리버스 프록시 사용자 지정 헤더(WebSocket) 누락$( [ "$status" = 502 ] && printf ' 또는 Mosquitto(localhost:9001) 미기동')" ;;
        "")
            record 3 WS-101 FAIL "응답 없음: $(head -1 "$TMPD/curl.err")" ;;
        *)
            record 3 WS-101 FAIL "HTTP $status (기대 101) — 리버스 프록시 대상/포트 확인" ;;
    esac
}

# ---------------------------------------------------------------- 4·5. 인증 거부
# CLI(실제 앱과 같은 HiveMQ 클라이언트)는 거부되면 출력 없이 재시도만 한다 → 12 s 안에
# "guest: connected"가 없으면 '접속 안 됨'. 이유(인증 거부인지, 아예 못 붙는지)는 mosquitto_sub의
# CONNACK 코드로 확정한다. mosquitto를 못 쓰면 6번(정상 접속)이 성공할 때만 PASS로 본다.
PENDING_AUTH=""
check_rejected() { # <번호> <이름> <cli user> <cli pass> <mq 옵션파일|-> <설명>
    local n=$1 name=$2 cu=$3 cp=$4 mqopts=$5 desc=$6
    header "$n" "$desc"
    local cli_res= mq_res= mq_code=

    if [ "$MQ_OK" = 1 ] && { [ "$mqopts" = - ] || [ "$MQ_CRED_OK" = 1 ]; }; then
        mq 15 sub "$mqopts" "sr/verify-$RUN_ID/auth" -E -d -i "bv-$RUN_ID-auth$n" > "$TMPD/mq-auth$n.out" 2>&1
        mq_code=$(connack_of "$TMPD/mq-auth$n.out")
        case "$mq_code" in
            4|5) mq_res=refused; echo "  mosquitto_sub: CONNACK $mq_code (거부: $(grep -m1 -i 'refused' "$TMPD/mq-auth$n.out" | sed 's/^.*Refused: //'))" ;;
            0)   mq_res=accepted; echo "  mosquitto_sub: CONNACK 0 (접속 허용됨!)" ;;
            *)   mq_res=error; echo "  mosquitto_sub: CONNACK 없음 — $(grep -m1 -iE 'error|unable' "$TMPD/mq-auth$n.out")" ;;
        esac
    else
        echo "  mosquitto_sub: 사용 안 함 (${MQ_WHY:-자격증명 없음})"
    fi

    if prepare_cli; then
        if gen_pairing "$cu" "$cp" "auth$n"; then
            start_cli guest "auth$n" "$TMPD/auth$n.guest.log"
            if wait_line "$TMPD/auth$n.guest.log" 'guest: connected' 12000; then
                cli_res=accepted
            else
                cli_res=rejected
            fi
            stop_cli "$CLI_PID" "$PIPE_PID"
            echo "  CLI guest: $( [ "$cli_res" = rejected ] && echo '12 s 내 CONNECTED 없음(거부/재시도 중)' || echo 'CONNECTED (접속 허용됨!)')"
        else
            echo "  CLI gen 실패: $(head -1 "$TMPD/auth$n.gen.err" | redact_stream)"
        fi
    else
        echo "  CLI: 사용 불가 ($CLI_WHY)"
    fi

    if [ "$cli_res" = accepted ] || [ "$mq_res" = accepted ]; then
        record "$n" "$name" FAIL "잘못된 자격증명으로 접속이 허용됨 (allow_anonymous/password_file 확인)"
    elif [ "$mq_res" = refused ]; then
        record "$n" "$name" PASS "CONNACK $mq_code(not authorised)$( [ "$cli_res" = rejected ] && echo ', CLI도 접속 실패')"
    elif [ "$cli_res" = rejected ] && [ -z "$mq_res" ]; then
        record "$n" "$name" PASS "CLI 접속 실패(6번 정상 접속 성공 시 확정)"
        PENDING_AUTH="$PENDING_AUTH $n"
    elif [ "$mq_res" = error ]; then
        record "$n" "$name" FAIL "판정 불가: 브로커 접속 자체 실패 (3번 결과 확인)"
    else
        record "$n" "$name" FAIL "판정 불가: CLI·mosquitto 모두 사용 불가"
    fi
}

# ---------------------------------------------------------------- 6. 정상 자격증명 왕복
E2E_OK=0; E2E_PAIR=
check_e2e() {
    header 6 "정상 자격증명: host + guest --cmd next"
    if ! prepare_cli; then record 6 E2E FAIL "CLI 사용 불가: $CLI_WHY"; return; fi
    if ! gen_pairing "$BV_USER" "$BV_PASS" e2e; then
        record 6 E2E FAIL "gen 실패: $(head -1 "$TMPD/e2e.gen.err" | redact_stream)"; return
    fi
    E2E_PAIR=$PAIR_ID
    echo "  pairId $PAIR_ID (토픽 sr/$PAIR_ID/{state,cmd,ack})"
    local hl="$TMPD/e2e.host.log" gl="$TMPD/e2e.guest.log"
    local t0 h_conn_ms g_start g_conn_ms g_conn_net seq rtt state_line
    t0=$(now_ms)
    start_cli host e2e "$hl"; local HPID=$CLI_PID HPIPE=$PIPE_PID
    if ! wait_line "$hl" 'host: connected' 30000; then
        record 6 E2E FAIL "host가 30 s 안에 접속 못 함$(explain_good_connect)"
        log_tail "$hl"; stop_cli "$HPID" "$HPIPE"; return
    fi
    h_conn_ms=$((LINE_TS - t0))
    wait_line "$hl" 'host: periodic state published' 10000
    echo "  host 접속 ${h_conn_ms} ms (JVM 기동 포함), 첫 state publish 완료"

    g_start=$(now_ms)
    start_cli guest e2e "$gl" --cmd next; local GPID=$CLI_PID GPIPE=$PIPE_PID
    local fail=
    if wait_line "$gl" 'guest: connection CONNECTED' 30000; then
        g_conn_ms=$((LINE_TS - g_start))
        local cts=$LINE_TS
        wait_line "$gl" 'guest: connecting to' 0 && g_conn_net=$((cts - LINE_TS))
        echo "  guest CONNECTED: 시작부터 ${g_conn_ms} ms, MQTT 접속+구독 ${g_conn_net:-?} ms"
    else
        fail="guest: connection CONNECTED 없음"
    fi
    if [ -z "$fail" ]; then
        if wait_line "$gl" 'guest: state ' 10000; then
            state_line=$LINE
            echo "  ${state_line:0:110}…"
        else
            fail="guest: state 줄 없음(10 s)"
        fi
    fi
    if [ -z "$fail" ]; then
        if wait_line "$gl" 'guest: sent next seq=[0-9]+' 10000; then
            seq=${LINE##*seq=}; local sent_ts=$LINE_TS
            if wait_line "$gl" "guest: ack for seq=$seq ok=true" 15000; then
                rtt=$((LINE_TS - sent_ts))
                echo "  ack seq=$seq ok=true, 명령 왕복 ${rtt} ms"
            else
                fail="guest: ack for seq=$seq ok=true 없음(15 s)"
            fi
            if [ -z "$fail" ] && ! wait_line "$hl" "host: command seq=$seq next" 3000; then
                fail="host 로그에 command seq=$seq next 없음"
            fi
        else
            fail="guest: sent next 줄 없음"
        fi
    fi
    stop_cli "$GPID" "$GPIPE"
    # 8번을 위해 host를 내린다(shutdown hook으로 깔끔히 DISCONNECT). retained state는 브로커에 남는다.
    stop_cli "$HPID" "$HPIPE"
    if [ -n "$fail" ]; then
        record 6 E2E FAIL "$fail"
        echo "      guest 로그:"; log_tail "$gl" 8
        echo "      host 로그:"; log_tail "$hl" 8
        return
    fi
    E2E_OK=1
    record 6 E2E PASS "CONNECTED ${g_conn_ms} ms(순수 접속 ${g_conn_net:-?} ms), state 수신, next ack ok, 왕복 ${rtt} ms"
}

# ---------------------------------------------------------------- 7. ACL
check_acl() {
    header 7 "ACL (sr/# 밖 토픽 차단)"
    if [ "$MQ_OK" != 1 ] || [ "$MQ_CRED_OK" != 1 ]; then
        record 7 ACL SKIP "${MQ_WHY:-mosquitto 클라이언트 사용 불가}"; return
    fi
    local ctl="sr/verify-$RUN_ID/x" sub="$TMPD/acl.sub"
    # 구독자 하나가 허용 토픽(대조군 ctl)과 금지 필터(test/#)를 함께 구독한다. 그다음
    #   ① test/x 에 MQTT v5 QoS1 publish → PUBACK RC 135(Not authorized)여야 한다(<128이면 허용된 것)
    #   ② ①이 끝난 뒤 ctl 에 publish → 구독자가 받은 첫 메시지가 ctl 이어야 한다.
    # ②가 도착했다는 것은 구독이 살아 있었다는 증거이므로, 그 전에 test/x가 안 왔다면 차단된 것이다.
    mq 15 sub "$MQ_OPTS_GOOD" "$ctl" -t 'test/#' -C 1 -W 12 -v -i "bv-$RUN_ID-aclsub" > "$sub" 2>&1 &
    local sp=$!; BG_PIDS="$BG_PIDS $sp"
    sleep 2.5   # 구독 완료 대기(mosquitto_sub 출력은 파일로 버퍼링되어 SUBACK 줄로 알 수 없다)
    mq 10 pub "$MQ_OPTS_GOOD" test/x -V 5 -q 1 -m "neg-$RUN_ID" -d -i "bv-$RUN_ID-negpub" > "$TMPD/acl-neg.pub" 2>&1
    mq 10 pub "$MQ_OPTS_GOOD" "$ctl" -q 1 -m "ctl-$RUN_ID" -i "bv-$RUN_ID-ctlpub" > "$TMPD/acl-ctl.pub" 2>&1
    wait $sp 2>/dev/null
    local first rc ack
    first=$(grep -E "(neg|ctl)-$RUN_ID" "$sub" | head -1)
    rc=$(sed -n 's/.*received PUBACK (Mid: [0-9]*, RC:\([0-9]*\)).*/\1/p' "$TMPD/acl-neg.pub" | head -1)
    ack=$(connack_of "$TMPD/acl-neg.pub")
    echo "  test/x publish(v5 QoS1): CONNACK ${ack:-없음}, PUBACK RC ${rc:-없음}$( [ "$rc" = 135 ] && echo ' (Not authorized)')"
    echo "  구독자 첫 수신: ${first:-(없음)}"

    if [ -n "$ack" ] && [ "$ack" != 0 ]; then
        record 7 ACL FAIL "판정 불가: 정상 자격증명 접속 거부(CONNACK $ack) — 자격증명 확인"
    elif [ -z "$ack" ]; then
        record 7 ACL FAIL "판정 불가: mosquitto_pub 접속 실패 ($(grep -m1 -iE 'error|unable' "$TMPD/acl-neg.pub"))"
    elif case "$first" in "test/"*) true ;; *) false ;; esac; then
        record 7 ACL FAIL "test/x 메시지가 구독자에게 전달됨 — ACL 미적용(acl_file/패턴 확인)"
    elif [ -n "$rc" ] && [ "$rc" -lt 128 ]; then
        record 7 ACL FAIL "test/x publish가 허용됨(PUBACK RC $rc) — ACL 미적용(acl_file/패턴 확인)"
    elif [ "${first%% *}" != "$ctl" ]; then
        record 7 ACL FAIL "판정 불가: 대조군 $ctl 도 수신 못 함(구독 지연/권한) — 다시 실행"
    else
        record 7 ACL PASS "test/x publish 거부(RC ${rc:-?}), test/# 전달 없음, sr/ 대조군 정상 수신"
    fi
}

# ---------------------------------------------------------------- 8. retained state
check_retained() {
    header 8 "retained state 유지 (host 없이 새 guest)"
    if [ "$E2E_OK" != 1 ]; then record 8 RETAINED SKIP "6번이 성공해야 검사 가능"; return; fi
    sleep 1
    local gl="$TMPD/ret.guest.log" g_start fail= sms
    g_start=$(now_ms)
    start_cli guest e2e "$gl"; local GPID=$CLI_PID GPIPE=$PIPE_PID
    if ! wait_line "$gl" 'guest: connection CONNECTED' 30000; then
        fail="guest 접속 실패"
    else
        local cts=$LINE_TS
        if wait_line "$gl" 'guest: state ' 5000; then
            sms=$((LINE_TS - cts))
        else
            fail="CONNECTED 후 5 s 안에 state 없음 (retain 미보존: persistence/브로커 재시작 확인)"
        fi
    fi
    stop_cli "$GPID" "$GPIPE"
    if [ -n "$fail" ]; then
        record 8 RETAINED FAIL "$fail"; log_tail "$gl" 6
    else
        record 8 RETAINED PASS "host 없이 접속 후 ${sms} ms에 retained state 수신 (시작부터 $((LINE_TS - g_start)) ms)"
    fi
}

cleanup_retained() {
    [ -n "$E2E_PAIR" ] || return 0
    local t="sr/$E2E_PAIR/state"
    if [ "$KEEP_RETAINED" = 1 ]; then
        echo "  (--keep-retained: $t 유지)"; return 0
    fi
    if [ "$MQ_OK" = 1 ] && [ "$MQ_CRED_OK" = 1 ]; then
        if mq 10 pub "$MQ_OPTS_GOOD" "$t" -r -n -q 1 -i "bv-$RUN_ID-clean" >/dev/null 2>&1; then
            echo "  검증용 retained $t 삭제함"
        else
            echo "  경고: retained $t 삭제 실패 (암호문이라 내용 노출은 없음)"
        fi
    else
        echo "  참고: retained $t 가 브로커에 남음 (암호문, mosquitto_pub 불가로 미삭제)"
    fi
}

# ---------------------------------------------------------------- 실행
echo "브로커 검증: $BROKER_URL  ($( [ "$LOCAL" = 1 ] && echo 로컬 || echo 운영), run $RUN_ID)"
[ -n "$BV_USER" ] && echo "사용자: $BV_USER (비밀번호는 출력하지 않음)"
echo "mosquitto 클라이언트: $( [ "$MQ_OK" = 1 ] && echo "WebSocket 사용 가능" || echo "사용 안 함 — $MQ_WHY")"

selected 1 && check_dns
selected 2 && check_tls
selected 3 && check_ws_upgrade
selected 4 && check_rejected 4 ANON-REJECT "anon-$RUN_ID" "x-$(openssl rand -hex 8)" - "익명/엉터리 계정 거부"
selected 5 && check_rejected 5 BADPASS-REJECT "$BV_USER" "wrong-$(openssl rand -hex 8)" "$MQ_OPTS_BAD" "정상 사용자 + 틀린 비밀번호 거부"
selected 6 && check_e2e
selected 7 && check_acl
selected 8 && check_retained
selected 6 && cleanup_retained

# 4·5번을 CLI만으로 판정한 경우: 6번 정상 접속이 성공해야 '거부'가 '연결 불가'와 구별된다.
for n in $PENDING_AUTH; do
    if [ "$E2E_OK" = 1 ]; then
        set_result "$n" PASS "CLI 접속 실패 + 6번 정상 접속 성공 → 인증 거부로 판정"
    else
        set_result "$n" FAIL "판정 불가: 정상 자격증명도 접속 실패(6번) — 연결 문제일 수 있음"
    fi
done

# ---------------------------------------------------------------- 요약
echo
echo "==================== 요약 ($BROKER_URL) ===================="
printf '%-3s %-15s %-5s %s\n' "#" "CHECK" "RES" "REASON"
printf '%-3s %-15s %-5s %s\n' "---" "---------------" "-----" "------"
i=0; npass=0; nfail=0; nskip=0; nwarn=0
while [ $i -lt ${#R_ID[@]} ]; do
    printf '%-3s %-15s %-5s %s\n' "${R_ID[$i]}" "${R_NAME[$i]}" "${R_STATUS[$i]}" "${R_REASON[$i]}"
    case "${R_STATUS[$i]}" in
        PASS) npass=$((npass + 1)) ;; FAIL) nfail=$((nfail + 1)) ;;
        SKIP) nskip=$((nskip + 1)) ;; WARN) nwarn=$((nwarn + 1)) ;;
    esac
    i=$((i + 1))
done
echo "PASS $npass / FAIL $nfail / WARN $nwarn / SKIP $nskip"
[ "$nfail" = 0 ]
