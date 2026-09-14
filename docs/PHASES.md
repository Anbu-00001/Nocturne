# Nocturne build phases

The spec's §9 phases, split into steps small enough to verify one at a time.
📱 = needs the phone (Oppo A18, CPH2591, Android 15 / ColorOS 15). Everything else runs on the laptop.

| Step | What | Phone | Status |
|---|---|---|---|
| 1a | `:core-model` glance classifier, time handling, adversarial and fuzz tests | no | done |
| 1b | Multi-module Gradle, Android-import guard on `:core-model`, `:tone` with a copy audit test | no | done |
| 1c | Room schema v1, idempotent raw_events, recompute, CSV export, DST/travel query tests | no (Robolectric) | done |
| 1d | Harvester worker, boot/timezone receiver, onboarding, Last night, Patterns, Settings/health | 📱 verify | installed on the A18 2026-09-14 |
| 1e | 7-day harvester soak with no gaps (the gate before Phase 2b) | 📱📱 | running since 2026-09-14 |
| 2a | Light dose (§6.1), Giménez suppression (§6.2), sleep inference (§6.3) in `:core-model` | no | done, replayed on 10 real nights |
| 2a+ | Sleep in the existing app: schema 2, nights table, personalised evening window, "I slept about X to Y" entry, sleep chart. No foreground service. | 📱 verify | installed on the A18 2026-09-15 |
| 2b | Light sampler service (`specialUse`), Tonight screen, light samples feeding §6.1/§6.2 | 📱 | after 1e passes |
| 2c | Sleep inference against 14 hand-labelled nights, ≥80% of onsets within 30 min | 📱 | needs your corrected times |
| 3a | μEMA reflection cards, focus timer with interruption count | 📱 | |
| 3b | Hannay19 port + golden-file test against Python `circadian` | laptop, 📱 for a real week of light CSV | |
| 3c | Personal sensitivity fit, n ≥ 30 nights | 📱 | |
| 4 | ActivityWatch exporter on Ubuntu | laptop + 📱 export | |

## Building

- JDK 17, Android SDK Platform **37.2** (Compose BOM 2026.09, current AndroidX and Vico 3.3 require compiling against API 37; `targetSdk` stays 36), Build Tools 36+.
- AGP 9.4.0 with built-in Kotlin 2.4.20, Gradle 9.6.0 via the wrapper, KSP 2.3.12, Room 2.8.5.
- `./gradlew :core-model:test :tone:test :data:testDebugUnitTest :collector:testDebugUnitTest` runs every test on the laptop.
- `./gradlew :app:installDebug` installs on a USB-connected phone.

Developer tools that read personal data. Keep every export and database copy out of the repository.

- Replay a `dumpsys usagestats` dump through the classifier:
  `NOCTURNE_DUMPSYS=dump.txt NOCTURNE_TZ=Asia/Kolkata ./gradlew :core-model:test`, then read `core-model/build/dumpsys-replay.txt`.
- Replay a raw-event CSV (Settings, Export) through sleep inference:
  `NOCTURNE_EVENTS_CSV=export.csv ./gradlew :core-model:test`, then read `core-model/build/sleep-replay.txt`.
  Add `NOCTURNE_SLEEP_LABELS=labels.csv` (lines like `2026-09-06,00:10,08:30`, local time) for the §10 hit rate, plain and leave-one-out.
- Check a schema change against the phone's real database before installing:
  copy `databases/nocturne.db` (plus `-wal`, `-shm`) off the phone with `adb exec-out run-as io.github.anbu00001.nocturne cat …`,
  then `NOCTURNE_LIVE_DB=nocturne.db ./gradlew :data:testDebugUnitTest --tests '*LiveDatabaseTest*'` and read `data/build/live-database.txt`.

## First install on the A18 (2026-09-14)

- First harvest backfilled 17,694 events covering 9.2 days, all with offset +330 (Asia/Kolkata). No row has an empty package.
- Visible to the app: 602 KEYGUARD_HIDDEN, 914 SCREEN_INTERACTIVE, 1,001 NOTIFICATION_INTERRUPTION. Keyguard mode applies on this phone.
- A later run re-read 54 overlapping events and inserted 0 (idempotent on a real device).
- The Settings diagnostic caught ColorOS resuming the last app behind the lock screen, and flagged `com.heytap.pictorial`. Both handled in classifier version 2.

