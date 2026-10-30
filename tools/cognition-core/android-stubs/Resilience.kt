@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.resilience

data class FetchedPage(
    val url: String,
    val status: Int,
    val body: String,
    val error: String = ""
)

interface PageFetcher {
    suspend fun fetch(url: String): FetchedPage
}

class ResearchAgent(
    private val fetcher: PageFetcher,
    private val sleeper: suspend (Long) -> Unit = {},
    private val maxPages: Int = 7,
    private val maxResultPages: Int = 3
) {
    class Request(val question: String)

    class Brief(
        val sources: List<String> = emptyList(),
        val pagesFetched: Int = 0,
        val stoppedReason: String = ""
    ) {
        fun toPromptContext(limit: Int): String = sources.joinToString("\n").take(limit)
    }

    suspend fun research(request: Request): Brief = Brief(stoppedReason = "no network in the harness")
}
