# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK tools proguard-defaults.txt file.

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Keep the IME service
-keep class com.aixtin.invisime.InvisImeService { *; }
-keep class com.aixtin.invisime.InjectReceiver { *; }
