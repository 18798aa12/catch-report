package com.zhouqishun.catchreport

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MihomoProxyGroup(
    val name: String,
    val type: String,
    val selected: String,
    val nodes: List<String>
)

object MihomoController {
    private const val BASE_URL = "http://127.0.0.1:9090"

    fun listGroups(): List<MihomoProxyGroup> {
        val json = request("GET", "/proxies")
        val proxies = JSONObject(json).getJSONObject("proxies")
        val groups = mutableListOf<MihomoProxyGroup>()

        for (name in proxies.keys()) {
            val entry = proxies.optJSONObject(name) ?: continue
            if (!entry.has("all")) continue

            val nodes = entry.getJSONArray("all").let { values ->
                List(values.length()) { index -> values.getString(index) }
            }
            if (nodes.isEmpty()) continue

            groups += MihomoProxyGroup(
                name = name,
                type = entry.optString("type"),
                selected = entry.optString("now"),
                nodes = nodes
            )
        }

        return groups.sortedWith(compareBy<MihomoProxyGroup> { groupPriority(it.name) }.thenBy { it.name })
    }

    fun selectNode(groupName: String, nodeName: String) {
        val encodedGroup = Uri.encode(groupName)
        request(
            method = "PUT",
            path = "/proxies/$encodedGroup",
            body = JSONObject(mapOf("name" to nodeName)).toString()
        )
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val connection = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3000
            readTimeout = 8000
            useCaches = false
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val code = connection.responseCode
            val input = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = input?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("mihomo controller HTTP $code: $response")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun groupPriority(name: String): Int {
        return when {
            name == "节点选择" -> 0
            name.contains("节点") -> 1
            name.equals("PROXY", ignoreCase = true) -> 2
            name.equals("Proxy", ignoreCase = true) -> 2
            name.equals("GLOBAL", ignoreCase = true) -> 3
            else -> 4
        }
    }
}
