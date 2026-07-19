#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <pty.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <signal.h>
#include <errno.h>
#include <cstring>
#include <vector>
#include <array>
#include <sstream>
#include <unordered_map>
#include <mutex>
#include "terminal_session.h"
#include "process_manager.h"
#include "performance_monitor.h"
#include "shell_extension.h"
#include "flashing_helper.h"
#include "root_session.h"

#define LOG_TAG "TerminalJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define JNI_CLASS_NAME "com/ai/assistance/aiterminal/terminal/TerminalJni"
#define ROOT_JNI_CLASS_NAME "com/ai/assistance/aiterminal/terminal/RootTerminalManager"

// Security (A-2/A-3): Per-session PTY registry. Replaces the previous
// single-global `g_shellPid` / `g_masterFd` pair that leaked a shell + fd
// every time a second root session was created. See root_session.h.
std::unordered_map<std::string, RootSession> g_rootSessions;
std::mutex g_rootSessionsMutex;

// Security (A-4): SIGCHLD reaper. Without a SIGCHLD handler, every forked
// shell becomes a zombie when it exits (the parent never waitpid()s it).
// This handler non-blockingly reaps ALL children that change state.
//
// RACE NOTE: `nativeCloseSession` calls `waitpid(pid, nullptr, 0)` AFTER
// `kill(pid, SIGKILL)`. If the SIGCHLD handler reaps the child first,
// `waitpid(pid, ...)` returns -1 with errno=ECHILD -- this is fine and we
// explicitly ignore that error. The handler is async-signal-safe (only
// uses `waitpid` and saves/restores errno).
static void sigchld_handler(int sig) {
    (void)sig;
    int saved_errno = errno;
    while (waitpid(-1, nullptr, WNOHANG) > 0) {
        // Reap all pending zombies.
    }
    errno = saved_errno;
}

// JNI 全局环境（用于回调Kotlin）
static JavaVM* gJvm = nullptr;
static jobject gJniCallbackObj = nullptr;
static jmethodID gPostEventMethod = nullptr;

// Native事件回调 -> Kotlin层
void postTerminalEvent(TerminalEventType type, const std::string& data, int code) {
    JNIEnv* env = nullptr;
    bool isAttached = false;

    // 获取JNI环境
    if (gJvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (gJvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("Failed to attach thread");
            return;
        }
        isAttached = true;
    }

    // 转换事件类型为字符串
    const char* typeStr;
    switch (type) {
        case TerminalEventType::COMMAND_OUTPUT: typeStr = "COMMAND_OUTPUT"; break;
        case TerminalEventType::DIRECTORY_CHANGED: typeStr = "DIRECTORY_CHANGED"; break;
        case TerminalEventType::SESSION_STATE_CHANGED: typeStr = "SESSION_STATE_CHANGED"; break;
        case TerminalEventType::COMMAND_FINISHED: typeStr = "COMMAND_FINISHED"; break;
        case TerminalEventType::ERROR_OCCURRED: typeStr = "ERROR_OCCURRED"; break;
        default: typeStr = "UNKNOWN";
    }

    // 调用Kotlin层回调方法
    if (gJniCallbackObj != nullptr && gPostEventMethod != nullptr) {
        jstring jType = env->NewStringUTF(typeStr);
        jstring jData = env->NewStringUTF(data.c_str());
        env->CallVoidMethod(gJniCallbackObj, gPostEventMethod, jType, jData, code);
        env->DeleteLocalRef(jType);
        env->DeleteLocalRef(jData);

        // 检查异常
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    // 分离线程
    if (isAttached) {
        gJvm->DetachCurrentThread();
    }
}

// ========== JNI 方法实现 ==========

/**
 * 初始化JNI回调
 */
extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_initJniCallback(
        JNIEnv *env,
        jobject /* this */,
        jobject callbackObj) {
    // A-15: defensive null check on callbackObj before dereferencing.
    if (callbackObj == nullptr) {
        LOGE("initJniCallback: callbackObj is null");
        return;
    }
    // 保存全局回调对象
    if (gJniCallbackObj != nullptr) {
        env->DeleteGlobalRef(gJniCallbackObj);
    }
    gJniCallbackObj = env->NewGlobalRef(callbackObj);

    // 获取回调方法ID
    jclass callbackCls = env->GetObjectClass(callbackObj);
    if (callbackCls == nullptr) {
        LOGE("initJniCallback: GetObjectClass returned null");
        return;
    }
    gPostEventMethod = env->GetMethodID(callbackCls, "postEvent", "(Ljava/lang/String;Ljava/lang/String;I)V");
    env->DeleteLocalRef(callbackCls);

    LOGD("JNI callback initialized");
}

/**
 * 创建会话（对标多会话管理）
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_createSession(
        JNIEnv *env,
        jobject /* this */,
        jstring sessionId) {
    // A-15: GetStringUTFChars may return nullptr on OOM -> SIGSEGV. Bail safely.
    const char* id = env->GetStringUTFChars(sessionId, nullptr);
    if (id == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return JNI_FALSE;
    }
    auto* pool = &TerminalSessionPool::getInstance();

    // 创建会话，绑定事件回调
    auto* session = pool->createSession(id, postTerminalEvent);
    env->ReleaseStringUTFChars(sessionId, id);

    LOGD("Session created: %s", id);
    return (session != nullptr) ? JNI_TRUE : JNI_FALSE;
}

