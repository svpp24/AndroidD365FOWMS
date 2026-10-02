package com.d365fo.mobileops.util

/**
 * A D365FO service error code as a sentence a worker can act on. Unknown codes keep a short
 * generic sentence and the code at the end, so support can still look it up.
 */
fun plainError(code: String?, whatFailed: String): String = when (code?.uppercase()) {
    null, "" -> "$whatFailed. Try again."
    "INVALID_REQUEST" -> "$whatFailed - the request was incomplete. Sign out and in, then try again."
    "INVALID_DATE" -> "$whatFailed - the device date looks wrong. Check the date and time settings."
    "USER_NOT_FOUND" -> "$whatFailed - your user isn't set up in this company. Ask a supervisor."
    "NOT_SUPERVISOR" -> "Only a cycle-count supervisor can do this."
    "JOURNAL_NOT_FOUND", "NOT_FOUND" -> "$whatFailed - it's closed or no longer exists in D365FO."
    "JOURNAL_POSTED" -> "This journal has already been posted."
    "LINE_NOT_FOUND" -> "$whatFailed - a line is no longer on this journal. Get data again."
    "NOT_AVAILABLE", "NOT_IMPLEMENTED" -> "This isn't available on this D365FO server yet."
    "POST_FAILED" -> "D365FO couldn't post it. A supervisor can see why in D365FO."
    else -> "$whatFailed (D365FO code $code)."
}
