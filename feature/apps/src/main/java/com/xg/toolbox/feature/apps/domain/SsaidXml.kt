package com.xg.toolbox.feature.apps.domain

internal object SsaidXml {

    private val SETTING_NODE = Regex(
        """<setting\b[^>]*?/>|<setting\b[^>]*?>.*?</setting>""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private val NAME_ATTR = Regex("""\bname\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val PACKAGE_ATTR = Regex("""\bpackage\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val VALUE_ATTR = Regex("""\bvalue\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val DEFAULT_VALUE_ATTR = Regex("""\bdefaultValue\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val ID_ATTR = Regex("""\bid\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private val HEX_16 = Regex("^[0-9A-Fa-f]{16}$")
    private val UID = Regex("^[0-9]+$")

    data class RewriteResult(
        val xml: String,
        val totalEntries: Int,
        val rewrittenEntries: Int,
        val unresolvedPackages: List<String>,
    )

    data class VerifyResult(
        val totalEntries: Int,
        val unresolvedPackages: List<String>,
        val mismatchedPackages: List<String>,
    )

    data class Lookup(
        val found: Boolean,
        val ssaid: String?,
        val error: String? = null,
    )

    fun removePackageEntry(xml: String, pkg: String): String? {
        val output = StringBuilder(xml.length)
        var cursor = 0
        var changed = false
        for (match in SETTING_NODE.findAll(xml)) {
            output.append(xml, cursor, match.range.first)
            if (matchesPackage(match.value, pkg)) {
                changed = true
            } else {
                output.append(match.value)
            }
            cursor = match.range.last + 1
        }
        output.append(xml, cursor, xml.length)
        return if (changed) output.toString() else null
    }

    fun hasPackageEntry(xml: String, pkg: String): Boolean =
        SETTING_NODE.findAll(xml).any { matchesPackage(it.value, pkg) }

    fun countEntries(xml: String): Int = SETTING_NODE.findAll(xml).count()

    fun ssaidEntryCount(xml: String): Int = entries(xml).size

    fun rewriteAll(xml: String, id16: String): RewriteResult {
        var total = 0
        var rewritten = 0
        val unresolved = mutableListOf<String>()
        val rewrittenXml = SETTING_NODE.replace(xml) { match ->
            val entry = parse(match.value) ?: return@replace match.value
            total++
            if (entry.ssaid == null) {
                unresolved += entry.packageName
                return@replace match.value
            }
            var tag = match.value
            if (entry.valueIsHex) tag = replaceAttr(tag, "value", id16)
            if (entry.defaultValueIsHex) tag = replaceAttr(tag, "defaultValue", id16)
            if (entry.idIsSsaid) tag = replaceAttr(tag, "id", id16)
            if (tag != match.value) rewritten++
            tag
        }
        return RewriteResult(rewrittenXml, total, rewritten, unresolved)
    }

    fun verifyAll(xml: String, id16: String): VerifyResult {
        val entries = entries(xml)
        val unresolved = mutableListOf<String>()
        val mismatched = mutableListOf<String>()
        for (entry in entries) {
            if (entry.ssaid == null) {
                unresolved += entry.packageName
                continue
            }
            val valueMismatch = entry.valueIsHex && !entry.value.equals(id16, ignoreCase = true)
            val defaultMismatch =
                entry.defaultValueIsHex && !entry.defaultValue.equals(id16, ignoreCase = true)
            val idMismatch = entry.idIsSsaid && !entry.id.equals(id16, ignoreCase = true)
            if (valueMismatch || defaultMismatch || idMismatch ||
                !entry.ssaid.equals(id16, ignoreCase = true)
            ) {
                mismatched += entry.packageName
            }
        }
        return VerifyResult(entries.size, unresolved, mismatched)
    }

    fun currentSsaid(xml: String, pkg: String): Lookup {
        val matches = entries(xml).filter { it.matchesPackage(pkg) }
        if (matches.isEmpty()) return Lookup(found = false, ssaid = null)
        if (matches.size > 1) {
            return Lookup(
                found = true,
                ssaid = null,
                error = "该包存在 ${matches.size} 条 SSAID 记录，无法确定当前值",
            )
        }
        val entry = matches.first()
        if (entry.ssaid == null) {
            return Lookup(
                found = true,
                ssaid = null,
                error = "该包 SSAID 记录无法解析为 16 位十六进制",
            )
        }
        return Lookup(found = true, ssaid = entry.ssaid)
    }

    private fun entries(xml: String): List<Entry> =
        SETTING_NODE.findAll(xml).mapNotNull { parse(it.value) }.toList()

    private fun parse(tag: String): Entry? {
        val name = attr(NAME_ATTR, tag)
        if (name == "userkey") return null
        val packageAttr = attr(PACKAGE_ATTR, tag)
        val value = attr(VALUE_ATTR, tag)
        val defaultValue = attr(DEFAULT_VALUE_ATTR, tag)
        val id = attr(ID_ATTR, tag)
        val valueIsHex = value != null && HEX_16.matches(value)
        val defaultValueIsHex = defaultValue != null && HEX_16.matches(defaultValue)
        val idIsHex = id != null && HEX_16.matches(id)
        val packageName = when {
            packageAttr != null -> packageAttr
            name?.startsWith("id/") == true -> name.removePrefix("id/")
            name != null && !UID.matches(name) && (valueIsHex || defaultValueIsHex || idIsHex) -> name
            else -> null
        } ?: return null
        val idIsSsaid = idIsHex && (name == null || name.startsWith("id/"))
        val ssaid = when {
            valueIsHex -> value
            defaultValueIsHex -> defaultValue
            idIsSsaid -> id
            else -> null
        }
        return Entry(
            packageName = packageName,
            name = name,
            value = value,
            defaultValue = defaultValue,
            id = id,
            valueIsHex = valueIsHex,
            defaultValueIsHex = defaultValueIsHex,
            idIsSsaid = idIsSsaid,
            ssaid = ssaid,
        )
    }

    private data class Entry(
        val packageName: String,
        val name: String?,
        val value: String?,
        val defaultValue: String?,
        val id: String?,
        val valueIsHex: Boolean,
        val defaultValueIsHex: Boolean,
        val idIsSsaid: Boolean,
        val ssaid: String?,
    ) {
        fun matchesPackage(pkg: String): Boolean =
            packageName == pkg || name == "id/$pkg"
    }

    private fun replaceAttr(tag: String, name: String, value: String): String {
        val regex = Regex(
            "(?<![\\w:.-])" + Regex.escape(name) + "\\s*=\\s*(?:\"[^\"]*\"|'[^']*')",
        )
        return regex.replace(tag, "$name=\"$value\"")
    }

    private fun matchesPackage(tag: String, pkg: String): Boolean {
        val name = attr(NAME_ATTR, tag)
        val packageName = attr(PACKAGE_ATTR, tag)
        return packageName == pkg || name == "id/$pkg"
    }

    private fun attr(regex: Regex, tag: String): String? {
        val match = regex.find(tag) ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?: match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
    }
}
