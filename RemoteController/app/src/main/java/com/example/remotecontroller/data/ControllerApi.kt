package com.example.remotecontroller.data

import okhttp3.OkHttpClient
import okhttp3.Interceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

data class LoginRequest(val email: String, val password: String)
data class LoginResponse(val token: String, val userId: String)
data class Device(val id: String, val name: String, val createdAt: String)
data class DevicesResponse(val devices: List<Device>)
data class RequestBody(val capability: String)
data class RequestResponse(val sessionId: String, val status: String)
data class SessionResponse(val sessionId: String, val capability: String, val status: String)

interface ControllerApi {
    @POST("auth/login") suspend fun login(@Body body: LoginRequest): LoginResponse
    @GET("devices") suspend fun devices(): DevicesResponse
    @POST("devices/{deviceId}/requests")
    suspend fun request(@Path("deviceId") deviceId: String, @Body body: RequestBody): RequestResponse
    @GET("sessions/{sessionId}")
    suspend fun session(@Path("sessionId") sessionId: String): SessionResponse
}

class ControllerRepository(private val api: ControllerApi) {
    suspend fun login(email: String, password: String) = api.login(LoginRequest(email, password))
    suspend fun devices() = api.devices().devices
    suspend fun request(deviceId: String, capability: String) =
        api.request(deviceId, RequestBody(capability))
    suspend fun session(sessionId: String) = api.session(sessionId)

    companion object {
        fun create(baseUrl: String, token: String? = null): ControllerRepository {
            val auth = Interceptor { chain ->
                val request = chain.request().newBuilder()
                if (!token.isNullOrBlank()) request.header("Authorization", "Bearer $token")
                chain.proceed(request.build())
            }
            val client = OkHttpClient.Builder().addInterceptor(auth).build()
            val api = Retrofit.Builder().baseUrl(baseUrl).client(client)
                .addConverterFactory(GsonConverterFactory.create()).build()
                .create(ControllerApi::class.java)
            return ControllerRepository(api)
        }
    }
}
