# Nocturne build phases

The spec's §9 phases, split into steps small enough to verify one at a time.
📱 = needs the phone (Oppo A18, CPH2591, Android 15 / ColorOS 15). Everything else runs on the laptop.

| Step | What | Phone | Status |
|---|---|---|---|
| 1a | `:core-model` glance classifier, time handling, adversarial and fuzz tests | no | done |
| 1b | Multi-module Gradle, Android-import guard on `:core-model`, `:tone` with a copy audit test | no | done |
| 1c | Room schema v1, idempotent raw_events, recompute, CSV export, DST/travel query tests | no (Robolectric) | done |
| 1d | Harvester worker, boot/timezone receiver, onboarding, Last night, Patterns, Settings/health | 📱 verify | installed on the A18 2026-09-14 |
| 1e | 7-day soak with no harvest gaps, now with the light service running | 📱📱 | restarted 2026-09-15 07:13 when light measurement went on; passes 2026-09-22 07:13 |
| 2a | Light dose (§6.1), Giménez suppression (§6.2), sleep inference (§6.3) in `:core-model` | no | done, replayed on real nights |
| 2a+ | Sleep in the existing app: schema 2, nights table, personalised evening window, "I slept about X to Y" entry, sleep chart. No foreground service. | 📱 verify | installed on the A18 2026-09-15 |
| 2b | Light sampler service (`specialUse`), Tonight screen, light samples feeding §6.1/§6.2 | 📱 | installed 2026-09-15 07:11; light measurement on since 07:13; phone checks under way |
| 2c | Sleep inference against 14 hand-labelled nights, ≥80% of onsets within 30 min | 📱 | 5 confirmed nights, all exact; 14 Sept labelled from memory and marked uncertain |
| 2.5 / 2.6 | Regularity metrics, schema 4 with `window_metrics` and `model_runs` (NOCTURNE_ANALYTICS.md) | 📱 verify | installed on the A18 2026-09-15 15:26 |
| 2.5c / 2.7 | Screen-use figures checked against sleep inference and nparACT; raw event names interned (schema 5) | 📱 verify | installed on the A18 2026-09-15 16:35 |
| 3a | μEMA reflection cards, focus timer with interruption count | 📱 | ready to start (see Phase 3 readiness) |
| 3b | Hannay19 port + golden-file test against Python `circadian` | laptop, 📱 for a real week of light CSV | reference generator and pinned environment ready; a week of real light by 22 Sept |
| 3c | Personal sensitivity fit, n ≥ 30 nights | 📱 | falling-asleep times collected from 2026-09-15; needs 30 nights with light |
| 4 | ActivityWatch exporter on Ubuntu | laptop + 📱 export | |

## Building

- JDK 17, Android SDK Platform **37.2** (Compose BOM 2026.09, current AndroidX and Vico 3.3 require compiling against API 37; `targetSdk` stays 36), Build Tools 36+.
- AGP 9.4.0 with built-in Kotlin 2.4.20, Gradle 9.6.0 via the wrapper, KSP 2.3.12, Room 2.8.5.
- `./gradlew :core-model:test :tone:test :data:testDebugUnitTest :collector:testDebugUnitTest` runs every test on the laptop.
- `./gradlew :app:assembleDebug` builds the APK without installing it; `./gradlew :app:installDebug` installs on a USB-connected phone.

Developer tools that read personal data. Keep every export and database copy out of the repository.

- Replay a `dumpsys usagestats` dump through the classifier:
  `NOCTURNE_DUMPSYS=dump.txt NOCTURNE_TZ=Asia/Kolkata ./gradlew :core-model:test`, then read `core-model/build/dumpsys-replay.txt`.
- Replay a raw-event CSV (Settings, Export) through sleep inference:
  `NOCTURNE_EVENTS_CSV=export.csv ./gradlew :core-model:test`, then read `core-model/build/sleep-replay.txt`.
  Add `NOCTURNE_SLEEP_LABELS=labels.csv` (lines like `2026-09-06,00:10,08:30`, or `2026-09-14,none,none` for a night without sleep, local time) for the §10 hit rate, plain and leave-one-out.
