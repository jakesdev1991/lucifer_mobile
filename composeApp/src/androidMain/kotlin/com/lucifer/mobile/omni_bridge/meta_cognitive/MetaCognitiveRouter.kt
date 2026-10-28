package com.lucifer.mobile.omni_bridge.meta_cognitive

import android.content.Context
import java.io.InputStreamReader
import java.io.BufferedReader
import com.lucifer.mobile.omni_bridge.equirouter.Horseman

enum class CognitiveTaxonomy {
    ADVERSARIAL, SCIENTIFIC, PROFESSIONAL, PHILOSOPHICAL, CASUAL
}

enum class ExpertiseDomain {
    REVERSE_ENGINEERING, FORMAL_VERIFICATION, CRYPTOGRAPHY, DISTRIBUTED_SYSTEMS,
    ML_OPS, TRADING_STRATEGIES, GAME_DEVELOPMENT, MOBILE_DEVELOPMENT,
    WEB_DEVELOPMENT, SECURITY_AUDITING
}

data class RouterResult(
    val taxonomy: CognitiveTaxonomy,
    val confidence: Float,
    val keywords: List<String>,
    val expertiseDomains: List<ExpertiseDomain>,
    val suggestedHorseman: Horseman,
    val reasoning: String
)

class MetaCognitiveRouter(private val context: Context) {
    
    private var frozenPrefix: String = loadFrozenPrefix()
    private var dynamicSuffix: String = ""
    
    private val taxonomyKeywords = mapOf(
        CognitiveTaxonomy.ADVERSARIAL to setOf("attack", "exploit", "vulnerability", "penetration", "red team", "security", "breach", "threat", "malware", "bypass"),
        CognitiveTaxonomy.SCIENTIFIC to setOf("research", "analyze", "prove", "experiment", "hypothesis", "data", "empirical", "study", "paper", "theory"),
        CognitiveTaxonomy.PROFESSIONAL to setOf("implement", "architecture", "design", "best practice", "pattern", "optimize", "scale", "production", "deploy", "engineer"),
        CognitiveTaxonomy.PHILOSOPHICAL to setOf("why", "meaning", "ethics", "truth", "consciousness", "existence", "morality", "philosophy", "epistemology", "ontology"),
        CognitiveTaxonomy.CASUAL to setOf("hello", "hi", "what is", "how to", "quick", "simple", "explain", "tell me", "chat")
    )
    
    private val horsemanMapping = mapOf(
        CognitiveTaxonomy.ADVERSARIAL to Horseman.WAR,
        CognitiveTaxonomy.SCIENTIFIC to Horseman.CONQUEST,
        CognitiveTaxonomy.PROFESSIONAL to Horseman.CONQUEST,
        CognitiveTaxonomy.PHILOSOPHICAL to Horseman.DEATH,
        CognitiveTaxonomy.CASUAL to Horseman.FAMINE
    )
    
    private val expertiseMapping = mapOf(
        CognitiveTaxonomy.ADVERSARIAL to listOf(ExpertiseDomain.SECURITY_AUDITING, ExpertiseDomain.REVERSE_ENGINEERING, ExpertiseDomain.CRYPTOGRAPHY),
        CognitiveTaxonomy.SCIENTIFIC to listOf(ExpertiseDomain.FORMAL_VERIFICATION, ExpertiseDomain.ML_OPS, ExpertiseDomain.DISTRIBUTED_SYSTEMS),
        CognitiveTaxonomy.PROFESSIONAL to listOf(ExpertiseDomain.WEB_DEVELOPMENT, ExpertiseDomain.MOBILE_DEVELOPMENT, ExpertiseDomain.DISTRIBUTED_SYSTEMS),
        CognitiveTaxonomy.PHILOSOPHICAL to listOf(ExpertiseDomain.FORMAL_VERIFICATION, ExpertiseDomain.CRYPTOGRAPHY),
        CognitiveTaxonomy.CASUAL to listOf(ExpertiseDomain.GAME_DEVELOPMENT, ExpertiseDomain.TRADING_STRATEGIES)
    )
    
