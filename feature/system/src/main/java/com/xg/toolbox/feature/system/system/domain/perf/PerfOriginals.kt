package com.xg.toolbox.feature.system.domain.perf

import org.json.JSONObject

internal data class PerfOriginals(
    val governors: Map<String, String>,
    val cpuMaxFreqKhz: Map<String, Long>,
    val cpuMinFreqKhz: Map<String, Long>,
    val gpuMinFreqHz: Long?,
    val gpuMaxFreqHz: Long?,
    val ioScheduler: String?,
    val thermalPolicies: Map<String, String>,
    val lowPower: String?,
    val zramDisksizeBytes: Long?,
) {

    fun toJson(): String = JSONObject().apply {
        put("governors", JSONObject(governors))
        put("cpuMaxFreqKhz", JSONObject(cpuMaxFreqKhz))
        put("cpuMinFreqKhz", JSONObject(cpuMinFreqKhz))
        gpuMinFreqHz?.let { put("gpuMinFreqHz", it) }
        gpuMaxFreqHz?.let { put("gpuMaxFreqHz", it) }
        ioScheduler?.let { put("ioScheduler", it) }
        put("thermalPolicies", JSONObject(thermalPolicies))
        lowPower?.let { put("lowPower", it) }
        zramDisksizeBytes?.let { put("zramDisksizeBytes", it) }
    }.toString()

    companion object {

        
        fun from(snapshot: PerfSnapshot): PerfOriginals = PerfOriginals(
            governors = snapshot.clusters.associate { it.policyPath to it.governor },
            cpuMaxFreqKhz = snapshot.clusters.associate { it.policyPath to it.maxFreqKhz },
            cpuMinFreqKhz = snapshot.clusters.associate { it.policyPath to it.minFreqKhz },
            gpuMinFreqHz = snapshot.gpu?.minFreqHz,
            gpuMaxFreqHz = snapshot.gpu?.maxFreqHz,
            ioScheduler = snapshot.io?.current,
            thermalPolicies = snapshot.thermalZones.associate { it.path to it.policy },
            lowPower = snapshot.lowPower?.let { if (it) "1" else "0" },
            zramDisksizeBytes = snapshot.zram?.disksizeBytes,
        )

        
        fun fromJson(raw: String): PerfOriginals? = runCatching {
            val root = JSONObject(raw)
            PerfOriginals(
                governors = root.optJSONObject("governors").toStringMap(),
                cpuMaxFreqKhz = root.optJSONObject("cpuMaxFreqKhz").toLongMap(),
                cpuMinFreqKhz = root.optJSONObject("cpuMinFreqKhz").toLongMap(),
                gpuMinFreqHz = root.optLongOrNull("gpuMinFreqHz"),
                gpuMaxFreqHz = root.optLongOrNull("gpuMaxFreqHz"),
                ioScheduler = root.optStringOrNull("ioScheduler"),
                thermalPolicies = root.optJSONObject("thermalPolicies").toStringMap(),
                lowPower = root.optStringOrNull("lowPower"),
                zramDisksizeBytes = root.optLongOrNull("zramDisksizeBytes"),
            )
        }.getOrNull()

        private fun JSONObject?.toStringMap(): Map<String, String> {
            if (this == null) return emptyMap()
            val result = mutableMapOf<String, String>()
            keys().forEach { key -> result[key] = optString(key) }
            return result
        }

        private fun JSONObject?.toLongMap(): Map<String, Long> {
            if (this == null) return emptyMap()
            val result = mutableMapOf<String, Long>()
            keys().forEach { key -> optLong(key, -1L).takeIf { it >= 0L }?.let { result[key] = it } }
            return result
        }

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (has(key)) optLong(key).takeIf { it >= 0L } else null

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key)) optString(key).takeIf { it.isNotBlank() } else null
    }
}