- Check a schema change against the phone's real database before installing:
  copy `databases/nocturne.db` (plus `-wal`, `-shm`) off the phone with `adb exec-out run-as io.github.anbu00001.nocturne cat …`,
  then `NOCTURNE_LIVE_DB=nocturne.db ./gradlew :data:testDebugUnitTest --tests '*LiveDatabaseTest*'` and read `data/build/live-database.txt`, which also lists the regularity windows for the latest night. It checks that every event survives a schema change unchanged and writes the latest week's screen-use minutes to `data/build/live-activity-minutes.txt`; move both out of the repository after use.
- Hannay19 reference trajectories (Phase 3b): `uv venv && uv pip install -r tools/circadian/requirements.txt`, then
  `python tools/circadian/hannay19_golden.py --light week.csv --out week-hannay19.csv` (or `--synthetic-days 7` before real light exists).
- nparACT reference values (analytics §5): build R and nparACT as in `tools/actigraphy/environment.txt`, then
  `Rscript tools/actigraphy/nparact_golden.R --synthetic core-model/src/test/resources/nparact` regenerates the golden files, or
  `Rscript tools/actigraphy/nparact_golden.R data/build/live-activity-minutes.txt` checks the phone's own week.

## First install on the A18 (2026-09-14)

- First harvest backfilled 17,694 events covering 9.2 days, all with offset +330 (Asia/Kolkata). No row has an empty package.
- Visible to the app: 602 KEYGUARD_HIDDEN, 914 SCREEN_INTERACTIVE, 1,001 NOTIFICATION_INTERRUPTION. Keyguard mode applies on this phone.
- A later run re-read 54 overlapping events and inserted 0 (idempotent on a real device).
- The Settings diagnostic caught ColorOS resuming the last app behind the lock screen, and flagged `com.heytap.pictorial`. Both handled in classifier version 2.

## Phase 2 install on the A18 (2026-09-15)

- Before installing, schema 2 migrated a fresh copy of the phone database in `LiveDatabaseTest`: 18,766 raw events before and after, 933 sessions, every night recomputed. A backup of that copy was kept off the repository.
- On the phone after `adb install -r`: schema 2, 18,769 raw events, all 933 sessions re-derived with classifier 3, nights written, model version `3.1` recorded, no crash.
- Classifier 3 on the real history: 37 alarm wakes (was 18) now that the clock's upcoming-alarm notices count as alarms; 319 counted glances.
- You confirmed the estimates for five nights, Wed 9 to Sun 13 Sept (01:59 to 10:48, 03:09 to 12:23, 03:58 to 13:00, 03:10 to 12:36, 04:09 to 13:00). Replayed against them, onset and wake are within 30 min on 5 of 5 (all exact), plain and leave-one-out. The late wakes are your real times, not a model bias.
- Sleep estimates for the 9 complete nights match the laptop replay exactly. Only 5 reach confidence 0.4, so the evening window stays provisional until 7 nights count. Two confirmed nights sit below 0.4: Wed 9 (0.38, a rival onset at 00:42) and Fri 11 (0.01, a near tie between waking at 13:00 and 14:02). Entering those two as your times makes 7, and the window becomes personal.
- Confidence under-rates those two correct nights, but with no wrong night yet there is nothing to calibrate it against, so it is unchanged.
- The five nights above are the only record of those labels: the laptop's reboot on 15 Sept cleared the local label file, CSV export and database backups.

## Phase 2b build and install (2026-09-15)

A running light service keeps Nocturne's process alive, which would hide exactly the ColorOS kills the 1e soak is testing for. So 2b was installed with light measurement off, and nothing starts the service until you turn it on.

