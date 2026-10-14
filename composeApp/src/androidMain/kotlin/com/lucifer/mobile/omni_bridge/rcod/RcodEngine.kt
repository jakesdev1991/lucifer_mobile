package com.lucifer.mobile.omni_bridge.rcod

// RCOD: Reverse Chain Overlap Density
// Detects phase transitions in agent reasoning trajectories
// Mathematical primitives from Omega Protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.acos

data class StateVector(
    val values: FloatArray,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun cosineSimilarity(other: StateVector): Float {
        if (values.size != other.values.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in values.indices) {
            dot += values[i] * other.values[i]
            normA += values[i] * values[i]
            normB += other.values[i] * other.values[i]
        }
        val denom = sqrt(normA * normB)
        return if (denom > 0) dot / denom else 0f
    }
}

// Chain Overlap Density (COD / Φ) - normalized mutual information
// Φ ≈ 1: Perfect consensus/redundancy (vacuum-like stability)
// Φ < 1: Reduced consensus, introducing non-overlapping novelty
data class CodMetrics(
    val phi: Float,  // Chain Overlap Density (0, 1]
    val mu: Float,   // Reverse Chain Overlap Density / Matter Density [0, 1)
    val theta: Float // Angle between states
) {
    val isStable: Boolean get() = phi > 0.95f
    val isNovel: Boolean get() = mu > 0.3f
    val isPhaseTransition: Boolean get() = mu > 0.7f
}

// Stochastic Swarm - multi-scale distributed sensor approach
class StochasticSwarm(
    private val macroWindow: Int = 252,      // Longest window (anchor)
    private val leafCount: Int = 50,         // Number of stochastic leaves
    private val leafMinWindow: Int = 10,     // Shortest leaf window
    private val leafMaxWindow: Int = 60      // Longest leaf window
) {
    
    private val windows = mutableListOf<Int>()
    private val macroMuHistory = mutableListOf<Float>()
    private val medianMuHistory = mutableListOf<Float>()
    private val maxMuHistory = mutableListOf<Float>()
    
    init {
        generateSwarm()
    }
    
    private fun generateSwarm() {
        windows.clear()
        windows.add(macroWindow)
        repeat(leafCount) {
            windows.add(leafMinWindow + (Math.random() * (leafMaxWindow - leafMinWindow)).toInt())
        }
    }
    
    // Step 2: Calculate Local Physics for each window
    fun calculateLocalPhysics(stateHistory: List<StateVector>): List<Float> {
        val muValues = mutableListOf<Float>()
        
        for (w in windows) {
            if (stateHistory.size >= w + 1) {
                val current = stateHistory.takeLast(w)
                val previous = stateHistory.dropLast(w).takeLast(w)
                
                if (current.isNotEmpty() && previous.isNotEmpty()) {
                    val vCurrent = averageVector(current)
                    val vPrevious = averageVector(previous)
                    
                    val phi = vCurrent.cosineSimilarity(vPrevious).coerceIn(0f, 1f)
                    val mu = sqrt(1f - phi * phi)
                    muValues.add(mu)
                }
            }
        }
        return muValues
    }
    
    private fun averageVector(vectors: List<StateVector>): StateVector {
        val size = vectors[0].values.size
        val sum = FloatArray(size)
        for (v in vectors) {
            for (i in sum.indices) {
                sum[i] += v.values[i]
            }
        }
        for (i in sum.indices) {
            sum[i] = sum[i] / vectors.size
        }
        return StateVector(sum)
    }
    
    // Step 3: Calculate Global Swarm Metrics
    fun calculateGlobalMetrics(muValues: List<Float>): SwarmMetrics {
        val sorted = muValues.sorted()
        val medianMu = sorted[sorted.size / 2]
        val macroMu = muValues.firstOrNull() ?: 0f  // Macro anchor is first
        val maxMu = sorted.lastOrNull() ?: 0f
        
        macroMuHistory.add(macroMu)
        medianMuHistory.add(medianMu)
        maxMuHistory.add(maxMu)
        
        // Trim history
        val maxHistory = 1000
        if (macroMuHistory.size > maxHistory) macroMuHistory.removeAt(0)
        if (medianMuHistory.size > maxHistory) medianMuHistory.removeAt(0)
        if (maxMuHistory.size > maxHistory) maxMuHistory.removeAt(0)
        
        return SwarmMetrics(
            medianMu = medianMu,
            macroMu = macroMu,
            maxMu = maxMu,
            vacuumRisk = medianMu < 0.05f,
            preShock = macroMu > 0.4f && medianMu < 0.2f,
            phaseTransition = maxMu > 0.7f
        )
    }
    
    fun getHistory(): SwarmHistory {
        return SwarmHistory(
            macroMu = macroMuHistory.toList(),
            medianMu = medianMuHistory.toList(),
            maxMu = maxMuHistory.toList()
        )
    }
}

