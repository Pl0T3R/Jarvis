# sherpa-onnx: the native library reads config fields and creates result objects by their names.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# The Anthropic SDK and Jackson bring their own rules (META-INF/proguard in their jars).

# Optional codecs of commons-compress that JARVIS never uses (only bzip2 + tar).
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
