# PowerShell script to build third-party libraries for Windows
# Builds libusb, openssl, and srt for Android

$ErrorActionPreference = "Stop"

# Get the directory where this script is located
$SCRIPT_DIR = $PSScriptRoot

# Android NDK setup
if ($env:ANDROID_NDK) {
    $ANDROID_NDK = $env:ANDROID_NDK
} else {
    # Try common Windows locations
    $possibleNDKPaths = @(
        "$env:LOCALAPPDATA\Android\Sdk\ndk\27.0.12077973",
        "$env:LOCALAPPDATA\Android\Sdk\ndk\26.1.10909125",
        "C:\Android\android-ndk-r26d",
        "$env:USERPROFILE\AppData\Local\Android\Sdk\ndk\27.0.12077973"
    )
    
    foreach ($path in $possibleNDKPaths) {
        if (Test-Path $path) {
            $ANDROID_NDK = $path
            break
        }
    }
    
    if (-not $ANDROID_NDK) {
        Write-Error "Android NDK not found. Please set ANDROID_NDK environment variable"
        exit 1
    }
}

Write-Host "Using Android NDK: $ANDROID_NDK"

# Check for required tools
$requiredCommands = @("cmake", "git")
foreach ($cmd in $requiredCommands) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Write-Error "$cmd is required but not found in PATH"
        Write-Host "Please install:"
        Write-Host "  - CMake: https://cmake.org/download/"
        Write-Host "  - Git: https://git-scm.com/download/win"
        exit 1
    }
}

# Check for ninja
$NINJA_AVAILABLE = Get-Command "ninja" -ErrorAction SilentlyContinue
if ($NINJA_AVAILABLE) {
    $CMAKE_GENERATOR = "Ninja"
    Write-Host "Using Ninja generator for faster builds"
} else {
    $CMAKE_GENERATOR = "MinGW Makefiles"
    Write-Host "Using MinGW Makefiles generator (install Ninja for faster builds)"
    
    # Check if MinGW make is available
    if (-not (Get-Command "mingw32-make" -ErrorAction SilentlyContinue)) {
        Write-Error "Neither Ninja nor MinGW Make found. Please install one of them."
        Write-Host "  - Ninja: https://ninja-build.org/"
        Write-Host "  - MinGW: https://www.mingw-w64.org/downloads/"
        exit 1
    }
}

$THIRD_PARTY_DIR = "$SCRIPT_DIR\third_party"
$OUTPUT_DIR = "$THIRD_PARTY_DIR\output"

# Create directories
if (-not (Test-Path $THIRD_PARTY_DIR)) {
    New-Item -ItemType Directory -Path $THIRD_PARTY_DIR | Out-Null
}

Set-Location $THIRD_PARTY_DIR

# Architecture configurations
$ARCHITECTURES = @(
    @{name="arm64-v8a"; abi="arm64-v8a"; arch="arm64"; triple="aarch64-linux-android"},
    @{name="armeabi-v7a"; abi="armeabi-v7a"; arch="armv7a"; triple="armv7a-linux-androideabi"},
    @{name="x86"; abi="x86"; arch="i686"; triple="i686-linux-android"},
    @{name="x86_64"; abi="x86_64"; arch="x86_64"; triple="x86_64-linux-android"}
)

# Clone repositories if not present
Write-Host "Checking repositories..."

if (-not (Test-Path "libusb")) {
    Write-Host "Cloning libusb..."
    & git clone https://github.com/libusb/libusb.git
    if ($LASTEXITCODE -ne 0) { 
        Write-Error "Failed to clone libusb"
        exit 1 
    }
}

if (-not (Test-Path "openssl")) {
    Write-Host "Cloning OpenSSL..."
    & git clone https://github.com/openssl/openssl.git
    if ($LASTEXITCODE -ne 0) { 
        Write-Error "Failed to clone OpenSSL"
        exit 1 
    }
    Push-Location openssl
    & git checkout OpenSSL_1_1_1w
    if ($LASTEXITCODE -ne 0) { 
        Pop-Location
        Write-Error "Failed to checkout OpenSSL version"
        exit 1 
    }
    Pop-Location
}

if (-not (Test-Path "srt")) {
    Write-Host "Cloning SRT..."
    & git clone https://github.com/Haivision/srt.git
    if ($LASTEXITCODE -ne 0) { 
        Write-Error "Failed to clone SRT"
        exit 1 
    }
}

