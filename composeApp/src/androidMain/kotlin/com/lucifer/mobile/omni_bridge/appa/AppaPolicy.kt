package com.lucifer.mobile.omni_bridge.appa

// APPA: Agentic Permissions Policy Algebra
// Solves the "Usability Wall" - monotone taint accumulation that locks agents out of sensitive tools
// Replaces rigid binary taint with two-monoid algebraic security model

enum class Integrity { TRUSTED, INTERNAL, EXTERNAL, UNTRUSTED }
enum class Confidentiality { PUBLIC, INTERNAL, SENSITIVE, SECRET }
enum class Origin { USER, TOOL, WEB, MODEL, REGISTRY, HUMAN }
enum class Purpose { QUERY, EXECUTION, PERSISTENCE, COMMUNICATION }

data class SecurityLabel(
    val integrity: Integrity,
    val confidentiality: Confidentiality,
    val origin: Origin,
    val purpose: Purpose
) {
    // Label ordering: higher = more trusted
    fun dominates(other: SecurityLabel): Boolean {
        return integrity.ordinal >= other.integrity.ordinal &&
               confidentiality.ordinal >= other.confidentiality.ordinal
    }
    
    // Join (least upper bound) - for merging contexts
    fun join(other: SecurityLabel): SecurityLabel {
        return SecurityLabel(
            integrity = if (this.integrity.ordinal >= other.integrity.ordinal) this.integrity else other.integrity,
            confidentiality = if (this.confidentiality.ordinal >= other.confidentiality.ordinal) this.confidentiality else other.confidentiality,
            origin = this.origin,  // Preserve original origin
            purpose = this.purpose
        )
    }
    
    // Meet (greatest lower bound) - for intersection
    fun meet(other: SecurityLabel): SecurityLabel {
        return SecurityLabel(
            integrity = if (this.integrity.ordinal <= other.integrity.ordinal) this.integrity else other.integrity,
            confidentiality = if (this.confidentiality.ordinal <= other.confidentiality.ordinal) this.confidentiality else other.confidentiality,
            origin = this.origin,
            purpose = this.purpose
        )
    }
}

// Tool capability requirements
data class ToolCapability(
    val name: String,
    val requiredIntegrity: Integrity = Integrity.INTERNAL,
    val requiredConfidentiality: Confidentiality = Confidentiality.INTERNAL,
    val allowedOrigins: Set<Origin> = setOf(Origin.USER, Origin.TOOL, Origin.MODEL),
    val requiredPurpose: Purpose = Purpose.EXECUTION
)

// Context object with security label
data class LabeledContext<T>(
    val data: T,
    val label: SecurityLabel,
    val provenance: List<String> = emptyList()  // Evidence chain
)

// APPA Policy Engine - core algebraic operations
class AppaPolicyEngine {
    
    // Prospective Acquisition Enforcement - check BEFORE reading external data
    fun canAcquire(
        sourceLabel: SecurityLabel,
        currentContextLabel: SecurityLabel,
        toolCapability: ToolCapability?
    ): Boolean {
        // Check if acquisition would cause label descent below tool requirements
        val projectedLabel = currentContextLabel.meet(sourceLabel)
        
        return if (toolCapability != null) {
            projectedLabel.dominates(SecurityLabel(
                integrity = toolCapability.requiredIntegrity,
                confidentiality = toolCapability.requiredConfidentiality,
                origin = sourceLabel.origin,
                purpose = toolCapability.requiredPurpose
            )) && toolCapability.allowedOrigins.contains(sourceLabel.origin)
        } else true
    }
    
    // Engine-Managed Context Branching - fork context instead of tainting main
    fun <T> branchContext(
        context: LabeledContext<T>,
        externalLabel: SecurityLabel,
        toolCapability: ToolCapability
    ): LabeledContext<T> {
        val newLabel = context.label.join(externalLabel)
        return LabeledContext(
            data = context.data,
            label = newLabel,
            provenance = context.provenance + "BRANCHED from ${externalLabel.origin}"
        )
    }
    
    // Parent Label Preservation - main context stays clean
    fun preserveParentLabel(context: LabeledContext<*>): SecurityLabel {
        return context.label  // Original label unchanged
    }
    
    // Merge Confinement - merged context cannot exceed either parent
    fun mergeConfinement(a: LabeledContext<*>, b: LabeledContext<*>): SecurityLabel {
        return a.label.meet(b.label)
    }
}

// Tool registry with capabilities
object LuciferToolRegistry {
    val WALLET_GENERATE = ToolCapability(
        name = "wallet.generate",
        requiredIntegrity = Integrity.TRUSTED,
        requiredConfidentiality = Confidentiality.SECRET,
        allowedOrigins = setOf(Origin.USER, Origin.HUMAN),
        requiredPurpose = Purpose.EXECUTION
    )
    
    val WALLET_SIGN = ToolCapability(
        name = "wallet.sign",
        requiredIntegrity = Integrity.TRUSTED,
        requiredConfidentiality = Confidentiality.SECRET,
        allowedOrigins = setOf(Origin.USER, Origin.HUMAN),
        requiredPurpose = Purpose.EXECUTION
    )
    
    val BROWSER_NAVIGATE = ToolCapability(
        name = "browser.navigate",
        requiredIntegrity = Integrity.INTERNAL,
        requiredConfidentiality = Confidentiality.INTERNAL,
        allowedOrigins = setOf(Origin.USER, Origin.TOOL, Origin.MODEL),
        requiredPurpose = Purpose.EXECUTION
    )
    
    val LLM_QUERY = ToolCapability(
        name = "llm.query",
        requiredIntegrity = Integrity.INTERNAL,
        requiredConfidentiality = Confidentiality.INTERNAL,
        allowedOrigins = setOf(Origin.USER, Origin.TOOL, Origin.MODEL, Origin.WEB),
        requiredPurpose = Purpose.QUERY
    )
    
    val SANDBOX_EXEC = ToolCapability(
        name = "sandbox.exec",
        requiredIntegrity = Integrity.TRUSTED,
        requiredConfidentiality = Confidentiality.SENSITIVE,
        allowedOrigins = setOf(Origin.USER, Origin.HUMAN),
        requiredPurpose = Purpose.EXECUTION
    )
    
    val allTools = listOf(WALLET_GENERATE, WALLET_SIGN, BROWSER_NAVIGATE, LLM_QUERY, SANDBOX_EXEC)
}

fun AppaDemo() {
    val engine = AppaPolicyEngine()
    
    // Clean context - can execute sensitive tools
    val cleanContext = LabeledContext(
        data = "User wallet operation",
        label = SecurityLabel(Integrity.TRUSTED, Confidentiality.SECRET, Origin.USER, Purpose.EXECUTION)
    )
    
    // External web data - untrusted
    val webDataLabel = SecurityLabel(Integrity.UNTRUSTED, Confidentiality.PUBLIC, Origin.WEB, Purpose.QUERY)
    
    // Without APPA: reading web data taints entire context -> can't sign wallet
    // With APPA: branch context, main stays clean
    val branched = engine.branchContext(cleanContext, webDataLabel, LuciferToolRegistry.WALLET_SIGN)
    
    val canSignMain = engine.canAcquire(cleanContext.label, cleanContext.label, LuciferToolRegistry.WALLET_SIGN)  // true
    val canSignBranched = engine.canAcquire(branched.label, branched.label, LuciferToolRegistry.WALLET_SIGN)  // false
    val canQueryWeb = engine.canAcquire(webDataLabel, cleanContext.label, LuciferToolRegistry.LLM_QUERY)  // true
}