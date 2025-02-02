package world.shanya.serialport.connect

import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.Handler
import world.shanya.serialport.SerialPort
import world.shanya.serialport.discovery.Device
import world.shanya.serialport.service.SerialPortService
import world.shanya.serialport.strings.SerialPortToast
import world.shanya.serialport.tools.LogUtil
import world.shanya.serialport.tools.SPUtil
import world.shanya.serialport.tools.SerialPortToolsByJava
import world.shanya.serialport.tools.ToastUtil
import java.io.IOException
import java.io.InputStream
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


//新连接状态接口
typealias ConnectionStatusCallback = (status: Boolean, bluetoothDevice: BluetoothDevice?) -> Unit
//旧连接状态接口
@Deprecated(message = "该回调在4.0.0版本开始被弃用",replaceWith = ReplaceWith(expression = "ConnectionStatusCallback"))
typealias ConnectStatusCallback = (status: Boolean, device: Device) -> Unit
//连接接口
typealias ConnectCallback = () -> Unit


/**
 * SerialPortConnect 连接管理类
 * @UpdateContent
 * 1. 部分代码重构
 * 2. 新增对BLE设备的支持
 * @Author Shanya
 * @Date 2021-7-21
 * @Version 4.0.0
 */
internal object SerialPortConnect {

    //传统设备UUID
    internal var UUID_LEGACY = "00001101-0000-1000-8000-00805F9B34FB"
    //BLE设备UUID
    internal var UUID_BLE = "0000ffe1-0000-1000-8000-00805f9b34fb"
    //是否开启间隔自动重连
    internal var autoReconnectAtIntervalsFlag = false
    //开启间隔自动重连时间间隔 单位ms
    internal var autoReconnectIntervalsTime = 10000
    //蓝牙通信Socket
    internal var bluetoothSocket: BluetoothSocket?= null
    //蓝牙通信inputStream
    internal var inputStream: InputStream?= null
    //连接状态
    internal var connectStatus = false
    //自动连接标志（执行自动连接后即为 true）
    internal var autoConnectFlag = false
    //已经连接的传统设备
    internal var connectedLegacyDevice: BluetoothDevice ?= null
    //已经连接的BLE设备
    internal var connectedBleDevice: BluetoothDevice ?= null
    //上一次成功连接的设备地址
    internal var lastDeviceAddress = ""

    internal var gattCharacteristic: BluetoothGattCharacteristic? = null

    internal var bluetoothGatt: BluetoothGatt? = null