data class SwarmMetrics(
    val medianMu: Float,      // Democratic Truth - baseline novelty rate
    val macroMu: Float,       // Anchor Stress - long-horizon structural stress
    val maxMu: Float,         // Outlier Extremum - sharp divergence events
    val vacuumRisk: Boolean,  // μ ≈ 0: Pure redundancy, zero novelty
    val preShock: Boolean,    // Macro μ high but median low: stress building
    val phaseTransition: Boolean // Max μ > 0.7: structural breakdown imminent
)

data class SwarmHistory(
    val macroMu: List<Float>,
    val medianMu: List<Float>,
    val maxMu: List<Float>
)

// Monotonicity Lemma: Global information loss is non-increasing
// d⟨I_global⟩/dt ≤ -c · A_horizon(t)
// Without external grounding, ungrounded agent loops suffer catastrophic collapse
class MonotonicityMonitor {
    private var globalInformation: Float = 1.0f
    private val horizonArea: Float = 1.0f
    private val dissipationConstant: Float = 0.01f
    
    fun update(mu: Float): Float {
        // Information loss proportional to matter density and horizon area
        val loss = dissipationConstant * mu * horizonArea
        globalInformation = (globalInformation - loss).coerceAtLeast(0f)
        return globalInformation
    }
    
    fun getGlobalInformation(): Float = globalInformation
    
    fun isCollapseImminent(): Boolean = globalInformation < 0.1f
}

// RCOD Telemetry integration for real-time monitoring
class RcodTelemetry {
    private val swarm = StochasticSwarm()
    private val monotonicity = MonotonicityMonitor()
    private val stateHistory = mutableListOf<StateVector>()
    private val maxHistory = 500
    
    fun recordState(vector: StateVector) {
        stateHistory.add(vector)
        if (stateHistory.size > maxHistory) stateHistory.removeAt(0)
    }
    
    fun evaluate(): RcodResult {
        val muValues = swarm.calculateLocalPhysics(stateHistory)
        val metrics = swarm.calculateGlobalMetrics(muValues)
        val globalInfo = monotonicity.update(metrics.medianMu)
        
        return RcodResult(
            metrics = metrics,
            globalInformation = globalInfo,
            collapseImminent = monotonicity.isCollapseImminent(),
            timestamp = System.currentTimeMillis()
        )
    }
    
    fun getHistory(): SwarmHistory = swarm.getHistory()
}

data class RcodResult(
    val metrics: SwarmMetrics,
    val globalInformation: Float,
    val collapseImminent: Boolean,
    val timestamp: Long
) {
    val status: String get() = when {
        collapseImminent -> "COLLAPSE_IMMINENT"
        metrics.phaseTransition -> "PHASE_TRANSITION"
        metrics.preShock -> "PRE_SHOCK"
        metrics.vacuumRisk -> "VACUUM_RISK"
        else -> "STABLE"
    }
}

// Example: Convert agent reasoning to state vectors
fun reasoningToStateVector(reasoning: String, embeddings: FloatArray): StateVector {
    // In production: use actual embeddings from model
    // For demo: hash-based pseudo-embedding
    val size = embeddings.size
    val values = FloatArray(size)
    for (i in 0 until size) {
        values[i] = (reasoning.hashCode() * (i + 1).toLong()).toFloat() / 1e9f
    }
    return StateVector(values)
}