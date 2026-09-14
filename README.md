# Critical State

**Critical State** is a mobile-first, reduced-order pressurized-water-reactor simulation game by NyerahWorks.

The goal is not to reproduce a specific operating plant or provide operator training. The goal is to make the plant behave causally: reactor power comes from neutronics, heat moves through stored thermal states, primary pressure now emerges from a conserved pressurizer state, and later systems will extend that chain through explicit primary-loop hydraulics, steam generators, turbine, condenser, feedwater, electrical systems, degradation, and economics.

## Current prototype

The Android slice now includes:

- six-group point kinetics with implicit integration;
- an S-shaped generic control-bank worth model;
- fuel and moderator temperature feedback;
- lumped fuel/coolant energy storage;
- a reduced secondary heat-removal response;
- turbine load demand;
- a fixed-volume, two-phase pressurizer with conserved mass and internal energy;
- primary thermal-expansion surge coupling;
- automatic proportional pressurizer heater and spray response;
- IAPWS-IF97 water/steam properties through a replaceable property adapter;
- reactor trip/reset;
- 1x, 10x, and 60x simulation speed;
- model-status labels that distinguish reference structure from estimated/calibration-required scaffolding;
- unit tests for steady-state behavior, rod-withdrawal response, and pressurizer surge direction;
- GitHub Actions builds that test and produce a G-Mee-compatible APK.

## Model basis

Development follows the latest **NYERAH Reactor Simulator — Mathematical Models and Physical Basis, Revision 0.1 (27 August 2026)** model-theory specification.

Core rule:

> Simplification may reduce spatial resolution or component detail, but it shall not replace a physical causal relationship with an arbitrary gameplay mapping.

The initial reference scale is a generic four-loop commercial PWR anchored where appropriate to public benchmark data. Estimated or calibration-required parameters are labeled as such in code and are not treated as validated plant data.

The pressurizer is intentionally a control-oriented equilibrium model rather than a spatial two-fluid model. Vessel mass and internal energy are state variables; pressure and level are recovered from a saturated IF97 equilibrium at fixed vessel volume. Primary coolant thermal expansion changes pressurizer inventory through a reduced surge-line boundary. This boundary is designed to be replaced when explicit primary-loop hydraulic control volumes are added.

## Architecture

```text
Android UI / instrumentation
        ↓
Simulation state
        ↓
Neutronics → thermal nodes → pressurizer / pressure control
        ↓
Future: explicit RCS hydraulics / SG / turbine / condenser / feedwater / grid
        ↓
Future: degradation / maintenance / economics / events
```

Rendering and gameplay UI must not silently change the governing physics. Simulator state and solver code live separately from instrumentation. Thermodynamic properties are also isolated behind a small interface so the underlying IF97 implementation can be replaced without rewriting plant models.

## Build

The project compiles against Android API 35 while retaining the current G-Mee compatibility target of API 30. The UI uses Android framework views to minimize runtime/dependency overhead on the target device.

GitHub Actions runs unit tests and builds `app-release.apk`. Pull requests build and test without replacing the direct-download release; pushes to `main` also refresh the `gmee-test` release asset.

The current IF97 implementation is the Hummeling Java library, version 2.0.0, under the GNU LGPL. See `THIRD_PARTY_NOTICES.md`.

## Status

Early prototype. Several constants in the neutronics, thermal, primary inventory, surge-line, and controller slices are intentionally marked estimated or calibration-required. Primary pressure is no longer a fixed display value, but the primary coolant system is still represented by a lumped thermal node plus a reduced inventory boundary rather than explicit hot/cold-leg hydraulic control volumes.

This software is for entertainment and education. It is not qualified for reactor design, licensing analysis, operator qualification, plant prediction, accident management, or safety-limit calculations.
