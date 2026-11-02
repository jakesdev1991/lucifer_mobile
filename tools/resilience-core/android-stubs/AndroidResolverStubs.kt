// Type-check-only stand-in for android.content.ContentResolver, as far as the lesson-transfer adapter
// touches it: an output stream for export, an input stream for import. A SAF Uri is the only way the
// lessons leave or enter the app, so the shape matters even though nothing here runs on a device.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.content

import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

class ContentResolver {
    fun openOutputStream(uri: Uri): OutputStream? = null
    fun openInputStream(uri: Uri): InputStream? = null
}
