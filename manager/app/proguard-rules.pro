# Protobuf
-shrinkunusedprotofields

# Commons-compress
-dontwarn com.github.luben.zstd.**

# Called by name from ksud through app_process, outside the Android app lifecycle.
# R8 cannot see these references: the class name travels as a string through
# ksud's argv, so without an explicit keep rule the entry points are renamed
# (or removed) in release builds and the web manager and dialer recovery break.
-keep class com.iki.xlh.webui.WebAppLabels { *; }
-keep class com.iki.xlh.ui.stealth.DialerRecoveryGuard { *; }
-keep class com.iki.xlh.ui.stealth.DialerRecoveryGuard$* { *; }
