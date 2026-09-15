# Critical State 0.5.0 Fidelity Lock

Version `0.5.0-fidelity-lock` closes the first whole-plant target-fidelity pass for Critical State.

## Target

Critical State is a causal, numerically stable reduced-order four-loop PWR intended to reproduce believable whole-plant transients around a generic 3411 MWth / ~1.15 GWe reference point. It does not claim spatial-core, vendor-specific, safety-analysis, or operator-qualification fidelity.

## Acceptance results

The locked acceptance suite covers the following behaviors:

1. **Shutdown worth and SCRAM** — total control-bank worth is 5000 pcm. A SCRAM from the 55% reference insertion follows a finite rod path and inserts about 2004 pcm of raw rod worth by full insertion; prompt fission falls while stored decay heat remains.
2. **Steam-generator feedwater control** — the SG level-controller integral term advances with real simulation time. Both +10% and -10% load steps recover level without reactor trip or breaker opening.
3. **Design-point hold** — 600 s with no operator input stays inside the declared pressure, temperature, SG-level, and net-output bands.
4. **Pressurizer swell/inventory coupling** — the model remains an inventory budget, not a surge-line momentum model. Heat-up increases PZR inventory and cooldown decreases it while pressure and level remain bounded.
5. **Saturation-envelope diagnostics** — PZR, SG secondary, and condenser remain saturation `(m,U)` solvers. Unbracketed or out-of-envelope solutions are explicitly flagged instead of silently accepted.
6. **Existing turbine/header/condenser lag** — the one-step production coupling is retained. Turbine trip gives a continuous steam-flow collapse and evolving secondary state; no tighter coupling was required.
7. **Operator glass** — player-visible process values use the existing instrumentation channels rather than direct solver truth, while diagnostics retain true state.
8. **Peak-factor heat-flux semantics** — the former hot-channel label is now explicitly an estimated peak-factor heat flux. It is not a resolved hot-channel or DNBR calculation.
9. **Decay-heat cleanup** — the dead zero-fraction group was removed. Ten active decay groups remain, with the total stored-energy fraction preserved.
10. **Long-duration/fast-forward validation** — one-RCP trip, all-RCP coastdown/natural circulation, turbine trip, load steps, 60x fast-forward, saturation diagnostics, and conservation closure are exercised in CI.

## Representative validation numbers

### 600 s autonomous full-power hold

| Quantity | t = 0 s | t = 600 s |
| --- | ---: | ---: |
| Primary pressure | 15.51000 MPa | 15.46320 MPa |
| Tavg | 582.441 K | 582.383 K |
| Mean SG level | 0.65000 | 0.65000 |
| Net output | 1115.000 MWe | 1109.853 MWe |
| Mass residual | 0.000 kg | -0.603 kg |
| Energy residual | 0.000 MJ | -1.411 MJ |

No trip occurred and the generator breaker stayed closed.

### Strict ±10% load-step level acceptance at 120 s

| Load command | Final mean SG level | Final error from 0.65 | Peak error | Trip | Breaker opened |
| --- | ---: | ---: | ---: | --- | --- |
| +10% | 0.64943 | 0.00057 | 0.00253 | No | No |
| -10% | 0.64799 | 0.00201 | 0.00226 | No | No |

### 60x nominal hold

| Simulated time | Primary pressure | Tavg | Total primary flow | Mean SG level | Net output | Mass residual | Energy residual | Instantaneous energy residual |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 600 s | 15.46320 MPa | 582.383 K | 17083.8 kg/s | 0.65000 | 1109.85 MWe | -0.608 kg | -1.686 MJ | -0.004 MW |
| 1800 s | 15.46379 MPa | 582.385 K | 17083.7 kg/s | 0.65000 | 1109.87 MWe | -0.614 kg | -1.799 MJ | ~0.000 MW |
| 3600 s | 15.46379 MPa | 582.388 K | 17083.6 kg/s | 0.65000 | 1109.90 MWe | -0.619 kg | -1.326 MJ | +0.001 MW |

The 60x nominal design hold remains inside the declared saturation envelope.

## Known model-envelope limits

Deep shutdown/cooldown transients can leave the saturation-only equilibrium-volume domain. That is intentionally reported rather than hidden.

- **Turbine trip:** condenser reaches the 4 kPa lower pressure envelope and flags `CONDENSER` after roughly 7 s.
- **All RCPs off:** positive natural-circulation flow is maintained, but prolonged cooldown later flags the condenser, then SG secondary volumes, and eventually the pressurizer as the reduced saturation-only assumptions leave their declared domain.

These flags are model-validity diagnostics, not claims of a physical plant failure mode.

## Still outside the fidelity claim

The fidelity lock does not add or validate:

- spatial neutron kinetics or 3-D transport;
- DNBR/CHF prediction;
- RELAP5/TRACE-class two-fluid thermal hydraulics;
- vendor-specific pump, turbine, steam-generator, or protection maps;
- plant-specific operating procedures or setpoints;
- safety-analysis-grade accident behavior;
- explicit surge-line hydraulics;
- subcooled/superheated two-phase vessel closure outside the declared saturation envelope.

The guiding rule remains: **simplify spatial or component detail, never replace causality with an arbitrary gameplay mapping.**
