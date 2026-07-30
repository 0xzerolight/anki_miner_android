# Fault tokens (diagnostics/FaultDiagnostics.kt) report an exception's class name and topmost frame,
# and they are the only cause a released build can send back for a failure that is otherwise recorded
# only in the app-private journal. Minified, they render as "t0 @ a.W:342" and say nothing. Keeping
# just the names of Throwables restores them; the classes themselves stay shrinkable and their
# members minifiable, so the cost is a handful of retained strings.
-keepnames class * extends java.lang.Throwable

# Python calls this Java surface by method name through Chaquopy. Keep both the interface and every
# concrete per-run callback object so R8 cannot rename, merge, or remove those entry points.
-keep interface com.ankiminer.android.engine.EngineCallbacks { *; }
-keep class * implements com.ankiminer.android.engine.EngineCallbacks { public *; }
