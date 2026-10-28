// Compile-only stand-in for the Keystore-backed secret store.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.security

import android.content.Context

object SecretStore {
    fun read(context: Context, relativePath: String): String? = null
    fun write(context: Context, relativePath: String, value: String): Boolean = false
    fun delete(context: Context, relativePath: String) {}
}
