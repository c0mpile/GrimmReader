# zstd-jni's native library finds its classes, fields and methods by their original names (JNI), and
# loads itself through util.Native. R8 renaming them kills the process while a .tar.zst is unpacked.
-keep class com.github.luben.zstd.** { *; }
