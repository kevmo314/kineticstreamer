# Build the Go kinetic JNI library (libkinetic.so) for all Android ABIs.
#
# Stages:
#   1. third-party C deps (libusb, OpenSSL, SRT). Driven by third_party/CMakeLists.txt
#      via bash (Git Bash / WSL) — the OpenSSL build pipeline relies on bash + GNU make.
#      Runs only if third_party/output/ is missing or -RebuildDeps is passed.
#   2. Go shared library via `go build -buildmode=c-shared` per ABI (parallel).
#      Outputs to ../jniLibs/<abi>/libkinetic.so plus the third-party .so's alongside.

param(
    [switch]$RebuildDeps,
    [switch]$DepsOnly,
    [switch]$SkipDeps
)

$ErrorActionPreference = "Stop"

if ($DepsOnly -and $SkipDeps) {
    Write-Error "-DepsOnly and -SkipDeps cannot be used together"
    exit 1
}

# ---- shared environment ----------------------------------------------------
$SCRIPT_DIR = $PSScriptRoot
$THIRD_PARTY_DIR = Join-Path $SCRIPT_DIR "third_party\output"
$JNI_LIBS_DIR = Join-Path (Split-Path $SCRIPT_DIR -Parent) "jniLibs"
$INCLUDE_DIR = Join-Path $SCRIPT_DIR "include"
$ANDROID_API = 24
$NDK_HOST_TAG = "windows-x86_64"
$ABIS = @(
    @{ABI="arm64-v8a";   GOARCH="arm64"; GOARM="";  CC_PREFIX="aarch64-linux-android"},
    @{ABI="armeabi-v7a"; GOARCH="arm";   GOARM="7"; CC_PREFIX="armv7a-linux-androideabi"},
    @{ABI="x86";         GOARCH="386";   GOARM="";  CC_PREFIX="i686-linux-android"},
    @{ABI="x86_64";      GOARCH="amd64"; GOARM="";  CC_PREFIX="x86_64-linux-android"}
)

# Resolve ANDROID_NDK from environment, ANDROID_HOME, or local.properties.
function Resolve-AndroidNdk {
    if ($env:ANDROID_NDK) { return $env:ANDROID_NDK }

    $androidHome = $env:ANDROID_HOME
    if (-not $androidHome) {
        $localProps = Join-Path (Resolve-Path (Join-Path $SCRIPT_DIR "..\..\..\..")) "local.properties"
        if (Test-Path $localProps) {
            $line = Get-Content $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
            if ($line) { $androidHome = ($line -replace '^sdk\.dir=', '').Trim() }
        }
    }
    if (-not $androidHome) {
        $androidHome = "$env:LOCALAPPDATA\Android\Sdk"
    }

    $ndkRoot = Join-Path $androidHome "ndk"
    if (-not (Test-Path $ndkRoot)) {
        Write-Error "Android NDK not found. Set ANDROID_NDK or install one under $ndkRoot"
        exit 1
    }

    # Pick highest-numbered NDK
    $ndk = Get-ChildItem $ndkRoot -Directory |
        Sort-Object { [version]($_.Name -replace '[^\d.].*$','') } -ErrorAction SilentlyContinue |
        Select-Object -Last 1
    if (-not $ndk) {
        $ndk = Get-ChildItem $ndkRoot -Directory | Sort-Object Name | Select-Object -Last 1
    }
    return $ndk.FullName
}

$ANDROID_NDK = Resolve-AndroidNdk
$NDK_BIN = Join-Path $ANDROID_NDK "toolchains\llvm\prebuilt\$NDK_HOST_TAG\bin"

Write-Host "Using ANDROID_NDK=$ANDROID_NDK"
Write-Host "Using NDK_HOST_TAG=$NDK_HOST_TAG"
Write-Host ""

# ---- stage 1: third-party C deps (delegated to bash) ----------------------
function Test-DepsBuilt {
    foreach ($a in $ABIS) {
        foreach ($lib in @("libusb-1.0.so","libsrt.so","librist.so")) {
            if (-not (Test-Path (Join-Path $THIRD_PARTY_DIR "$($a.ABI)\lib\$lib"))) { return $false }
        }
        foreach ($lib in @("libcrypto.a","libssl.a")) {
            if (-not (Test-Path (Join-Path $THIRD_PARTY_DIR "$($a.ABI)\lib\$lib"))) { return $false }
        }
    }
    return $true
}

if ($SkipDeps -and -not (Test-DepsBuilt)) {
    Write-Error "Third-party dependencies are missing. Run without -SkipDeps or build them first."
    exit 1
}

if (-not $SkipDeps -and ($RebuildDeps -or -not (Test-DepsBuilt))) {
    $bashCmd = Get-Command bash -ErrorAction SilentlyContinue
    $bash = if ($bashCmd) { $bashCmd.Source } else { $null }
    if (-not $bash) {
        Write-Error @"
Third-party C dependencies are not built and bash was not found in PATH.
The third-party stage requires bash (Git Bash or WSL) because OpenSSL's
build pipeline uses bash + GNU make.

Options:
  - Install Git for Windows (provides bash) and re-run.
  - Run from a WSL shell: bash ./build.sh --rebuild-deps
"@
        exit 1
    }
    Write-Host "Building third-party C dependencies via bash..."
    $bashArgs = @()
    if ($RebuildDeps) { $bashArgs += "--rebuild-deps" }
    if ($DepsOnly) { $bashArgs += "--deps-only" }
    & $bash (Join-Path $SCRIPT_DIR "build.sh") @bashArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Third-party build failed"
        exit 1
    }
}

