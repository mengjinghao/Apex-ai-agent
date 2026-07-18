// JNI entry point for rage-native.
//
// Mirrors the established pattern in ai-terminal/src/main/cpp/terminal_jni.cpp:
//   - JavaVM* gJvm global, saved in JNI_OnLoad
//   - jobject gCallbackObj global ref (Kotlin NativeCallbacks instance)
//   - jmethodID cached for onEvent / onLlmRequest / onSearchRequest
//   - AttachCurrentThread before any callback from a non-JVM thread
//
// All native methods are SYNCHRONOUS from Kotlin's perspective. The Kotlin
// side (rage-jni/RageNativeBridge) is responsible for dispatching off the
// main thread.
//
// JNI symbol naming (must EXACTLY match Kotlin `external fun`):
//   Package: com.apex.rage.nativelib
//   Class:   RageNative
//   Symbol:  Java_com_apex_rage_nativelib_RageNative_<methodName>
//
// Note: the package was renamed because the previous final package segment
// was the Java reserved word "native", which AGP rejects as a namespace.
// The internal C++ rage::native namespace used below is unrelated to the
// Kotlin package name and is unchanged.

#include <jni.h>

#include <android/log.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

#include "agent/AgentOrchestrator.h"
#include "cache/AggressiveCache.h"
#include "core/Blackboard.h"
#include "core/MetricsCollector.h"
#include "core/ParallelScheduler.h"
#include "core/RageTypes.h"
#include "core/TaskStateMachine.h"
#include "skill/SkillGraph.h"
#include "skill/SkillMatcher.h"
#include "util/JsonSerializer.h"

#define LOG_TAG "RageJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ============================================================
// Globals — callback registry
// ============================================================
JavaVM*  gJvm              = nullptr;
jobject  gCallbackObj      = nullptr;
jmethodID gOnEventMethod   = nullptr;  // void onEvent(String)
jmethodID gLlmInvokeMethod = nullptr;  // String onLlmRequest(String, String)
jmethodID gSearchMethod    = nullptr;  // String onSearchRequest(String)

// ============================================================
// Long-lived core singletons (created in nativeInit, destroyed in nativeDestroy)
// ============================================================
std::mutex                                             gCoreMutex;
std::unique_ptr<rage::native::ParallelScheduler>       gScheduler;
std::unique_ptr<rage::native::Blackboard>              gBlackboard;
std::unique_ptr<rage::native::MetricsCollector>        gMetrics;
std::unique_ptr<rage::native::TaskStateMachine>        gFsm;
std::unique_ptr<rage::native::AgentOrchestrator>       gOrchestrator;
std::unique_ptr<rage::native::SkillGraph>              gSkillGraph;
std::unique_ptr<rage::native::SkillMatcher>            gSkillMatcher;
std::unique_ptr<rage::native::AggressiveCache>         gCache;

// ============================================================
// String helpers
// ============================================================

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return std::string();
    const char* cstr = env->GetStringUTFChars(s, nullptr);
    if (!cstr) return std::string();
    std::string out(cstr);
    env->ReleaseStringUTFChars(s, cstr);
    return out;
}

