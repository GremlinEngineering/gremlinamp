# Keep the app entry points and JNI method names. R8 removes unused dependency
# classes, including the AndroidX address detector that references WebView.
-keep class ru.oopssre.gremlinamp.** { *; }
