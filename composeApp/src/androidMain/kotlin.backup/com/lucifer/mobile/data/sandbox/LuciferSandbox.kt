package com.lucifer.mobile.data.sandbox

import com.lucifer.mobile.omni_bridge.kai_bridge.KaiSandboxBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LuciferSandbox {

    private val kaiBridge = KaiSandboxBridge()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    fun initialize(context: android.content.Context) {
        scope.launch {
            kaiBridge.initialize()
        }
    }
    
    fun execute(command: String, args: List<String> = emptyList()): ExecResult {
        return kaiBridge.execute(command, args)
    }
    
    fun execPython(script: String, args: List<String> = emptyList()): ExecResult {
        return kaiBridge.exec_python(script, args)
    }
    
    fun execNode(script: String, args: List<String> = emptyList()): ExecResult {
        return kaiBridge.exec_node(script, args)
    }
    
    fun execShell(script: String, args: List<String> = emptyList()): ExecResult {
        return kaiBridge.exec_shell(script, args)
    }
    
    fun readFile(path: String): String? {
        return kaiBridge.read_file(path)
    }
    
    fun writeFile(path: String, content: String): Boolean {
        return kaiBridge.write_file(path, content)
    }
    
    fun deleteFile(path: String): Boolean {
        return kaiBridge.delete_file(path)
    }
    
    fun listDir(path: String): List<FileInfo> {
        return kaiBridge.list_dir(path)
    }
    
    fun makeDir(path: String): Boolean {
        return kaiBridge.make_dir(path)
    }
    
    fun removeDir(path: String): Boolean {
        return kaiBridge.remove_dir(path)
    }
    
    fun getResourceUsage(): ResourceUsage {
        return kaiBridge.get_resource_usage()
    }
    
    fun shutdown() {
        // Cleanup
    }
}

data class ExecResult(
    val exitCode: Int = -1,
    val stdout: String = "",
    val stderr: String = "",
    val timeout: Boolean = false,
    val oom: Boolean = false,
    val durationMs: Double = 0.0
)

data class FileInfo(
    val path: String,
    val size: Long = 0,
    val isDir: Boolean = false,
    val mimeType: String = ""
)

data class ResourceUsage(
    val cpuPercent: Double = 0.0,
    val memoryBytes: Long = 0,
    val diskBytes: Long = 0
)