# AndroidJUnitRunner uses Kotlin's LazyKt during instrumentation startup. The test runner is not
# part of the app's normal dependency graph, so R8 can otherwise remove this facade from the app APK.
-keep class kotlin.LazyKt { *; }

# Compose's IdlingResourceRegistry calls CompletableJob.complete() through the target APK. Preserve
# the coroutine runtime surface for UI tests in the minified Debug target; release rules stay unchanged.
-keep interface kotlinx.coroutines.CompletableJob { *; }
-keep class kotlinx.coroutines.** { *; }
