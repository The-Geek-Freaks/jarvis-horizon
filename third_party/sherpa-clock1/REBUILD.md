# Native build provenance

The bundled AAR derives from upstream sherpa-onnx v1.13.8, commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`. Apply `keyword-frame-offset-clock1.patch` before building. Only the ARMv7 and ARM64 C-API shared libraries differ from the original AAR; JNI, ONNX Runtime, other ABIs and Java classes retain upstream bytes.

The public rebuild uses Android NDK 28.2.13676358, Release / `-O3 -DNDEBUG`, API 28, static libc++, C API enabled, and binary/JNI/TTS/diarization/websocket/Python/test targets disabled. It retains the original clock1 behavior. Map source, build, SDK and builder-home paths to neutral prefixes using both `-ffile-prefix-map=LOCAL=PUBLIC` and `-fdebug-prefix-map=LOCAL=PUBLIC` in C and C++ flags. After compilation run NDK `llvm-strip --strip-unneeded` on copies of the two shared libraries, then replace only their corresponding `jni/<abi>/libsherpa-onnx-c-api.so` ZIP members.

Neutral output prefixes are `/jarvis/sherpa-src`, `/jarvis/sherpa-build`, `/android-sdk` and `/builder`. Compiler prefix maps remove absolute local paths from `__FILE__` strings as well as debug metadata. Stripping alone is insufficient. The exact output hashes are recorded in the adjacent manifest; the original AAR SHA-256 is `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`.

The public binary is recompiled from source, never modified with string replacement. Validate native exports and recursively inspect packaged AAR/APK members before publication.
