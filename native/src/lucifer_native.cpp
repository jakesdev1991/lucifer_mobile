#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "LuciferNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static jobject g_app_context = nullptr;

// Lucifer Native API
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("Lucifer Native library loaded");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lucifer_mobile_LuciferApplication_nativeInit(JNIEnv* env, jobject thiz, jobject context) {
    g_app_context = env->NewGlobalRef(context);
    LOGI("Lucifer Native initialized");
    
    // The native library currently provides lifecycle logging only. Every former JNI
    // placeholder (Kai socket bridge, wallet/browser/llm/sandbox stubs) was deleted:
    // the Kotlin side declares no external funs for them, so they were dead code.
}

extern "C" JNIEXPORT void JNICALL
Java_com_lucifer_mobile_LuciferApplication_nativeShutdown(JNIEnv* env, jobject thiz) {
    if (g_app_context) {
        env->DeleteGlobalRef(g_app_context);
        g_app_context = nullptr;
    }
    LOGI("Lucifer Native shutdown");
}

