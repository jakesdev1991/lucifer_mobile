package com.lucifer.mobile.omni_bridge.atoms

// ATOMS: Abilities in Theory of Mind Space
// 7-State Psychological Profile Generator for obscure/ambiguous prompts
// Projects prompt intent into continuous psychological embeddings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.Math.log

// 7 Mental States
enum class MentalState {
    BELIEFS,       // What user assumes to be true
    INTENTIONS,    // Explicit or implicit goals
    DESIRES,       // What user wants/values
    EMOTIONS,      // Affective state
    KNOWLEDGE,     // What user knows/don't know
    PERCEPTS,      // Sensory/observational input
    NON_LITERAL    // Metaphor, sarcasm, implied meaning
}

data class AtomsProfile(
    val beliefs: MentalHypothesis,
    val intentions: MentalHypothesis,
    val desires: MentalHypothesis,
    val emotions: MentalHypothesis,
    val knowledge: MentalHypothesis,
    val percepts: MentalHypothesis,
    val nonLiteral: MentalHypothesis,
    val compositeEmbedding: FloatArray,  // 7-dimensional continuous embedding
    val confidence: Float
)

data class MentalHypothesis(
    val state: MentalState,
    val hypothesis: String,
    val probability: Float,  // 0.0 - 1.0
    val evidence: List<String>,
    val embedding: FloatArray  // Dimensional embedding for this state
)

data class MultiOrderIntentionality(
    val order: Int,  // 1st order: "User wants X", 2nd: "User believes S knows X", 3rd: "User wants S to believe they know X"
    val description: String,
    val confidence: Float
)

// Theory of Mind Agent (ToMA)
class TheoryOfMindAgent {
    
    // 7-State Vectorization
    fun vectorize(prompt: String): AtomsProfile {
        val lowerPrompt = prompt.lowercase()
        
        val beliefs = analyzeBeliefs(lowerPrompt)
        val intentions = analyzeIntentions(lowerPrompt)
        val desires = analyzeDesires(lowerPrompt)
        val emotions = analyzeEmotions(lowerPrompt)
        val knowledge = analyzeKnowledge(lowerPrompt)
        val percepts = analyzePercepts(lowerPrompt)
        val nonLiteral = analyzeNonLiteral(lowerPrompt)
        
        // Build composite embedding (7D: one dimension per mental state)
        val composite = floatArrayOf(
            beliefs.probability,
            intentions.probability,
            desires.probability,
            emotions.probability,
            knowledge.probability,
            percepts.probability,
            nonLiteral.probability
        )
        
        val confidence = composite.average().toFloat()
        
        return AtomsProfile(
            beliefs = beliefs,
            intentions = intentions,
            desires = desires,
            emotions = emotions,
            knowledge = knowledge,
            percepts = percepts,
            nonLiteral = nonLiteral,
            compositeEmbedding = composite,
            confidence = confidence
        )
    }
    
    private fun analyzeBeliefs(prompt: String): MentalHypothesis {
        val indicators = listOf("I think", "I believe", "assuming", "suppose", "must be", "probably", "likely", "obviously")
        val hypothesis = when {
            indicators.any { prompt.contains(it) } -> "User holds explicit assumptions about system state"
            prompt.contains("should") -> "User believes system ought to behave in specific way"
            else -> "User's beliefs unclear from prompt"
        }
        val prob = indicators.count { prompt.contains(it) } / indicators.size.toFloat()
        
        return MentalHypothesis(
            state = MentalState.BELIEFS,
            hypothesis = hypothesis,
            probability = prob.coerceIn(0.1f, 1.0f),
            evidence = indicators.filter { prompt.contains(it) },
            embedding = floatArrayOf(prob, 0f, 0f, 0f, 0f, 0f, 0f)
        )
    }
    
