// android.os, as far as Lucifer's world touches it.
// Signatures mirror the API 35 SDK jar; see the note at the top of AndroidCore.kt.
//
// Two members the old stub invented are gone on purpose:
//
//   * `UserManager.getUserInfo(...)` — every overload is `@hide` in the platform, so android.jar has
//     none of them. Nothing in Lucifer's adapters calls it; the stub was declaring an API that does
//     not exist and would have hidden a real compile error.
//   * a public `UserHandle(int)` constructor — the SDK has none. It stays here as a stub-only
//     convenience (the harnesses need instances), which is why this file is compile-only.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.os

open class Bundle {
    fun keySet(): Set<String> = emptySet()
    fun getString(key: String): String? = null
}

/** What DevicePolicyManager.createAndManageUser takes — not a `Bundle`. */
open class PersistableBundle : Bundle()

interface IBinder

/**
 * The SDK exposes no way to read the user id back out of a UserHandle (`getIdentifier()` is
 * `@SystemApi`), which is why WorldManager parses `toString()` — the platform renders it as
 * `UserHandle{10}`. That format is what `userIdOf` depends on, so it is pinned here.
 */
class UserHandle(private val id: Int = 0) {
    override fun hashCode(): Int = id
    override fun toString(): String = "UserHandle{$id}"
}

class UserManager {
    /** @see UserManager.getUserProfiles in the SDK */
    val userProfiles: List<UserHandle> = emptyList()

    /** @see UserManager.getUserRestrictions */
    val userRestrictions: Bundle = Bundle()

    fun isUserRunning(user: UserHandle): Boolean = false
    fun isManagedProfile(): Boolean = false
    fun isProfile(): Boolean = false

    companion object {
        const val USER_OPERATION_SUCCESS = 0
        const val USER_OPERATION_ERROR_UNKNOWN = 1
        const val USER_OPERATION_ERROR_MANAGED_PROFILE = 2
    }
}

class Build private constructor() {
    object VERSION { const val SDK_INT = 35 }
}

object Process {
    fun myUid(): Int = 10123
    fun myUserHandle(): UserHandle = UserHandle(0)
}
