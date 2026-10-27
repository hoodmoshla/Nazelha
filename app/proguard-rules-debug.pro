# AndroidJUnitRunner uses Kotlin's LazyKt during instrumentation startup. The test runner is not
# part of the app's normal dependency graph, so R8 can otherwise remove this facade from the app APK.
-keep class kotlin.LazyKt { *; }