- Light measurement, once on, reads the light sensor only while the screen is on and writes one sample per 30 s window: time-weighted median lux, the brightness setting, dark mode and the warm filter. It is started by opening the app, by each harvester run, at boot and after an app update; a start Android refuses is shown in Settings.
- Nights with light samples get a modelled suppression band from the evening window's start to sleep onset, with how many screen-on minutes a sample covered. Nights from before the service ran stay unmodelled rather than counted as dark.
- Tonight tab: time to the evening window, light at the eyes now against the 10 lux line (the screen alone while room light is not measured), glances so far, suppression so far.
- Schema 3 only adds columns. Its migration ran on a copy of the phone database pulled at 07:09 (19,157 raw events before and after, 960 sessions, every night recomputed), then on the phone: installed 07:11, schema 3, 19,157 raw events, model version `3.2`, no crash, no light samples and no service running. WorkManager rescheduled the harvester under a new job id straight after the update.
- Soak so far: 31 harvest runs by 06:30 on 15 Sept, 30 of them OK, longest gap 5.4 h overnight while the phone was idle. After the restart, 14 of 14 runs OK by 15:00, longest gap 4.3 h. The OS keeps 10 days of events, so gaps that size lose nothing.
- Light measurement went on from the phone at 07:13:44 and you chose to keep it, restarting the soak. First phone checks: the service runs in the foreground (`specialUse`), `dumpsys sensorservice` lists it as a light client at 200 ms, samples arrive every 30 s with about 150 readings each, and after each update it restarted by itself within seconds (MY_PACKAGE_REPLACED). Settings shows the sensor as stk33c01, steps of 1 lux up to 65,535 lux: anything under 1 lux reads 0, so dim evening light can only be bounded, not measured. By 15:00, 180 samples, the brightest 49 lux. "Warm display filter now: off" at 07:21 was right but did not prove the ColorOS keys are read; the night did: every light sample from 23:00 on 15 Sept to 01:00 on 16 Sept records the warm filter on (106 samples), every sample in the 20:00 hour records it off, and none is unreadable.

## No-sleep nights (2026-09-15)

You did not sleep on the night of Sun 14 Sept, then slept in the morning. The app first said "asleep about 21:30 to 00:31": the phone was quiet then because you were at the laptop, then in use until 07:30. That is the false positive the spec warns about (§6.3), and it exposed two gaps:

- **A quiet stretch looks the same to the phone whether you were asleep or awake elsewhere.** The likelihood cancels, so only priors can decide: sleep of that length starting then (your usual onset and duration) against an away stretch of that length at any hour, with a 3% prior that a night holds no sleep at all. Around your own onset and duration the priors are Student-t, so an unusual night is unlikely rather than impossible.
- **The sleep after a night awake starts in the morning,** which the spec's 20:00 to 06:00 onset window cannot place. Onsets up to noon are now weighed on every night, but only with a wake the phone saw: a morning onset "still asleep" at the edge of the history would let a quiet afternoon pass for sleep, which is what broke the first attempt. Ordinary nights are unchanged, because their own sleep is longer and better placed than any morning stretch.
- On the real history 14 Sept now reads 07:30 to 11:51 (confidence 0.53), with the laptop evening as the runner-up, and the five confirmed nights stay exact. You remembered "probably 06:00 to 12:10, not sure". The phone shows unlocked use at 06:05 to 06:19 and 07:08 to 07:30, and quiet from 07:31 until a notification at 11:51, so the label is kept but marked uncertain and does not count toward 2c until you are sure. Scored against it, onset is 90 min late and wake 18 min early.
- In the app, "Correct these times" offers "I did not sleep". A sleepless night has no times, shows as a ring on the Patterns chart, and never feeds priors, the window or corrective offsets.
- The wake step also offers an optional falling-asleep band, the Pittsburgh Sleep Quality Index's item 2 bands (15 min or less, 16 to 30, 31 to 60, over 60), collected now because the Phase 3c sensitivity fit needs 30 nights of it.

## Regularity on the A18 (2026-09-15)

- Schema 4 only adds the `window_metrics` and `model_runs` tables and `nights.modelRunId`. On a phone copy pulled at 15:25: 19,843 raw events before and after, 983 sessions, every night and window recomputed. On the phone after the 15:26 install: schema 4, 19,843 raw events, model version `3.3.1`, one model run (FIRST, with the A18 display profile), 360 window rows, the light service restarted and the harvester rescheduled, no crash.
- For the 7 nights ending Mon 14 Sept: Sleep Regularity Index 79; onset spread 2 h 2 min; social jetlag 55 min; composite phase deviation 1.6 h a night. From screen use: stability (IS) 0.27, fragmentation (IV) 0.81, relative amplitude 0.87, circadian function index 0.58; the quietest 5 hours start around 07:08 and the busiest 10 around 17:28. Over 14 nights (only 11 recorded): SRI 73, onset spread 2 h 13 min, phase deviation 2.7 h.
- Patterns shows these under "Regularity" for the 7- and 28-night windows ending on the latest judged night, and says what a withheld figure is missing.

## Screen use against sleep, and slimmer raw events (2026-09-15)

