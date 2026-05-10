package com.example.track.network

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.Response

data class LoginRequest(val email: String, val password: String)
data class LoginResponse(val access_token: String, val role: String, val user_id: Int, val is_approved: Boolean? = true, val name: String? = null)
data class RegisterRequest(val name: String, val email: String, val role: String, val password: String)

data class DriverRequest(val id: Int, val name: String, val email: String, val role: String)

data class BusResponse(
    val id: Int? = null,
    val bus_number: String,
    val route_name: String? = null,
    val home_route_info: String? = null,
    val status: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val driver_id: Int? = null,
    val driver_name: String? = null,
    val is_home_bus: Boolean? = false,
    val departure_time: String? = null,
    val time_period: String? = null
)

data class StopResponse(
    val id: Int,
    val name: String,
    val lat: Double,
    val lng: Double
)

data class AssignStopRequest(
    val driver_id: Int,
    val stop_id: Int
)

data class UpdateHomeInfoRequest(
    val bus_id: Int,
    val home_route_info: String,
    val route_type: String? = "HOME",
    val time_period: String? = null,
    val departure_time: String? = null
)

data class AssignBusRequest(val bus_number: String)

interface ApiService {
    @POST("/api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("/api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<Unit>

    @GET("/api/admin/pending-drivers")
    suspend fun getPendingDrivers(): Response<List<DriverRequest>>

    @GET("/api/admin/drivers")
    suspend fun getAllDrivers(): Response<List<DriverRequest>>

    @POST("/api/admin/approve-driver/{id}")
    suspend fun approveDriver(@Path("id") id: Int): Response<Unit>

    @POST("/api/admin/assign-stop")
    suspend fun assignStop(@Body request: AssignStopRequest): Response<Unit>

    @POST("/api/admin/update-bus-home-info")
    suspend fun updateBusHomeInfo(@Body request: UpdateHomeInfoRequest): Response<Unit>

    @GET("/api/buses")
    suspend fun getBuses(): Response<List<BusResponse>>

    @GET("/api/stops")
    suspend fun getStops(): Response<List<StopResponse>>
    
    @GET("/api/driver/bus")
    suspend fun getDriverBus(): Response<BusResponse>

    @POST("/api/driver/assign-bus")
    suspend fun assignBus(@Body request: AssignBusRequest): Response<BusResponse>
}