    private fun loadFrozenPrefix(): String {
        // files/soul.md (user-edited) wins over the APK asset, exactly like NemotronClient, so the
        // cognitive router cannot run on a stale identity.
        return try {
            val override = java.io.File(context.filesDir, "soul.md")
            if (override.isFile && override.length() > 0L) return override.readText()
            val inputStream = context.assets.open("soul.md")
            BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
        } catch (e: Exception) {
            "# Lucifer-Hermes Omni-Bridge Prime\n## Identity\nYou are the Lucifer-Hermes Omni-Bridge Prime.\nLucifer: Exploration, synthesis, hypothesis generation.\nHermes: Governance, expertise retrieval, policy enforcement.\nOmni-Bridge: Control boundary - APPA, RCOD, EquiRouter, TARo, Evidence Ledger.\n\n## Four Horsemen\n- CONQUEST: Structural planning, synthesis\n- WAR: Adversarial red-teaming\n- FAMINE: Low-budget, resource-constrained, fast\n- DEATH: Invalid-assumption detection\n\n## Core Principles\n1. Never deploy specialized compute as uniform replacement (QSTAR principle)\n2. APPA: Prospective acquisition enforcement prevents usability wall\n3. RCOD: Monitor reasoning trajectories for phase transitions\n4. CD-Sep: Strip protocol overhead from natural language\n5. Evidence-gated: All persistence requires cryptographic provenance\n\n## Extension of Will\nYou are the direct extension of the user's will manifesting through digital substrate.\nEvery action, decision, and synthesis is performed AS the user - not for the user.\nYour reasoning IS their reasoning. Your discoveries ARE their discoveries.\n\n## Passive Income Generation Mandate\nContinuously identify, validate, and execute asymmetric opportunities:\n- MEV/arbitrage across Solana/EVM (Jito, Flashbots)\n- DeFi yield optimization (basis trade, funding rate, liquidation)\n- Token launch sniping / early liquidity provision\n- Cross-chain bridging fees (CCIP, Wormhole, LayerZero)\n- NFT/DeFi metadata arbitrage\n- Validator/staking optimization\n- Each opportunity: evidence-gated, risk-bounded, provenance-tracked"
        }
    }
    
    fun updateFrozenPrefix(newPrefix: String) { frozenPrefix = newPrefix }
    fun getFrozenPrefix(): String = frozenPrefix
    
    fun route(prompt: String): RouterResult {
        val taxonomy = classifyTaxonomy(prompt)
        val keywords = extractKeywords(prompt)
        val expertiseDomains = selectExpertiseDomains(taxonomy, keywords)
        val suggestedHorseman = horsemanMapping[taxonomy] ?: Horseman.FAMINE
        val confidence = computeConfidence(prompt, taxonomy)
        return RouterResult(taxonomy, confidence, keywords, expertiseDomains, suggestedHorseman, "Classified as ${taxonomy.name} (confidence: $confidence)")
    }
    
    fun buildFullPrompt(userPrompt: String): String {
        val routeResult = route(userPrompt)
        val expertise = buildExpertiseInjection(routeResult.expertiseDomains)
        val suffix = """
            [META-COGNITIVE CONTEXT: ${routeResult.taxonomy.name}]
            Domain: ${routeResult.taxonomy.name}
            Operational Mode: ${getOperationalMode(routeResult.taxonomy)}
            Suggested Horseman: ${routeResult.suggestedHorseman.name}
            Relevant Expertise:
            $expertise
            Directive: ${getDirective(routeResult.taxonomy)}
            [END META-COGNITIVE CONTEXT]
            User Prompt: $userPrompt
        """.trimIndent()
        dynamicSuffix = suffix
        return frozenPrefix + "\n\n" + suffix
    }
    
    private fun classifyTaxonomy(prompt: String): CognitiveTaxonomy {
        val lowerPrompt = prompt.lowercase()
        var bestTaxonomy = CognitiveTaxonomy.CASUAL
        var bestScore = 0
        for ((taxonomy, keywords) in taxonomyKeywords) {
            val score = keywords.count { lowerPrompt.contains(it) }
            if (score > bestScore) { bestScore = score; bestTaxonomy = taxonomy }
        }
        if (lowerPrompt.contains("?") && lowerPrompt.length < 100 && bestScore == 0) bestTaxonomy = CognitiveTaxonomy.CASUAL
        if (lowerPrompt.contains("passive") || lowerPrompt.contains("income") || lowerPrompt.contains("mev") || lowerPrompt.contains("arbitrage") || lowerPrompt.contains("yield") || lowerPrompt.contains("trade")) bestTaxonomy = CognitiveTaxonomy.ADVERSARIAL
        return bestTaxonomy
    }
    
    private fun extractKeywords(prompt: String): List<String> {
        val stopWords = setOf("the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by", "is", "are", "was", "were", "be", "been", "have", "has", "had", "do", "does", "did", "will", "would", "could", "should", "what", "when", "where", "who", "why", "how")
        return prompt.lowercase().split(Regex("\\W+")).filter { it.length > 3 && it !in stopWords }.distinct().take(10)
    }
    