- **The check (2.5c).** Each window also computes IS and IV on wakefulness from the sleep estimates (1 awake, 0 asleep), and how much of the screen-use L5 the estimates call asleep. For the 7 nights ending Mon 14 Sept: asleep for 80% of the quietest 5 hours (from 07:08); stability 0.27 from screen use against 0.66 from sleep times; fragmentation 0.81 against 0.28. The four 7-night windows so far read 53%, 70%, 74% and 80% asleep, the 14-night window 64% (from 05:46). Screen use is noisier than sleep and wake, as the 2024 JMIR study found for phones against actigraphy, but its quietest hours are mostly sleep.
- **What the check is not.** Sleep inference reads the same screen events, so part of the agreement is built in. The independent ground truth is still the nights you enter.
- **A flag, not a gate.** When less than half of L5 is asleep, Patterns says the screen-use figures describe phone habits more than rest. The 50% line is a Nocturne choice; no study gives one for phones.
- **L5 and M10 ties.** An untouched night gives a run of equally quiet 5-hour windows. nparACT and pyActigraphy take the first, which on a phone can put L5 at the start of an afternoon that is just as quiet; Nocturne takes the middle of the longest run. No real window so far had a tie, so the phone's figures did not move.
- **nparACT golden files.** R 4.5.3 with nparACT 0.9.1 (`tools/actigraphy`). Its IS, IV, L5 and M10 are Van Someren's formulas, as in Nocturne; it rounds to 2 decimals and derives RA from the rounded L5 and M10, its activity cutoff never takes effect (the filter edits a copy), and it refuses missing data, so only gapless weeks compare. Two synthetic weeks are core-model tests. On the phone's own week ending 14 Sept it gives IS 0.27, IV 0.81, L5 0.02 from 07:08 and M10 0.32 from 17:28, the same as Nocturne; its RA of 0.88 against Nocturne's 0.873 is the rounding. pyActigraphy 1.2.2 would not install on Python 3.11 or 3.12.
- **Interning (2.7).** Package and class names moved into `event_components`, one row per pair (238 on the phone), with an integer id on each event. One table of pairs rather than a package table and a class table, since every read needs both. On the 16:09 phone copy raw_events with its index went from 2.56 MB to 0.83 MB, about 128 bytes an event to 42: at about 1,830 events a day, 28 MB a year instead of 85.
- **The migration is by hand.** Room cannot generate a table rebuild. Events keep their ids, so harvest order within a millisecond is unchanged. Schema 4's table is renamed `raw_events_v4` and kept for one release (the file is larger until then); the next schema change drops it, and a delete-all must drop it too. Before finishing, the migration counts old rows with no identical new row and throws if there is one, which rolls the upgrade back to schema 4. Android's SQLite keeps the legacy ALTER TABLE RENAME behaviour, which only matters for triggers and views naming the table, and none do while migrations run. An ignored re-harvest can use up an id (the sequence was at 20,958 with 19,905 rows), so ids are ordered, not dense.
- **Install record.** LiveDatabaseTest on a phone copy pulled at 16:32: schema 4 to 5, all 20,066 events identical, every night and window recomputed. The installed APK and a database copy from 16:35 were kept off the repository first, so a failure would have been one downgrade install away. On the phone after the 16:35 install: schema 5, `quick_check` ok, 20,066 events plus the kept copy of 20,066, no backup row without an identical row, 238 name pairs, model run `3.3.2` (MODEL_CHANGE), 468 window rows, the light service restarted, no crash. The first harvest after it, at 16:42, stored 6 new events through the interned insert, skipped the 9 it already held without duplicating any, and needed no new name pair. By 01:00 on 16 Sept: 18 of 18 harvests since the install OK (longest gap 2.7 h), 21,002 events with 248 name pairs, none without a name and no duplicate keys.

## Phase 3 readiness

What each step needs, and what is already in place.

