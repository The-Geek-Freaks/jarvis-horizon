#include <jni.h>
#include <dlfcn.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>

#include "sherpa-onnx/c-api/c-api.h"

namespace {

struct SherpaApi {
  void* library = nullptr;
  std::string error;
  decltype(&SherpaOnnxCreateKeywordSpotter) create_spotter = nullptr;
  decltype(&SherpaOnnxDestroyKeywordSpotter) destroy_spotter = nullptr;
  decltype(&SherpaOnnxCreateKeywordStream) create_stream = nullptr;
  decltype(&SherpaOnnxDestroyOnlineStream) destroy_stream = nullptr;
  decltype(&SherpaOnnxOnlineStreamAcceptWaveform) accept_waveform = nullptr;
  decltype(&SherpaOnnxIsKeywordStreamReady) is_ready = nullptr;
  decltype(&SherpaOnnxDecodeKeywordStream) decode = nullptr;
  decltype(&SherpaOnnxGetKeywordResult) get_result = nullptr;
  decltype(&SherpaOnnxDestroyKeywordResult) destroy_result = nullptr;
};

SherpaApi g_api;
std::once_flag g_api_once;

void LoadApi() {
  g_api.library = dlopen("libsherpa-onnx-c-api.so", RTLD_NOW | RTLD_LOCAL);
  if (!g_api.library) {
    const char* dl_error = dlerror();
    g_api.error = dl_error ? dl_error : "dlopen(libsherpa-onnx-c-api.so) failed";
    return;
  }
#define LOAD_SYMBOL(field, symbol) \
  g_api.field = reinterpret_cast<decltype(g_api.field)>(dlsym(g_api.library, #symbol)); \
  if (!g_api.field) { g_api.error = std::string("missing C API symbol: ") + #symbol; return; }
  LOAD_SYMBOL(create_spotter, SherpaOnnxCreateKeywordSpotter)
  LOAD_SYMBOL(destroy_spotter, SherpaOnnxDestroyKeywordSpotter)
  LOAD_SYMBOL(create_stream, SherpaOnnxCreateKeywordStream)
  LOAD_SYMBOL(destroy_stream, SherpaOnnxDestroyOnlineStream)
  LOAD_SYMBOL(accept_waveform, SherpaOnnxOnlineStreamAcceptWaveform)
  LOAD_SYMBOL(is_ready, SherpaOnnxIsKeywordStreamReady)
  LOAD_SYMBOL(decode, SherpaOnnxDecodeKeywordStream)
  LOAD_SYMBOL(get_result, SherpaOnnxGetKeywordResult)
  LOAD_SYMBOL(destroy_result, SherpaOnnxDestroyKeywordResult)
#undef LOAD_SYMBOL
}

bool EnsureApi(std::string* error) {
  std::call_once(g_api_once, LoadApi);
  if (!g_api.error.empty()) { *error = g_api.error; return false; }
  return true;
}

void ThrowIllegalState(JNIEnv* env, const std::string& message) {
  if (env->ExceptionCheck()) env->ExceptionClear();
  jclass type = env->FindClass("java/lang/IllegalStateException");
  if (type) {
    env->ThrowNew(type, message.c_str());
    env->DeleteLocalRef(type);
  }
}

class UtfChars {
 public:
  UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
    chars_ = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
  }
  ~UtfChars() { if (chars_) env_->ReleaseStringUTFChars(value_, chars_); }
  const char* get() const { return chars_; }
 private:
  JNIEnv* env_;
  jstring value_;
  const char* chars_ = nullptr;
};

struct Spotter {
  std::mutex mutex;
  const SherpaOnnxKeywordSpotter* handle = nullptr;
  int stream_count = 0;
  bool release_requested = false;
};

struct Stream {
  Spotter* owner = nullptr;
  const SherpaOnnxOnlineStream* handle = nullptr;
  std::mutex mutex;
};

std::string Path(const char* directory, const char* name) {
  return std::string(directory) + "/" + name;
}

jlong CreateSpotter(JNIEnv* env, jstring directory) {
  UtfChars dir(env, directory);
  if (!dir.get()) { ThrowIllegalState(env, "Wake model directory unavailable"); return 0; }
  std::string error;
  if (!EnsureApi(&error)) { ThrowIllegalState(env, error); return 0; }

  const std::string encoder = Path(dir.get(), "encoder.onnx");
  const std::string decoder = Path(dir.get(), "decoder.onnx");
  const std::string joiner = Path(dir.get(), "joiner.onnx");
  const std::string tokens = Path(dir.get(), "tokens.txt");
  const std::string keywords = Path(dir.get(), "keywords.txt");
  SherpaOnnxKeywordSpotterConfig config{};
  config.model_config.transducer.encoder = encoder.c_str();
  config.model_config.transducer.decoder = decoder.c_str();
  config.model_config.transducer.joiner = joiner.c_str();
  config.model_config.tokens = tokens.c_str();
  config.model_config.model_type = "zipformer2";
  config.model_config.provider = "cpu";
  config.model_config.num_threads = 1;
  config.max_active_paths = 4;
  config.num_trailing_blanks = 1;
  config.keywords_score = 1.0f;
  config.keywords_threshold = 0.25f;
  config.keywords_file = keywords.c_str();

  const SherpaOnnxKeywordSpotter* handle = g_api.create_spotter(&config);
  if (!handle) { ThrowIllegalState(env, "Sherpa C API rejected the wake model assets"); return 0; }
  auto* spotter = new Spotter();
  spotter->handle = handle;
  return reinterpret_cast<jlong>(spotter);
}

jlong CreateStream(JNIEnv* env, jlong spotter_ptr) {
  auto* spotter = reinterpret_cast<Spotter*>(spotter_ptr);
  if (!spotter) { ThrowIllegalState(env, "Wake keyword spotter is released"); return 0; }
  std::lock_guard<std::mutex> lock(spotter->mutex);
  if (spotter->release_requested || !spotter->handle) {
    ThrowIllegalState(env, "Wake keyword spotter is released"); return 0;
  }
  const SherpaOnnxOnlineStream* handle = g_api.create_stream(spotter->handle);
  if (!handle) { ThrowIllegalState(env, "Sherpa C API could not create a wake stream"); return 0; }
  auto* stream = new Stream();
  stream->owner = spotter;
  stream->handle = handle;
  ++spotter->stream_count;
  return reinterpret_cast<jlong>(stream);
}

bool IsDestroyableLocked(const Spotter* spotter) {
  return spotter->release_requested && spotter->stream_count == 0 && spotter->handle;
}

void DestroySpotterNow(Spotter* spotter) {
  g_api.destroy_spotter(spotter->handle);
  delete spotter;
}

void DestroyStream(jlong stream_ptr) {
  auto* stream = reinterpret_cast<Stream*>(stream_ptr);
  if (!stream) return;
  {
    std::lock_guard<std::mutex> stream_lock(stream->mutex);
    if (stream->handle) g_api.destroy_stream(stream->handle);
    stream->handle = nullptr;
  }
  Spotter* owner = stream->owner;
  delete stream;
  bool destroy_now = false;
  {
    std::lock_guard<std::mutex> owner_lock(owner->mutex);
    --owner->stream_count;
    destroy_now = IsDestroyableLocked(owner);
  }
  if (destroy_now) DestroySpotterNow(owner);
}

jlong AcceptWaveform(JNIEnv* env, jlong stream_ptr, jfloatArray samples, jint sample_count, jint sample_rate) {
  auto* stream = reinterpret_cast<Stream*>(stream_ptr);
  if (!stream || !samples || sample_rate != 16000) {
    ThrowIllegalState(env, "Wake stream requires 16 kHz PCM samples"); return -1;
  }
  std::lock_guard<std::mutex> lock(stream->mutex);
  if (!stream->handle) { ThrowIllegalState(env, "Wake stream is released"); return -1; }
  const jsize array_count = env->GetArrayLength(samples);
  if (sample_count < 0 || sample_count > array_count) {
    ThrowIllegalState(env, "Wake sample count is outside its buffer"); return -1;
  }
  jfloat* pcm = env->GetFloatArrayElements(samples, nullptr);
  if (!pcm) { if (env->ExceptionCheck()) env->ExceptionClear(); ThrowIllegalState(env, "Unable to read wake PCM"); return -1; }
  g_api.accept_waveform(stream->handle, sample_rate, pcm, sample_count);
  env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
  while (g_api.is_ready(stream->owner->handle, stream->handle)) {
    g_api.decode(stream->owner->handle, stream->handle);
    const SherpaOnnxKeywordResult* result = g_api.get_result(stream->owner->handle, stream->handle);
    if (result && result->keyword && result->keyword[0] && result->count > 0 && result->timestamps) {
      const double start_seconds = static_cast<double>(result->start_time) + result->timestamps[0];
      g_api.destroy_result(result);
      if (std::isfinite(start_seconds) && start_seconds >= 0.0) {
        return static_cast<jlong>(llround(start_seconds * 16000.0));
      }
      return -1;
    }
    if (result) g_api.destroy_result(result);
  }
  return -1;
}

void ResetStream(JNIEnv* env, jlong stream_ptr) {
  auto* stream = reinterpret_cast<Stream*>(stream_ptr);
  if (!stream) { ThrowIllegalState(env, "Wake stream is released"); return; }
  std::lock_guard<std::mutex> stream_lock(stream->mutex);
  std::lock_guard<std::mutex> owner_lock(stream->owner->mutex);
  if (!stream->handle || stream->owner->release_requested || !stream->owner->handle) {
    ThrowIllegalState(env, "Wake stream is released");
    return;
  }
  // A fresh stream intentionally resets the physical frame clock; C API reset preserves it.
  g_api.destroy_stream(stream->handle);
  stream->handle = g_api.create_stream(stream->owner->handle);
  if (!stream->handle) ThrowIllegalState(env, "Sherpa C API could not reset the wake stream");
}

void DestroySpotter(jlong spotter_ptr) {
  auto* spotter = reinterpret_cast<Spotter*>(spotter_ptr);
  if (!spotter) return;
  bool destroy_now = false;
  {
    std::lock_guard<std::mutex> lock(spotter->mutex);
    if (spotter->release_requested) return;
    spotter->release_requested = true;
    destroy_now = IsDestroyableLocked(spotter);
  }
  if (destroy_now) DestroySpotterNow(spotter);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ai_openclaw_app_voice_SherpaWakeKeywordSpotter_nativeCreate(JNIEnv* env, jobject, jstring directory) {
  return CreateSpotter(env, directory);
}
extern "C" JNIEXPORT void JNICALL
Java_ai_openclaw_app_voice_SherpaWakeKeywordSpotter_nativeDestroy(JNIEnv*, jobject, jlong spotter) { DestroySpotter(spotter); }
extern "C" JNIEXPORT jlong JNICALL
Java_ai_openclaw_app_voice_SherpaWakeKeywordSpotter_nativeCreateStream(JNIEnv* env, jobject, jlong spotter) { return CreateStream(env, spotter); }
extern "C" JNIEXPORT void JNICALL
Java_ai_openclaw_app_voice_SherpaWakeKeywordSpotter_nativeDestroyStream(JNIEnv*, jobject, jlong stream) { DestroyStream(stream); }
extern "C" JNIEXPORT jlong JNICALL
Java_ai_openclaw_app_voice_SherpaWakeKeywordSpotter_nativeAcceptWaveform(JNIEnv* env, jobject, jlong stream, jfloatArray samples, jint count, jint rate) { return AcceptWaveform(env, stream, samples, count, rate); }
extern "C" JNIEXPORT void JNICALL
Java_ai_openclaw_app_voice_SherpaWakeKeywordSpotter_nativeResetStream(JNIEnv* env, jobject, jlong stream) { ResetStream(env, stream); }
