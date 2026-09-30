package com.d365fo.mobileops.domain.model

import com.d365fo.mobileops.data.repository.WorkerSession

/**
 * Whether a signed-in worker may reach the Admin Center.
 *
 * Deliberately not a new field on [WorkerSession] - `isSupervisor` already exists, already
 * round-trips from `HSMobileOpsUser.IsSupervisor` on every sign-in, and until now was read and
 * never consumed by anything on this side. Adding a second, parallel "is this person an admin"
 * flag would mean two things to keep in sync for one question; deriving it here means there is
 * exactly one source of truth; a role can never disagree with `isSupervisor` because it is
 * `isSupervisor`.
 *
 * Recomputed from the live session every time, not cached - `WorkerSession` itself is memory-only
 * in a release build (see [WorkerSession]'s own class doc), so ADMIN status can never survive an
 * app restart without the server re-asserting `IsSupervisor` at the next sign-in. That is a
 * deliberate security property, not an oversight - see the plan's Part 5 for why local role data
 * is never trusted as the actual authorization boundary, only as what the UI shows.
 */
enum class WorkerRole { ADMIN, OPERATOR }

val WorkerSession.role: WorkerRole
    get() = if (isSupervisor) WorkerRole.ADMIN else WorkerRole.OPERATOR
