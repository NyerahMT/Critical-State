package com.nyerahworks.criticalstate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.isTraceInProgress
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nyerahworks.criticalstate.sim.PlantState
import com.nyerahworks.criticalstate.sim.ReactorSimulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CriticalStateTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CriticalStateScreen()
                }
            }
        }
    }
}

private val CriticalStateColors = darkColorScheme(
    primary = Color(0xFFE7B84B),
    onPrimary = Color(0xFF16130B),
    secondary = Color(0xFF9CB7C8),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF111820),
    surfaceVariant = Color(0xFF18212B),
    onBackground = Color(0xFFE8EDF2),
    onSurface = Color(0xFFE8EDF2),
    onSurfaceVariant = Color(0xFFAEBBC6),
    error = Color(0xFFFF7A70),
)

@Composable
private fun CriticalStateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CriticalStateColors,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CriticalStateScreen() {
    val simulator = remember { ReactorSimulator() }
    var state by remember { mutableStateOf(simulator.snapshot()) }
    var running by remember { mutableStateOf(true) }
    var timeScale by remember { mutableStateOf(1.0) }

    LaunchedEffect(running, timeScale) {
        if (!running) return@LaunchedEffect
        while (isActive) {
            state = simulator.advance(wallSeconds = 0.05, timeScale = timeScale)
            delay(50)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Header(state)
            }

            item {
                MetricGrid(state)
            }

            item {
                ControlCard(
                    state = state,
                    running = running,
                    timeScale = timeScale,
                    onRunningChange = { running = it },
                    onTimeScaleChange = { timeScale = it },
                    onRodChange = {
                        simulator.setRodInsertion(it)
                        state = simulator.snapshot()
                    },
                    onTurbineLoadChange = {
                        simulator.setTurbineLoad(it)
                        state = simulator.snapshot()
                    },
                    onTrip = {
                        simulator.trip()
                        state = simulator.snapshot()
                    },
                    onReset = {
                        simulator.reset()
                        state = simulator.snapshot()
                    },
                )
            }

            item {
                ModelStatusCard(state)
            }

            item {
                Text(
                    text = "GENERIC REDUCED-ORDER PWR • ENTERTAINMENT / EDUCATION • NOT OPERATOR TRAINING",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(state: PlantState) {
    val status = when {
        state.tripped -> "REACTOR TRIP"
        state.fissionPowerMw < ReactorSimulator.REFERENCE_THERMAL_POWER_MW * 0.02 -> "SUBCRITICAL / LOW POWER"
        else -> "AT POWER"
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "CRITICAL STATE",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            letterSpacing = MaterialTheme.typography.headlineMedium.letterSpacing,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.titleMedium,
            color = if (state.tripped) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "SIM T+${formatDuration(state.simulationSeconds)}",
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetricGrid(state: PlantState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricCard(
                label = "REACTOR POWER",
                value = String.format(Locale.US, "%.1f %%", state.fissionPowerMw / ReactorSimulator.REFERENCE_THERMAL_POWER_MW * 100.0),
                detail = String.format(Locale.US, "%.0f MWth", state.fissionPowerMw),
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "GENERATOR",
                value = String.format(Locale.US, "%.0f MW", state.generatorPowerMw),
                detail = String.format(Locale.US, "Load %.0f %%", state.turbineLoad * 100.0),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricCard(
                label = "COOLANT",
                value = String.format(Locale.US, "%.1f K", state.coolantTemperatureK),
                detail = String.format(Locale.US, "Fuel %.0f K", state.fuelTemperatureK),
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "PRIMARY",
                value = String.format(Locale.US, "%.2f MPa", state.primaryPressureMpa),
                detail = "Pressurizer model deferred",
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricCard(
                label = "REACTIVITY",
                value = String.format(Locale.US, "%+.1f pcm", state.totalReactivityPcm),
                detail = "Rod + temperature feedback",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "PERIOD",
                value = state.reactorPeriodSeconds?.let {
                    if (abs(it) > 9999.0) "> 9999 s" else String.format(Locale.US, "%+.1f s", it)
                } ?: "STABLE",
                detail = "Instantaneous estimate",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ControlCard(
    state: PlantState,
    running: Boolean,
    timeScale: Double,
    onRunningChange: (Boolean) -> Unit,
    onTimeScaleChange: (Double) -> Unit,
    onRodChange: (Double) -> Unit,
    onTurbineLoadChange: (Double) -> Unit,
    onTrip: () -> Unit,
    onReset: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "PLANT CONTROL",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            ControlSlider(
                label = "CONTROL BANK INSERTION",
                valueText = String.format(Locale.US, "%.1f %%", state.rodInsertion * 100.0),
                value = state.rodInsertion.toFloat(),
                range = 0f..1f,
                enabled = !state.tripped,
                onChange = { onRodChange(it.toDouble()) },
            )

            ControlSlider(
                label = "TURBINE LOAD DEMAND",
                valueText = String.format(Locale.US, "%.0f %%", state.turbineLoad * 100.0),
                value = state.turbineLoad.toFloat(),
                range = 0.30f..1.10f,
                enabled = true,
                onChange = { onTurbineLoadChange(it.toDouble()) },
            )

            Text(
                text = "SIMULATION SPEED",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1.0, 10.0, 60.0).forEach { speed ->
                    FilterChip(
                        selected = timeScale == speed,
                        onClick = { onTimeScaleChange(speed) },
                        label = { Text("${speed.toInt()}×") },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { onRunningChange(!running) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (running) "PAUSE" else "RUN")
                }
                Button(
                    onClick = onTrip,
                    enabled = !state.tripped,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text("TRIP")
                }
                Button(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("RESET")
                }
            }
        }
    }
}

@Composable
private fun ControlSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
        )
    }
}

@Composable
private fun ModelStatusCard(state: PlantState) {
    val rows = listOf(
        "Neutronics" to "Six-group point kinetics • implicit update",
        "Rod worth" to "S-curve fallback • calibration required",
        "Thermal" to "Lumped fuel/coolant energy balance • estimated constants",
        "Secondary" to "Reduced heat-removal response • calibration required",
        "Pressure" to "Held at reference state • solver deferred",
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "MODEL STATUS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            rows.forEach { (name, description) ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.diagnostic?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
}
