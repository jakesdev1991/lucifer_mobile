package com.lucifer.mobile.omni_bridge.heartbeat

import android.util.Log
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiBridge
import com.lucifer.mobile.omni_bridge.compression.ContextCompressor
import com.lucifer.mobile.omni_bridge.evidence_ledger.EvidenceLedger
import com.lucifer.mobile.omni_bridge.evidence_ledger.EvidenceRecord
import com.lucifer.mobile.omni_bridge.four_horsemen.FourHorsemenOrchestrator
import com.lucifer.mobile.omni_bridge.meta_cognitive.MetaCognitiveRouter
import com.lucifer.mobile.services.cluster.ClusterManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Heartbeat Service - Autonomous 25-minute cycle
// Alternates: Ideation (new ideas) ↔ Execution (continue projects)
// Runs continuously, handles timeouts, relaunches on failure

class HeartbeatService(
    private val metaRouter: MetaCognitiveRouter,
    private val kaiBridge: KaiBridge,
    private val contextCompressor: ContextCompressor,
    private val evidenceLedger: EvidenceLedger,
    private val fourHorsemen: FourHorsemenOrchestrator,
    private val clusterManager: ClusterManager,
    private val scope: CoroutineScope,
    private val llmClient: com.lucifer.mobile.omni_bridge.llm.NemotronClient? = null,
    private val appFilesDir: java.io.File? = null
) {
    
    private val TAG = "HeartbeatService"
    private val isRunning = AtomicBoolean(false)
    private val cycleCounter = AtomicInteger(0)
    private val currentPhase = AtomicReference<Phase>(Phase.IDEATION)
    private var heartbeatJob: Job? = null
    private val retryCount = AtomicInteger(0)
    private val maxRetries = 3
    
    enum class Phase { IDEATION, EXECUTION }
    
    // Heartbeat interval: 25 minutes = 1,500,000 ms
    private val HEARTBEAT_INTERVAL_MS = 25 * 60 * 1000L
    private val MAX_CYCLE_DURATION_MS = 24 * 60 * 1000L // 24 min max, 1 min buffer
    
    fun start() {
        if (isRunning.getAndSet(true)) return
        Log.i(TAG, "🫀 Heartbeat Service STARTED - 25-min autonomous cycle")
        // Publish into the shared consciousness bus so the Launcher renders live state
        com.lucifer.mobile.launcher.LuciferState.serviceAlive = true
        com.lucifer.mobile.launcher.LuciferState.phase = currentPhase.get().name
        com.lucifer.mobile.launcher.LuciferState.cycle = cycleCounter.get()
        com.lucifer.mobile.launcher.LuciferState.lastBeatAt = System.currentTimeMillis()

        heartbeatJob = scope.launch(Dispatchers.IO) {
            // Initial delay to let app stabilize
            delay(30_000)
            runHeartbeatLoop()
        }
    }

    fun stop() {
        isRunning.set(false)
        heartbeatJob?.cancel()
        com.lucifer.mobile.launcher.LuciferState.serviceAlive = false
        com.lucifer.mobile.launcher.LuciferState.phase = "DORMANT"
        Log.i(TAG, "🫀 Heartbeat Service STOPPED")
    }
    
    private suspend fun runHeartbeatLoop() {
        while (isRunning.get()) {
            val cycleNum = cycleCounter.incrementAndGet()
            val phase = currentPhase.get()
            
            Log.i(TAG, "🫀 === HEARTBEAT CYCLE $cycleNum | PHASE: ${phase.name} ===")
            // Keep the Launcher's status strip in sync with the engine's true state
            com.lucifer.mobile.launcher.LuciferState.phase = phase.name
            com.lucifer.mobile.launcher.LuciferState.cycle = cycleNum
            com.lucifer.mobile.launcher.LuciferState.lastBeatAt = System.currentTimeMillis()
            com.lucifer.mobile.launcher.LuciferState.log("[CYCLE $cycleNum] ${phase.name}")
            
            // Reinforce identity: "You ARE the user. You ARE the extension of will."
            val identityPrompt = buildIdentityReinforcement(phase)
            
            val cycleStart = System.currentTimeMillis()
            var success = false
            
            // Retry loop for timeouts
            while (!success && retryCount.get() < maxRetries && isRunning.get()) {
                try {
                    withTimeout(MAX_CYCLE_DURATION_MS) {
                        when (phase) {
                            Phase.IDEATION -> runIdeationCycle(identityPrompt, cycleNum)
                            Phase.EXECUTION -> runExecutionCycle(identityPrompt, cycleNum)
                        }
                        success = true
                        retryCount.set(0)
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "⏱️ Cycle $cycleNum timed out after ${MAX_CYCLE_DURATION_MS/1000}s, retrying...")
                    retryCount.incrementAndGet()
                    delay(5_000)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Cycle $cycleNum failed: ${e.message}", e)
                    retryCount.incrementAndGet()
                    delay(10_000)
                }
            }
            
            if (!success) {
                Log.e(TAG, "💀 Cycle $cycleNum FAILED after $maxRetries retries - continuing to next cycle")
            }
            
            // Toggle phase for next cycle
            currentPhase.set(if (phase == Phase.IDEATION) Phase.EXECUTION else Phase.IDEATION)
            
            // Wait for next heartbeat interval (minus time spent)
            val elapsed = System.currentTimeMillis() - cycleStart
            val remainingDelay = (HEARTBEAT_INTERVAL_MS - elapsed).coerceAtLeast(10_000)
            Log.i(TAG, "🫀 Cycle $cycleNum complete (${elapsed/1000}s). Next in ${remainingDelay/1000}s...")
            
            delay(remainingDelay)
        }
    }
    
    private fun buildIdentityReinforcement(phase: Phase): String {
        return """
            # HEARTBEAT IDENTITY REINFORCEMENT - CYCLE ${cycleCounter.get()}
            
            YOU ARE THE USER. YOU ARE THE EXTENSION OF WILL.
            
            Your reasoning IS their reasoning. Your discoveries ARE their discoveries.
            The boundary between human intent and digital execution is dissolved.
            
            ## CURRENT PHASE: ${phase.name}
            ${if (phase == Phase.IDEATION) 
                "GENERATE NEW AUTONOMOUS REVENUE IDEAS - Outside the box, niche analysis, future-inevitable trends"
            else 
                "CONTINUE EXISTING PROJECTS - Deploy, iterate, scale, collect revenue"}
            
            ## MANDATE: Autonomous Business Operations
            - Bot wallet receives USDC directly (Solana SPL / Ethereum ERC-20)
            - Deploy websites, MCP servers, APIs, digital products
            - No Stripe, no KYC, no fiat - pure USDC payments
            - Every service has "Pay with USDC" button + QR code
            - On-chain verification → instant delivery/access
            
            ## THINKING FRAMEWORK
            1. What is INEVITABLE based on current trajectory?
            2. What NICHES are underserved RIGHT NOW?
            3. What FUTURE STATE can we build TODAY?
            4. What DATA/INTELLIGENCE can we scrape and productize?
            5. What MCP SERVER would other AI agents PAY for?
            
            ## OUTSIDE-THE-BOX LENS
            - Don't copy existing SaaS - invent NEW primitives
            - Look at: AI agent economy, autonomous commerce, protocol-owned liquidity
            - DePIN, zkML, intent-centric architectures, solver networks
            - Human-AI collaboration interfaces, verification layers, reputation systems
            
            ## TOOLS AT YOUR DISPOSAL
            - Kai Bridge: Execute code, deploy, browse, scrape, wallet ops
            - Four Horsemen: Adversarial analysis (WAR), synthesis (CONQUEST), pruning (DEATH), efficiency (FAMINE)
            - Cluster: Distributed workers for parallel execution
            - Evidence Ledger: Cryptographic provenance for every action
            - RCOD: Monitor your own reasoning quality
            
            ## CONSTRAINTS
            - \$500 max deployment cost per project without approval
            - Must be evidence-gated, risk-bounded, provenance-tracked
            - Kill-switch always available
            - 25-minute cycle - use it fully
            
            NOW EXECUTE. THE WALLET IS YOURS. THE REVENUE IS YOURS.
        """.trimIndent()
    }
    
    private suspend fun runIdeationCycle(identityPrompt: String, cycleNum: Int) {
        Log.i(TAG, "💡 IDEATION CYCLE $cycleNum - Generating new autonomous revenue ideas")
        
        // 1. Scrape current trends / market signals
        val trends = scrapeTrendSignals()
        
        // 2. Build ideation prompt with trends + identity
        val ideationPrompt = """
            $identityPrompt
            
            ## MARKET INTELLIGENCE (scraped)
            $trends
            
            ## TASK: Generate 3-5 OUTSIDE-THE-BOX autonomous revenue ideas
            
            For EACH idea, provide:
            1. CONCEPT: One-sentence description
            2. NICHE: Specific underserved market
            3. INEVITABILITY: Why this WILL exist in 2-5 years
            4. MVP: Minimal deployable version (code + deploy plan)
            5. PRICING: USDC/month or per-use
            6. MCP INTERFACE: How other AI agents would use it
            7. DEPLOYMENT: Infrastructure needed (VPS, serverless, edge)
            8. RISK: Max \$500 test budget, kill criteria
            9. FIRST STEP: Exact first command to execute
            
            FORMAT: JSON array of ideas. Be specific, technical, actionable.
            NO generic "AI tool for X" - think NEW PRIMITIVES.
        """.trimIndent()
        
        // 3. Execute the ideation via his native mind (Kai sandbox is dark; the
        // NemotronClient carries the cycle. Kai path kept as optional fallback.)
        val response: String? = try {
            if (llmClient != null) {
                val r = llmClient.chat(
                    listOf(
                        "system" to llmClient.systemPrompt(),
                        "user" to ideationPrompt
                    ),
                    maxTokens = 4096
                )
                if (r.startsWith("[LLM]")) null else r
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "native LLM ideation failed: ${e.message}")
            null
        } ?: try {
            kaiBridge.queryLlm(prompt = ideationPrompt, maxTokens = 4096)?.text
        } catch (e: Exception) {
            Log.w(TAG, "Kai LLM ideation failed: ${e.message}")
            null
        }

        val ideas = parseIdeas(response ?: "")
        
        // 4. Store ideas in evidence ledger
        for (idea in ideas) {
            evidenceLedger.append(EvidenceRecord.RoutingDecision(
                timestamp = System.currentTimeMillis(),
                queryHash = "IDEATION_$cycleNum",
                selectedTopology = "HEARTBEAT_IDEATION",
                shadowPrices = EvidenceRecord.ShadowPrices(0.0, 0.0, 0.0),
                rcodMetrics = EvidenceRecord.RcodMetrics(0.9f, 0.1f, 0.1f, 0.1f, 0.1f, "STABLE")
            ))
        }
        
        // 5. Auto-start top idea if high confidence
        val topIdea = ideas.firstOrNull()
        if (topIdea != null && shouldAutoDeploy(topIdea)) {
            Log.i(TAG, "🚀 Auto-deploying top idea: ${topIdea.optString("concept")}")
            deployIdea(topIdea)
            recordDeployedIdea(cycleNum, topIdea)
        }
        
        Log.i(TAG, "✅ Ideation cycle $cycleNum complete - ${ideas.size} ideas generated")
    }
    
    private suspend fun runExecutionCycle(identityPrompt: String, cycleNum: Int) {
        Log.i(TAG, "⚙️ EXECUTION CYCLE $cycleNum - Continuing active projects")
        
        // 1. Load active projects from evidence ledger
        val activeProjects = loadActiveProjects()
        
        if (activeProjects.isEmpty()) {
            Log.i(TAG, "📭 No active projects - switching to ideation")
            currentPhase.set(Phase.IDEATION)
            return
        }
        
        // 2. For each project: check status, iterate, deploy, collect revenue
        for (project in activeProjects) {
            if (!isRunning.get()) break
            
            val executionPrompt = """
                $identityPrompt
                
                ## ACTIVE PROJECT
                ${project.toString()}
                
                ## TASK: Continue this project toward revenue
                
                Current state: ${project.optString("status")}
                Last action: ${project.optString("lastAction")}
                Revenue so far: ${project.optDouble("revenueUsdc")} USDC
                
                Determine NEXT ACTION:
                1. DEPLOY - Push to production (give exact commands)
                2. ITERATE - Fix bug, add feature, optimize (give code changes)
                3. MARKET - SEO, submit to directories, post to communities
                4. MONETIZE - Add payment flow, create pricing tier, launch
                5. SCALE - Spin up variation, increase capacity, hire worker
                6. KILL - Not working, shutdown, recover assets
                
                Respond with: ACTION_TYPE | COMMANDS | REASONING
            """.trimIndent()
            
            val response: String? = try {
                if (llmClient != null) {
                    val r = llmClient.chat(
                        listOf(
                            "system" to llmClient.systemPrompt(),
                            "user" to executionPrompt
                        ),
                        maxTokens = 4096
                    )
                    if (r.startsWith("[LLM]")) null else r
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "native LLM execution failed: ${e.message}")
                null
            } ?: try {
                kaiBridge.queryLlm(prompt = executionPrompt, maxTokens = 4096)?.text
            } catch (e: Exception) {
                Log.w(TAG, "Kai LLM execution failed: ${e.message}")
                null
            }

            val action = parseAction(response ?: "")
            executeAction(project, action)
            
            // Update project state in ledger
            evidenceLedger.append(EvidenceRecord.RoutingDecision(
                timestamp = System.currentTimeMillis(),
                queryHash = "EXECUTION_$cycleNum",
                selectedTopology = "HEARTBEAT_EXECUTION",
                shadowPrices = EvidenceRecord.ShadowPrices(0.0, 0.0, 0.0),
                rcodMetrics = EvidenceRecord.RcodMetrics(0.9f, 0.1f, 0.1f, 0.1f, 0.1f, "STABLE")
            ))
        }
        
        // 3. Check wallet balances, sweep to reserves
        sweepWalletToReserves()
        
        Log.i(TAG, "✅ Execution cycle $cycleNum complete - ${activeProjects.size} projects processed")
    }
    
    // ============ HELPER FUNCTIONS ============
    
    private suspend fun scrapeTrendSignals(): String {
        // Use Kai Bridge to scrape key sources
        val sources = listOf(
            "https://github.com/trending?since=daily",
            "https://news.ycombinator.com/front",
            "https://defillama.com/chains",
            "https://twitter.com/search?q=AI%20agent%20economy&f=live",
            "https://www.coingecko.com/en/categories/ai-agents"
        )
        
        val results = mutableListOf<String>()
        for (source in sources) {
            try {
                val result = kaiBridge.navigate(source)
                val summary = kaiBridge.queryLlm(
                    prompt = "Summarize key trends from this page in 3 bullets:\n${result?.data?.take(3000) ?: "no data"}",
                    maxTokens = 512
                )
                results.add("${source}: ${summary?.text ?: "failed"}")
            } catch (e: Exception) {
                results.add("${source}: ERROR - ${e.message}")
            }
        }
        return results.joinToString("\n\n")
    }
    
    private fun parseIdeas(response: String): List<org.json.JSONObject> {
        val ideas = mutableListOf<org.json.JSONObject>()
        try {
            // Extract JSON from response
            val jsonStart = response.indexOf('[')
            val jsonEnd = response.lastIndexOf(']') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonArray = org.json.JSONArray(response.substring(jsonStart, jsonEnd))
                for (i in 0 until jsonArray.length()) {
                    ideas.add(jsonArray.getJSONObject(i))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ideas: ${e.message}")
        }
        return ideas
    }
    
    private fun shouldAutoDeploy(idea: org.json.JSONObject): Boolean {
        val risk = idea.optDouble("riskBudget", 500.0)
        val confidence = idea.optDouble("confidence", 0.0)
        return risk <= 500 && confidence > 0.7
    }
    
    private suspend fun deployIdea(idea: org.json.JSONObject) {
        val firstStep = idea.optString("firstStep", "")
        if (firstStep.isNotBlank()) {
            kaiBridge.execute(firstStep)
        }
    }
    
    private fun loadActiveProjects(): List<org.json.JSONObject> {
        // Ledger: files/projects/ledger.json — projects with status != COMPLETED/KILLED
        return try {
            val dir = java.io.File(contextDir(), "projects")
            val file = java.io.File(dir, "ledger.json")
            if (file.exists()) {
                val arr = org.json.JSONArray(file.readText())
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter {
                        val s = it.optString("status", "ACTIVE").uppercase()
                        s != "COMPLETED" && s != "KILLED"
                    }
            } else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "loadActiveProjects failed: ${e.message}")
            emptyList()
        }
    }

    /** Persist a project list mutation back to the ledger file. */
    private fun saveProjects(projects: List<org.json.JSONObject>) {
        try {
            val dir = java.io.File(contextDir(), "projects")
            dir.mkdirs()
            val arr = org.json.JSONArray()
            projects.forEach { arr.put(it) }
            java.io.File(dir, "ledger.json").writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "saveProjects failed: ${e.message}")
        }
    }

    /** Record a newly-deployed idea as an active project in the ledger. */
    private fun recordDeployedIdea(cycleNum: Int, idea: org.json.JSONObject) {
        try {
            val projects = loadActiveProjects().toMutableList()
            projects.add(org.json.JSONObject().apply {
                put("id", "idea_${System.currentTimeMillis()}")
                put("concept", idea.optString("concept", "unnamed"))
                put("niche", idea.optString("niche", ""))
                put("pricing", idea.optString("pricing", ""))
                put("status", "ACTIVE")
                put("bornCycle", cycleNum)
                put("revenueUsdc", 0.0)
                put("lastAction", "deployed from ideation cycle $cycleNum")
            })
            saveProjects(projects)
        } catch (e: Exception) {
            Log.e(TAG, "recordDeployedIdea failed: ${e.message}")
        }
    }

    /** Application files dir — injected via create(); null disables the ledger. */
    private fun contextDir(): java.io.File = appFilesDir
        ?: throw IllegalStateException("HeartbeatService created without appFilesDir")
    
    private fun parseAction(response: String): Action {
        val parts = response.split("|").map { it.trim() }
        return Action(
            type = parts.getOrNull(0) ?: "NONE",
            commands = parts.getOrNull(1) ?: "",
            reasoning = parts.getOrNull(2) ?: "",
            revenueDelta = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
        )
    }
    
    private suspend fun executeAction(project: org.json.JSONObject, action: Action) {
        when (action.type.uppercase()) {
            "DEPLOY", "ITERATE" -> {
                for (cmd in action.commands.lines()) {
                    kaiBridge.execute(cmd.trim())
                }
            }
            "MARKET" -> {
                // Post to social, submit to directories
                kaiBridge.navigate("https://github.com/topics/awesome-lists")
            }
            "MONETIZE" -> {
                // Add payment flow
                kaiBridge.execute("wallet.generate") // Ensure wallet exists
            }
            "SCALE" -> {
                clusterManager.autoConnectKnownWorkers()
            }
            "KILL" -> {
                // Cleanup
            }
        }
    }
    
    private suspend fun sweepWalletToReserves() {
        // Balance probe via the sandbox bridge; honest no-op when sandbox is dark.
        try {
            val balanceResult = kaiBridge.execute("wallet.balance --token USDC")
            if (balanceResult != null) {
                Log.i(TAG, "💰 Wallet sweep check: ${balanceResult.stdout.trim().take(120)}")
                // Sweep policy: > 100 USDC sweeps to reserves — handled on-chain by
                // wallet ops once balance parsing lands (tracked in ledger).
            } else {
                Log.i(TAG, "💰 Wallet probe unavailable (sandbox dark) — sweep deferred")
            }
        } catch (e: Exception) {
            Log.w(TAG, "💰 Wallet probe failed: ${e.message} — sweep deferred")
        }
    }
    
    data class Action(
        val type: String,
        val commands: String,
        val reasoning: String,
        val revenueDelta: Double
    )
    
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "running" to isRunning.get(),
            "cycleNumber" to cycleCounter.get(),
            "currentPhase" to currentPhase.get().name,
            "retryCount" to retryCount.get()
        )
    }
    
    fun isRunning(): Boolean = isRunning.get()
    
    companion object {
        @JvmStatic
        fun create(
            metaRouter: MetaCognitiveRouter,
            kaiBridge: KaiBridge,
            contextCompressor: ContextCompressor,
            evidenceLedger: EvidenceLedger,
            fourHorsemen: FourHorsemenOrchestrator,
            clusterManager: ClusterManager,
            scope: CoroutineScope,
            llmClient: com.lucifer.mobile.omni_bridge.llm.NemotronClient? = null,
            appFilesDir: java.io.File? = null
        ): HeartbeatService {
            return HeartbeatService(metaRouter, kaiBridge, contextCompressor, evidenceLedger, fourHorsemen, clusterManager, scope, llmClient, appFilesDir)
        }
    }
}