/**
 * 启动会话（支持多Shell）
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_startSession(
        JNIEnv *env,
        jobject /* this */,
        jstring sessionId,
        jstring shellType) {
    // A-15: null-check both strings.
    const char* id = env->GetStringUTFChars(sessionId, nullptr);
    if (id == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return JNI_FALSE;
    }
    const char* shell = env->GetStringUTFChars(shellType, nullptr);
    if (shell == nullptr) {
        env->ReleaseStringUTFChars(sessionId, id);
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return JNI_FALSE;
    }

    auto* pool = &TerminalSessionPool::getInstance();
    auto* session = pool->getSession(id);
    bool success = (session != nullptr) && session->start(shell);

    env->ReleaseStringUTFChars(sessionId, id);
    env->ReleaseStringUTFChars(shellType, shell);
    return success ? JNI_TRUE : JNI_FALSE;
}

/**
 * 执行命令（对标命令执行能力）
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_executeCommand(
        JNIEnv *env,
        jobject /* this */,
        jstring sessionId,
        jstring command) {
    // A-15: null-check both strings.
    const char* id = env->GetStringUTFChars(sessionId, nullptr);
    if (id == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return JNI_FALSE;
    }
    const char* cmd = env->GetStringUTFChars(command, nullptr);
    if (cmd == nullptr) {
        env->ReleaseStringUTFChars(sessionId, id);
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return JNI_FALSE;
    }

    auto* pool = &TerminalSessionPool::getInstance();
    auto* session = pool->getSession(id);
    bool success = (session != nullptr) && session->executeCommand(cmd);

    env->ReleaseStringUTFChars(sessionId, id);
    env->ReleaseStringUTFChars(command, cmd);
    return success ? JNI_TRUE : JNI_FALSE;
}

/**
 * 切换会话
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_switchSession(
        JNIEnv *env,
        jobject /* this */,
        jstring sessionId) {
    const char* id = env->GetStringUTFChars(sessionId, nullptr);
    if (id == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return JNI_FALSE;
    }
    auto* pool = &TerminalSessionPool::getInstance();
    bool success = pool->switchSession(id);
    env->ReleaseStringUTFChars(sessionId, id);
    return success ? JNI_TRUE : JNI_FALSE;
}

/**
 * 切换工作目录（对标状态管理）
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_changeDirectory(
        JNIEnv *env,
        jobject /* this */,
        jstring sessionId,
        jstring path) {
    // A-15: null-check both strings.
    const char* id = env->GetStringUTFChars(sessionId, nullptr);
    if (id == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return JNI_FALSE;
    }
    const char* dir = env->GetStringUTFChars(path, nullptr);
    if (dir == nullptr) {
        env->ReleaseStringUTFChars(sessionId, id);
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return JNI_FALSE;
    }

    auto* pool = &TerminalSessionPool::getInstance();
    auto* session = pool->getSession(id);
    bool success = (session != nullptr) && session->changeDirectory(dir);

    env->ReleaseStringUTFChars(sessionId, id);
    env->ReleaseStringUTFChars(path, dir);
    return success ? JNI_TRUE : JNI_FALSE;
}

/**
 * 获取当前工作目录
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_getCurrentDirectory(
        JNIEnv *env,
        jobject /* this */,
        jstring sessionId) {
    const char* id = env->GetStringUTFChars(sessionId, nullptr);
    if (id == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return nullptr;
    }
    auto* pool = &TerminalSessionPool::getInstance();
    auto* session = pool->getSession(id);
    std::string cwd = session != nullptr ? session->cwd : "";

    env->ReleaseStringUTFChars(sessionId, id);
    return env->NewStringUTF(cwd.c_str());
}

