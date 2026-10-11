package com.gatar.recipedealer.web

import com.gatar.recipedealer.model.Group
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * schema.org JSON-LD as the extractor returns it (the parsed `<script>` blocks, unresolved): `@graph`, arrays,
 * `@type` arrays and `{"@id"}` references resolved against all nodes (Yoast writes author / image that way).
 */
object JsonLd {
    /** The Recipe node with `{"@id"}` references replaced by the referenced nodes (one level deep per hop). */
    fun findRecipe(blocks: JsonElement?): JsonObject? {
        val nodes = mutableListOf<JsonObject>()
        fun collect(el: JsonElement?) {
            when (el) {
                is JsonArray -> el.forEach(::collect)
                is JsonObject -> {
                    nodes += el
                    el["@graph"]?.let(::collect)
                }
                else -> Unit
            }
        }
        collect(blocks)
        val byId = nodes.mapNotNull { n -> id(n)?.let { it to n } }.filter { it.second.size > 1 }.toMap()
        val recipe = nodes.firstOrNull { isType(it, "Recipe") } ?: return null
        return resolve(recipe, byId, depth = 0) as JsonObject
    }

    fun id(o: JsonObject): String? = (o["@id"] as? JsonPrimitive)?.contentOrNull

    fun isType(o: JsonObject, type: String): Boolean = o["@type"].array().any {
        (it as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/')?.equals(type, ignoreCase = true) == true
    }

    private fun resolve(el: JsonElement, byId: Map<String, JsonObject>, depth: Int): JsonElement = when {
        depth > 4 -> el
        el is JsonObject && el.size == 1 && el.containsKey("@id") ->
            byId[id(el)]?.let { resolve(it, byId, depth + 1) } ?: el
        el is JsonObject -> JsonObject(el.mapValues { (k, v) -> if (k == "@graph") v else resolve(v, byId, depth + 1) })
        el is JsonArray -> JsonArray(el.map { resolve(it, byId, depth + 1) })
        else -> el
    }

    /** recipeInstructions: string blob, strings, HowToStep, HowToSection (→ named group), ItemList. */
    fun instructions(el: JsonElement?): List<Group> {
        val groups = mutableListOf<Group>()
        val loose = mutableListOf<String>()
        fun flush() {
            if (loose.isNotEmpty()) groups += Group(null, loose.toList())
            loose.clear()
        }
        fun steps(e: JsonElement?): List<String> = e.array().flatMap { s ->
            when (s) {
                is JsonPrimitive -> lines(s.contentOrNull)
                is JsonObject -> if (s.containsKey("itemListElement")) steps(s["itemListElement"])
                    else listOfNotNull(stepText(s))
                else -> emptyList()
            }
        }
        for (item in el.array()) {
            when {
                item is JsonObject && (isType(item, "HowToSection") ||
                    (item.containsKey("itemListElement") && !isType(item, "ItemList"))) -> {
                    flush()
                    val items = steps(item["itemListElement"]).map(ExtractorMerge::cleanItem).filter(String::isNotEmpty)
                    if (items.isNotEmpty()) groups += Group(ExtractorMerge.groupName(ExtractorMerge.text(item["name"])), items)
                }
                else -> loose += steps(item).map(ExtractorMerge::cleanItem).filter(String::isNotEmpty)
            }
        }
        flush()
        return groups
    }

    private fun stepText(o: JsonObject): String? =
        ExtractorMerge.text(o["text"]) ?: ExtractorMerge.text(o["name"]) ?: ExtractorMerge.text(o["description"])

    /** A text blob with one step per line (HTML line breaks included). */
    private fun lines(s: String?): List<String> =
        s.orEmpty().replace(Regex("<br\\s*/?>|</p>|</li>", RegexOption.IGNORE_CASE), "\n").split('\n')
            .map(Text::norm).filter(String::isNotEmpty)

    /** image: URL, ImageObject {url | contentUrl}, or arrays of those. */
    fun images(el: JsonElement?): List<String> = el.array().mapNotNull { i ->
        when (i) {
            is JsonPrimitive -> i.contentOrNull
            is JsonObject -> ((i["url"] ?: i["contentUrl"]) as? JsonPrimitive)?.contentOrNull
            else -> null
        }?.trim()?.takeIf { it.startsWith("http") }
    }

    /** author: string, Person / Organization {name}, or arrays of those → "A, B". */
    fun names(el: JsonElement?): String? = el.array().mapNotNull { a ->
        when (a) {
            is JsonPrimitive -> a.contentOrNull
            is JsonObject -> (a["name"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }?.let(Text::norm)?.takeIf { it.isNotEmpty() }
    }.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ")
}
