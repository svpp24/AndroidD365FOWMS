package com.d365fo.mobileops.util

/**
 * A D365FO service error code as a sentence a worker can act on. Unknown codes keep a short
 * generic sentence and the code at the end, so support can still look it up.
 */
fun plainError(code: String?, whatFailed: String): String = when (code?.uppercase()) {
    null, "" -> "$whatFailed - try again."
    "INVALID_REQUEST" -> "$whatFailed - sign out and in, then retry."
    "INVALID_DATE" -> "$whatFailed - check the device date."
    "USER_NOT_FOUND" -> "$whatFailed - user not set up. Ask a supervisor."
    "NOT_SUPERVISOR" -> "Only a cycle-count supervisor can do this."
    "JOURNAL_NOT_FOUND", "NOT_FOUND" -> "$whatFailed - it no longer exists."
    "JOURNAL_POSTED" -> "Already posted."
    "LINE_NOT_FOUND" -> "$whatFailed - get data again."
    "NOT_AVAILABLE", "NOT_IMPLEMENTED" -> "Not available yet."
    "POST_FAILED" -> "Couldn't post - ask a supervisor."
    else -> "$whatFailed (D365FO code $code)."
}
