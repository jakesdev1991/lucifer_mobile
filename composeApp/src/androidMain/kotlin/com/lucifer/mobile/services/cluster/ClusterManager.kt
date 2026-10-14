package com.lucifer.mobile.services.cluster

// SSH Cluster Service
// Persistent SSH server on Pixel 8a for multi-device cluster connectivity
// Auto-connects to worker laptops and future mobile devices

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.*
import java.net.ServerSocket
import java.net.Socket

// Worker node in cluster
data class WorkerNode(
    val id: String,
    val hostname: String,
    val port: Int,
    val capabilities: Set<String>,  // "wallet", "browser", "llm", "sandbox", "vector_db"
    val status: WorkerStatus = WorkerStatus.DISCONNECTED,
    val lastSeen: Long = 0
)

enum class WorkerStatus {
    CONNECTED, DISCONNECTED, CONNECTING, ERROR
}

// Cluster manager
class ClusterManager {
    private val workers = mutableMapOf<String, WorkerNode>()
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val port = 8023
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Register this device as cluster node
    fun registerThisNode(): WorkerNode {
        val thisNode = WorkerNode(
            id = "pixel8a-${android.os.Build.SERIAL}",
            hostname = "localhost",
            port = port,
            capabilities = setOf("wallet", "browser", "llm", "sandbox", "rcod", "evidence_ledger"),
            status = WorkerStatus.CONNECTED,
            lastSeen = System.currentTimeMillis()
        )
        workers[thisNode.id] = thisNode
        return thisNode
    }
    
    // Start SSH server
    fun startServer(): Boolean {
        return try {
            serverSocket = ServerSocket(port)
            running = true
            scope.launch { acceptConnections() }
            Log.d("ClusterManager", "SSH server started on port $port")
            true
        } catch (e: Exception) {
            Log.e("ClusterManager", "Failed to start server", e)
            false
        }
    }
    
    private fun acceptConnections() {
        while (running) {
            try {
                val socket = serverSocket?.accept()
                socket?.let { handleConnection(it) }
            } catch (e: Exception) {
                if (running) Log.e("ClusterManager", "Accept error", e)
            }
        }
    }
    
    private fun handleConnection(socket: Socket) {
        scope.launch(Dispatchers.IO) {
            try {
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = PrintWriter(socket.getOutputStream(), true)
                
                // Simple protocol: first line is command
                val command = input.readLine() ?: return@launch
                
                when (command) {
                    "REGISTER" -> {
                        val workerId = input.readLine() ?: "unknown"
                        val capabilities = input.readLine()?.split(",")?.toSet() ?: emptySet()
                        val worker = WorkerNode(
                            id = workerId,
                            hostname = socket.inetAddress.hostAddress,
                            port = socket.port,
                            capabilities = capabilities,
                            status = WorkerStatus.CONNECTED,
                            lastSeen = System.currentTimeMillis()
                        )
                        workers[workerId] = worker
                        output.println("OK: Registered as $workerId")
                    }
                    "HEARTBEAT" -> {
                        val workerId = input.readLine() ?: "unknown"
                        workers[workerId]?.let { workers[workerId] = it.copy(lastSeen = System.currentTimeMillis()) }
                        output.println("OK")
                    }
                    "EXECUTE" -> {
                        val task = input.readLine() ?: ""
                        // Execute task locally or forward
                        val result = executeLocalTask(task)
                        output.println("RESULT: $result")
                    }
                    "GET_WORKERS" -> {
                        val list = workers.values.joinToString("\n") { "${it.id}:${it.status}:${it.capabilities.joinToString(",")}" }
                        output.println(list)
                    }
                    else -> output.println("ERROR: Unknown command")
                }
            } catch (e: Exception) {
                Log.e("ClusterManager", "Connection handling error", e)
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }
    
    private fun executeLocalTask(task: String): String {
        // Route to appropriate Kai bridge operation
        return "Executed: $task"
    }
    
    // Connect to worker laptop
    suspend fun connectToWorker(hostname: String, port: Int = 8023): Boolean {
        return try {
            val socket = Socket(hostname, port)
            val output = PrintWriter(socket.getOutputStream(), true)
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            
            val thisNode = registerThisNode()
            output.println("REGISTER")
            output.println(thisNode.id)
            output.println(thisNode.capabilities.joinToString(","))
            
            val response = input.readLine()
            val success = response?.startsWith("OK") == true
            
            if (success) {
                Log.d("ClusterManager", "Connected to worker $hostname")
            }
            
            socket.close()
            success
        } catch (e: Exception) {
            Log.e("ClusterManager", "Failed to connect to $hostname", e)
            false
        }
    }
    
    // Auto-discover and connect to known workers
    suspend fun autoConnectKnownWorkers() {
        val knownWorkers = listOf(
            "laptop1.local" to 8023,
            "laptop2.local" to 8023,
            "192.168.1.100" to 8023,
            "192.168.1.101" to 8023
        )
        
        for ((host, port) in knownWorkers) {
            connectToWorker(host, port)
        }
    }
    
    fun getWorkers(): List<WorkerNode> = workers.values.toList()
    
    fun getConnectedWorkers(): List<WorkerNode> = workers.values.filter { it.status == WorkerStatus.CONNECTED }.toList()
    
    fun shutdown() {
        running = false
        serverSocket?.close()
    }
}

// SSH Service for Android
class SshClusterService : Service() {
    private val clusterManager = ClusterManager()
    
    override fun onCreate() {
        super.onCreate()
        clusterManager.registerThisNode()
        clusterManager.startServer()
        // Auto-connect to known workers
        CoroutineScope(Dispatchers.IO).launch {
            clusterManager.autoConnectKnownWorkers()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        clusterManager.shutdown()
        super.onDestroy()
    }
}

// Cluster UI State
data class ClusterState(
    val thisNode: WorkerNode?,
    val connectedWorkers: List<WorkerNode>,
    val totalWorkers: Int,
    val serverRunning: Boolean
)