    private fun analyzeIntentions(prompt: String): MentalHypothesis {
        val indicators = listOf("want to", "need to", "trying to", "goal", "objective", "aim", "trying", "want", "need")
        val hypothesis = when {
            indicators.any { prompt.contains(it) } -> "User has explicit goal-directed intention"
            prompt.contains("how to") -> "User intends to learn procedure"
            prompt.contains("fix") || prompt.contains("solve") -> "User intends to resolve problem"
            else -> "User's intention is exploratory or ambiguous"
        }
        val prob = indicators.count { prompt.contains(it) } / indicators.size.toFloat()
        
        return MentalHypothesis(
            state = MentalState.INTENTIONS,
            hypothesis = hypothesis,
            probability = prob.coerceIn(0.1f, 1.0f),
            evidence = indicators.filter { prompt.contains(it) },
            embedding = floatArrayOf(0f, prob, 0f, 0f, 0f, 0f, 0f)
        )
    }
    
    private fun analyzeDesires(prompt: String): MentalHypothesis {
        val indicators = listOf("would like", "prefer", "hope", "wish", "ideally", "best", "better", "love", "hate")
        val hypothesis = when {
            indicators.any { prompt.contains(it) } -> "User expresses preference or value judgment"
            prompt.contains("better") -> "User desires improvement over current state"
            else -> "User's desires not explicitly stated"
        }
        val prob = indicators.count { prompt.contains(it) } / indicators.size.toFloat()
        
        return MentalHypothesis(
            state = MentalState.DESIRES,
            hypothesis = hypothesis,
            probability = prob.coerceIn(0.1f, 1.0f),
            evidence = indicators.filter { prompt.contains(it) },
            embedding = floatArrayOf(0f, 0f, prob, 0f, 0f, 0f, 0f)
        )
    }
    
    private fun analyzeEmotions(prompt: String): MentalHypothesis {
        val indicators = listOf("frustrated", "angry", "confused", "excited", "worried", "anxious", "happy", "sad", "annoyed", "stressed")
        val hypothesis = when {
            indicators.any { prompt.contains(it) } -> "User expressing clear emotional state"
            prompt.contains("!!") || prompt.contains("??") -> "User showing emotional intensity"
            else -> "User's emotional state neutral or unstated"
        }
        val prob = indicators.count { prompt.contains(it) } / indicators.size.toFloat()
        
        return MentalHypothesis(
            state = MentalState.EMOTIONS,
            hypothesis = hypothesis,
            probability = prob.coerceIn(0.05f, 1.0f),
            evidence = indicators.filter { prompt.contains(it) },
            embedding = floatArrayOf(0f, 0f, 0f, prob, 0f, 0f, 0f)
        )
    }
    
    private fun analyzeKnowledge(prompt: String): MentalHypothesis {
        val indicators = listOf("I know", "I don't know", "aware", "unaware", "familiar", "expert", "beginner", "new to", "learning")
        val hypothesis = when {
            indicators.any { prompt.contains(it) } -> "User explicitly references their knowledge state"
            prompt.contains("explain") -> "User acknowledges knowledge gap"
            prompt.contains("obviously") || prompt.contains("clearly") -> "User assumes knowledge"
            else -> "User's knowledge state implicit"
        }
        val prob = indicators.count { prompt.contains(it) } / indicators.size.toFloat()
        
        return MentalHypothesis(
            state = MentalState.KNOWLEDGE,
            hypothesis = hypothesis,
            probability = prob.coerceIn(0.1f, 1.0f),
            evidence = indicators.filter { prompt.contains(it) },
            embedding = floatArrayOf(0f, 0f, 0f, 0f, prob, 0f, 0f)
        )
    }
    
    private fun analyzePercepts(prompt: String): MentalHypothesis {
        val indicators = listOf("see", "saw", "observe", "noticed", "looks like", "appears", "seems", "shows", "displays")
        val hypothesis = when {
            indicators.any { prompt.contains(it) } -> "User referencing perceptual observation"
            prompt.contains("screenshot") || prompt.contains("log") -> "User providing perceptual evidence"
            else -> "No perceptual references in prompt"
        }
        val prob = indicators.count { prompt.contains(it) } / indicators.size.toFloat()
        
        return MentalHypothesis(
            state = MentalState.PERCEPTS,
            hypothesis = hypothesis,
            probability = prob.coerceIn(0.05f, 1.0f),
            evidence = indicators.filter { prompt.contains(it) },
            embedding = floatArrayOf(0f, 0f, 0f, 0f, 0f, prob, 0f)
        )
    }
    
