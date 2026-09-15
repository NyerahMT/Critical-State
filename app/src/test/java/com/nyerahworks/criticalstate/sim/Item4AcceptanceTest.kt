package com.nyerahworks.criticalstate.sim

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 4 locks the existing pressurizer coupling as an inventory/swell budget.
 *
 * There is deliberately no modeled surge-line momentum here.  A heat-up is
 * represented by the RCS liquid inventory falling as the coolant swells, so
 * the complementary pressurizer inventory must rise.  Cooldown reverses that
 * bookkeeping.  This test exercises only the already-existing conserved-mass
 * coupling; it does not add a new hydraulic state or a new control law.
 */
class Item4AcceptanceTest {
    private fun rawLog(text: String) {
        val out = FileOutputStream(FileDescriptor.out)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private data class BudgetRun(
        val label: String,
        val initialInventoryKg: Double,
        val finalInventoryKg: Double,
        val minPressureMpa: Double,
        val maxPressureMpa: Double,
        val minLevel: Double,
        val maxLevel: Double,
        val trace: String,
    )

    @Test
    fun swellInventoryBudgetMovesCorrectDirectionAndStaysBounded() {
        val water = If97WaterProperties()
        val referenceRcsLiquidMassKg = 300_000.0
        val displacementKg = 300.0
        val hotSourceH = water.statePT(
            ReferencePlant.PRIMARY_PRESSURE_MPA,
            ReferencePlant.HOT_LEG_T_K,
        ).enthalpyKjKg
        val coldSourceH = water.statePT(
            ReferencePlant.PRIMARY_PRESSURE_MPA,
            ReferencePlant.COLD_LEG_T_K,
        ).enthalpyKjKg

        fun run(label: String, rcsLiquidMassKg: Double): BudgetRun {
            val pzr = PressurizerModel(water, referenceRcsLiquidMassKg)
            val initial = pzr.snapshot()
            var minPressure = initial.pressureMpa
            var maxPressure = initial.pressureMpa
            var minLevel = initial.levelFraction
            var maxLevel = initial.levelFraction
            val trace = buildString {
                append(String.format(
                    Locale.US,
                    "ITEM4 %s t=%5.1fs PZR_inventory=%.3f kg inventory_transfer=%+.3f kg/s P=%.5f MPa level=%.5f\n",
                    label,
                    0.0,
                    initial.totalMassKg,
                    initial.surgeFlowKgPerS,
                    initial.pressureMpa,
                    initial.levelFraction,
                ))
                repeat(600) { step ->
                    val now = pzr.advance(
                        primaryLiquidMassKg = rcsLiquidMassKg,
                        surgeSourceEnthalpyKjKg = hotSourceH,
                        spraySourceEnthalpyKjKg = coldSourceH,
                        dt = 0.1,
                    )
                    minPressure = minOf(minPressure, now.pressureMpa)
                    maxPressure = maxOf(maxPressure, now.pressureMpa)
                    minLevel = minOf(minLevel, now.levelFraction)
                    maxLevel = maxOf(maxLevel, now.levelFraction)
                    if ((step + 1) % 100 == 0) {
                        append(String.format(
                            Locale.US,
                            "ITEM4 %s t=%5.1fs PZR_inventory=%.3f kg inventory_transfer=%+.3f kg/s P=%.5f MPa level=%.5f\n",
                            label,
                            (step + 1) * 0.1,
                            now.totalMassKg,
                            now.surgeFlowKgPerS,
                            now.pressureMpa,
                            now.levelFraction,
                        ))
                    }
                }
            }
            val final = pzr.snapshot()
            return BudgetRun(
                label = label,
                initialInventoryKg = initial.totalMassKg,
                finalInventoryKg = final.totalMassKg,
                minPressureMpa = minPressure,
                maxPressureMpa = maxPressure,
                minLevel = minLevel,
                maxLevel = maxLevel,
                trace = trace,
            )
        }

        // Thermal swell: less liquid remains in the explicitly modeled RCS
        // control volumes, so the complementary pressurizer inventory rises.
        val heatup = run(
            label = "HEATUP",
            rcsLiquidMassKg = referenceRcsLiquidMassKg - displacementKg,
        )

        // Thermal contraction: more liquid inventory resides in the RCS,
        // therefore pressurizer inventory falls by the same budget logic.
        val cooldown = run(
            label = "COOLDOWN",
            rcsLiquidMassKg = referenceRcsLiquidMassKg + displacementKg,
        )

        val summary = String.format(
            Locale.US,
            "ITEM4-SUMMARY heatup inventory %.3f -> %.3f kg P=[%.5f, %.5f] level=[%.5f, %.5f] | cooldown inventory %.3f -> %.3f kg P=[%.5f, %.5f] level=[%.5f, %.5f]\n",
            heatup.initialInventoryKg,
            heatup.finalInventoryKg,
            heatup.minPressureMpa,
            heatup.maxPressureMpa,
            heatup.minLevel,
            heatup.maxLevel,
            cooldown.initialInventoryKg,
            cooldown.finalInventoryKg,
            cooldown.minPressureMpa,
            cooldown.maxPressureMpa,
            cooldown.minLevel,
            cooldown.maxLevel,
        )
        rawLog(heatup.trace + cooldown.trace + summary)

        assertTrue(
            "heat-up did not increase pressurizer inventory: $summary",
            heatup.finalInventoryKg > heatup.initialInventoryKg,
        )
        assertTrue(
            "cooldown did not decrease pressurizer inventory: $summary",
            cooldown.finalInventoryKg < cooldown.initialInventoryKg,
        )

        // Existing UI/model operating envelope only; this is a boundedness
        // guard, not a new protection setpoint or plant-specific criterion.
        listOf(heatup, cooldown).forEach { result ->
            assertTrue(
                "${result.label} pressure left bounded model operating envelope: $summary",
                result.minPressureMpa >= 13.5 && result.maxPressureMpa <= 17.5,
            )
            assertTrue(
                "${result.label} level left bounded model operating envelope: $summary",
                result.minLevel >= 0.20 && result.maxLevel <= 0.90,
            )
        }
    }
}
