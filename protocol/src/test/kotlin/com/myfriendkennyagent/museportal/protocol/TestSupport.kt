package com.myfriendkennyagent.museportal.protocol

import org.json.JSONObject

object TestSupport {
  fun resource(path: String): String =
    TestSupport::class.java.classLoader.getResourceAsStream(path)!!.use { String(it.readBytes(), Charsets.UTF_8) }

  fun json(path: String): JSONObject = JSONObject(resource(path))

  fun pairingVectors(): List<JSONObject> {
    val array = json("vectors/link_pairing_v5.json").getJSONArray("vectors")
    return (0 until array.length()).map { array.getJSONObject(it) }
  }

  fun pairingVector(name: String): JSONObject = pairingVectors().first { it.getString("name") == name }
}
