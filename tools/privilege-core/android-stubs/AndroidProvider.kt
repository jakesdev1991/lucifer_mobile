@file:Suppress("unused")

package android.provider

class Settings private constructor() {
    companion object {
        const val ACTION_MANAGED_PROFILE_SETTINGS = "android.settings.MANAGED_PROFILE_SETTINGS"
        const val ACTION_DEVICE_ADMIN_SETTINGS = "android.settings.DEVICE_ADMIN_SETTINGS"
    }
}
