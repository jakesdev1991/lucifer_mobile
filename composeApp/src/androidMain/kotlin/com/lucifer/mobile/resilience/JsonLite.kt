package com.lucifer.mobile.resilience

/**
 * Minimal JSON reader/writer so the resilience core stays dependency-free (no org.json, no
 * Gson, no kotlinx.serialization) and can be compiled/tested on a plain JVM.
 *
 * It intentionally supports only the subset Lucifer needs: objects, arrays, strings, numbers,
 * booleans, and null. Parsing is bounded and never throws on malformed input - callers get
 * null and can fall back to a template.
 */
object JsonLite {

    private const val MAX_INPUT_CHARS = 256_000
    private const val MAX_DEPTH = 24

    fun parse(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        if (raw.length > MAX_INPUT_CHARS) return null
        return try {
            val reader = Reader(raw)
            reader.skipWhitespace()
            val value = reader.readValue(0)
            reader.skipWhitespace()
            value
        } catch (_: Exception) {
            null
        }
    }

    /** Parse an object, tolerating prose around the JSON body (common in model replies). */
    fun parseObject(raw: String?): Map<String, Any?>? {
        if (raw.isNullOrBlank()) return null
        (parse(raw) as? Map<*, *>)?.let { return it.asStringMap() }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return (parse(raw.substring(start, end + 1)) as? Map<*, *>)?.asStringMap()
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    fun quote(text: String): String = StringBuilder().also { write(it, text) }.toString()

    private fun write(builder: StringBuilder, value: Any?) {
        when (value) {
            null -> builder.append("null")
            is String -> writeString(builder, value)
            is Boolean -> builder.append(if (value) "true" else "false")
            is Int, is Long -> builder.append(value.toString())
            is Float, is Double -> builder.append(value.toString())
            is Map<*, *> -> {
                builder.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) builder.append(',')
                    first = false
                    writeString(builder, key?.toString() ?: "")
                    builder.append(':')
                    write(builder, item)
                }
                builder.append('}')
            }
            is Iterable<*> -> {
                builder.append('[')
                var first = true
                for (item in value) {
                    if (!first) builder.append(',')
                    first = false
                    write(builder, item)
                }
                builder.append(']')
            }
            else -> writeString(builder, value.toString())
        }
    }

    private fun writeString(builder: StringBuilder, text: String) {
        builder.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else -> if (ch < ' ') {
                    builder.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else builder.append(ch)
            }
        }
        builder.append('"')
    }

    private fun Map<*, *>.asStringMap(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(size)
        for ((key, value) in this) {
            val name = key?.toString() ?: continue
            out[name] = value
        }
        return out
    }

    private class Reader(private val text: String) {
        private var index = 0

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("json too deep")
            skipWhitespace()
            if (index >= text.length) throw IllegalArgumentException("unexpected end")
            return when (val ch = text[index]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else -> if (ch == '-' || ch.isDigit()) readNumber() else throw IllegalArgumentException("unexpected $ch")
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { index++; return map }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                map[key] = readValue(depth + 1)
                skipWhitespace()
                when (val ch = peek()) {
                    ',' -> { index++; continue }
                    '}' -> { index++; return map }
                    else -> throw IllegalArgumentException("expected , or }")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { index++; return list }
            while (true) {
                list.add(readValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> { index++; continue }
                    ']' -> { index++; return list }
                    else -> throw IllegalArgumentException("expected , or ]")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) throw IllegalArgumentException("unterminated string")
                val ch = text[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) throw IllegalArgumentException("unterminated escape")
                        when (val esc = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw IllegalArgumentException("bad unicode escape")
                                val hex = text.substring(index, index + 4)
                                index += 4
                                out.append(hex.toInt(16).toChar())
                            }
                            else -> out.append(esc)
                        }
                    }
                    else -> out.append(ch)
                }
            }
        }

        private fun readNumber(): Any {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] == '.' ||
                        text[index] == 'e' || text[index] == 'E' || text[index] == '+' || text[index] == '-')
            ) index++
            val slice = text.substring(start, index)
            return slice.toLongOrNull() ?: slice.toDoubleOrNull() ?: throw IllegalArgumentException("bad number")
        }

        private fun readBoolean(): Boolean =
            if (text.startsWith("true", index)) { index += 4; true }
            else if (text.startsWith("false", index)) { index += 5; false }
            else throw IllegalArgumentException("bad boolean")

        private fun readNull(): Any? {
            if (!text.startsWith("null", index)) throw IllegalArgumentException("bad null")
            index += 4
            return null
        }

        private fun expect(expected: Char) {
            skipWhitespace()
            if (index >= text.length || text[index] != expected) throw IllegalArgumentException("expected $expected")
            index++
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'
    }
}

// ───────────── convenient typed accessors for parsed objects ─────────────

fun Map<String, Any?>.str(key: String, fallback: String = ""): String =
    (this[key] as? String)?.takeIf { it.isNotBlank() } ?: fallback

fun Map<String, Any?>.strOrNull(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotBlank() }

fun Map<String, Any?>.int(key: String, fallback: Int = 0): Int = when (val value = this[key]) {
    is Long -> value.toInt()
    is Double -> value.toInt()
    is String -> value.toIntOrNull() ?: fallback
    else -> fallback
}

fun Map<String, Any?>.long(key: String, fallback: Long = 0L): Long = when (val value = this[key]) {
    is Long -> value
    is Double -> value.toLong()
    is String -> value.toLongOrNull() ?: fallback
    else -> fallback
}

fun Map<String, Any?>.bool(key: String, fallback: Boolean = false): Boolean = when (val value = this[key]) {
    is Boolean -> value
    is String -> value.equals("true", ignoreCase = true)
    else -> fallback
}

fun Map<String, Any?>.arr(key: String): List<Any?> =
    (this[key] as? List<*>)?.toList() ?: emptyList()

fun Map<String, Any?>.obj(key: String): Map<String, Any?>? =
    (this[key] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }

fun Map<String, Any?>.stringList(key: String): List<String> =
    arr(key).mapNotNull { it?.toString()?.takeIf { text -> text.isNotBlank() } }
