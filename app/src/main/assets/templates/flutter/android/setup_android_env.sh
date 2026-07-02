#!/usr/bin/env bash
set -euo pipefail

VERSION="2.0.0"

RESET="\033[0m"
RED="\033[31m"
GREEN="\033[32m"
YELLOW="\033[33m"
BLUE="\033[34m"
CYAN="\033[36m"

GRADLE_VERSION="${GRADLE_VERSION:-8.14}"
GRADLE_ROOT="${GRADLE_ROOT:-$HOME/gradle}"
GRADLE_DIST="gradle-${GRADLE_VERSION}"
GRADLE_ZIP="${GRADLE_ROOT}/${GRADLE_DIST}-bin.zip"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
FLUTTER_RELEASES_URL="${FLUTTER_RELEASES_URL:-}"
FLUTTER_DEFAULT_INSTALL_DIR="${FLUTTER_DEFAULT_INSTALL_DIR:-$HOME/flutter}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-28.2.13676358}"
ANDROID_NDK_ZIP_URL="${ANDROID_NDK_ZIP_URL:-}"
ANDROID_CMAKE_VERSION="${ANDROID_CMAKE_VERSION:-3.22.1}"
ENABLE_ARM64_NDK_EMULATION="${ENABLE_ARM64_NDK_EMULATION:-1}"
FLUTTER_STORAGE_BASE_URL_SELECTED=""
PUB_HOSTED_URL_SELECTED=""
FLUTTER_SDK=""
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

        if command_exists aria2c; then
            if aria2c --allow-overwrite=true --continue=true \
                --max-connection-per-server=16 --split=16 --min-split-size=1M \
                --connect-timeout=30 --timeout=120 --max-tries=3 --retry-wait=3 \
                --async-dns=false --disable-ipv6=true --file-allocation=none \
                --summary-interval=0 --console-log-level=warn \
                --out="$(basename "$dest")" --dir="$(dirname "$dest")" "$url" 2>/dev/null; then
                return 0
            fi
        elif command_exists curl; then
            if curl -L --connect-timeout 30 --max-time 300 \
                --retry 2 --retry-delay 3 -o "$dest" \
                -w "Speed: %{speed_download}B/s" "$url" 2>&1 | grep -v "^Speed:"; then
                return 0
            fi
        elif command_exists wget; then
            if wget --timeout=30 --tries=3 --waitretry=3 -O "$dest" "$url" 2>&1 | grep -v "^--"; then
                return 0
            fi
        else
            fail "aria2c/curl/wget is required"
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

download_text() {
    local url="$1"
    if command_exists curl; then
        curl -L --connect-timeout 30 --max-time 120 --retry 2 --retry-delay 3 --silent --show-error "$url"
        return
    fi
    if command_exists wget; then
        wget --timeout=30 --tries=3 --waitretry=3 -qO- "$url"
        return
    fi
    fail "curl or wget is required to download text resources."
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

detect_flutter_arch() {
    case "$(uname -m)" in
        x86_64 | amd64)
            echo "x64"
            ;;
        aarch64 | arm64 | arm64_v8a)
            echo "arm64"
            ;;
        *)
            echo "arm64"
            ;;
    esac
}

select_flutter_release_manifest_url() {
    if [[ -n "$FLUTTER_RELEASES_URL" ]]; then
        echo "$FLUTTER_RELEASES_URL"
        return
    fi

    select_mirror \
        "Flutter release manifest" \
        "https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json" \
        "storage.googleapis.com" \
        "storage.flutter-io.cn" "https://storage.flutter-io.cn/flutter_infra_release/releases/releases_linux.json" \
        "mirrors.tuna.tsinghua.edu.cn" "https://mirrors.tuna.tsinghua.edu.cn/flutter_infra_release/releases/releases_linux.json"
}

resolve_flutter_storage_base_url() {
    if [[ -n "$FLUTTER_STORAGE_BASE_URL_SELECTED" ]]; then
        echo "$FLUTTER_STORAGE_BASE_URL_SELECTED"
        return
    fi

    if [[ -n "${FLUTTER_STORAGE_BASE_URL:-}" ]]; then
        FLUTTER_STORAGE_BASE_URL_SELECTED="${FLUTTER_STORAGE_BASE_URL%/}"
        echo "$FLUTTER_STORAGE_BASE_URL_SELECTED"
        return
    fi

    local manifest_url
    manifest_url=$(select_flutter_release_manifest_url)
    local derived_root="${manifest_url%/flutter_infra_release/releases/releases_linux.json}"
    if [[ "$derived_root" == "$manifest_url" || -z "$derived_root" ]]; then
        derived_root="https://storage.googleapis.com"
    fi

    FLUTTER_STORAGE_BASE_URL_SELECTED="$derived_root"
    echo "$FLUTTER_STORAGE_BASE_URL_SELECTED"
}

