package isel.seaspot.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.Handler
import androidx.activity.result.ActivityResultLauncher
import isel.seaspot.R
import isel.seaspot.utils.*
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** References
 * General:
 * - https://developer.android.com/guide/topics/connectivity/bluetooth/setup
 * - https://developer.android.com/guide/topics/connectivity/bluetooth/find-ble-devices
 * - https://punchthrough.com/android-ble-guide/#:~:text=device%20from%20there.-,Implementing%20a%20basic%20queuing%20mechanism,-In%20this%20section
 * @see bluetoothLeScanner - [4] https://stackoverflow.com/a/35476512/9375488
 * @see connectGatt - [5] https://developer.android.com/guide/topics/connectivity/bluetooth/connect-gatt-server
 * @see connectGatt - [6] https://medium.com/@martijn.van.welie/making-android-ble-work-part-2-47a3cdaade07#:~:text=Autoconnect%20only%20works%20for%20cached%20or%20bonded%20devices!
 * @see disconnect - [6.1] https://stackoverflow.com/a/23657148
 * @see bluetoothGattCallback - [7] https://medium.com/@martijn.van.welie/making-android-ble-work-part-2-47a3cdaade07#:~:text=it%20means%20the%20connection%20state
 * @see bluetoothGattCallback - [8] https://medium.com/@martijn.van.welie/making-android-ble-work-part-2-47a3cdaade07#:~:text=int%20bondstate%20%3D-,device.getBondState()%3B,-The%20bond%20state
 * @see bluetoothGattCallback - [9] https://medium.com/@martijn.van.welie/making-android-ble-work-part-2-47a3cdaade07#:~:text=have%20to%20add%20a%201000%E2%80%931500%20ms%20delay.
 * @see bluetoothGattCallback - [10] https://developer.android.com/reference/android/bluetooth/BluetoothGatt#discoverServices()
 * @see bluetoothGattCallback - [11] https://issuetracker.google.com/issues/228984309 https://stackoverflow.com/q/32363931
 * @see clearServicesCache - [12] https://medium.com/@martijn.van.welie/making-android-ble-work-part-2-47a3cdaade07#:~:text=the%20services%0A...-,Caching%20of%20services,-The%20Android%20stack
 * @see setCharacteristicNotification - [13] https://developer.android.com/guide/topics/connectivity/bluetooth/transfer-ble-data#notification
 */
