# ProGuard rules for Lucifer Mobile

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp { *; }
-keep class * extends dagger.hilt.android.HiltViewModel { *; }

# Keep Compose related classes
-keep class androidx.compose.** { *; }
-keep class org.jetbrains.compose.** { *; }

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep Ktor client
-keep class io.ktor.** { *; }

# Keep KSP generated
-keep class com.google.dagger.hilt.** { *; }

# Keep Kotlinx serialization
-keep class kotlinx.serialization.** { *; }

# Keep OkHttp/Okio
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep BouncyCastle
-keep class org.bouncycastle.** { *; }

# Keep JWT decode
-keep class com.auth0.android.jwtdecode.** { *; }

# Keep Jose4KT
-keep class org.bitbucket.b_c.jose4kt.** { *; }

# Keep native library
-keep class com.lucifer.mobile.LuciferApplication { *; }

# Keep Hilt ViewModels
-keep class * extends androidx.lifecycle.ViewModel { *; }

# Keep Hilt EntryPoints
-keep class * extends dagger.hilt.android.EntryPoint { *; }

# Keep Kai Bridge native methods
-keep class com.lucifer.mobile.omni_bridge.kai_bridge.** { *; }
-keep class com.lucifer.mobile.data.wallet.LuciferWallet { *; }
-keep class com.lucifer.mobile.data.browser.LuciferBrowser { *; }
-keep class com.lucifer.mobile.data.llm.LuciferLLM { *; }
-keep class com.lucifer.mobile.data.sandbox.LuciferSandbox { *; }
-keep class com.lucifer.mobile.omni_bridge.kai_bridge.KaiBridge { *; }

# Keep OmniBridge
-keep class com.lucifer.mobile.omni_bridge.OmniBridge { *; }

# Keep Services
-keep class com.lucifer.mobile.services.** { *; }

# Keep Receivers
-keep class com.lucifer.mobile.receivers.** { *; }

# Keep Launcher Activity
-keep class com.lucifer.mobile.launcher.LauncherActivity { *; }
-keep class com.lucifer.mobile.launcher.** { *; }

# Don't obfuscate native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNI classes
-keep class com.lucifer.mobile.LuciferApplication { *; }

# Don't warn about missing classes
-dontwarn org.jetbrains.compose.**
-dontwarn androidx.compose.**
-dontwarn kotlinx.coroutines.**
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.bouncycastle.**
-dontwarn com.auth0.android.jwtdecode.**
-dontwarn org.bitbucket.b_c.jose4kt.**
-dontwarn dagger.hilt.**
-dontwarn kotlinx.serialization.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder