package com.example.track.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.example.track.Constants
import com.example.track.SessionManager
import com.example.track.network.AssignBusRequest
import com.example.track.network.BusResponse
import com.example.track.network.RetrofitClient
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URISyntaxException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverDashboard(navController: NavHostController) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var isTracking by remember { mutableStateOf(false) }
    var currentLatLng by remember { mutableStateOf<LatLng?>(null) }
    var currentBearing by remember { mutableStateOf(0f) }
    var mapType by remember { mutableStateOf(MapType.NORMAL) }
    var showMapTypeMenu by remember { mutableStateOf(false) }
    
    var assignedBus by remember { mutableStateOf<BusResponse?>(null) }
    var showBusEntryDialog by remember { mutableStateOf(false) }
    var busNumberInput by remember { mutableStateOf("") }
    var busEntryError by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val socket: Socket? = remember {
        try {
            IO.socket(Constants.BASE_URL)
        } catch (e: URISyntaxException) {
            null
        }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 2f)
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onResult = { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Log.d("Location", "User enabled location settings")
            }
        }
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasLocationPermission = isGranted }
    )

    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val newLatLng = LatLng(location.latitude, location.longitude)
                    val isFirstFix = currentLatLng == null
                    currentLatLng = newLatLng
                    currentBearing = location.bearing

                    if (isFirstFix) {
                        scope.launch {
                            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(newLatLng, 15f))
                        }
                    }

                    if (isTracking) {
                        scope.launch {
                            val cameraPosition = CameraPosition.Builder()
                                .target(newLatLng)
                                .zoom(18f)
                                .bearing(location.bearing)
                                .tilt(45f)
                                .build()

                            cameraPositionState.animate(
                                CameraUpdateFactory.newCameraPosition(cameraPosition),
                                1000
                            )
                        }

                        val driverId = sessionManager.getDriverId() ?: "unknown"
                        val data = JSONObject().apply {
                            put("driver_id", driverId)
                            put("lat", location.latitude)
                            put("lng", location.longitude)
                        }
                        socket?.emit("update_location", data)
                    }
                }
            }
        }
    }

    val mapProperties by remember(mapType, hasLocationPermission) {
        mutableStateOf(MapProperties(
            isMyLocationEnabled = false,
            mapType = mapType
        ))
    }
    val uiSettings by remember {
        mutableStateOf(MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false, compassEnabled = true))
    }

    fun fetchBusInfo() {
        scope.launch {
            try {
                RetrofitClient.setToken(sessionManager.getToken())
                val response = RetrofitClient.instance.getDriverBus()
                if (response.isSuccessful) {
                    assignedBus = response.body()
                    showBusEntryDialog = false
                } else {
                    showBusEntryDialog = true
                }
            } catch (e: Exception) {
                showBusEntryDialog = true
            }
        }
    }

    LaunchedEffect(Unit) {
        fetchBusInfo()
    }

    DisposableEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            socket?.connect()
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                .setMinUpdateIntervalMillis(1000L)
                .build()

            val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
            val client: SettingsClient = LocationServices.getSettingsClient(context)
            val task = client.checkLocationSettings(builder.build())

            task.addOnSuccessListener {
                startLocationUpdates(fusedLocationClient, locationCallback)
            }

            task.addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution.intentSender).build()
                        settingsLauncher.launch(intentSenderRequest)
                    } catch (sendEx: Exception) {}
                }
            }
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        onDispose {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            socket?.disconnect()
        }
    }

    if (showBusEntryDialog) {
        AlertDialog(
            onDismissRequest = { showBusEntryDialog = false },
            title = { Text("Assign Bus Number") },
            text = {
                Column {
                    Text("Enter your bus number to start live tracking.")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = busNumberInput,
                        onValueChange = { busNumberInput = it },
                        label = { Text("Bus Number (e.g. Bus 1)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isSubmitting
                    )
                    busEntryError?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showBusEntryDialog = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isSubmitting = true
                            busEntryError = null
                            try {
                                val token = sessionManager.getToken()
                                Log.d("BusAssignment", "Token: $token")
                                RetrofitClient.setToken(token)
                                val requestBody = AssignBusRequest(busNumberInput.trim())
                                Log.d("BusAssignment", "Sending request with bus_number: ${requestBody.bus_number}")

                                val response = RetrofitClient.instance.assignBus(requestBody)
                                Log.d("BusAssignment", "Response code: ${response.code()}, Is successful: ${response.isSuccessful}")

                                if (response.isSuccessful) {
                                    assignedBus = response.body()
                                    Log.d("BusAssignment", "Bus assigned successfully: ${response.body()}")
                                    showBusEntryDialog = false
                                    busNumberInput = ""
                                } else {
                                    val errorBody = response.errorBody()?.string()
                                    Log.e("BusAssignment", "Error code: ${response.code()}, Body: $errorBody")

                                    busEntryError = when {
                                        response.code() == 401 -> "Session expired. Please login again."
                                        response.code() == 400 -> {
                                            if (errorBody?.contains("already assigned") == true) {
                                                "This bus is already taken by another driver"
                                            } else {
                                                "Invalid bus number. Please check and try again."
                                            }
                                        }
                                        response.code() >= 500 -> "Server error. Please try again later."
                                        else -> errorBody?.let {
                                            try {
                                                JSONObject(it).optString("msg", "Error assigning bus. Please try again.")
                                            } catch (e: Exception) {
                                                "Error assigning bus. Please try again."
                                            }
                                        } ?: "Error assigning bus. Please try again."
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("BusAssignment", "Exception: ${e.message}", e)
                                busEntryError = "Connection failed: ${e.message ?: "Unknown error"}"
                            } finally {
                                isSubmitting = false
                            }
                        }
                    },
                    enabled = busNumberInput.isNotBlank() && !isSubmitting
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text("Confirm")
                    }
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Driver Portal", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Change/Set Bus Number") },
                    selected = false,
                    onClick = { 
                        busNumberInput = assignedBus?.bus_number ?: ""
                        busEntryError = null
                        showBusEntryDialog = true
                        scope.launch { drawerState.close() } 
                    },
                    icon = { Icon(Icons.Default.DirectionsBus, contentDescription = null) }
                )
                NavigationDrawerItem(
                    label = { Text("Logout") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            sessionManager.logout()
                            navController.navigate("login") { popUpTo(0) { inclusive = true } }
                        }
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = mapProperties,
                uiSettings = uiSettings
            ) {
                currentLatLng?.let { loc ->
                    MarkerComposable(
                        state = MarkerState(position = loc),
                        title = assignedBus?.bus_number ?: "Your Bus",
                        snippet = "To: ${assignedBus?.home_route_info ?: "N/A"}",
                        anchor = Offset(0.5f, 0.5f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White,
                            shadowElevation = 8.dp,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsBus,
                                    contentDescription = null,
                                    tint = Color(0xFF1976D2),
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Top UI
            Row(
                modifier = Modifier.statusBarsPadding().padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = 8.dp,
                    color = Color.White
                ) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }

                Box {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        shadowElevation = 8.dp,
                        color = Color.White
                    ) {
                        IconButton(onClick = { showMapTypeMenu = !showMapTypeMenu }) {
                            Icon(Icons.Default.Layers, contentDescription = "Map Type")
                        }
                    }

                    DropdownMenu(expanded = showMapTypeMenu, onDismissRequest = { showMapTypeMenu = false }) {
                        MapType.entries.filter { it != MapType.NONE }.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = { mapType = type; showMapTypeMenu = false }
                            )
                        }
                    }
                }
            }

            // Bottom Panel
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                shadowElevation = 16.dp,
                color = Color.White
            ) {
                Column(modifier = Modifier.padding(24.dp).navigationBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = assignedBus?.bus_number ?: "No Bus Selected",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Target: ${assignedBus?.home_route_info ?: "Route Not Assigned"}",
                                fontSize = 14.sp,
                                color = Color.Gray
                            )
                        }
                        
                        if (isTracking) {
                            Surface(color = Color(0xFF34A853), shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    text = "LIVE",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = { 
                            if (assignedBus == null) {
                                showBusEntryDialog = true
                            } else if (currentLatLng != null) {
                                isTracking = !isTracking 
                            }
                        },
                        enabled = currentLatLng != null || assignedBus == null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (isTracking) MaterialTheme.colorScheme.error else Color.Black)
                    ) {
                        Text(
                            text = if (assignedBus == null) "SELECT BUS TO START" else if (isTracking) "STOP TRACKING" else "START TRACKING",
                            modifier = Modifier.padding(vertical = 8.dp),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun startLocationUpdates(fusedLocationClient: FusedLocationProviderClient, callback: LocationCallback) {
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
    fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
}