@SuppressLint("MissingPermission") //because we already call askForPermissions
class BLE_Manager(
    private val ctx: Context,
    private val handleResultOfAskingForBTEnabling: ActivityResultLauncher<Intent>
){
    private val bluetoothManager: BluetoothManager = ctx.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var currentlyConnectedDevice: BluetoothDevice? = null
    private var currentlyConnectedDeviceGatt: BluetoothGatt? = null

    private var scanning = false
    private val handler = Handler()
    private val SCAN_PERIOD: Long = 5000

    var bleDevices = hashMapOf<String, BluetoothDevice>()

    private fun bluetoothLeScanner() = bluetoothAdapter?.bluetoothLeScanner //if bluetooth is not turned on, this value will be null
    private fun isBluetoothOn() = bluetoothAdapter?.isEnabled == true

    var postScan: () -> Unit = {}
    var onConnectionSuccessful: () -> Unit = {}
    var onServicesDiscovered: () -> Unit = {}
    var onDisconnect: (msg: String) -> Unit = {}

    private fun turnOnBluetooth(){
        log("---turnOnBluetooth---")
        if (bluetoothAdapter == null) {
            toast(R.string.device_no_bluet, ctx)
            return
        }

        if (! isBluetoothOn()) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            handleResultOfAskingForBTEnabling.launch(enableBtIntent)
        }
        //toast("start leScanner = "+bluetoothLeScanner().toString(), ctx)
    }

    @Throws(SecurityException::class)
    fun scanForDevices() { // Stops scanning after a pre-defined scan period.
        log("---scanLeDevice---")
        if(! isBluetoothOn()) turnOnBluetooth()
        else if (!scanning) {
            handler.postDelayed({
                scanning = false
                postScan()
                toast(R.string.scanningDone, ctx)
                bluetoothLeScanner()?.stopScan(bleScanCallback)
            }, SCAN_PERIOD)
            scanning = true
            log("---started scanning---")
            //log("LeScanner = "+bluetoothLeScanner().toString())
            toast(R.string.scanning, ctx)

            //Configurations of the scan methods
            val filters: MutableList<ScanFilter> = ArrayList()
            val scanFilterBuilder = ScanFilter.Builder()
            filters.add(scanFilterBuilder.build())

            val settingsBuilder = ScanSettings.Builder()
            settingsBuilder.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            settingsBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)

            bluetoothLeScanner()?.startScan(null, settingsBuilder.build(), bleScanCallback)
        } else {
            log("Already scanning")
        }
    }

    private fun stopScanning(){
        if(scanning){
            scanning = false
            bluetoothLeScanner()?.stopScan(bleScanCallback)
        }
    }

    //Note: Location need to be turned in order for the ScanCallback to work ... [4]
    private val bleScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            toast(R.string.scanError, ctx)
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) { //Note: this method is called multiple times
            try {
                super.onScanResult(callbackType, result)
                bleDevices.set(result.device.address, result.device)
                //log(result.toString())
            } catch (e: Exception){
                log("Exception occurred in onScanResult -> $e")
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            log("onBatchScanResults")
            for (sr in results) {
                log("ScanResult - Results $sr")
            }
        }
    }

    fun connectGatt(device: BluetoothDevice, onConnectSuccessful: () -> Unit, onServicesDiscovered: () -> Unit) { //[5]
        stopScanning()
        this.onConnectionSuccessful = onConnectSuccessful
        this.onServicesDiscovered = onServicesDiscovered
        currentlyConnectedDeviceGatt = device.connectGatt(ctx, false, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE) //autoConnect = false because -> [6]
        currentlyConnectedDevice = device
    }

    fun disconnect(){ // [6.1]
        if(currentlyConnectedDeviceGatt==null) onDisconnect("Already disconnected")
        else currentlyConnectedDeviceGatt?.disconnect()
    }

    fun getConnectedDevice() = currentlyConnectedDevice
    fun getConnectedDeviceGatt() = currentlyConnectedDeviceGatt

    fun getConnectedDeviceServices() : MutableList<BluetoothGattService> {
        val services = currentlyConnectedDeviceGatt?.services ?: mutableListOf()
        val servIterator = services.iterator()

        servIterator.forEach {
            if(ignoredServices.contains(AssignedNumbersService.uuidToEnum(it.uuid))){
                servIterator.remove()
            }
        }

        return services
    }

    private val bluetoothGattCallback: BluetoothGattCallback = object : BluetoothGattCallback(){ //For this callback there's only 1 thread named like: "binder:19774_1" which is used for all overwritten methods
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if(status == BluetoothGatt.GATT_SUCCESS) { //it means the connection state change was the result of a successful operation like connecting but it could also be because you wanted to disconnect. [7]
                log("onConnectionStateChange -> GATT_SUCCESS")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        log("STATE_CONNECTED")
                        val bondState = currentlyConnectedDevice?.bondState //[8]
                        if(bondState == BluetoothDevice.BOND_NONE || bondState == BluetoothDevice.BOND_BONDED) { //consider [9]
                            onConnectionSuccessful()
                            log("BOND_BONDED")
                            val areThereServices = gatt?.discoverServices() //[10]
                            if(areThereServices == true) log("Started onServicesDiscovered()")
                            else log("Couldn't call onServicesDiscovered()")
                        } else if (bondState == BluetoothDevice.BOND_BONDING) {
                            log("waiting for bonding to complete")
                        }
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        log("STATE_CONNECTING")
                    }
                    BluetoothProfile.STATE_DISCONNECTING -> {
                        log("STATE_DISCONNECTING")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        log("STATE_DISCONNECTED")
                        currentlyConnectedDeviceGatt = null
                        currentlyConnectedDevice = null
                        onDisconnect("STATE_DISCONNECTED")
                    }
                    else -> {
                        log("State = $newState")
                    }
                }
            } else {
                log("Unexpected error occurred in onConnectionStateChange. Status = $status. Gatt = $gatt")
                clearServicesCache()
                gatt?.close()
                currentlyConnectedDeviceGatt = null
                currentlyConnectedDevice = null
                onDisconnect("")
            }
        }

        //This method sometimes will have gatt?.services empty [11]
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("onServicesDiscovered received -> GATT_SUCCESS, ${currThread()}")
                log("Services = ${gatt?.services?.map { "${it.uuid} (${AssignedNumbersService.uuidToEnum(it.uuid).name})" } }")
                if(gatt?.services?.isNotEmpty()==true){
                    log("SERVICES FOUND")
                    onServicesDiscovered()
                } else {
                    log("SERVICES NOT FOUND")
                }
            }
            else log("onServicesDiscovered received: $status")
        }

        //This callback is run if Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            runBlockingCallback {
                log("onCharacteristicRead ${Thread.currentThread()}")
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        log("characteristic.value -> String = ${value.decodeToString()}. Raw = ${value}. uuid = ${characteristic.uuid}")
                        characteristicsRead.add(value)
                    }
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                        log("onCharacteristicRead received GATT_READ_NOT_PERMITTED")
                    }
                    else -> {
                        log("onCharacteristicRead failed? received: $status")
                    }
                }
            }
        }

        //This callback is run if Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            runBlockingCallback {
                log("onCharacteristicRead2 ${Thread.currentThread()}")
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        log("characteristic.value -> String = ${characteristic?.value}. uuid = ${characteristic?.uuid}")
                        characteristicsRead.add(characteristic?.value ?: byteArrayOf(1))
                    }
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                        log("onCharacteristicRead received GATT_READ_NOT_PERMITTED")
                    }
                    else -> {
                        log("onCharacteristicRead failed? received: $status")
                    }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            log("onCharacteristicWrite")
            log("Characteristic value: ${characteristic?.value?.decodeToString()}")
            log("status = $status")
            super.onCharacteristicWrite(gatt, characteristic, status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            log("onCharacteristicChanged()")
        }

        fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            log("onCharacteristicChanged222()")
        }

        override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
            log("onReliableWriteCompleted()")
        }

        fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int, value: ByteArray) {
            log("Value = ${value.decodeToString()}")
        }
    }

    //Command queueing variables & methods:
    private val lock = ReentrantLock()
    private var isOperationRunning = false
    private var threadsWaiting: Condition = lock.newCondition()
    private val characteristicsRead = mutableListOf<ByteArray>()

    fun readCharacteristics(characteristic: List<BluetoothGattCharacteristic>) : List<ByteArray>{
        try {
            characteristicsRead.clear()
            log("readCharacteristics ${Thread.currentThread()}")
            var maxWaitSeconds = 5
            lock.withLock{
                characteristic.forEach{
                    while(true){
                        if(!isOperationRunning) {
                            log("Will readCharacteristic")
                            isOperationRunning = true
                            currentlyConnectedDeviceGatt?.readCharacteristic(it)
                            break
                        }
                        log("Will wait")
                        maxWaitSeconds--
                        threadsWaiting.await(1, TimeUnit.SECONDS)
                        log("wokeup, maxWaitSeconds = $maxWaitSeconds")
                        if(maxWaitSeconds==0) {
                            log("Quit waiting")
                            break
                        }
                    }
                    maxWaitSeconds = 5
                }
                if(isOperationRunning) threadsWaiting.await(maxWaitSeconds.toLong(), TimeUnit.SECONDS) //💡
                return characteristicsRead
            }
        } catch (e: Exception) {
            log("executeCharacteristicRead exception: $e")
            return mutableListOf()
        }
    }

    private fun runBlockingCallback(operation: () -> Unit){ //Utility func. Meant be used when we want to make many BLE operations at the same time or to sync the function that call a BLE callback with it's completion state
        operation()
        lock.withLock {
            isOperationRunning = false
            log("Finished, will signal")
            threadsWaiting.signal()
        }
    }

    private fun clearServicesCache() : Boolean { //[12]
        var result = false
        log("Will try to clear cache")
        try {
            val refreshMethod = currentlyConnectedDevice?.javaClass?.getMethod("refresh")
            if (refreshMethod != null) {
                result = refreshMethod.invoke(currentlyConnectedDevice) as Boolean
            }
        } catch (e: Exception) {
            log("ERROR: Could not invoke refresh method")
        }
        return result
    }

    //Later
    fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic, enabled: Boolean) { //[13]
        currentlyConnectedDeviceGatt?.let { gatt ->
            gatt.setCharacteristicNotification(characteristic, enabled)

            val descriptor = characteristic.getDescriptor(UUID.fromString("SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG"))
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        } ?: run {
            log("BluetoothGatt not initialized")
        }
    }
}

fun getCharacteristic(services: List<BluetoothGattService>, service: Service, characUUID: UUID) : BluetoothGattCharacteristic? {
    val thisService = services.find {
        it.uuid.toString()==service.fullUUID //more accurate search, in case it's an Unknown service
    }
    return thisService?.getCharacteristic(characUUID)
}
