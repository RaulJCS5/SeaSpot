package isel.seaspot.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import isel.seaspot.R
import isel.seaspot.activities.MainViewModel
import isel.seaspot.bluetooth.*
import isel.seaspot.bluetooth.Properties
import isel.seaspot.ui.elements.Button
import isel.seaspot.ui.elements.HeaderText
import isel.seaspot.utils.doAsync
import isel.seaspot.utils.log
import isel.seaspot.utils.toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.*
import kotlin.concurrent.thread

@Composable @SuppressLint("MissingPermission")
fun ConnectedDeviceScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope() //This coroutine scope is tied to the composable from where it is called and will automatically be cancelled when this composable leaves composition

    val servicesOpen by remember {
        mutableStateOf(mutableStateMapOf<String, Boolean>()) //String = service uuid, Boolean = isOpen. This type of MutableState will update the UI when the list changes
    }

    var servicesData by remember {
        mutableStateOf(mutableListOf<Service>()) //This type of MutableState will need to be set w/ a new obj to update the UI
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HeaderText("Connected to")
        HeaderText("${vm.getConnectedDevice()?.name}")
        Button({
            vm.disconnect()
        }, stringResource(R.string.disconnect))

        LazyColumn(
            Modifier.fillMaxWidth().padding(paddingValues = PaddingValues(start = 5.dp, end = 5.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {

            val gatt = vm.getConnectedDeviceGatt()
            val services = vm.getConnectedDeviceServices()

            services.forEach {
                val uuid = it.uuid.toString()
                val x = servicesOpen.get(uuid)
                if(x==null) servicesOpen.set(uuid, false)
            }

            items(services) { service ->
                Card(
                    Modifier.fillMaxWidth().padding(5.dp)
                        .clickable {
                            val isItOpen = servicesOpen.get(service.uuid.toString())
                            if (isItOpen != null) {
                                servicesOpen.set(service.uuid.toString(), !isItOpen)
                                if (!isItOpen) { //If it wasn't open, (and now it has been set to open), get the characteristic values
                                    doAsync { //in order to not block the main thread
                                        log("Will executeCharacteristicRead ${service.uuid} with ${Thread.currentThread().name}")
                                        val gattCharacteristics = service.characteristics.toMutableList() //to create a copy

                                        val characteristicsOfThisService = mutableListOf<Characteristic>()

                                        //Trying to access gattCharacteristics[0].value will return null before this method
                                        val charsValues = vm.readCharacteristics(gattCharacteristics) //blocking

                                        log("Values of the characteristics obtained: ${charsValues.map { it.decodeToString() }}. Raw = ${charsValues.map { it }}")
                                        gattCharacteristics.forEachIndexed { index, charac ->
                                            log("------Reading characteristic: ${AssignedNumbersCharacteristics.uuidToEnum(charac.uuid).name}")
                                            log("properties = ${charac.properties}")
                                            log("permissions = ${charac.permissions}") //There's some problem with the reading of permissions https://github.com/pauldemarco/flutter_blue/issues/6 https://stackoverflow.com/questions/23674668/android-bluetooth-low-energy-characteristic-getpermissions-returns-0
                                            log("Does it contain property read? ${doesItContainField(charac.properties, Properties.READ)}")
                                            log("Does it contain property write? ${doesItContainField(charac.properties, Properties.WRITE)}")
                                            log("Does it contain permission read? ${doesItContainField(charac.permissions, Permissions.READ)}")
                                            log("Does it contain permission write? ${doesItContainField(charac.permissions, Permissions.WRITE)}")

                                            val isWritable = doesItContainField(charac.properties, Properties.WRITE)
                                            val values = try {
                                                charsValues.get(index)
                                            } catch(e: IndexOutOfBoundsException){
                                                byteArrayOf(1)
                                            }
                                            characteristicsOfThisService.add(
                                                Characteristic(charac.uuid, charac.uuid, values, isWritable)
                                            )
                                        }
                                        val currentServiceData = Service(service.uuid, service.uuid, characteristicsOfThisService)

                                        servicesData.removeIf {
                                            it.uuid == currentServiceData.uuid
                                        }
                                        val newServicesData = servicesData.toMutableList() //to create a copy
                                        newServicesData.add(currentServiceData)

                                        servicesData = newServicesData
                                    }
                                }
                            }
                        },
                    elevation = 20.dp
                ) {
                    val isThisServiceViewOpen = isThisServiceOpen(servicesOpen, service)
                    Column (Modifier.padding(if(isThisServiceViewOpen) 20.dp else 5.dp)) {
                        Text("${stringResource(R.string.service)}: ${AssignedNumbersService.uuidToEnum(service.uuid).name}", Modifier.padding(5.dp))
                        if(isThisServiceViewOpen) {
                            CharacteristicDisplay(gatt, servicesData, service, services, ctx)
                        }
                    }
                }
            }
        }
    }
}

@Composable @SuppressLint("MissingPermission")
fun CharacteristicDisplay(
    gatt: BluetoothGatt?,
    servicesData: MutableList<Service>,
    service: BluetoothGattService,
    services: MutableList<BluetoothGattService>,
    ctx: Context
) {
    Card(Modifier.fillMaxSize().padding(5.dp), elevation = 10.dp) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)){
            val service = servicesData.find { it.fullUUID==service.uuid.toString() }
            service?.characteristics?.forEach { characteristic ->

                Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                    Text("${stringResource(R.string.characteristic)}: ${characteristic.uuid}")
                    if(characteristic.isWritable) {

                        val success = "${stringResource(R.string.success)}" //because "@Composable invocations can only happen from the context of a @Composable function"
                        val failed = "${stringResource(R.string.failed)}"
                        val characteristicNotFound = "${stringResource(R.string.characNotFound)}"

                        if(characteristic.isAction()){
                            val value = byteArrayOf(1)
                            Button({
                                val charac = getCharacteristic(services, service, characteristic.getFullUUID())
                                if(charac==null) toast(characteristicNotFound, ctx) //if this is true, something went wrong

                                    val worked = charac?.setValue(value)
                                    if(worked==true) toast(success, ctx) else toast(failed, ctx)

                            }, stringResource(R.string.refresh))
                        } else {
                            log("Text raw = ${characteristic.value.toList()}. String = ${characteristic.value.decodeToString()}")
                            var text by rememberSaveable { mutableStateOf(characteristic.value.decodeToString()) }
                            var isEditButtonEnabled by rememberSaveable { mutableStateOf(text.length<=maximumBytes) }
                            val valueTooBigMaximumIs = "${stringResource(R.string.valueTooBig)}"
                            TextField(text,
                                onValueChange = { txt ->
                                    if(txt.length>=maximumBytes){
                                        toast("$valueTooBigMaximumIs $maximumBytes", ctx)
                                    } else {
                                        log("length = ${txt.length}. onValueChange = $text -> $txt")
                                        text = txt
                                        isEditButtonEnabled = true
                                    }
                                },
                                keyboardOptions = KeyboardOptions.Default.copy(
                                    keyboardType = if(characteristic.isNumber()) KeyboardType.Number else KeyboardType.Text
                                )
                            )

                            Button({
                                val charac = getCharacteristic(services, service, characteristic.getFullUUID())
                                if(charac==null) toast(characteristicNotFound, ctx) //if this is true, something went wrong

                                    val worked = charac?.setValue(text)
                                    //charac?.value = text.encodeToByteArray()
                                    if(worked==true) toast(success, ctx) else toast(failed, ctx)

                            }, stringResource(R.string.edit), isEditButtonEnabled)
                        }
                    }
                    else Text("${stringResource(R.string.value)}: ${characteristic.value.decodeToString()}")
                }
            }
        }
    }
}

fun isThisServiceOpen(servicesOpen: MutableMap<String, Boolean>, service: BluetoothGattService?): Boolean {
    return servicesOpen.any {
        it.value && it.key == service?.uuid.toString()
    }
}
