package com.xg.toolbox.feature.device

internal object DeviceCopyFormatter {

    private const val DASH = "—"

    
    fun groupText(group: DeviceGroupUi): String =
        group.fields.joinToString(separator = "\n") { field ->
            "${field.name}: ${field.value ?: DASH}"
        }

    
    fun markdown(
        groups: List<DeviceGroupUi>,
        title: String,
        groupLabel: String,
        nameLabel: String,
        keyLabel: String,
        valueLabel: String,
    ): String {
        val builder = StringBuilder()
        builder.append("# ").append(title).append("\n\n")
        builder.append("| ").append(groupLabel)
            .append(" | ").append(nameLabel)
            .append(" | ").append(keyLabel)
            .append(" | ").append(valueLabel)
            .append(" |\n")
        builder.append("| --- | --- | --- | --- |\n")
        groups.forEach { group ->
            group.fields.forEach { field ->
                builder.append("| ").append(cell(group.title))
                    .append(" | ").append(cell(field.name))
                    .append(" | ").append(cell(field.propertyKey))
                    .append(" | ").append(cell(field.value))
                    .append(" |\n")
            }
        }
        return builder.toString().trimEnd('\n')
    }

    private fun cell(raw: String?): String {
        if (raw.isNullOrBlank()) return DASH
        return raw.replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
    }
}
