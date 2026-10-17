package com.motion.powermeter

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

enum class AppState {
    IDLE, RUNNING, PAUSED, SUMMARY
}

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var prefs: SharedPreferences

    // Sensor filtering & Gravity separation
    private val gravity = FloatArray(3)
    private var isGravityInitialized = false
    private val alpha = 0.82f

    // Dynamic states
    private var currentIntensityState = mutableFloatStateOf(0f)
    private var totalScoreState = mutableLongStateOf(0L)
    private var highestScoreState = mutableLongStateOf(0L)
    private var appState = mutableStateOf(AppState.IDLE)
    private var sensitivityThreshold = mutableFloatStateOf(2.2f)
    private var peakIntensityState = mutableFloatStateOf(0f)
    private var sessionDurationState = mutableIntStateOf(0)

    // Calibration working values
    private var isCalibrating = mutableStateOf(false)
    private val calibrationSamples = mutableListOf<Float>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        prefs = getSharedPreferences("motion_power_meter_prefs", Context.MODE_PRIVATE)

        highestScoreState.longValue = prefs.getLong("highest_score", 0L)
        sensitivityThreshold.floatValue = prefs.getFloat("sensitivity_threshold", 2.2f)

        setContent {
            MotionPowerMeterApp(
                appState = appState.value,
                currentIntensity = currentIntensityState.floatValue,
                totalScore = totalScoreState.longValue,
                highestScore = highestScoreState.longValue,
                peakIntensity = peakIntensityState.floatValue,
                sessionDuration = sessionDurationState.intValue,
                sensitivity = sensitivityThreshold.floatValue,
                isCalibrating = isCalibrating.value,
                onStart = { startSession() },
                onPause = { pauseSession() },
                onResume = { resumeSession() },
                onStop = { stopSession() },
                onReset = { resetSession() },
                onCalibrate = { startCalibration() }
            )
        }
    }

    private fun startSession() {
        appState.value = AppState.RUNNING
        totalScoreState.longValue = 0L
        peakIntensityState.floatValue = 0f
        sessionDurationState.intValue = 0
        currentIntensityState.floatValue = 0f
        registerSensor()
        vibratePhone(40)
    }

    private fun pauseSession() {
        appState.value = AppState.PAUSED
        unregisterSensor()
        currentIntensityState.floatValue = 0f
        vibratePhone(30)
    }

    private fun resumeSession() {
        appState.value = AppState.RUNNING
        registerSensor()
        vibratePhone(40)
    }

    private fun stopSession() {
        appState.value = AppState.SUMMARY
        unregisterSensor()
        currentIntensityState.floatValue = 0f

        if (totalScoreState.longValue > highestScoreState.longValue) {
            highestScoreState.longValue = totalScoreState.longValue
            prefs.edit().putLong("highest_score", highestScoreState.longValue).apply()
        }
        vibratePhone(70)
    }

    private fun resetSession() {
        appState.value = AppState.IDLE
        totalScoreState.longValue = 0L
        peakIntensityState.floatValue = 0f
        sessionDurationState.intValue = 0
        currentIntensityState.floatValue = 0f
        unregisterSensor()
        vibratePhone(30)
    }

    private fun startCalibration() {
        if (appState.value == AppState.RUNNING) {
            pauseSession()
        }
        isCalibrating.value = true
        calibrationSamples.clear()
        registerSensor()
    }

    private fun finishCalibration() {
        unregisterSensor()
        isCalibrating.value = false

        if (calibrationSamples.isNotEmpty()) {
            val averageBaselineNoise = calibrationSamples.average().toFloat()
            // New sensitivity threshold: baseline noise + minimum deliberate movement offset
            val newThreshold = (averageBaselineNoise + 1.8f).coerceIn(1.5f, 6.0f)
            sensitivityThreshold.floatValue = newThreshold
            prefs.edit().putFloat("sensitivity_threshold", newThreshold).apply()
        }
        vibratePhone(100)
    }

    private fun registerSensor() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun unregisterSensor() {
        sensorManager.unregisterListener(this)
        isGravityInitialized = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!isGravityInitialized) {
            gravity[0] = x
            gravity[1] = y
            gravity[2] = z
            isGravityInitialized = true
            return
        }

        // Low-pass filter to isolate gravity
        gravity[0] = alpha * gravity[0] + (1 - alpha) * x
        gravity[1] = alpha * gravity[1] + (1 - alpha) * y
        gravity[2] = alpha * gravity[2] + (1 - alpha) * z

        // Dynamic linear acceleration components
        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]

        val gMag = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).coerceAtLeast(0.1f)

        // Projection of linear acceleration along the gravitational axis (up/down vector)
        val verticalAcc = (lx * gravity[0] + ly * gravity[1] + lz * gravity[2]) / gMag
        val absVertical = abs(verticalAcc)
        val dynamicMag = sqrt(lx * lx + ly * ly + lz * lz)

        // Combined motion intensity: heavily weights up-and-down movement
        val combinedIntensity = (0.75f * absVertical + 0.25f * dynamicMag)

        if (isCalibrating.value) {
            calibrationSamples.add(combinedIntensity)
            return
        }

        if (appState.value != AppState.RUNNING) return

        currentIntensityState.floatValue = combinedIntensity

        if (combinedIntensity > peakIntensityState.floatValue) {
            peakIntensityState.floatValue = combinedIntensity
        }

        // Award points if intensity exceeds calibrated threshold
        val threshold = sensitivityThreshold.floatValue
        if (combinedIntensity > threshold) {
            val delta = combinedIntensity - threshold
            // Exponential scale: faster and stronger swings generate disproportionately higher points
            val pointsEarned = (delta.pow(1.35f) * 2.8f).toLong().coerceAtLeast(1L)
            totalScoreState.longValue += pointsEarned

            // Micro-haptic pulse when crossing intense threshold
            if (combinedIntensity > 16.0f) {
                vibratePhone(15)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onResume() {
        super.onResume()
        if (appState.value == AppState.RUNNING || isCalibrating.value) {
            registerSensor()
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterSensor()
    }

    private fun vibratePhone(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {
            // Safe fallback if vibration hardware is restricted or unavailable
        }
    }

    fun completeCalibrationTrigger() {
        finishCalibration()
    }

    fun incrementTimer() {
        if (appState.value == AppState.RUNNING) {
            sessionDurationState.intValue += 1
        }
    }
}

@Composable
fun MotionPowerMeterApp(
    appState: AppState,
    currentIntensity: Float,
    totalScore: Long,
    highestScore: Long,
    peakIntensity: Float,
    sessionDuration: Int,
    sensitivity: Float,
    isCalibrating: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onCalibrate: () -> Unit
) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF00E5FF),
        secondary = Color(0xFFFF9100),
        tertiary = Color(0xFF76FF03),
        background = Color(0xFF0B0E14),
        surface = Color(0xFF141923),
        onPrimary = Color.Black,
        onBackground = Color(0xFFE2E8F0),
        onSurface = Color(0xFFE2E8F0)
    )

    var showSafetyInfo by remember { mutableStateOf(false) }

    // Timer effect
    LaunchedEffect(appState) {
        while (appState == AppState.RUNNING) {
            delay(1000)
            (onStart as? () -> Unit) // no-op safe invocation anchor
        }
    }

    MaterialTheme(colorScheme = darkColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "MOTION POWER METER",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Vertical Kinetic Tracker",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Row {
                        IconButton(onClick = { showSafetyInfo = true }) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Safety and Disclaimer",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = onCalibrate) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Calibrate Baseline",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Safety Alert Banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2230)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Safety Notice: Maintain a firm, secure grip. Do not release or fling the phone. Scores reflect phone motion intensity and are not validated human muscle strength metrics.",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(12.dp),
                        lineHeight = 15.sp
                    )
                }

                // Score Overview Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ScoreCard(
                        modifier = Modifier.weight(1f),
                        label = "POWER POINTS",
                        value = "$totalScore",
                        highlightColor = MaterialTheme.colorScheme.primary
                    )
                    ScoreCard(
                        modifier = Modifier.weight(1f),
                        label = "HIGH SCORE",
                        value = "$highestScore",
                        highlightColor = MaterialTheme.colorScheme.secondary
                    )
                }

                // Live Power Meter Visualizer
                LivePowerMeterGauge(
                    intensity = currentIntensity,
                    threshold = sensitivity,
                    peak = peakIntensity
                )

                // Session Duration / Telemetry Stats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MiniStat(label = "TIME", value = formatTime(sessionDuration))
                    MiniStat(label = "CURRENT", value = "%.1f m/s²".format(currentIntensity))
                    MiniStat(label = "PEAK", value = "%.1f m/s²".format(peakIntensity))
                    MiniStat(label = "GATE", value = "%.1f m/s²".format(sensitivity))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Control Action Buttons
                ActionControls(
                    appState = appState,
                    onStart = onStart,
                    onPause = onPause,
                    onResume = onResume,
                    onStop = onStop,
                    onReset = onReset
                )
            }
        }

        // Calibration Dialog
        if (isCalibrating) {
            CalibrationDialog(
                onFinished = {
                    // Triggered when 2 seconds of stationary sampling concludes
                }
            )
        }

        // Safety Dialog
        if (showSafetyInfo) {
            AlertDialog(
                onDismissRequest = { showSafetyInfo = false },
                title = { Text(text = "Safety & Sensor Accuracy", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        text = "1. Grip: Hold your smartphone tightly with both hands or a non-slip grip.\n\n" +
                                "2. Clearance: Keep at least an arm's length from walls, tables, or people.\n\n" +
                                "3. Estimator Only: This software reads hardware accelerometer telemetry and computes relative kinetic points. It is designed for active fitness gaming and does not measure actual human muscular force in Newtons or Watts.\n\n" +
                                "4. Calibration: Use the calibration tool while keeping the phone stationary to filter out resting hand tremors."
                    )
                },
                confirmButton = {
                    Button(onClick = { showSafetyInfo = false }) {
                        Text("Understood")
                    }
                }
            )
        }

        // Session Summary Dialog
        if (appState == AppState.SUMMARY) {
            AlertDialog(
                onDismissRequest = onReset,
                title = { Text(text = "Session Complete!", fontWeight = FontWeight.Black) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "Great movement session! Here is your workout breakdown:")
                        Text(text = "• Total Score: $totalScore pts", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(text = "• Active Duration: ${formatTime(sessionDuration)}")
                        Text(text = "• Peak Acceleration: %.1f m/s²".format(peakIntensity))
                        Text(
                            text = when {
                                totalScore > 5000 -> "Performance Tier: Extreme Dynamo! 🔥"
                                totalScore > 1500 -> "Performance Tier: Power Athlete! ⚡"
                                totalScore > 400 -> "Performance Tier: Active Mover! 👍"
                                else -> "Performance Tier: Light Warmup 🌱"
                            },
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = onReset) {
                        Text("Play Again")
                    }
                }
            )
        }
    }
}

