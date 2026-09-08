# Critical State

**Critical State** is a mobile-first, reduced-order pressurized-water-reactor simulation game by NyerahWorks.

The goal is not to reproduce a specific operating plant or provide operator training. The goal is to make the plant behave causally: reactor power comes from neutronics, heat moves through stored thermal states, and later systems will extend that chain through the primary loop, steam generators, turbine, condenser, feedwater, electrical systems, degradation, and economics.

## Current prototype

The first Android slice now includes:

- six-group point kinetics with implicit integration;
- an S-shaped generic control-bank worth model;
- fuel and moderator temperature feedback;
- lumped fuel/coolant energy storage;
- reduced secondary heat-removal response;
- turbine load demand;
- reactor trip/reset;
- 1x, 10x, and 60x simulation speed;
- model-status labels that distinguish reference structure from estimated/calibration-required scaffolding;
- unit tests for steady-state behavior and rod-withdrawal response;
- GitHub Actions builds that upload a debug APK on every push.

## Model basis

Development follows the latest **NYERAH Reactor Simulator — Mathematical Models and Physical Basis, Revision 0.1 (27 August 2026)** model-theory specification.

Core rule:

> Simplification may reduce spatial resolution or component detail, but it shall not replace a physical causal relationship with an arbitrary gameplay mapping.

The initial reference scale is a generic four-loop commercial PWR anchored where appropriate to public benchmark data. Estimated or calibration-required parameters are labeled as such in code and are not treated as validated plant data.

## Architecture

```text
Android UI / instrumentation
        ↓
Simulation state
        ↓
Neutronics → thermal nodes → secondary heat removal
        ↓
Future: hydraulics / SG / turbine / condenser / feedwater / grid
        ↓
Future: degradation / maintenance / economics / events
```

Rendering and gameplay UI must not silently change the governing physics. Simulator state and solver code live separately from instrumentation.

## Build

The project targets Android API 36 and uses Jetpack Compose.

GitHub Actions runs unit tests and builds `app-debug.apk`. Open the latest **Android CI** workflow run and download the `Critical-State-debug` artifact.

## Status

Very early prototype. Several constants in the first thermal/secondary slice are intentionally marked estimated or calibration-required. Primary pressure is currently held at the reference state until the pressurizer and primary hydraulic models are implemented.

This software is for entertainment and education. It is not qualified for reactor design, licensing analysis, operator qualification, plant prediction, accident management, or safety-limit calculations.
