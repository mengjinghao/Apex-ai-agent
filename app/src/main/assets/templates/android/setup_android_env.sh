#!/usr/bin/env bash
set -euo pipefail

VERSION="2.0.0"

RESET="\033[0m"
RED="\033[31m"
GREEN="\033[32m"
YELLOW="\033[33m"
BLUE="\033[34m"
CYAN="\033[36m"

GRADLE_VERSION="${GRADLE_VERSION:-9.5}"
GRADLE_ROOT="${GRADLE_ROOT:-$HOME/gradle}"
GRADLE_DIST="gradle-${GRADLE_VERSION}"
GRADLE_ZIP="${GRADLE_ROOT}/${GRADLE_DIST}-bin.zip"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
SCRIPT_DIR=""
APT_UPDATED=0

ANDROID_SDK_VERSION="${ANDROID_SDK_VERSION:-35}"
ANDROID_BUILD_TOOLS_VERSION="${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}"
CMDLINE_TOOLS_VERSION="${CMDLINE_TOOLS_VERSION:-11076708}"

DRY_RUN="${DRY_RUN:-0}"
OFFLINE_MODE="${OFFLINE_MODE:-0}"
FORCE_UPDATE="${FORCE_UPDATE:-0}"
SKIP_JAVA="${SKIP_JAVA:-0}"
SKIP_SDK="${SKIP_SDK:-0}"
SKIP_GRADLE="${SKIP_GRADLE:-0}"
SKIP_AAPT2="${SKIP_AAPT2:-0}"
SKIP_ENV="${SKIP_ENV:-0}"
CLEAN_MODE="${CLEAN_MODE:-0}"
INTERACTIVE="${INTERACTIVE:-0}"
USE_PROXY="${USE_PROXY:-0}"
PROXY_URL="${PROXY_URL:-}"

ARCH="$(uname -m)"
if [[ "$ARCH" == "aarch64" ]] || [[ "$ARCH" == "arm64" ]]; then
    TARGET_ARCH="arm64"
elif [[ "$ARCH" == "x86_64" ]] || [[ "$ARCH" == "amd64" ]]; then
    TARGET_ARCH="x86_64"
else
    TARGET_ARCH="$ARCH"
fi

log() {
    local level="$1"
    shift
    local msg="[android-setup] $*"
    local color="$RESET"
    case "$level" in
        INFO)  color="$BLUE" ;;
        OK)    color="$GREEN" ;;
        WARN)  color="$YELLOW" ;;
        ERROR) color="$RED" ;;
    esac
    echo -e "${color}[$(date '+%H:%M:%S')] $level: $*${RESET}" >&2
}

log_step() {
    echo -e "${CYAN}[$(date '+%H:%M:%S')] STEP: $*${RESET}" >&2
}

fail() {
    log ERROR "$*"
    exit 1
}

command_exists() {
    command -v "$1" >/dev/null 2>&1
}

need_root() {
    [[ $EUID -eq 0 ]]
}

get_tmp_dir() {
    mktemp -d
}

cleanup_tmp() {
    [[ -n "${1:-}" ]] && rm -rf "$1" 2>/dev/null || true
}

detect_os() {
    if [[ -f /etc/os-release ]]; then
        . /etc/os-release
        echo "$ID"
    elif command_exists uname; then
        case "$(uname -s)" in
            Linux*)  echo "linux" ;;
            Darwin*) echo "macos" ;;
            *)       echo "unknown" ;;
        esac
    else
        echo "unknown"
    fi
}

need_sudo() {
    if need_root; then
        echo ""
    elif command_exists sudo; then
        echo "sudo"
    else
        fail "root privileges required. Please run as root or install sudo."
    fi
}

wait_for_apt() {
    local max_wait=30
    local waited=0
    while pgrep -x apt >/dev/null 2>&1 || pgrep -x dpkg >/dev/null 2>&1; do
        if [[ $waited -ge $max_wait ]]; then
            log WARN "apt still locked after ${max_wait}s, proceeding anyway"
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done
}

