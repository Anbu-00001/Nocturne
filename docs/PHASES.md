# Nocturne build phases

The spec's §9 phases, split into steps small enough to verify one at a time.
📱 = needs the phone (Oppo A18, CPH2591, Android 15 / ColorOS 15). Everything else runs on the laptop.

| Step | What | Phone | Status |
|---|---|---|---|
| 1a | `:core-model` glance classifier, time handling, adversarial and fuzz tests | no | done, 37 tests |
| 1b | Multi-module Gradle, Android-import guard on `:core-model`, `:tone` with a copy audit test | no | done |
| 1c | Room schema v1, idempotent raw_events, recompute, CSV export, DST/travel query tests | no (Robolectric) | done |
| 1d | Harvester worker, boot/timezone receiver, onboarding, Last night, Patterns, Settings/health | 📱 verify | installed on the A18 2026-09-14: see results below |
| 1e | 7-day harvester soak with no gaps (the gate before Phase 2) | 📱📱 | not started |
| 2a | Light dose (§6.1), Giménez suppression (§6.2), sleep inference (§6.3) in `:core-model` | no | allowed during the soak |
| 2b | Light sampler service (`specialUse`), personalised evening window, Tonight screen | 📱 | after 1e passes |
| 2c | Sleep inference against 14 hand-labelled nights, ≥80% within 30 min | 📱 | after 2b |
| 3a | μEMA reflection cards, focus timer with interruption count | 📱 | |
| 3b | Hannay19 port + golden-file test against Python `circadian` | laptop, 📱 for a real week of light CSV | |
| 3c | Personal sensitivity fit, n ≥ 30 nights | 📱 | |
| 4 | ActivityWatch exporter on Ubuntu | laptop + 📱 export | |

## Building

- JDK 17, Android SDK Platform **37.2** (Compose BOM 2026.09, current AndroidX and Vico 3.3 require compiling against API 37; `targetSdk` stays 36), Build Tools 36+.
- AGP 9.4.0 with built-in Kotlin 2.4.20, Gradle 9.6.0 via the wrapper, KSP 2.3.12, Room 2.8.5.
- `./gradlew :core-model:test :tone:test :data:testDebugUnitTest :collector:testDebugUnitTest` runs every test on the laptop.
- `./gradlew :app:installDebug` installs on a USB-connected phone.
- Replay a real phone dump through the classifier:
  `adb shell dumpsys usagestats > dump.txt`, then
  `NOCTURNE_DUMPSYS=dump.txt NOCTURNE_TZ=Asia/Kolkata ./gradlew :core-model:test`, and read `core-model/build/dumpsys-replay.txt`.

## First install on the A18 (2026-09-14)

- First harvest backfilled 17,694 events covering 9.2 days, all with offset +330 (Asia/Kolkata). No row has an empty package.
- Visible to the app: 602 KEYGUARD_HIDDEN, 914 SCREEN_INTERACTIVE, 1,001 NOTIFICATION_INTERRUPTION. Keyguard mode applies on this phone.
- A later run re-read 54 overlapping events and inserted 0 (idempotent on a real device).
- 913 sessions over 10 nights: 277 lock-screen and 55 unlocked glances counted; 25 call, 18 alarm and 34 notification wakes attributed.
- The Settings diagnostic caught ColorOS resuming the last app behind the lock screen, and flagged `com.heytap.pictorial`. Both are handled in classifier version 2; the install re-scored all history on start.
- No crashes in the crash log; the WorkManager periodic job is registered with JobScheduler.

## Phone checks for 1d and 1e

1. Install and finish onboarding: usage access, battery exemption, ColorOS auto launch and background activity.
2. Settings shows a first harvest with about 10 days of backfilled events. Last night and Patterns show real nights.
3. Force-stop the app, wait over an hour, reopen: Settings shows the gap was backfilled and no night lost sessions.
4. Reboot without opening the app: harvest runs resume (last run time keeps advancing).
5. Check "Apps seen on locked wakes" in Settings. Every package there should be something that can appear over the lock screen (clock, dialer, camera). If not, the package lists in `ClassifierConfig` need tuning.
6. Soak (1e): for 7 days, "days since last successful harvest" never goes above 0, and no night has a hole.

## Where the build departs from the spec, and why

- **`LOCKED_EXTENDED` session kind.** §6.5's four kinds leave a lit, never-unlocked screen of 15 s or more with no kind: alarms, calls, reading notifications on the lock screen.
- **Unlocked wakes under 30 s that open a real app are `SHORT`.** The spec's `SHORT` starts at 30 s, which left them unclassified too.
- **`WakeTrigger` (notification, alarm, call).** Alarms and incoming calls light the screen on their own. They are recorded but not counted as glances. Calls are recognised by the in-call activity name, because Google Dialer hosts both its normal screens and the call screen. Both rules were checked against a real 24 h dump from the phone, which contained 6 genuine calls.
- **raw_events natural key adds `className`, and no key column is nullable.** SQLite treats NULLs as distinct in UNIQUE indexes, so `INSERT OR IGNORE` would re-insert those rows on every overlapping harvest. Global events carry package `android` on the device, not null.
- **First run backfills 10 days, not 7.** The AOSP `UsageStatsDatabase` prunes daily files older than 10 days, and the phone holds exactly 10.
- **Harvest cursor lives in a `harvest_runs` table, not DataStore.** The same table is the health log. The cursor only moves after a complete run, and a run that died halfway is re-derived on the next one.
- **Harvests are skipped until the phone has been unlocked once after boot.** Before that, `queryEvents` returns nothing, and advancing the cursor past that window would lose it for good.
- **`zone_changes` table.** Each event's offset is resolved for its own instant from a log of zone changes, so DST and travel stay correct. That log comes from `ACTION_TIMEZONE_CHANGED` plus a check on every harvest.
- **Sessions are keyed by wake time.** Re-derivation is a delete-from plus insert. Incremental and full recompute share one code path, and a test asserts they produce identical rows.
- **On a wake that never unlocked, only apps that can draw over the lock screen are credited.** ColorOS resumes the last-used app behind the keyguard on every wake.
- **`CLASSIFIER_VERSION`.** When it goes up, the app recomputes every session from raw_events on its next start, so improvements re-score the whole history (§5).
- **Evening window is provisional (21:00 to 07:00)** until §6.3 has 7 nights. Every session is re-tagged by recompute when Phase 2 lands.
- **Dark theme only.** The app is opened at night, and its own screen is the light source it measures.

## Device facts that matter later

- Hardware light sensor `stk33c01` (TYPE_LIGHT), so §6.1 ambient light is available.
- `screen_brightness` reads 566, so ColorOS does not use a 0 to 255 scale. §6.1 needs the real maximum before normalising.
- Screen-off timeout is 30 minutes, so `SCREEN_NON_INTERACTIVE` can trail real use by up to that long. §6.3 sleep onset should anchor on the last user activity, not screen-off.
- On a fingerprint wake, ColorOS logs the last app's `ACTIVITY_RESUMED` before `KEYGUARD_HIDDEN`.
