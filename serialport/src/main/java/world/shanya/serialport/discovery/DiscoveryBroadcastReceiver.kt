package world.shanya.serialport.discovery

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import world.shanya.serialport.SerialPort
import world.shanya.serialport.tools.LogUtil

/**
 * DiscoveryBroadcastReceiver 蓝牙搜索状态广播接收器
 * @Author Shanya
 * @Date 2021-8-13
 * @Version 4.0.3
 */
class DiscoveryBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            BluetoothDevice.ACTION_FOUND -> {
                val device =
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (device != null) {
                    if (SerialPort.ignoreNoNameDeviceFlag) {
                        if (device.name != null) {
                            SerialPortDiscovery.addDevice(device)
                        }
                    } else {
                        SerialPortDiscovery.addDevice(device)
                    }
                }
            }

            BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                LogUtil.log("开始搜索传统蓝牙设备")
                SerialPortDiscovery.discoveryStatusWithTypeCallback?.invoke(SerialPort.DISCOVERY_LEGACY, true)
                SerialPortDiscovery.discoveryStatusCallback?.invoke(true)
                SerialPortDiscovery.discoveryStatusLiveData.value = true
            }

            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                LogUtil.log("停止搜索传统蓝牙设备")
                SerialPortDiscovery.stopBleScan()
                SerialPortDiscovery.discoveryStatusWithTypeCallback?.invoke(SerialPort.DISCOVERY_LEGACY, false)
                SerialPortDiscovery.discoveryStatusCallback?.invoke(false)
                SerialPortDiscovery.discoveryStatusLiveData.value = false
            }
        }
    }

}