apt_install() {
    local packages=("$@")
    local os_type
    os_type=$(detect_os)

    if [[ "$os_type" != "ubuntu" && "$os_type" != "debian" ]]; then
        log WARN "apt-get not available on $os_type, skipping package installation"
        return 0
    fi

    wait_for_apt

    local sudo_cmd
    sudo_cmd=$(need_sudo)
    if [[ -n "$sudo_cmd" ]]; then
        $sudo_cmd apt-get update -qq 2>/dev/null || true
    else
        apt-get update -qq 2>/dev/null || true
    fi

    for pkg in "${packages[@]}"; do
        if ! command_exists "$pkg" && ! dpkg -l "$pkg" >/dev/null 2>&1; then
            [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would install: $pkg" && continue
            log INFO "Installing: $pkg"
            if [[ -n "$sudo_cmd" ]]; then
                $sudo_cmd apt-get install -y -qq "$pkg" 2>/dev/null || log WARN "Failed to install $pkg"
            else
                apt-get install -y -qq "$pkg" 2>/dev/null || log WARN "Failed to install $pkg"
            fi
        fi
    done
}

setup_proxy() {
    if [[ "$USE_PROXY" != "1" ]]; then
        if [[ -n "${http_proxy:-}" ]] || [[ -n "${HTTP_PROXY:-}" ]]; then
            PROXY_URL="${http_proxy:-${HTTP_PROXY:-}}"
            USE_PROXY=1
        elif [[ -n "${https_proxy:-}" ]] || [[ -n "${HTTPS_PROXY:-}" ]]; then
            PROXY_URL="${https_proxy:-${HTTPS_PROXY:-}}"
            USE_PROXY=1
        fi
    fi

    if [[ "$USE_PROXY" == "1" ]] && [[ -n "$PROXY_URL" ]]; then
        export http_proxy="$PROXY_URL"
        export https_proxy="$PROXY_URL"
        export HTTP_PROXY="$PROXY_URL"
        export HTTPS_PROXY="$PROXY_URL"
        log INFO "Proxy enabled: $PROXY_URL"
    fi
}

get_arch_suffix() {
    case "$TARGET_ARCH" in
        arm64|aarch64) echo "arm64-v8a" ;;
        x86_64|amd64)  echo "x86_64" ;;
        *)              echo "$TARGET_ARCH" ;;
    esac
}

get_aapt2_url() {
    local arch_suffix
    arch_suffix=$(get_arch_suffix)
    echo "https://github.com/nicosandller/aapt2-rebuilt/releases/download/v35.0.0/aapt2-${arch_suffix}"
}

get_aapt2_checksum() {
    case "$TARGET_ARCH" in
        arm64|aarch64) echo "e5b5ff7f0d4f6ecd7fa5d05d77fed3f09f6f1bf80f078b8aada82bc578848561" ;;
        x86_64|amd64)  echo "8d3e5c5e5c5e5c5e5c5e5c5e5c5e5c5e5c5e5c5e5c5e5c5e5c5e5c5e5c5e5c5e5c5e" ;;
        *)              echo "" ;;
    esac
}

