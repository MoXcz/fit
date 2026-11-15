// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import it.krzeminski.snakeyaml.engine.kmp.nodes.MappingNode
import it.krzeminski.snakeyaml.engine.kmp.nodes.Node
import it.krzeminski.snakeyaml.engine.kmp.nodes.ScalarNode
import it.krzeminski.snakeyaml.engine.kmp.nodes.SequenceNode
import it.krzeminski.snakeyaml.engine.kmp.nodes.Tag

// Collects the problems of one file. YAML marks are 0-based and count from the header's first line.
internal class ParseContext(
    val file: String,
    private val firstYamlLine: Int,
) {
    val problems = mutableListOf<ContentProblem>()

    fun line(node: Node): Int = (node.startMark?.line ?: 0) + firstYamlLine

    fun badValue(
        node: Node,
        field: String,
        expected: String,
    ) {
        problems += ContentProblem.BadValue(file, line(node), field, expected)
    }
}

// A YAML mapping read key by key; [finish] reports every key nobody asked for, so typos are caught.
internal class YamlMap(
    private val context: ParseContext,
    private val node: MappingNode,
    private val path: String,
) {
    private val entries = LinkedHashMap<String, Node>()
    private val keyLines = HashMap<String, Int>()
    private val read = HashSet<String>()

    init {
        for (tuple in node.value) {
            val key = (tuple.keyNode as? ScalarNode)?.value
            if (key == null) {
                context.badValue(tuple.keyNode, fieldOf("?"), "a plain key")
                continue
            }
            if (key in entries) {
                context.problems += ContentProblem.DuplicateKey(context.file, context.line(tuple.keyNode), fieldOf(key))
                continue
            }
            entries[key] = tuple.valueNode
            keyLines[key] = context.line(tuple.keyNode)
        }
    }

    val line: Int get() = context.line(node)

    // Where [key] is written, or the map's own line.
    fun lineOf(key: String): Int = keyLines[key] ?: line

    fun fieldOf(key: String): String = if (path.isEmpty()) key else "$path.$key"

    private fun take(
        key: String,
        required: Boolean,
    ): Node? {
        read += key
        val value = entries[key]
        if (value == null || value.isNull()) {
            if (required) context.problems += ContentProblem.MissingKey(context.file, keyLines[key] ?: line, fieldOf(key))
            return null
        }
        return value
    }

    // Any scalar reads as text, so `name: 300` is the name "300".
    fun string(
        key: String,
        required: Boolean = false,
    ): String? {
        val value = take(key, required) ?: return null
        val text = value.scalar()
        if (text == null) context.badValue(value, fieldOf(key), "text")
        return text
    }

    fun <T> parsed(
        key: String,
        expected: String,
        required: Boolean = false,
        parse: (String) -> T?,
    ): T? {
        val value = take(key, required) ?: return null
        val result = value.scalar()?.let(parse)
        if (result == null) context.badValue(value, fieldOf(key), expected)
        return result
    }

    fun int(
        key: String,
        range: IntRange,
        required: Boolean = false,
    ): Int? =
        parsed(key, "a whole number from ${range.first} to ${range.last}", required) { text ->
            text.toIntOrNull()?.takeIf { it in range }
        }

    fun map(
        key: String,
        required: Boolean = false,
    ): YamlMap? {
        val value = take(key, required) ?: return null
        if (value !is MappingNode) {
            context.badValue(value, fieldOf(key), "a map")
            return null
        }
        return YamlMap(context, value, fieldOf(key))
    }

    fun list(
        key: String,
        required: Boolean = false,
    ): List<Node>? {
        val value = take(key, required) ?: return null
        if (value !is SequenceNode) {
            context.badValue(value, fieldOf(key), "a list")
            return null
        }
        return value.value
    }

    // A list of scalars, each checked by [parse]; bad entries are reported and dropped.
    fun <T> scalarList(
        key: String,
        expected: String,
        parse: (String) -> T?,
    ): List<T>? = scalarLines(key, expected, parse)?.map { it.first }

    // As [scalarList], with each entry's line.
    fun <T> scalarLines(
        key: String,
        expected: String,
        parse: (String) -> T?,
    ): List<Pair<T, Int>>? =
        list(key)?.mapIndexedNotNull { index, item ->
            val result = item.scalar()?.let(parse)
            if (result == null) context.badValue(item, "${fieldOf(key)}[$index]", expected)
            result?.let { it to context.line(item) }
        }

    // Maps inside a list, each read with its own unknown-key check.
    fun mapList(
        key: String,
        required: Boolean = false,
    ): List<YamlMap>? =
        list(key, required)?.mapIndexedNotNull { index, item ->
            if (item is MappingNode) {
                YamlMap(context, item, "${fieldOf(key)}[$index]")
            } else {
                context.badValue(item, "${fieldOf(key)}[$index]", "a map")
                null
            }
        }

    // For a value that read fine but doesn't make sense as a whole (an empty list where one is needed).
    fun bad(
        key: String,
        expected: String,
    ) {
        entries[key]?.let { context.badValue(it, fieldOf(key), expected) }
    }

    // For a map whose keys read fine but don't fit together (a step with both a duration and reps).
    fun badHere(
        key: String,
        expected: String,
    ) {
        context.problems += ContentProblem.BadValue(context.file, lineOf(key), fieldOf(key), expected)
    }

    fun has(key: String): Boolean = key in entries

    fun finish() {
        for (key in entries.keys - read) {
            context.problems += ContentProblem.UnknownKey(context.file, keyLines.getValue(key), fieldOf(key))
        }
    }
}

private fun Node.isNull(): Boolean = this is ScalarNode && tag == Tag.NULL

private fun Node.scalar(): String? = (this as? ScalarNode)?.value
