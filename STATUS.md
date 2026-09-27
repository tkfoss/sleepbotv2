# SleepBot v2 — status report & test instructions

_Updated 2026-09-27_

## Deliverables

| What | Where |
|---|---|
| Installable APK (debug-signed) | `dist/SleepBot-4.0.0-debug.apk` |
| Source (Kotlin + Compose, Gradle) | `app/`, see `README.md` for the layout |
| Emulator launcher | `scripts/emulator.sh` |
| Sample data generator | `scripts/seed-sample-data.sh` |
| Reverse-engineering specs of 3.2.8 | not in repo; regenerate from the APK if needed |

## Test on the emulator

The Android SDK, emulator and a test device named **`sb`** (720×1280, same resolution as the old
screenshots) are already installed on this Mac under `~/Library/Android/sdk`.

```sh
cd ~/syncthing/work/sleepbotv2
scripts/emulator.sh              # boots "sb" (with a window), installs dist/ APK, launches SleepBot
scripts/emulator.sh --build      # same, but rebuilds from source first
scripts/seed-sample-data.sh 30   # optional: 30 nights of fake entries so graphs have data
```

Handy extras while testing:

```sh
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB emu sensor set acceleration 3:1:9.8          # fake phone movement (night screen graph / smart alarm)
$ADB exec-out screencap -p > shot.png            # screenshot
$ADB logcat -b crash                              # crashes
$ADB uninstall com.sleepbot.app                   # reset everything (tutorial shows again)
```

### On a real phone

Enable *Developer options → USB debugging*, plug in, then
`~/Library/Android/sdk/platform-tools/adb install -r -g dist/SleepBot-4.0.0-debug.apk`
(or copy the APK to the phone and open it, allowing "install unknown apps").
Real sleep tracking, sound recording and alarm reliability can only be judged on a real phone.

### Suggested test checklist

1. First launch → 4-page tutorial → **Get Started**.
2. Header alarm icon → alarm list (presets 8:30 weekdays / 9:00 weekends) → add an alarm 2 min ahead → it rings → try snooze ±5 and **swipe to dismiss**.
3. Home: toggle **Track Motion** / **Record Sound** → **going to sleep…** → night screen (dims after 15 s, Back un-dims) → **waking up!** → "Sleep Entry Created!" dialog (stars + note).
4. Smart alarm: set an alarm within 24 h, enable **Smart Alarm**, punch in, shake/fake movement inside the window → alarm should ring early.
5. Entries tab: date range, row long-press (Edit/Delete), stats icon, **ADD NEW ENTRY**, **SHARE** (CSV).
6. Entry editor: movement/sound mini graphs → tap → zoomed landscape graphs, tap a sound bar to play the clip.
7. Graph tab → tap chart → landscape TREND / LENGTH / PATTERN / SLEEP / WAKE, range spinner, Ratings, share.
8. Help tab: accordion + articles. Settings: every section, Backup/Restore, CSV import, Reset Debt.
9. Widget: long-press home screen → Widgets → SleepBot 1×1 toggle.

## Work status

### Verified on the emulator (screenshots compared with the 3.2.8 ones)
- Tutorial, header/tab bar, Home tab (today's sleep/debt box, punch button, toggles).
- Entries tab (grouping, per-day debt, empty state), entry editor, motion mini graph.
- Overview "Current Trend" + CURRENT DEBT / AVERAGE SLEEP/DAY; landscape TREND / PATTERN / LENGTH charts — visually match the originals.
- Full punch-in → night screen (starfield, clock, live movement graph, dimming) → punch-out → wake dialog.
- Alarm list, `SET_ALARM` intent, alarm firing, ringing screen, swipe-to-dismiss.
- Help accordion + HTML articles (caffeine chart), Settings root screen.
- Intent API (`android.intent.action.RUN`) used to seed data.
- Unit tests: stats/debt/row grouping/formatting (5/5 pass).

### Built but not yet exercised at runtime
- Smart alarm early trigger, auto alarm, snooze re-ring, alarm timeout, reboot rescheduling.
- Sound recording + clip playback in zoomed graphs.
- Bedtime reminders and the "remind later" dialog.
- CSV export/import, JSON backup/restore, `sb://` URI API, home-screen widget, share-as-image.
- Settings sub-screens (reviewed in code, not clicked through).

### Intentionally changed or dropped (see README)
- No cloud server: Google Auto Backup + manual JSON/CSV instead. A small Go sync server is a possible next step.
- Dropped: auto flight mode/Wi-Fi off, lock-screen text, App2SD, keyguard tweaks, force-English, idle auto punch-in, Facebook, analytics.
- Smart alarm no longer rewrites the stored alarm time; alarms stored as JSON prefs (not in Google backup).
- Sound sensitivity labels fixed (index 0 = least sensitive), clips are WAV, max 5 min each.
- Original bugs fixed: last sound record never saved, reset orphaning sensor data, empty "Punched in since" time, unselectable optimal hours.

### Known gaps / next steps
- Only English strings (3.2.8 had ~40 translations in the APK that could be ported).
- Debug-signed APK only; a release keystore + `assembleRelease` is needed for Play Store.
- Night screen can't auto-open on screen-on (Android background-launch limits); opening the app while asleep does show it.
- Help "Read this!"/exercise videos pages need internet and YouTube embeds may refuse to play in a WebView.
- Old 3.2.8 `backup.bak` restore is implemented but untested against a real old backup file.
