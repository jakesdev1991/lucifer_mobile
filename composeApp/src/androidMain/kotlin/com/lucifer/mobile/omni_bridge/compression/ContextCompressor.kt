package com.lucifer.mobile.omni_bridge.compression

// Context Compression Pipeline
// Solves: "context too large" for NVIDIA API calls
// Pipeline: Ingest -> Chunk -> Embed -> Store -> Retrieve -> Rerank -> Summarize -> Inject

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.log
import kotlin.math.sqrt

// Message in conversation history
data class ConversationMessage(
    val id: String,
    val role: String,           // "user" | "assistant" | "system" | "tool"
    val content: String,
    val timestamp: Long,
    val tokens: Int,
    val metadata: Map<String, String> = emptyMap()
)

// Compressed context segment
data class CompressedSegment(
    val id: String,
    val summary: String,
    val originalMessages: List<String>,  // Message IDs
    val embedding: FloatArray,
    val tokenCount: Int,
    val importance: Float,      // 0.0 - 1.0
    val timestamp: Long
)

// Vector store interface (SQLite-vec / Faiss on-device)
interface VectorStore {
    fun add(embedding: FloatArray, metadata: String): Long
    fun search(queryEmbedding: FloatArray, k: Int): List<VectorResult>
    fun delete(id: Long)
    fun clear()
}

data class VectorResult(
    val id: Long,
    val score: Float,
    val metadata: String
)

// In-memory vector store (replace with SQLite-vec on device)
class InMemoryVectorStore : VectorStore {
    private val vectors = mutableMapOf<Long, Pair<FloatArray, String>>()
    private var nextId = 0L
    
    override fun add(embedding: FloatArray, metadata: String): Long {
        val id = nextId++
        vectors[id] = embedding to metadata
        return id
    }
    
    override fun search(queryEmbedding: FloatArray, k: Int): List<VectorResult> {
        return vectors.entries.map { (id, pair) ->
            val score = cosineSimilarity(queryEmbedding, pair.first)
            VectorResult(id, score, pair.second)
        }.sortedByDescending { it.score }.take(k)
    }
    
    override fun delete(id: Long) {
        vectors.remove(id)
    }
    
    override fun clear() {
        vectors.clear()
        nextId = 0
    }
    
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA * normB)
        return if (denom > 0) dot / denom else 0f
    }
}

// Local embedding model (MiniLM - ~50MB on device)
class LocalEmbedder {
    // In production: load ONNX model for all-MiniLM-L6-v2
    // For demo: hash-based pseudo-embedding
    fun embed(text: String): FloatArray {
        val size = 384  // MiniLM embedding dimension
        val values = FloatArray(size)
        val hash = text.hashCode().toLong()
        for (i in 0 until size) {
            values[i] = ((hash * (i + 1L)) % 1000000).toFloat() / 1e6f - 0.5f
        }
        return normalize(values)
    }
    
    fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }
    
    private fun normalize(vec: FloatArray): FloatArray {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm)
        if (norm > 0) {
            for (i in vec.indices) vec[i] /= norm
        }
        return vec
    }
}

// Cross-encoder reranker (bge-reranker-base)
class Reranker {
    // In production: load ONNX cross-encoder
    // For demo: heuristic scoring
    fun rerank(query: String, candidates: List<CompressedSegment>): List<CompressedSegment> {
        return candidates.map { segment ->
            val relevance = computeRelevance(query, segment.summary)
            segment.copy(importance = segment.importance * relevance)
        }.sortedByDescending { it.importance }
    }
    
    private fun computeRelevance(query: String, summary: String): Float {
        // Heuristic: keyword overlap
        val queryWords = query.lowercase().split(Regex("\\W+")).toSet()
        val summaryWords = summary.lowercase().split(Regex("\\W+")).toSet()
        val overlap = queryWords.intersect(summaryWords).size.toFloat()
        return (overlap / queryWords.size.coerceAtLeast(1)).coerceIn(0.1f, 1.0f)
    }
}

// Summarization LLM (distilled Nemotron on-device or via API)
class Summarizer {
    private val nvidiaApi = NvidiaApiClient()
    
    suspend fun summarize(messages: List<ConversationMessage>): String {
        val text = messages.joinToString("\n") { "${it.role}: ${it.content}" }
        val prompt = """
            Compress the following conversation into a concise summary preserving:
            - Key decisions and outcomes
            - Important facts and data
            - Tool calls and results
            - User preferences and constraints
            
            Conversation:
            $text
            
            Summary:
        """.trimIndent()
        
        return nvidiaApi.complete(prompt, maxTokens = 512)
    }
    
    // For on-device: use distilled model
    fun summarizeLocal(messages: List<ConversationMessage>): String {
        // Heuristic summarization
        val keyPoints = messages.filter { it.role == "assistant" || it.role == "tool" }
            .takeLast(5)
            .map { "${it.role}: ${it.content.take(100)}" }
            .joinToString("; ")
        return "Recent context: $keyPoints"
    }
}

