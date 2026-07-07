#!/bin/bash
set -euo pipefail

RESET="\033[0m"
RED="\033[31m"
GREEN="\033[32m"
YELLOW="\033[33m"
BLUE="\033[34m"
CYAN="\033[36m"

AUTO_UPDATE="${AUTO_UPDATE:-1}"
UPDATE_REMINDER="${UPDATE_REMINDER:-1}"
USE_PYENV="${USE_PYENV:-1}"
USE_NVM="${USE_NVM:-1}"

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

command_exists() {
    command -v "$1" >/dev/null 2>&1
}

download_text() {
    local url="$1"
    if command_exists curl; then
        curl -L --connect-timeout 10 --max-time 30 --retry 2 --silent --show-error "$url"
    elif command_exists wget; then
        wget --timeout=10 --tries=2 -qO- "$url"
    else
        return 1
    fi
}

get_latest_python_version() {
    local versions
    versions=$(download_text "https://www.python.org/downloads/" 2>/dev/null || true)
    if [[ -n "$versions" ]]; then
        echo "$versions" | grep -oE 'Python ([0-9]+\.[0-9]+\.[0-9]+)' | head -1 | sed 's/Python //'
        return
    fi
    
    versions=$(download_text "https://raw.githubusercontent.com/python/cpython/main/VERSION" 2>/dev/null || true)
    if [[ -n "$versions" ]]; then
        echo "$versions" | head -1 | tr -d '\n'
        return
    fi
    
    echo "3.13.0"
}

get_latest_node_version() {
    local version
    version=$(download_text "https://nodejs.org/dist/latest/SHASUMS256.txt" 2>/dev/null | grep -E 'node-v[0-9]+\.[0-9]+\.[0-9]+-linux-x64.tar.xz' | head -1 | grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' | sed 's/^v//')
    if [[ -n "$version" ]]; then
        echo "$version"
        return
    fi
    
    version=$(download_text "https://nodejs.org/dist/latest/SHASUMS256.txt" 2>/dev/null | grep -E 'node-v[0-9]+\.[0-9]+\.[0-9]+-linux-arm64.tar.xz' | head -1 | grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' | sed 's/^v//')
    if [[ -n "$version" ]]; then
        echo "$version"
        return
    fi
    
    echo "22.0.0"
}

get_current_python_version() {
    if command_exists python3; then
        python3 --version 2>&1 | awk '{print $2}'
    else
        echo "0.0.0"
    fi
}

get_current_node_version() {
    if command_exists node; then
        node --version 2>&1 | sed 's/^v//'
    else
        echo "0.0.0"
    fi
}

version_less_than() {
    local current="$1"
    local latest="$2"
    
    IFS='.' read -r -a current_parts <<< "$current"
    IFS='.' read -r -a latest_parts <<< "$latest"
    
    for i in 0 1 2; do
        local c=${current_parts[$i]:-0}
        local l=${latest_parts[$i]:-0}
        if [[ "$c" -lt "$l" ]]; then
            return 0
        elif [[ "$c" -gt "$l" ]]; then
            return 1
        fi
    done
    return 1
}

install_basic_packages() {
    log_step "Installing basic packages"
    
    if command -v apt-get > /dev/null 2>&1; then
        echo "Updating package lists..."
        apt-get update -qq 2>/dev/null || true
        
        local packages=(
            python3 python3-pip
            nodejs npm
            git curl wget
            unzip zip vim nano htop tree jq
            build-essential libssl-dev zlib1g-dev
            libbz2-dev libreadline-dev libsqlite3-dev
            llvm libncurses5-dev libncursesw5-dev
            xz-utils tk-dev libffi-dev liblzma-dev
        )
        
        echo "Installing packages..."
        apt-get install -y -qq "${packages[@]}" 2>/dev/null || true
    fi
}

install_pyenv() {
    if [[ "$USE_PYENV" != "1" ]]; then
        log INFO "pyenv integration disabled"
        return 0
    fi
    
    if command_exists pyenv; then
        log INFO "pyenv already installed"
        return 0
    fi
    
    log_step "Installing pyenv (Python version manager)"
    
    local pyenv_dir="$HOME/.pyenv"
    if [[ -d "$pyenv_dir" ]]; then
        log INFO "pyenv directory exists, skipping clone"
    else
        git clone https://github.com/pyenv/pyenv.git "$pyenv_dir" || {
            log WARN "Failed to clone pyenv, trying backup URL"
            git clone https://gitee.com/mirrors/pyenv.git "$pyenv_dir" || {
                log ERROR "Failed to install pyenv"
                return 1
            }
        }
    fi
    
    local bashrc="$HOME/.bashrc"
    if ! grep -q 'export PYENV_ROOT' "$bashrc" 2>/dev/null; then
        cat >> "$bashrc" <<EOF

# Pyenv configuration
export PYENV_ROOT="$HOME/.pyenv"
export PATH="\$PYENV_ROOT/bin:\$PATH"
eval "\$(pyenv init -)"
EOF
        log INFO "Added pyenv to ~/.bashrc"
    fi
    
    export PYENV_ROOT="$HOME/.pyenv"
    export PATH="$PYENV_ROOT/bin:$PATH"
    eval "$(pyenv init -)"
    
    log OK "pyenv installed successfully"
    return 0
}