    internal var bleUUIDList = ArrayList<String>()
    /**
     * bluetoothGattCallback BLE设备连接回调
     * @Author Shanya
     * @Date 2021-7-21
     * @Version 4.0.0
     */
    private val bluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            //BLE设备连接成功
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedResult(SerialPort.newContext, true, gatt, gatt?.device)
            }

            //BLE设备断开连接
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedBleDevice == null) {
                    connectedResult(SerialPort.newContext, false, gatt, gatt?.device)
                }else {
                    disconnectResult(SerialPort.newContext)
                    gatt?.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                bleUUIDList.clear()
                gatt?.services?.let {
                    for (gattService in it) {
                        val gattCharacteristics = gattService.characteristics
                        for (gattCharacteristic in gattCharacteristics) {
                            val uuid = gattCharacteristic.uuid.toString()
                            bleUUIDList.add(uuid)
                            if (uuid == UUID_BLE) {
                                this@SerialPortConnect.gattCharacteristic = gattCharacteristic
                            }
                        }
                    }
                }
            }
            gatt?.setCharacteristicNotification(gattCharacteristic,true)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                LogUtil.log("BLE设备发送数据成功")

            }
            else if (status == BluetoothGatt.GATT_FAILURE) {
                LogUtil.log("BLE设备发送数据失败")
            }
        }


        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            val value = characteristic?.value
            if (value != null && value.isNotEmpty()) {
                val receivedData = String(value)
                LogUtil.log("BLE设备收到数据", receivedData)
                SerialPort.receivedDataCallback?.invoke(receivedData)
            }

        }
    }

    /**
     * connectBle 连接BLE设备
     * @param context 上下文
     * @param address 设备地址
     * @Author Shanya
     * @Date 2021-7-21
     * @Version 4.0.0
     */
    internal fun connectBle(context: Context, address: String) {
        val bluetoothDevice =
            SerialPort.bluetoothAdapter.getRemoteDevice(address)
        bluetoothDevice.connectGatt(context, false, bluetoothGattCallback)
    }

    /**
     * connectLegacy 连接传统设备（处理大逻辑结构）
     * @param context 上下文
     * @param address 设备地址
     * @Author Shanya
     * @Date 2021-7-21
     * @Version 4.0.0
     */
    internal fun connectLegacy(context: Context, address: String) {
        Thread {
            bluetoothSocket?.isConnected?.let { bluetoothSocketIsConnected ->
                if (bluetoothSocketIsConnected) {
                    SerialPort.connectCallback?.invoke()
                    if (autoConnectFlag) {
                        ToastUtil.toast(context, SerialPortToast.disconnectFirst)
                    }
                } else {
                    _connectLegacy(context, address)
                }
            } ?: _connectLegacy(context, address)
        }.start()
    }


    /**
     * _connectLegacy 连接传统设备（处理具体连接细节）
     * @param context 上下文
     * @param address 设备地址
     * @Author Shanya
     * @Date 2021-7-21
     * @Version 4.0.0
     */
    internal fun _connectLegacy(context: Context, address: String) {
        var bluetoothDevice:BluetoothDevice ?= null
        try {
            bluetoothDevice =
                SerialPort.bluetoothAdapter.getRemoteDevice(address)
            bluetoothSocket =
                bluetoothDevice.createRfcommSocketToServiceRecord(UUID.fromString(UUID_LEGACY))
            bluetoothSocket?.connect()
            connectedResult(context, true, null, bluetoothDevice)
            inputStream = bluetoothSocket?.inputStream
            context.startService(Intent(context, SerialPortService::class.java))
        } catch (e: IOException) {
            e.printStackTrace()
            connectedResult(context, false, null, bluetoothDevice)
            try {
                bluetoothSocket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * bleDisconnect BLE设备断开连接
     * @Author Shanya
     * @Date 2021-7-21
     * @Version 4.0.0
     */
    internal fun bleDisconnect() {
        bluetoothGatt?.disconnect()
    }

    /**
     * legacyDisconnect 传统设备断开连接
     * @param context 上下文
     * @Author Shanya
     * @Date 2021-7-21
     * @Version 4.0.0
     */
    internal fun legacyDisconnect(context: Context) {
        context.stopService(Intent(context, SerialPortService::class.java))
    }

    /**
     * connectedResult 连接结果报告
     * @param context 上下文
     * @param result 连接结构
     * @param gatt gatt
     * @param bluetoothDevice 连接设备
     * @Author Shanya
     * @Date 2021-7-21
     * @Version 4.0.0
     */
    private fun connectedResult(
        context: Context?,
        result: Boolean,
        gatt: BluetoothGatt?,
        bluetoothDevice: BluetoothDevice?
    ) {
        if (result) {
            if (bluetoothDevice?.type == 2) {
                bluetoothGatt = gatt
                bluetoothGatt?.discoverServices()
            }
            val device = Device(
                bluetoothDevice?.name ?: "",
                bluetoothDevice?.address ?: "",
                bluetoothDevice?.type ?: 1)
            connectStatus = true
            SerialPort.connectCallback?.invoke()
            SerialPort.connectStatusCallback?.invoke(true, device)
            SerialPort.connectionStatusCallback?.invoke(true, bluetoothDevice)
            if (bluetoothDevice?.type == 2) {
                connectedBleDevice = bluetoothDevice
                LogUtil.log("连接BLE设备成功","设备地址: ${bluetoothDevice.address}")
            } else {
                connectedLegacyDevice = bluetoothDevice
                LogUtil.log("连接传统设备成功","设备地址: ${bluetoothDevice?.address}")
            }
            context?.let {
                SPUtil.putDeviceInfo(it, bluetoothDevice)
                lastDeviceAddress = bluetoothDevice?.address?:""
                ToastUtil.toast(it, SerialPortToast.connectSucceeded)
            }
        } else {
            SerialPort.connectCallback?.invoke()
            LogUtil.log("连接失败")
            context?.let {
                ToastUtil.toast(it, SerialPortToast.connectFailed)
            }
        }

    }

    /**
     * disconnectResult 断开结果报告
     * @param context 上下文
     * @Author Shanya
     * @Date 2021-7-21
     * @Version 4.0.0
     */
    internal fun disconnectResult(context: Context?) {
        connectedLegacyDevice?.let {
            bluetoothSocket?.close()
        }
        SerialPort.connectCallback?.invoke()
        connectedBleDevice?.let {
            val device = Device(it.name, it.address, it.type)
            SerialPort.connectStatusCallback?.invoke(false, device)
            SerialPort.connectionStatusCallback?.invoke(false, it)
            connectStatus = false
            connectedBleDevice = null
            context?.let {context ->
                ToastUtil.toast(context,SerialPortToast.disconnect)
            }
            LogUtil.log("断开BLE设备连接","设备地址: ${it.address}")
        }
        connectedLegacyDevice?.let {
            val device = Device(it.name, it.address, it.type)
            SerialPort.connectStatusCallback?.invoke(false, device)
            SerialPort.connectionStatusCallback?.invoke(false, it)
            connectStatus = false
            connectedLegacyDevice = null
            context?.let {context ->
                ToastUtil.toast(context,SerialPortToast.disconnect)
            }
            LogUtil.log("断开传统设备连接","设备地址: ${it.address}")
        }
    }

    /**
     * 间隔自动重连相关
     * @param handler 定时任务的处理
     * @param runnable 具体的任务操作
     * @fun autoConnect 开始任务
     * @fun cancelAutoConnect 停止任务
     * @Author Shanya
     * @Date 2021-7-21
     * @Version 4.0.0
     */
    private val handler = Handler()
    private val runnable = object : Runnable {
        override fun run() {
            if (autoReconnectAtIntervalsFlag && !connectStatus) {
                SerialPort.newContext?.let {context ->
                    if (lastDeviceAddress != "") {
                        LogUtil.log("间隔自动重连","设备地址: ${lastDeviceAddress}")
                        SPUtil.getDeviceType(context)?.let {type ->
                            when (type) {
                                "1" -> {
                                    SPUtil.getDeviceAddress(context)?.let {
                                        lastDeviceAddress = it
                                        _connectLegacy(context, lastDeviceAddress)
                                    }
                                }
                                "2" -> {
                                    SPUtil.getDeviceAddress(context)?.let {
                                        lastDeviceAddress = it
                                        connectBle(context, lastDeviceAddress)
                                    }
                                }
                                else -> {

                                }
                            }
                        }
                    }
                }
            }
            handler.postDelayed(this, autoReconnectIntervalsTime.toLong())
        }
    }
    internal fun autoConnect() {
        handler.postDelayed(runnable,autoReconnectIntervalsTime.toLong())
    }
    internal fun cancelAutoConnect() {
        handler.removeCallbacks(runnable)
    }
}