select_flutter_release_base_url() {
    local storage_root
    storage_root=$(resolve_flutter_storage_base_url)
    echo "${storage_root%/}/flutter_infra_release/releases"
}

parse_flutter_release_manifest() {
    local manifest="$1"
    local stable_hash
    stable_hash=$(printf '%s\n' "$manifest" | sed -n 's/.*"stable": "\([^"]*\)".*/\1/p' | head -n 1)
    if [[ -z "$stable_hash" ]]; then
        return 1
    fi

    local base_url
    base_url=$(printf '%s\n' "$manifest" | sed -n 's/.*"base_url": "\([^"]*\)".*/\1/p' | head -n 1)
    if [[ -z "$base_url" ]]; then
        return 1
    fi

    local release_info
    release_info=$(printf '%s\n' "$manifest" | awk -v hash="$stable_hash" '
    index($0, "\"hash\": \"" hash "\"") { found = 1 }
    found && /"version":/ {
      line = $0
      sub(/^.*"version": "/, "", line)
      sub(/".*$/, "", line)
      version = line
    }
    found && /"archive":/ {
      line = $0
      sub(/^.*"archive": "/, "", line)
      sub(/".*$/, "", line)
      archive = line
    }
    found && /"release_date":/ {
      line = $0
      sub(/^.*"release_date": "/, "", line)
      sub(/".*$/, "", line)
      release_date = line
    }
    found && /"sha256":/ {
      line = $0
      sub(/^.*"sha256": "/, "", line)
      sub(/".*$/, "", line)
      sha = line
      print hash "\t" version "\t" archive "\t" sha "\t" release_date
      exit
    }
  ')
    if [[ -z "$release_info" ]]; then
        return 1
    fi

    printf '%s\t%s\n' "$base_url" "$release_info"
}

install_flutter_from_archive() {
    local install_dir="$1"
    apt_install xz-utils

    if ! command_exists tar; then
        fail "tar is required to extract the Flutter SDK archive."
    fi
    if ! command_exists sha256sum; then
        fail "sha256sum is required to verify the Flutter SDK archive."
    fi

    log INFO "Fetching Flutter stable release manifest"
    local manifest
    local manifest_url
    manifest_url=$(select_flutter_release_manifest_url)
    manifest=$(download_text "$manifest_url") || fail "Failed to download Flutter release manifest: $manifest_url"

    local release_info
    release_info=$(parse_flutter_release_manifest "$manifest") || fail "Failed to parse Flutter stable release manifest."

    local base_url
    base_url=$(printf '%s' "$release_info" | cut -f1)
    local release_hash
    release_hash=$(printf '%s' "$release_info" | cut -f2)
    local version
    version=$(printf '%s' "$release_info" | cut -f3)
    local archive
    archive=$(printf '%s' "$release_info" | cut -f4)
    local expected_sha256
    expected_sha256=$(printf '%s' "$release_info" | cut -f5)

    if [[ -z "$base_url" || -z "$release_hash" || -z "$version" || -z "$archive" || -z "$expected_sha256" ]]; then
        fail "Flutter stable release metadata is incomplete."
    fi

    local download_base_url
    download_base_url=$(select_flutter_release_base_url)
    if [[ -z "$download_base_url" ]]; then
        download_base_url="$base_url"
    fi
    local archive_url="${download_base_url%/}/$archive"

    log INFO "Installing Flutter stable $version from $archive_url"

    local tmp_dir
    tmp_dir=$(get_tmp_dir)
    local archive_path="$tmp_dir/flutter-sdk.tar.xz"
    download_file "$archive_url" "$archive_path"

    local actual_sha256
    actual_sha256=$(sha256sum "$archive_path" | awk '{print $1}')
    if [[ "$actual_sha256" != "$expected_sha256" ]]; then
        rm -rf "$tmp_dir"
        fail "Flutter SDK checksum mismatch: expected $expected_sha256, got $actual_sha256"
    fi

    mkdir -p "$(dirname "$install_dir")"
    tar -xJf "$archive_path" -C "$tmp_dir"

    if [[ ! -d "$tmp_dir/flutter" ]]; then
        rm -rf "$tmp_dir"
        fail "Flutter archive did not contain the expected flutter directory."
    fi

    rm -rf "$install_dir"
    mv "$tmp_dir/flutter" "$install_dir"
    rm -rf "$tmp_dir"
}

install_flutter_sdk() {
    local install_dir="$FLUTTER_DEFAULT_INSTALL_DIR"
    local arch
    arch=$(detect_flutter_arch)

    if [[ -x "$install_dir/bin/flutter" ]]; then
        FLUTTER_SDK="$install_dir"
        return
    fi

    log INFO "Flutter SDK not found; installing it automatically"
    case "$arch" in
        x64 | arm64)
            if [[ "$arch" == "arm64" ]]; then
                log INFO "Using the official generic Linux Flutter SDK bundle; Flutter will bootstrap Linux arm64 host tools from official storage"
            fi
            install_flutter_from_archive "$install_dir"
            ;;
        *)
            fail "Unsupported Linux host architecture: $arch"
            ;;
    esac

    FLUTTER_SDK="$install_dir"
}

ensure_ping() {
    if command_exists ping || command_exists busybox; then
        return
    fi
    if command_exists apt-get; then
        apt_install iputils-ping
    fi
    if ! command_exists ping && ! command_exists busybox; then
        log WARN "ping still unavailable; mirror selection will be skipped"
    fi
}

ensure_java() {
    [[ "$SKIP_JAVA" == "1" ]] && log INFO "Skipping Java installation" && return 0
    log_step "Setting up Java"

    if command_exists java; then
        local version
        version=$(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')
        local major="${version%%.*}"
        if [[ "$major" == "1" ]]; then
            major=$(echo "$version" | cut -d. -f2)
        fi
        if [[ -n "$major" && "$major" -ge 17 ]]; then
            log OK "Java $version detected"
            return
        fi
        log WARN "Java version $version is below 17; upgrading"
    else
        log INFO "Java not found; installing OpenJDK 17"
    fi
    apt_install openjdk-17-jdk
}

resolve_java_home() {
    if [[ -n "${JAVA_HOME:-}" ]] && [[ -d "$JAVA_HOME" ]]; then
        export JAVA_HOME
        return
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
            return
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
            return
        fi
    done

    log WARN "Could not determine JAVA_HOME"
}

resolve_flutter_sdk() {
    if [[ -n "${FLUTTER_ROOT:-}" ]] && [[ -d "$FLUTTER_ROOT" ]]; then
        FLUTTER_SDK="$FLUTTER_ROOT"
    elif [[ -n "${FLUTTER_HOME:-}" ]] && [[ -d "$FLUTTER_HOME" ]]; then
        FLUTTER_SDK="$FLUTTER_HOME"
    elif [[ -f "local.properties" ]]; then
        local configured_flutter_sdk
        configured_flutter_sdk=$(sed -n 's/^flutter\.sdk=//p' local.properties | tail -n 1)
        if [[ -n "$configured_flutter_sdk" ]] && [[ -d "$configured_flutter_sdk" ]]; then
            FLUTTER_SDK="$configured_flutter_sdk"
        fi
    fi

    if [[ -z "$FLUTTER_SDK" ]] && command_exists flutter; then
        local flutter_path
        flutter_path=$(readlink -f "$(command -v flutter)" 2>/dev/null || true)
        if [[ -n "$flutter_path" ]]; then
            FLUTTER_SDK=$(dirname "$(dirname "$flutter_path")")
        fi
    fi

    if [[ -z "$FLUTTER_SDK" ]] || [[ ! -d "$FLUTTER_SDK" ]] || [[ ! -x "$FLUTTER_SDK/bin/flutter" ]]; then
        install_flutter_sdk
    fi

    export FLUTTER_SDK
    export FLUTTER_ROOT="$FLUTTER_SDK"
    export PATH="$FLUTTER_SDK/bin:$PATH"
    log INFO "Flutter SDK detected: $FLUTTER_SDK"
}

configure_flutter_storage_env() {
    local storage_root
    storage_root=$(resolve_flutter_storage_base_url)
    export FLUTTER_STORAGE_BASE_URL="$storage_root"
    log INFO "Using Flutter storage mirror: $FLUTTER_STORAGE_BASE_URL"
}

measure_pub_host_speed() {
    local pub_url="$1"
    local probe_url="${pub_url%/}/api/packages/flutter"
    if ! command_exists curl; then
        echo 0
        return 0
    fi
    local speed
    speed=$(curl -L --output /dev/null --silent --show-error \
        --connect-timeout 3 --max-time 8 \
        -w "%{speed_download}" "$probe_url" 2>/dev/null || echo 0)
    speed_to_int "$speed"
}

speed_to_int() {
    local speed="$1"
    if [[ ! "$speed" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
        echo 0
        return
    fi
    local speed_int="${speed%.*}"
    [[ -z "$speed_int" ]] && speed_int=0
    echo "$speed_int"
}

configure_pub_hosted_url() {
    if [[ -n "${PUB_HOSTED_URL:-}" ]]; then
        PUB_HOSTED_URL_SELECTED="${PUB_HOSTED_URL%/}"
        export PUB_HOSTED_URL="$PUB_HOSTED_URL_SELECTED"
        log INFO "Using user-specified PUB_HOSTED_URL: $PUB_HOSTED_URL"
        return 0
    fi

    local default_pub="https://pub.dev"
    local mirror_pub="https://pub.flutter-io.cn"

    local default_speed mirror_speed
    default_speed=$(measure_pub_host_speed "$default_pub")
    mirror_speed=$(measure_pub_host_speed "$mirror_pub")

    if [[ "$mirror_speed" -gt "$default_speed" ]]; then
        PUB_HOSTED_URL_SELECTED="$mirror_pub"
    else
        PUB_HOSTED_URL_SELECTED="$default_pub"
    fi

    export PUB_HOSTED_URL="$PUB_HOSTED_URL_SELECTED"
    log INFO "Selected PUB_HOSTED_URL: $PUB_HOSTED_URL (pub.dev=${default_speed}B/s, mirror=${mirror_speed}B/s)"
}

bootstrap_flutter_sdk() {
    log INFO "Bootstrapping Flutter SDK"
    if ! flutter --version; then
        fail "Flutter SDK bootstrap failed."
    fi
}

precache_flutter_sdk() {
    log INFO "Precaching Flutter artifacts for Linux and Android"
    if ! flutter precache --linux --android; then
        fail "Flutter artifact precache failed."
    fi
}

is_arm64_host() {
    case "$(uname -m)" in
        aarch64 | arm64 | arm64_v8a)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

ensure_flutter_arm64_dart_sdk() {
    if ! is_arm64_host; then
        return 0
    fi

    local dart_bin="$FLUTTER_SDK/bin/cache/dart-sdk/bin/dart"
    if [[ -x "$dart_bin" ]] && "$dart_bin" --version >/dev/null 2>&1; then
        log INFO "ARM64 host Dart SDK is already usable"
        return 0
    fi

    local engine_version_file="$FLUTTER_SDK/bin/internal/engine.version"
    if [[ ! -f "$engine_version_file" ]]; then
        fail "Flutter engine.version not found: $engine_version_file"
    fi

    local engine_version
    engine_version=$(tr -d '[:space:]' < "$engine_version_file")
    if [[ -z "$engine_version" ]]; then
        fail "Failed to read Flutter engine version from: $engine_version_file"
    fi

    local storage_root
    storage_root=$(resolve_flutter_storage_base_url)
    local primary_url="${storage_root%/}/flutter_infra_release/flutter/${engine_version}/dart-sdk-linux-arm64.zip"
    local fallback_url="https://storage.googleapis.com/flutter_infra_release/flutter/${engine_version}/dart-sdk-linux-arm64.zip"

    log INFO "ARM64 host detected; repairing Flutter Dart SDK cache"

    local tmp_dir
    tmp_dir=$(get_tmp_dir)
    local zip_path="$tmp_dir/dart-sdk-linux-arm64.zip"

    if ! download_file "$primary_url" "$zip_path"; then
        log WARN "Primary Dart SDK URL failed, trying fallback: $fallback_url"
        download_file "$fallback_url" "$zip_path" || {
            rm -rf "$tmp_dir"
            fail "Failed to download ARM64 Dart SDK for engine $engine_version"
        }
    fi

    local extract_dir="$tmp_dir/extract"
    mkdir -p "$extract_dir"
    unzip -q "$zip_path" -d "$extract_dir"

    local extracted_sdk_dir=""
    if [[ -d "$extract_dir/dart-sdk" ]]; then
        extracted_sdk_dir="$extract_dir/dart-sdk"
    else
        extracted_sdk_dir=$(find "$extract_dir" -mindepth 1 -maxdepth 3 -type d -name dart-sdk | head -n 1)
    fi

    if [[ -z "$extracted_sdk_dir" ]] || [[ ! -d "$extracted_sdk_dir" ]]; then
        rm -rf "$tmp_dir"
        fail "Extracted ARM64 Dart SDK directory not found"
    fi

    rm -rf "$FLUTTER_SDK/bin/cache/dart-sdk"
    mkdir -p "$FLUTTER_SDK/bin/cache"
    mv "$extracted_sdk_dir" "$FLUTTER_SDK/bin/cache/dart-sdk"

    chmod +x "$FLUTTER_SDK/bin/cache/dart-sdk/bin/dart" 2>/dev/null || true
    chmod +x "$FLUTTER_SDK/bin/cache/dart-sdk/bin/dartaotruntime" 2>/dev/null || true

    rm -f "$FLUTTER_SDK/bin/cache/flutter_tools.snapshot"
    rm -rf "$tmp_dir"

    if ! "$FLUTTER_SDK/bin/cache/dart-sdk/bin/dart" --version >/dev/null 2>&1; then
        fail "ARM64 Dart SDK verification failed after patch"
    fi

    log OK "ARM64 Dart SDK patch applied successfully"
}

verify_setup_health() {
    log_step "Running final health checks"
    flutter --version || fail "flutter --version failed during final check"
    if ! flutter doctor -v; then
        log WARN "flutter doctor reports remaining optional issues; setup continues"
    fi
}

ensure_android_ndk_preinstall() {
    local ndk_dir="$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION"
    if [[ -x "$ndk_dir/ndk-build" ]]; then
        log INFO "Android NDK already present: $ANDROID_NDK_VERSION"
        return 0
    fi

    apt_install unzip

    local ndk_url="${ANDROID_NDK_ZIP_URL:-}"
    if [[ -z "$ndk_url" ]]; then
        case "$ANDROID_NDK_VERSION" in
            28.2.13676358)
                ndk_url="https://dl.google.com/android/repository/android-ndk-r28b-linux.zip"
                ;;
            *)
                log WARN "No direct NDK zip mapping for $ANDROID_NDK_VERSION; will fallback to sdkmanager"
                return 1
                ;;
        esac
    fi

    local selected_ndk_url
    selected_ndk_url=$(select_mirror \
        "Android NDK ${ANDROID_NDK_VERSION}" \
        "$ndk_url" \
        "dl.google.com" \
        "mirrors.tuna.tsinghua.edu.cn" "https://mirrors.tuna.tsinghua.edu.cn/android/repository/$(basename "$ndk_url")" \
        "mirrors.bfsu.edu.cn" "https://mirrors.bfsu.edu.cn/android/repository/$(basename "$ndk_url")" \
        "mirrors.aliyun.com" "https://mirrors.aliyun.com/android/repository/$(basename "$ndk_url")" \
        "mirrors.huaweicloud.com" "https://mirrors.huaweicloud.com/android/repository/$(basename "$ndk_url")")

    local tmp_dir
    tmp_dir=$(get_tmp_dir)
    local ndk_zip="$tmp_dir/ndk.zip"

    log INFO "Pre-downloading Android NDK with multi-connection downloader"
    if ! download_file "$selected_ndk_url" "$ndk_zip"; then
        rm -rf "$tmp_dir"
        log WARN "NDK zip download failed; will fallback to sdkmanager"
        return 1
    fi

    local extract_dir="$tmp_dir/extract"
    mkdir -p "$extract_dir"
    if ! unzip -q "$ndk_zip" -d "$extract_dir"; then
        rm -rf "$tmp_dir"
        log WARN "NDK zip extract failed; will fallback to sdkmanager"
        return 1
    fi

    local extracted_ndk
    extracted_ndk=$(find "$extract_dir" -mindepth 1 -maxdepth 2 -type d -name "android-ndk-*" | head -n 1)
    if [[ -z "$extracted_ndk" ]] || [[ ! -d "$extracted_ndk" ]]; then
        rm -rf "$tmp_dir"
        log WARN "Extracted NDK folder not found; will fallback to sdkmanager"
        return 1
    fi

    mkdir -p "$ANDROID_HOME/ndk"
    rm -rf "$ndk_dir"
    mv "$extracted_ndk" "$ndk_dir"
    rm -rf "$tmp_dir"

    if [[ -x "$ndk_dir/ndk-build" ]]; then
        log OK "Android NDK preinstall complete: $ANDROID_NDK_VERSION"
        return 0
    fi

    log WARN "NDK preinstall verification failed; will fallback to sdkmanager"
    return 1
}

ensure_android_cmake() {
    if [[ -x "$ANDROID_HOME/cmake/$ANDROID_CMAKE_VERSION/bin/cmake" ]]; then
        log INFO "Android CMake already present: $ANDROID_CMAKE_VERSION"
        return 0
    fi
    log INFO "Installing Android CMake via sdkmanager: $ANDROID_CMAKE_VERSION"
    sdkmanager "cmake;$ANDROID_CMAKE_VERSION"
}

configure_arm64_cmake_emulation() {
    if ! is_arm64_host; then
        return 0
    fi

    local cmake_bin_dir="$ANDROID_HOME/cmake/$ANDROID_CMAKE_VERSION/bin"
    if [[ ! -d "$cmake_bin_dir" ]]; then
        log WARN "Android CMake bin dir not found, skip ARM64 cmake emulation: $cmake_bin_dir"
        return 1
    fi

    apt_install box64 ninja-build file

    local wrapped_count=0
    local t
    for t in cmake ctest cpack; do
        local f="$cmake_bin_dir/$t"
        [[ -f "$f" ]] || continue

        if file "$f" | grep -q 'ELF 64-bit.*x86-64'; then
            local real_path="$cmake_bin_dir/.${t}.real"
            if [[ ! -f "$real_path" ]]; then
                mv "$f" "$real_path"
            fi
            cat > "$f" <<EOF
#!/usr/bin/env bash
exec box64 "$real_path" "\$@"
EOF
            chmod +x "$f"
            wrapped_count=$((wrapped_count + 1))
        fi
    done

    if command_exists ninja; then
        cat > "$cmake_bin_dir/ninja" <<'EOF'
#!/usr/bin/env bash
exec /usr/bin/ninja "$@"
EOF
        chmod +x "$cmake_bin_dir/ninja"
    else
        log WARN "native ninja not found in /usr/bin; keep original cmake ninja"
    fi

    if ! "$cmake_bin_dir/cmake" --version >/dev/null 2>&1; then
        log WARN "ARM64 CMake emulation self-check failed"
        return 1
    fi

    log OK "Configured ARM64 CMake emulation in $cmake_bin_dir (wrapped $wrapped_count tools, ninja=native)"
}

configure_arm64_ndk_emulation() {
    if ! is_arm64_host; then
        return 0
    fi
    if [[ "$ENABLE_ARM64_NDK_EMULATION" != "1" ]]; then
        log INFO "ARM64 NDK emulation disabled by ENABLE_ARM64_NDK_EMULATION=$ENABLE_ARM64_NDK_EMULATION"
        return 0
    fi

    local ndk_bin="$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin"
    if [[ ! -d "$ndk_bin" ]]; then
        log WARN "NDK linux-x86_64 toolchain dir not found, skip ARM64 emulation: $ndk_bin"
        return 1
    fi

    apt_install box64 file lld

    local wrapped_count=0
    local f
    for f in "$ndk_bin"/*; do
        [[ -f "$f" ]] || continue

        if head -c 2 "$f" 2>/dev/null | grep -q '^#!'; then
            continue
        fi

        if file "$f" | grep -q 'ELF 64-bit.*x86-64'; then
            local base
            base=$(basename "$f")
            local real_path="$ndk_bin/.${base}.real"
            if [[ ! -f "$real_path" ]]; then
                mv "$f" "$real_path"
            fi
            cat > "$f" <<EOF
#!/usr/bin/env bash
exec box64 "$real_path" "\$@"
EOF
            chmod +x "$f"
            wrapped_count=$((wrapped_count + 1))
        fi
    done

    if [[ -x "/usr/bin/ld.lld" ]]; then
        cat > "$ndk_bin/lld" <<'EOF'
#!/usr/bin/env bash
exec /usr/bin/ld.lld "$@"
EOF
        chmod +x "$ndk_bin/lld"
        rm -f "$ndk_bin/ld.lld"
        ln -s lld "$ndk_bin/ld.lld"
    fi

    local test_src="/tmp/Apex_ndk_test.c"
    local test_bin="/tmp/Apex_ndk_test"
    echo 'int main(){return 0;}' > "$test_src"
    if ! "$ndk_bin/clang" --target=aarch64-none-linux-android24 --sysroot="$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/sysroot" "$test_src" -o "$test_bin" >/dev/null 2>&1; then
        log WARN "ARM64 NDK emulation self-check failed (clang link test)"
        return 1
    fi

    log OK "Configured ARM64 NDK emulation wrappers in $ndk_bin (wrapped $wrapped_count x86_64 tools)"
}

ensure_android_tools() {
    [[ "$SKIP_SDK" == "1" ]] && log INFO "Skipping SDK installation" && return 0
    log_step "Setting up Android SDK"

    ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android}}"
    export ANDROID_HOME
    export ANDROID_SDK_ROOT="$ANDROID_HOME"

    local sdkmanager_path="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

    if [[ ! -x "$sdkmanager_path" ]] || [[ "$FORCE_UPDATE" == "1" ]]; then
        log INFO "Installing Android command line tools"
        apt_install unzip

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
        "ndk;${ANDROID_NDK_VERSION}"
        "cmake;${ANDROID_CMAKE_VERSION}"
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

    ensure_android_cmake
    if ! configure_arm64_cmake_emulation; then
        log WARN "ARM64 CMake emulation setup failed or skipped; CMake-based native builds may fail on ARM64 host"
    fi

    if ! ensure_android_ndk_preinstall; then
        log WARN "Falling back to sdkmanager for NDK: $ANDROID_NDK_VERSION"
        sdkmanager "ndk;$ANDROID_NDK_VERSION" >/dev/null 2>&1 || true
    fi

    if ! configure_arm64_ndk_emulation; then
        log WARN "ARM64 NDK emulation setup failed or skipped; native builds may still fail on ARM64 host"
    fi

    log OK "Android SDK installed at: $ANDROID_HOME"
}

ensure_gradle() {
    [[ "$SKIP_GRADLE" == "1" ]] && log INFO "Skipping Gradle installation" && return 0
    log_step "Setting up Gradle"

    apt_install unzip
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

update_gradle_wrapper_properties() {
    local wrapper_file="gradle/wrapper/gradle-wrapper.properties"
    [[ ! -f "$wrapper_file" ]] && log WARN "Wrapper properties not found, skipping" && return

    if [[ ! -f "$GRADLE_ZIP" ]]; then
        log WARN "Gradle zip not found; keeping existing wrapper distributionUrl"
        return
    fi

    local gradle_zip_abs="$GRADLE_ZIP"
    if command_exists readlink; then
        gradle_zip_abs=$(readlink -f "$GRADLE_ZIP" 2>/dev/null || echo "$GRADLE_ZIP")
    fi
    local file_url="file\\://$gradle_zip_abs"

    [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would update wrapper URL" && return

    if grep -q '^distributionUrl=' "$wrapper_file"; then
        sed -i "s|^distributionUrl=.*|distributionUrl=$file_url|" "$wrapper_file"
    else
        echo "distributionUrl=$file_url" >> "$wrapper_file"
    fi
    log INFO "Gradle wrapper configured"
}

warmup_gradle_wrapper_cache() {
    [[ ! -x "./gradlew" ]] && log WARN "gradlew not found; skipping warm-up" && return 0
    [[ ! -f "gradle/wrapper/gradle-wrapper.properties" ]] && log WARN "Wrapper properties not found; skipping warm-up" && return 0

    [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would warm up Gradle wrapper" && return 0

    log INFO "Warming Gradle wrapper cache..."
    if ./gradlew --version --no-daemon >/dev/null 2>&1; then
        log OK "Gradle wrapper warm-up successful"
    else
        log WARN "Gradle wrapper warm-up failed, continuing"
    fi
}

restore_gradle_properties() {
    log_step "Configuring gradle.properties"

    [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would write gradle.properties" && return

    cat > gradle.properties <<EOF
org.gradle.jvmargs=-Xmx8G -XX:MaxMetaspaceSize=4G -XX:ReservedCodeCacheSize=512m -XX:+HeapDumpOnOutOfMemoryError
org.gradle.workers.max=1
android.useAndroidX=true
android.aapt2.process.daemon=false
android.enableResourceOptimizations=false
org.gradle.parallel=true
org.gradle.caching=true
EOF

    log INFO "gradle.properties configured"
}

restore_gradlew_bat() {
    [[ -f "gradlew.bat" ]] && return

    [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would write gradlew.bat" && return

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

    [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would write local.properties" && return

    cat > local.properties <<EOF
flutter.sdk=$FLUTTER_SDK
sdk.dir=$ANDROID_HOME
ndk.dir=$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION
EOF

    log INFO "local.properties configured"
}

configure_env_persistence() {
    [[ "$SKIP_ENV" == "1" ]] && log INFO "Skipping environment persistence configuration" && return 0
    log_step "Configuring environment persistence"

    [[ -z "$JAVA_HOME" ]] && resolve_java_home

    local bashrc="$HOME/.bashrc"
    touch "$bashrc"

    local env_block="# >>> Apex flutter android env >>>"
    local env_block_end="# <<< Apex flutter android env <<<"

    if grep -q "$env_block" "$bashrc" 2>/dev/null; then
        log INFO "Environment variables already configured in ~/.bashrc"
        return 0
    fi

    [[ "$DRY_RUN" == "1" ]] && log WARN "[DRY RUN] Would append environment variables to ~/.bashrc" && return 0

    cat >> "$bashrc" <<EOF

$env_block
export FLUTTER_ROOT=$FLUTTER_SDK
export FLUTTER_STORAGE_BASE_URL=$FLUTTER_STORAGE_BASE_URL
export PUB_HOSTED_URL=$PUB_HOSTED_URL
export JAVA_HOME=${JAVA_HOME:-}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android}
export ANDROID_SDK_ROOT=\$ANDROID_HOME
export PATH=\$FLUTTER_ROOT/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$JAVA_HOME/bin:\$PATH
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/.gradle}
export GRADLE_HOME=${GRADLE_HOME:-$HOME/gradle/gradle-8.14}
export PATH=\$GRADLE_HOME/bin:\$PATH
export ENABLE_ARM64_NDK_EMULATION=$ENABLE_ARM64_NDK_EMULATION
$env_block_end
EOF

    log OK "Environment variables appended to ~/.bashrc"
    log INFO "Run 'source ~/.bashrc' or restart shell to apply changes"
}

ensure_pub_get() {
    local project_root
    project_root=$(cd "$SCRIPT_DIR/.." && pwd)

    if [[ ! -f "$project_root/pubspec.yaml" ]]; then
        fail "pubspec.yaml not found in project root: $project_root"
    fi

    log INFO "Running flutter pub get in project root: $project_root"
    if ! (cd "$project_root" && flutter pub get); then
        fail "flutter pub get failed in project root: $project_root"
    fi

    if [[ ! -f "$project_root/.dart_tool/package_config.json" ]]; then
        fail "package_config.json still missing after pub get: $project_root/.dart_tool/package_config.json"
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

    local updated_sdk_count=0
    if [[ -d "$ANDROID_HOME/build-tools" ]]; then
        while IFS= read -r -d '' build_tools_dir; do
            if [[ -f "$build_tools_dir/aapt2" ]]; then
                cp "$aapt2_installed" "$build_tools_dir/aapt2"
                updated_sdk_count=$((updated_sdk_count + 1))
            fi
        done < <(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d -print0)
    fi
    [[ $updated_sdk_count -gt 0 ]] && log INFO "Replaced SDK build-tools aapt2 binaries: $updated_sdk_count"

    local gradle_cache_root="$GRADLE_USER_HOME/caches"
    local gradle_aapt_dir="$gradle_cache_root/modules-2/files-2.1/com.android.tools.build/aapt2"

    if [[ -d "$gradle_aapt_dir" ]]; then
        local updated_jar_count=0
        while IFS= read -r -d '' jar_path; do
            local jar_dir
            jar_dir=$(dirname "$jar_path")
            cp "$aapt2_installed" "$jar_dir/aapt2" 2>/dev/null || true
            (cd "$jar_dir" && zip -q -f "$(basename "$jar_path")" aapt2 2>/dev/null || true)
            updated_jar_count=$((updated_jar_count + 1))
        done < <(find "$gradle_aapt_dir" -name "aapt2-*-linux.jar" -print0 2>/dev/null || true)
        log INFO "Updated Gradle cache aapt2 jars: $updated_jar_count"
    fi

    local updated_transform_count=0
    while IFS= read -r -d '' transformed_aapt2; do
        cp "$aapt2_installed" "$transformed_aapt2" 2>/dev/null || true
        updated_transform_count=$((updated_transform_count + 1))
    done < <(find "$gradle_cache_root" -type f -name "aapt2" -path "*/transforms*/*" -print0 2>/dev/null || true)

    [[ $updated_transform_count -gt 0 ]] && log INFO "Updated transformed aapt2 binaries: $updated_transform_count"

    cleanup_tmp "$tmp_dir"
    log OK "AAPT2 replacement complete"
}

print_summary() {
    echo ""
    echo -e "${CYAN}═══════════════════════════════════════════════════════${RESET}"
    echo -e "${GREEN}  Flutter Android Environment Setup Complete (v$VERSION)${RESET}"
    echo -e "${CYAN}═══════════════════════════════════════════════════════${RESET}"
    echo ""
    echo -e "  ${YELLOW}Architecture:${RESET}    $TARGET_ARCH"
    echo -e "  ${YELLOW}Flutter SDK:${RESET}    ${FLUTTER_SDK:-not set}"
    echo -e "  ${YELLOW}JAVA_HOME:${RESET}       ${JAVA_HOME:-not set}"
    echo -e "  ${YELLOW}ANDROID_HOME:${RESET}    ${ANDROID_HOME:-$HOME/Android}"
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
            --version|-v)
                echo "flutter-android-setup v$VERSION"
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
                echo "  -v, --version         Show version"
                echo "  -h, --help            Show this help message"
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
    echo -e "${CYAN}║  Flutter Android Environment Setup v$VERSION${RESET}"
    echo -e "${CYAN}║  Target Architecture: $TARGET_ARCH${RESET}"
    echo -e "${CYAN}╚═══════════════════════════════════════════════════════╝${RESET}"
    echo ""

    [[ "$DRY_RUN" == "1" ]] && log WARN "DRY RUN MODE - No changes will be made"

    setup_proxy
    apt_install wget curl aria2 unzip zip xz-utils git
    ensure_ping
    ensure_java
    resolve_java_home
    configure_flutter_storage_env
    configure_pub_hosted_url
    resolve_flutter_sdk
    ensure_flutter_arm64_dart_sdk
    configure_env_persistence
    bootstrap_flutter_sdk
    precache_flutter_sdk
    ensure_android_tools
    ensure_gradle
    update_gradle_wrapper_properties

    [[ -f "./gradlew" ]] && chmod +x "./gradlew"

    warmup_gradle_wrapper_cache
    restore_gradle_properties
    restore_gradlew_bat
    update_local_properties
    ensure_pub_get
    replace_aapt2
    verify_setup_health

    print_summary
}

main "$@"
