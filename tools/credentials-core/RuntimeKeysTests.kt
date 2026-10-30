package com.lucifer.mobile.omni_bridge.llm

fun main() {
    var checks = 0
    fun verify(ok: Boolean) { check(ok) { "Credential rule failed at check ${checks + 1}" }; checks++ }
    val primary = "test-primary-000000000"
    val fallback = "test-fallback-00000000"
    verify(RuntimeKeys.parse("") == emptyList<String>())
    verify(RuntimeKeys.parse(" \n\t") == emptyList<String>())
    verify(RuntimeKeys.parse(primary) == listOf(primary))
    verify(RuntimeKeys.parse(" $primary,\n$fallback\t$primary ") == listOf(primary, fallback))
    verify(RuntimeKeys.parse("$primary,$fallback")?.joinToString("\n") == "$primary\n$fallback")
    verify(RuntimeKeys.parse("short") == null)
    verify(RuntimeKeys.parse("x".repeat(512))?.size == 1)
    verify(RuntimeKeys.parse("x".repeat(513)) == null)
    verify(RuntimeKeys.parse("x".repeat(16))?.size == 1)
    verify(RuntimeKeys.parse("x".repeat(15)) == null)
    verify(RuntimeKeys.parse("$primary,short") == null)
    verify(RuntimeKeys.parse(",,,") == null)
    verify(RuntimeKeys.parse("é".repeat(20)) == null)
    verify(RuntimeKeys.parse("$primary\u0000") == null)
    verify(RuntimeKeys.parse("Bearer $primary") == null)
    verify(RuntimeKeys.parse(" $primary" + " ".repeat(8192)) == null)
    verify(RuntimeKeys.parse((1..16).joinToString(",") { "test-key-00000000-$it" })?.size == 16)
    verify(RuntimeKeys.parse((1..17).joinToString(",") { "test-key-00000000-$it" }) == null)
    verify(RuntimeKeys.parse(List(30) { primary }.joinToString(",")) == listOf(primary))
    verify(RuntimeKeys.parse("$fallback,$primary") == listOf(fallback, primary))
    println("PASS · $checks credential parsing checks (synthetic keys only)")
}
