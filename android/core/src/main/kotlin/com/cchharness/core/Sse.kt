package com.cchharness.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Incremental Server-Sent Events parser. Feed it one line at a time (without
 * the line terminator); it returns an event when a blank line completes one.
 * Only `data:` is interpreted: the harness puts `{"seq","ts","data"}` there.
 */
class SseParser {
    private val data = StringBuilder()
    private var hasData = false

    fun feed(line: String): StreamEvent? {
        if (line.isEmpty()) {
            if (!hasData) return null
            val payload = data.toString()
            data.setLength(0)
            hasData = false
            return parseEvent(payload)
        }
        if (line.startsWith(":")) return null // comment / heartbeat
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        if (field == "data") {
            if (hasData) data.append('\n')
            data.append(value)
            hasData = true
        }
        return null
    }

    companion object {
        fun parseEvent(payload: String): StreamEvent? = try {
            val o = HarnessJson.parseToJsonElement(payload).jsonObject
            val inner = o["data"] as? JsonObject ?: return null
            StreamEvent(
                seq = o["seq"]?.jsonPrimitive?.longOrNull ?: 0,
                ts = o["ts"]?.jsonPrimitive?.longOrNull ?: 0,
                data = inner,
            )
        } catch (e: Exception) {
            null // a malformed event must not kill the stream
        }
    }
}