/**
 * 挂起会话
 */
extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_suspendSession(
        JNIEnv *env,
        jobject /* this */,
        jstring sessionId) {
    const char* id = env->GetStringUTFChars(sessionId, nullptr);
    if (id == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return;
    }
    auto* pool = &TerminalSessionPool::getInstance();
    auto* session = pool->getSession(id);
    if (session != nullptr) {
        session->suspend();
    }
    env->ReleaseStringUTFChars(sessionId, id);
}

/**
 * 恢复会话
 */
extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_resumeSession(
        JNIEnv *env,
        jobject /* this */,
        jstring sessionId) {
    const char* id = env->GetStringUTFChars(sessionId, nullptr);
    if (id == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return;
    }
    auto* pool = &TerminalSessionPool::getInstance();
    auto* session = pool->getSession(id);
    if (session != nullptr) {
        session->resume();
    }
    env->ReleaseStringUTFChars(sessionId, id);
}

/**
 * 关闭会话
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_closeSession(
        JNIEnv *env,
        jobject /* this */,
        jstring sessionId) {
    const char* id = env->GetStringUTFChars(sessionId, nullptr);
    if (id == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return JNI_FALSE;
    }
    auto* pool = &TerminalSessionPool::getInstance();
    bool success = pool->closeSession(id);
    env->ReleaseStringUTFChars(sessionId, id);
    return success ? JNI_TRUE : JNI_FALSE;
}

/**
 * 关闭所有会话
 */
extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_closeAllSessions(
        JNIEnv *env,
        jobject /* this */) {
    auto* pool = &TerminalSessionPool::getInstance();
    pool->closeAllSessions();
    LOGD("All sessions closed");
}

/**
 * 获取当前会话ID
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_getCurrentSessionId(
        JNIEnv *env,
        jobject /* this */) {
    auto* pool = &TerminalSessionPool::getInstance();
    auto* session = pool->getCurrentSession();
    if (session != nullptr) {
        return env->NewStringUTF(session->sessionId.c_str());
    }
    return nullptr;
}

/**
 * 清理JNI资源
 */
extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_cleanup(
        JNIEnv *env,
        jobject /* this */) {
    auto* pool = &TerminalSessionPool::getInstance();
    pool->closeAllSessions();
    
    if (gJniCallbackObj != nullptr) {
        env->DeleteGlobalRef(gJniCallbackObj);
        gJniCallbackObj = nullptr;
    }
    gPostEventMethod = nullptr;
    LOGD("JNI cleaned up");
}

// ========== JNI 注册 ==========

static JNINativeMethod nativeMethods[] = {
        {"initJniCallback", "(Ljava/lang/Object;)V", (void*)Java_com_ai_assistance_aiterminal_terminal_TerminalJni_initJniCallback},
        {"createSession", "(Ljava/lang/String;)Z", (void*)Java_com_ai_assistance_aiterminal_terminal_TerminalJni_createSession},
        {"startSession", "(Ljava/lang/String;Ljava/lang/String;)Z", (void*)Java_com_ai_assistance_aiterminal_terminal_TerminalJni_startSession},
        {"executeCommand", "(Ljava/lang/String;Ljava/lang/String;)Z", (void*)Java_com_ai_assistance_aiterminal_terminal_TerminalJni_executeCommand},
        {"switchSession", "(Ljava/lang/String;)Z", (void*)Java_com_ai_assistance_aiterminal_terminal_TerminalJni_switchSession},
        {"changeDirectory", "(Ljava/lang/String;Ljava/lang/String;)Z", (void*)Java_com_ai_assistance_aiterminal_terminal_TerminalJni_changeDirectory},
        {"getCurrentDirectory", "(Ljava/lang/String;)Ljava/lang/String;", (void*)Java_com_ai_assistance_aiterminal_terminal_TerminalJni_getCurrentDirectory},
        {"suspendSession", "(Ljava/lang/String;)V", (void*)Java_com_ai_assistance_aiterminal_terminal_TerminalJni_suspendSession},
        {"resumeSession", "(Ljava/lang/String;)V", (void*)Java_com_ai_assistance_aiterminal_terminal_TerminalJni_resumeSession},
        {"closeSession", "(Ljava/lang/String;)Z", (void*)Java_com_ai_assistance_aiterminal_terminal_TerminalJni_closeSession},
        {"closeAllSessions", "()V", (void*)Java_com_ai_assistance_aiterminal_terminal_TerminalJni_closeAllSessions},
        {"getCurrentSessionId", "()Ljava/lang/String;", (void*)Java_com_ai_assistance_aiterminal_terminal_TerminalJni_getCurrentSessionId},
        {"cleanup", "()V", (void*)Java_com_ai_assistance_aiterminal_terminal_TerminalJni_cleanup}
};

