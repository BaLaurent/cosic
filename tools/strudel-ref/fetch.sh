#!/usr/bin/env bash
# Fetches the pinned Strudel reference checkout into tools/strudel-ref/strudel, installs its pnpm workspace and
# builds doc.json. Every stage is skipped once it has completed, so the script is safe to re-run.
#
#   bash tools/strudel-ref/fetch.sh
#
# The checkout is the oracle of the parity harness (see docs/parity/README.md). It is git-ignored.
set -euo pipefail

COMMIT=84ef32eec8074f90c6690ab8d6a6c204f508b182
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/strudel"

# A missing or moved remote must fall through to the next method, not wait for a username.
export GIT_TERMINAL_PROMPT=0

# ---- 1. checkout --------------------------------------------------------------------------------------------
# Each method is a && chain on purpose: inside `if method; then`, `set -e` is ignored, so a failing step in the
# middle of a function body would not stop it.
fetch_shallow() {
  rm -rf "$SRC" && mkdir -p "$SRC" \
    && git init -q "$SRC" \
    && git -C "$SRC" remote add origin https://codeberg.org/uzu/strudel.git \
    && git -C "$SRC" fetch --depth 1 origin "$COMMIT" \
    && git -C "$SRC" checkout -q FETCH_HEAD \
    && [ "$(git -C "$SRC" rev-parse HEAD)" = "$COMMIT" ]
}

fetch_clone() {
  rm -rf "$SRC" \
    && git clone --filter=blob:none https://codeberg.org/uzu/strudel.git "$SRC" \
    && git -C "$SRC" checkout -q "$COMMIT" \
    && [ "$(git -C "$SRC" rev-parse HEAD)" = "$COMMIT" ]
}

fetch_tarball() {
  rm -rf "$SRC" && mkdir -p "$SRC" \
    && curl -fL "https://codeberg.org/uzu/strudel/archive/$COMMIT.tar.gz" | tar -xz --strip-components=1 -C "$SRC"
}

if [ ! -f "$SRC/.fetched" ]; then
  fetched=0
  for method in fetch_shallow fetch_clone fetch_tarball; do
    if "$method"; then fetched=1; break; fi
    echo "fetch.sh: $method failed, trying the next method" >&2
  done
  if [ "$fetched" != 1 ]; then
    echo "fetch.sh: could not fetch Strudel $COMMIT from codeberg.org/uzu/strudel" >&2
    exit 1
  fi
  echo "$COMMIT" > "$SRC/.fetched"
fi

# ---- 2. toolchain -------------------------------------------------------------------------------------------
# Node 22 matches the checkout's .nvmrc, and Node 24 changed number-to-string rounding (see the comment in
# test/tunes.test.mjs), so the reference is run under Node 22 when npx can provide it. The command prefix that
# worked is stored in .runner for export.mjs, which runs vitest through the same toolchain.
if [ ! -f "$SRC/.runner" ]; then
  runner="npx --yes -p node@22 -p pnpm@10.15.0 --"
  if ! (cd "$SRC" && $runner node --version > /dev/null 2>&1); then
    echo "fetch.sh: node@22 is not available through npx, falling back to the system Node ($(node --version))" >&2
    runner="npx --yes -p pnpm@10.15.0 --"
  fi
  echo "$runner" > "$SRC/.runner"
fi
RUNNER="$(cat "$SRC/.runner")"

# ---- 3. install and doc.json --------------------------------------------------------------------------------
# --ignore-scripts: pnpm 10 blocks dependency build scripts anyway, and none of them is needed to read the sources.
if [ ! -f "$SRC/.installed" ]; then
  (cd "$SRC" && $RUNNER pnpm install --frozen-lockfile --ignore-scripts)
  touch "$SRC/.installed"
fi

if [ ! -f "$SRC/doc.json" ]; then
  (cd "$SRC" && $RUNNER pnpm jsdoc-json)
fi

[ -f "$SRC/doc.json" ] || { echo "fetch.sh: pnpm jsdoc-json did not write doc.json" >&2; exit 1; }
echo "fetch.sh: Strudel $COMMIT ready in $SRC (runner: $RUNNER)"
