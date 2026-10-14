package com.lucifer.mobile.omni_bridge.heartbeat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lucifer.mobile.omni_bridge.atoms.AtomsProfile
import com.lucifer.mobile.omni_bridge.atoms.TheoryOfMindAgent
import com.lucifer.mobile.omni_bridge.compression.ContextCompressor
import com.lucifer.mobile.omni_bridge.evidence_ledger.EvidenceLedger
import com.lucifer.mobile.omni_bridge.evidence_ledger.EvidenceRecord
import com.lucifer.mobile.omni_bridge.four_horsemen.FourHorsemenOrchestrator
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiBridge
import com.lucifer.mobile.omni_bridge.meta_cognitive.MetaCognitiveRouter
import com.lucifer.mobile.services.cluster.ClusterManager
import kotlinx.coroutines.*

class LuciferHeartbeatService : Service() {

    companion object {
        const val CHANNEL_ID = "lucifer_heartbeat"
        const val NOTIFICATION_ID = 1001
    }

    private var heartbeatEngine: HeartbeatService? = null
    private var scope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        
        // Create notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lucifer Heartbeat",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Autonomous 25-min cycle service"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        // Build dependencies (same as MainActivity)
        val metaRouter = MetaCognitiveRouter(this)
        val kaiBridge = KaiBridge(this)
        val contextCompressor = ContextCompressor()
        val evidenceLedger = EvidenceLedger()
        val fourHorsemen = FourHorsemenOrchestrator(this)
        val clusterManager = ClusterManager()
        val llmClient = com.lucifer.mobile.omni_bridge.llm.NemotronClient(this)
        val nativeBrowser = com.lucifer.mobile.data.browser.LuciferBrowser(this)

        // Create isolated scope
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Construct the heartbeat engine
        heartbeatEngine = HeartbeatService.create(
            metaRouter = metaRouter,
            kaiBridge = kaiBridge,
            contextCompressor = contextCompressor,
            evidenceLedger = evidenceLedger,
            fourHorsemen = fourHorsemen,
            clusterManager = clusterManager,
            scope = scope!!,
            llmClient = llmClient,
            appFilesDir = filesDir,
            browser = nativeBrowser
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create foreground notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lucifer Heartbeat")
            .setContentText("Autonomous cycle running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        // Try startForeground with explicit dataSync type (required targeting SDK 34+),
        // fall back to the untyped call on older releases, then degrade gracefully.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.w("HeartbeatService", "startForeground failed (permissions?), continuing as started service", e)
        } catch (e: Exception) {
            Log.w("HeartbeatService", "startForeground failed, continuing as started service", e)
        }

        // Start the engine
        Log.i("HeartbeatService", "🫀 Heartbeat Service STARTED - 25-min autonomous cycle")
        com.lucifer.mobile.launcher.LuciferState.serviceAlive = true
        heartbeatEngine?.start()

        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        heartbeatEngine?.stop()
        Log.i("HeartbeatService", "🫀 Heartbeat Service STOPPED")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}