// JNI_OnLoad：初始化全局VM
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to get JNIEnv");
        return JNI_ERR;
    }

    // 注册Native方法
    jclass clazz = env->FindClass(JNI_CLASS_NAME);
    if (clazz == nullptr) {
        LOGE("Failed to find class: %s", JNI_CLASS_NAME);
        return JNI_ERR;
    }

    if (env->RegisterNatives(clazz, nativeMethods, sizeof(nativeMethods)/sizeof(nativeMethods[0])) != JNI_OK) {
        LOGE("Failed to register native methods");
        return JNI_ERR;
    }

    // Security (A-4): install a SIGCHLD handler that reaps ALL exited
    // children non-blockingly. Without this, every forked shell (from
    // nativeCreatePty, nativeCreatePtyRoot, or TerminalSession::start)
    // becomes a zombie when it exits, because nothing waitpid()s it.
    // SA_RESTART: auto-restart interrupted read/write syscalls.
    // SA_NOCLDSTOP: don't signal on SIGSTOP/SIGCONT (only on exit).
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = sigchld_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESTART | SA_NOCLDSTOP;
    if (sigaction(SIGCHLD, &sa, nullptr) != 0) {
        LOGE("Failed to install SIGCHLD handler: %s", strerror(errno));
        // Non-fatal: continue without reaper. Zombies will accumulate but
        // the library still functions.
    } else {
        LOGD("SIGCHLD reaper installed");
    }

    LOGD("JNI_OnLoad completed");
    return JNI_VERSION_1_6;
}

// JNI_OnUnload：释放全局资源
void JNI_OnUnload(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    if (gJniCallbackObj != nullptr) {
        env->DeleteGlobalRef(gJniCallbackObj);
        gJniCallbackObj = nullptr;
    }
    gPostEventMethod = nullptr;
    
    // 关闭所有会话
    auto* pool = &TerminalSessionPool::getInstance();
    pool->closeAllSessions();
    
    LOGD("JNI_OnUnload completed");
}

// ========== RootTerminalManager 专用方法 ==========
//
// Security (A-2/A-3): the previous global `g_shellPid` / `g_masterFd` pair
// is GONE. Replaced by the session-keyed `g_rootSessions` map (defined at
// the top of this file, see root_session.h). Each nativeCreatePty /
// nativeCreatePtyRoot call now takes a `sessionId` parameter; the
// {child PID, master FD} pair is stored in the map keyed by sessionId.
// nativeCloseSession(sessionId) kills the child, reaps it, and closes
// the master fd — properly tearing down the whole PTY.

