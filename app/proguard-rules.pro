# Keep App Functions generated metadata and serializable DTOs intact so the
# OS can reflect over them when an agent (Gemini) invokes a function.
-keep class androidx.appfunctions.** { *; }
-keep @androidx.appfunctions.AppFunctionSerializable class * { *; }
-keepclassmembers class * {
    @androidx.appfunctions.service.AppFunction *;
}

# Jsoup
-keeppackagenames org.jsoup.nodes

# Cronet (Chromium network stack) — loaded reflectively/JNI.
-keep class org.chromium.net.** { *; }
-dontwarn org.chromium.net.**

# OkHttp / Okio (standard consumer rules ship with the libs; these are belt-and-suspenders)
-dontwarn okhttp3.**
-dontwarn okio.**