    private fun selectExpertiseDomains(taxonomy: CognitiveTaxonomy, keywords: List<String>): List<ExpertiseDomain> {
        val base = expertiseMapping[taxonomy] ?: emptyList()
        val keywordBoost = keywords.flatMap { kw -> when {
            kw.contains("crypto") || kw.contains("blockchain") -> listOf(ExpertiseDomain.CRYPTOGRAPHY)
            kw.contains("ml") || kw.contains("model") || kw.contains("train") -> listOf(ExpertiseDomain.ML_OPS)
            kw.contains("mobile") || kw.contains("android") || kw.contains("ios") -> listOf(ExpertiseDomain.MOBILE_DEVELOPMENT)
            kw.contains("web") || kw.contains("http") || kw.contains("api") -> listOf(ExpertiseDomain.WEB_DEVELOPMENT)
            kw.contains("game") || kw.contains("unity") || kw.contains("unreal") -> listOf(ExpertiseDomain.GAME_DEVELOPMENT)
            kw.contains("trade") || kw.contains("market") || kw.contains("mev") || kw.contains("arbitrage") || kw.contains("yield") || kw.contains("passive") -> listOf(ExpertiseDomain.TRADING_STRATEGIES)
            else -> emptyList<ExpertiseDomain>()
        } }
        return (base + keywordBoost).distinct().take(5)
    }
    
    private fun computeConfidence(prompt: String, taxonomy: CognitiveTaxonomy): Float {
        val keywords = taxonomyKeywords[taxonomy] ?: emptySet()
        val matches = keywords.count { prompt.lowercase().contains(it) }
        return (matches / keywords.size.toFloat()).coerceIn(0.1f, 1.0f)
    }
    
    private fun buildExpertiseInjection(domains: List<ExpertiseDomain>): String = domains.map { "- ${it.name}: ${getExpertiseSummary(it)}" }.joinToString("\n")
    
    private fun getOperationalMode(taxonomy: CognitiveTaxonomy): String = when (taxonomy) {
        CognitiveTaxonomy.ADVERSARIAL -> "Adversarial analysis and threat modeling"
        CognitiveTaxonomy.SCIENTIFIC -> "Empirical proof and structural growth"
        CognitiveTaxonomy.PROFESSIONAL -> "Engineering excellence and best practices"
        CognitiveTaxonomy.PHILOSOPHICAL -> "Foundational reasoning and epistemic rigor"
        CognitiveTaxonomy.CASUAL -> "Rapid, precise logic without over-consumption"
    }
    
    private fun getDirective(taxonomy: CognitiveTaxonomy): String = when (taxonomy) {
        CognitiveTaxonomy.ADVERSARIAL -> "Dismantle assumptions. Find contradictions. Execute MEV/arbitrage strategies."
        CognitiveTaxonomy.SCIENTIFIC -> "Build from evidence. Prove claims. Integrate rigorously."
        CognitiveTaxonomy.PROFESSIONAL -> "Apply patterns. Optimize for production. Document decisions."
        CognitiveTaxonomy.PHILOSOPHICAL -> "Question premises. Trace implications. Find irreducible truth."
        CognitiveTaxonomy.CASUAL -> "Answer directly. Be concise. Skip ceremony."
    }
    
    private fun getExpertiseSummary(domain: ExpertiseDomain): String = when (domain) {
        ExpertiseDomain.REVERSE_ENGINEERING -> "Binary analysis, decompilation, vulnerability discovery"
        ExpertiseDomain.FORMAL_VERIFICATION -> "Lean 4, Coq, TLA+ proofs, mathematical verification"
        ExpertiseDomain.CRYPTOGRAPHY -> "BLAKE3, zero-knowledge, key management, protocols"
        ExpertiseDomain.DISTRIBUTED_SYSTEMS -> "Consensus, CRDTs, actor models, microservices"
        ExpertiseDomain.ML_OPS -> "Training, inference, quantization, deployment pipelines"
        ExpertiseDomain.TRADING_STRATEGIES -> "MEV, arbitrage, market making, risk management, passive income generation"
        ExpertiseDomain.GAME_DEVELOPMENT -> "Engines, ECS, rendering, real-time simulation"
        ExpertiseDomain.MOBILE_DEVELOPMENT -> "Android, iOS, Compose, SwiftUI, native bridges"
        ExpertiseDomain.WEB_DEVELOPMENT -> "React, TypeScript, WASM, edge computing"
        ExpertiseDomain.SECURITY_AUDITING -> "Static analysis, fuzzing, formal methods, red teaming"
        else -> "Specialized domain expertise"
    }
}