/**
 * 创建 PTY 并启动 Shell（支持 Root/Non-Root 双模）
 *
 * Security (A-2/A-3): now takes a `sessionId` parameter and stores the
 * {child PID, master FD} pair in `g_rootSessions[sessionId]`. If a session
 * with the same id already exists, it is torn down first (SIGKILL + reap +
 * close) so we never leak a shell + fd on session recreation.
 *
 * Security (A-10): no longer `dup()`s the master fd — the master fd is
 * returned DIRECTLY to Java and also stored in the session map. Java is
 * expected to adopt the fd into a ParcelFileDescriptor and close it on its
 * side; the session map's copy is closed by nativeCloseSession. This is
 * safe because ParcelFileDescriptor.adoptFd takes ownership of the int
 * (it does NOT dup), so when Java closes the PFD AND we close via the
 * session map, one of them is a no-op. The convention is:
 *   - Java closes its PFD on session close (ParcelFileDescriptor.close())
 *   - nativeCloseSession then kills the shell + reaps + closes the map's fd
 *   - If Java already closed the fd, the close() in nativeCloseSession
 *     returns -1 with EBADF which we ignore.
 *
 * Security (A-15): all GetStringUTFChars calls (shellPath + each env string)
 * are null-checked.
 *
 * Security (A-18): uses ptsname_r (thread-safe) instead of ptsname() which
 * returns a pointer to a static buffer.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_aiterminal_terminal_RootTerminalManager_nativeCreatePty(
        JNIEnv *env,
        jobject /* this */,
        jstring sessionId,
        jint cols,
        jint rows,
        jobjectArray envArray,
        jstring shellPath) {

    // A-15: null-check sessionId and shellPath.
    if (sessionId == nullptr || shellPath == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "sessionId and shellPath must not be null");
        return -1;
    }
    const char* sid = env->GetStringUTFChars(sessionId, nullptr);
    if (sid == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return -1;
    }
    const char* shell = env->GetStringUTFChars(shellPath, nullptr);
    if (shell == nullptr) {
        env->ReleaseStringUTFChars(sessionId, sid);
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return -1;
    }
    LOGD("Creating PTY for session %s with Shell: %s", sid, shell);

    // A-2/A-3: tear down any existing session with this id BEFORE creating
    // a new one. This prevents leaking the previous shell + fd. We hold
    // the session-map mutex during teardown so the lookup+kill+close+erase
    // is atomic w.r.t. concurrent nativeCloseSession / nativeGetSessionPid
    // calls.
    {
        std::lock_guard<std::mutex> lock(g_rootSessionsMutex);
        auto it = g_rootSessions.find(sid);
        if (it != g_rootSessions.end()) {
            RootSession& old = it->second;
            LOGD("Tearing down existing session %s (pid=%d, masterFd=%d)",
                 sid, old.pid, old.masterFd);
            if (old.pid > 0) {
                // RACE NOTE (A-4): the SIGCHLD handler may reap the child
                // before this waitpid returns; in that case waitpid returns
                // -1 with ECHILD, which is fine. We must still try to reap
                // to avoid a race where the new fork reuses the same pid.
                kill(old.pid, SIGKILL);
                int saved_errno = errno;
                (void)waitpid(old.pid, nullptr, 0);
                errno = saved_errno;
            }
            if (old.masterFd != -1) {
                // Best-effort close; EBADF is fine (Java may have closed
                // its adopted PFD already).
                (void)close(old.masterFd);
            }
            g_rootSessions.erase(it);
        }
    }

    // 1. 打开 PTY 主设备
    int masterFd = posix_openpt(O_RDWR | O_NOCTTY);
    if (masterFd == -1) {
        LOGE("posix_openpt failed: %s", strerror(errno));
        env->ReleaseStringUTFChars(sessionId, sid);
        env->ReleaseStringUTFChars(shellPath, shell);
        return -1;
    }

    // 2. 授权 PTY 从设备
    if (grantpt(masterFd) == -1) {
        LOGE("grantpt failed: %s", strerror(errno));
        close(masterFd);
        env->ReleaseStringUTFChars(sessionId, sid);
        env->ReleaseStringUTFChars(shellPath, shell);
        return -1;
    }

    // 3. 解锁 PTY 从设备
    if (unlockpt(masterFd) == -1) {
        LOGE("unlockpt failed: %s", strerror(errno));
        close(masterFd);
        env->ReleaseStringUTFChars(sessionId, sid);
        env->ReleaseStringUTFChars(shellPath, shell);
        return -1;
    }

    // 4. 获取 PTY 从设备路径 (A-18: ptsname_r instead of ptsname).
    char slavePath[256];
    if (ptsname_r(masterFd, slavePath, sizeof(slavePath)) != 0) {
        LOGE("ptsname_r failed: %s", strerror(errno));
        close(masterFd);
        env->ReleaseStringUTFChars(sessionId, sid);
        env->ReleaseStringUTFChars(shellPath, shell);
        return -1;
    }

    // 5. 准备环境变量 (A-15: null-check each env string).
    std::vector<char*> envp;
    if (envArray != nullptr) {
        jsize envCount = env->GetArrayLength(envArray);
        for (jsize i = 0; i < envCount; i++) {
            jstring envStr = (jstring)env->GetObjectArrayElement(envArray, i);
            if (envStr == nullptr) {
                envp.push_back(strdup(""));
                continue;
            }
            const char* envCStr = env->GetStringUTFChars(envStr, nullptr);
            if (envCStr == nullptr) {
                // OOM. Clean up what we have so far and bail.
                for (char* p : envp) {
                    if (p != nullptr) free(p);
                }
                env->ReleaseStringUTFChars(sessionId, sid);
                env->ReleaseStringUTFChars(shellPath, shell);
                close(masterFd);
                env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                              "GetStringUTFChars returned null for env string");
                return -1;
            }
            envp.push_back(strdup(envCStr));
            env->ReleaseStringUTFChars(envStr, envCStr);
            env->DeleteLocalRef(envStr);
        }
    }
    envp.push_back(nullptr); // 结束标记

    // 6. 配置窗口大小
    struct winsize ws;
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    // 7. Fork 子进程
    pid_t shellPid = fork();

    if (shellPid == 0) {
        // 子进程：配置 PTY 从设备并启动 Shell
        setsid(); // 创建新会话

        int slaveFd = open(slavePath, O_RDWR);
        if (slaveFd == -1) {
            // A-8/A-9: async-signal-safe error report. NO LOGE here.
            const char* em = "nativeCreatePty: open slave PTY failed\n";
            (void)write(STDERR_FILENO, em, strlen(em));
            _exit(127);
        }

        // 设置窗口大小
        ioctl(slaveFd, TIOCSWINSZ, &ws);

        // 重定向标准输入/输出/错误
        dup2(slaveFd, STDIN_FILENO);
        dup2(slaveFd, STDOUT_FILENO);
        dup2(slaveFd, STDERR_FILENO);

        // 关闭不需要的文件描述符
        close(slaveFd);
        close(masterFd);

        // 准备 exec 参数
        char* argv[] = {const_cast<char*>(shell), nullptr};

        // 执行 Shell
        execve(shell, argv, envp.data());

        // execve only returns on failure. A-8/A-9: report via async-signal-safe
        // write() — NO LOGE here (uses __android_log_print which is not safe).
        const char* em = "nativeCreatePty: execve failed\n";
        (void)write(STDERR_FILENO, em, strlen(em));
        _exit(127);

    } else if (shellPid > 0) {
        // 父进程：成功
        LOGD("PTY created for session %s, master FD: %d, Shell PID: %d",
             sid, masterFd, shellPid);

        // 清理 envp 中复制的字符串
        for (char* p : envp) {
            if (p != nullptr) {
                free(p);
            }
        }

        // A-2/A-3: store {pid, masterFd} in the session map so
        // nativeCloseSession can kill+reap+close later.
        {
            std::lock_guard<std::mutex> lock(g_rootSessionsMutex);
            g_rootSessions[sid] = RootSession{shellPid, masterFd};
        }

        env->ReleaseStringUTFChars(sessionId, sid);
        env->ReleaseStringUTFChars(shellPath, shell);

        // A-10: return masterFd DIRECTLY (no dup). The session map owns a
        // reference and will close it on nativeCloseSession. Java adopts
        // the fd into a ParcelFileDescriptor (also no dup) and closes it
        // on its side; whichever closes second sees EBADF and ignores it.
        return masterFd;

    } else {
        LOGE("Fork failed: %s", strerror(errno));
        close(masterFd);

        // 清理 envp 中复制的字符串
        for (char* p : envp) {
            if (p != nullptr) {
                free(p);
            }
        }

        env->ReleaseStringUTFChars(sessionId, sid);
        env->ReleaseStringUTFChars(shellPath, shell);
        return -1;
    }
}

