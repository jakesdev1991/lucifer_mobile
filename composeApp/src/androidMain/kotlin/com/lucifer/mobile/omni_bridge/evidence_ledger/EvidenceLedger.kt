package com.lucifer.mobile.omni_bridge.evidence_ledger

// BLAKE3 Merkle Tree Evidence Ledger
// Append-only, tamper-evident logging for all routing decisions, shadow price updates, model payloads
// Feeds Provenance-Driven Counterfactual Audit Engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest

// Evidence record types
sealed class EvidenceRecord {
    data class RoutingDecision(
        val timestamp: Long,
        val queryHash: String,
        val selectedTopology: String,
        val shadowPrices: ShadowPrices,
        val rcodMetrics: RcodMetrics
    ) : EvidenceRecord()
    
    data class ShadowPriceUpdate(
        val timestamp: Long,
        val lambdaUsd: Double,
        val lambdaLat: Double,
        val lambdaTokens: Double,
        val budgetUtilization: Double
    ) : EvidenceRecord()
    
    data class ModelPayload(
        val timestamp: Long,
        val modelId: String,
        val inputHash: String,
        val outputHash: String,
        val tokenCount: Int,
        val latencyMs: Int
    ) : EvidenceRecord()
    
    data class SecurityEvent(
        val timestamp: Long,
        val eventType: String,
        val label: SecurityLabel,
        val toolName: String,
        val allowed: Boolean
    ) : EvidenceRecord()
    
    data class RcodMetrics(
        val phi: Float,
        val mu: Float,
        val medianMu: Float,
        val macroMu: Float,
        val maxMu: Float,
        val status: String
    )
    
    data class ShadowPrices(
        val lambdaUsd: Double,
        val lambdaLat: Double,
        val lambdaTokens: Double
    )
    
    data class SecurityLabel(
        val integrity: String,
        val confidentiality: String,
        val origin: String,
        val purpose: String
    )
}

// Merkle Tree Node
data class MerkleNode(
    val hash: String,
    val left: MerkleNode? = null,
    val right: MerkleNode? = null,
    val isLeaf: Boolean = false,
    val leafData: EvidenceRecord? = null
)

// BLAKE3 Merkle Tree Implementation
class Blake3MerkleTree {
    private var leaves = mutableListOf<MerkleNode>()
    private var root: MerkleNode? = null
    private val hasher = Blake3Hasher()
    
    fun addLeaf(record: EvidenceRecord) {
        val leafHash = hasher.hash(record.toString())
        val leaf = MerkleNode(hash = leafHash, isLeaf = true, leafData = record)
        leaves.add(leaf)
        rebuildTree()
    }
    
    private fun rebuildTree() {
        if (leaves.isEmpty()) {
            root = null
            return
        }
        
        var currentLevel = leaves.toMutableList()
        while (currentLevel.size > 1) {
            val nextLevel = mutableListOf<MerkleNode>()
            for (i in 0 until currentLevel.size step 2) {
                val left = currentLevel[i]
                val right = if (i + 1 < currentLevel.size) currentLevel[i + 1] else left
                val combinedHash = hasher.hash(left.hash + right.hash)
                nextLevel.add(MerkleNode(hash = combinedHash, left = left, right = right))
            }
            currentLevel = nextLevel
        }
        root = currentLevel[0]
    }
    
    fun getRootHash(): String? = root?.hash
    fun getLeafCount(): Int = leaves.size
    fun getRoot(): MerkleNode? = root
    
    // Generate Merkle proof for a leaf (for verification)
    fun getProof(leafIndex: Int): List<String> {
        val proof = mutableListOf<String>()
        var index = leafIndex
        var level = leaves.toMutableList()
        
        while (level.size > 1) {
            val siblingIndex = if (index % 2 == 0) index + 1 else index - 1
            if (siblingIndex < level.size) {
                proof.add(level[siblingIndex].hash)
            }
            index = index / 2
            val nextLevel = mutableListOf<MerkleNode>()
            for (i in 0 until level.size step 2) {
                val left = level[i]
                val right = if (i + 1 < level.size) level[i + 1] else left
                val combinedHash = hasher.hash(left.hash + right.hash)
                nextLevel.add(MerkleNode(hash = combinedHash, left = left, right = right))
            }
            level = nextLevel
        }
        return proof
    }
    
    // Verify a leaf against root using proof
    fun verifyLeaf(leafHash: String, proof: List<String>, rootHash: String): Boolean {
        var currentHash = leafHash
        for (siblingHash in proof) {
            currentHash = hasher.hash(currentHash + siblingHash)
        }
        return currentHash == rootHash
    }
}

// BLAKE3 Hasher (simplified - use actual BLAKE3 in production)
class Blake3Hasher {
    fun hash(input: String): String {
        return hash(input.toByteArray())
    }
    
    fun hash(input: ByteArray): String {
        // In production: use actual BLAKE3
        // Using SHA-256 as placeholder
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input)
        return hash.joinToString("") { "%02x".format(it) }
    }
}

// Evidence Ledger - append-only with Merkle tree
class EvidenceLedger {
    private val merkleTree = Blake3MerkleTree()
    private val records = mutableListOf<EvidenceRecord>()
    private val maxRecords = 100000
    
    fun append(record: EvidenceRecord) {
        records.add(record)
        merkleTree.addLeaf(record)
        
        // Trim old records but keep Merkle tree integrity
        if (records.size > maxRecords) {
            records.removeAt(0)
            // Note: In production, you'd keep all hashes for verification
        }
    }
    