// NVIDIA API Client
class NvidiaApiClient {
    suspend fun complete(prompt: String, maxTokens: Int): String {
        // In production: call NVIDIA Nemotron API with key rotation
        return "API response for: ${prompt.take(50)}..."
    }
}

// Context Compression Pipeline Orchestrator
class ContextCompressor(
    private val embedder: LocalEmbedder = LocalEmbedder(),
    private val vectorStore: VectorStore = InMemoryVectorStore(),
    private val reranker: Reranker = Reranker(),
    private val summarizer: Summarizer = Summarizer(),
    private val maxContextTokens: Int = 8000,
    private val compressionThreshold: Float = 0.7f
) {
    
    private val conversationHistory = mutableListOf<ConversationMessage>()
    private val compressedSegments = mutableListOf<CompressedSegment>()
    private var nextSegmentId = 0
    
    // Add message to history
    fun addMessage(message: ConversationMessage) {
        conversationHistory.add(message)
        
        // Check if compression needed
        val totalTokens = conversationHistory.sumOf { it.tokens }
        if (totalTokens > maxContextTokens * compressionThreshold) {
            compressOldest()
        }
    }
    
    // Compress oldest messages
    private fun compressOldest() {
        val toCompress = conversationHistory.take(10)  // Compress in chunks of 10
        conversationHistory.removeAll(toCompress)
        
        // Generate summary
        val summary = summarizer.summarizeLocal(toCompress)
        
        // Embed and store
        val embedding = embedder.embed(summary)
        val tokenCount = toCompress.sumOf { it.tokens }
        
        val segment = CompressedSegment(
            id = "seg_$nextSegmentId",
            summary = summary,
            originalMessages = toCompress.map { it.id },
            embedding = embedding,
            tokenCount = tokenCount,
            importance = computeImportance(toCompress),
            timestamp = System.currentTimeMillis()
        )
        
        nextSegmentId++
        compressedSegments.add(segment)
        vectorStore.add(embedding, segment.id)
    }
    
    private fun computeImportance(messages: List<ConversationMessage>): Float {
        // Tool calls, decisions, user preferences = high importance
        val importanceSignals = messages.count { msg ->
            msg.role == "tool" || 
            msg.content.contains("decision") ||
            msg.content.contains("wallet") ||
            msg.content.contains("sign") ||
            msg.metadata.containsKey("important")
        }
        return (importanceSignals / messages.size.toFloat()).coerceIn(0.1f, 1.0f)
    }
    
    // Get compressed context for LLM call
    fun getCompressedContext(currentQuery: String): String {
        val currentTokens = conversationHistory.sumOf { it.tokens }
        val availableTokens = maxContextTokens - currentTokens
        
        if (availableTokens <= 500) {
            // Aggressive compression needed
            return buildMinimalContext()
        }
        
        // Retrieve relevant compressed segments
        val queryEmbedding = embedder.embed(currentQuery)
        val results = vectorStore.search(queryEmbedding, k = 10)
        
        val relevantSegments = results.mapNotNull { result ->
            compressedSegments.find { it.id == result.metadata }
        }
        
        // Rerank
        val reranked = reranker.rerank(currentQuery, relevantSegments)
        
        // Build context within token budget
        return buildContext(reranked, availableTokens)
    }
    
    private fun buildContext(segments: List<CompressedSegment>, budget: Int): String {
        var used = 0
        val builder = StringBuilder()
        
        builder.appendLine("=== COMPRESSED CONTEXT ===")
        
        for (segment in segments) {
            if (used + segment.tokenCount > budget) break
            builder.appendLine("[${segment.timestamp}] ${segment.summary}")
            used += segment.tokenCount
        }
        
        builder.appendLine("=== RECENT CONVERSATION ===")
        for (msg in conversationHistory.takeLast(10)) {
            if (used + msg.tokens > budget) break
            builder.appendLine("${msg.role}: ${msg.content}")
            used += msg.tokens
        }
        
        return builder.toString()
    }
    
    private fun buildMinimalContext(): String {
        // Keep only last 3 messages + highest importance segment
        val recent = conversationHistory.takeLast(3)
        val topSegment = compressedSegments.maxByOrNull { it.importance }
        
        return """
            === MINIMAL CONTEXT ===
            ${topSegment?.let { "[${it.timestamp}] ${it.summary}" } ?: "No compressed context"}
            ${recent.joinToString("\n") { "${it.role}: ${it.content}" }}
        """.trimIndent()
    }
    
    fun getStats(): CompressionStats {
        val historyTokens = conversationHistory.sumOf { it.tokens }
        val compressedTokens = compressedSegments.sumOf { it.tokenCount }
        val totalTokens = historyTokens + compressedTokens
        val ratio = if (totalTokens > 0) compressedTokens.toFloat() / totalTokens else 0f
        
        return CompressionStats(
            historyMessages = conversationHistory.size,
            historyTokens = historyTokens,
            compressedSegments = compressedSegments.size,
            compressedTokens = compressedTokens,
            compressionRatio = ratio
        )
    }
}

data class CompressionStats(
    val historyMessages: Int,
    val historyTokens: Int,
    val compressedSegments: Int,
    val compressedTokens: Int,
    val compressionRatio: Float
)