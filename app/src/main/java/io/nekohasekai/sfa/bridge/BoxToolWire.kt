package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.backend.tools.TaildropFileData
import io.nekohasekai.sfa.backend.tools.TaildropReceivingFileData
import io.nekohasekai.sfa.backend.tools.TaildropInboxState
import io.nekohasekai.sfa.backend.usbip.UsbSharedInterfaceData
import io.nekohasekai.sfa.backend.usbip.UsbSharedDeviceData
import io.nekohasekai.sfa.backend.usbip.UsbipServerData

internal fun TaildropFileData.toWire(): Map<String, Any?> = mapOf(
    "name" to name,
    "size" to size,
    "senderName" to senderName,
)

internal fun TaildropReceivingFileData.toWire(): Map<String, Any?> = mapOf(
    "name" to name,
    "size" to size,
    "receivedBytes" to receivedBytes,
    "senderID" to senderID,
    "senderName" to senderName,
)

internal fun TaildropInboxState.toWire(): Map<String, Any?> = mapOf(
    "files" to files.map { it.toWire() },
    "receiving" to receiving.map { it.toWire() },
)

internal fun UsbSharedInterfaceData.toWire(): Map<String, Any?> = mapOf(
    "interfaceClass" to interfaceClass,
    "interfaceSubClass" to interfaceSubClass,
    "interfaceProtocol" to interfaceProtocol,
)

internal fun UsbSharedDeviceData.toWire(): Map<String, Any?> = mapOf(
    "busId" to busId,
    "stableId" to stableId,
    "backend" to backend,
    "state" to state,
    "deviceId" to deviceId,
    "busNum" to busNum,
    "devNum" to devNum,
    "speed" to speed,
    "vendorId" to vendorId,
    "productId" to productId,
    "bcdDevice" to bcdDevice,
    "deviceClass" to deviceClass,
    "deviceSubClass" to deviceSubClass,
    "deviceProtocol" to deviceProtocol,
    "configurationValue" to configurationValue,
    "numConfigurations" to numConfigurations,
    "serial" to serial,
    "product" to product,
    "interfaces" to interfaces.map { it.toWire() },
)

internal fun UsbipServerData.toWire(): Map<String, Any?> = mapOf(
    "serverTag" to serverTag,
    "devices" to devices.map { it.toWire() },
)
