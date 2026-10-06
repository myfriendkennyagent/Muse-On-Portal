package com.myfriendkennyagent.museportal.protocol.link

import org.json.JSONObject

/** One parameter of a device command, as Muse sees it. */
data class Param(val name: String, val type: String, val description: String)

/**
 * A command Muse can invoke on the device (`commands_v2` in `link.register`).
 * Muse reads [description] to decide when and how to call it, so it should
 * say what the command does and what the result means.
 */
data class CommandSpec(
  val name: String,
  val description: String,
  val required: List<Param> = emptyList(),
  val optional: List<Param> = emptyList(),
  val timeoutMs: Long? = null,
) {
  fun toJson(): JSONObject {
    fun params(list: List<Param>) =
      JSONObject().also { obj ->
        list.forEach { obj.put(it.name, JSONObject().put("type", it.type).put("description", it.description)) }
      }
    val json =
      JSONObject().put("description", description).put("required", params(required)).put("optional", params(optional))
    timeoutMs?.let { json.put("timeout_ms", it) }
    return json
  }
}

fun List<CommandSpec>.toCommandsJson(): JSONObject =
  JSONObject().also { obj -> forEach { obj.put(it.name, it.toJson()) } }

/** `link.result` bodies, as upstream's `ok()` and `error()`. */
object CommandResult {
  fun ok(payload: JSONObject = JSONObject()): JSONObject = JSONObject().put("ok", true).put("payload", payload)

  fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)
}

/** Runs one `link.invoke`; returns [CommandResult.ok] or [CommandResult.error]. */
fun interface CommandRunner {
  suspend fun run(command: String, params: JSONObject, timeoutMs: Long?): JSONObject
}
