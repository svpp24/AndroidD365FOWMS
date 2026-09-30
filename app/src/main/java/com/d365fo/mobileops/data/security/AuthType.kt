package com.d365fo.mobileops.data.security

/**
 * How this device proves itself to Azure AD. Independent of how a worker proves themselves to
 * HSMobileOpsUser on the sign-in screen - this is the device's own identity, not theirs.
 *
 * Restored after a brief removal earlier this session. Device-code alone turned out to be
 * genuinely painful in practice for this fleet's commissioning flow - admin consent, the
 * "Allow public client flows" toggle, a multi-tenant app registration swap, and AAD's own slow
 * device-code round trips all had to be worked through live, on explicit instruction to remove
 * client-credentials first. Brought back on the reverse instruction, once that cost was felt
 * directly rather than argued about in the abstract.
 */
enum class AuthType {
    /** A client secret compiled into the build or typed into Settings. See ClientCredentialsTokenProvider. */
    CLIENT_CREDENTIALS,

    /** The OAuth2 device-code grant. See DeviceCodeTokenProvider. */
    DEVICE_CODE,
}
