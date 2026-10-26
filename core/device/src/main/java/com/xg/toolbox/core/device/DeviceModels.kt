package com.xg.toolbox.core.device

enum class FieldRestriction {
    NONE,
    UNAVAILABLE,
    MASKED,
    NEEDS_ROOT,
    LIMITED,
}

data class DeviceField(
    val id: String,
    val groupId: String,
    val name: String,
    val propertyKey: String,
    val value: String?,
    val restriction: FieldRestriction = FieldRestriction.NONE,
    val note: String? = null,
)

data class DeviceGroup(
    val id: String,
    val title: String,
    val fields: List<DeviceField>,
)

internal object DeviceGroupIds {
    const val IDENTITY = "identity"
    const val SYSTEM = "system"
    const val IDENTIFIERS = "identifiers"
    const val HARDWARE = "hardware"
    const val NETWORK = "network"
    const val SECURITY = "security"
}

internal object DeviceFieldIds {
    const val IDENTITY_NAME = "identity.name"
    const val IDENTITY_MODEL = "identity.model"
    const val IDENTITY_MARKET_NAME = "identity.marketName"
    const val IDENTITY_BRAND = "identity.brand"
    const val IDENTITY_MANUFACTURER = "identity.manufacturer"
    const val IDENTITY_DEVICE = "identity.device"
    const val IDENTITY_SERIAL = "identity.serial"
    const val IDENTITY_LOCALE = "identity.locale"

    const val SYSTEM_VERSION = "system.version"
    const val SYSTEM_ANDROID_VERSION = "system.androidVersion"
    const val SYSTEM_API_LEVEL = "system.apiLevel"
    const val SYSTEM_SECURITY_PATCH = "system.securityPatch"
    const val SYSTEM_BUILD_TYPE = "system.buildType"
    const val SYSTEM_BUILD_TAGS = "system.buildTags"
    const val SYSTEM_FINGERPRINT = "system.fingerprint"
    const val SYSTEM_KERNEL = "system.kernel"
    const val SYSTEM_ARCH = "system.arch"
    const val SYSTEM_UPTIME = "system.uptime"

    const val IDENTIFIERS_ANDROID_ID = "identifiers.androidId"
    const val IDENTIFIERS_OAID = "identifiers.oaid"
    const val IDENTIFIERS_INSTALL_ID = "identifiers.installId"
    const val IDENTIFIERS_ADVERTISING_ID = "identifiers.advertisingId"
    const val IDENTIFIERS_APP_SET_ID = "identifiers.appSetId"
    const val IDENTIFIERS_GSF_ID = "identifiers.gsfId"
    const val IDENTIFIERS_DRM_DEVICE_ID = "identifiers.drmDeviceId"

    const val HARDWARE_SOC = "hardware.soc"
    const val HARDWARE_CPU_ABI = "hardware.cpuAbi"
    const val HARDWARE_CPU_CORES = "hardware.cpuCores"
    const val HARDWARE_GPU = "hardware.gpu"
    const val HARDWARE_MEMORY = "hardware.memory"
    const val HARDWARE_STORAGE = "hardware.storage"
    const val HARDWARE_RESOLUTION = "hardware.resolution"
    const val HARDWARE_DENSITY = "hardware.density"
    const val HARDWARE_REFRESH_RATE = "hardware.refreshRate"
    const val HARDWARE_BATTERY_CAPACITY = "hardware.batteryCapacity"
    const val HARDWARE_BATTERY_HEALTH = "hardware.batteryHealth"
    const val HARDWARE_SENSOR_COUNT = "hardware.sensorCount"

    const val NETWORK_WIFI_SSID = "network.wifiSsid"
    const val NETWORK_IPV4 = "network.ipv4"
    const val NETWORK_IPV6 = "network.ipv6"
    const val NETWORK_WIFI_MAC = "network.wifiMac"
    const val NETWORK_BT_MAC = "network.btMac"
    const val NETWORK_MOBILE_TYPE = "network.mobileType"
    const val NETWORK_SIM_STATE = "network.simState"
    const val NETWORK_BASEBAND = "network.baseband"
    const val NETWORK_IMEI = "network.imei"

    const val SECURITY_SELINUX = "security.selinux"
    const val SECURITY_BOOTLOADER = "security.bootloader"
    const val SECURITY_DM_VERITY = "security.dmVerity"
    const val SECURITY_AVB = "security.avb"
    const val SECURITY_PLAY_INTEGRITY = "security.playIntegrity"
    const val SECURITY_ADB = "security.adb"
    const val SECURITY_DEVELOPER_OPTIONS = "security.developerOptions"
    const val SECURITY_WEBVIEW = "security.webview"
}
