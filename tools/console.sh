#!/usr/bin/env bash
# Finding the console, shared by the tools that need one.
#
# Source this; it defines find_console and require_console. There is deliberately no default
# address: the wireless-debugging port changes on every reboot, so a hardcoded one is wrong more
# often than it is right, and it fails late — after a two-minute build — with "device not found".
#
# A discovered address can be wrong the same way, which is the other half of the problem. The
# console advertises two adb services and they are not interchangeable:
#
#   _adb-tls-connect._tcp   the pairing endpoint, whose port rotates on every daemon restart
#   _adb._tcp               classic `adb tcpip`, which docs/RUNBOOK.md pins to 5555
#
# Both can be advertised at once, and the rotating one can still be advertised after it has
# stopped answering: a GlassOS console was seen publishing an _adb-tls-connect record whose port
# refused every connection while _adb._tcp on 5555 worked. Choosing a service type in advance is
# therefore choosing a coin flip. What settles it is connecting — the address that answers is the
# address — so discovery tries candidates in preference order and returns the first that actually
# attaches, rather than the first that is advertised.


# Reports adb's state word for $1 — "device", "unauthorized", "offline" — or nothing if adb has
# never heard of it. Deliberately not called adb-is-connected: the states are not interchangeable
# and the caller has to be able to tell them apart.
#
# `adb` is called plainly rather than through an overridable variable. tools/deploy.sh and
# tools/certify.sh both wrap plain `adb` from PATH, so a knob here would only ever half-apply — and
# two adb clients of different versions kill and restart each other's server, dropping the very
# transport this file just verified.
_console_state() {
  command adb devices | awk -v want="$1" '$1 == want { print $2; exit }'
}

# Whether $1 is usable now. `adb devices` is the authority: `adb connect` can report success for a
# host that accepts the TCP connection and then fails the adb handshake.
_console_attached() {
  [ "$(_console_state "$1")" = "device" ]
}

# Try to attach $1. Never fatal — callers are walking a candidate list — but not silent either.
_console_connect() {
  [ -n "${1:-}" ] || return 1
  _console_attached "$1" && return 0

  local out state
  out="$(command adb connect "$1" 2>&1)"
  _console_attached "$1" && return 0

  # An address that answered and then sat at `unauthorized` is a different problem from a dead
  # port, and it has a different fix — one dialog on the console, per docs/RUNBOOK.md — so it must
  # not be folded into "wireless debugging is off". Losing this distinction sends an operator into
  # Developer options on a console whose launcher may be the reason they are here.
  state="$(_console_state "$1")"
  if [ -n "$state" ]; then
    echo "    $1 is $state" >&2
  elif [ -n "$out" ]; then
    echo "    $out" >&2
  fi
  return 1
}

# Prints "host:port" for one advertised service type, or fails.
_console_advertised() {
  local svc="$1" found name line host port ip

  # adb's own resolver first: it is already running and needs no extra tooling.
  found="$(command adb mdns services 2>/dev/null |
    awk -v svc="$svc" '$2 ~ svc { print $3; exit }')"
  if [ -n "$found" ]; then echo "$found"; return 0; fi

  command -v dns-sd >/dev/null 2>&1 || return 1

  # Only the "Add" rows are results; the browse banner names the service type too, so matching on
  # the type alone finds the banner and nothing else.
  name="$(timeout 5 dns-sd -B "$svc" 2>/dev/null |
    awk '$2 == "Add" { print $NF; exit }')"
  [ -n "$name" ] || return 1

  # "... can be reached at Android.local.:41517 (interface 14)" — the last field is the interface,
  # so the host:port is taken by shape rather than by position.
  line="$(timeout 5 dns-sd -L "$name" "$svc" 2>/dev/null |
    awk '/can be reached at/ {
           for (i = 1; i <= NF; i++) if ($i ~ /:[0-9]+$/) { print $i; exit }
         }')"
  [ -n "$line" ] || return 1

  host="${line%%:*}"; port="${line##*:}"
  # adb connect wants an address, not the .local name mDNS reports.
  ip="$(dscacheutil -q host -a name "${host%.}" 2>/dev/null |
    awk '/^ip_address/ { print $2; exit }')"
  [ -n "$ip" ] && echo "$ip:$port" || echo "${host%.}:$port"
}

# Prints an adb address for a console that is attached by the time this returns, or fails.
#
# Classic `adb tcpip` is tried before the pairing endpoint because it is the one that survives:
# its port is pinned, so when both answer, preferring it means the address stays valid across the
# next daemon restart instead of going stale under whoever cached it.
find_console() {
  local connected candidate host svc tried=""

  connected="$(command adb devices | awk '$2 == "device" { print $1; exit }')"
  if [ -n "$connected" ]; then echo "$connected"; return 0; fi

  for svc in _adb._tcp _adb-tls-connect._tcp; do
    candidate="$(_console_advertised "$svc")" || continue
    if _console_connect "$candidate"; then echo "$candidate"; return 0; fi
    # Reported rather than swallowed: an advertised service that will not attach is the symptom
    # that sends people looking at the console when the other service type was up all along.
    echo "    $svc advertised $candidate, which did not attach" >&2
    tried="$tried $candidate"
    host="${candidate%%:*}"
  done

  # Last resort, and only for a host mDNS already named: docs/RUNBOOK.md pins classic wireless
  # debugging to 5555, so a console whose pairing record has gone stale is often still reachable
  # there. This is not a hardcoded address — it is a hardcoded *port* on a discovered host.
  #
  # Skipped when it was already a candidate. _adb._tcp is by definition advertised on the pinned
  # port, so when that is the record that failed, retrying the same address buys nothing, costs a
  # full connect timeout, and prints the identical refusal twice — muddying the legible failure
  # this is here to produce.
  if [ -n "${host:-}" ]; then
    local fallback="$host:5555"
    case " $tried " in
      *" $fallback "*) ;;
      *) if _console_connect "$fallback"; then echo "$fallback"; return 0; fi ;;
    esac
  fi

  return 1
}

# Resolves $1 if given, otherwise discovers one, and connects. Prints the address.
#
# Verifies rather than assumes. This used to run `adb connect ... || true` and print the address
# regardless, so a refused port produced a confident answer and the caller failed later with
# "device not found" — the exact late failure this file exists to prevent.
require_console() {
  local device="${1:-}"
  if [ -z "$device" ]; then
    echo "==> looking for a console" >&2
    if ! device="$(find_console)"; then
      echo "no console found. If a candidate above reported 'unauthorized', accept the dialog on" >&2
      echo "the console instead — the port is fine and your host key is not trusted yet. Otherwise" >&2
      echo "wireless debugging is off (it does not survive a reboot); turn it back on, then either" >&2
      echo "pass the address or run: adb connect <ip>:<port>. See docs/RUNBOOK.md." >&2
      return 1
    fi
    echo "    found $device" >&2
    echo "$device"
    return 0
  fi

  if ! _console_connect "$device"; then
    echo "$device did not attach. If it reported 'unauthorized' the port is fine and the console" >&2
    echo "needs a dialog accepted; if the console rebooted, wireless debugging is off and the port" >&2
    echo "it advertised is stale. See docs/RUNBOOK.md." >&2
    return 1
  fi
  echo "$device"
}
