# Nocturne

Nocturne is a personal Android instrument for long-term tracking of night-time phone and laptop usage, inferring sleep patterns without wearables, and modeling the circadian impact of light exposure.

Unlike commercial screen-time trackers or Digital Wellbeing, Nocturne persists high-resolution event data indefinitely, classifies brief time-check glance unlocks, integrates published physiological models (Giménez melatonin suppression, Hannay19 and Forger99 ODE circadian phase estimators), and incorporates laptop activity via ActivityWatch.

---

## Key Pillars

1. **Indefinite Local Retention**: OS `UsageStatsManager` discards granular event logs after ~7 days. Nocturne continuously harvests raw Android events into an append-only SQLite database stored locally forever.
2. **Glance-Level Resolution**: Distinguishes between full interactive sessions, keyguard unlocks, and momentary screen wakes (e.g., checking the time for 4 seconds).
3. **Physiological Circadian & Sleep Modeling**:
   - **Light & Melatonin**: Computes melanopic Equivalent Daylight Illuminance (EDI) from ambient sensor readings and screen luminance, applying the Giménez (2014) non-linear dose-response curve to model melatonin suppression.
   - **Sleep Inference**: Passive sleep detection using screen state transitions, alarm interactions, and laptop activity via Bayesian priors and Bayesian Online Change Point Detection (BOCPD).
   - **Circadian Dynamics**: Integrates published 3-state ODE models (Hannay 2019, Forger 1999) to estimate circadian phase shifts ($\Delta \phi$) and Dim Light Melatonin Onset (DLMO).
   - **Regularity Metrics**: Calculates Sleep Regularity Index (SRI), Interdaily Stability (IS), Intradaily Variability (IV), Relative Amplitude (RA), and Circadian Function Index (CFI).
4. **Compassionate Copy & Objective Data**: Adheres to a strict tone policy—merciless, precise numbers paired with neutral, non-judgmental language. No adjectives blaming the user, no exclamation marks, and no emojis in insight strings.
5. **Cross-Platform Integration**: Integrates Linux laptop activity via a custom sync daemon connecting to `aw-server-rust` (ActivityWatch), unifying phone and laptop light/sleep dynamics.

---

## System Architecture

```mermaid
graph TD
    subgraph Android Device
        OS["Android OS Events / UsageStats"] -->|Harvest every <=6h| HARVESTER["Harvester Worker"]
        LIGHT_SENSOR["Ambient Light Sensor (stk33c01)"] -->|Sample 30s screen-on| LIGHT_SVC["Light Sampler Foreground Service"]
        
        HARVESTER -->|Raw Events| ROOM_DB[("Room Database (v7)")]
        LIGHT_SVC -->|Light Samples| ROOM_DB
        
        subgraph Core Logic
            ROOM_DB --> CLASSIFIER["Glance Classifier"]
            CLASSIFIER --> RECOMPUTER["Night Recomputer"]
            ROOM_DB --> RECOMPUTER
            
            RECOMPUTER --> CORE_MODEL["core-model Engine"]
            CORE_MODEL --> SLEEP_INF["Sleep Inference & Prior Fitting"]
            CORE_MODEL --> GIME_MODEL["Giménez Melatonin Suppression"]
            CORE_MODEL --> ODE_SOLVER["Hannay19 / Forger99 ODE Solvers"]
            CORE_MODEL --> REG_METRICS["Regularity Metrics (SRI, IS, IV, CFI)"]
        end

        RECOMPUTER -->|Nights, Model Runs, Metrics| ROOM_DB
        ROOM_DB --> TONE_ENGINE["tone Module"]
        TONE_ENGINE --> UI["Jetpack Compose UI"]
    end

    subgraph Ubuntu Laptop
        AW_SERVER["ActivityWatch Server"] -->|aw-client| AW_PYTHON["nocturne_aw.py Daemon"]
        AW_PYTHON -->|ADB Content Provider sync| ROOM_DB
        ROOM_DB -->|Export Sessions & Sleep| AW_PYTHON
        AW_PYTHON -->|Write BUCKETS| AW_SERVER
    end
```

