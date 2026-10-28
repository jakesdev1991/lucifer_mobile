@file:Suppress("unused", "UNUSED_PARAMETER")

package android.os

import android.content.pm.UserInfo

class Bundle {
    fun keySet(): Set<String> = emptySet()
}

interface IBinder

class UserHandle(val identifier: Int = 0) {
    override fun hashCode(): Int = identifier
}

class UserManager {
    val userProfiles: List<UserHandle> = emptyList()
    val userRestrictions: Bundle = Bundle()
    fun getUserInfo(handle: UserHandle): UserInfo = UserInfo(0, "Owner", false)
}

class Build private constructor() {
    object VERSION { const val SDK_INT = 35 }
}

object Process {
    fun myUid(): Int = 10123
    fun myUserHandle(): UserHandle = UserHandle(0)
}
