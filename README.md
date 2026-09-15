# Critical State

**Critical State** is a mobile-first, reduced-order pressurized-water-reactor simulation game by NyerahWorks.

The plant is intentionally built as a coupled physical system rather than a collection of scripted gauges. Reactor power comes from neutron kinetics. Fission and decay heat move through fuel, cladding and coolant. Primary flow follows pump head, hydraulic resistance, coolant inertia and buoyancy. Steam generation follows primary-to-secondary heat transfer and secondary inventory. Turbine power follows steam flow and enthalpy drop. Generator output follows shaft/grid dynamics. Condenser pressure and feedwater behavior close the power-conversion loop.

The guiding rule is:

> **Simplification may reduce spatial resolution or component detail, but it must not replace physical causality with an arbitrary gameplay mapping.**

## Current coupled plant

Version **0.5.0-fidelity-lock** closes the first target-fidelity pass for the complete real-time reference-plant chain:

- six-group point reactor kinetics with equilibrium precursor initialization, source term and inhour-period verification;
- finite-speed control-bank motion, nonlinear integral worth, 5000 pcm full-bank worth and finite-time SCRAM insertion;
- Doppler, moderator-density, soluble-boron, iodine/xenon and promethium/samarium reactivity;
- ten active stored-energy decay-heat groups preserving prior operating history;
- six axial core thermal regions with radial fuel storage, cladding storage, coolant enthalpy transport, estimated peak-factor heat-flux indication and IF97 subcooling margin;
- four independent primary loops with hot/cold-leg transport, Darcy/minor losses, dynamic mass flow, centrifugal RCP curves, rotor inertia, coastdown and natural-circulation head;
- a fixed-volume saturation pressurizer with conserved mass/internal energy, swell/inventory transfer budgeting, heaters, spray and generic relief flow;
- four dynamic steam generators with segmented primary heat transfer, tube-wall thermal inertia, conserved saturation-secondary mass/energy, boiling, steam export, level and automatic feedwater demand;
- a dynamic main-steam header;
- a two-stage reduced steam turbine using a Stodola pressure-flow relation and IF97 isentropic expansion;
- turbine-generator inertia, grid synchronization/breaker logic, swing-equation behavior, gross/net MW and reactive-power indication;
- condenser/hotwell mass and energy storage with cooling-water heat rejection and thermodynamic vacuum;
- a physical feedwater train with pump dynamics, valve distribution and extraction-steam heating;
- dynamic instrumentation and generic redundant protection channels with latching reactor-trip logic;
- operator-facing process values routed through instrumentation channels while diagnostics retain true solver state;
- explicit saturation-envelope diagnostics for the PZR, SG secondary volumes and condenser;
- hidden equipment condition that changes physical component parameters rather than a visible health-stat multiplier;
- long-timescale generation/revenue/debt accounting driven by actual net electrical output;
- whole-plant mass/energy residual diagnostics with modeled boundary-energy accounting;
- 1x, 10x and 60x simulation speeds using bounded physical substeps rather than enlarged game timesteps.

Detailed acceptance evidence for this release is in [`docs/FIDELITY_LOCK_0_5.md`](docs/FIDELITY_LOCK_0_5.md).

## Reference plant

The first reference plant is a **generic four-loop commercial PWR**. Core scale and selected physics data are anchored where appropriate to public MIT BEAVRS benchmark information; missing balance-of-plant geometry and performance data use compatible public engineering references or explicitly tagged calibration-required estimates.

Representative scale:

- reactor thermal power: 3411 MWth;
- four primary loops;
- nominal RCS pressure: 15.51 MPa;
- total reference core flow: about 17,083 kg/s;
- 193 fuel assemblies, 17 x 17 lattice, 264 fuel rods/assembly;
- active fuel length: 3.6576 m;
- steam-generator secondary pressure: roughly 6.2 MPa reference state;
- gross output target: about 1.15 GWe;
- net output target: about 1.115 GWe.

These values define a game/reference model. They are **not** a reconstruction of an operating station.

## Validated target-fidelity envelope

The current CI acceptance suite includes:

- a 600 s autonomous full-power design hold;
- finite-time SCRAM with thousands of pcm of shutdown worth and retained decay heat;
- one-RCP coastdown and all-RCP-off natural-circulation behavior;
- full-power turbine trip with continuous secondary evolution;
- automatic SG level and pressurizer response to ±10% load commands;
- explicit saturation-envelope reporting rather than silent best-guess vessel states;
- a 60-minute nominal run through the actual 60x fast-forward path;
- bounded whole-plant mass and energy residuals.