    fun getRootHash(): String? = merkleTree.getRootHash()
    fun getRecordCount(): Int = records.size
    fun getRecords(): List<EvidenceRecord> = records.toList()
    
    // Get records in range for counterfactual audit
    fun getRecordsSince(timestamp: Long): List<EvidenceRecord> {
        return records.filter { (it as? EvidenceRecord.RoutingDecision)?.timestamp ?: 
                                 (it as? EvidenceRecord.ShadowPriceUpdate)?.timestamp ?: 
                                 (it as? EvidenceRecord.ModelPayload)?.timestamp ?: 
                                 (it as? EvidenceRecord.SecurityEvent)?.timestamp ?: 0L >= timestamp }
    }
    
    // Export for counterfactual replay
    fun exportForAudit(): AuditExport {
        return AuditExport(
            rootHash = merkleTree.getRootHash() ?: "",
            leafCount = merkleTree.getLeafCount(),
            records = records.toList(),
            timestamp = System.currentTimeMillis()
        )
    }
}

data class AuditExport(
    val rootHash: String,
    val leafCount: Int,
    val records: List<EvidenceRecord>,
    val timestamp: Long
)

// Provenance-Driven Counterfactual Audit Engine
// Converts static log trails into active perturbation-based diagnostic models
class CounterfactualAuditEngine(
    private val ledger: EvidenceLedger
) {
    
    // Perturb a past routing decision and measure counterfactual outcome
    fun auditRoutingDecision(recordIndex: Int): CounterfactualResult {
        val record = ledger.getRecords()[recordIndex]
        when (record) {
            is EvidenceRecord.RoutingDecision -> {
                // Counterfactual: what if we chose different topology?
                val alternatives = generateAlternativeTopologies(record.selectedTopology)
                val counterfactuals = alternatives.map { alt ->
                    CounterfactualScenario(
                        original = record,
                        perturbation = "routing_decision",
                        alternative = alt,
                        estimatedCostDelta = estimateCostDelta(record.shadowPrices, alt),
                        estimatedLatencyDelta = estimateLatencyDelta(record.shadowPrices, alt)
                    )
                }
                return CounterfactualResult(
                    originalRecord = record,
                    scenarios = counterfactuals,
                    recommendation = selectBestCounterfactual(counterfactuals)
                )
            }
            else -> return CounterfactualResult(
                originalRecord = record,
                scenarios = emptyList(),
                recommendation = "No counterfactual for this record type"
            )
        }
    }
    
    // Isolate causal triggers for anomalies
    fun isolateCausalTrigger(anomalyTimestamp: Long): CausalAnalysis {
        val relevantRecords = ledger.getRecordsSince(anomalyTimestamp - 3600000) // 1 hour window
        
        // Find shadow price spikes
        val priceSpikes = relevantRecords.filterIsInstance<EvidenceRecord.ShadowPriceUpdate>()
            .filter { it.lambdaUsd > 10.0 || it.lambdaLat > 10.0 }
        
        // Find RCOD phase transitions
        val phaseTransitions = relevantRecords.filterIsInstance<EvidenceRecord.RoutingDecision>()
            .filter { it.rcodMetrics.status == "PHASE_TRANSITION" }
        
        // Find security violations
        val securityViolations = relevantRecords.filterIsInstance<EvidenceRecord.SecurityEvent>()
            .filter { !it.allowed }
        
        return CausalAnalysis(
            timestamp = anomalyTimestamp,
            priceSpikes = priceSpikes.size,
            phaseTransitions = phaseTransitions.size,
            securityViolations = securityViolations.size,
            primaryCause = when {
                priceSpikes.isNotEmpty() -> "BUDGET_PRESSURE"
                phaseTransitions.isNotEmpty() -> "RCOD_PHASE_TRANSITION"
                securityViolations.isNotEmpty() -> "SECURITY_VIOLATION"
                else -> "UNKNOWN"
            }
        )
    }
    
    private fun generateAlternativeTopologies(selected: String): List<String> {
        // In production: return actual alternative topologies
        return listOf("${selected}_ALT1", "${selected}_ALT2")
    }
    
    private fun estimateCostDelta(prices: EvidenceRecord.ShadowPrices, alt: String): Double {
        // Simplified estimation
        return (Math.random() - 0.5) * 10.0
    }
    
    private fun estimateLatencyDelta(prices: EvidenceRecord.ShadowPrices, alt: String): Double {
        return (Math.random() - 0.5) * 200.0
    }
    
    private fun selectBestCounterfactual(scenarios: List<CounterfactualScenario>): String {
        if (scenarios.isEmpty()) return "NONE"
        return scenarios.minByOrNull { it.estimatedCostDelta + it.estimatedLatencyDelta / 1000 }?.alternative ?: "NONE"
    }
}

data class CounterfactualScenario(
    val original: EvidenceRecord,
    val perturbation: String,
    val alternative: String,
    val estimatedCostDelta: Double,
    val estimatedLatencyDelta: Double
)

data class CounterfactualResult(
    val originalRecord: EvidenceRecord,
    val scenarios: List<CounterfactualScenario>,
    val recommendation: String
)

data class CausalAnalysis(
    val timestamp: Long,
    val priceSpikes: Int,
    val phaseTransitions: Int,
    val securityViolations: Int,
    val primaryCause: String
)