#!/usr/bin/env bash
# Verifies that @kabelsalat/core@0.4.0 and @kabelsalat/lib@0.4.1 exist in the pinned Strudel checkout's pnpm store and prints their
# versions plus the integrity lines of pnpm-lock.yaml. Only if the store entries are missing does it `npm pack` them into
# tools/strudel-ref/kabel/vendor/ (git-ignored) and verify the sha512 integrity against the lockfile.
#   usage: tools/strudel-ref/kabel/fetch-kabelsalat.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
strudel="${STRUDEL_DIR:-$here/../strudel}"
store="$strudel/node_modules/.pnpm"
lock="$strudel/pnpm-lock.yaml"
core="$store/@kabelsalat+core@0.4.0/node_modules/@kabelsalat/core"
lib="$store/@kabelsalat+lib@0.4.1/node_modules/@kabelsalat/lib"
ver() { node -e 'const p=require(process.argv[1]+"/package.json");console.log(p.name+"@"+p.version+"  license "+p.license)' "$1"; }
integrity() { grep -A1 -F "'$1':" "$lock" | sed -n 2p | sed 's/^ *//'; }

if [ -f "$core/package.json" ] && [ -f "$lib/package.json" ]; then
  echo "pnpm store: $store"
  ver "$core"; ver "$lib"
  integrity '@kabelsalat/core@0.4.0'
  integrity '@kabelsalat/lib@0.4.1'
  ls "$core/src" "$lib/src" >/dev/null
  echo "ok: kabelsalat sources present in the pnpm store (no vendor/ needed)"
  exit 0
fi

echo "pnpm store entries missing: falling back to npm pack into vendor/" >&2
mkdir -p "$here/vendor" && cd "$here/vendor"
npm pack @kabelsalat/core@0.4.0 @kabelsalat/lib@0.4.1 --silent
for pair in "core:0.4.0" "lib:0.4.1"; do
  n="${pair%%:*}"; v="${pair##*:}"; tgz="kabelsalat-$n-$v.tgz"
  got="sha512-$(openssl dgst -sha512 -binary "$tgz" | base64 -w0)"
  want="$(integrity "@kabelsalat/$n@$v" | sed -E 's/.*integrity: *(sha512-[^,}]*).*/\1/')"
  [ "$got" = "$want" ] || { echo "integrity mismatch for $tgz: $got != $want" >&2; exit 1; }
  mkdir -p "$n" && tar -xzf "$tgz" -C "$n"
  echo "vendored $tgz ($got)"
done
