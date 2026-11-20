package cosic.script

import cosic.pattern.Tonal
import cosic.script.catalogue.Catalogue

/**
 * One documented function for the reference screen. [example] is a program that runs on its own;
 * [snippet] is the same line prepared for pasting into an existing program: a pattern needs a `$:`
 * label there, because a program with labels ignores unlabelled expressions.
 */
public data class ReferenceEntry(
    val name: String,
    val aliases: List<String>,
    val category: String,
    val signature: String,
    val doc: String,
    val example: String,
    val snippet: String,
)

/** Every documented function, grouped by category, each with a one-line example that runs as a program. */
public fun reference(): List<ReferenceEntry> = Catalogue.docEntries().filter { it.doc.listed }.map { entry ->
    val doc = entry.doc
    ReferenceEntry(entry.names[0], entry.names.drop(1), doc.category, doc.signature, doc.text, doc.example, doc.snippet ?: labelled(doc.example))
}

/** The scale names that can follow a root note, as in `C:minor` (spaces written as colons: `C:harmonic:minor`): every scale of the tonal library. */
public fun scaleModes(): List<String> = Tonal.scaleModeNames()

/** [example] ready to paste into a program that has labels: it gets a `$: ` label unless it is labelled already. */
private fun labelled(example: String): String =
    if (tokenize(example).any { it.kind == TokenKind.LABEL }) example else "\$: $example"