At the 3600 s nominal 60x checkpoint, the accepted run held approximately 15.464 MPa primary pressure, 582.388 K average primary temperature, 17,083.6 kg/s primary flow, 0.65000 mean SG level and 1109.90 MWe net output, with about -0.619 kg mass residual and -1.326 MJ cumulative energy residual.

Deep shutdown/cooldown can leave the declared saturation-only equilibrium-volume domain. Those departures are flagged explicitly; they are not presented as validated saturation states.

## Architecture

```text
Player commands
    |
    v
Actuators / automatic controllers
    |
    +--> Rod drive --> reactivity --> six-group kinetics
    |                                 |
    |                                 v
    |                         fission + decay heat
    |                                 |
    |                                 v
    |                       fuel / clad / coolant
    |                                 |
    |                                 v
    +--> RCPs --> four-loop RCS hydraulics <--> pressurizer inventory budget
                                      |
                                      v
                           four steam generators
                                      |
                                      v
                              main steam header
                                      |
                                      v
                           turbine / shaft / grid
                                      |
                                      v
                             condenser / hotwell
                                      |
                                      v
                               feedwater train
                                      |
                                      +--------> SGs

True physical state --> sensors --> protection / operator glass
True physical state --> conservation audit / degradation / economics
```

`ReactorSimulator` is the plant orchestrator, not the source of subsystem physics. Governing models are split across dedicated classes under `sim/` so individual closures can be replaced as fidelity improves.

## Numerical strategy

Rendering is decoupled from the physical solver. The plant uses bounded thermal-hydraulic steps and smaller kinetics substeps. Each plant step performs coupled half-steps to reduce feedback lag across neutronics, core thermal response, RCS hydraulics and the secondary plant. Fast-forward advances many stable physical steps rather than multiplying a single timestep by the speed factor.

The test suite exercises the declared reference state, rod-drive dynamics, neutronic response, SCRAM/decay heat, RCP coastdown, protection behavior, IF97 state recovery, saturation-envelope handling, operator-instrument lag, load-step control recovery, turbine-trip causality, fast-forward positivity/finite-state behavior and long-duration conservation closure.

## Model status and limitations

Critical State is an **engineering-inspired reduced-order entertainment/education simulator**, not a qualified nuclear analysis product. A physically causal model is not the same thing as a validated plant model.

The current implementation intentionally does **not** claim:

- plant-specific safety limits, protection algorithms or operating procedures;
- safety-analysis-grade accident prediction;
- full 3-D neutron diffusion/transport or pin-by-pin depletion;
- RELAP5/TRACE-class two-fluid system thermal hydraulics;
- validated vendor CHF/DNBR correlations;
- proprietary pump, turbine or steam-generator maps;
- explicit surge-line hydraulic momentum;
- general subcooled/superheated two-phase vessel closure outside the declared saturation envelope;
- structural/finite-element fuel or vessel analysis;
- prediction of a specific operating reactor.

Many balance-of-plant coefficients are marked calibration-required or estimated. They are retained as physical coefficients with provenance/status rather than silently tuned gameplay values. DNBR remains intentionally absent until an applicable validated public correlation is selected.

## Water and steam properties

Ordinary-water/steam thermodynamics currently use `com.hummeling:if97:2.0.0`, wrapped behind the project-owned `WaterProperties` interface. This provides IAPWS-IF97 property closure without tying the plant model directly to one library API. See `THIRD_PARTY_NOTICES.md` for licensing information.

## Android build

- compile SDK: 35
- minimum SDK: 23
- target SDK: 30 for the current G-Mee compatibility envelope
- Java: 17
- UI: Android framework views, kept deliberately lightweight for the target phone

GitHub Actions runs the unit suite, assembles a signed release APK, verifies the DEX header and uploads the APK artifact. Pull requests build/test without replacing the public test release; successful pushes to `main` refresh the fixed `gmee-test` release asset.

Direct installed-build URL after a successful `main` build:

`https://github.com/NyerahMT/Critical-State/releases/download/gmee-test/Critical-State-GMee-Test.apk`

### Signing note

The current CI workflow still creates a temporary test keystore for each build. Until a persistent secret-backed signing key is configured, a newly downloaded APK may require uninstalling the previous Critical State build before installation because Android treats different signatures as different update authorities.

## Technical basis

The model-theory basis draws on public references including IAPWS-IF97, U.S. DOE reactor theory and thermal-fluid handbooks, IAEA reactor-kinetics/decay-heat material, MIT BEAVRS, OECD/NEA PSBT, public NRC steam-generator assessments and standard steam-turbine / synchronous-machine relationships.

Critical State is for entertainment and education. It is not qualified for reactor design or modification, licensing or regulatory analysis, operator qualification, real-plant prediction, accident-management decisions, or technical-specification calculations.
