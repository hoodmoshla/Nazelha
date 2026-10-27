# Compose instrumentation runs against the minified Debug target. Its test-only runtime references
# Kotlin stdlib and coroutine APIs that the app itself does not reach, so R8 would otherwise strip them.
# Keep them only in Debug; the Release build retains its normal shrinking rules.
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }
