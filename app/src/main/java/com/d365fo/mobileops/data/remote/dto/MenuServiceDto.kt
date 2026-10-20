package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HSMobileOpsMenuSvc/getMenu - the worker's handheld menus (Mobile device menus in D365FO). */
@Serializable
data class MenuServiceRequest(@SerialName("_request") val request: MenuRequestBody)

@Serializable
data class MenuRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserName") val userName: String,
)

/** [rootMenuId] is blank when no menu is set up - Home then keeps its plain list of modules. */
@Serializable
data class MenuServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String = "",
    @SerialName("RootMenuId") val rootMenuId: String = "",
    @SerialName("Menus") val menus: List<MenuRowDto> = emptyList(),
)

@Serializable
data class MenuRowDto(
    @SerialName("MenuId") val menuId: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Lines") val lines: List<MenuLineRowDto> = emptyList(),
)

/** LineType MODULE carries [module] (a WarehouseWorkflow name); MENU carries [subMenuId]. */
@Serializable
data class MenuLineRowDto(
    @SerialName("LineType") val lineType: String = "",
    @SerialName("Module") val module: String = "",
    @SerialName("SubMenuId") val subMenuId: String = "",
)