## Phase 2 install on the A18 (2026-09-15)

- Before installing, schema 2 migrated a fresh copy of the phone database in `LiveDatabaseTest`: 18,766 raw events before and after, 933 sessions, every night recomputed. A backup of that copy was kept off the repository.
- On the phone after `adb install -r`: schema 2, 18,769 raw events, all 933 sessions re-derived with classifier 3, nights written, model version `3.1` recorded, no crash.
- Classifier 3 on the real history: 37 alarm wakes (was 18) now that the clock's upcoming-alarm notices count as alarms; 319 counted glances.
- You confirmed the estimate for the night of Sun 13 Sept (asleep 04:09 to 13:00): the first labelled night toward 2c.
- Sleep estimates for the 9 complete nights match the laptop replay exactly. Only 5 reach confidence 0.4, so the evening window stays provisional until more nights, or your entered times, reach 7.

## Phone checks for 1d and 1e

1. Install and finish onboarding: usage access, battery exemption, ColorOS auto launch and background activity.
2. Settings shows a first harvest with about 10 days of backfilled events. Last night and Patterns show real nights.
3. Force-stop the app, wait over an hour, reopen: Settings shows the gap was backfilled and no night lost sessions.
4. Reboot without opening the app: harvest runs resume (last run time keeps advancing).
5. Check "Apps seen on locked wakes" in Settings. Every package there should be something that can appear over the lock screen (clock, dialer, camera). If not, the package lists in `ClassifierConfig` need tuning.
6. Soak (1e): for 7 days, "days since last successful harvest" never goes above 0, and no night has a hole.

## Phone checks for 2a+ and 2c

1. Last night shows "Asleep about … to …" with a confidence, and a sleep line under the timeline.
2. For nights you remember, tap "Correct these times" and enter when you fell asleep and woke. After three such nights the others shift by your typical gap (corrective offsets).
3. Patterns shows one sleep bar per night; entered nights are solid, estimates lighter.
4. 2c passes when at least 80% of 14 labelled nights have an estimated onset within 30 min of your times, scored leave-one-out.

## Where the build departs from the spec, and why

Phase 1:

- **`LOCKED_EXTENDED` session kind.** §6.5's four kinds leave a lit, never-unlocked screen of 15 s or more with no kind: alarms, calls, reading notifications on the lock screen.
- **Unlocked wakes under 30 s that open a real app are `SHORT`.** The spec's `SHORT` starts at 30 s, which left them unclassified too.
- **`WakeTrigger` (notification, alarm, call).** Alarms and incoming calls light the screen on their own. They are recorded but not counted as glances. Calls are recognised by the in-call activity name, because Google Dialer hosts both its normal screens and the call screen.
- **raw_events natural key adds `className`, and no key column is nullable.** SQLite treats NULLs as distinct in UNIQUE indexes, so `INSERT OR IGNORE` would re-insert those rows on every overlapping harvest.
- **First run backfills 10 days, not 7.** The AOSP `UsageStatsDatabase` prunes daily files older than 10 days.
- **Harvest cursor lives in a `harvest_runs` table, not DataStore.** The same table is the health log.
- **Harvests are skipped until the phone has been unlocked once after boot.** Before that, `queryEvents` returns nothing.
- **`zone_changes` table.** Each event's offset is resolved for its own instant, so DST and travel stay correct.
- **Sessions are keyed by wake time.** Incremental and full recompute share one code path, and tests assert identical rows.
- **On a wake that never unlocked, only apps that can draw over the lock screen are credited.** ColorOS resumes the last-used app behind the keyguard on every wake.
- **Dark theme only.** The app is opened at night, and its own screen is the light source it measures.

Phase 2:

- **Suppression uses the Giménez 2022 equation itself, not the spec's `100 / (1 + (ED50 / mEDI)^k)`.** The paper's Eq. 7 fits a logistic to log10(mEDI·10⁶) (b = 9.002, d = 7.496), which is far shallower: around ED50 it behaves like a Hill slope near 0.4, so a tenth of ED50 still gives about a quarter of the suppression. Table 3 prints the duration coefficient as −0.008; the paper's own worked ED50s (208 lx at 90 min, 72 lx dilated) need −0.0076, which also reproduces the spec's 600/350/120/43/15 table, so that value is used and both are tested.
- **Phillips 2019 and Zeitzer 2000 are cross-checks, never averaged in.** Phillips' rising per-hour ED50s (13.47/19.38/38.89 lx) measure melatonin AUC within each hour after DLMO, a different quantity from Giménez's cumulative duration. Phillips' 24.6 photopic lx at ~4000 K is within a factor of 2 of the model's 4 h ED50 (15 lx mEDI).
- **Durations outside Giménez's 30 to 240 min are clamped and flagged**, not extrapolated.
- **Bursty exposure is a band, not a hidden choice.** How intermittent light adds up for melatonin is unsettled (Najjar and Zeitzer 2016). The mid estimate uses exposed minutes at their mean; the band spans "only the longest unbroken run" to "first to last exposure as one block". An unbroken block gives the paper's value exactly.
- **No γ = 2.2 on screen brightness.** Since Android 9 the slider converts to linear before writing `SCREEN_BRIGHTNESS`, so the stored value is already linear (AOSP BrightnessController). The A18's own display config maps its 0 to 4095 setting linearly onto 2 to 490 nits.
- **Screen light includes a content-level factor.** A dark interface is mostly black; the spec's formula assumes a full-white screen. It is an assumption with a wide range (0.05 to 0.5 for dark UI), stated as such.
- **Sleep inference is a per-night change-point model, not "last screen-off before a gap over 4 h".** On this phone's real nights the 4 h rule fails: several nights hold 3 h blocks, and a 1 s check at 04:16 splits an 8 h night. As in SensibleSleep (Cuttone et al. 2017), user-initiated sessions arrive as Poisson processes at a low rate while asleep and at the night's own rate while awake; the most likely onset/wake pair wins, with soft priors on duration and, after 7 nights, on usual onset. Wakes the phone caused (a notification, the alarm notice) with nobody touching it are ignored.
- **Onset sees past the screen-off timeout.** The A18 is set to 30 min. A session that timed out is cut back to its last activity, unless the screen was switched back on within 2 min (seen on the phone: a browser timing out and being woken 4 s later).
- **No estimate for nights the history does not cover**, from before the first harvest or still under way (before 06:00 the next morning). Every estimate keeps its runner-up explanation.
- **Charging is sampled at each harvest**, not by an `ACTION_POWER_CONNECTED` receiver, which cannot be declared in the manifest for modern targets. A charging phone is out of Doze, so samples come every 15 min.
- **Your entered times teach a corrective offset** (Abdullah et al. 2016): after 3 entered nights, the median gap between the estimate and your times shifts the other nights, clamped to 90 min. Validation scores it leave-one-out.
- **The evening window is fixed per week**: from the 4 weeks before that week's Monday, needing 7 usable nights; until 4 weeks exist, the earliest nights stand in.
- **Classifier 3.** The clock's "upcoming alarm" notification lights the screen for 10 s, 15 min before each alarm; it is now an alarm wake, not a glance. ColorOS usually logs a waking notification after `SCREEN_INTERACTIVE` (134 of 232 notification wakes within 0 to 1 s after), so that counts too.
- **Schema 2 is additive**, so Room's AutoMigration covers it, verified against schema 1's exported JSON and a copy of the phone's database. `sleep_reports` and `power_samples` are primary data and survive every recompute.

## Device facts that matter later

- Hardware light sensor `stk33c01` (TYPE_LIGHT), so §6.1 ambient light is available.
- Brightness setting runs 0 to 4095 (the slider stops at 3276 without high brightness mode), linear onto 2 to 490 nits. Auto brightness is on and ColorOS writes its value to the setting (140 at night, 566 by day).
- Dark mode is on and ColorOS eye comfort runs at 2700 K from 22:00 to 07:00. At the night setting the screen's modelled mEDI stays under 1 lux even at the high end of the band, so for this phone room light, not the screen, is likely the larger evening contributor. That makes the 2b light sampler worth more than the screen model.
- Screen-off timeout is 30 minutes (`last_manual_screen_off_timeout` says 1 minute was once set). Only the current value is readable, and it is applied to all history.
- Double-tap to wake is on, which likely explains many 1 s lock-screen wakes with no notification (178 in 10 days).
- On a fingerprint wake, ColorOS logs the last app's `ACTIVITY_RESUMED` before `KEYGUARD_HIDDEN`.