/**
 * Security (A-2/A-3): close a root PTY session — kill the shell, reap it,
 * close the master fd, and erase from the session map.
 *
 * Idempotent: returns JNI_FALSE if the session was not found (no-op),
 * JNI_TRUE if it was found and torn down.
 *
 * RACE NOTE (A-4): the global SIGCHLD handler may reap the child before
 * our waitpid() runs. In that case waitpid returns -1 with ECHILD — we
 * ignore that error (the child is already reaped, which is what we want).
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_RootTerminalManager_nativeCloseSession(
        JNIEnv *env,
        jobject /* this */,
        jstring sessionId) {
    if (sessionId == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "sessionId must not be null");
        return JNI_FALSE;
    }
    const char* sid = env->GetStringUTFChars(sessionId, nullptr);
    if (sid == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return JNI_FALSE;
    }

    bool found = false;
    {
        std::lock_guard<std::mutex> lock(g_rootSessionsMutex);
        auto it = g_rootSessions.find(sid);
        if (it != g_rootSessions.end()) {
            RootSession s = it->second;  // copy out so we can release the lock
            g_rootSessions.erase(it);
            found = true;

            LOGD("nativeCloseSession: tearing down session %s (pid=%d, masterFd=%d)",
                 sid, s.pid, s.masterFd);

            if (s.pid > 0) {
                // Send SIGKILL to the shell. The SIGCHLD handler (A-4) will
                // reap it asynchronously; we still call waitpid here as a
                // belt-and-suspenders measure. If the handler already reaped
                // the child, waitpid returns -1 with ECHILD — we ignore it.
                kill(s.pid, SIGKILL);
                int saved_errno = errno;
                (void)waitpid(s.pid, nullptr, 0);
                errno = saved_errno;
            }
            if (s.masterFd != -1) {
                // Best-effort close; EBADF is fine (Java may have already
                // closed its adopted ParcelFileDescriptor).
                (void)close(s.masterFd);
            }
        }
    }

    env->ReleaseStringUTFChars(sessionId, sid);
    return found ? JNI_TRUE : JNI_FALSE;
}

