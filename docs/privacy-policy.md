# Cosic: Privacy Policy

Last updated: 2026-11-23

Cosic is a live-coding music app for Android. This policy describes what data the app handles and who processes it.

## What is stored on your device

- Cosic has no accounts, no sign-in and no server run by the developer. The developer receives no data from the app.
- Your projects (the code you write), your settings and the list of sample folders you imported are stored on your device. If you have Android's device backup enabled, Android may include this app data in your Google backup. The purchase state and the MQTT passwords you saved are never included in the backup. The backup is handled by Google, not by the developer, and you can turn it off in the Android system settings.
- Sample folders that you import are read locally to play their sounds. Their files are never uploaded.
- Whether you bought "Remove ads" is remembered on the device, so that the app keeps working offline.

## Network requests

Cosic contacts the servers below directly, without going through any server of the developer. Each request reveals your device's IP address, the user agent `Cosic/<version> (Android)` and the address of the file requested. No identifier of you or of your device is sent. The files fetched stay in the app's cache on the device.

- `strudel.b-cdn.net`: the sample library of the Strudel project. The default sound lists are read at start, and each sound file the first time it plays.
- `raw.githubusercontent.com`: GitHub. Sample maps and sample files that a program names with `github:` or `bubo:`.
- `shabda.ndre.gr`: Shabda. Sounds and speech that a program asks for with `shabda:` or `shabda/speech`.
- `felixroos.github.io`: the General MIDI instrument sounds (`gm_*`).
- `pidxdsxphlhzjnzmifth.supabase.co`: the Supabase database of strudel.cc. It is contacted only when you import an old-style strudel.cc short link: the short code is sent, and the code it stands for comes back.
- Any address that your own code names (for example `samples('https://…')`) or that you configure in Settings (the OSC host, the MQTT broker).

Speech output uses the text-to-speech engine of your device, which may process the text with its provider depending on the engine chosen in the Android settings.

## Advertising

The free version of Cosic shows ads served by Google AdMob. To serve, measure and secure ads, the Google Mobile Ads SDK may collect and process the device advertising ID, the IP address, device and app information, ad interactions and diagnostics data.

In the European Economic Area, the United Kingdom and Switzerland, Cosic only requests ads after you have answered Google's consent message (Google User Messaging Platform). When this message applies to you, Settings offers "Privacy options" so you can change your answer at any time. You can also reset or delete your advertising ID in the Android system settings.

After you buy "Remove ads", Cosic no longer shows the consent message and no longer starts the ads SDK.

How Google handles this data is described in Google's privacy policy: <https://policies.google.com/privacy>.

## Purchases

Cosic offers one optional one-time purchase, "Remove ads". It is processed entirely by Google Play. The developer never sees your payment details. Cosic asks Google Play which purchases your account owns, and keeps no purchase data beyond the on/off state described above.

## Children

Cosic is not directed at children.

## Your data and your rights

The developer holds no personal data about you. To remove everything Cosic stored on your device, clear the app's storage or uninstall it. Data that Google processes for ads is managed through your Google account and Android settings.

## Changes to this policy

If this policy changes, the new version is published at the same address with a new date.

## Contact

Questions about this policy: laurent@baazcool.fr
