#!/usr/bin/env bash
# Finding the console, shared by the tools that need one.
#
# Source this; it defines find_console and require_console. There is deliberately no default
# address: the wireless-debugging port changes on every reboot, so a hardcoded one is wrong more
# often than it is right, and it fails late — after a two-minute build — with "device not found".

# Prints an adb address for the console, or fails.
#
# Something already connected wins: it is the one the operator most likely means. Otherwise ask the
# network, because wireless debugging advertises itself over mDNS as _adb-tls-connect._tcp.
find_console() {
  local connected
  connected="$(command adb devices | awk '$2 == "device" { print $1; exit }')"
  if [ -n "$connected" ]; then echo "$connected"; return 0; fi

  local found
  found="$(command adb mdns services 2>/dev/null |
    awk '$2 ~ /_adb-tls-connect/ { print $3; exit }')"
  if [ -n "$found" ]; then echo "$found"; return 0; fi

  command -v dns-sd >/dev/null 2>&1 || return 1

  # Only the "Add" rows are results; the browse banner names the service type too, so matching on
  # the type alone finds the banner and nothing else.
  local name
  name="$(timeout 5 dns-sd -B _adb-tls-connect._tcp 2>/dev/null |
    awk '$2 == "Add" { print $NF; exit }')"
  [ -n "$name" ] || return 1

  # "... can be reached at Android.local.:41517 (interface 14)" — the last field is the interface,
  # so the host:port is taken by shape rather than by position.
  local line
  line="$(timeout 5 dns-sd -L "$name" _adb-tls-connect._tcp 2>/dev/null |
    awk '/can be reached at/ {
           for (i = 1; i <= NF; i++) if ($i ~ /:[0-9]+$/) { print $i; exit }
         }')"
  [ -n "$line" ] || return 1

  local host="${line%%:*}" port="${line##*:}" ip
  # adb connect wants an address, not the .local name mDNS reports.
  ip="$(dscacheutil -q host -a name "${host%.}" 2>/dev/null |
    awk '/^ip_address/ { print $2; exit }')"
  [ -n "$ip" ] && echo "$ip:$port" || echo "${host%.}:$port"
}

# Resolves $1 if given, otherwise discovers one, and connects. Prints the address.
require_console() {
  local device="${1:-}"
  if [ -z "$device" ]; then
    echo "==> looking for a console" >&2
    if ! device="$(find_console)"; then
      echo "no console found. Turn wireless debugging back on (it does not survive a" >&2
      echo "reboot), then either pass the address or run: adb connect <ip>:<port>" >&2
      return 1
    fi
    echo "    found $device" >&2
  fi
  command adb connect "$device" >/dev/null 2>&1 || true
  echo "$device"
}