---

## Data & Processing Pipeline

```mermaid
sequenceDiagram
    autonumber
    participant OS as Android OS / Sensors
    participant Coll as Collector Service
    participant DB as Room DB (v7)
    participant Class as Classifier & Recomputer
    participant Model as Core Model Engine
    participant AW as ActivityWatch Daemon

    OS->>Coll: Emit raw events & ambient lux readings
    Coll->>DB: Insert into raw_events_v5 & light_samples
    AW->>DB: Sync laptop spans via ADB Content Provider
    DB->>Class: Fetch raw events & light samples for window
    Class->>Class: Classify unlocks, glances, and app sessions
    Class->>Model: Run sleep inference (Bayesian priors + BOCPD)
    Model->>Model: Calculate melanopic EDI & Giménez suppression
    Model->>Model: Solve Hannay19 ODE for circadian phase drift
    Model->>Model: Compute regularity metrics (SRI, IS, IV, CFI)
    Class->>DB: Store nights, window_metrics, model_runs
    DB->>AW: Phone sessions & nights exported to ActivityWatch
```

---

## Module Overview

The codebase is organized as a multi-module Kotlin project with strict architectural boundaries:

```mermaid
graph LR
    app[":app - Jetpack Compose UI"] --> data[":data - Room DB & Repositories"]
    app --> core_model[":core-model - Pure JVM Math"]
    app --> tone[":tone - String Templates"]
    app --> collector[":collector - WorkManager & Services"]
    collector --> data
    collector --> core_model
    data --> core_model
```

### Module Responsibilities

- **`:core-model`**: Pure Kotlin JVM module with **zero Android dependencies**. Contains all numerical math, ODE differential equation solvers (Hannay 2019, Forger 1999), light dose calculations, Giménez melatonin suppression model, sleep inference logic, Bayesian priors, and actigraphy regularity algorithms. Validated against Python reference oracles (`Arcascope/circadian`, `nparACT`).
- **`:tone`**: Centralized string formatting and copy generator. Enforces non-judgmental, compassionate copy and strict formatting rules (no emojis, no exclamation marks).
- **`:data`**: Room Database implementation (currently Schema v7), entities, DAOs, schema migrations, CSV/Zip export engine, and ADB ContentProvider interface (`LaptopContentProvider`).
- **`:collector`**: Android background harvesting framework (`Harvester` WorkManager job), `LightSamplerService` (foreground service with `specialUse` type sampling light while screen is interactive), boot/timezone receivers.
- **`:app`**: Jetpack Compose UI layer featuring four main tabs:
  - **Tonight**: Real-time evening light dose, eye lux, glance counter, modelled melatonin suppression, and countdown to personal evening window.
  - **Focus**: Exact-alarm timer, phone-down gap cards, unlock counters, and reflection prompts.
  - **Regularity (Patterns)**: SRI score, onset/wake distribution charts, IS/IV actigraphy metrics, and weekly history.
  - **Settings**: System status, sensor diagnostics, display profiles, ADB debug tools, data export/import.
- **`tools/activitywatch`**: Python daemons and CLI tools running on Ubuntu Linux for bi-directional synchronization with ActivityWatch.
- **`tools/circadian` & `tools/bocpd`**: Python reference scripts used as test oracles for cross-validating Kotlin ODE solvers and change point algorithms.

---

## Circadian & Physiological Models

```mermaid
flowchart LR
    subgraph Inputs
        L_amb["Ambient Light Lux"]
        S_bright["Screen Brightness & Color Filter"]
        L_laptop["Laptop Display Light"]
    end

    subgraph Photobiology
        L_amb & S_bright & L_laptop --> EDI["Melanopic EDI Calculation"]
        EDI --> DOSE["Evening Light Dose"]
    end

    subgraph Physiology
        DOSE --> GIME["Giménez Melatonin Suppression Curve"]
        DOSE --> ODE["Hannay19 / Forger99 ODE Integrator"]
    end

    subgraph Outputs
        GIME --> SUPP["Melatonin Suppression %"]
        ODE --> PHASE["Phase Shift & DLMO Estimate"]
    end
```

