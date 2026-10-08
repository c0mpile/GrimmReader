# Commons Compress refers to optional codecs (zstd-jni, XZ for Java, Brotli) that the app does not bundle;
# only its zip reader is used. Their absence is expected.
-dontwarn com.github.luben.zstd.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
