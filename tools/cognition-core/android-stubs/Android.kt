// Type-check-only stand-ins for the Android surface the runner touches.
//
// The loop itself (CognitionRunner.run) takes ports, not Android types. The one part of the file that
// needs a device is `AndroidCognitionTools`, and it lives here only so that the adapters below it are
// compiled against something with the right shape. Nothing in this directory executes on a device.
@file:Suppress("unused", "UNUSED_PARAMETER", "FunctionName")

package android.content

import java.io.File

open class Context {
    open val applicationContext: Context get() = this
    open val filesDir: File get() = File(System.getProperty("java.io.tmpdir"), "lucifer-stub").apply { mkdirs() }
}
