// Type-check-only stand-in with a fixture: the corpus index itself is not what the toolbox test is about.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.omni_bridge.knowledge

object CorpusFixture {
    @Volatile var hits: List<String> = emptyList()
    @Volatile var failure: Exception? = null
    val queries = mutableListOf<String>()

    fun reset() {
        hits = emptyList()
        failure = null
        queries.clear()
    }
}

class EmbeddedToE(private val context: android.content.Context) {
    fun search(query: String, limit: Int): List<String> {
        CorpusFixture.queries += query
        CorpusFixture.failure?.let { throw it }
        return CorpusFixture.hits.take(limit)
    }
}
