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
#include "terminal_session.h"
#include "process_manager.h"
#include "performance_monitor.h"
#include "shell_extension.h"
#include "flashing_helper.h"

#define LOG_TAG "TerminalJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define JNI_CLASS_NAME "com/ai/assistance/aiterminal/terminal/TerminalJni"
#define ROOT_JNI_CLASS_NAME "com/ai/assistance/aiterminal/terminal/RootTerminalManager"

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
    // 保存全局回调对象
    if (gJniCallbackObj != nullptr) {
        env->DeleteGlobalRef(gJniCallbackObj);
    }
    gJniCallbackObj = env->NewGlobalRef(callbackObj);

    // 获取回调方法ID
    jclass callbackCls = env->GetObjectClass(callbackObj);
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
    const char* id = env->GetStringUTFChars(sessionId, nullptr);
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
    const char* id = env->GetStringUTFChars(sessionId, nullptr);
    const char* shell = env->GetStringUTFChars(shellType, nullptr);

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
    const char* id = env->GetStringUTFChars(sessionId, nullptr);
    const char* cmd = env->GetStringUTFChars(command, nullptr);

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
    const char* id = env->GetStringUTFChars(sessionId, nullptr);
    const char* dir = env->GetStringUTFChars(path, nullptr);

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

// 全局变量用于 PTY 管理（简化示例）
static pid_t g_shellPid = -1;
static int g_masterFd = -1;

/**
 * 创建 PTY 并启动 Shell（支持 Root/Non-Root 双模）
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_aiterminal_terminal_RootTerminalManager_nativeCreatePty(
        JNIEnv *env,
        jobject /* this */,
        jint cols,
        jint rows,
        jobjectArray envArray,
        jstring shellPath) {

    const char* shell = env->GetStringUTFChars(shellPath, nullptr);
    LOGD("Creating PTY with Shell: %s", shell);

    // 1. 打开 PTY 主设备
    g_masterFd = posix_openpt(O_RDWR | O_NOCTTY);
    if (g_masterFd == -1) {
        LOGE("posix_openpt failed: %s", strerror(errno));
        env->ReleaseStringUTFChars(shellPath, shell);
        return -1;
    }

    // 2. 授权 PTY 从设备
    if (grantpt(g_masterFd) == -1) {
        LOGE("grantpt failed: %s", strerror(errno));
        close(g_masterFd);
        env->ReleaseStringUTFChars(shellPath, shell);
        return -1;
    }

    // 3. 解锁 PTY 从设备
    if (unlockpt(g_masterFd) == -1) {
        LOGE("unlockpt failed: %s", strerror(errno));
        close(g_masterFd);
        env->ReleaseStringUTFChars(shellPath, shell);
        return -1;
    }

    // 4. 获取 PTY 从设备路径
    const char* slaveName = ptsname(g_masterFd);
    if (slaveName == nullptr) {
        LOGE("ptsname failed: %s", strerror(errno));
        close(g_masterFd);
        env->ReleaseStringUTFChars(shellPath, shell);
        return -1;
    }

    // 5. 准备环境变量
    std::vector<char*> envp;
    if (envArray != nullptr) {
        jsize envCount = env->GetArrayLength(envArray);
        for (jsize i = 0; i < envCount; i++) {
            jstring envStr = (jstring)env->GetObjectArrayElement(envArray, i);
            const char* envCStr = env->GetStringUTFChars(envStr, nullptr);
            envp.push_back(strdup(envCStr));
            env->ReleaseStringUTFChars(envStr, envCStr);
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
    g_shellPid = fork();

    if (g_shellPid == 0) {
        // 子进程：配置 PTY 从设备并启动 Shell
        setsid(); // 创建新会话

        int slaveFd = open(slaveName, O_RDWR);
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
        close(g_masterFd);

        // 准备 exec 参数
        char* argv[] = {const_cast<char*>(shell), nullptr};

        // 执行 Shell
        execve(shell, argv, envp.data());

        // execve only returns on failure. A-8/A-9: report via async-signal-safe
        // write() — NO LOGE here (uses __android_log_print which is not safe).
        const char* em = "nativeCreatePty: execve failed\n";
        (void)write(STDERR_FILENO, em, strlen(em));
        _exit(127);
        
    } else if (g_shellPid > 0) {
        // 父进程：成功，返回主设备 FD 的副本
        LOGD("PTY created successfully, master FD: %d, Shell PID: %d", g_masterFd, g_shellPid);
        
        // 清理 envp 中复制的字符串
        for (char* p : envp) {
            if (p != nullptr) {
                free(p);
            }
        }
        
        env->ReleaseStringUTFChars(shellPath, shell);
        
        // 返回主设备 FD（使用 dup 来提供独立的文件描述符）
        return dup(g_masterFd);
        
    } else {
        LOGE("Fork failed: %s", strerror(errno));
        close(g_masterFd);
        g_masterFd = -1;
        
        // 清理 envp 中复制的字符串
        for (char* p : envp) {
            if (p != nullptr) {
                free(p);
            }
        }
        
        env->ReleaseStringUTFChars(shellPath, shell);
        return -1;
    }
}

// ========== ProcessManager JNI Methods ==========

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_aiterminal_terminal_TerminalJni_addProcess(
        JNIEnv *env,
        jobject /* this */,
        jstring jCommand,
        jstring jSessionId) {
    const char* command = env->GetStringUTFChars(jCommand, nullptr);
    const char* sessionId = env->GetStringUTFChars(jSessionId, nullptr);
    
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
    const char* sessionId = env->GetStringUTFChars(jSessionId, nullptr);
    const char* command = env->GetStringUTFChars(jCommand, nullptr);
    
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
    const char* name = env->GetStringUTFChars(jName, nullptr);
    const char* command = env->GetStringUTFChars(jCommand, nullptr);
    
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
