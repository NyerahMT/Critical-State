# Critical State operations overhaul

This milestone turns the coupled-plant prototype into an operator-facing mobile simulator while preserving the existing reduced-order PWR physics.

## Runtime architecture

`SimulationRuntime` is the sole owner of `ReactorSimulator`. It runs on a dedicated `HandlerThread`; all plant commands are serialized onto that worker. The Android main thread only receives immutable `PlantState` snapshots and renders the current page.

Normal operation advances a 0.10 s wall-clock simulation quantum and publishes at approximately 10 Hz. UI rendering is therefore decoupled from the internal kinetics and thermal-hydraulic substeps. The worker schedules its next tick only after the previous solve completes, so a slow device does not accumulate an unbounded queue of simulation work.

The status header exposes requested time scale, effective measured time scale and solver compute time. This makes a performance regression visible on the target phone rather than hiding it behind a delayed simulation clock.

## Thermodynamic performance

The IF97 adapter now uses direct-mapped exact-value caches for PT, PH, saturation and PS evaluations. Inputs are never rounded or interpolated. Cache hits require exact `Double` bit patterns, so the optimization removes duplicate property calls without changing the thermodynamic state passed to the plant model.

The normal 1x runtime remains at 0.05 s coupled half-steps. Accelerated batches may use thermal-hydraulic plant steps up to 0.25 s; the orchestrator still performs two coupled half-steps and point kinetics keeps its own 0.01 s substep. This is intended to make 10x/60x usable on low-end hardware while retaining a bounded integration hierarchy.

## Fixed-screen HMI

The previous `ScrollView` development panel has been replaced by five fixed pages:

- **Overview** — process mimic, eight critical plant instruments, rod nudges, SCRAM and pause/run.
- **Primary** — neutronics, reactivity, core thermal state, pressurizer, all four RCP controls, rod-bank slider and boron controls.
- **Steam** — four steam generators, main steam, feedwater, condenser and heat balance.
- **Grid** — turbine speed, governor, generator output, reactive power, auxiliary load, breaker and turbine controls.
- **Trends** — lightweight fixed-buffer trends for reactor power, RCS pressure and net generation plus conservation/protection diagnostics.

A persistent top header carries plant status, simulation clock, measured solver performance and time acceleration. A persistent alarm strip keeps trip/model advisories visible on every page. Bottom navigation changes pages without vertical scrolling.

## Visual language

The UI uses a dark industrial HMI palette with color reserved for state meaning:

- green: normal/running
- amber: caution/off-normal/manual
- red: trip/unsafe
- blue: primary/feedwater
- cyan: steam/secondary
- white/gray: neutral data

The process mimic is intentionally diagrammatic rather than a literal P&ID so the energy path remains readable on a phone-sized display.

## Why trends are custom

For this low-end target, trends use a small custom `View` and a fixed 240-sample ring buffer rather than a general chart framework. The view draws one path and a frame, creates no per-sample widget hierarchy and has no gesture/animation overhead. This keeps the display useful for transient diagnosis without turning trend rendering into another performance bottleneck.

## On-device acceptance targets

The milestone is considered successful when the target G-Mee demonstrates:

1. no vertical scrolling on any primary operating page;
2. responsive controls while the coupled plant is solving;
3. an effective 1x rate close to real time;
4. materially faster 10x/60x progression than the pre-overhaul build;
5. readable alarm, plant-state and performance information at a glance;
6. stable physics tests and finite state under accelerated operation.