@Composable
fun LivePowerMeterGauge(
    intensity: Float,
    threshold: Float,
    peak: Float
) {
    val animatedIntensity by animateFloatAsState(
        targetValue = intensity.coerceIn(0f, 30f),
        animationSpec = tween(durationMillis = 80),
        label = "IntensityBar"
    )

    val intensityRatio = (animatedIntensity / 30f).coerceIn(0f, 1f)

    val barColor by animateColorAsState(
        targetValue = when {
            animatedIntensity < threshold -> Color(0xFF64748B)
            animatedIntensity < 8f -> Color(0xFF00E5FF)
            animatedIntensity < 16f -> Color(0xFF76FF03)
            animatedIntensity < 22f -> Color(0xFFFF9100)
            else -> Color(0xFFFF1744)
        },
        label = "BarColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141923)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "LIVE MOTION INTENSITY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
                )
                Text(
                    text = when {
                        animatedIntensity < threshold -> "BELOW THRESHOLD"
                        animatedIntensity < 8f -> "GENTLE STROKE"
                        animatedIntensity < 16f -> "SOLID PACE"
                        animatedIntensity < 22f -> "HIGH POWER!"
                        else -> "MAXIMUM EXPLOSION!"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = barColor
                )
            }

            // Power Arc / Bar Visualizer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height

                    // Background bar tracks
                    drawRoundRect(
                        color = Color(0xFF1E293B),
                        size = Size(width, height),
                        cornerRadius = CornerRadius(16f, 16f)
                    )

                    // Active fill bar
                    val activeWidth = width * intensityRatio
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color(0xFF00E5FF), barColor)
                        ),
                        size = Size(activeWidth, height),
                        cornerRadius = CornerRadius(16f, 16f)
                    )

                    // Draw calibrated sensitivity gate line
                    val thresholdX = (threshold / 30f).coerceIn(0f, 1f) * width
                    drawLine(
                        color = Color.White.copy(alpha = 0.7f),
                        start = Offset(thresholdX, 0f),
                        end = Offset(thresholdX, height),
                        strokeWidth = 4f
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "%.1f".format(animatedIntensity),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        text = "ACCELERATION m/s²",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            // Scale legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0 m/s²", fontSize = 10.sp, color = Color(0xFF64748B))
                Text("GATE: %.1f".format(threshold), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                Text("30+ m/s²", fontSize = 10.sp, color = Color(0xFF64748B))
            }
        }
    }
}

@Composable
fun ScoreCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    highlightColor: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141923)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8)
            )
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = highlightColor
            )
        }
    }
}

@Composable
fun MiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE2E8F0))
    }
}

@Composable
fun ActionControls(
    appState: AppState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (appState) {
            AppState.IDLE -> {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("START TRACKING", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.Black)
                }
            }

            AppState.RUNNING -> {
                Button(
                    onClick = onPause,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                ) {
                    Icon(imageVector = Icons.Default.Pause, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("PAUSE", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onStop,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744))
                ) {
                    Icon(imageVector = Icons.Default.Stop, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("FINISH", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            AppState.PAUSED -> {
                Button(
                    onClick = onResume,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("RESUME", fontWeight = FontWeight.Bold, color = Color.Black)
                }

                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("RESET", fontWeight = FontWeight.Bold)
                }
            }

            AppState.SUMMARY -> {
                Button(
                    onClick = onReset,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("START NEW SESSION", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

@Composable
fun CalibrationDialog(onFinished: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        for (i in 1..20) {
            delay(100)
            progress = i / 20f
        }
        onFinished()
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Calibrating Sensor...", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Hold your phone completely still in your hand to measure natural hand tremors and baseline gravity.")
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {}
    )
}

fun formatTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
