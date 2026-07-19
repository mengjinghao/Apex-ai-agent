#include <jni.h>
#include <string>
#include <unistd.h>
#include <fcntl.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <android/log.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "RootTerminalCore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// 尝试寻找 su 二进制的路径
const char* SU_PATHS[] = {
    "/system/xbin/su",
    "/system/bin/su",
    "/sbin/su",
    "/vendor/bin/su",
    "/su/bin/su",
    "/system/su",
    "/data/local/xbin/su",
    "/data/local/bin/su",
    nullptr
};

const char* find_su_binary() {
    for (int i = 0; SU_PATHS[i] != nullptr; i++) {
        if (access(SU_PATHS[i], X_OK) == 0) {
            LOGI("Found su binary at: %s", SU_PATHS[i]);
            return SU_PATHS[i];
        }
    }
    LOGE("No su binary found");
    return nullptr;
}

// 寻找普通 shell
const char* find_shell_binary() {
    const char* SHELL_PATHS[] = {
        "/system/bin/sh",
        "/system/xbin/sh",
        "/vendor/bin/sh",
        "/system/bin/bash",
        nullptr
    };
    
    for (int i = 0; SHELL_PATHS[i] != nullptr; i++) {
        if (access(SHELL_PATHS[i], X_OK) == 0) {
            LOGI("Found shell binary at: %s", SHELL_PATHS[i]);
            return SHELL_PATHS[i];
        }
    }
    LOGE("No shell binary found");
    return nullptr;
}

// 设置终端窗口大小
void set_window_size(int fd, int cols, int rows) {
    struct winsize ws;
    ws.ws_col = cols;
    ws.ws_row = rows;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    int result = ioctl(fd, TIOCSWINSZ, &ws);
    if (result < 0) {
        LOGW("ioctl(TIOCSWINSZ) failed: %s", strerror(errno));
    }
}

// 可选的 SELinux 处理
void try_disable_selinux() {
    // 这是一个可选的处理，不是必需的
    // 在某些设备上可能需要，在其他设备上可能无效
    // 使用 setexeccon(NULL) 或者其他方式
    LOGI("SELinux handling is available but not enabled by default");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_ai_assistance_aiterminal_terminal_RootTerminalManager_nativeCreatePtyRoot(
        JNIEnv *env,
        jobject thiz,
        jint cols,
        jint rows,
        jobjectArray env_array,
        jboolean use_root) {

    // 1. 打开 PTY Master
    int master_fd = posix_openpt(O_RDWR | O_CLOEXEC);
    if (master_fd < 0) {
        LOGE("Failed to open PTY master: %s", strerror(errno));
        return -1;
    }
    LOGD("PTY master created, fd: %d", master_fd);

    // 2. Grant/Unlock PTY
    if (grantpt(master_fd) < 0 || unlockpt(master_fd) < 0) {
        LOGE("Failed to grant/unlock PTY: %s", strerror(errno));
        close(master_fd);
        return -1;
    }
    LOGD("PTY granted and unlocked");

    // 3. 获取 PTY Slave 路径
    const char* slave_name = ptsname(master_fd);
    if (!slave_name) {
        LOGE("Failed to get PTY slave name: %s", strerror(errno));
        close(master_fd);
        return -1;
    }
    LOGD("PTY slave path: %s", slave_name);

    // 4. 设置窗口大小
    set_window_size(master_fd, cols, rows);

    // 5. Pre-compute shell path in the PARENT (before fork).
    // A-8: find_su_binary / find_shell_binary call __android_log_print which
    // is NOT async-signal-safe, so they must NOT be called in the child
    // branch (between fork and exec).
    const char* shell_path_pre = use_root ? find_su_binary() : find_shell_binary();
    if (shell_path_pre == nullptr) {
        LOGE(use_root ? "SU binary not found" : "Shell binary not found");
        close(master_fd);
        return -1;
    }
    LOGI("Starting %s shell with: %s", use_root ? "Root" : "Normal", shell_path_pre);

    // 6. Fork 进程
    pid_t pid = fork();
    if (pid < 0) {
        LOGE("Fork failed: %s", strerror(errno));
        close(master_fd);
        return -1;
    }

    if (pid == 0) {
        // --- 子进程 (这里将变成 Shell) ---
        // A-8/A-9: ONLY async-signal-safe calls allowed between fork and exec.
        // No LOGD/LOGE/LOGI/LOGW (uses __android_log_print), no exit() (use _exit).
        // Diagnostics go through write(STDERR_FILENO, ...).

        // 新建 Session
        if (setsid() < 0) {
            const char* m = "nativeCreatePtyRoot: setsid failed\n";
            (void)write(STDERR_FILENO, m, strlen(m));
            // 继续执行，即使失败了
        }

        // 打开 PTY Slave
        int slave_fd = open(slave_name, O_RDWR);
        if (slave_fd < 0) {
            const char* m = "nativeCreatePtyRoot: open slave PTY failed\n";
            (void)write(STDERR_FILENO, m, strlen(m));
            _exit(127);
        }

        // 设置 Slave 为控制终端
        if (ioctl(slave_fd, TIOCSCTTY, 0) < 0) {
            // Best-effort; continue. Use write() for diagnostics (A-8/A-9).
            const char* m = "nativeCreatePtyRoot: TIOCSCTTY failed\n";
            (void)write(STDERR_FILENO, m, strlen(m));
        }

        // 重定向 STDIN/STDOUT/STDERR
        dup2(slave_fd, STDIN_FILENO);
        dup2(slave_fd, STDOUT_FILENO);
        dup2(slave_fd, STDERR_FILENO);
        
        // 关闭不需要的文件描述符
        if (slave_fd > STDERR_FILENO) {
            close(slave_fd);
        }
        close(master_fd);

        // 处理环境变量 (将 Java 数组转为 C char**)
        jsize env_len = env->GetArrayLength(env_array);
        char** c_env = (char**) malloc((env_len + 1) * sizeof(char*));
        if (!c_env) {
            const char* m = "nativeCreatePtyRoot: malloc env array failed\n";
            (void)write(STDERR_FILENO, m, strlen(m));
            _exit(127);
        }
        
        for (int i = 0; i < env_len; i++) {
            jstring str = (jstring) env->GetObjectArrayElement(env_array, i);
            const char* c_str = env->GetStringUTFChars(str, nullptr);
            c_env[i] = strdup(c_str);
            env->ReleaseStringUTFChars(str, c_str);
        }
        c_env[env_len] = nullptr;

        // 选择启动 su 或 sh (path was pre-computed in PARENT — see A-8 note above).
        char* const argv[] = { const_cast<char*>(shell_path_pre), nullptr };

        // 执行 shell
        execve(shell_path_pre, argv, c_env);

        // execve 只有在出错时才会返回. A-8/A-9: NO LOGE here — async-signal-
        // unsafe. Report via write() and exit via _exit(127).
        {
            const char* m = "nativeCreatePtyRoot: execve failed\n";
            (void)write(STDERR_FILENO, m, strlen(m));
        }

        // 清理
        for (int i = 0; c_env[i]; i++) free(c_env[i]);
        free(c_env);
        _exit(127);
    }

    // --- 父进程 (继续运行在 App 上下文) ---
    LOGI("Session created, PID: %d, Root: %s", pid, use_root ? "yes" : "no");
    
    // 返回 master fd 给 Java 层
    // 注意：我们不 dup，直接传递 fd
    return master_fd;
}
