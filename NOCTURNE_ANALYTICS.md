# Nocturne — Analytics Layer Specification

**Companion to `NOCTURNE_SPEC.md`. Read that first.**
**Scope:** how sleep-pattern scoring is computed, on what cadence, and how data is retained.
**Status of the originating idea:** not dumb. Half of it is the right instinct pointed at the wrong problem, and the other half is straightforwardly correct and should be built.

---

## 0. Verdict in one page

The request bundled three claims. They need separating, because they have different answers.

| Claim | Verdict |
|---|---|
| "Run scoring periodically rather than continuously" | **Right, for a reason you didn't state.** Not storage — statistical validity. Most sleep-regularity metrics are mathematically undefined on a single night. |
| "This lets us avoid storing much data" | **Backwards.** The storage saving is ~54 MB/year, i.e. nothing, and paying for it destroys the one capability that makes this project worth building. |
| "Modular code and database" | **Correct and important.** This is the part to actually implement, and it's the part that makes the other two tractable. |
| "Every 6 months" | **Half right.** It matches common industry practice for scheduled retraining, but the literature on drift is clear that fixed cadence alone is the weakest option. Use a tiered schedule with a drift sentinel. |

**The single sentence version:** build the tiered, modular scoring pipeline exactly as your instinct suggests — but drive the tiers off *how much data each statistic needs to be meaningful*, not off a storage constraint that does not exist.

---

## 1. Killing the compression premise (with arithmetic)

Storage is the stated justification, so it has to be checked rather than assumed. Computed for a heavy user at ~2,000 raw events/day:

```
raw_events (package names interned)    137.5 KB/day      49.0 MB/yr
raw_events (package names as TEXT)     330.9 KB/day     117.9 MB/yr
binary sleep/wake, 1-min, bit-packed     0.176 KB/day     64.2 KB/yr
light_samples (30s, screen-on only)     14.1 KB/day       5.0 MB/yr
─────────────────────────────────────────────────────────────────
TOTAL                                   ~54 MB/yr   |   10 yr = 0.53 GB
```

**One year of complete raw history costs about as much as fourteen phone photos.** Ten years fits in half a gigabyte — less than a single 90-second 4K video clip.

So the entire compression exercise buys back, at absolute best, 54 MB/year on a device with 128–512 GB. Meanwhile, what it costs:

**You permanently lose the ability to re-score your own history.** In month eight you will improve the melatonin model, retune the glance thresholds, or discover your light-dose distance assumption was wrong. If `raw_events` survives, one call to `recomputeAll()` re-scores *every night you have ever recorded* under the corrected model. If you discarded raw events in favour of summaries, those old summaries are frozen artefacts of a model you no longer believe. You cannot un-summarise.

That single property is the reason this project beats Digital Wellbeing. Trading it for 54 MB is the worst trade available in this codebase.

**The one real optimisation, and it is not deletion:** intern package names into a `packages` lookup table and store a 4-byte FK on each event. That alone takes you from 118 MB/yr to 49 MB/yr — a 58% reduction with **zero information loss**. Do this. Then stop optimising storage and never think about it again.

