# Python calls this Java surface by method name through Chaquopy. Keep both the interface and every
# concrete per-run callback object so R8 cannot rename, merge, or remove those entry points.
-keep interface com.ankiminer.android.engine.EngineCallbacks { *; }
-keep class * implements com.ankiminer.android.engine.EngineCallbacks { public *; }