- **3a μEMA cards and focus timer.** Needs phone-down gaps (derivable from sessions today), the one-tap card, the caps (1 per 3 h, 4 a day, never in the evening window) and interruption counts from raw_events unlocks. The reflections and focus_blocks tables have existed since schema 1. Design risk: the spec forbids notifications, so a card can only appear when you open Nocturne; response rates depend on how often that is, and dismissals are recorded as signal.
- **3b Hannay19 port.** `tools/circadian/hannay19_golden.py` runs Arcascope `circadian` 1.0.3 (pinned in `requirements.txt`) and writes every state at full precision; a synthetic week gives identical output on re-runs. What the port must copy exactly: fixed-step RK4 with light held over each step, the step from t[i-1] to t[i] using the light at t[i], photopic lux with alpha = 0.05·I^1.5/(I^1.5 + 9325), the default initial condition for 16L:8D at midnight, and DLMO = CBTmin − 7 h. Risks to resolve in 3b: the phone measures light only while the screen is on, so daylight is missing from a phone-only series (sleep can stand in as darkness, awake hours need a stated prior); the model takes photopic lux while §6.1 produces melanopic EDI, so the conversion is an explicit assumption; published accuracy is about ±1 h against lab DLMO.
- **3c personal sensitivity fit.** Needs at least 30 nights with both modelled suppression (light on since 15 Sept) and your falling-asleep band (collected from 15 Sept). Earliest around mid-October if you answer most mornings.

## Analytics layer (NOCTURNE_ANALYTICS.md): how it fits

Where each part of the analytics spec lands among the phases above, and where the build departs from it.

| Step | What | Needs | When |
|---|---|---|---|
| 2.5a | `SufficiencyGate` and the Sleep Regularity Index in `:core-model`, with tests | nothing | done 2026-09-15 |
| 2.5b | IS, IV, RA, L5/M10, CFI, CPD, social jetlag, onset SD over rolling 7/14/28-night windows; a Patterns "Regularity" section that says what is missing | 2.5a | done and installed 2026-09-15 |
| 2.6 | Schema 4: a derived `window_metrics` table and `model_runs`, nights and windows carrying the run that wrote them | 2.5b | done and installed 2026-09-15 |
| 2.5c | Screen-use figures checked against sleep inference in every window; nparACT golden files | 2.5b | done and installed 2026-09-15 |
| 2.7 | Package and class names interned into `event_components` (schema 5): an event with its index from about 128 bytes to 42, nothing lost | a manual migration tested on a phone copy, with the old table kept one release | done and installed 2026-09-15; the next schema change drops the old table |
| 3.5 | Bayesian online changepoint sentinel on nightly onset and 7-day SRI | 60 nights, about 3 Nov 2026 | after 3a and 3b |
| 5 | Tier 3: PELT eras, elastic net, a mixture model of night types, refits, on a charging and idle worker | 180 nights, about March 2027 | last |

- **Every raw event stays.** The analytics spec's storage arithmetic matches this phone: about 1,830 events a day, and the whole database with derived tables was 2.9 MB after 11 days. Interning (2.7) takes a year of raw events from about 85 MB to 28 MB.
- **Tier 0 already exists.** NightRecomputer writes each night's figures in the same transaction as sessions.
- **Tier 1 runs inside that recompute, not as a weekly worker.** It costs milliseconds, keeps "incremental equals full" testable (WindowMetricsTest), and avoids a second background job for ColorOS to kill. Windows are keyed by the night they end on, so a recompute from a night rewrites exactly the windows that can include it.
- **The scorer's input is all primary data, not events alone.** Entered sleep times, charging samples and light samples change results as much as events do. `RegularityWindows.compute` is the pure function; the database layer only loads and stores.
- **SRI choices are pinned in code** because packages disagree enough to change study conclusions (RIRI statement, SLEEP 2026): 1-minute epochs, noon-to-noon days, a day counts at 80% known, unknown epochs left out pairwise, the compared share must reach 80%, at least 7 counted days in consecutive pairs, reported from −100 to 100. GGIR differs (30 s epochs, day pairs dropped below 66% valid). A day is known only when its night has a verdict, so a night still under way never counts as awake.
- **Naps are absent from Nocturne's SRI.** Sleep inference finds one main sleep a night, so this is main-sleep regularity. Sleepless nights count as awake, which is what they were.
- **IS, IV, RA, L5/M10 and CFI come from screen use and are labelled so.** The activity series is the share of each minute the screen was on. A 2024 JMIR study found phone-derived IS and IV significantly lower than actigraphy's and dropped relative amplitude as uninformative, so none of these is compared with actigraphy norms. IS and IV use Van Someren's hourly means (an hour counts with 30 known minutes; IV compares only neighbouring known hours, because IV moves with epoch length); L5 and M10 use the minute-by-minute average day with windows that wrap, equal means to the middle of the longest run of them; CFI averages IS, (2 − IV)/2 and RA (Ortiz-Tudela 2010). Every window checks them against sleep inference, and nparACT checks the formulas (see "Screen use against sleep").
- **Composite phase deviation derives the chronotype from the window itself,** as the MCTQ defines MSFsc (free-night midsleep, corrected for sleeping in when free-night sleep is longer), because Nocturne has no questionnaire. The MCTQ withholds MSFsc when an alarm is used on free days; the phone cannot tell. A night counts when the night before has times, needing 6 such pairs.
- **Social jetlag is |MSF − MSW|** (Wittmann 2006), with Friday and Saturday nights as free nights by default, a setting rather than a constant since a student's week is not an office week.
- **Onset spread is the sample standard deviation** of onset in minutes after noon, so late-night and early-morning onsets sit on one unbroken scale.
- **A model run is reused while nothing changes.** Its key is the classifier, sleep and metrics versions plus the sleep, display, light and free-night configuration as their data classes print. The classifier's package lists are left out (a new launcher is not a new model), and the configuration is stored as readable text rather than parsed JSON. Sessions do not carry a run id: they depend only on the classifier version, which the run records.
- **The sensitivity fit stays in Phase 3c at 30 nights,** as the main spec says, and Tier 3 re-runs it later. The analytics spec bundles it into Phase 5, which would wait for 180 nights with no statistical reason.
- **Cold-tier compression waits until year three,** as the analytics spec itself says.

