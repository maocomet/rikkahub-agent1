package me.rerere.rikkahub.sticker

/** One ranked candidate, with the score that put it there. */
data class StickerSearchHit(
    val sticker: Sticker,
    val score: Int,
)

/**
 * Local, explainable retrieval over the shared sticker library.
 *
 * Deliberately not embeddings. A sticker library is a few hundred hand-curated items whose
 * metadata the person wrote or approved, and matching a short query against tags and a sentence of
 * description is something a substring scan does well — while an embedding index would add a model
 * call, a store to keep in sync with deletes, and a failure mode where retrieval silently returns
 * nothing because a vector was never computed. The upgrade path stays open if it ever earns its
 * keep.
 *
 * A pure function over a candidate list, with no I/O and no Android types, so every ranking rule
 * is exercised directly in JVM tests rather than inferred from a device.
 *
 * ## Scoring
 *
 * Per token, the *single* best match counts:
 *
 * | match | score |
 * |---|---|
 * | a tag equals the token | 100 |
 * | a tag contains the token | 60 |
 * | the description contains the token | 30 |
 *
 * Scores accumulate across tokens, so `委屈 撒娇` ranks a sticker matching both above one matching
 * either. Taking only the best per token is what keeps a sticker that happens to have "委屈" in
 * both its tags and its description from outranking one that matches an *additional* token.
 *
 * ## Tokenisation
 *
 * Lowercase, split on whitespace and the punctuation people actually type between tags. A Chinese
 * phrase with no separators stays one token and is matched as a whole substring — which is exactly
 * what the person typed, and honest about not having segmented it. There is no language model here
 * and no pretence of one.
 */
object StickerSearch {

    const val DEFAULT_LIMIT: Int = 10
    const val MAX_LIMIT: Int = 20
    const val MIN_LIMIT: Int = 1

    private const val TAG_EXACT = 100
    private const val TAG_PARTIAL = 60
    private const val DESCRIPTION_CONTAINS = 30

    /** Whitespace plus the separators a tag list is written with, in either script. */
    private val SEPARATORS = charArrayOf(
        ' ', '\t', '\n', '\r',
        ',', '，', '、', ';', '；', '/', '|',
    )

    /** Clamps a caller-supplied limit into the range the tool advertises. */
    fun clampLimit(limit: Int?): Int = (limit ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)

    fun tokenize(query: String): List<String> = query
        .lowercase()
        .split(*SEPARATORS)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    /**
     * Tokenises [query] and ranks [candidates] against it.
     *
     * An empty or punctuation-only query returns nothing rather than everything: "no query" is not
     * a request for the whole library, and treating it as one would hand the model a wall of
     * stickers it did not ask for.
     */
    fun search(
        candidates: List<Sticker>,
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<StickerSearchHit> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        return rank(candidates, tokens, limit)
    }

    fun rank(
        candidates: List<Sticker>,
        tokens: List<String>,
        limit: Int = DEFAULT_LIMIT,
    ): List<StickerSearchHit> {
        if (tokens.isEmpty()) return emptyList()
        return candidates
            .map { StickerSearchHit(sticker = it, score = score(it, tokens)) }
            // A candidate that matched nothing is not a weak result, it is not a result. Returning
            // it would let the model pick a sticker that has nothing to do with the conversation.
            .filter { it.score > 0 }
            .sortedWith(
                compareByDescending<StickerSearchHit> { it.score }
                    // Creation time, not updatedAt: re-recognising a sticker should not reshuffle
                    // equally-relevant results, and the tie-break only has to be *stable*, not
                    // meaningful.
                    .thenByDescending { it.sticker.createdAtMs }
                    .thenBy { it.sticker.id },
            )
            .take(clampLimit(limit))
    }

    fun score(sticker: Sticker, tokens: List<String>): Int {
        val tags = sticker.tags.map { it.lowercase() }
        val description = sticker.description.lowercase()
        return tokens.sumOf { token ->
            when {
                tags.any { it == token } -> TAG_EXACT
                tags.any { it.contains(token) } -> TAG_PARTIAL
                description.contains(token) -> DESCRIPTION_CONTAINS
                else -> 0
            }
        }
    }
}
