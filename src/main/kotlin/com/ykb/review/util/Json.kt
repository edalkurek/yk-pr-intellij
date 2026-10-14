package com.ykb.review.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Gson ağaç modeli için null-güvenli okuma yardımcıları (Kotlin data class + Gson eksik alan sorunlarından kaçınmak için). */
fun JsonElement?.obj(): JsonObject = if (this != null && isJsonObject) asJsonObject else JsonObject()

fun JsonElement?.arr(): JsonArray = if (this != null && isJsonArray) asJsonArray else JsonArray()

fun JsonObject.str(name: String): String {
    val e = get(name) ?: return ""
    return if (e.isJsonPrimitive) e.asJsonPrimitive.asString else ""
}

fun JsonObject.long(name: String): Long? {
    val e = get(name) ?: return null
    if (!e.isJsonPrimitive) return null
    val p = e.asJsonPrimitive
    return if (p.isNumber) p.asLong else p.asString.toLongOrNull()
}

fun JsonObject.bool(name: String): Boolean {
    val e = get(name) ?: return false
    if (!e.isJsonPrimitive) return false
    val p = e.asJsonPrimitive
    return if (p.isBoolean) p.asBoolean else p.asString.equals("true", ignoreCase = true)
}
