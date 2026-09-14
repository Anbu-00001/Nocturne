# Nocturne — Build Specification

**Target agent:** Claude Code
**Platform:** Android (personal sideload, no Play Store)
**Language:** Kotlin + Jetpack Compose. No Flutter. No Rust/JNI. (Rationale in §3.)
**Dev machine:** Ubuntu laptop, Android Studio + adb.
**Author's intent:** a personal instrument that shows, honestly and over years, how night-time phone use is wrecking a sleep schedule — and models the circadian cost of it.

---

## 0. Read this first (agent orientation)

This is not a screen-time app. Dozens of those exist. The three things that make this project worth building are:

1. **Indefinite retention.** Android's own `UsageStatsManager` keeps detailed events for roughly 7 days, weekly buckets for ~a month, and monthly rollups for ~6 months. Digital Wellbeing shows you *yesterday*. Nothing on the market lets you ask "what did my October 2026 look like compared to March 2027?" This app harvests the OS's 7-day window into an append-only local database and keeps it forever. That alone is the feature.
2. **Glance-level resolution.** Every unlock, including the ones where the user *only checked the time and put the phone down*, is a separate, classified, timestamped record. No mainstream tracker surfaces this, because a 4-second unlock contributes ~0 to "screen time" and is therefore invisible in every aggregate.
3. **A real circadian model.** Not a "blue light bad" icon. An implementation of published dose–response and phase models that outputs an estimated melatonin suppression percentage and an estimated circadian phase shift, with honest error bars.

Build in the order given in §9. Do not start on the diary or the Ubuntu sync until Phase 1 and 2 are running on a real device for at least a week.

---

## 1. Verdict on the idea

**Build it.** But three parts of the original vision need to change, and the agent should implement the corrected versions, not the originals.

### 1.1 What already exists (don't rebuild these)

| Project | What it does | Why it's not this |
|---|---|---|
| `ActivityWatch/activitywatch` + `aw-android` | Cross-platform automated time tracker, Kotlin Android client, Rust server, local-first, syncs via a folder + Syncthing | General-purpose bucket store. No night semantics, no circadian model, no glance classification. **Use it for Phase 4 instead of building your own laptop tracker.** |
| Google Digital Wellbeing | Per-app time, unlock counts, Bedtime mode | Short retention, no export, no model, no introspection loop |
| `kumarpiyushraj/MinST`, `mhdalitarhini/DigitalBalance`, and ~40 similar GitHub repos | `UsageStatsManager` → Room → charts, app limits, break reminders | Exactly the commodity layer. Read one for API reference, then move on. Their retention is 30 days and their analysis is "you used Instagram 3h." |
| `trextrinorex/phone-sleep-tracker` | Infers sleep duration from passive phone activity, no wearable | **Closest prior art to Phase 2's sleep inference.** Read it before writing the sleep estimator. It does not model light or melatonin. |
| `LibreShift/red-moon` | Blue-light screen filter | Intervention, not measurement. Complementary — it changes the number this app measures. |
| `Arcascope/circadian` (Python) | Implements Forger99, Jewett99, Hannay19 circadian models + phase response curves | **Do not reimplement blindly — port from this, and use it as the test oracle.** See §6.4. |

**Conclusion: the combination does not exist.** No open-source project joins (a) indefinite glance-resolution night logging, (b) a published melanopic dose model, and (c) low-burden self-report. Each ingredient exists separately. That is a genuine gap, and it's a gap precisely because it's only worth the effort for someone building for themselves.

### 1.2 Three corrections to the original vision

**Correction 1 — "judges you" must become "shows you, without flinching, in a voice that doesn't make you quit."**

This is not squeamishness; it's the difference between an app used for two years and one deleted in nine days. The evidence:

- A survey of 230 undergraduates on iOS Screen Time found it *did* change usage behaviour but *also* triggered negative emotions, most sharply in heavy users who were already struggling with mental health. The paper is literally titled *"Digital Overload Warnings – 'The Right Amount of Shame'?"*
- Bedtime procrastination research consistently finds that harsh self-criticism is part of the maintaining loop, not the cure. A multimodal intervention study found self-compassion reduced bedtime procrastination by improving emotion regulation, specifically by defusing setback-related shame rather than amplifying it.
- Users habituate to aversive reminders and then reject the app as intrusive and ineffective (the SPACE app finding).

