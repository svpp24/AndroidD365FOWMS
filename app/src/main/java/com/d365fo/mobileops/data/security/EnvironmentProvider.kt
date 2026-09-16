package com.d365fo.mobileops.data.security

/**
 * Late-bound accessors for values that are chosen at runtime rather than at graph-construction
 * time: the active D365FO environment, and the legal entity the handheld is operating in.
 *
 * Declared as our own functional interfaces rather than `javax.inject.Provider` because Dagger
 * refuses to let a `@Provides` method return one of its own framework types — it cannot tell a
 * deliberate indirection from a mistake. These also read better at the call site than a bare
 * `Provider<String?>`, which says nothing about what the string is.
 */
fun interface EnvironmentProvider {
    operator fun invoke(): D365Environment
}

fun interface CompanyProvider {
    /** D365FO `dataAreaId` for the X-Company header, or null before device registration. */
    operator fun invoke(): String?
}