### 1. Melanopic Light Dose & Suppression
- **Eye Illuminance Model**: Combines ambient room lux from `Sensor.TYPE_LIGHT` with screen luminance calculated from current display brightness settings and dark mode / warm display filter states.
- **Giménez Dose-Response Model**: Applies non-linear saturation curves to model light-induced melatonin suppression during the evening window ($t = \text{Sleep Onset} - 3\text{h}$).

### 2. Sleep Inference Engine
- Uses passive phone interactions (last screen-off, alarm dismissions, keyguard events) merged with laptop activity.
- Applies Bayesian priors (Student-t distributions over habitual onset/wake) combined with a prior for sleepless nights.
- Employs Bayesian Online Change Point Detection (BOCPD) to detect sleep boundary shifts without manual annotations.

### 3. Circadian Phase Dynamics (ODE Solvers)
- Implements 4th-order Runge-Kutta (RK4) integration of the 3-state **Hannay 2019** and **Forger 1999** differential equation models.
- Predicts individual Dim Light Melatonin Onset (DLMO) and phase shift ($\Delta \phi$) trajectory under arbitrary light schedules.

---

## ActivityWatch Linux Integration

The `tools/activitywatch` subsystem connects Nocturne with `aw-server-rust`:

- **Laptop -> Phone**: Reads active display spans and AFK status from ActivityWatch, writing them to Nocturne's Room database via `adb shell content write`. Laptop light minutes directly feed sleep inference and light dose models.
- **Phone -> Laptop**: Reads classified phone sessions and inferred sleep intervals from Nocturne via `adb shell content read` and pushes them as custom buckets (`nocturne-sessions_<device>`, `nocturne-sleep_<device>`) into ActivityWatch.

```mermaid
sequenceDiagram
    participant Laptop as Ubuntu (nocturne_aw.py)
    participant AW as aw-server-rust
    participant Phone as Nocturne (Android App)

    Laptop->>AW: Query AFK & window buckets
    Laptop->>Phone: ADB content write -> laptop_spans
    Phone->>Phone: Recompute sleep & light with laptop spans
    Laptop->>Phone: ADB content read -> sessions & sleep
    Laptop->>AW: Post nocturne-sessions & nocturne-sleep buckets
```

---

## Database Schema Evolution

Nocturne maintains a Room database schema progression with zero-data-loss migrations:

| Schema Version | Key Changes |
|---|---|
| **v1** | Initial raw events (`raw_events_v4`), classified sessions, basic night records. |
| **v2** | Added `nights` table with sleep inference onset/wake, user labels, and confidence scoring. |
| **v3** | Added `light_samples` table for ambient sensor readings and screen brightness tracking. |
| **v4** | Added `window_metrics` and `model_runs` tables for tracking actigraphy regularity metrics over 7/14/28-night windows. |
| **v5** | Interned package names (`raw_events_v5`) for reduced storage footprint. |
| **v6** | Added `reflections` table for micro-EMA gap cards and focus timer records; dropped obsolete `raw_events_v4`. |
| **v7** | Added `laptop_spans`, `laptop_hosts`, and `nights.lightLaptopMinutes` for ActivityWatch cross-device sync. |

---

## Build & Development Setup

### Prerequisites
- **JDK**: Version 17
- **Android SDK**: Platform 37.2 (Build Tools 36+, `targetSdk` 36, Compose BOM 2026.09)
- **Gradle**: 9.6.0 (via wrapper)
- **Kotlin**: 2.4.20 (via AGP 9.4.0)

### Running Unit & Oracle Tests
Execute all JVM unit tests across modules:
```bash
./gradlew :core-model:test :tone:test :data:testDebugUnitTest :collector:testDebugUnitTest
```

### Installing on Device
Connect Android device via USB with ADB enabled and run:
```bash
./gradlew :app:installDebug
```

### ActivityWatch Daemon (Linux)
Install Python dependencies and run the sync daemon:
```bash
python3 tools/activitywatch/nocturne_aw.py sync
```

---

## License

Personal instrument software. All rights reserved.
