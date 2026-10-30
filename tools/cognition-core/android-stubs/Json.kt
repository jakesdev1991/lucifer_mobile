// A small, real implementation of the parts of org.json the ledger uses.
//
// This is not a stub that returns nothing: the ledger's contract is about *what ends up in the file*,
// so the harness needs a JSON object that actually serialises and parses. It is deliberately minimal —
// objects, arrays, strings, numbers, booleans and null — and it makes no attempt to be a general
// library. Anything the ledger does not use is absent rather than unimplemented.
@file:Suppress("unused", "UNUSED_PARAMETER")

package org.json

class JSONException(message: String) : Exception(message)

class JSONArray {
    private val items = mutableListOf<Any?>()

    constructor()
    constructor(collection: Collection<*>) {
        collection.forEach { items += it }
    }

    fun put(value: Any?): JSONArray {
        items += value
        return this
    }

    fun length(): Int = items.size

    fun get(index: Int): Any? = items[index]

    fun opt(index: Int): Any? = items.getOrNull(index)

    fun optInt(index: Int, fallback: Int = 0): Int = when (val value = items.getOrNull(index)) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: fallback
        else -> fallback
    }

    fun optString(index: Int, fallback: String = ""): String = when (val value = items.getOrNull(index)) {
        null -> fallback
        is String -> value
        else -> value.toString()
    }

    fun toList(): List<Any?> = items.toList()

    override fun toString(): String = buildString {
        append('[')
        items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            append(encode(item))
        }
        append(']')
    }
}

class JSONObject {

    private val values = LinkedHashMap<String, Any?>()

    constructor()

    constructor(source: String) {
        val parsed = JsonParser(source).parseValue()
        if (parsed !is Map<*, *>) throw JSONException("not an object")
        parsed.forEach { (key, value) -> values[key as String] = value }
    }

    fun put(key: String, value: Any?): JSONObject {
        values[key] = normalise(value)
        return this
    }

    fun keys(): Iterator<String> = values.keys.iterator()

    fun length(): Int = values.size

    fun has(key: String): Boolean = values.containsKey(key)

    fun optInt(key: String, fallback: Int = 0): Int = when (val value = values[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: fallback
        else -> fallback
    }

    fun optLong(key: String, fallback: Long = 0L): Long = when (val value = values[key]) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: fallback
        else -> fallback
    }

    fun optBoolean(key: String, fallback: Boolean = false): Boolean = when (val value = values[key]) {
        is Boolean -> value
        else -> fallback
    }

    fun optString(key: String, fallback: String = ""): String = when (val value = values[key]) {
        null -> fallback
        is String -> value
        else -> value.toString()
    }

    fun optJSONArray(key: String): JSONArray? = when (val value = values[key]) {
        is JSONArray -> value
        is List<*> -> JSONArray(value)
        else -> null
    }

    fun getString(key: String): String = optString(key)

    fun getInt(key: String): Int = optInt(key)

    override fun toString(): String = buildString {
        append('{')
        var first = true
        values.forEach { (key, value) ->
            if (!first) append(',')
            first = false
            append(encode(key)).append(':').append(encode(value))
        }
        append('}')
    }

    private fun normalise(value: Any?): Any? = when (value) {
        is Collection<*> -> JSONArray(value)
        is Array<*> -> JSONArray(value.toList())
        else -> value
    }
}

private fun encode(value: Any?): String = when (value) {
    null -> "null"
    is String -> encodeString(value)
    is Boolean -> value.toString()
    is Number -> if (value is Double || value is Float) {
        val double = value.toDouble()
        if (double == double.toLong().toDouble()) double.toLong().toString() else double.toString()
    } else {
        value.toString()
    }
    is JSONObject -> value.toString()
    is JSONArray -> value.toString()
    is Collection<*> -> JSONArray(value).toString()
    else -> encodeString(value.toString())
}

private fun encodeString(text: String): String = buildString {
    append('"')
    for (character in text) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u").append("%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}

/** A recursive-descent reader for the subset above. Throws [JSONException] on anything malformed. */
private class JsonParser(private val text: String) {

    private var index = 0

    fun parseValue(): Any? {
        skipSpace()
        if (index >= text.length) throw JSONException("empty input")
        return when (val character = text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            else -> if (character == '-' || character.isDigit()) parseNumber() else throw JSONException("unexpected $character")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        expect('{')
        skipSpace()
        if (peek() == '}') { index++; return result }
        while (true) {
            skipSpace()
            val key = parseString()
            skipSpace()
            expect(':')
            result[key] = parseValue()
            skipSpace()
            when (peek()) {
                ',' -> index++
                '}' -> { index++; return result }
                else -> throw JSONException("expected , or }")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        val result = mutableListOf<Any?>()
        expect('[')
        skipSpace()
        if (peek() == ']') { index++; return result }
        while (true) {
            result += parseValue()
            skipSpace()
            when (peek()) {
                ',' -> index++
                ']' -> { index++; return result }
                else -> throw JSONException("expected , or ]")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            if (index >= text.length) throw JSONException("unterminated string")
            when (val character = text[index++]) {
                '"' -> return builder.toString()
                '\\' -> {
                    when (val escape = text[index++]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000c')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            builder.append(text.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> throw JSONException("bad escape $escape")
                    }
                }
                else -> builder.append(character)
            }
        }
    }

    private fun parseNumber(): Any {
        val start = index
        if (peek() == '-') index++
        while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
        val raw = text.substring(start, index)
        return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw JSONException("bad number $raw")
    }

    private fun parseLiteral(word: String, value: Any?): Any? {
        if (!text.startsWith(word, index)) throw JSONException("expected $word")
        index += word.length
        return value
    }

    private fun expect(character: Char) {
        if (peek() != character) throw JSONException("expected $character at $index")
        index++
    }

    private fun peek(): Char = if (index < text.length) text[index] else throw JSONException("unexpected end")

    private fun skipSpace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
