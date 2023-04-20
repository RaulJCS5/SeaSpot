package isel.seaspot.activities

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import isel.seaspot.screens.NavGraph
import isel.seaspot.screens.Screens
import isel.seaspot.utils.*


class MainActivity : ComponentActivity() {

    lateinit var navController: NavHostController

    private val viewModel: MainViewModel by viewModels {
        viewModelInit {
            MainViewModel(application, handleResultOfAskingForBTEnabling, navController)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val routeNames = mutableListOf<String>()
        Screens.values().forEach { //This check doesn't workout properly in Screens in a init{}
            if(routeNames.contains(it.name)) throw IllegalArgumentException("There are screens with repetitive names")
            routeNames.add(it.name)
        }

        setContent {
            navController = rememberNavController()
            NavGraph(viewModel, navController)
        }
    }

    override fun onStart() {
        super.onStart()
        if(! haveThePermissionsBeenGranted(this)){
            askForPermissions(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy(); log("onDestroy")
        viewModel.disconnect()
    }

    //https://stackoverflow.com/a/63654043/9375488 This must be declared here or it causes this https://stackoverflow.com/q/64476827/9375488
    private var handleResultOfAskingForBTEnabling = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (! isLocationOn(this)) {
                //Goes to settings. Easier approach. To enable GPS without existing the app, which requires more code, see: https://stackoverflow.com/q/29801368/9375488 https://youtu.be/nTgmnjg2pa0
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                handleResultOfAskingForLocationEnabling.launch(intent)
            }
        }
    }

    private var handleResultOfAskingForLocationEnabling = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
        }
    }

    //Solely to disable screen rotation, and thus we won't need to handle it (I think)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}


