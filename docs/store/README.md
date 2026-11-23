# Google Play listing assets

Source of the images uploaded in Play Console → Main store listing → Graphics.
Rules checked against https://support.google.com/googleplay/android-developer/answer/9866151.

| File (`out/`) | Size | Play slot |
|---|---|---|
| `icon-512.png` | 512 × 512 | App icon |
| `feature-graphic.png` | 1024 × 500 | Feature graphic |
| `shot-01.png` … `shot-07.png` | 1080 × 1920 (9:16) | Phone screenshots (2–8 allowed; 4+ at ≥1080 px make the app eligible for large-format recommendations) |

Screenshots and feature graphic are 24-bit PNG without alpha; the icon is a 32-bit PNG (RGBA, full-bleed square) as its spec asks. All well under their size limits.

## How they are made

1. `raw/*.png` are real captures of the app (debug build, same UI as release) on the `cosic35` emulator (1080 × 1920, 420 dpi), status bar in
   demo mode (9:41, full battery, Wi-Fi, no notifications), ads removed (debug build, Settings → "Simulate ads removed") so
   no test banner or "Remove ads" button shows.
2. `screenshots.html`, `feature-graphic.html`, `icon.html` compose them (tagline, background, card).
3. `./render.sh` exports `out/*.png` with headless Chromium. Open a page with `#s3`, `#feature`, `#icon` to see one asset.

To change a tagline or a screenshot: edit the HTML (or replace the raw capture), run `./render.sh`, upload `out/`.

## Content rules followed

- Real app UI only, no device frame, no hands.
- Taglines under 20% of the image, no ranking / price / "free" / "new" / call to action, nothing dated.
- Background in the brand mint and amber, never pure white, black or dark grey.
- Feature graphic: key elements in the central area, no icon lookalike.
- English only. Localise the taglines (not the in-app UI) when a translated listing is added.

## Alt text (≤ 140 characters, paste into the Play Console alt text field)

| Asset | Alt text |
|---|---|
| `feature-graphic.png` | Code of a music sketch with a piano roll drawn under the melody, next to the words "Live-code music". |
| `shot-01.png` | A music sketch playing: the steps that sound are outlined in the code and a piano roll sits under the melody. |
| `shot-02.png` | The edit row of the keyboard with a whole .gain(0.6) call selected using the Sel+ key. |
| `shot-03.png` | Step 2 of an interactive lesson: a piano roll drawn under the code, with Previous and Next buttons. |
| `shot-04.png` | The reference panel showing the lpf function with Insert buttons, and autocomplete suggestions above the edit row. |
| `shot-05.png` | The Sounds panel listing drum machines, each with the names of the sounds it plays. |
| `shot-06.png` | A sketch playing with an oscilloscope drawn under the sawtooth line. |
| `shot-07.png` | The library: three saved projects above the list of lessons. |
| `icon-512.png` | Cosic icon: a ring of eight steps, three of them lit in mint. |