If it ever genuinely matters (it won't), the escape hatch is transparent compression of cold partitions, not deletion — see §6.3.

---

## 2. The real reason for periodic scoring: statistics, not bytes

Here is the argument that actually justifies your instinct, and it's stronger than the storage one.

**Almost every meaningful sleep-regularity metric is undefined or meaningless on short windows:**

- **Sleep Regularity Index (SRI)** is defined as the probability of being in the same state (asleep/awake) at any two timepoints **24 hours apart**. With one night, there is no "24 hours apart" pair to compare. Standard practice computes it over **≥7 days**; the UK Biobank mortality analysis used 7 days of accelerometry.
- **Interdaily Stability (IS)** is literally the variance of the average daily profile divided by total variance. With n=1 day, the average daily profile *is* the day, and IS is degenerate.
- **Intradaily Variability (IV), M10, L5, Relative Amplitude** can be computed per-day, but the standard protocol across the actigraphy literature is a **7-day** window, because single-day values are noise-dominated.
- A **personal light-sensitivity fit** (§6.2 of the main spec) needs enough nights to regress modelled suppression against outcome. Below ~30 nights you are fitting noise.

**Therefore the cadence is not an efficiency choice. It is a correctness constraint.** Computing an SRI nightly and showing it to the user would be displaying a number that does not mean what its name says. This is exactly the kind of error that makes quantified-self apps quietly useless.

Corollary that matters for the UI: the "Patterns" screen must refuse to render regularity metrics until it has the required window, and say so plainly — "SRI needs 7 nights; you have 4" — rather than showing a provisional number that will swing wildly and destroy trust.

---

## 3. What the "ML" should actually be

Be precise about the sample size. Six months of nightly data is **n ≈ 180 observations** on a handful of features, from **one person**. That is a small, autocorrelated, single-subject time series.

**What is therefore wrong for this problem:**
- Neural networks of any kind. LSTMs, transformers, TFLite/LiteRT, ONNX Runtime. At n=180 these overfit catastrophically and cannot be validated. There is no honest way to hold out a test set from your own 180 nights.
- Anything that produces an unexplainable score. If the app says "your sleep score is 61" and cannot say why, it is astrology with a progress bar.

**What is right** — and this is what the n-of-1 / *idiographic* modelling literature actually uses on exactly this kind of data:

| Job | Method | Why |
|---|---|---|
| Describe regularity | **SRI, IS, IV, RA, L5/M10, CPD, social jetlag** | Closed-form, validated against hard outcomes, zero parameters to fit, fully explainable |
| Detect "something changed" in real time | **Bayesian Online Changepoint Detection** (Adams & MacKay 2007) | Streaming, O(1) amortised with pruning, gives a *probability* of change and a run-length posterior — not a binary alarm |
| Segment history definitively | **PELT** (Killick et al. 2012) | Offline, exact, linear time. Run over the whole history to get authoritative "eras" |
| Find which behaviours predict bad nights | **Elastic-net regularised regression** | Handles correlated predictors, does variable selection, coefficients are directly readable |
| Identify recurring regimes | **Gaussian finite mixture model** over nightly feature vectors | "You have three modes: weekday, weekend, and collapse" — interpretable clusters |
| Fit personal light sensitivity | **Nonlinear least squares on the logistic ED50 multiplier** | One parameter, physically meaningful, reported with a CI |

This mirrors published practice: the antidepressant-tapering study used within-person kernel changepoint analysis on 4 months of actigraphy and detected change points near the symptom transition in 69% of individuals who had one. The idiographic PTSD work used Gaussian finite mixture modelling plus elastic-net per person. The methods that work at n-of-1 are classical, regularised, and interpretable.

**Kotlin implications:** every method above is a few hundred lines of pure Kotlin in `:core-model`. No NDK, no TFLite, no model files to ship, no inference runtime. The heaviest is PELT, which is O(n) on n≈2000 nights — microseconds. This keeps the module boundary from the main spec intact and keeps the Python cross-validation path open.

---

## 4. The cadence: tiered, not six-monthly

Your "every 6 months" matches what industry actually does — scheduled full retrains every 3–6 months are common practice. But the drift literature is consistent that this is the weakest available policy on its own: fixed schedules waste compute in stable periods and miss rapid change in unstable ones. The recommended pattern is uniformly **hybrid: scheduled baseline + drift-triggered updates**. (Worth noting the counter-evidence: a recent ~4,000-experiment study found periodic retraining can outperform drift-triggered retraining in some regimes, so don't throw away the schedule — run both.)

For this app, drive the tiers off **data sufficiency**, which happens to produce a clean schedule:

### Tier 0 — Nightly (cost: ~5 ms)
Pure derivation from the night just closed. No fitting, no model.
`eveningScreenMinutes`, `glanceCount`, `postOnsetInterruptions`, `estimatedSleepOnset`, `melanopicDoseLuxHours`, `modelledSuppressionPct`.
Written to `nights`. This is bookkeeping, not ML.

### Tier 1 — Weekly, rolling 7/14/28-day windows (cost: ~50 ms)
The first tier where regularity metrics become defined.
`SRI`, `IS`, `IV`, `RA`, `L5`/`M10` + their phases, `CPD`, `socialJetlag`, `sleepOnsetSD`.
Gate on data sufficiency: SRI requires ≥7 nights with ≥80% epoch coverage, else emit `null` and a reason code, never a guess.

### Tier 2 — Continuous drift sentinel (cost: ~0.1 ms per night)
**BOCPD** running over the nightly `sleepOnsetMinutes` and `SRI_7d` series, hazard rate ≈ 1/90 (one expected regime change per quarter).

This is the piece your original plan was missing, and it's the most valuable one. A purely 6-monthly job means that if your schedule collapses in March, you find out in July. The sentinel notices within days and says so — with a probability, not a claim: *"Your sleep onset distribution appears to have shifted around 14 March. P(change) = 0.81, based on 21 nights since."*

### Tier 3 — Quarterly / semi-annual refit (cost: ~2 s, run on charger + idle)
**This is your 6-month job. Keep it.** It does what only a full-history batch can:
1. **PELT** over the entire history → authoritative era segmentation. Labels every era with its mean onset, SRI, and duration.
2. **Elastic-net refit** of the nightly-predictors → sleep-onset model, with coefficients surfaced in plain language.
3. **GMM** over nightly feature vectors → recurring night types.
4. **Personal ED50 multiplier refit** (§6.2 main spec) once n ≥ 30 nights, reported with a confidence interval and the n it was fitted on.
5. **`recomputeAll()`** — re-score all history under the current model version, which is only possible because §1 kept the raw events.

Trigger: `every 180 days` **OR** `BOCPD posterior > 0.9` **OR** manual button. Hybrid, exactly as the drift literature recommends.

```
Tier 0  nightly      derive        ~5 ms     no model
Tier 1  weekly       aggregate     ~50 ms    closed-form, needs ≥7 nights
Tier 2  nightly      BOCPD         ~0.1 ms   streaming sentinel
Tier 3  180d/trigger PELT+fit      ~2 s      full history, refits parameters
```

---

## 5. Metric definitions (implement exactly; do not improvise)

There is a published warning here worth heeding: the **RIRI statement** (Reporting Items for Regularity Indices, *SLEEP* 2026) exists precisely because different SRI implementations — `sleepreg` vs `GGIR` — produce **different scores from the same data**, enough to change study conclusions. So pin the choices explicitly in code and in a doc comment.

**Sleep Regularity Index**
```
SRI = -100 + (200 / (M(N-1))) * Σ_{i=1}^{N-1} Σ_{j=1}^{M} δ(s_{i,j}, s_{i+1,j})
```
where `s_{i,j}` is the binary sleep state on day *i* at epoch *j*, `δ` is 1 if equal else 0, `M` = epochs/day, `N` = days.
Range −100 to 100 (commonly reported rescaled 0–100). ≥90 is regular; low-70s is the mean in clinical populations.

**Pin these and document them:**
- Epoch length: **1 minute** (`M = 1440`)
- Day boundary: **noon-to-noon**, not midnight — a midnight boundary splits the night being measured, which is exactly wrong for this app
- Naps: **included** (SRI's design advantage is that it needs no "main sleep period")
- Missing epochs: **excluded pairwise**, and record coverage %; refuse to emit SRI below 80% coverage

**Interdaily Stability** — variance of the mean 24 h profile / total variance. 0 = no rhythm, 1 = perfectly stable.
**Intradaily Variability** — mean squared difference between consecutive epochs / variance around the grand mean. 0 for a pure sine, ≈2 for Gaussian noise; healthy subjects typically <1.
**RA** = (M10 − L5) / (M10 + L5), range 0–1.
**Circadian Function Index** = mean of (normalised inverted IV, IS, RA) — a single robustness scalar, useful as the headline "how is my rhythm doing" number.

For Nocturne, compute IS/IV/M10/L5 over the **inverse screen-activity series** as an actigraphy proxy, and validate the proxy against the sleep inference before trusting it. Document loudly that this is a proxy, not accelerometry. Cross-check against `nsrr/actiCircadian` (R) on exported CSV as a golden-file test, same pattern as the circadian ODE validation.

---

## 6. Modularity — the genuinely good part of the request

### 6.1 Code

```
:core-model/
  metrics/
    RegularityMetrics.kt      SRI, IS, IV, RA, L5/M10, CPD, CFI
    SufficiencyGate.kt        "does this window have enough data?" — one place
  detect/
    Bocpd.kt                  streaming sentinel
    Pelt.kt                   offline segmentation
  fit/
    ElasticNet.kt
    GaussianMixture.kt
    SensitivityFit.kt         personal ED50 multiplier
  Scorer.kt                   orchestrates tiers; PURE FUNCTION of (events) -> (scores)
```

Two hard rules, both enforceable by test:

1. **`Scorer` is a pure function.** `score(events: List<RawEvent>, config: ModelConfig): ScoreSet`. No DB access, no clock reads, no Android. Given the same input it returns the same output forever. This makes `recomputeAll()` trivially correct and makes every metric testable against R/Python reference implementations.
2. **Every metric declares its own data requirement.** `interface Metric { val minNights: Int; val minCoverage: Float; fun compute(w: Window): Result<Double> }`. Adding a metric must never require touching the scheduler. The sufficiency gate lives in one file, not scattered as `if (nights.size >= 7)` across the codebase.

### 6.2 Model versioning — the piece that makes re-scoring honest

```kotlin
@Entity(tableName = "model_runs")
data class ModelRun(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val ranAt: Long,
  val modelVersion: Int,        // bump on ANY formula change
  val trigger: RunTrigger,      // SCHEDULED, DRIFT_DETECTED, MANUAL, RECOMPUTE
  val nightsCovered: Int,
  val configJson: String,       // full ModelConfig snapshot
  val fittedParamsJson: String  // ED50 multiplier, elastic-net coefs, PELT breakpoints
)
```

Every derived row carries the `modelRunId` that produced it. Consequences:
- You can ask "did my SRI actually change, or did I change the formula?" — the single most important question in any long-running personal-analytics system, and one that is unanswerable without this table.
- A bad refit is revertible: re-score from the previous `configJson`.
- `fittedParamsJson` accumulates into its own time series. Watching your personal ED50 multiplier drift across two years is itself a finding.

### 6.3 Database tiering — the correct version of your compression instinct

Your instinct to tier the data is right. Just never let the bottom tier be `DELETE`.

| Tier | Age | Contents | Action |
|---|---|---|---|
| Hot | 0–90 d | `raw_events` full fidelity | Indexed, queried live |
| Warm | 90 d–2 y | `raw_events` full fidelity, plus all derived | Drop the secondary index, keep the primary |
| Cold | >2 y | `raw_events` in a compressed archive table (zstd-compressed monthly blobs of serialised events) | Transparently rehydrated on demand by `recomputeAll()` |

Cold-tier compression gets you 5–10× on event data (highly repetitive, sorted by timestamp) at the cost of a rehydration step that only runs during Tier 3. That is a real, lossless, reversible optimisation. It is the *only* storage work worth doing, and given §1 you should not bother with it before year three.

Standard time-series practice — Prometheus retention, InfluxDB downsampling, TimescaleDB continuous aggregates — all follow this shape: **downsample for query speed, never for storage scarcity, and keep the source of truth.**

---

## 7. Phasing (slots into the main spec)

- **Phase 2.5** (after sleep inference works): `RegularityMetrics.kt` + `SufficiencyGate` + weekly Tier 1 worker. Purely closed-form. No fitting. This alone makes the Patterns screen worth opening.
- **Phase 3.5** (after ≥60 nights of real data): `Bocpd.kt` as Tier 2 sentinel. Tune the hazard rate against *your own* history, not a default.
- **Phase 5** (after ≥180 nights): Tier 3 — PELT, elastic net, GMM, sensitivity refit, `model_runs`. **Do not build this before the data exists.** An unfittable model fitted anyway produces confident garbage, and you will believe it because it's about you.

Note the ordering constraint: Tier 3 is the last thing built, not the first, because it is the only tier that cannot be tested without a real longitudinal dataset. Building it early means writing code you cannot validate.

---

## 8. Pitfalls specific to this layer

1. **Deleting raw data to save 54 MB.** The premise this document exists to refute. Intern strings instead.
2. **Showing SRI on day 3.** It is undefined. Gate it, and say why.
3. **Midnight day boundaries.** Splits the measured night in half. Use noon-to-noon.
4. **Changing a formula without bumping `modelVersion`.** You will later see a "trend" that is entirely an artefact of your own edit, and you will believe it.
5. **Reaching for a neural net.** n=180, one subject, autocorrelated. Elastic net or nothing.
6. **Treating BOCPD output as a verdict.** It emits a posterior. Surface it as "P(change) = 0.81 around 14 March," never "YOUR SLEEP HAS DEGRADED." This is the §1.2 tone rule from the main spec applied to statistics.
7. **Fitting personal sensitivity on n=12 nights** because the button was there. Hard-gate at n≥30 and always display the n alongside the estimate.
8. **Assuming the screen-activity proxy equals actigraphy.** It doesn't. IS/IV computed on phone activity is a *related* construct, not the published one. Validate against your own sleep inference and label it accordingly.
9. **Running Tier 3 on battery.** Constrain the Worker to `requiresCharging` + `requiresDeviceIdle`.
10. **Optimising any of this before Phase 2 ships.** The main spec's phase order still governs.

---

## 9. Sources

- Phillips, A.J.K. et al. (2017). Irregular sleep/wake patterns are associated with poorer academic performance… *Sci Rep* — original SRI definition.
- Windred, D.P. et al. (2023/2024). Sleep regularity and mortality: a prospective analysis in the UK Biobank. *eLife* 12:e88359 — SRI over 7 days of accelerometry; SRI outperforms SD-based metrics.
- Lunsford-Avery, J.R. et al. (2018). Validation of the Sleep Regularity Index in Older Adults. *Sci Rep* 8:14158 — MESA, n=1978.
- **RIRI statement** (2026). Comparison of SRI scores calculated by open-source packages. *SLEEP* — why §5 pins every parameter explicitly.
- Van Someren, E.J.W. et al. — nonparametric circadian rhythm analysis (IS, IV, L5, M10, RA). See also `github.com/nsrr/actiCircadian`.
- Ortiz-Tudela, E. et al. — Circadian Function Index.
- Adams, R.P. & MacKay, D.J.C. (2007). Bayesian Online Changepoint Detection. arXiv:0710.3742.
- Killick, R., Fearnhead, P., Eckley, I.A. (2012). PELT. See `ruptures` (Python) for a reference implementation to validate against.
- Kunkels, Y.K. et al. (2024). Individual-specific change points in circadian rest-activity rhythm… *Sci Rep* 14 — within-person kernel changepoint analysis on 4 months of actigraphy.
- Dasari, S. When to Retrain: An Empirical Study of Retraining Policies for Streaming ML Under Concept Drift — periodic vs. triggered, ~4,000 experiments.
- Idiographic/N-of-1 ML methodology: elastic-net + GFMM per-subject designs in mHealth.

---

## 10. First command

```
Read NOCTURNE_SPEC.md, then NOCTURNE_ANALYTICS.md.

Do NOT implement the analytics layer yet — it is Phase 2.5 and later.

Implement one thing now: in :core-model, add metrics/SufficiencyGate.kt and
metrics/RegularityMetrics.kt with SRI only, plus its unit tests. Use a
noon-to-noon day boundary, 1-minute epochs, pairwise-complete handling, and
refuse to emit a score below 7 nights or 80% coverage.

Test it against a hand-computed 8-day synthetic case and against a perfectly
regular sleeper (expect SRI ~100) and a random sleeper (expect SRI ~0).
Show me the gate and the tests before writing anything else.
```