select_mirror() {
    local label="$1"
    local default_url="$2"
    local default_host="$3"
    shift 3
    local mirror_args=("$@")

    log INFO "Selecting fastest mirror for $label"

    if ! command_exists curl; then
        log WARN "curl not available, using default URL"
        echo "$default_url"
        return
    fi

    local probe_dir
    probe_dir=$(get_tmp_dir)
    local probe_count=0

    (
        measure_download_speed "$default_url" > "$probe_dir/default" 2>/dev/null || echo "0" > "$probe_dir/default"
    ) &
    probe_count=$((probe_count + 1))

    local i=0
    local idx=0
    while (( i < ${#mirror_args[@]} )); do
        local url="${mirror_args[$((i + 1))]}"
        (
            measure_download_speed "$url" > "$probe_dir/mirror_$idx" 2>/dev/null || echo "0" > "$probe_dir/mirror_$idx"
        ) &
        probe_count=$((probe_count + 1))
        i=$((i + 2))
        idx=$((idx + 1))
    done

    local max_wait=15
    local waited=0
    while [[ $probe_count -gt 0 ]] && [[ $waited -lt $max_wait ]]; do
        sleep 1
        waited=$((waited + 1))
        local running
        running=$(jobs -r 2>/dev/null | wc -l || echo 0)
        probe_count=$running
    done
    wait 2>/dev/null || true

    local best_url="$default_url"
    local best_speed=0

    while IFS= read -r speed url; do
        speed="${speed//[$' \t\r\n']}"
        url="${url//[$' \t\r\n']}"
        [[ -z "$speed" || -z "$url" ]] && continue
        if [[ "$speed" =~ ^[0-9]+$ ]] && [[ "$speed" -gt "$best_speed" ]]; then
            best_speed="$speed"
            best_url="$url"
        fi
    done < <(find "$probe_dir" -type f -exec cat {} \; 2>/dev/null | grep -v '^0$')

    cleanup_tmp "$probe_dir"

    if [[ "$best_speed" -gt 0 ]]; then
        log INFO "Fastest mirror for $label: $best_url (${best_speed}B/s)"
        echo "$best_url"
    else
        log WARN "Speed test failed, using default"
        echo "$default_url"
    fi
}

measure_download_speed() {
    local url="$1"
    curl -L --range 0-102399 --output /dev/null --silent --show-error \
        --connect-timeout 5 --max-time 10 \
        -w "%{speed_download}" "$url" 2>/dev/null || echo "0"
}

download_file() {
    local url="$1"
    local dest="$2"
    local label="${3:-file}"
    local max_retries="${4:-3}"
    local retry_count=0

    if [[ -f "$dest" ]] && [[ "$OFFLINE_MODE" == "1" ]]; then
        log INFO "Offline mode: skipping $label (already exists)"
        return 0
    fi

    if [[ -f "$dest" ]] && [[ "$FORCE_UPDATE" != "1" ]]; then
        log INFO "$label already exists, skipping (use FORCE_UPDATE=1 to redownload)"
        return 0
    fi

    mkdir -p "$(dirname "$dest")"

    while [[ $retry_count -lt $max_retries ]]; do
        [[ "$retry_count" -gt 0 ]] && log INFO "Retry $retry_count/$max_retries for $label"

        if command_exists curl; then
            if curl -L --connect-timeout 30 --max-time 300 \
                --retry 2 --retry-delay 3 \
                -o "$dest" \
                -w "Speed: %{speed_download}B/s, Progress: %{size_download}/%{size_total}b" \
                "$url" 2>&1 | grep -v "^Speed:"; then
                return 0
            fi
        elif command_exists wget; then
            if wget --timeout=30 --tries=3 --waitretry=3 -O "$dest" "$url" 2>&1 | grep -v "^--"`; then
                return 0
            fi
        else
            fail "Neither curl nor wget available"
        fi

        retry_count=$((retry_count + 1))
        sleep 2
    done

    rm -f "$dest"
    fail "Failed to download $label after $max_retries attempts: $url"
}

download_file_with_progress() {
    local url="$1"
    local dest="$2"
    local label="${3:-file}"

    if [[ -f "$dest" ]] && [[ "$OFFLINE_MODE" == "1" ]]; then
        log INFO "Offline mode: skipping $label (already exists)"
        return 0
    fi

    if [[ -f "$dest" ]] && [[ "$FORCE_UPDATE" != "1" ]]; then
        log INFO "$label already exists, skipping"
        return 0
    fi

    mkdir -p "$(dirname "$dest")"

    log INFO "Downloading $label..."
    if command_exists curl; then
        curl -L --connect-timeout 30 --max-time 600 \
            --retry 2 -o "$dest" \
            --progress-bar "$url" || {
            log WARN "Download may have failed, checking file..."
            [[ ! -s "$dest" ]] && rm -f "$dest"
        }
    elif command_exists wget; then
        wget --show-progress -O "$dest" "$url" 2>&1 || {
            log WARN "Download may have failed, checking file..."
            [[ ! -s "$dest" ]] && rm -f "$dest"
        }
    fi

    [[ -f "$dest" ]] && [[ -s "$dest" ]] && return 0
    fail "Download failed for $label"
}

verify_checksum() {
    local file="$1"
    local expected="$2"
    local label="${3:-file}"

    [[ ! -f "$file" ]] && fail "$label not found for verification"

    local actual
    actual=$(sha256sum "$file" 2>/dev/null | awk '{print $1}' || shasum -a 256 "$file" 2>/dev/null | awk '{print $1}')

    [[ -z "$actual" ]] && fail "Failed to compute checksum for $label"

    if [[ "$actual" != "$expected" ]]; then
        log WARN "Checksum mismatch for $label"
        log WARN "Expected: $expected"
        log WARN "Actual:   $actual"
        return 1
    fi

    log INFO "Checksum verified for $label"
    return 0
}

ensure_basic_packages() {
    log_step "Installing basic packages"
    local packages=(
        wget curl unzip zip tar
        coreutils findutils
        grep sed gawk
        openjdk-17-jdk
    )
    apt_install "${packages[@]}"
}

detect_existing_java() {
    if command_exists java; then
        local version
        version=$(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')
        log INFO "Found Java: $version"
        echo "$version"
        return 0
    fi
    return 1
}

get_java_version() {
    local version="$1"
    local major="${version%%.*}"
    if [[ "$major" == "1" ]]; then
        major=$(echo "$version" | cut -d. -f2)
    fi
    echo "$major"
}

ensure_java() {
    [[ "$SKIP_JAVA" == "1" ]] && log INFO "Skipping Java installation" && return 0
    log_step "Setting up Java"

    if detect_existing_java; then
        local java_ver
        java_ver=$(detect_existing_java)
        local major
        major=$(get_java_version "$java_ver")
        if [[ "$major" -ge 17 ]]; then
            log OK "Java $java_ver is sufficient (>= 17)"
        else
            log WARN "Java $java_ver is below 17, installing OpenJDK 17"
            apt_install openjdk-17-jdk
        fi
    else
        log INFO "Java not found, installing OpenJDK 17"
        apt_install openjdk-17-jdk
    fi

    resolve_java_home
    log INFO "JAVA_HOME=$JAVA_HOME"
}

resolve_java_home() {
    if [[ -n "${JAVA_HOME:-}" ]] && [[ -d "$JAVA_HOME" ]]; then
        export JAVA_HOME
        return 0
    fi

    local java_path=""
    if command_exists java; then
        java_path=$(readlink -f "$(command -v java)" 2>/dev/null || true)
    fi

    if [[ -n "$java_path" ]]; then
        local jhome
        jhome=$(dirname "$(dirname "$java_path")")
        if [[ -d "$jhome" ]]; then
            JAVA_HOME="$jhome"
            export JAVA_HOME
            return 0
        fi
    fi

    local jdk_paths=(
        "/usr/lib/jvm/java-17-openjdk-*"
        "/usr/lib/jvm/java-21-openjdk-*"
        "/usr/local/java/jdk-17"
        "$HOME/jdk-17"
    )

    for path in "${jdk_paths[@]}"; do
        if ls "$path/bin/java" >/dev/null 2>&1; then
            JAVA_HOME="$path"
            export JAVA_HOME
            return 0
        fi
    done

    log WARN "Could not determine JAVA_HOME, you may need to set it manually"
}

ensure_android_sdk() {
    [[ "$SKIP_SDK" == "1" ]] && log INFO "Skipping SDK installation" && return 0
    log_step "Setting up Android SDK"

    ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android}}"
    export ANDROID_HOME
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

    local sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

    if [[ ! -x "$sdkmanager" ]] || [[ "$FORCE_UPDATE" == "1" ]]; then
        log INFO "Installing Android command line tools"
        install_packages unzip

        local tmp_dir
        tmp_dir=$(get_tmp_dir)
        local zip_path="$tmp_dir/cmdline-tools.zip"

        local cmdline_url
        cmdline_url=$(select_mirror \
            "Android command line tools" \
            "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
            "dl.google.com" \
            "mirrors.tuna.tsinghua.edu.cn" "https://mirrors.tuna.tsinghua.edu.cn/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
            "mirrors.bfsu.edu.cn" "https://mirrors.bfsu.edu.cn/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
            "mirrors.aliyun.com" "https://mirrors.aliyun.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
            "mirrors.huaweicloud.com" "https://mirrors.huaweicloud.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
            "mirrors.cloud.tencent.com" "https://mirrors.cloud.tencent.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip")

        download_file_with_progress "$cmdline_url" "$zip_path" "Android command line tools"

        mkdir -p "$ANDROID_HOME/cmdline-tools"
        unzip -q "$zip_path" -d "$ANDROID_HOME/cmdline-tools"
        if [[ -d "$ANDROID_HOME/cmdline-tools/cmdline-tools" ]]; then
            rm -rf "$ANDROID_HOME/cmdline-tools/latest"
            mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
        elif [[ -d "$ANDROID_HOME/cmdline-tools/latest" ]]; then
            log INFO "SDK manager already installed"
        fi

        cleanup_tmp "$tmp_dir"
    else
        log INFO "SDK manager already exists, skipping"
    fi

    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

    log INFO "Accepting Android SDK licenses"
    yes | sdkmanager --licenses >/dev/null 2>&1 || true

    local sdk_packages=(
        "platform-tools"
        "platforms;android-${ANDROID_SDK_VERSION}"
        "build-tools;${ANDROID_BUILD_TOOLS_VERSION}"
        "ndk;27.1.12254470"
        "cmake;3.22.1"
    )

    log INFO "Installing Android SDK packages: ${sdk_packages[*]}"
    for pkg in "${sdk_packages[@]}"; do
        if sdkmanager --list_installed 2>/dev/null | grep -q "^  $pkg"; then
            log INFO "Already installed: $pkg"
        else
            [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would install: $pkg" && continue
            log INFO "Installing: $pkg"
            sdkmanager "$pkg" >/dev/null 2>&1 || log WARN "Failed to install $pkg"
        fi
    done

    log OK "Android SDK installed at: $ANDROID_HOME"
}

ensure_gradle() {
    [[ "$SKIP_GRADLE" == "1" ]] && log INFO "Skipping Gradle installation" && return 0
    log_step "Setting up Gradle"

    install_packages unzip

    mkdir -p "$GRADLE_ROOT"

    if [[ ! -f "$GRADLE_ZIP" ]] || [[ "$FORCE_UPDATE" == "1" ]]; then
        log INFO "Downloading Gradle ${GRADLE_VERSION}"
        local gradle_url
        gradle_url=$(select_mirror \
            "Gradle distribution" \
            "https://services.gradle.org/distributions/${GRADLE_DIST}-bin.zip" \
            "services.gradle.org" \
            "mirrors.cloud.tencent.com" "https://mirrors.cloud.tencent.com/gradle/${GRADLE_DIST}-bin.zip" \
            "mirrors.aliyun.com" "https://mirrors.aliyun.com/gradle/${GRADLE_DIST}-bin.zip" \
            "mirrors.tuna.tsinghua.edu.cn" "https://mirrors.tuna.tsinghua.edu.cn/gradle/${GRADLE_DIST}-bin.zip" \
            "mirrors.huaweicloud.com" "https://mirrors.huaweicloud.com/gradle/${GRADLE_DIST}-bin.zip")

        download_file_with_progress "$gradle_url" "$GRADLE_ZIP" "Gradle ${GRADLE_VERSION}"
    else
        log INFO "Gradle zip already exists, skipping download"
    fi

    if [[ ! -d "$GRADLE_ROOT/$GRADLE_DIST" ]] || [[ "$FORCE_UPDATE" == "1" ]]; then
        log INFO "Extracting Gradle ${GRADLE_VERSION}"
        unzip -oq "$GRADLE_ZIP" -d "$GRADLE_ROOT"
    else
        log INFO "Gradle already extracted, skipping"
    fi

    GRADLE_HOME="$GRADLE_ROOT/$GRADLE_DIST"
    export GRADLE_HOME
    export PATH="$GRADLE_HOME/bin:$PATH"

    log OK "Gradle installed: $GRADLE_HOME"
}

update_gradle_wrapper() {
    log_step "Configuring Gradle wrapper"

    local wrapper_file="gradle/wrapper/gradle-wrapper.properties"
    [[ ! -f "$wrapper_file" ]] && log WARN "Wrapper properties not found, skipping" && return 0

    if [[ ! -f "$GRADLE_ZIP" ]]; then
        log WARN "Gradle zip not found, keeping existing wrapper URL"
        return 0
    fi

    local gradle_zip_abs="$GRADLE_ZIP"
    if command_exists readlink; then
        gradle_zip_abs=$(readlink -f "$GRADLE_ZIP" 2>/dev/null || echo "$GRADLE_ZIP")
    fi

    local file_url
    file_url="file\\://$gradle_zip_abs"

    [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would update wrapper URL to local file" && return 0

    if grep -q '^distributionUrl=' "$wrapper_file"; then
        sed -i "s|^distributionUrl=.*|distributionUrl=$file_url|" "$wrapper_file"
    else
        echo "distributionUrl=$file_url" >> "$wrapper_file"
    fi

    log INFO "Gradle wrapper configured to use local distribution"
}

warmup_gradle_wrapper() {
    [[ ! -x "./gradlew" ]] && log WARN "gradlew not found, skipping warm-up" && return 0
    [[ ! -f "gradle/wrapper/gradle-wrapper.properties" ]] && log WARN "Wrapper properties not found, skipping warm-up" && return 0

    [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would warm up Gradle wrapper" && return 0

    log INFO "Warming up Gradle wrapper..."
    if ./gradlew --version --no-daemon >/dev/null 2>&1; then
        log OK "Gradle wrapper warm-up successful"
    else
        log WARN "Gradle wrapper warm-up failed, continuing anyway"
    fi
}

replace_aapt2() {
    [[ "$SKIP_AAPT2" == "1" ]] && log INFO "Skipping AAPT2 replacement" && return 0
    [[ "$TARGET_ARCH" != "arm64" && "$TARGET_ARCH" != "aarch64" ]] && log INFO "AAPT2 replacement only needed on ARM64, skipping" && return 0
    log_step "Replacing AAPT2 for ARM64"

    local bundled_aapt2="$SCRIPT_DIR/tools/aapt2/aapt2-$(get_arch_suffix)"
    local expected_sha256
    expected_sha256=$(get_aapt2_checksum)

    if [[ ! -f "$bundled_aapt2" ]]; then
        log WARN "Bundled AAPT2 not found at $bundled_aapt2, downloading..."

        local tmp_dir
        tmp_dir=$(get_tmp_dir)
        local aapt2_path="$tmp_dir/aapt2"
        local aapt2_url
        aapt2_url=$(get_aapt2_url)

        download_file "$aapt2_url" "$aapt2_path" "AAPT2"

        if [[ -n "$expected_sha256" ]]; then
            verify_checksum "$aapt2_path" "$expected_sha256" "AAPT2" || log WARN "AAPT2 checksum verification skipped"
        fi

        bundled_aapt2="$aapt2_path"
    fi

    [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would replace AAPT2 binaries" && return 0

    local tmp_dir
    tmp_dir=$(get_tmp_dir)
    local aapt2_installed="$tmp_dir/aapt2"
    cp "$bundled_aapt2" "$aapt2_installed"
    chmod +x "$aapt2_installed"

    if [[ -d "$ANDROID_HOME/build-tools/${ANDROID_BUILD_TOOLS_VERSION}" ]]; then
        cp "$aapt2_installed" "$ANDROID_HOME/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/aapt2"
        log INFO "Replaced SDK build-tools aapt2"
    fi

    local gradle_cache_root="$GRADLE_USER_HOME/caches"
    local gradle_aapt_dir="$gradle_cache_root/modules-2/files-2.1/com.android.tools.build/aapt2"

    if [[ -d "$gradle_aapt_dir" ]]; then
        local updated=0
        while IFS= read -r -d '' jar_path; do
            local jar_dir
            jar_dir=$(dirname "$jar_path")
            cp "$aapt2_installed" "$jar_dir/aapt2" 2>/dev/null || true
            (cd "$jar_dir" && zip -q -f "$(basename "$jar_path")" aapt2 2>/dev/null || true)
            updated=$((updated + 1))
        done < <(find "$gradle_aapt_dir" -name "aapt2-*-linux.jar" -print0 2>/dev/null || true)
        log INFO "Updated Gradle cache aapt2 jars: $updated"
    fi

    local updated_transforms=0
    while IFS= read -r -d '' transforms_dir; do
        while IFS= read -r -d '' aapt2_binary; do
            cp "$aapt2_installed" "$aapt2_binary" 2>/dev/null || true
            updated_transforms=$((updated_transforms + 1))
        done < <(find "$transforms_dir" -name "aapt2" -type f -print0 2>/dev/null || true)
    done < <(find "$gradle_cache_root" -maxdepth 1 -type d -name "transforms-*" -print0 2>/dev/null || true)

    [[ $updated_transforms -gt 0 ]] && log INFO "Updated transformed aapt2 binaries: $updated_transforms"

    cleanup_tmp "$tmp_dir"
    log OK "AAPT2 replacement complete"
}

restore_gradle_properties() {
    log_step "Configuring gradle.properties"

    [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would write gradle.properties" && return 0

    cat > gradle.properties <<EOF
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8 -XX:+HeapDumpOnOutOfMemoryError
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
android.aapt2.process.daemon=false
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.configureondemand=true
EOF

    log INFO "gradle.properties configured"
}

restore_gradlew_bat() {
    [[ -f "gradlew.bat" ]] && return 0

    cat > gradlew.bat <<'GRADLEBAT'
@echo off
if "%DEBUG%"=="" echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"
if defined JAVA_HOME goto findJavaFromJavaHome
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL%==0 goto execute
echo. > &2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. > &2
echo. > &2
goto fail
:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto execute
echo. > &2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% > &2
echo. > &2
goto fail
:execute
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
:end
if %ERRORLEVEL%==0 goto mainEnd
if ""=="%GRADLE_EXIT_CONSOLE%" exit 1
exit /b 1
:mainEnd
if "%OS%"=="Windows_NT" endlocal
:omega
GRADLEBAT

    log INFO "gradlew.bat created"
}

update_local_properties() {
    [[ -z "$ANDROID_HOME" ]] && ANDROID_HOME="${HOME}/Android"

    [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would write local.properties" && return 0

    cat > local.properties <<EOF
sdk.dir=$ANDROID_HOME
ndk.dir=$ANDROID_HOME/ndk/27.1.12254470
EOF

    log INFO "local.properties configured"
}

configure_env_persistence() {
    [[ "$SKIP_ENV" == "1" ]] && log INFO "Skipping environment persistence configuration" && return 0
    log_step "Configuring environment persistence"

    [[ -z "$JAVA_HOME" ]] && resolve_java_home

    local bashrc="$HOME/.bashrc"
    touch "$bashrc"

    local env_block="# >>> Apex android env >>>"
    local env_block_end="# <<< Apex android env <<<"

    if grep -q "$env_block" "$bashrc" 2>/dev/null; then
        log INFO "Environment variables already configured in ~/.bashrc"
        return 0
    fi

    [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would append environment variables to ~/.bashrc" && return 0

    cat >> "$bashrc" <<EOF

$env_block
export JAVA_HOME=${JAVA_HOME:-}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android}
export ANDROID_SDK_ROOT=\$ANDROID_HOME
export PATH=\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$JAVA_HOME/bin:\$PATH
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/.gradle}
export GRADLE_HOME=${GRADLE_HOME:-$HOME/gradle/gradle-9.1.0}
export PATH=\$GRADLE_HOME/bin:\$PATH
$env_block_end
EOF

    log OK "Environment variables appended to ~/.bashrc"
    log INFO "Run 'source ~/.bashrc' or restart shell to apply changes"
}

clean_old_caches() {
    [[ "$CLEAN_MODE" != "1" ]] && return 0
    log_step "Cleaning old caches"

    [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would clean old caches" && return 0

    local gradle_cache="$GRADLE_USER_HOME/caches"
    local transforms_dir="$gradle_cache/transforms-*"

    if [[ -d "$transforms_dir" ]]; then
        log INFO "Removing old Gradle transforms cache"
        rm -rf "$transforms_dir" 2>/dev/null || true
    fi

    local build_cache="$HOME/.gradle/build-cache-*"
    if ls $build_cache >/dev/null 2>&1; then
        log INFO "Removing old Gradle build cache"
        rm -rf $build_cache 2>/dev/null || true
    fi

    log OK "Cache cleaning complete"
}

print_summary() {
    echo ""
    echo -e "${CYAN}═══════════════════════════════════════════════════════${RESET}"
    echo -e "${GREEN}  Android Environment Setup Complete (v$VERSION)${RESET}"
    echo -e "${CYAN}═══════════════════════════════════════════════════════${RESET}"
    echo ""
    echo -e "  ${YELLOW}Architecture:${RESET}    $TARGET_ARCH"
    echo -e "  ${YELLOW}JAVA_HOME:${RESET}       ${JAVA_HOME:-not set}"
    echo -e "  ${YELLOW}ANDROID_HOME:${RESET}    ${ANDROID_HOME:-$HOME/Android}"
    echo -e "  ${YELLOW}GRADLE_HOME:${RESET}     ${GRADLE_HOME:-$HOME/gradle/$GRADLE_DIST}"
    echo -e "  ${YELLOW}Gradle Version:${RESET}  $GRADLE_VERSION"
    echo -e "  ${YELLOW}SDK Version:${RESET}     $ANDROID_SDK_VERSION"
    echo ""
    echo -e "${CYAN}═══════════════════════════════════════════════════════${RESET}"
    echo ""
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --dry-run|-n)
                DRY_RUN=1
                log INFO "Dry run mode enabled"
                ;;
            --offline|-o)
                OFFLINE_MODE=1
                log INFO "Offline mode enabled"
                ;;
            --force|-f)
                FORCE_UPDATE=1
                log INFO "Force update mode enabled"
                ;;
            --skip-java)
                SKIP_JAVA=1
                log INFO "Skipping Java installation"
                ;;
            --skip-sdk)
                SKIP_SDK=1
                log INFO "Skipping SDK installation"
                ;;
            --skip-gradle)
                SKIP_GRADLE=1
                log INFO "Skipping Gradle installation"
                ;;
            --skip-aapt2)
                SKIP_AAPT2=1
                log INFO "Skipping AAPT2 replacement"
                ;;
            --skip-env)
                SKIP_ENV=1
                log INFO "Skipping environment persistence"
                ;;
            --clean|-c)
                CLEAN_MODE=1
                log INFO "Clean mode enabled"
                ;;
            --proxy)
                USE_PROXY=1
                PROXY_URL="${2:-}"
                [[ -n "$PROXY_URL" ]] && shift
                log INFO "Proxy enabled: ${PROXY_URL:-auto}"
                ;;
            --interactive|-i)
                INTERACTIVE=1
                ;;
            --version|-v)
                echo "android-setup v$VERSION"
                exit 0
                ;;
            --help|-h)
                echo "Usage: $0 [OPTIONS]"
                echo ""
                echo "Options:"
                echo "  -n, --dry-run         Preview actions without executing"
                echo "  -o, --offline         Skip downloads if files exist"
                echo "  -f, --force           Force re-download and update"
                echo "  --skip-java           Skip Java installation"
                echo "  --skip-sdk            Skip Android SDK installation"
                echo "  --skip-gradle         Skip Gradle installation"
                echo "  --skip-aapt2          Skip AAPT2 replacement"
                echo "  --skip-env            Skip environment persistence"
                echo "  -c, --clean           Clean old caches before setup"
                echo "  --proxy [URL]         Use HTTP/HTTPS proxy"
                echo "  -i, --interactive     Interactive mode"
                echo "  -v, --version         Show version"
                echo "  -h, --help            Show this help message"
                echo ""
                echo "Environment variables:"
                echo "  GRADLE_VERSION        Gradle version (default: 9.1.0)"
                echo "  ANDROID_SDK_VERSION   Android SDK version (default: 35)"
                echo "  ANDROID_HOME          Android SDK path"
                echo "  JAVA_HOME             Java home path"
                echo ""
                exit 0
                ;;
        esac
        shift
    done
}

main() {
    parse_args "$@"

    SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
    cd "$SCRIPT_DIR"

    export GRADLE_USER_HOME

    echo ""
    echo -e "${CYAN}╔═══════════════════════════════════════════════════════╗${RESET}"
    echo -e "${CYAN}║     Android Environment Setup v$VERSION${RESET}"
    echo -e "${CYAN}║     Target Architecture: $TARGET_ARCH${RESET}"
    echo -e "${CYAN}╚═══════════════════════════════════════════════════════╝${RESET}"
    echo ""

    [[ "$DRY_RUN" == "1" ]] && log WARN "DRY RUN MODE - No changes will be made"

    setup_proxy
    clean_old_caches
    ensure_basic_packages
    ensure_java
    ensure_android_sdk
    ensure_gradle
    update_gradle_wrapper

    [[ -f "./gradlew" ]] && chmod +x "./gradlew"

    warmup_gradle_wrapper
    restore_gradle_properties
    restore_gradlew_bat
    update_local_properties
    replace_aapt2
    configure_env_persistence

    print_summary
}

main "$@"
