#!/usr/bin/env bash
# Exports the Play Store assets in this folder to out/ as 24-bit PNGs (no alpha, as Play requires).
# Needs chromium and Python Pillow.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out

shoot() { # page, anchor, width, height, output
  chromium --headless --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --window-size="$3,$4" --virtual-time-budget=3000 \
    --screenshot="out/$5.tmp.png" "file://$PWD/$1#$2" >/dev/null 2>&1
  python3 -c "
from PIL import Image; import sys
im = Image.open('out/$5.tmp.png').convert('RGB')
assert im.size == ($3, $4), im.size
im.save('out/$5.png', optimize=True)"
  rm "out/$5.tmp.png"
}

for n in 1 2 3 4 5 6 7; do shoot screenshots.html "s$n" 1080 1920 "shot-0$n"; done
shoot feature-graphic.html feature 1024 500 feature-graphic
shoot icon.html icon 512 512 icon-512
ls -l out
