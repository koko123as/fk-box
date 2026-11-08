// YAML node handling for FlClash's profile methods, after FlClash v0.9.3
// core/profile_base.go (GPL-3.0): the profile and the overlay are merged as
// node trees, so scalar styles, tags and anchors reach mihomo as written.
package com.follow.clash.core

import org.json.JSONArray
import org.json.JSONObject
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.StreamDataWriter
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.composer.Composer
import org.snakeyaml.engine.v2.emitter.Emitter
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.NodeTuple
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.parser.ParserImpl
import org.snakeyaml.engine.v2.scanner.StreamReader
import org.snakeyaml.engine.v2.serializer.Serializer

internal object FkYaml {
    // What makes a profile large stays with the profile; the overlay replaces every other section.
    private val profileBaseSections = listOf("proxies", "proxy-groups", "sub-rules")

    private val loadSettings: LoadSettings = LoadSettings.builder()
        .setAllowDuplicateKeys(true)
        .setCodePointLimit(Int.MAX_VALUE)
        .setMaxAliasesForCollections(Int.MAX_VALUE)
        .build()

    private val dumpSettings: DumpSettings = DumpSettings.builder()
        .setDefaultFlowStyle(FlowStyle.BLOCK)
        .setWidth(Int.MAX_VALUE)
        .build()

    fun compose(text: String): Node? {
        val composer = Composer(loadSettings, ParserImpl(loadSettings, StreamReader(loadSettings, text)))
        return if (composer.hasNext()) composer.next() else null
    }

    fun dump(node: Node): String {
        val builder = StringBuilder()
        val writer = object : StreamDataWriter {
            override fun write(str: String) {
                builder.append(str)
            }

            override fun write(str: String, off: Int, len: Int) {
                builder.append(str, off, off + len)
            }
        }
        val serializer = Serializer(dumpSettings, Emitter(dumpSettings, writer))
        serializer.emitStreamStart()
        serializer.serializeDocument(node)
        serializer.emitStreamEnd()
        return builder.toString()
    }

    fun mapping(text: String): MappingNode {
        val node = compose(text) ?: return emptyMapping()
        return when {
            node is MappingNode -> node
            node is ScalarNode && node.tag == Tag.NULL -> emptyMapping()
            else -> throw CoreMethodError("config_error", "line ${node.startMark.map { it.line + 1 }.orElse(0)}: a config must be a mapping")
        }
    }

    private fun emptyMapping() = MappingNode(Tag.MAP, mutableListOf(), FlowStyle.BLOCK)

    /** A key the mapping lacks is looked up in its merge keys, as the decoder does. */
    fun value(mapping: MappingNode, key: String): Node? {
        val sources = mutableListOf<Node>()
        for (tuple in mapping.value) {
            val name = tuple.keyNode as? ScalarNode ?: continue
            if (name.value == "<<" && name.isPlain) sources += tuple.valueNode
            else if (name.value == key) return tuple.valueNode
        }
        for (source in sources) {
            val candidates = if (source is SequenceNode) source.value else listOf(source)
            for (candidate in candidates) {
                if (candidate is MappingNode) value(candidate, key)?.let { return it }
            }
        }
        return null
    }

    fun setValue(mapping: MappingNode, key: String, value: Node) {
        val tuples = mapping.value
        val index = tuples.indexOfFirst { (it.keyNode as? ScalarNode)?.value == key }
        val tuple = NodeTuple(ScalarNode(Tag.STR, key, ScalarStyle.PLAIN), value)
        if (index >= 0) tuples[index] = NodeTuple(tuples[index].keyNode, value) else tuples.add(tuple)
    }

    fun remove(mapping: MappingNode, key: String) {
        mapping.value.removeAll { (it.keyNode as? ScalarNode)?.value == key }
    }

    private fun isNull(node: Node?) = node is ScalarNode && node.tag == Tag.NULL

    /** core/profile_base.go mergeProfileBase: the overlay with the profile's base sections and rules after the overlay's. */
    fun mergeProfileBase(profileText: String, overlayText: String): MappingNode {
        val profile = mapping(profileText)
        val root = mapping(overlayText)
        for (key in profileBaseSections) {
            value(profile, key)?.let { setValue(root, key, it) }
        }
        val added = value(root, "rules")
        val rules = value(profile, "rules")
        if (rules != null) {
            val merged = when {
                added == null || isNull(added) || (added is SequenceNode && added.value.isEmpty()) -> rules
                isNull(rules) -> added
                added is SequenceNode && rules is SequenceNode ->
                    SequenceNode(Tag.SEQ, (added.value + rules.value).toMutableList(), FlowStyle.BLOCK)
                else -> throw CoreMethodError("config_error", "rules must be a list")
            }
            setValue(root, "rules", merged)
        }
        if (profileText.contains("short-id")) retagShortIds(root, HashSet())
        return root
    }

    // A REALITY short-id such as 0123 must reach mihomo as text, not a number.
    private fun retagShortIds(node: Node, seen: MutableSet<Node>) {
        if (!seen.add(node)) return
        when (node) {
            is MappingNode -> node.value.forEachIndexed { index, tuple ->
                val key = (tuple.keyNode as? ScalarNode)?.value
                val value = tuple.valueNode
                if (key == "short-id" && value is ScalarNode && value.isPlain && value.tag != Tag.NULL) {
                    node.value[index] = NodeTuple(tuple.keyNode, ScalarNode(Tag.STR, value.value, ScalarStyle.DOUBLE_QUOTED))
                } else {
                    retagShortIds(value, seen)
                }
            }
            is SequenceNode -> node.value.forEach { retagShortIds(it, seen) }
            else -> Unit
        }
    }

    /** The JSON a mihomo RawConfig would marshal to, closely enough for the Dart models. */
    fun toJson(node: Node?, seen: MutableSet<Node> = HashSet()): Any? = when (node) {
        null -> null
        is ScalarNode -> scalar(node)
        is SequenceNode -> JSONArray().also { array -> node.value.forEach { array.put(toJson(it, seen) ?: JSONObject.NULL) } }
        is MappingNode -> {
            val result = JSONObject()
            val merges = mutableListOf<Node>()
            for (tuple in node.value) {
                val key = (tuple.keyNode as? ScalarNode)?.value ?: continue
                if (key == "<<" && (tuple.keyNode as ScalarNode).isPlain) merges += tuple.valueNode
                else result.put(key, toJson(tuple.valueNode, seen) ?: JSONObject.NULL)
            }
            for (merge in merges) {
                val sources = if (merge is SequenceNode) merge.value else listOf(merge)
                for (source in sources) {
                    val values = toJson(source, seen) as? JSONObject ?: continue
                    for (key in values.keys()) if (!result.has(key)) result.put(key, values.get(key))
                }
            }
            result
        }
        else -> null
    }

    private fun scalar(node: ScalarNode): Any? {
        val text = node.value
        return when (node.tag) {
            Tag.NULL -> null
            Tag.BOOL -> text.equals("true", ignoreCase = true)
            Tag.INT -> parseInt(text) ?: text
            Tag.FLOAT -> text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: text
            else -> text
        }
    }

    private fun parseInt(text: String): Long? {
        val value = text.replace("_", "")
        return when {
            value.startsWith("0x") || value.startsWith("0X") -> value.substring(2).toLongOrNull(16)
            value.startsWith("0o") -> value.substring(2).toLongOrNull(8)
            else -> value.toLongOrNull()
        }
    }
}