install_latest_python() {
    if [[ "$USE_PYENV" != "1" ]]; then
        return 0
    fi
    
    local current_version
    current_version=$(get_current_python_version)
    local latest_version
    latest_version=$(get_latest_python_version)
    
    log INFO "Current Python: $current_version, Latest: $latest_version"
    
    if version_less_than "$current_version" "$latest_version"; then
        log_step "Installing Python $latest_version"
        
        if ! command_exists pyenv; then
            log WARN "pyenv not installed, skipping Python update"
            return 1
        fi
        
        pyenv install "$latest_version" --skip-existing || {
            log WARN "Failed to install Python $latest_version"
            return 1
        }
        
        pyenv global "$latest_version" || {
            log WARN "Failed to set Python $latest_version as global"
            return 1
        }
        
        log OK "Python $latest_version installed and set as default"
        return 0
    else
        log INFO "Python is up to date: $current_version"
        return 0
    fi
}

install_nvm() {
    if [[ "$USE_NVM" != "1" ]]; then
        log INFO "nvm integration disabled"
        return 0
    fi
    
    if command_exists nvm; then
        log INFO "nvm already installed"
        return 0
    fi
    
    log_step "Installing nvm (Node.js version manager)"
    
    local nvm_dir="$HOME/.nvm"
    if [[ -d "$nvm_dir" ]]; then
        log INFO "nvm directory exists"
    else
        download_text "https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.0/install.sh" | bash || {
            log WARN "Failed to install nvm from GitHub, trying backup"
            download_text "https://gitee.com/mirrors/nvm/raw/v0.40.0/install.sh" | bash || {
                log ERROR "Failed to install nvm"
                return 1
            }
        }
    fi
    
    export NVM_DIR="$HOME/.nvm"
    [[ -s "$NVM_DIR/nvm.sh" ]] && \. "$NVM_DIR/nvm.sh"
    
    log OK "nvm installed successfully"
    return 0
}

install_latest_node() {
    if [[ "$USE_NVM" != "1" ]]; then
        return 0
    fi
    
    local current_version
    current_version=$(get_current_node_version)
    local latest_version
    latest_version=$(get_latest_node_version)
    
    log INFO "Current Node.js: $current_version, Latest: $latest_version"
    
    if version_less_than "$current_version" "$latest_version"; then
        log_step "Installing Node.js $latest_version"
        
        if ! command_exists nvm; then
            log WARN "nvm not installed, skipping Node.js update"
            return 1
        fi
        
        nvm install "$latest_version" || {
            log WARN "Failed to install Node.js $latest_version"
            return 1
        }
        
        nvm alias default "$latest_version" || {
            log WARN "Failed to set Node.js $latest_version as default"
            return 1
        }
        
        log OK "Node.js $latest_version installed and set as default"
        return 0
    else
        log INFO "Node.js is up to date: $current_version"
        return 0
    fi
}

