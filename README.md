# SleepBot v2

A modern rebuild of **SleepBot 3.2.8** (2013–2015) in Kotlin + Jetpack Compose. Behaviour, formulas,
texts, translations and the original artwork were carried over from the 3.2.8 APK, while platform
plumbing was modernised. This repo no longer depends on the old APK.

## Build

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # stats / debt / formatting tests
```

Requires JDK 17+ and an Android SDK with platform 37 (`local.properties` → `sdk.dir`).

Translations in `res/values-*/strings.xml` were ported once from 3.2.8 and are now maintained by hand.
Strings missing from a language fall back to English.

## Layout

| Package | What |
|---|---|
| `data` | Room DB (`hours` table kept from the original, plus movement/sound records), `Prefs`, `Stats` (legacy Statistics port), `Debt` |
| `session` | `SleepSession` — punch-in / punch-out state machine used by every entry point |
| `ui/home`, `ui/entries`, `ui/overview` | the four main tabs, entry editor, landscape graphs |
| `ui/graph` | Compose port of the original custom `GraphView` (trend / bars / pattern / movement / sound) |
| `tracking` | foreground tracking service (original accelerometer + sound algorithms), night screen, sensor graphs, bedtime reminders |
| `alarm` | the built-in alarm clock (list, editor, ringing screen with swipe-to-dismiss), smart alarm, auto alarm |
| `settings`, `backup` | settings tree, CSV export/import, JSON backup/restore |
| `help`, `widget`, `api` | knowledge base (original HTML in `assets/kb`), tutorial, 1×1 widget, `RUN` intent + `sb://` URI API |

## Differences from 3.2.8

- **No server.** mysleepbot.com cloud sync, Facebook, C2DM, Flurry/Crashlytics are gone. Data is
  covered by Android's Google backup (DB + prefs, see `res/xml/*backup*`) and manual JSON backup / CSV
  export via the system file picker. A small sync server can be added later.
- Settings Android no longer allows apps to change were dropped: auto flight mode / mobile data,
  Wi-Fi off, lock-screen text, App2SD, keyguard hacks.
- Smart alarm no longer rewrites the user's stored alarm time; it rings early and skips that
  occurrence instead (same observable behaviour).
- Sound clips are recorded with `AudioRecord` into app-private storage instead of the SD card.
- Known original bugs fixed: inverted sound-sensitivity labels, last sound record never saved,
  session reset orphaning sensor data, empty "Punched in since" time, selectable optimal-hour range.
