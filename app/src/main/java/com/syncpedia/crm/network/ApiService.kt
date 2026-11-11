package com.syncpedia.crm.network

import com.syncpedia.crm.models.ActivityItem
import com.syncpedia.crm.models.ApiListResponse
import com.syncpedia.crm.models.BulkIdsRequest
import com.syncpedia.crm.models.CreateActivityRequest
import com.syncpedia.crm.models.CreateLeadRequest
import com.syncpedia.crm.models.CreateLeadResponse
import com.syncpedia.crm.models.CrmNotification
import com.syncpedia.crm.models.GoogleConfigResponse
import com.syncpedia.crm.models.GoogleLoginRequest
import com.syncpedia.crm.models.Lead
import com.syncpedia.crm.models.LocationRequest
import com.syncpedia.crm.models.LoginRequest
import com.syncpedia.crm.models.LoginResponse
import com.syncpedia.crm.models.MarkAllReadRequest
import com.syncpedia.crm.models.ServerCallLog
import com.syncpedia.crm.models.SuccessResponse
import com.syncpedia.crm.models.UpdateLeadRequest
import com.syncpedia.crm.models.UploadResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Query


interface ApiService {

    @POST("auth.php")
    suspend fun login(
        @Body body: LoginRequest
    ): Response<LoginResponse>

    @GET("auth.php")
    suspend fun googleConfig(
        @Query("action") action: String = "google_config"
    ): Response<GoogleConfigResponse>

    @POST("auth.php")
    suspend fun googleLogin(
        @Body body: GoogleLoginRequest
    ): Response<LoginResponse>

    @GET("leads.php")
    suspend fun getLeads(
        @Query("search") search: String? = null,
        @Query("status") status: String? = null,
        @Query("status_changed") statusChanged: String? = null,
        @Query("limit") limit: Int = 10000,
        @Query("offset") offset: Int = 0,
        @Query("org_id") orgId: String? = null
    ): Response<ApiListResponse<Lead>>

    @POST("leads.php")
    suspend fun createLead(@Body body: CreateLeadRequest): Response<CreateLeadResponse>

    @PUT("leads.php")
    suspend fun updateLead(
        @Query("id") id: String,
        @Body body: UpdateLeadRequest
    ): Response<SuccessResponse>

    @GET("activities.php")
    suspend fun getActivities(
        @Query("lead_id") leadId: String
    ): Response<ApiListResponse<ActivityItem>>

    @POST("activities.php")
    suspend fun createActivity(@Body body: CreateActivityRequest): Response<SuccessResponse>

    /**
     * Website Call Log page reads and writes `/api/call_logs.php`, not `calls.php`.
     * Field names match the site form: call_type, call_status, duration_seconds, call_date, call_time.
     */
    @POST("call_logs.php")
    suspend fun addCallLog(
        @Query("action") action: String = "add_log",
        @Body body: com.syncpedia.crm.models.CallLogUploadRequest
    ): Response<ResponseBody>

    @Multipart
    @POST("call_logs.php")
    suspend fun addCallLogWithRecording(
        @Query("action") action: String = "add_log",
        @Part("call_type") callType: RequestBody,
        @Part("call_status") callStatus: RequestBody,
        @Part("duration_seconds") durationSeconds: RequestBody,
        @Part("call_date") callDate: RequestBody,
        @Part("call_time") callTime: RequestBody,
        @Part("client_phone") clientPhone: RequestBody,
        @Part("client_name") clientName: RequestBody,
        @Part("device_call_id") deviceCallId: RequestBody,
        @Part("latitude") latitude: RequestBody?,
        @Part("longitude") longitude: RequestBody?,
        @Part recording: MultipartBody.Part?
    ): Response<ResponseBody>

    @Multipart
    @POST("calls.php")
    suspend fun uploadCall(
        @Part("phoneNumber") phoneNumber: RequestBody,
        @Part("contactName") contactName: RequestBody,
        @Part("callType") callType: RequestBody,
        @Part("duration") duration: RequestBody,
        @Part("calledAt") calledAt: RequestBody,
        @Part("deviceCallId") deviceCallId: RequestBody,
        @Part("latitude") latitude: RequestBody?,
        @Part("longitude") longitude: RequestBody?,
        @Part("leadId") leadId: RequestBody?,
        @Part recording: MultipartBody.Part?
    ): Response<UploadResponse>

    @GET("call_logs.php")
    suspend fun getWebsiteCallLogs(
        @Query("action") action: String = "get_logs",
        @Query("period") period: String = "custom",
        @Query("date_from") dateFrom: String,
        @Query("date_to") dateTo: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100
    ): Response<com.syncpedia.crm.models.CallLogsPageResponse>

    @GET("calls.php")
    suspend fun getCallLogs(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50
    ): Response<ApiListResponse<ServerCallLog>>

    @POST("location.php")
    suspend fun postLocation(@Body body: LocationRequest): Response<SuccessResponse>

    /** Website bell — raw JSON array from notifications.php */
    @GET("notifications.php")
    suspend fun getNotifications(): Response<List<CrmNotification>>

    @PUT("notifications.php")
    suspend fun markNotificationRead(
        @Query("id") id: String
    ): Response<SuccessResponse>

    @PUT("notifications.php")
    suspend fun markAllNotificationsRead(
        @Query("action") action: String = "mark_all_read",
        @Body body: MarkAllReadRequest
    ): Response<SuccessResponse>

    @DELETE("notifications.php")
    suspend fun deleteNotification(@Query("id") id: String): Response<SuccessResponse>

    @HTTP(method = "DELETE", path = "notifications.php", hasBody = true)
    suspend fun bulkDeleteNotifications(
        @Query("action") action: String = "bulk_delete",
        @Body body: BulkIdsRequest
    ): Response<SuccessResponse>
}
