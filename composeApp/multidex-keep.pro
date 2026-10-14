# MultiDex keep rules for Lucifer Mobile
# Keep the launcher activity in the main dex file
-keep class com.lucifer.mobile.launcher.LauncherActivity { *; }
-keep class com.lucifer.mobile.MainActivity { *; }
-keep class com.lucifer.mobile.LuciferApplication { *; }
-keep class com.lucifer.mobile.omni_bridge.heartbeat.LuciferHeartbeatService { *; }
-keep class com.lucifer.mobile.services.SSHService { *; }