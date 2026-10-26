package com.xg.toolbox.core.device

import android.content.Context
import com.xg.toolbox.core.common.SingleFlightValueCache
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class DeviceInfoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val serialController: SerialNumberController,
) {

    private val cache = SingleFlightValueCache<List<DeviceGroup>>()

    suspend fun collect(forceRefresh: Boolean = false): List<DeviceGroup> = withContext(Dispatchers.IO) {
        requireNotNull(cache.get(forceRefresh) { collectUncached() }) {
            "设备信息缓存不可用"
        }
    }

    suspend fun invalidateCache() = cache.invalidate()

    private suspend fun collectUncached(): List<DeviceGroup> {
        val rootGranted = rootStatus.state.value is RootState.Granted
        val source = FieldSource.load(context = context, shell = shell, rootGranted = rootGranted)
        return listOf(
            buildIdentityGroup(context, source),
            buildSystemGroup(context, source),
            buildIdentifierGroup(context, source),
            buildHardwareGroup(context, source),
            buildNetworkGroup(context, source),
            buildSecurityGroup(context, source),
        )
    }

    suspend fun serialNumber(): XgResult<String> = serialController.readRaw()

    suspend fun serialEditCapability(): XgResult<SerialEditCapability> =
        serialController.editCapability()

    suspend fun setSerial(newSerial: String): XgResult<SerialChangeResult> =
        serialController.setSerial(newSerial)
}
