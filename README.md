# Ran — free calls & messages on your local Wi-Fi

**Ran** lets phones on the same Wi-Fi call, video-call and message each other **with no internet, no SIM card,
no server and no computer**. Everything stays on the phones. Free and non-profit, made for universities,
schools, NGOs and emergency teams.

Developer: **Nawzad Rasul Mohammed** — nawzadrasul92@gmail.com — Ranya, Sulaymaniyah, Kurdistan Region, Iraq.
Built for the University of Raparin, English Department.

## What it does
- **Voice and video calls** over the local Wi-Fi, with ringing on the lock screen
- **Messages, photos, videos and files** (up to 1 GB), with save-to-gallery
- **Your phone number is your identity** — you type it yourself (country picker, Iraq by default, checked for mistakes)
- **Optional profile picture**, shared only with people you talk to
- **End-to-end encryption** everywhere (ECDH P-256 + AES-256-GCM), QR verification, safety numbers
- **Voice rooms** (walkie-talkie), **safety check-in**, **SOS**, **roll call** for teams
- **Connection check**: if something blocks calls, Ran says exactly what and offers a fix
- **Kurdish (Sorani), Arabic and English**, right-to-left, light and dark themes
- **Pause / Resume** receiving at any time
- No accounts, no adverts, no tracking, no data collection

## Build
Push to GitHub — the Action produces:
- `Ran.apk` — install directly on phones
- `Ran-play.aab` — upload to Google Play

For Play, add your own upload key as repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Project structure
```
app/src/main/java/iq/uor/ran/
  App.kt                     application start-up, notification channels
  core/crypto/               identity keys, encrypted channel, QR codes
  core/net/                  Wi-Fi discovery, links, boot receiver
  core/data/                 storage, translations, app info
  feature/call/              calls & video (WebRTC), ringing, call screen
  feature/chat/              messages, files, media saving
  feature/rooms/             voice rooms (walkie-talkie)
  feature/safety/            check-in, SOS, roll call, team
  feature/setup/             country list, phone validation, profile picture
  ui/                        home screen, settings, About, connection check
```

## Ports used on the local network
| Port | Protocol | Purpose |
|------|----------|---------|
| 45454 | UDP | finding other phones (only if you allow it) |
| 45455 | TCP | encrypted handshake, calls, messages, files, profile pictures |
| 45456 / dynamic | UDP | encrypted voice and video |
| 45457 | UDP | voice rooms |

See `docs/PLAY-STORE.md` for the store listing and `docs/PRIVACY-POLICY.md` for the privacy policy.