## Phone checks for 1d and 1e

1. Install and finish onboarding: usage access, battery exemption, ColorOS auto launch and background activity.
2. Settings shows a first harvest with about 10 days of backfilled events. Last night and Patterns show real nights.
3. Force-stop the app, wait over an hour, reopen: Settings shows the gap was backfilled and no night lost sessions.
4. Reboot without opening the app: harvest runs resume (last run time keeps advancing).
5. Check "Apps seen on locked wakes" in Settings. Every package there should be something that can appear over the lock screen (clock, dialer, camera). If not, the package lists in `ClassifierConfig` need tuning.
6. Soak (1e): for 7 days, "days since last successful harvest" never goes above 0, and no night has a hole.

## Phone checks for 2a+ and 2c

1. Last night shows "Asleep about … to …" with a confidence, and a sleep line under the timeline.
2. For nights you remember, tap "Correct these times" and enter when you fell asleep and woke, or "I did not sleep". After three nights with times the others shift by your typical gap (corrective offsets).
3. Patterns shows one sleep bar per night; entered nights are solid, estimates lighter, sleepless nights a ring.
4. 2c passes when at least 80% of 14 labelled nights have an estimated onset within 30 min of your times, scored leave-one-out.

## Phone checks for 2b

1. Settings, Light: "Warm display filter now" reads on between 22:00 and 07:00 and off by day (not "not readable"). By day, `adb shell settings get system oplus_customize_eye_protect_enable` should still say 1: the flag means "scheduled", as the code assumes.
2. Settings shows the sampler running and, within a minute of the screen coming on, a last sample time.
3. `adb shell dumpsys sensorservice` lists Nocturne as a light sensor client while the screen is on, and not after screen-off.
4. Cover the sensor with a finger: the next sample's lux drops. In a dark room at minimum brightness expect about 0 lux (the stk33c01 read 0.00 at 02:44 with the screen at setting 13).
5. Overnight with the screen off, the service survives until morning, or Settings shows when a start was refused. Last night's coverage line shows how many screen-on minutes were measured.
6. Battery over 48 h: Nocturne stays within the spec's 2% target (Settings, Battery usage).
7. With notification permission denied, the service still runs and appears under active apps.

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
- **Onsets reach past 06:00 to noon when the wake is observed** (sleep model 3), so the sleep after a night awake can be placed. See "No-sleep nights".
- **No estimate for nights the history does not cover**, from before the first harvest or still under way (before 06:00 the next morning). Every estimate keeps its runner-up explanation.
- **A night can have no sleep.** The spec assumes every night holds one; see "No-sleep nights" above. A night's confidence is scaled by the chance it held sleep at all.
- **Charging is sampled at each harvest**, not by an `ACTION_POWER_CONNECTED` receiver, which cannot be declared in the manifest for modern targets. A charging phone is out of Doze, so samples come every 15 min.
- **Your entered times teach a corrective offset** (Abdullah et al. 2016): after 3 entered nights, the median gap between the estimate and your times shifts the other nights, clamped to 90 min. Validation scores it leave-one-out.
- **The evening window is fixed per week**: from the 4 weeks before that week's Monday, needing 7 usable nights; until 4 weeks exist, the earliest nights stand in.
- **Classifier 3.** The clock's "upcoming alarm" notification lights the screen for 10 s, 15 min before each alarm; it is now an alarm wake, not a glance. ColorOS usually logs a waking notification after `SCREEN_INTERACTIVE` (134 of 232 notification wakes within 0 to 1 s after), so that counts too.
- **Schema 2 is additive**, so Room's AutoMigration covers it, verified against schema 1's exported JSON and a copy of the phone's database. `sleep_reports` and `power_samples` are primary data and survive every recompute.
- **Falling-asleep time is collected before Phase 3 needs it**, as PSQI item 2 bands on the wake step, optional and one tap.

