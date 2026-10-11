package com.ubers.observador.domain.lifecycle

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** One accessibility node copied into plain data, so no Android object outlives the read. */
data class ScreenNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val enabled: Boolean = true
)

/**
 * What the accessibility service saw at one moment, free of Android types so classification and
 * the ride lifecycle run in plain JVM tests. [nodes] are in depth-first order. The tree is read
 * once per snapshot; the offer parser and every screen detector work from this copy.
 */
data class ScreenSnapshot(
    val timestampMs: Long,
    val packageName: String,
    val nodes: List<ScreenNode>
) {
    /** Text, then description, of each node, trimmed and without blanks: what OfferParser has always read. */
    val texts: List<String> by lazy {
        nodes.flatMap { node ->
            listOfNotNull(
                node.text?.trim()?.takeIf { it.isNotEmpty() },
                node.contentDescription?.trim()?.takeIf { it.isNotEmpty() }
            )
        }
    }

    /** [texts] normalized once, for all detectors. */
    val normalizedTexts: List<String> by lazy { texts.map(TextNormalizer::normalize) }
}

/**
 * Makes labels comparable without losing what they say: "  Iniciar   Viaje\n" and "iniciar viaje"
 * normalize the same, and so do "Llegué" and "llegue". The original text is never replaced.
 */
object TextNormalizer {

    private val MARKS = Regex("\\p{M}+")
    private val INVISIBLE = Regex("[\\u200B-\\u200D\\u2060\\uFEFF]")
    private val SPACES = Regex("\\s+")
    private val DIGIT = Regex("\\d")

    fun normalize(raw: String): String {
        // NFKD splits accents off their letters and turns compatibility forms (non-breaking
        // space, ligatures, full-width letters) into plain ones
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        val plain = INVISIBLE.replace(MARKS.replace(decomposed, ""), "")
        return SPACES.replace(plain.lowercase(Locale.ROOT), " ").trim()
    }

    /** "3 min (1.2 km)" -> "# min (#.# km)": keeps the shape of a value, not the value. */
    fun maskDigits(text: String): String = DIGIT.replace(text, "#")
}

/**
 * Identity of a rendered screen, to skip work when Uber repaints the same thing. Two snapshots
 * share a fingerprint only if every node has the same id, class, tap state and text (digits
 * masked, so a countdown does not count as a new screen) and the same parsed offer, so two offers
 * with the same layout never collide. Only a short hash is kept: no label text survives in it.
 */
object ScreenFingerprint {

    private const val LENGTH = 16

    fun of(snapshot: ScreenSnapshot, offerHash: String?): String {
        val raw = StringBuilder(snapshot.packageName).append('\u0001').append(offerHash.orEmpty())
        snapshot.nodes.forEach { node ->
            raw.append('\u0001')
                .append(node.viewId.orEmpty()).append('|')
                .append(node.className.orEmpty()).append('|')
                .append(if (node.clickable) 'c' else '-').append(if (node.enabled) 'e' else '-').append('|')
                .append(TextNormalizer.maskDigits(TextNormalizer.normalize(node.text.orEmpty()))).append('|')
                .append(TextNormalizer.maskDigits(TextNormalizer.normalize(node.contentDescription.orEmpty())))
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(LENGTH)
    }
}

/**
 * A node listing that can be pasted into a chat to build detection rules, without the rider's
 * data. A label containing a known Uber phrase shows only that phrase ("dejar a" +12 car.), so a
 * name after it never leaks. Otherwise a text is shown (digits masked) only when it is a tappable
 * label of a few words or a bare number with units; anything else, such as names and addresses,
 * is replaced by its length. View ids and classes are kept: they are Uber's, not the rider's.
 */
object SnapshotSanitizer {

    const val MAX_BUTTON_WORDS = 3
    /** Words allowed next to a number: units and the connectors of Uber's "A 7 min (4.1 km)" style. */
    private val UNITS = setOf("min", "km", "dop", "rd", "h", "s", "seg", "m", "x", "a", "en", "viaje")
    private val WORD = Regex("[a-z]+")

    fun describe(snapshot: ScreenSnapshot, signatures: ScreenSignatures): String =
        buildString {
            append("paquete: ").append(snapshot.packageName).append(" · nodos: ").append(snapshot.nodes.size)
            snapshot.nodes.forEachIndexed { i, node ->
                val text = safe(node.text, node.clickable, signatures)
                val desc = safe(node.contentDescription, node.clickable, signatures)
                if (node.viewId == null && text == null && desc == null && !node.clickable) return@forEachIndexed
                append('\n').append('#').append(i)
                node.className?.let { append(' ').append(it.substringAfterLast('.')) }
                if (node.clickable) append(" [toca]")
                if (!node.enabled) append(" [inactivo]")
                node.viewId?.let { append(" id=").append(it.substringAfter(":id/")) }
                text?.let { append(" texto=").append(it) }
                desc?.let { append(" desc=").append(it) }
            }
        }

    /** The label as it may be shared, or null when the node has none. */
    fun safe(raw: String?, clickable: Boolean, signatures: ScreenSignatures): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = TextNormalizer.normalize(trimmed)
        signatures.knownPhraseIn(normalized)?.let { phrase ->
            val rest = normalized.length - phrase.length
            return "\"" + phrase + "\"" + if (rest > 0) " +$rest car." else ""
        }
        val keep = isBareMeasure(normalized) || (clickable && normalized.split(' ').size <= MAX_BUTTON_WORDS)
        return if (keep) "\"" + TextNormalizer.maskDigits(normalized) + "\"" else "‹oculto, ${trimmed.length} car.›"
    }

    /** "dop 136.06", "7 min (4.1 km)": a number plus units only, no words that could name anyone. */
    private fun isBareMeasure(normalized: String): Boolean =
        normalized.any { it.isDigit() } && WORD.findAll(normalized).all { it.value in UNITS }
}
