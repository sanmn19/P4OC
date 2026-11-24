#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "WhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string jstringToUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

struct Session {
    whisper_context *ctx = nullptr;
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_blazelight_p4oc_core_voice_WhisperBridge_nativeInit(JNIEnv *env, jobject, jstring modelPath) {
    const std::string path = jstringToUtf8(env, modelPath);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file failed: %s", path.c_str());
        return 0;
    }
    auto *session = new Session();
    session->ctx = ctx;
    LOGI("whisper model loaded: %s", path.c_str());
    return reinterpret_cast<jlong>(session);
}

/**
 * Transcribes 16 kHz mono float samples in [-1, 1]. Returns the plain text, or an empty string.
 */
JNIEXPORT jstring JNICALL
Java_dev_blazelight_p4oc_core_voice_WhisperBridge_nativeTranscribe(
    JNIEnv *env,
    jobject,
    jlong handle,
    jfloatArray samples,
    jint sampleCount,
    jboolean translate
) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || session->ctx == nullptr || samples == nullptr || sampleCount <= 0) {
        return env->NewStringUTF("");
    }
    jfloat *raw = env->GetFloatArrayElements(samples, nullptr);
    if (raw == nullptr) return env->NewStringUTF("");
    std::vector<float> audio(raw, raw + sampleCount);
    env->ReleaseFloatArrayElements(samples, raw, JNI_ABORT);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.translate = translate == JNI_TRUE;
    params.single_segment = true;
    params.no_timestamps = true;
    params.suppress_blank = true;
    params.n_threads = 4;

    const int result = whisper_full(session->ctx, params, audio.data(), static_cast<int>(audio.size()));
    if (result != 0) {
        LOGE("whisper_full failed: %d", result);
        return env->NewStringUTF("");
    }

    std::string text;
    const int segments = whisper_full_n_segments(session->ctx);
    for (int i = 0; i < segments; i++) {
        const char *segment = whisper_full_get_segment_text(session->ctx, i);
        if (segment != nullptr) {
            if (!text.empty()) text.push_back(' ');
            text += segment;
        }
    }
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_dev_blazelight_p4oc_core_voice_WhisperBridge_nativeRelease(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    if (session->ctx != nullptr) whisper_free(session->ctx);
    delete session;
}

}  // extern "C"