**Design rule: the data is merciless, the copy is not.** Never write "you wasted 3 hours." Write "3h 12m after 23:00, mostly Instagram. Your 4-week median is 1h 40m." Attack the behaviour with numbers; never attack the person with adjectives. There must be zero exclamation marks and zero emoji in any generated insight string. A separate `Tone.kt` file holds every user-facing sentence template so it can be audited in one place.

**Correction 2 — the cutoff is not 8pm or 9pm. It is 3 hours before the user's own sleep onset, computed from their own data.**

The author flagged uncertainty here, and the uncertainty is correct — a fixed clock time is wrong. The expert consensus (Brown et al., PLOS Biology 2022, an 18-author panel including Czeisler, Lockley, Cajochen, Roenneberg) defines the evening window as **starting at least 3 hours before bedtime**, with a target of ≤10 lux melanopic EDI in that window and ≤1 lux during sleep. Daytime target is ≥250 lux melanopic EDI at eye level.

So: estimate habitual sleep onset from ≥7 days of data (§6.3), set `eveningWindowStart = sleepOnset − 3h`, and recompute it weekly. A user who reliably sleeps at 02:00 has an evening window starting at 23:00, and judging them at 21:00 is simply a false positive. Expose the fixed-time override in settings, defaulted off.

**Correction 3 — the app cannot measure melatonin, and must never imply it can.**

It models *light-induced melatonin suppression* from an *estimated* light dose. That estimate has real uncertainty stacked in it (screen spectrum assumed, viewing distance assumed, ambient sensor uncalibrated, individual sensitivity unknown and known to vary enormously). Every melatonin number in the UI carries the label "modelled" and a range, never a bare point estimate. §6.1 specifies this honestly. Interindividual variability in evening-light sensitivity is the single largest error term — Phillips et al. (PNAS 2019) found group ED50 of ~24.6 lux but individual-level curves spanning a range far wider than the group curve suggests. This is not a detail to paper over; it's the reason §6.2 includes a personal calibration path.

---

## 2. What is technically possible (verified)

| Requirement | Feasible? | Mechanism |
|---|---|---|
| Per-app foreground time with timestamps | Yes | `UsageStatsManager.queryEvents()` → `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED` |
| Every screen wake, including no-unlock | Yes | `SCREEN_INTERACTIVE` / `SCREEN_NON_INTERACTIVE` (API 28+) |
| Distinguish "unlocked" from "just lit up" | Yes | `KEYGUARD_HIDDEN` / `KEYGUARD_SHOWN` (API 28+) |
| "Opened phone only to check the time" | Yes | Derived — see §6.5. This is the flagship metric. |
| Years of history | Yes, **only if the app persists it itself** | OS retention is ~7 days detailed. Harvest every ≤6h; never rely on the OS as storage. |
| Ambient light | Partially | `Sensor.TYPE_LIGHT`. Uncalibrated, vendor-dependent, wide FOV, sits beside the front camera. It measures light *falling on the phone's face*, i.e. room light — **not** the screen's own emission. Both matter and must be modelled separately. |
| Screen brightness | Yes | `Settings.System.SCREEN_BRIGHTNESS`, plus `Settings.Secure` night-mode flags where vendor exposes them |
| Reliable background operation | Yes, with the right architecture | See §4. An always-on `dataSync` foreground service is **not** viable on Android 15+ — it is capped at 6 hours per 24. |

The "check the time" feature — the one the author was least sure about — is the most solidly supported of the lot. `SCREEN_INTERACTIVE` fires without unlock, `KEYGUARD_HIDDEN` fires only on unlock, and both carry millisecond timestamps. The distinction is clean.

---

## 3. Stack decision, and why not Flutter/Rust

The author asked for Kotlin + Flutter, with Rust or C++ for the maths. Implement **pure Kotlin instead**, for these reasons:

