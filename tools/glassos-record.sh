#!/usr/bin/env bash
#
# glassos-record.sh — capture live GlassOS telemetry during a real workout.
#
# WHY THIS EXISTS
#   Every reading taken so far was captured while the console sat IDLE, where every metric service
#   returns an empty message. That is ambiguous: proto3 omits default values, so "{}" could mean
#   "no workout is running" or "the value really is zero". Stride cannot render a number until we
#   know which. This script captures the populated shape during an actual workout so MachineLink
#   can be written against observed data instead of an assumption.
#
# SAFETY — read the next four lines before running this.
#   This script calls ONLY CanRead, Get*, and *Subscription methods. Those read the machine; they
#   cannot move the belt. It never calls SetSpeed, SetIncline, StartNewWorkout, Pause, Stop, or
#   anything else that commands the machine. Start and stop your workout with the treadmill's own
#   controls. If anything feels wrong, use the safety key — not this terminal.
#
# USAGE
#   tools/glassos-record.sh [seconds]        # default 600
#
set -uo pipefail

DURATION="${1:-600}"
PORT=54321
CLIENT_ID="com.ifit.eriador"

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROTO_PATH="$REPO/protocol/glassos"
OUT="$REPO/.telemetry/$(date +%Y%m%d-%H%M%S)"

# Credentials are never committed and never copied into the repo. Point this at wherever the
# extracted PEMs live; see protocol/glassos/README.md.
CERT_DIR="${GLASSOS_CERT_DIR:-$HOME/.copilot/session-state/58e3731e-9738-4a0e-81f0-b5e1a1cfbe84/files/qz/x/assets}"

for f in client_cert.pem client_key.pem ca_cert.pem; do
  [[ -f "$CERT_DIR/$f" ]] || { echo "missing $CERT_DIR/$f — set GLASSOS_CERT_DIR" >&2; exit 1; }
done
command -v grpcurl >/dev/null || { echo "grpcurl not installed" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq not installed" >&2; exit 1; }

mkdir -p "$OUT"

# -insecure skips Go's hostname check only. The server certificate is CN-only with no SAN, which
# modern TLS stacks reject outright. The chain itself is verified separately with `openssl verify`
# against ca_cert.pem, and Stride's Android client must pin that CA properly rather than copy this
# shortcut.
CREDS=(-insecure
       -cert "$CERT_DIR/client_cert.pem"
       -key  "$CERT_DIR/client_key.pem"
       -import-path "$PROTO_PATH"
       -H "client_id:$CLIENT_ID"
       -max-time "$DURATION")

# service:proto:subscription-rpc
STREAMS=(
  "DistanceService:workout/DistanceService.proto:DistanceSubscription"
  "SpeedService:workout/SpeedService.proto:SpeedSubscription"
  "InclineService:workout/InclineService.proto:InclineSubscription"
  "ElapsedTimeService:workout/ElapsedTimeService.proto:ElapsedTimeSubscription"
  "CaloriesBurnedService:workout/CaloriesBurnedService.proto:CaloriesBurnedSubscription"
  "HeartRateService:workout/HeartRateService.proto:HeartRateSubscription"
  "ConsoleService:console/ConsoleService.proto:ConsoleStateChanged"
)

echo "Recording to $OUT for ${DURATION}s. Read-only: no command is ever sent."
echo "Start your workout on the treadmill's own controls whenever you are ready."
echo

PIDS=()
for entry in "${STREAMS[@]}"; do
  IFS=: read -r svc proto rpc <<<"$entry"
  [[ -f "$PROTO_PATH/$proto" ]] || { echo "  skip $svc (no proto)"; continue; }
  # `{t: now} + .` timestamps each message without inventing any field the server did not send —
  # an omitted field must stay omitted in the capture, because its absence is the finding.
  ( grpcurl "${CREDS[@]}" -proto "$proto" "127.0.0.1:$PORT" "com.ifit.glassos.$svc/$rpc" 2>"$OUT/$svc.err" \
      | jq -c --unbuffered '{t: now} + .' >>"$OUT/$svc.jsonl" ) &
  PIDS+=($!)
  echo "  streaming $svc/$rpc"
done

trap 'for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done' INT TERM

echo
echo "Ctrl-C to stop early."
wait

echo
echo "=== capture summary ==="
for f in "$OUT"/*.jsonl; do
  [[ -e "$f" ]] || continue
  printf "%-28s %6s messages\n" "$(basename "$f")" "$(wc -l <"$f" | tr -d ' ')"
done
echo
echo "Fields actually observed (this is what MachineLink may parse):"
for f in "$OUT"/*.jsonl; do
  [[ -s "$f" ]] || continue
  echo "  $(basename "$f" .jsonl): $(jq -r 'keys[]' "$f" 2>/dev/null | sort -u | grep -v '^t$' | tr '\n' ' ')"
done
echo
echo "Saved to $OUT"
