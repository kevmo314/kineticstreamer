# Keep readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable

# libkinetic.so binds `external fun`s by their mangled Java_* symbol names and
# calls back into Kotlin by name (e.g. PLICallback.onPLI via GetMethodID), so
# nothing in this package may be renamed or removed.
-keep class com.kevmo314.kineticstreamer.kinetic.** { *; }