    private fun analyzeNonLiteral(prompt: String): MentalHypothesis {
        val indicators = listOf("literally", "figuratively", "metaphor", "ironic", "sarcastic", "joking", "as if", "like a")
        val hypothesis = when {
            indicators.any { prompt.contains(it) } -> "User using non-literal communication"
            prompt.contains("😂") || prompt.contains("😉") || prompt.contains("/s") -> "User signaling non-literal intent"
            else -> "Communication appears literal"
        }
        val prob = indicators.count { prompt.contains(it) } / indicators.size.toFloat()
        
        return MentalHypothesis(
            state = MentalState.NON_LITERAL,
            hypothesis = hypothesis,
            probability = prob.coerceIn(0.05f, 1.0f),
            evidence = indicators.filter { prompt.contains(it) },
            embedding = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, prob)
        )
    }
    
    // Multi-Order Intentionality
    fun inferIntentionality(profile: AtomsProfile): List<MultiOrderIntentionality> {
        val orders = mutableListOf<MultiOrderIntentionality>()
        
        // 1st order: User wants X
        if (profile.intentions.probability > 0.5f) {
            orders.add(MultiOrderIntentionality(
                order = 1,
                description = "User intends to ${profile.intentions.hypothesis}",
                confidence = profile.intentions.probability
            ))
        }
        
        // 2nd order: User believes system knows X
        if (profile.beliefs.probability > 0.5f && profile.knowledge.probability > 0.3f) {
            orders.add(MultiOrderIntentionality(
                order = 2,
                description = "User believes the system knows ${profile.beliefs.hypothesis}",
                confidence = (profile.beliefs.probability + profile.knowledge.probability) / 2
            ))
        }
        
        // 3rd order: User wants system to believe they know X
        if (profile.desires.probability > 0.4f && profile.nonLiteral.probability > 0.3f) {
            orders.add(MultiOrderIntentionality(
                order = 3,
                description = "User wants system to believe they understand ${profile.desires.hypothesis}",
                confidence = (profile.desires.probability + profile.nonLiteral.probability) / 2
            ))
        }
        
        return orders
    }
}

// Active Inference & Variational Free Energy Minimization (ODAR)
class OdarActiveInference {
    
    data class FreeEnergy(
        val expectedFreeEnergy: Float,  // G = E[log p(o|s)] - H[p(s|o)]
        val risk: Float,                // Expected cost
        val ambiguity: Float,           // Expected entropy
        val preferredOutcomes: FloatArray
    )
    
    // Minimize variational free energy for decision-making under uncertainty
    fun minimizeFreeEnergy(
        observations: FloatArray,
        priorBeliefs: FloatArray,
        possibleActions: List<FloatArray>
    ): Int {
        var bestAction = 0
        var minFreeEnergy = Float.MAX_VALUE
        
        for ((i, action) in possibleActions.withIndex()) {
            // G = Risk + Ambiguity
            val risk = computeRisk(action, observations)
            val ambiguity = computeAmbiguity(action, priorBeliefs)
            val freeEnergy = risk + ambiguity
            
            if (freeEnergy < minFreeEnergy) {
                minFreeEnergy = freeEnergy
                bestAction = i
            }
        }
        
        return bestAction
    }
    
    private fun computeRisk(action: FloatArray, observations: FloatArray): Float {
        // Expected cost: -E[log p(o|s)]
        var risk = 0f
        for (i in action.indices) {
            val diff = action[i] - observations[i]
            risk += diff * diff
        }
        return risk / action.size
    }
    
    private fun computeAmbiguity(action: FloatArray, prior: FloatArray): Float {
        // Expected entropy: H[p(s|o)]
        var ambiguity = 0f
        for (i in action.indices) {
            val p = (action[i] + prior[i]) / 2
            if (p > 0) ambiguity += -p * log(p.toDouble()).toFloat()
        }
        return ambiguity / action.size
    }
}