/**
 * Security (A-2/A-3): expose the child shell PID for a given session so
 * the Kotlin layer can track / signal it (e.g. for graceful shutdown via
 * SIGHUP before SIGKILL). Returns -1 if the session is not registered.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_aiterminal_terminal_RootTerminalManager_nativeGetSessionPid(
        JNIEnv *env,
        jobject /* this */,
        jstring sessionId) {
    if (sessionId == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "sessionId must not be null");
        return -1;
    }
    const char* sid = env->GetStringUTFChars(sessionId, nullptr);
    if (sid == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return -1;
    }

    pid_t pid = -1;
    {
        std::lock_guard<std::mutex> lock(g_rootSessionsMutex);
        auto it = g_rootSessions.find(sid);
        if (it != g_rootSessions.end()) {
            pid = it->second.pid;
        }
    }

    env->ReleaseStringUTFChars(sessionId, sid);
    return (jint)pid;
}

// ========== ProcessManager JNI Methods ==========

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_addProcess(
        JNIEnv *env,
        jobject /* this */,
        jstring jCommand,
        jstring jSessionId) {
    // A-15: null-check both strings.
    const char* command = env->GetStringUTFChars(jCommand, nullptr);
    if (command == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return (jlong)-1;
    }
    const char* sessionId = env->GetStringUTFChars(jSessionId, nullptr);
    if (sessionId == nullptr) {
        env->ReleaseStringUTFChars(jCommand, command);
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return (jlong)-1;
    }
    
    pid_t pid = ProcessManager::getInstance().addProcess(command, sessionId);
    
    env->ReleaseStringUTFChars(jCommand, command);
    env->ReleaseStringUTFChars(jSessionId, sessionId);
    return (jlong)pid;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_killProcess(
        JNIEnv *env,
        jobject /* this */,
        jlong pid) {
    return ProcessManager::getInstance().killProcess((pid_t)pid) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_suspendProcess(
        JNIEnv *env,
        jobject /* this */,
        jlong pid) {
    return ProcessManager::getInstance().suspendProcess((pid_t)pid) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_resumeProcess(
        JNIEnv *env,
        jobject /* this */,
        jlong pid) {
    return ProcessManager::getInstance().resumeProcess((pid_t)pid) ? JNI_TRUE : JNI_FALSE;
}

// ========== PerformanceMonitor JNI Methods ==========

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_startCommandTracking(
        JNIEnv *env,
        jobject /* this */,
        jstring jSessionId,
        jstring jCommand) {
    // A-15: null-check both strings.
    const char* sessionId = env->GetStringUTFChars(jSessionId, nullptr);
    if (sessionId == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return;
    }
    const char* command = env->GetStringUTFChars(jCommand, nullptr);
    if (command == nullptr) {
        env->ReleaseStringUTFChars(jSessionId, sessionId);
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return;
    }
    
    PerformanceMonitor::getInstance().startCommand(sessionId, command);
    
    env->ReleaseStringUTFChars(jSessionId, sessionId);
    env->ReleaseStringUTFChars(jCommand, command);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_endCommandTracking(
        JNIEnv *env,
        jobject /* this */,
        jstring jSessionId,
        jint exitCode,
        jlong outputSize) {
    const char* sessionId = env->GetStringUTFChars(jSessionId, nullptr);
    if (sessionId == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return;
    }
    PerformanceMonitor::getInstance().endCommand(sessionId, (int)exitCode, (long long)outputSize);
    env->ReleaseStringUTFChars(jSessionId, sessionId);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_getSystemMemoryUsed(
        JNIEnv *env,
        jobject /* this */) {
    return (jlong)PerformanceMonitor::getInstance().getSystemMemoryUsed();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_getSystemCpuUsage(
        JNIEnv *env,
        jobject /* this */) {
    return (jdouble)PerformanceMonitor::getInstance().getSystemCpuUsage();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_getAppMemoryUsed(
        JNIEnv *env,
        jobject /* this */) {
    return (jlong)PerformanceMonitor::getInstance().getAppMemoryUsed();
}

// ========== ShellExtension JNI Methods ==========

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_isCommandSafe(
        JNIEnv *env,
        jobject /* this */,
        jstring jCommand) {
    const char* command = env->GetStringUTFChars(jCommand, nullptr);
    if (command == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return JNI_FALSE;
    }
    ShellExtension shell;
    bool isSafe = shell.isCommandSafe(command);
    env->ReleaseStringUTFChars(jCommand, command);
    return isSafe ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_addAlias(
        JNIEnv *env,
        jobject /* this */,
        jstring jName,
        jstring jCommand) {
    // A-15: null-check both strings.
    const char* name = env->GetStringUTFChars(jName, nullptr);
    if (name == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return JNI_FALSE;
    }
    const char* command = env->GetStringUTFChars(jCommand, nullptr);
    if (command == nullptr) {
        env->ReleaseStringUTFChars(jName, name);
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return JNI_FALSE;
    }
    
    ShellExtension shell;
    bool success = shell.getAliasManager()->addAlias(name, command);
    
    env->ReleaseStringUTFChars(jName, name);
    env->ReleaseStringUTFChars(jCommand, command);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_resolveAlias(
        JNIEnv *env,
        jobject /* this */,
        jstring jCommand) {
    const char* command = env->GetStringUTFChars(jCommand, nullptr);
    if (command == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return nullptr;
    }
    ShellExtension shell;
    std::string resolved = shell.getAliasManager()->resolveAlias(command);
    env->ReleaseStringUTFChars(jCommand, command);
    return env->NewStringUTF(resolved.c_str());
}

// ========== FlashingHelper JNI Methods ==========

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_initFlashingHelper(
        JNIEnv *env,
        jobject /* this */) {
    static FlashingHelper helper;
    return helper.initialize() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_isRootAvailable(
        JNIEnv *env,
        jobject /* this */) {
    static FlashingHelper helper;
    return helper.isRootAvailable() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_listPartitions(
        JNIEnv *env,
        jobject /* this */) {
    static FlashingHelper helper;
    auto partitions = helper.listPartitions();
    
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(partitions.size() * 2, stringClass, nullptr);
    
    for (size_t i = 0; i < partitions.size(); ++i) {
        env->SetObjectArrayElement(result, i * 2, env->NewStringUTF(partitions[i].name.c_str()));
        env->SetObjectArrayElement(result, i * 2 + 1, env->NewStringUTF(partitions[i].path.c_str()));
    }
    
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_backupPartition(
        JNIEnv *env,
        jobject /* this */,
        jstring jPartitionName) {
    static FlashingHelper helper;
    const char* partitionName = env->GetStringUTFChars(jPartitionName, nullptr);
    if (partitionName == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"),
                      "GetStringUTFChars returned null");
        return nullptr;
    }
    
    auto result = helper.backupPartition(partitionName);
    
    env->ReleaseStringUTFChars(jPartitionName, partitionName);
    return env->NewStringUTF(result.message.c_str());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_listMagiskModules(
        JNIEnv *env,
        jobject /* this */) {
    static FlashingHelper helper;
    auto modules = helper.listMagiskModules();
    
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(modules.size() * 3, stringClass, nullptr);
    
    for (size_t i = 0; i < modules.size(); ++i) {
        env->SetObjectArrayElement(result, i * 3, env->NewStringUTF(modules[i].id.c_str()));
        env->SetObjectArrayElement(result, i * 3 + 1, env->NewStringUTF(modules[i].name.c_str()));
        env->SetObjectArrayElement(result, i * 3 + 2, modules[i].enabled ? env->NewStringUTF("1") : env->NewStringUTF("0"));
    }
    
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_clearDalvikCache(
        JNIEnv *env,
        jobject /* this */) {
    static FlashingHelper helper;
    auto result = helper.clearDalvikCache();
    return env->NewStringUTF(result.message.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_wipeCachePartition(
        JNIEnv *env,
        jobject /* this */) {
    static FlashingHelper helper;
    auto result = helper.wipeCachePartition();
    return env->NewStringUTF(result.message.c_str());
}