# Function to build libraries for an architecture
function Build-Libraries {
    param(
        [hashtable]$arch
    )
    
    $ABI = $arch.abi
    $ARCH = $arch.arch
    $TRIPLE = $arch.triple
    $ARCH_OUTPUT = "$OUTPUT_DIR\$ABI"
    
    Write-Host "========================================="
    Write-Host "Building for $ABI"
    Write-Host "========================================="
    
    # Create output directory
    if (-not (Test-Path $ARCH_OUTPUT)) {
        New-Item -ItemType Directory -Path $ARCH_OUTPUT -Force | Out-Null
        New-Item -ItemType Directory -Path "$ARCH_OUTPUT\lib" -Force | Out-Null
        New-Item -ItemType Directory -Path "$ARCH_OUTPUT\include" -Force | Out-Null
    }
    
    # Build libusb
    Write-Host "Building libusb for $ABI..."
    $LIBUSB_BUILD = "$THIRD_PARTY_DIR\build-libusb-$ABI"
    if (Test-Path $LIBUSB_BUILD) {
        Remove-Item -Recurse -Force $LIBUSB_BUILD
    }
    New-Item -ItemType Directory -Path $LIBUSB_BUILD | Out-Null
    
    Push-Location $LIBUSB_BUILD
    try {
        & cmake "$THIRD_PARTY_DIR\libusb" `
            -G $CMAKE_GENERATOR `
            -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK\build\cmake\android.toolchain.cmake" `
            -DANDROID_ABI=$ABI `
            -DANDROID_PLATFORM=android-24 `
            -DCMAKE_BUILD_TYPE=Release `
            -DCMAKE_INSTALL_PREFIX=$ARCH_OUTPUT
        
        if ($LASTEXITCODE -ne 0) {
            throw "CMake configuration failed for libusb"
        }
        
        if ($CMAKE_GENERATOR -eq "Ninja") {
            & ninja
            if ($LASTEXITCODE -ne 0) { throw "Build failed" }
            & ninja install
            if ($LASTEXITCODE -ne 0) { throw "Install failed" }
        } else {
            & mingw32-make
            if ($LASTEXITCODE -ne 0) { throw "Build failed" }
            & mingw32-make install
            if ($LASTEXITCODE -ne 0) { throw "Install failed" }
        }
        
        Write-Host "libusb built successfully for $ABI"
    }
    catch {
        Write-Error "libusb build failed for $ABI : $_"
        throw
    }
    finally {
        Pop-Location
    }
    
    # Build OpenSSL
    Write-Host "Building OpenSSL for $ABI..."
    Set-Location "$THIRD_PARTY_DIR\openssl"
    
    # Configure OpenSSL (this part might need adjustments for Windows)
    $env:ANDROID_NDK_ROOT = $ANDROID_NDK
    $env:PATH = "$ANDROID_NDK\toolchains\llvm\prebuilt\windows-x86_64\bin;$env:PATH"
    
    $opensslTarget = switch ($ABI) {
        "arm64-v8a" { "android-arm64" }
        "armeabi-v7a" { "android-arm" }
        "x86" { "android-x86" }
        "x86_64" { "android-x86_64" }
    }
    
    # Note: OpenSSL build on Windows is complex and might require Perl and additional setup
    Write-Warning "OpenSSL building on Windows requires Perl and might need manual configuration"
    Write-Host "You may need to build OpenSSL separately or use prebuilt binaries"
    
    # Copy prebuilt OpenSSL if available (you'll need to provide these)
    $prebuiltOpenSSL = "$SCRIPT_DIR\prebuilt\openssl\$ABI"
    if (Test-Path $prebuiltOpenSSL) {
        Write-Host "Using prebuilt OpenSSL for $ABI"
        Copy-Item -Path "$prebuiltOpenSSL\*" -Destination $ARCH_OUTPUT -Recurse -Force
    }
    
    # Build SRT
    Write-Host "Building SRT for $ABI..."
    $SRT_BUILD = "$THIRD_PARTY_DIR\build-srt-$ABI"
    if (Test-Path $SRT_BUILD) {
        Remove-Item -Recurse -Force $SRT_BUILD
    }
    New-Item -ItemType Directory -Path $SRT_BUILD | Out-Null
    
    Push-Location $SRT_BUILD
    try {
        & cmake "$THIRD_PARTY_DIR\srt" `
            -G $CMAKE_GENERATOR `
            -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK\build\cmake\android.toolchain.cmake" `
            -DANDROID_ABI=$ABI `
            -DANDROID_PLATFORM=android-24 `
            -DCMAKE_BUILD_TYPE=Release `
            -DCMAKE_INSTALL_PREFIX=$ARCH_OUTPUT `
            -DENABLE_SHARED=OFF `
            -DENABLE_STATIC=ON `
            -DENABLE_APPS=OFF `
            -DUSE_ENCLIB=openssl `
            -DOPENSSL_ROOT_DIR=$ARCH_OUTPUT `
            -DOPENSSL_LIBRARIES="$ARCH_OUTPUT\lib" `
            -DOPENSSL_INCLUDE_DIR="$ARCH_OUTPUT\include"
        
        if ($LASTEXITCODE -ne 0) {
            throw "CMake configuration failed for SRT"
        }
        
        if ($CMAKE_GENERATOR -eq "Ninja") {
            & ninja
            if ($LASTEXITCODE -ne 0) { throw "Build failed" }
            & ninja install
            if ($LASTEXITCODE -ne 0) { throw "Install failed" }
        } else {
            & mingw32-make
            if ($LASTEXITCODE -ne 0) { throw "Build failed" }
            & mingw32-make install
            if ($LASTEXITCODE -ne 0) { throw "Install failed" }
        }
        
        Write-Host "SRT built successfully for $ABI"
    }
    catch {
        Write-Error "SRT build failed for $ABI : $_"
        throw
    }
    finally {
        Pop-Location
    }
    
    Write-Host "Successfully built libraries for $ABI"
}

# Build for all architectures (sequential for Windows)
Write-Host "========================================="
Write-Host "Building libraries for all architectures (sequential)"
Write-Host "========================================="
Write-Host ""

$successCount = 0
$failedArchs = @()

foreach ($arch in $ARCHITECTURES) {
    try {
        Build-Libraries $arch
        $successCount++
        Write-Host "✓ Successfully built all libraries for $($arch.name)" -ForegroundColor Green
        Write-Host ""
    }
    catch {
        Write-Host "✗ Failed to build libraries for $($arch.name)" -ForegroundColor Red
        $failedArchs += $arch.name
        Write-Host ""
    }
}

Write-Host "========================================="
if ($failedArchs.Count -gt 0) {
    Write-Error "Build failed for: $($failedArchs -join ', ')"
    Write-Host "Successfully built: $successCount of $($ARCHITECTURES.Count) architectures"
    exit 1
} else {
    Write-Host "All third-party libraries built successfully!" -ForegroundColor Green
    Write-Host "Output directory: $OUTPUT_DIR"
    Write-Host "========================================="
}

# Return to original directory
Set-Location $SCRIPT_DIR

exit 0