check_updates() {
    if [[ "$UPDATE_REMINDER" != "1" ]]; then
        return 0
    fi
    
    log_step "Checking for updates"
    
    local python_current
    python_current=$(get_current_python_version)
    local python_latest
    python_latest=$(get_latest_python_version)
    
    local node_current
    node_current=$(get_current_node_version)
    local node_latest
    node_latest=$(get_latest_node_version)
    
    local has_updates=0
    
    if version_less_than "$python_current" "$python_latest"; then
        echo -e "${YELLOW}⚠️  Python update available: $python_current → $python_latest${RESET}"
        has_updates=1
    fi
    
    if version_less_than "$node_current" "$node_latest"; then
        echo -e "${YELLOW}⚠️  Node.js update available: $node_current → $node_latest${RESET}"
        has_updates=1
    fi
    
    if [[ "$has_updates" == "0" ]]; then
        echo -e "${GREEN}✓ All languages are up to date${RESET}"
    fi
}

print_summary() {
    echo ""
    echo -e "${CYAN}═══════════════════════════════════════════════════════${RESET}"
    echo -e "${GREEN}  ApexEngine Container Initialized${RESET}"
    echo -e "${CYAN}═══════════════════════════════════════════════════════${RESET}"
    echo ""
    
    if command_exists python3; then
        local py_ver
        py_ver=$(python3 --version 2>&1 | awk '{print $2}')
        echo -e "  ${YELLOW}Python:${RESET}         $py_ver"
    fi
    
    if command_exists node; then
        local node_ver
        node_ver=$(node --version 2>&1)
        echo -e "  ${YELLOW}Node.js:${RESET}       $node_ver"
    fi
    
    if command_exists git; then
        local git_ver
        git_ver=$(git --version 2>&1 | awk '{print $3}')
        echo -e "  ${YELLOW}Git:${RESET}            $git_ver"
    fi
    
    if command_exists pyenv; then
        echo -e "  ${YELLOW}pyenv:${RESET}          ✓ Installed"
    fi
    
    if command_exists nvm; then
        echo -e "  ${YELLOW}nvm:${RESET}            ✓ Installed"
    fi
    
    echo ""
    echo -e "${CYAN}═══════════════════════════════════════════════════════${RESET}"
    echo ""
}

main() {
    echo ""
    echo -e "${CYAN}╔═══════════════════════════════════════════════════════╗${RESET}"
    echo -e "${CYAN}║          Initializing ApexEngine Container          ║${RESET}"
    echo -e "${CYAN}╚═══════════════════════════════════════════════════════╝${RESET}"
    echo ""
    
    export PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin
    export HOME=/root
    export TERM=xterm-256color
    
    if [ ! -d "/proc" ]; then mkdir -p /proc; fi
    if [ ! -d "/dev" ]; then mkdir -p /dev; fi
    if [ ! -d "/sys" ]; then mkdir -p /sys; fi
    if [ ! -d "/home" ]; then mkdir -p /home; fi
    
    if [ -f /etc/debian_version ]; then
        log INFO "Debian-based system detected"
        install_basic_packages
    fi
    
    if [[ "$AUTO_UPDATE" == "1" ]]; then
        install_pyenv
        install_nvm
        
        install_latest_python
        install_latest_node
    else
        log INFO "Auto-update disabled"
    fi
    
    check_updates
    print_summary
    
    echo "Container initialized successfully"
    echo "Ready for tool execution..."
}

main "$@"
