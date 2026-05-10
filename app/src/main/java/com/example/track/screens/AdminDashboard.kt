package com.example.track.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.track.Constants
import com.example.track.R
import com.example.track.SessionManager
import com.example.track.bitmapDescriptorFromVector
import com.example.track.network.BusResponse
import com.example.track.network.RetrofitClient
import com.example.track.network.UpdateHomeInfoRequest
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URISyntaxException

enum class AdminTab {
    MAP, ASSIGNMENTS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboard(navController: NavHostController) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(AdminTab.MAP) }
    var buses by remember { mutableStateOf<List<BusResponse>>(emptyList()) }
    var mapType by remember { mutableStateOf(MapType.NORMAL) }
    var showMapTypeMenu by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    
    val cameraPositionState = rememberCameraPositionState {
        // Initial camera position set to a neutral default
        position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 2f)
    }

    // Bus Icon
    val busIcon = remember {
        bitmapDescriptorFromVector(context, R.drawable.ic_bus)
    }

    val mapProperties by remember(mapType) {
        mutableStateOf(MapProperties(mapType = mapType, isMyLocationEnabled = false))
    }
    val uiSettings by remember {
        mutableStateOf(MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false))
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
            isRefreshing = true
            try {
                val response = RetrofitClient.instance.getBuses()
                if (response.isSuccessful) {
                    buses = response.body() ?: emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        fetchBuses()
        socket?.connect()
        socket?.on("bus_location_update") { args ->
            val data = args[0] as JSONObject
            val driverId = data.getInt("driver_id")
            val lat = data.getDouble("lat")
            val lng = data.getDouble("lng")

            // Update the bus location in the list
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
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Admin Portal",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.headlineSmall,
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
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == AdminTab.MAP,
                        onClick = { selectedTab = AdminTab.MAP },
                        icon = { Icon(Icons.Default.Map, contentDescription = null) },
                        label = { Text("Map") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == AdminTab.ASSIGNMENTS,
                        onClick = { selectedTab = AdminTab.ASSIGNMENTS },
                        icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        label = { Text("Assign") }
                    )
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                if (selectedTab == AdminTab.MAP) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        properties = mapProperties,
                        uiSettings = uiSettings
                    ) {
                        buses.forEach { bus ->
                            if (bus.lat != null && bus.lng != null && bus.status == "ACTIVE") {
                                key(bus.id) {
                                    Marker(
                                        state = MarkerState(position = LatLng(bus.lat, bus.lng)),
                                        title = "Bus: ${bus.bus_number}",
                                        snippet = "To: ${bus.home_route_info ?: "N/A"}",
                                        icon = busIcon ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                                    )
                                }
                            }
                        }
                    }

                    // Top UI
                    Row(
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(16.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            shadowElevation = 8.dp,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box {
                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    shadowElevation = 8.dp,
                                    color = MaterialTheme.colorScheme.surface
                                ) {
                                    IconButton(onClick = { showMapTypeMenu = !showMapTypeMenu }) {
                                        Icon(Icons.Default.Layers, contentDescription = "Map Type")
                                    }
                                }

                                DropdownMenu(
                                    expanded = showMapTypeMenu,
                                    onDismissRequest = { showMapTypeMenu = false }
                                ) {
                                    MapType.entries.filter { it != MapType.NONE }.forEach { type ->
                                        DropdownMenuItem(
                                            text = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                            onClick = {
                                                mapType = type
                                                showMapTypeMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                shadowElevation = 8.dp,
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                IconButton(onClick = { fetchBuses() }) {
                                    if (isRefreshing) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Assignments Tab
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            "Bus Route Assignments", 
                            fontSize = 24.sp, 
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "Set destination and time for home routes",
                            fontSize = 14.sp, 
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        if (buses.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(64.dp))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("No buses found in database", color = Color.Gray)
                                    TextButton(onClick = { fetchBuses() }) {
                                        Text("Tap to Refresh")
                                    }
                                }
                            }
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(buses) { bus ->
                                    BusAssignmentCard(bus) { destination, type, period, time ->
                                        scope.launch {
                                            bus.id?.let { busId ->
                                                try {
                                                    val response = RetrofitClient.instance.updateBusHomeInfo(
                                                        UpdateHomeInfoRequest(
                                                            bus_id = busId,
                                                            home_route_info = destination,
                                                            route_type = type,
                                                            time_period = period,
                                                            departure_time = time
                                                        )
                                                    )
                                                    if (response.isSuccessful) {
                                                        fetchBuses()
                                                    }
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BusAssignmentCard(bus: BusResponse, onUpdate: (String, String, String, String) -> Unit) {
    var destination by remember(bus.id) { mutableStateOf(bus.home_route_info ?: "") }
    var routeType by remember(bus.id) { mutableStateOf(if (bus.is_home_bus == true) "HOME" else "COLLEGE") }
    var timePeriod by remember(bus.id) { mutableStateOf(bus.time_period ?: "AFTERNOON") }
    var departureTime by remember(bus.id) { mutableStateOf(bus.departure_time ?: "") }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Bus Number: ${bus.bus_number}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text("Route Type:", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = routeType == "COLLEGE", onClick = { routeType = "COLLEGE" })
                Text("College", fontSize = 14.sp)
                Spacer(modifier = Modifier.width(16.dp))
                RadioButton(selected = routeType == "HOME", onClick = { routeType = "HOME" })
                Text("Home", fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text("Destination (e.g. Haldwani)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            if (routeType == "HOME") {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Time Period:", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = timePeriod == "AFTERNOON", onClick = { timePeriod = "AFTERNOON" })
                    Text("Afternoon", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(16.dp))
                    RadioButton(selected = timePeriod == "EVENING", onClick = { timePeriod = "EVENING" })
                    Text("Evening", fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = departureTime,
                    onValueChange = { departureTime = it },
                    label = { Text("Departure Time (e.g. 04:30 PM)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = { onUpdate(destination, routeType, timePeriod, departureTime) },
                modifier = Modifier.align(Alignment.End),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Confirm Assignment")
            }
        }
    }
}
