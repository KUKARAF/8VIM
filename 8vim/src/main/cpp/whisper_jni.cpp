#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_inc_flide_vim8_ime_speech_WhisperEngine_openNative(JNIEnv *env, jobject, jstring path) {
    const char *cpath = env->GetStringUTFChars(path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(cpath, params);
    env->ReleaseStringUTFChars(path, cpath);
    return reinterpret_cast<jlong>(ctx);
}

// language: "" or "auto" for detection, otherwise an ISO code such as "en".
JNIEXPORT jstring JNICALL
Java_inc_flide_vim8_ime_speech_WhisperEngine_inferNative(
        JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring language, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");

    const char *clang = env->GetStringUTFChars(language, nullptr);
    std::string lang(clang);
    env->ReleaseStringUTFChars(language, clang);

    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.single_segment = true;
    params.no_context = true;
    params.suppress_blank = true;
    params.n_threads = threads;
    params.language = (lang.empty() || lang == "auto") ? nullptr : lang.c_str();
    params.detect_language = params.language == nullptr;

    std::string result;
    if (whisper_full(ctx, params, data, n) == 0) {
        int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; i++) result += whisper_full_get_segment_text(ctx, i);
    }
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_inc_flide_vim8_ime_speech_WhisperEngine_closeNative(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

}
