# Changelog

Each release's notes on GitHub (and in Breezy's update prompt) come from its section here.

## 0.9.5
- **Site icons on home shortcuts**: shortcuts show just the site's own colour icon, like the tab
  switcher, instead of a letter in a shape. Shortcuts without an icon use a plain circle. Icons are saved as you browse (never from private tabs); a
  shortcut to a site you haven't visited fetches its icon once.

## 0.9.4
- **Swipe up on the toolbar** to open the tab switcher. (Long-press and drag still moves the
  toolbar.)

## 0.9.3
- **Smarter language detection**: Breezy reads the page's text to decide its language (on your
  device, nothing is sent), instead of trusting what the site declares. Pages like French
  articles on politico.eu, which label everything English, now get the translate button.
  Single-page sites are re-checked as you move around them.

## 0.9.2
- **Translate button in the toolbar**: when a page is in another language, a translate button
  appears next to Shields. Tap it to translate; tap again to show the original. Nothing is
  translated until you tap it.
- **Swipe to close tabs**: swipe a tab sideways in the tab switcher to close it (Undo still
  works). Can be turned off in Settings → Search & browsing.

## 0.9.1
- **SponsorBlock fixed**: segments are now caught even when YouTube's player hides playback
  events from other scripts, the "Skipped" bar shows in fullscreen, and real segments are no
  longer ignored when the video's length differs slightly from SponsorBlock's records.
- SponsorBlock lookups and skips are listed in Settings → Advanced → Share diagnostics.

## 0.9.0
- **SponsorBlock for YouTube**: sponsors, self-promotion and subscribe reminders are skipped
  automatically, with **Undo**. Choose categories (intros, outros, previews, filler...) in
  Settings → Downloads & media. Lookups are anonymous: YouTube video ids are never sent.
- **Translate pages**: Menu → **Translate page** translates in place, including content that
  loads later, and **Show original** switches back. Breezy offers it when a page is in another
  language. Choose the language in Settings → Search & browsing.
- **Fingerprinting protection** (Shields): canvas, WebGL and audio get invisible noise that's
  different for every site and every session, so sites can't recognise your device. Off when
  Shields are down for a site.
- **Global Privacy Control**: tells sites not to sell or share your data (on by default), plus an
  optional Do Not Track signal. Settings → Privacy & security.

## 0.8.1
- **What's new in updates**: release pages list the changes in each version, and Breezy's update
  prompt shows them before you install.

## 0.8.0
- **In-app updates**: Breezy checks for a new release once a day and offers **Update**. It
  downloads the APK, checks it against the published checksum and opens the installer.
  Settings → Advanced has **Check for updates** and an option to turn the daily check off.

## 0.7.4
- Search suggestions: pages you've visited now sit right above the search box, then search
  suggestions, then open tabs, so the best matches are a quick tap away.

## 0.7.3
- **Install APKs from the browser**: a "Downloaded · Install" bar appears when an APK finishes,
  and tapping the notification or the file in Downloads opens the installer.
- "View on Reddit" and other links inside embedded posts open as a normal new tab (fixes the app
  closing).
- Crash reports are offered after any unexpected exit; Settings → Advanced → Share diagnostics.
- Settings: more space above the first group.

## 0.7.2
- Themed (Pixel "minimal") icon shows the breeze waves cut out of the shield.

## 0.7.1
- Breezy no longer closes when a web page crashes; the tab reloads instead.

## 0.7.0
- **Settings reorganised** into collapsible groups with a summary of each group's settings.
- **Theme**: System, Light or Dark, plus **Pure black (AMOLED)**.
- Downloads slow down and retry when a server says "too many requests" (HTTP 429) instead of failing.

## 0.6.1
- Video downloads from embedded players (e.g. on anime sites) send the player's own referrer, so
  servers stop refusing them with HTTP 403.

## 0.6.0
- **Download manager** with multi-connection downloads, pause/resume and retry.
- **Media detection**: download videos, audio and HLS streams that a page plays.
- **Secure DNS (DNS over HTTPS)**: Cloudflare, Quad9, Mullvad, AdGuard, Google or custom.