jstring toJstr(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

// ============================================================
// AttachCurrentThread helper for callbacks from C++ worker threads
// ============================================================

JNIEnv* attachCurrentThread(const char* tag) {
    if (!gJvm) return nullptr;
    JNIEnv* env = nullptr;
    jint rc = gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (rc == JNI_OK) return env;
    if (rc != JNI_EDETACHED) {
        LOGW("%s: GetEnv returned %d", tag, rc);
        return nullptr;
    }
    JavaVMAttachArgs args;
    args.version = JNI_VERSION_1_6;
    args.name    = const_cast<char*>("RageNativeCb");
    args.group   = nullptr;
    rc = gJvm->AttachCurrentThread(&env, &args);
    if (rc != JNI_OK) {
        LOGE("%s: AttachCurrentThread failed rc=%d", tag, rc);
        return nullptr;
    }
    return env;
}

bool isCurrentThreadAttached() {
    if (!gJvm) return false;
    JNIEnv* env = nullptr;
    return gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK;
}

void detachIfAttached() {
    if (!gJvm) return;
    JNIEnv* env = nullptr;
    if (gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        gJvm->DetachCurrentThread();
    }
}

// ============================================================
// JNI callback: emit an event to Kotlin
// (Calls NativeCallbacks.onEvent(eventJson: String))
// ============================================================
void emitEventToKotlin(const rage::native::NativeEvent& ev) {
    if (!gCallbackObj || !gOnEventMethod || !gJvm) return;
    JNIEnv* env = attachCurrentThread("emitEvent");
    if (!env) return;
    bool wasAttached = isCurrentThreadAttached();
    std::string json = rage::native::serializeEvent(ev);
    jstring jJson = toJstr(env, json);
    env->CallVoidMethod(gCallbackObj, gOnEventMethod, jJson);
    env->DeleteLocalRef(jJson);
    if (env->ExceptionCheck()) {
        LOGW("onEvent threw — clearing exception");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (!wasAttached) detachIfAttached();
}

// ============================================================
// JNI callback: invoke LLM
// (Calls NativeCallbacks.onLlmRequest(prompt, systemPrompt): String)
// Returns the LLM response text, or empty string on failure.
// ============================================================
std::string callLlmOnKotlin(const std::string& prompt, const std::string& systemPrompt) {
    if (!gCallbackObj || !gLlmInvokeMethod || !gJvm) {
        LOGW("callLlmOnKotlin: callback not initialized");
        return std::string();
    }
    JNIEnv* env = attachCurrentThread("callLlm");
    if (!env) return std::string();
    bool wasAttached = isCurrentThreadAttached();

    jstring jPrompt       = toJstr(env, prompt);
    jstring jSystemPrompt = toJstr(env, systemPrompt);
    jobject jResp         = env->CallObjectMethod(gCallbackObj, gLlmInvokeMethod,
                                                  jPrompt, jSystemPrompt);
    env->DeleteLocalRef(jPrompt);
    env->DeleteLocalRef(jSystemPrompt);

    std::string response;
    if (env->ExceptionCheck()) {
        LOGW("onLlmRequest threw — clearing exception");
        env->ExceptionDescribe();
        env->ExceptionClear();
    } else if (jResp != nullptr) {
        response = jstr(env, static_cast<jstring>(jResp));
        env->DeleteLocalRef(jResp);
    }
    if (!wasAttached) detachIfAttached();
    return response;
}

// ============================================================
// JNI callback: search
// (Calls NativeCallbacks.onSearchRequest(query): String)
// ============================================================
std::string callSearchOnKotlin(const std::string& query) {
    if (!gCallbackObj || !gSearchMethod || !gJvm) {
        LOGW("callSearchOnKotlin: callback not initialized");
        return std::string();
    }
    JNIEnv* env = attachCurrentThread("callSearch");
    if (!env) return std::string();
    bool wasAttached = isCurrentThreadAttached();

    jstring jQuery = toJstr(env, query);
    jobject jResp  = env->CallObjectMethod(gCallbackObj, gSearchMethod, jQuery);
    env->DeleteLocalRef(jQuery);

    std::string response;
    if (env->ExceptionCheck()) {
        LOGW("onSearchRequest threw — clearing exception");
        env->ExceptionDescribe();
        env->ExceptionClear();
    } else if (jResp != nullptr) {
        response = jstr(env, static_cast<jstring>(jResp));
        env->DeleteLocalRef(jResp);
    }
    if (!wasAttached) detachIfAttached();
    return response;
}

// ============================================================
// Build the AgentInvoker that routes NativeEvent -> JNI callbacks
// ============================================================
rage::native::AgentInvoker buildInvoker() {
    return [](const rage::native::NativeEvent& req) -> rage::native::NativeEvent {
        rage::native::NativeEvent resp = req;
        switch (req.type) {
            case rage::native::EventType::LLM_REQUEST:
                resp.message = callLlmOnKotlin(req.llmPrompt, req.llmSystemPrompt);
                // Also broadcast the event (non-fatal if it fails).
                emitEventToKotlin(req);
                break;
            case rage::native::EventType::SEARCH_REQUEST:
                resp.message = callSearchOnKotlin(req.searchQuery);
                emitEventToKotlin(req);
                break;
            default:
                // Observation-only event (TASK_STARTED, AGENT_STEP, etc.)
                emitEventToKotlin(req);
                break;
        }
        return resp;
    };
}

} // namespace (anonymous)

// ============================================================
// JNI exported functions
// ============================================================

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    gJvm = vm;
    LOGI("JNI_OnLoad: JavaVM saved");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_apex_rage_nativelib_RageNative_nativeInit(JNIEnv* env, jobject /*thiz*/, jobject callback) {
    if (gCallbackObj != nullptr) {
        env->DeleteGlobalRef(gCallbackObj);
        gCallbackObj = nullptr;
    }
    if (callback != nullptr) {
        gCallbackObj = env->NewGlobalRef(callback);
        jclass cbCls = env->GetObjectClass(callback);
        if (cbCls != nullptr) {
            gOnEventMethod = env->GetMethodID(cbCls, "onEvent",
                                              "(Ljava/lang/String;)V");
            gLlmInvokeMethod = env->GetMethodID(cbCls, "onLlmRequest",
                                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
            gSearchMethod = env->GetMethodID(cbCls, "onSearchRequest",
                                             "(Ljava/lang/String;)Ljava/lang/String;");
            env->DeleteLocalRef(cbCls);
        }
    }
    if (!gOnEventMethod || !gLlmInvokeMethod || !gSearchMethod) {
        LOGE("nativeInit: failed to look up callback methods "
             "(onEvent=%p onLlmRequest=%p onSearchRequest=%p)",
             gOnEventMethod, gLlmInvokeMethod, gSearchMethod);
        return JNI_FALSE;
    }

    // Create core singletons (idempotent — re-init replaces previous).
    std::lock_guard<std::mutex> lk(gCoreMutex);
    gScheduler    = std::make_unique<rage::native::ParallelScheduler>(4);
    gBlackboard   = std::make_unique<rage::native::Blackboard>();
    gMetrics      = std::make_unique<rage::native::MetricsCollector>();
    gFsm          = std::make_unique<rage::native::TaskStateMachine>();
    gSkillGraph   = std::make_unique<rage::native::SkillGraph>();
    gSkillMatcher = std::make_unique<rage::native::SkillMatcher>();
    gCache        = std::make_unique<rage::native::AggressiveCache>(256, 5 * 60 * 1000);
    gOrchestrator = std::make_unique<rage::native::AgentOrchestrator>(
        *gScheduler, *gBlackboard, *gMetrics, *gFsm);
    LOGI("nativeInit: core singletons created");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_apex_rage_nativelib_RageNative_nativeStartTask(JNIEnv* env,
                                                     jobject /*thiz*/,
                                                     jstring taskJson,
                                                     jstring configJson) {
    if (!taskJson || !configJson) {
        return toJstr(env, rage::native::serializeResult(
            rage::native::NativeExecutionResult{}));
    }
    std::string taskStr   = jstr(env, taskJson);
    std::string configStr = jstr(env, configJson);

    rage::native::NativeTask        task   = rage::native::deserializeTask(taskStr);
    rage::native::NativeRageConfig  config = rage::native::deserializeConfig(configStr);

    if (task.id.empty()) {
        // Defensive: synthesize a task ID if Kotlin didn't supply one.
        task.id = "rage-" + std::to_string(
            std::chrono::steady_clock::now().time_since_epoch().count());
    }
    if (task.preset.empty()) task.preset = "BALANCED";

    // (Re)create the scheduler with the requested concurrency.
    {
        std::lock_guard<std::mutex> lk(gCoreMutex);
        if (gScheduler) gScheduler->shutdown();
        gScheduler    = std::make_unique<rage::native::ParallelScheduler>(
            config.maxConcurrency > 0 ? config.maxConcurrency : 4);
        gOrchestrator = std::make_unique<rage::native::AgentOrchestrator>(
            *gScheduler, *gBlackboard, *gMetrics, *gFsm);
    }

    rage::native::AgentInvoker invoker = buildInvoker();
    rage::native::NativeExecutionResult result;
    {
        std::lock_guard<std::mutex> lk(gCoreMutex);
        if (!gOrchestrator) {
            result.success = false;
            result.errorMessage = "nativeInit not called";
        } else {
            result = gOrchestrator->executeTask(task, config, invoker);
        }
    }
    result.taskId = task.id;
    return toJstr(env, rage::native::serializeResult(result));
}

JNIEXPORT jboolean JNICALL
Java_com_apex_rage_nativelib_RageNative_nativeCancelTask(JNIEnv* env,
                                                      jobject /*thiz*/,
                                                      jstring taskId) {
    if (!taskId) return JNI_FALSE;
    std::string id = jstr(env, taskId);
    std::lock_guard<std::mutex> lk(gCoreMutex);
    if (!gOrchestrator) return JNI_FALSE;
    gOrchestrator->requestCancel(id);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_apex_rage_nativelib_RageNative_nativeGetMetrics(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(gCoreMutex);
    rage::native::NativeMetrics m;
    if (gMetrics) m = gMetrics->snapshot();
    return toJstr(env, rage::native::serializeMetrics(m));
}

JNIEXPORT void JNICALL
Java_com_apex_rage_nativelib_RageNative_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(gCoreMutex);
    gOrchestrator.reset();
    gScheduler.reset();
    gBlackboard.reset();
    gMetrics.reset();
    gFsm.reset();
    gSkillGraph.reset();
    gSkillMatcher.reset();
    gCache.reset();

    if (gCallbackObj != nullptr && gJvm != nullptr) {
        JNIEnv* env = nullptr;
        if (gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
            env->DeleteGlobalRef(gCallbackObj);
        }
        gCallbackObj = nullptr;
    }
    gOnEventMethod   = nullptr;
    gLlmInvokeMethod = nullptr;
    gSearchMethod    = nullptr;
    LOGI("nativeDestroy: core singletons destroyed");
}

} // extern "C"
