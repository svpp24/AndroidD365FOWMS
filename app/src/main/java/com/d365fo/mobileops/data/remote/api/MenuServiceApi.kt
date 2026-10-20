package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.data.remote.dto.MenuServiceRequest
import com.d365fo.mobileops.data.remote.dto.MenuServiceResponse
import com.d365fo.mobileops.data.remote.interceptor.IdempotentRead
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

/** `HSMobileOpsMenuSvc` - a read. */
interface MenuServiceApi {

    @POST("HSMobileOpsServiceGroup/HSMobileOpsMenuSvc/getMenu")
    suspend fun getMenu(
        @Body body: MenuServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<MenuServiceResponse>
}
