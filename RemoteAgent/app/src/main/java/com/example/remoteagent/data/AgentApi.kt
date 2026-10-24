package com.example.remoteagent.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import java.net.URI

data class DecisionRequest(val decision: String)
data class DecisionResponse(val sessionId: String, val status: String)
data class EndResponse(val sessionId: String, val status: String)
data class AccessState(val enabled: Boolean)
data class FcmTokenRequest(val fcmToken: String)

interface AgentApi {
    @POST("agent/sessions/{sessionId}/decision")
    suspend fun decide(@Path("sessionId") id: String, @Body body: DecisionRequest): DecisionResponse

    @POST("agent/sessions/{sessionId}/end")
    suspend fun end(@Path("sessionId") id: String): EndResponse

    @GET("agent/access")
    suspend fun access(): AccessState

    @PUT("agent/access")
    suspend fun setAccess(@Body body: AccessState): AccessState

    @PUT("agent/fcm-token")
    suspend fun updateFcmToken(@Body body: FcmTokenRequest)
}

class AgentRepository(private val api: AgentApi) {
    suspend fun decide(sessionId: String, accept: Boolean) =
        api.decide(sessionId, DecisionRequest(if (accept) "accept" else "decline"))

    suspend fun end(sessionId: String) = api.end(sessionId)
    suspend fun access() = api.access()
    suspend fun setAccess(enabled: Boolean) = api.setAccess(AccessState(enabled))
    suspend fun updateFcmToken(token: String) = api.updateFcmToken(FcmTokenRequest(token))

    companion object {
        fun create(baseUrl: String, token: String): AgentRepository {
            val parsed = URI(baseUrl.trim())
            require(parsed.scheme == "https" && parsed.host != null) {
                "RemoteHub URL must be a valid HTTPS URL."
            }
            val normalizedBaseUrl = baseUrl.trim().trimEnd('/') + "/"
            val auth = Interceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .header("Authorization", "Bearer $token").build())
            }
            val client = OkHttpClient.Builder().addInterceptor(auth).build()
            val api = Retrofit.Builder().baseUrl(normalizedBaseUrl).client(client)
                .addConverterFactory(GsonConverterFactory.create()).build()
                .create(AgentApi::class.java)
            return AgentRepository(api)
        }
    }
}
