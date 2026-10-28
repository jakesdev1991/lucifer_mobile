// Type-check-only stand-ins. See AndroidContentStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package org.json

class JSONObject {
    constructor()
    constructor(text: String) {
        if (!text.trimStart().startsWith("{")) throw JSONException("not an object")
    }

    fun put(key: String, value: Any?): JSONObject = this
    fun optString(key: String): String = ""
    fun optString(key: String, fallback: String): String = fallback
    fun optInt(key: String): Int = 0
    fun optInt(key: String, fallback: Int): Int = fallback
    fun optLong(key: String): Long = 0L
    fun optBoolean(key: String, fallback: Boolean): Boolean = fallback
    fun optJSONArray(key: String): JSONArray? = null
    fun optJSONObject(key: String): JSONObject? = null
    override fun toString(): String = "{}"
}

class JSONArray {
    constructor()
    fun put(value: Any?): JSONArray = this
    fun length(): Int = 0
    fun getJSONObject(index: Int): JSONObject = JSONObject()
    fun optJSONObject(index: Int): JSONObject? = null
}

class JSONException(message: String) : Exception(message)
