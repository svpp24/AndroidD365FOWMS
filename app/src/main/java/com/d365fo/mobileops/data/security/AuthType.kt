package com.d365fo.mobileops.data.security

/**
 * How this device proves itself to Azure AD. Independent of how a worker proves themselves to
 * HSMobileOpsUser on the sign-in screen - this is the device's own identity, not theirs.
 */
enum class AuthType {
    /** A client secret compiled into the build or typed into Settings. See ClientCredentialsTokenProvider. */
    CLIENT_CREDENTIALS,

    /** The OAuth2 device-code grant. See DeviceCodeTokenProvider. */
    DEVICE_CODE,
}
