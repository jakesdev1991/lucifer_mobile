#ifndef LUCIFER_NATIVE_H
#define LUCIFER_NATIVE_H

#include <jni.h>
#include <string>

#ifdef __cplusplus
extern "C" {
#endif

// Lucifer Native Library API

// Initialize Lucifer native library
// Called from LuciferApplication.onCreate()
JNIEXPORT void JNICALL Java_com_lucifer_mobile_LuciferApplication_nativeInit(
    JNIEnv* env, jobject thiz, jobject context
);

// Shutdown Lucifer native library
JNIEXPORT void JNICALL Java_com_lucifer_mobile_LuciferApplication_nativeShutdown(
    JNIEnv* env, jobject thiz
);

// Kai Sandbox Bridge
JNIEXPORT jboolean JNICALL Java_com_lucifer_mobile_omni_1bridge_kai_1bridge_KaiSandboxBridge_nativeInitialize(
    JNIEnv* env, jobject thiz
);

JNIEXPORT jstring JNICALL Java_com_lucifer_mobile_omni_1bridge_kai_1bridge_KaiSandboxBridge_nativeExecute(
    JNIEnv* env, jobject thiz, jstring command, jobjectArray args
);

JNIEXPORT void JNICALL Java_com_lucifer_mobile_omni_1bridge_kai_1bridge_KaiSandboxBridge_nativeClose(
    JNIEnv* env, jobject thiz
);

// Wallet Operations
JNIEXPORT jstring JNICALL Java_com_lucifer_mobile_data_wallet_LuciferWallet_nativeGenerateWallet(
    JNIEnv* env, jobject thiz, jstring chain
);

JNIEXPORT jstring JNICALL Java_com_lucifer_mobile_data_wallet_LuciferWallet_nativeSignTransaction(
    JNIEnv* env, jobject thiz, jstring params
);

JNIEXPORT jstring JNICALL Java_com_lucifer_mobile_data_wallet_LuciferWallet_nativeGetBalance(
    JNIEnv* env, jobject thiz, jstring address, jstring token
);

// Browser Operations
JNIEXPORT jstring JNICALL Java_com_lucifer_mobile_data_browser_LuciferBrowser_nativeNavigate(
    JNIEnv* env, jobject thiz, jstring url
);

JNIEXPORT jstring JNICALL Java_com_lucifer_mobile_data_browser_LuciferBrowser_nativeGetContent(
    JNIEnv* env, jobject thiz
);

// LLM Operations
JNIEXPORT jstring JNICALL Java_com_lucifer_mobile_data_llm_LuciferLLM_nativeGenerate(
    JNIEnv* env, jobject thiz, jstring messages
);

JNIEXPORT jstring JNICALL Java_com_lucifer_mobile_data_llm_LuciferLLM_nativeListModels(
    JNIEnv* env, jobject thiz
);

// Sandbox Operations
JNIEXPORT jstring JNICALL Java_com_lucifer_mobile_data_sandbox_LuciferSandbox_nativeExecute(
    JNIEnv* env, jobject thiz, jstring command, jobjectArray args
);

JNIEXPORT jstring JNICALL Java_com_lucifer_mobile_data_sandbox_LuciferSandbox_nativeReadFile(
    JNIEnv* env, jobject thiz, jstring path
);

JNIEXPORT jboolean JNICALL Java_com_lucifer_mobile_data_sandbox_LuciferSandbox_nativeWriteFile(
    JNIEnv* env, jobject thiz, jstring path, jstring content
);

#ifdef __cplusplus
}
#endif

#endif // LUCIFER_NATIVE_H