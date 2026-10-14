package com.sportcamhud.core.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One indicator's settings. Stored as the same JSON object the Windows app keeps in
 * its layout/preset files, so presets can be moved between Windows and Android.
 */
class IndicatorConfig(val key: String, val props: JsonObject) {
    operator fun contains(name: String) = props[name] != null && props[name] !is JsonNull

    private fun prim(name: String): JsonPrimitive? = (props[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    fun double(name: String, default: Double): Double = prim(name)?.let { it.doubleOrNull ?: it.booleanOrNull?.let { b -> if (b) 1.0 else 0.0 } } ?: default
    fun doubleOrNull(name: String): Double? = prim(name)?.doubleOrNull
    fun float(name: String, default: Float): Float = double(name, default.toDouble()).toFloat()
    fun int(name: String, default: Int): Int = prim(name)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: default
    fun bool(name: String, default: Boolean): Boolean = prim(name)?.let {
        it.booleanOrNull ?: it.doubleOrNull?.let { d -> d != 0.0 } ?: it.contentOrNull?.lowercase()?.let { s -> s == "true" }
    } ?: default
    fun string(name: String, default: String): String = prim(name)?.contentOrNull ?: default

    val enabled get() = bool("enabled", true)
    val form get() = normalizeForm(string("form", "text"))
    val x get() = double("x", 50.0)
    val y get() = double("y", 50.0)
    val rotation get() = int("rotation", 0)
    val label get() = string("label", "")

    fun with(name: String, value: Any?): IndicatorConfig = IndicatorConfig(key, JsonObject(props + (name to toJson(value))))
    fun withAll(values: Map<String, Any?>): IndicatorConfig =
        IndicatorConfig(key, JsonObject(props + values.mapValues { toJson(it.value) }))

    override fun equals(other: Any?) = other is IndicatorConfig && other.key == key && other.props == props
    override fun hashCode() = key.hashCode() * 31 + props.hashCode()

    companion object {
        private val FORM_ALIASES = mapOf("TEXT" to "text", "SUWAK" to "bar", "LICZNIK" to "text")
        fun normalizeForm(form: String) = FORM_ALIASES[form] ?: form

        fun toJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is List<*> -> JsonArray(value.map { toJson(it) })
            is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJson(it.value) })
            else -> JsonPrimitive(value.toString())
        }
    }
}

/**
 * The HUD layout: the same structure as the Windows `def_layout.json` presets
 * (`version`, `global`, `indicators`, `custom_texts`, `smoothing`).
 */
class HudLayout(
    val indicators: LinkedHashMap<String, IndicatorConfig>,
    val global: JsonObject = JsonObject(mapOf("text_outline" to JsonPrimitive(3))),
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    val textOutline: Int get() = (global["text_outline"] as? JsonPrimitive)?.intOrNull ?: 3

    fun replace(cfg: IndicatorConfig): HudLayout {
        val copy = LinkedHashMap(indicators)
        copy[cfg.key] = cfg
        return HudLayout(copy, global, extra)
    }

    fun remove(key: String): HudLayout {
        val copy = LinkedHashMap(indicators)
        copy.remove(key)
        return HudLayout(copy, global, extra)
    }

    fun toJson(): String {
        val obj = LinkedHashMap<String, JsonElement>()
        obj.putAll(extra)
        obj["version"] = JsonPrimitive(6)
        obj["global"] = global
        obj["indicators"] = JsonObject(indicators.mapValues { it.value.props })
        return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(obj))
    }

    companion object {
        private val prettyJson = Json { prettyPrint = true }

        /** Parses a Windows or Android preset. Unknown indicators are kept as-is. */
        fun fromJson(text: String): HudLayout {
            val root = Json.parseToJsonElement(text).jsonObject
            val inds = LinkedHashMap<String, IndicatorConfig>()
            (root["indicators"] as? JsonObject)?.forEach { (k, v) ->
                if (v is JsonObject) inds[k] = IndicatorConfig(k, v)
            }
            val global = root["global"] as? JsonObject ?: JsonObject(mapOf("text_outline" to JsonPrimitive(3)))
            val extra = JsonObject(root.filterKeys { it != "indicators" && it != "global" && it != "version" })
            return HudLayout(inds, global, extra)
        }

        /** Merges a preset over the defaults so missing built-ins still exist (normalize_layout). */
        fun fromJsonMergedWithDefaults(text: String): HudLayout {
            val loaded = fromJson(text)
            val base = DefaultLayout.create()
            val merged = LinkedHashMap<String, IndicatorConfig>()
            for ((k, v) in base.indicators) {
                val user = loaded.indicators[k]
                merged[k] = if (user == null) v else IndicatorConfig(k, JsonObject(v.props + user.props))
            }
            for ((k, v) in loaded.indicators) if (k !in merged) merged[k] = v
            return HudLayout(merged, loaded.global, loaded.extra)
        }
    }
}
