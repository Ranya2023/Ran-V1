# Google Play — everything needed for the release

## Before you start
- Google Play developer account: **$25 once** (play.google.com/console).
- **Option A (recommended for the university):** publish as a *private app* in **managed Google Play** —
  only your organisation sees it, review is lighter and the yearly target-API pressure does not apply.
- **Option B:** public listing on Google Play — normal review, needs everything below.

## App details
- **App name:** Ran
- **Short description (80 chars):** Free calls and messages on your local Wi-Fi — no internet needed.
- **Category:** Communication · **Content rating:** Everyone · **Contains ads:** No · **In-app purchases:** No
- **Package:** `iq.uor.ran` · **Target API:** 36 (required by Google Play since 31 Aug 2026)
- **Privacy policy URL:** publish `docs/PRIVACY-POLICY.md` (for example on GitHub Pages) and paste the link.

## Full description (English)
Ran is a free, non-profit app that lets phones on the same Wi-Fi network talk to each other — with no internet,
no SIM card, no server and no computer.

• Voice and video calls over your local Wi-Fi, ringing on the lock screen
• Messages, photos, videos and files, with saving to your gallery
• Your own phone number is your identity — no codes to remember
• End-to-end encrypted, with QR verification between people who meet face to face
• Voice rooms (walkie-talkie) for teams
• Safety check-in, SOS alert and roll call for emergencies
• Connection check that explains, in your language, why something is not working
• Kurdish (Sorani), Arabic and English · light and dark themes
• No accounts, no adverts, no tracking, no data collection

Ran was built at the University of Raparin for universities, schools, organisations and emergency teams —
places where the Wi-Fi works but the internet may not.

Important: Ran only works between phones on the **same local network**. Some public networks separate devices
("client isolation"); the app tells you when that happens and how to fix it.

## Data safety form (answers)
- Does your app collect or share any user data? **No.**
- Is all data encrypted in transit? **Yes** (end-to-end, on the local network).
- Can users request data deletion? **Yes** — uninstalling removes everything; no server copy exists.
- Location: collected? **No** (a check-in position is sent only between users, never to the developer).

## Notes for the reviewer (put this in "App access / review notes")
Ran works only between two Android phones on the same Wi-Fi network, so a single test device cannot place a call.
To test with two devices: install on both, connect both to the same Wi-Fi (or a phone hotspot), register with any
phone number, open Contacts → the other phone appears under "Available now". A short demo video is attached.
No login and no test account are needed.

## Screenshots to prepare (phone, 1080×1920 or larger)
1. Home with contacts · 2. A chat with a photo · 3. An incoming call on the lock screen · 4. Video call
5. Team / safety check-in · 6. Connection check · 7. Kurdish interface

## Release steps
1. Push to GitHub → download `Ran-play.aab` from the Actions run or the release.
2. Play Console → Create app → upload the AAB to **Internal testing** first.
3. Fill in: store listing, data safety, content rating, target audience, privacy policy URL.
4. Test with your own group, then promote to Production (or to your organisation in managed Google Play).
