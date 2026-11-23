package io.nekohasekai.sfa.backend.usbip

import io.nekohasekai.libbox.USBIPServerStatus
import io.nekohasekai.libbox.USBIPServerStatusUpdate
import io.nekohasekai.libbox.USBSharedDevice

data class UsbSharedInterfaceData(
    val interfaceClass: Int,
    val interfaceSubClass: Int,
    val interfaceProtocol: Int,
)

data class UsbSharedDeviceData(
    val busId: String,
    val stableId: String,
    val backend: Int,
    val state: Int,
    val deviceId: String,
    val busNum: Int,
    val devNum: Int,
    val speed: Int,
    val vendorId: Int,
    val productId: Int,
    val bcdDevice: Int,
    val deviceClass: Int,
    val deviceSubClass: Int,
    val deviceProtocol: Int,
    val configurationValue: Int,
    val numConfigurations: Int,
    val serial: String,
    val product: String,
    val interfaces: List<UsbSharedInterfaceData>,
) {
    val key: String get() = stableId.ifEmpty { busId }
}

data class UsbipServerData(
    val serverTag: String,
    val devices: List<UsbSharedDeviceData>,
)

// Conversion of the libbox status updates to plain values; the subscriptions
// are in bridge/BoxToolStatus.kt and the lists in mobile-ffi tool_status.rs.

internal fun convertUpdate(status: USBIPServerStatusUpdate): List<UsbipServerData> {
    val servers = mutableListOf<UsbipServerData>()
    val iterator = status.servers()
    while (iterator.hasNext()) {
        servers.add(convertServer(iterator.next()))
    }
    return servers
}

internal fun convertServer(server: USBIPServerStatus): UsbipServerData {
    val devices = mutableListOf<UsbSharedDeviceData>()
    val iterator = server.devices()
    while (iterator.hasNext()) {
        devices.add(convertDevice(iterator.next()))
    }
    return UsbipServerData(serverTag = server.serverTag, devices = devices)
}

internal fun convertDevice(device: USBSharedDevice): UsbSharedDeviceData {
    val interfaces = mutableListOf<UsbSharedInterfaceData>()
    val iterator = device.interfaces()
    while (iterator.hasNext()) {
        val iface = iterator.next()
        interfaces.add(
            UsbSharedInterfaceData(
                interfaceClass = iface.interfaceClass,
                interfaceSubClass = iface.interfaceSubClass,
                interfaceProtocol = iface.interfaceProtocol,
            ),
        )
    }
    return UsbSharedDeviceData(
        busId = device.busID,
        stableId = device.stableID,
        backend = device.backend,
        state = device.state,
        deviceId = device.deviceID,
        busNum = device.busNum,
        devNum = device.devNum,
        speed = device.speed,
        vendorId = device.vendorID,
        productId = device.productID,
        bcdDevice = device.getBCDDevice(),
        deviceClass = device.deviceClass,
        deviceSubClass = device.deviceSubClass,
        deviceProtocol = device.deviceProtocol,
        configurationValue = device.configurationValue,
        numConfigurations = device.numConfigurations,
        serial = device.serial,
        product = device.product,
        interfaces = interfaces,
    )
}