**No Flutter.** Every hard part of this app is platform-native: `UsageStatsManager`, `SensorManager`, `WorkManager`, foreground service types, the Usage Access permission flow. Flutter would wrap 100% of the data path in method channels and buy nothing — there is no second platform to share code with, because iOS structurally cannot do this (its Screen Time equivalent, `FamilyControls`/`DeviceActivity`, is gated behind an Apple entitlement for parental-control vendors and cannot read other apps' usage). Use **Jetpack Compose**. It is the shortest path from zero to a chart on screen, and it keeps the whole app in one language.

**No Rust/C++/JNI.** The instinct — reach for a fast language for numerics — is sound in general and wrong at this scale. The heaviest computation is integrating a 3-state ODE over a day of light data at 1-minute resolution: 1,440 RK4 steps, three state variables. That is sub-millisecond work in Kotlin on a phone. Adding the NDK means cross-compilation, ABI splits, and a debugging boundary, in exchange for nothing measurable.

**But keep the author's underlying instinct, redirected.** Write the maths as `:core-model`, a **pure Kotlin JVM module with zero Android dependencies**. Consequences:
- It runs in fast JVM unit tests with no emulator.
- It can be driven from a plain `main()` on the Ubuntu laptop over exported CSV.
- It can be **cross-validated against `Arcascope/circadian`** in Python — same light input, compare state trajectories. That Python package is the test oracle (§6.4), which is exactly the right job for a second language.

### Module layout

```
:app              Compose UI, navigation, DI wiring
:data             Room entities/DAOs, repositories, export
:collector        UsageStatsManager harvesting, sensors, WorkManager, FGS
:core-model       PURE KOTLIN, NO ANDROID IMPORTS. Light, melatonin, circadian,
                  sleep inference, glance classification. 100% unit-tested.
:tone             All user-facing strings. Audited as a unit. Zero logic.
```

The `:core-model` / Android boundary is the most important architectural constraint in this document. If `:core-model` ever imports `android.*`, the port is broken and validation becomes impossible. Add a Gradle check or a lint rule that fails the build on any `android.` import in that module.

---

## 4. Collection architecture

**The critical insight: you do not need an always-running service to capture usage.** The OS is already logging these events. The app's job is to *harvest* them before the ~7-day window rolls off. This changes everything about robustness and battery.

### 4.1 Harvester (the backbone)

A `PeriodicWorkRequest` via WorkManager, every **15 minutes** (WorkManager's floor), doing:

1. Read `lastHarvestedTimestamp` from DataStore.
2. `usageStatsManager.queryEvents(lastHarvestedTimestamp - 60_000, System.currentTimeMillis())` — overlap 60s to tolerate clock jitter.
3. Insert into `raw_events` with `INSERT OR IGNORE` on a natural key of `(timestamp, packageName, eventType)`. Idempotent by construction — re-running the harvester must never duplicate or corrupt.
4. Advance the cursor. Run the session/glance derivation (§6.5) over the newly closed window.

Because the OS retains ~7 days, **even a harvester that fails for four straight days loses nothing.** This is why the design is robust and why it must be the backbone rather than a live listener. Add a "days since last successful harvest" health indicator to the debug screen; if it ever exceeds 5, warn loudly.

### 4.2 Light sampler (Phase 2, optional service)

Ambient light can only be read with a live sensor listener, which needs a live process. Use a foreground service with `android:foregroundServiceType="specialUse"` — **not `dataSync`**, which Android 15+ caps at 6 hours per 24 and terminates via `Service.onTimeout()`. Declare the `<property>` element for `specialUse`; Play Console reviews it, but for a sideloaded personal build this is a non-issue.

The service:
- Registers a runtime `BroadcastReceiver` for `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` / `ACTION_USER_PRESENT` (these cannot be declared in the manifest; they require runtime registration and therefore a living process).
- On screen-on, registers `TYPE_LIGHT` at `SENSOR_DELAY_NORMAL`, writes a median lux value every 30s, unregisters on screen-off.
- Battery cost is near-zero because it only samples while the screen is on — the screen already dominates draw at that moment.

If this service is killed, **usage data is still safe** (the harvester covers it); only ambient lux is lost for that interval. The model must handle missing lux by falling back to the ambient prior (§6.1). Design for the service dying. It will.

Prompt for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` once, with an honest explanation, and note that Xiaomi/Oppo/Vivo/Samsung all have additional vendor-level killers (`dontkillmyapp.com` has the per-vendor steps — surface a link in onboarding).

### 4.3 Permissions

| Permission | Type | Notes |
|---|---|---|
| `PACKAGE_USAGE_STATS` | Special, Settings-only | Cannot be requested via dialog. Deep-link with `Settings.ACTION_USAGE_ACCESS_SETTINGS` and poll for grant on resume. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Normal | Phase 2 |
| `POST_NOTIFICATIONS` | Runtime, API 33+ | Required even for the FGS notification |
| `RECEIVE_BOOT_COMPLETED` | Normal | Re-enqueue work on boot. Note: apps targeting Android 15+ may **not** launch a `dataSync` FGS from `BOOT_COMPLETED`. Re-enqueue WorkManager instead; start the light service lazily on first screen-on. |
| `QUERY_ALL_PACKAGES` | — | **Avoid.** Use `<queries>` with an intent filter for launchable apps. Enough for labels/icons, and avoids the most Play-hostile permission in Android. |

---

## 5. Data model (Room)

Schema principle: **`raw_events` is append-only and sacred.** Everything else is derived and must be fully rebuildable from it by a single `recomputeAll()` call. When the model improves in month 8, the entire history is re-scored — which is only possible if nothing is ever destructively aggregated. This is the difference between a toy and an instrument.

```kotlin
@Entity(
  tableName = "raw_events",
  indices = [Index(value = ["timestamp","packageName","eventType"], unique = true),
             Index(value = ["timestamp"])]
)
data class RawEvent(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val timestamp: Long,          // epoch millis, UTC
  val utcOffsetMinutes: Int,    // capture offset AT event time — DST/travel correctness
  val eventType: Int,           // UsageEvents.Event constants
  val packageName: String?,     // null for SCREEN_*/KEYGUARD_*
  val className: String?
)

@Entity(tableName = "light_samples")
data class LightSample(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val timestamp: Long,
  val ambientLux: Float?,       // null when sensor unavailable
  val screenBrightness: Float?, // 0..1 normalised
  val screenOn: Boolean,
  val foregroundPackage: String?
)

// ---- derived, rebuildable ----

@Entity(tableName = "sessions")
data class Session(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val startTs: Long,
  val endTs: Long,
  val kind: SessionKind,        // GLANCE_NO_UNLOCK, GLANCE_UNLOCKED, SHORT, EXTENDED
  val unlocked: Boolean,
  val dominantPackage: String?,
  val appCount: Int,
  val inEveningWindow: Boolean, // personalised, not clock-based
  val sleepOnsetOffsetMin: Int? // minutes relative to estimated sleep onset; negative = before
)

@Entity(tableName = "nights")
data class Night(
  @PrimaryKey val dateOfNight: String,   // "2026-09-14" = night STARTING that evening
  val estimatedSleepOnset: Long?,
  val estimatedWakeTime: Long?,
  val confidence: Float,                 // 0..1
  val source: SleepSource,               // INFERRED, USER_REPORTED, HEALTH_CONNECT
  val eveningScreenMinutes: Int,
  val postOnsetInterruptions: Int,
  val modelledSuppressionPct: Float?,    // point estimate
  val suppressionLowPct: Float?,         // interval
  val suppressionHighPct: Float?,
  val modelledPhaseShiftMin: Float?,     // negative = delay
  val melanopicDoseLuxHours: Float?
)

@Entity(tableName = "reflections")
data class Reflection(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val promptedAt: Long,
  val answeredAt: Long?,
  val gapStartTs: Long?,
  val gapEndTs: Long?,
  val rating: Int?,             // single-tap, 1..4. NEVER required.
  val note: String?,            // optional, always skippable
  val dismissed: Boolean
)

@Entity(tableName = "focus_blocks")
data class FocusBlock(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val startTs: Long, val endTs: Long,
  val plannedMinutes: Int,
  val completed: Boolean,
  val interruptionCount: Int,   // unlocks DURING the block — the honest metric
  val label: String?
)
```

Storage sanity check: a heavy user generates roughly 1,500–3,000 raw events/day. At ~60 bytes/row that is ~50 MB/year uncompressed. Ten years fits comfortably. The author's "months or years" requirement is not a stretch goal; it's free.

Use `utcOffsetMinutes` captured at event time, not at read time. A year of data crossing DST boundaries or a trip abroad will otherwise silently corrupt every "after 11pm" query. Store UTC, render local, always.

---

## 6. The models (`:core-model`)

### 6.1 Light dose estimation

Two independent contributions to corneal melanopic EDI. Model them separately; they behave differently.

**Screen contribution.** The ambient light sensor faces the user and therefore does **not** see the screen's own output. It must be modelled:

```
mEDI_screen ≈ L_max · b_norm^γ · (A_screen / d²) · k_geom · MDER_display
```

- `b_norm` = normalised brightness from `Settings.System.SCREEN_BRIGHTNESS`; `γ ≈ 2.2` for the perceptual-to-linear curve
- `L_max` = panel peak luminance (cd/m²), a per-device constant in a config file — default 500, override after calibration
- `d` = viewing distance. Measured mean smartphone viewing distance is ~30–37 cm; default **0.35 m**, configurable
- `k_geom` = solid-angle factor for a phone-sized emitter at that distance
- `MDER_display` = melanopic daylight efficacy ratio: **~0.75–0.9** for a typical white-point OLED/LCD, dropping to **~0.4–0.5** with night mode / warm filter engaged

**Ambient contribution.** `ambientLux × MDER_ambient`. Default `MDER_ambient` by hour: ~0.9 daytime (daylight-ish), ~0.45 evening indoor (warm LED, 2700–3000 K). Switching a room from 6500 K to 3000 K roughly halves melanopic EDI — significant enough to be worth modelling.

Total: `mEDI_total = mEDI_screen + mEDI_ambient`.

**Emit uncertainty, not a point value.** Propagate a distance range (0.25–0.45 m) and an MDER range through to a low/high band. The UI shows the band. This is not hedging — it is the difference between a model and a horoscope.

**Reference anchors to encode as constants and show in the UI:**
| Context | Target mEDI |
|---|---|
| Daytime, eye level | **≥250 lux** |
| Evening, from 3h before bed | **≤10 lux** |
| Sleep environment | **≤1 lux** |

A phone at moderate brightness in a dark room lands well above 10. That single comparison is the app's most useful screen.

### 6.2 Melatonin suppression — the Giménez duration-dependent logistic

Use the four-parameter logistic, which every study in this literature converges on:

```
suppression% = 100 / (1 + (ED50 / mEDI)^k)
```

The key refinement — and the reason to use Giménez et al. (J Pineal Res, 2022) over the classic Zeitzer curve — is that **ED50 is a strong function of exposure duration**. That paper's machine-learning analysis of 29 published datasets yields ED50 in melanopic EDI:

| Exposure duration | ED50 (lux mEDI) |
|---|---|
| 0.5 h | 600 |
| 1 h | 350 |
| 2 h | 120 |
| 3 h | 43 |
| 4 h | 15 |

Roughly a **tenfold** swing between 30 minutes and 4 hours. This is exactly the effect the app exists to surface: *duration is doing more work than brightness.* A dim phone for three hours is worse than a bright one for twenty minutes, and no existing app tells anyone that.

Implementation: log-interpolate ED50 across duration, integrate over the actual exposure profile rather than assuming a single constant block (real use is bursty — that's what `sessions` is for).

Cross-check constants to keep in tests:
- Zeitzer et al. (J Physiol, 2000): half-max ~106 lux photopic, saturation ~200 lux, minimal suppression below ~80 lux — for a 6.5 h exposure.
- Phillips et al. (PNAS, 2019): overall ED50 **24.6 lux** (95% CI 21.3–28.4), and by hour: 13.5 / 19.4 / 38.9 lux for hours 1/2/3. Note this runs *opposite* in direction to a naive reading of Giménez because it's computed on cumulative post-DLMO AUC — reconcile carefully in the code comments, and do not average the two blindly.

**Personal calibration hook (Phase 3).** Interindividual variability here is enormous — larger than most of the effects being measured. Provide a settings slider for `personalSensitivityMultiplier` applied to ED50, defaulted 1.0, and — once ≥30 nights exist — fit it by regressing modelled suppression against the user's own reported sleep-onset latency. Label the result "fitted to your data, n=NN nights." That fit is the single highest-value thing in this app, and it only becomes possible because of the retention decision in §0.

### 6.3 Sleep inference

Before any user input, estimate sleep onset/wake from behaviour:

- **Onset candidate:** last `SCREEN_NON_INTERACTIVE` followed by a gap > 4h with no `SCREEN_INTERACTIVE`, within a plausible window (20:00–06:00).
- **Wake candidate:** first `SCREEN_INTERACTIVE` after that gap.
- **Corroborating signals:** charging state (`ACTION_POWER_CONNECTED`), gap duration, consistency with the trailing 7-day distribution.
- **Confidence:** low when gaps are ambiguous (long phone-free evenings produce false positives), high when the gap is long, charging-aligned, and matches personal history.
- Read `phone-sleep-tracker` on GitHub before writing this — same problem, already field-tested, and its stated limitations (phone-free periods creating false candidates; first-interaction-as-wake being only a proxy) are the exact traps to avoid.

Offer, but do not require, a manual "I slept ~X to ~Y" entry, and Health Connect ingestion if a wearable is present. Once ≥7 days exist, compute `habitualSleepOnset` as the trimmed median, then `eveningWindowStart = habitualSleepOnset − 3h`, recomputed weekly. **This is the answer to "8pm or 9pm?" — neither; it's derived.**

### 6.4 Circadian phase (Phase 3, do not attempt before Phase 2 ships)

Port **Hannay19** (single-population) from `Arcascope/circadian` — it's physiologically interpretable, and its predictions diverge meaningfully from the older van der Pol models precisely in the sub-100-lux regime where evening phone use lives, which is the regime this app cares about. Keep `Forger99` as a comparison implementation.

Input: a 1-minute light time series from §6.1. Output: circadian phase, amplitude, predicted DLMO.

Expect ~1 hour mean error against laboratory DLMO for healthy populations — that's the published state of the art, and the UI copy must reflect it ("±1h" not a precise clock time).

**Validation protocol (this is why `:core-model` has no Android imports):**
1. Export a real week of light data to CSV.
2. On the Ubuntu laptop: `pip install circadian`, run the same input through `Hannay19`.
3. Assert the Kotlin trajectory matches within 1e-4 per state variable.
4. Commit that CSV + expected output as a **golden-file regression test**.

Do not ship §6.4 until that test passes. An unvalidated ODE port produces numbers that look authoritative and are wrong, which is worse than shipping nothing.

### 6.5 Glance classification — the flagship

The author's "even opening the phone to check the time should be noted." Precisely:

```
GLANCE_NO_UNLOCK   SCREEN_INTERACTIVE → SCREEN_NON_INTERACTIVE,
                   no KEYGUARD_HIDDEN, duration < 15s
                   → "you looked at the lock screen and put it down"

GLANCE_UNLOCKED    SCREEN_INTERACTIVE → KEYGUARD_HIDDEN → SCREEN_NON_INTERACTIVE,
                   duration < 30s, foreground apps ⊆ {launcher, systemui, clock}
                   → "you unlocked it, saw nothing, locked it"

SHORT              unlocked, 30s–3min, ≥1 real app

EXTENDED           unlocked, > 3min
```

Track all four separately. The insight that makes this app worth its own existence: **a night with 40 glances and 25 minutes of screen time is a worse night than one with 3 sessions and 50 minutes** — it signals fragmented, anxious, wakeful sleep. Total screen time hides this completely; glance count exposes it. Make "glances after sleep onset" a first-class headline metric on the night view, not a buried statistic.

Thresholds go in a config object, not inline literals — they will need tuning against reality in the first fortnight.

---

## 7. Reflection / diary — designed against the irritation constraint

The author's hard requirement: *they must not feel irritated writing these.* There is a directly applicable research literature, and it gives an unambiguous answer.

**Microinteraction EMA (μEMA)** — single-question prompts answerable with one tap — produces **higher response rates and lower perceived burden than conventional multi-question EMA, despite roughly 8× more frequent interruption.** The mechanism is that each interaction is a 3–4 second glance-and-tap requiring no cognitive assembly. Follow-up work confirmed the effect comes from the microinteraction format itself, not from the device. Conversely, longer surveys increase perceived burden and degrade data quality regardless of prompt frequency.

**Therefore, the primary reflection UI is one question, four taps, no text field.**

```
┌──────────────────────────────────────┐
│  Phone was down 09:15 → 11:40        │
│                                      │
│  [ Deep work ] [ Light work ]        │
│  [ Rest ]      [ Not sure ]          │
│                              Dismiss │
└──────────────────────────────────────┘
```

Rules, all non-negotiable:

1. **Never prompt during a gap.** A notification asking "what are you doing?" *is itself the interruption that ends the focused block.* Queue it and show it on the next unlock, as an in-app card — not a notification.
2. **Max 1 prompt per 3h, max 4/day.** Hard-capped in code.
3. **Dismiss is free and silent.** No streak penalty, no guilt copy, no re-ask. A dismissed prompt is recorded as `dismissed = true` and is itself useful signal.
4. **Text is strictly optional**, behind a "add a note" affordance that is never the default focus. Never auto-open a keyboard.
5. **Backfill is allowed.** A quiet "3 unlabelled gaps this week" entry point in the weekly review, where labelling four gaps in fifteen seconds feels like tidying rather than confession.
6. **Never ask at night.** No prompts inside the evening window — that's the window being measured; instrumenting it with interruptions corrupts the measurement and the sleep.

If the author later wants longer-form journaling, add it as an explicitly separate, entirely user-initiated screen. Do not merge the two. Compulsory prose is what kills diary features.

### Pomodoro / focus timer

Standard 25/5, configurable. The differentiator is one metric no other timer computes: **interruption count** — unlocks recorded during the block, cross-joined from `raw_events`. That converts "I did four pomodoros" into "you did four pomodoros and unlocked your phone 23 times during them," which is the true statement. Store as `FocusBlock.interruptionCount`. Report it flatly, without commentary.

---

## 8. UI surfaces (Compose)

1. **Tonight** — live. Time until evening window; mEDI now vs the 10-lux line; glance count; modelled suppression so far with its band.
2. **Last night** — sessions on a timeline, glances as tick marks, sleep onset estimate, modelled suppression + phase shift, unlabelled-gap prompts.
3. **Patterns** — the payoff screen, and the reason for indefinite retention. Sleep onset over time (weeks/months/years). Evening screen minutes vs. sleep onset scatter with a fitted line. Glance count vs. self-reported rest quality. Month-over-month drift. *This screen is the product.* Build it early enough to stay motivated, even with sparse data.
4. **Focus** — timer + interruption history.
5. **Settings / Debug** — permission health, harvester last-run, model constants, `recomputeAll()`, CSV/JSON export, and a full data wipe.

Charts: **Vico** (Compose-native, actively maintained) over MPAndroidChart. All data offline, no network permission at all in Phase 1–3 — omit `INTERNET` from the manifest entirely. It's the strongest privacy guarantee available and it costs nothing.

---

## 9. Phases

**Phase 1 — Skeleton + truth (target: 1 weekend)**
Room schema; harvester Worker; Usage Access flow; glance classifier; "Last night" timeline. Success: leave it a week, open Patterns, see real glance counts. **Do not proceed until the harvester has survived 7 days without gaps.**

**Phase 2 — Light and sleep (1–2 weekends)**
Light service; §6.1 dose estimation; §6.2 Giménez suppression; §6.3 sleep inference; personalised evening window; "Tonight". Success: the modelled sleep onset matches the author's actual recollection within ~30 min on most nights.

**Phase 3 — Reflection, focus, and the model that earns its keep (2 weekends)**
μEMA cards; focus timer with interruption counting; Hannay19 port + golden-file validation; personal sensitivity fit once n≥30 nights.

**Phase 4 — Ubuntu (1 weekend)**
**Do not write a Linux tracker.** Install ActivityWatch on the laptop — `aw-server-rust` already exposes a REST API and `aw-sync` already syncs via a folder (Syncthing/Dropbox). Write a small exporter that pushes Nocturne's events into an ActivityWatch bucket, or a Python notebook on the laptop that reads Nocturne's exported SQLite directly. The laptop's screen light then folds into §6.1 as a second emitter, which materially improves the model for anyone working late. Building a bespoke Linux watcher would cost weeks and be strictly worse than a mature MPL-2.0 project.

---

## 10. Testing

`:core-model` gets real tests, because it's the only part where being wrong is silent:

- **Glance classifier:** synthetic event streams for each of the four kinds, plus adversarial cases — notification wake with no unlock, rapid on/off/on, screen-on during an alarm, a call arriving mid-gap.
- **Suppression:** assert the Giménez table reproduces (600/350/120/43/15 lux at 0.5/1/2/3/4 h) within tolerance. Assert monotonicity in both mEDI and duration.
- **Circadian:** golden-file test vs. `Arcascope/circadian` (§6.4). **Blocking for Phase 3.**
- **Sleep inference:** hand-label 14 real nights; require ≥80% within 30 min.
- **Time handling:** a DST transition and a timezone change must not corrupt "after 23:00" queries. Write this test before you need it.
- **Idempotency:** run the harvester twice over the same window; assert zero new rows.

On-device checks: kill the app and confirm the harvester backfills; disable battery optimisation and confirm the light service survives overnight; measure battery delta over 48h (target: <2%).

---

## 11. Pitfalls, ranked by how much time they will cost

1. **Treating the OS as storage.** The ~7-day window rolls off silently. Harvest, or lose the years.
2. **An always-on `dataSync` foreground service.** Android 15+ kills it at 6h/24h via `onTimeout()`. Use `specialUse` + WorkManager harvesting.
3. **Local time in the database.** DST and travel will corrupt every night-window query, months later, invisibly. UTC + captured offset, from day one.
4. **Believing the light sensor.** Uncalibrated, vendor-specific, occluded by cases and thumbs, and it *cannot see the screen*. Treat it as a weak prior with a wide band, never as ground truth.
5. **Shipping a bare melatonin percentage.** The interindividual range makes a point estimate misleading. Always a band, always labelled "modelled."
6. **Judgmental copy.** The evidence says this is how the app gets deleted in week two. Route every string through `:tone`.
7. **A required text field in the reflection flow.** The μEMA result is unambiguous: one tap or nothing.
8. **Prompting during a detected gap.** It destroys the very thing being measured.
9. **Destructive aggregation.** If `raw_events` isn't append-only and `recomputeAll()` isn't exact, the model can never be improved retroactively — and improving it retroactively over years of data is the whole point.
10. **Vendor battery killers.** Xiaomi/Oppo/Vivo/Samsung will silently kill the service. Handle it in onboarding, and design so that losing the service costs only lux, never usage.

---

## 12. Key sources

- Brown, T. et al. (2022). Recommendations for daytime, evening, and nighttime indoor light exposure. *PLOS Biology* 20(3): e3001571. — the 250/10/1 lux mEDI targets and the 3-hour evening window.
- Giménez, M.C. et al. (2022). Predicting melatonin suppression by light in humans. *J Pineal Res* 72:e12786. — duration-dependent ED50; the core of §6.2.
- Zeitzer, J.M. et al. (2000). Sensitivity of the human circadian pacemaker to nocturnal light. *J Physiol* 526:695. — the original four-parameter logistic.
- Phillips, A.J.K. et al. (2019). High sensitivity and interindividual variability in the response of the human circadian system to evening light. *PNAS* 116(24). — why §6.2 needs personal calibration.
- Hannay, K.M., Booth, V., Forger, D.B. (2019). Macroscopic models for human circadian rhythms. *J Biol Rhythms*. — the §6.4 model.
- Intille, S. et al. (2016). μEMA: Microinteraction-based EMA. *UbiComp '16*; and the JMIR mHealth (2021) criterion-validity study. — the §7 design.
- *Digital Overload Warnings – "The Right Amount of Shame"?* (2020). — why §1.2 Correction 1 exists.
- `github.com/Arcascope/circadian`, `github.com/ActivityWatch/aw-android`, `github.com/trextrinorex/phone-sleep-tracker`.

---

## 13. First command

```
Read this spec. Implement Phase 1 only.
Start with :core-model — the glance classifier and its unit tests — before
writing any Android code. The classifier is the heart of the app and it is
pure logic over an event stream, so it can be fully built and tested with
no device, no permissions, and no emulator.
Show me the classifier and its tests before continuing to Room or the UI.
```