Phase 2b:

- **Off by default.** §4.2 calls the light service optional, so nothing starts until you turn it on.
- **Room light with the screen off is an assumption, not a reading.** §4.2 samples only while the screen is on. The low end assumes dark at once, the middle keeps the last reading for 30 min, the high end keeps it (or the evening prior) until sleep onset. Each bound of the suppression band now picks its own exposed minutes, so light only the high end assumes still raises the high bound; before, a minute counted only if the middle estimate cleared 1 lux.
- **Tonight shows the screen alone when the room is not measured.** Adding the room prior gave a headline of about 14 lux that was the prior restated as a measurement.
- **Samples are time-weighted medians.** `TYPE_LIGHT` is on-change: a steady room may send nothing, so the value in force carries into each window, and a burst of events cannot outvote the rest. (On the A18 it reports every 200 ms while auto brightness listens.)
- **The foreground app is not stored with each sample.** Sessions already know it exactly from raw_events.
- **The raw brightness setting is stored**, not only the 0 to 1 value, so a corrected display profile can re-read history.
- **The warm filter is read from ColorOS's own settings** (`oplus_customize_eye_protect_enable` with its 22:00 to 07:00 schedule). Keys Android itself does not define stay readable to apps (AOSP SettingsProvider), AOSP Night Light is tried as a fallback, and an unreadable value widens the band instead of being guessed.
- **Service starts follow Android's background rules.** Android 15 still lets `specialUse` start from BOOT_COMPLETED (only camera, dataSync, mediaPlayback, mediaProjection, microphone and phoneCall are barred); the harvester's runs rely on the battery-optimisation exemption; a refused start is recorded, never thrown.
- **Notification permission is optional.** Since Android 13 a foreground service runs without it and is listed under active apps instead of in the notification shade.
- **Schema 3 was amended before its first install** with the no-sleep and falling-asleep columns, so the phone went from 2 to 3 once.

Analytics: see "Analytics layer" above.

## Device facts that matter later

- Hardware light sensor `stk33c01` (TYPE_LIGHT, on-change), so §6.1 ambient light is available. While auto brightness listens it reports every 200 ms; at 02:44 with the screen at minimum brightness it read 0.00 lux.
- Brightness setting runs 0 to 4095 (the slider stops at 3276 without high brightness mode), linear onto 2 to 490 nits. Auto brightness is on and ColorOS writes its applied value to the setting (13 at 02:44, 140 at night, 566 by day).
- Dark mode is on and ColorOS eye comfort runs at 2700 K from 22:00 to 07:00. At the night setting the screen's modelled mEDI stays under 1 lux even at the high end of the band, so for this phone room light, not the screen, is likely the larger evening contributor. That makes the 2b light sampler worth more than the screen model.
- Screen-off timeout is 30 minutes (`last_manual_screen_off_timeout` says 1 minute was once set). Only the current value is readable, and it is applied to all history.
- Double-tap to wake is on, which likely explains many 1 s lock-screen wakes with no notification (178 in 10 days).
- On a fingerprint wake, ColorOS logs the last app's `ACTIVITY_RESUMED` before `KEYGUARD_HIDDEN`.
- Nocturne is exempt from battery optimisation (standby bucket 5, on the device idle whitelist) and does not hold notification permission.
- Reinstalling Nocturne gives its WorkManager harvest a new JobScheduler id; `adb shell cmd jobscheduler run -f` needs the id read again.