if ($DepsOnly) {
    Write-Host "Third-party dependencies built to: $THIRD_PARTY_DIR"
    exit 0
}

# ---- stage 2: Go shared library --------------------------------------------
if (-not (Test-Path $INCLUDE_DIR)) { New-Item -ItemType Directory -Path $INCLUDE_DIR | Out-Null }
foreach ($hdr in @("libusb-1.0","srt","openssl")) {
    $dst = Join-Path $INCLUDE_DIR $hdr
    $src = Join-Path $THIRD_PARTY_DIR "arm64-v8a\include\$hdr"
    if (-not (Test-Path $dst) -and (Test-Path $src)) {
        Copy-Item -Path $src -Destination $dst -Recurse -Force
    }
}

# Resolve the actual clang.exe / clang++.exe paths for an ABI.
# NDK ships .cmd wrappers on some setups and bare files on others.
function Resolve-ClangPath([string]$basePath) {
    foreach ($ext in @(".cmd",".exe","")) {
        $p = "$basePath$ext"
        if (Test-Path $p) { return $p }
    }
    return $null
}

# Script block run in parallel jobs for each ABI.
$buildJob = {
    param($arch, $scriptDir, $jniLibsDir, $thirdPartyDir, $includeDir, $ndkBin, $androidApi)

    $env:GOOS = "android"
    $env:GOARCH = $arch.GOARCH
    if ($arch.GOARM) { $env:GOARM = $arch.GOARM }
    $env:CGO_ENABLED = "1"

    $base = Join-Path $ndkBin "$($arch.CC_PREFIX)$androidApi-clang"
    $cc = $null; $cxx = $null
    foreach ($ext in @(".cmd",".exe","")) {
        if (-not $cc  -and (Test-Path "$base$ext"))   { $cc  = "$base$ext" }
        if (-not $cxx -and (Test-Path "$base++$ext")) { $cxx = "$base++$ext" }
    }
    if (-not $cc -or -not $cxx) { throw "Could not locate NDK clang for $($arch.ABI) at $base" }

    $env:CC = $cc
    $env:CXX = $cxx

    # Go expects forward-slash paths in CGO flags.
    $tpForward = ($thirdPartyDir -replace '\\','/') + "/$($arch.ABI)"
    $incForward = $includeDir -replace '\\','/'
    $env:CGO_CFLAGS  = "-I$incForward -I$tpForward/include -D__ANDROID__"
    $env:CGO_LDFLAGS = "-L$tpForward/lib -lusb-1.0 -lsrt -lssl -lcrypto -lrist -ldl -static-libstdc++"

    $archOut = Join-Path $jniLibsDir $arch.ABI
    if (-not (Test-Path $archOut)) { New-Item -ItemType Directory -Path $archOut -Force | Out-Null }
    $outPath = ($archOut -replace '\\','/') + "/libkinetic.so"

    & go build -C (Join-Path $scriptDir "cmd\jni") -buildmode=c-shared `
        "-ldflags=-s -w -checklinkname=0 -extldflags '-Wl,-soname,libkinetic.so -Wl,-z,max-page-size=16384'" `
        -o $outPath `
        . 2>&1
    if ($LASTEXITCODE -ne 0) { throw "go build failed for $($arch.ABI)" }

    foreach ($lib in @("libusb-1.0.so","libsrt.so","librist.so")) {
        Copy-Item (Join-Path $thirdPartyDir "$($arch.ABI)\lib\$lib") $archOut -Force
    }
    foreach ($lib in @("libcrypto.so","libssl.so")) {
        $path = Join-Path $thirdPartyDir "$($arch.ABI)\lib\$lib"
        if (Test-Path $path) { Copy-Item $path $archOut -Force }
    }
    return @{Success=$true; ABI=$arch.ABI}
}

Write-Host "Building libkinetic.so for all ABIs..."
$jobs = @()
foreach ($arch in $ABIS) {
    $jobs += Start-Job -ScriptBlock $buildJob `
        -ArgumentList $arch, $SCRIPT_DIR, $JNI_LIBS_DIR, $THIRD_PARTY_DIR, $INCLUDE_DIR, $NDK_BIN, $ANDROID_API
}

$failed = @()
foreach ($job in $jobs) {
    $output = Receive-Job -Job $job -Wait -ErrorAction Continue 2>&1
    foreach ($item in $output) {
        if ($item -is [hashtable] -and $item.Success) {
            Write-Host "[$($item.ABI)] Done"
        } else {
            Write-Host $item
        }
    }
    if ($job.State -ne "Completed") { $failed += $job.Id }
    Remove-Job -Job $job -Force
}

# Generated by `go build -buildmode=c-shared`; not used.
$header = Join-Path $SCRIPT_DIR "cmd\jni\libkinetic.h"
if (Test-Path $header) { Remove-Item $header }

if ($failed.Count -gt 0) {
    Write-Error "One or more native builds failed"
    exit 1
}

Write-Host ""
Write-Host "Native libraries built to: $JNI_LIBS_DIR"
