package com.example.track.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
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
import com.example.track.R
import com.example.track.SessionManager
import com.example.track.network.BusResponse
import com.example.track.network.RetrofitClient
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URISyntaxException

enum class StudentTab {
    COLLEGE, HOME
}

@Composable
fun BusSection(title: String, buses: List<BusResponse>, backgroundColor: Color) {
    Column {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (buses.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = backgroundColor.copy(alpha = 0.5f)
            ) {
                Box(modifier = Modifier.padding(16.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No buses available", color = Color.Gray)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                buses.forEach { bus ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = backgroundColor,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DirectionsBus,
                                        contentDescription = null,
                                        modifier = Modifier.padding(8.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Bus No: ${bus.bus_number}",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (!bus.departure_time.isNullOrEmpty()) {
                                        Text(
                                            text = "Time: ${bus.departure_time}",
                                            fontSize = 12.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (bus.status == "ACTIVE") Color(0xFF34A853) else Color(0xFFBDBDBD)
                                ) {
                                    Text(
                                        text = bus.status ?: "INACTIVE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            if (!bus.home_route_info.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row {
                                    Icon(
                                        Icons.Default.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = bus.home_route_info,
                                        fontSize = 13.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                            if (!bus.driver_name.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = bus.driver_name,
                                        fontSize = 13.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentDashboard(navController: NavHostController) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    var selectedTab by remember { mutableStateOf(StudentTab.COLLEGE) }
    var buses by remember { mutableStateOf<List<BusResponse>>(emptyList()) }
    
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    var studentLocation by remember { mutableStateOf(LatLng(0.0, 0.0)) }
    var mapType by remember { mutableStateOf(MapType.NORMAL) }
    var showMapTypeMenu by remember { mutableStateOf(false) }

    // Permission state
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 2f)
    }

    // Optimization: Remember Map Properties and UI Settings
    val mapProperties by remember(mapType, hasLocationPermission) {
        mutableStateOf(MapProperties(
            isMyLocationEnabled = hasLocationPermission,
            mapType = mapType
        ))
    }
    val uiSettings by remember {
        mutableStateOf(MapUiSettings(zoomControlsEnabled = false))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasLocationPermission = isGranted
        }
    )

    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val newLoc = LatLng(location.latitude, location.longitude)
                    studentLocation = newLoc
                    // Update camera to user location if it was at default
                    if (cameraPositionState.position.target.latitude == 0.0) {
                        cameraPositionState.position = CameraPosition.fromLatLngZoom(newLoc, 15f)
                    }
                }
            }
        }
    }

    val socket: Socket? = remember {
        try {
            IO.socket(Constants.BASE_URL)
        } catch (e: URISyntaxException) {
            null
        }
    }

    fun fetchBuses() {
        scope.launch {
            try {
                val response = RetrofitClient.instance.getBuses()
                if (response.isSuccessful) {
                    buses = response.body() ?: emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(Unit) {
        fetchBuses()
        if (hasLocationPermission) {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
            try {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        socket?.connect()
        socket?.on("bus_location_update") { args ->
            val data = args[0] as JSONObject
            val driverId = data.getInt("driver_id")
            val lat = data.getDouble("lat")
            val lng = data.getDouble("lng")

            buses = buses.map { bus ->
                if (bus.driver_id == driverId) {
                    bus.copy(lat = lat, lng = lng, status = "ACTIVE")
                } else {
                    bus
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            socket?.disconnect()
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Student Portal",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider()
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
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(
                        selected = selectedTab == StudentTab.COLLEGE,
                        onClick = { selectedTab = StudentTab.COLLEGE },
                        icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                        label = { Text("College") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == StudentTab.HOME,
                        onClick = { selectedTab = StudentTab.HOME },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Home") }
                    )
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                if (selectedTab == StudentTab.COLLEGE) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        properties = mapProperties,
                        uiSettings = uiSettings
                    ) {
                        buses.forEach { bus ->
                            if (bus.lat != null && bus.lng != null && bus.status == "ACTIVE") {
                                key(bus.id) {
                                    MarkerComposable(
                                        state = MarkerState(position = LatLng(bus.lat, bus.lng)),
                                        title = "Bus: ${bus.bus_number}",
                                        snippet = "Driver: ${bus.driver_name ?: "Unknown"}",
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
                        }
                    }

                    // Map Control UI
                    Column(modifier = Modifier.padding(16.dp).align(Alignment.TopEnd)) {
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

                    // Info Overlay
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        shadowElevation = 8.dp,
                        color = Color.White.copy(alpha = 0.9f)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Showing ${buses.count { it.lat != null && it.status == "ACTIVE" }} active buses", fontWeight = FontWeight.Medium)
                        }
                    }
                } else {
                    // Home Tab: Afternoon and Evening bus routes
                    val homeBuses = buses.filter { it.is_home_bus == true }
                    val afternoonBuses = homeBuses.filter { it.time_period == "AFTERNOON" }
                    val eveningBuses = homeBuses.filter { it.time_period == "EVENING" }

                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            text = "Home Routes",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        if (homeBuses.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No home bus routes available")
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                item {
                                    BusSection(
                                        title = "Afternoon",
                                        buses = afternoonBuses,
                                        backgroundColor = Color(0xFFFFF3E0)
                                    )
                                }
                                item {
                                    BusSection(
                                        title = "Evening",
                                        buses = eveningBuses,
                                        backgroundColor = Color(0xFFE3F2FD)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
