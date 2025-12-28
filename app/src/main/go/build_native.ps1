# PowerShell build script for Windows
# Builds the Go kinetic library with native JNI for Android

$ErrorActionPreference = "Stop"

# Record start time for build duration
$startTime = Get-Date

# Get the directory where this script is located
$SCRIPT_DIR = $PSScriptRoot

# Set up paths
$THIRD_PARTY_DIR = Join-Path (Join-Path $SCRIPT_DIR "third_party") "output"
$OUTPUT_DIR = Join-Path (Split-Path $SCRIPT_DIR -Parent) "jniLibs"

# Use the NDK from Android SDK
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

$ANDROID_API = 24

Write-Host "Using Android NDK: $ANDROID_NDK"

# Check if third_party libraries exist
if (-not (Test-Path $THIRD_PARTY_DIR)) {
    Write-Error "Error: Third party libraries not found at $THIRD_PARTY_DIR"
    Write-Error "Please run ./build_third_party.ps1 first"
    exit 1
}

# Create include directory
$INCLUDE_DIR = Join-Path $SCRIPT_DIR "include"
if (-not (Test-Path $INCLUDE_DIR)) {
    New-Item -ItemType Directory -Path $INCLUDE_DIR | Out-Null
}

# Copy headers (for cross-platform compatibility)
$headerDirs = @("libusb-1.0", "srt", "openssl")
foreach ($headerDir in $headerDirs) {
    $srcPath = Join-Path (Join-Path (Join-Path $THIRD_PARTY_DIR "arm64-v8a") "include") $headerDir
    $dstPath = Join-Path $INCLUDE_DIR $headerDir
    
    if (-not (Test-Path $dstPath)) {
        if (Test-Path $srcPath) {
            Write-Host "Copying $headerDir headers..."
            Copy-Item -Path $srcPath -Destination $dstPath -Recurse -Force
        } else {
            Write-Warning "$headerDir headers not found at $srcPath"
        }
    }
}

# Function to build for a specific architecture (used for sequential fallback)
function Build-Arch {
    param(
        [string]$ARCH,
        [string]$GOARCH,
        [string]$GOARM,
        [string]$CC_PREFIX,
        [string]$PLATFORM
    )
    
    Write-Host "Building for $ARCH..."
    
    # Set up output directory
    $archOutputDir = Join-Path $OUTPUT_DIR $ARCH
    if (-not (Test-Path $archOutputDir)) {
        New-Item -ItemType Directory -Path $archOutputDir -Force | Out-Null
    }
    
    # Set up environment variables
    $env:GOOS = "android"
    $env:GOARCH = $GOARCH
    if ($GOARM) {
        $env:GOARM = $GOARM
    }
    $env:CGO_ENABLED = "1"
    
    # Use forward slashes for paths in CGO flags (Go expects Unix-style paths)
    $ndkPath = $ANDROID_NDK -replace '\\', '/'
    $includePath = $INCLUDE_DIR -replace '\\', '/'
    $thirdPartyPath = "$THIRD_PARTY_DIR/$ARCH" -replace '\\', '/'
    
    # Try different compiler executable patterns
    $ccBase = "$ndkPath/toolchains/llvm/prebuilt/windows-x86_64/bin/${CC_PREFIX}${ANDROID_API}-clang"
    $cxxBase = "$ndkPath/toolchains/llvm/prebuilt/windows-x86_64/bin/${CC_PREFIX}${ANDROID_API}-clang++"
    
    # Check for different extensions (.cmd, .exe, or no extension)
    $ccVariants = @("${ccBase}.cmd", "${ccBase}.exe", $ccBase)
    $cxxVariants = @("${cxxBase}.cmd", "${cxxBase}.exe", $cxxBase)
    
    $env:CC = $null
    foreach ($variant in $ccVariants) {
        if (Test-Path $variant) {
            $env:CC = $variant
            break
        }
    }
    
    if (-not $env:CC) {
        Write-Error "Could not find C compiler. Tried: $($ccVariants -join ', ')"
        throw "C compiler not found"
    }
    
    $env:CXX = $null
    foreach ($variant in $cxxVariants) {
        if (Test-Path $variant) {
            $env:CXX = $variant
            break
        }
    }
    
    if (-not $env:CXX) {
        Write-Error "Could not find C++ compiler. Tried: $($cxxVariants -join ', ')"
        throw "C++ compiler not found"
    }
    
    $env:CGO_CFLAGS = "-I$includePath -I$thirdPartyPath/include -D__ANDROID__"
    $env:CGO_LDFLAGS = "-L$thirdPartyPath/lib -lusb-1.0 -lsrt -lcrypto -lssl"
    
    # Build the shared library
    $jniDir = Join-Path (Join-Path $SCRIPT_DIR "cmd") "jni"
    Push-Location $jniDir
    try {
        # Ensure output path uses forward slashes for Go
        $outputPath = "$archOutputDir/libkinetic.so" -replace '\\', '/'
        
        Write-Host "Building with:"
        Write-Host "  CC: $env:CC"
        Write-Host "  Output: $outputPath"
        
        # Run go build
        & go build -buildmode=c-shared `
            "-ldflags=-s -w -extldflags '-Wl,-soname,libkinetic.so'" `
            -o "$outputPath" `
            .
        
        if ($LASTEXITCODE -ne 0) {
            throw "Go build failed with exit code $LASTEXITCODE"
        }
        
        # Verify the output file was created
        $outputFile = Join-Path $archOutputDir "libkinetic.so"
        if (-not (Test-Path $outputFile)) {
            throw "Output file was not created: $outputFile"
        }
        
        Write-Host "Successfully created $ARCH/libkinetic.so"
    }
    catch {
        throw $_
    }
    finally {
        Pop-Location
    }
}

# Build for all architectures (parallel for faster builds)
Write-Host "Starting parallel builds for all architectures..."
Write-Host ""

$architectures = @(
    @{ARCH="arm64-v8a"; GOARCH="arm64"; GOARM=""; CC_PREFIX="aarch64-linux-android"; PLATFORM="android-arm64"},
    @{ARCH="armeabi-v7a"; GOARCH="arm"; GOARM="7"; CC_PREFIX="armv7a-linux-androideabi"; PLATFORM="android-arm"},
    @{ARCH="x86"; GOARCH="386"; GOARM=""; CC_PREFIX="i686-linux-android"; PLATFORM="android-x86"},
    @{ARCH="x86_64"; GOARCH="amd64"; GOARM=""; CC_PREFIX="x86_64-linux-android"; PLATFORM="android-x86_64"}
)

# Create a script block that will run in parallel jobs
$buildJob = {
    param(
        $arch,
        $scriptDir,
        $outputDir,
        $thirdPartyDir,
        $includeDir,
        $androidNdk,
        $androidApi
    )
    
    # Recreate the Build-Arch function within the job
    function Build-Arch {
        param(
            [string]$ARCH,
            [string]$GOARCH,
            [string]$GOARM,
            [string]$CC_PREFIX,
            [string]$PLATFORM
        )
        
        Write-Output "Building for $ARCH..."
        
        # Set up output directory
        $archOutputDir = Join-Path $outputDir $ARCH
        if (-not (Test-Path $archOutputDir)) {
            New-Item -ItemType Directory -Path $archOutputDir -Force | Out-Null
        }
        
        # Set up environment variables
        $env:GOOS = "android"
        $env:GOARCH = $GOARCH
        if ($GOARM) {
            $env:GOARM = $GOARM
        }
        $env:CGO_ENABLED = "1"
        
        # Use forward slashes for paths in CGO flags (Go expects Unix-style paths)
        $ndkPath = $androidNdk -replace '\\', '/'
        $includePath = $includeDir -replace '\\', '/'
        $thirdPartyPath = "$thirdPartyDir/$ARCH" -replace '\\', '/'
        
        # Try different compiler executable patterns
        $ccBase = "$ndkPath/toolchains/llvm/prebuilt/windows-x86_64/bin/${CC_PREFIX}${androidApi}-clang"
        $cxxBase = "$ndkPath/toolchains/llvm/prebuilt/windows-x86_64/bin/${CC_PREFIX}${androidApi}-clang++"
        
        # Check for different extensions (.cmd, .exe, or no extension)
        $ccVariants = @("${ccBase}.cmd", "${ccBase}.exe", $ccBase)
        $cxxVariants = @("${cxxBase}.cmd", "${cxxBase}.exe", $cxxBase)
        
        $env:CC = $null
        foreach ($variant in $ccVariants) {
            if (Test-Path $variant) {
                $env:CC = $variant
                break
            }
        }
        
        if (-not $env:CC) {
            throw "Could not find C compiler. Tried: $($ccVariants -join ', ')"
        }
        
        $env:CXX = $null
        foreach ($variant in $cxxVariants) {
            if (Test-Path $variant) {
                $env:CXX = $variant
                break
            }
        }
        
        if (-not $env:CXX) {
            throw "Could not find C++ compiler. Tried: $($cxxVariants -join ', ')"
        }
        
        $env:CGO_CFLAGS = "-I$includePath -I$thirdPartyPath/include -D__ANDROID__"
        $env:CGO_LDFLAGS = "-L$thirdPartyPath/lib -lusb-1.0 -lsrt -lcrypto -lssl"
        
        # Build the shared library
        $jniDir = Join-Path (Join-Path $scriptDir "cmd") "jni"
        Push-Location $jniDir
        try {
            # Ensure output path uses forward slashes for Go
            $outputPath = "$archOutputDir/libkinetic.so" -replace '\\', '/'
            
            Write-Output "Building with:"
            Write-Output "  CC: $env:CC"
            Write-Output "  Output: $outputPath"
            
            # Run go build (capture all output)
            $buildOutput = & go build -buildmode=c-shared `
                "-ldflags=-s -w -extldflags '-Wl,-soname,libkinetic.so'" `
                -o "$outputPath" `
                . 2>&1
            
            # Always show the build output for debugging
            if ($buildOutput) {
                Write-Output "Build output for ${ARCH}:"
                $buildOutput | ForEach-Object { Write-Output "  $_" }
            }
            
            # Check for build failure
            if ($LASTEXITCODE -ne 0) {
                # Filter for real errors
                $realErrors = $buildOutput | Where-Object {
                    $_ -and ($_.ToString() -match "error:" -or $_.ToString() -match "undefined reference" -or $_.ToString() -match "cannot find")
                }
                
                if ($realErrors) {
                    throw "Go build failed with errors: $($realErrors -join '; ')"
                }
                
                # Check if output file was created despite non-zero exit code
                $outputFile = Join-Path $archOutputDir "libkinetic.so"
                if (-not (Test-Path $outputFile)) {
                    throw "Go build failed - exit code $LASTEXITCODE and no output file created. Full output: $($buildOutput -join '; ')"
                }
                
                Write-Output "Warning: Build had non-zero exit code $LASTEXITCODE but output file was created"
            }
            
            # Verify the output file was created
            $outputFile = Join-Path $archOutputDir "libkinetic.so"
            if (-not (Test-Path $outputFile)) {
                throw "Output file was not created: $outputFile"
            }
            
            Write-Output "Successfully created $ARCH/libkinetic.so"
        }
        catch {
            throw $_
        }
        finally {
            Pop-Location
        }
    }
    
    try {
        Build-Arch @arch
        return @{Success = $true; Arch = $arch.ARCH; Message = "Successfully built $($arch.ARCH)"}
    }
    catch {
        $errorDetails = $_.Exception.Message
        if ($_.Exception.InnerException) {
            $errorDetails += "; Inner: " + $_.Exception.InnerException.Message
        }
        return @{Success = $false; Arch = $arch.ARCH; Message = "Failed to build $($arch.ARCH): $errorDetails"; Error = $_}
    }
}

# Start parallel jobs
$jobs = @()
foreach ($arch in $architectures) {
    Write-Host "Starting build job for $($arch.ARCH)..." -ForegroundColor Cyan
    $job = Start-Job -ScriptBlock $buildJob -ArgumentList $arch, $SCRIPT_DIR, $OUTPUT_DIR, $THIRD_PARTY_DIR, $INCLUDE_DIR, $ANDROID_NDK, $ANDROID_API
    $jobs += $job
}

# Wait for all jobs to complete with progress updates
Write-Host ""
Write-Host "Waiting for all build jobs to complete..." -ForegroundColor Yellow
Write-Host ""

$completedJobs = 0
$totalJobs = $jobs.Count

while ($jobs | Where-Object { $_.State -eq 'Running' }) {
    $running = ($jobs | Where-Object { $_.State -eq 'Running' }).Count
    $completed = $totalJobs - $running
    
    if ($completed -gt $completedJobs) {
        $completedJobs = $completed
        Write-Host "Progress: $completedJobs/$totalJobs architectures completed" -ForegroundColor Cyan
    }
    
    Start-Sleep -Seconds 2
}

Write-Host ""

# Give jobs a moment to finish writing output
Start-Sleep -Seconds 1

Write-Host "All build jobs completed. Collecting results..." -ForegroundColor Yellow
Write-Host ""

# Collect results
$successCount = 0
$failedArchs = @()
$processedArchs = @()

foreach ($job in $jobs) {
    try {
        # Get job output, suppressing the GOPATH/GOROOT warning
        $allOutput = @()
        $result = $null
        
        try {
            # Get all job output including errors
            $jobData = Receive-Job -Job $job -ErrorAction Continue -WarningAction Continue 2>&1
            
            # Separate result hashtable from output
            foreach ($item in $jobData) {
                if ($item -is [hashtable] -and $item.ContainsKey('Success')) {
                    $result = $item
                } else {
                    $allOutput += $item
                }
            }
            
            # Check for any errors in the job
            if ($job.State -eq 'Failed') {
                Write-Host "Job failed with state: Failed" -ForegroundColor Red
                $jobError = $job.ChildJobs[0].Error
                if ($jobError) {
                    Write-Host "Job error: $jobError" -ForegroundColor Red
                }
            }
        }
        catch {
            Write-Host "Error receiving job output: $_" -ForegroundColor Red
            Write-Host "Exception type: $($_.Exception.GetType().FullName)" -ForegroundColor Red
        }
        
        # Display all output for debugging
        if ($allOutput) {
            Write-Host "Build output:" -ForegroundColor Gray
            $allOutput | ForEach-Object { 
                $line = $_.ToString()
                if ($line -match "error:" -or $line -match "undefined reference" -or $line -match "cannot find") {
                    Write-Host "  $_" -ForegroundColor Red
                } elseif ($line -match "warning:" -or $line -match "GOPATH and GOROOT") {
                    Write-Host "  $_" -ForegroundColor Yellow
                } else {
                    Write-Host "  $_" -ForegroundColor Gray
                }
            }
        }
        
        # Process result
        if ($result) {
            $processedArchs += $result.Arch
            if ($result.Success) {
                $successCount++
                Write-Host $result.Message -ForegroundColor Green
            }
            else {
                $failedArchs += $result.Arch
                Write-Host $result.Message -ForegroundColor Red
                if ($result.Error) {
                    Write-Host "Stack trace: $($result.Error.ScriptStackTrace)" -ForegroundColor Red
                }
            }
        }
        else {
            Write-Host "Could not determine result for job (GOPATH warning suppressed)" -ForegroundColor Yellow
        }
    }
    catch {
        Write-Host "Error collecting job results: $_" -ForegroundColor Yellow
    }
    finally {
        Write-Host ""
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    }
}

# Don't auto-verify based on file existence - require explicit success from job
# Check for any unprocessed architectures (shouldn't happen but log if it does)
$unprocessedArchs = @()
foreach ($arch in $architectures) {
    if ($arch.ARCH -notin $processedArchs) {
        $unprocessedArchs += $arch.ARCH
        Write-Host "WARNING: Architecture $($arch.ARCH) was not processed by any job" -ForegroundColor Yellow
        $failedArchs += $arch.ARCH
    }
}

if ($unprocessedArchs.Count -gt 0) {
    Write-Host "Unprocessed architectures: $($unprocessedArchs -join ', ')" -ForegroundColor Yellow
    Write-Host ""
}

if ($failedArchs.Count -gt 0) {
    Write-Host ""
    Write-Error "Build failed for: $($failedArchs -join ', ')"
    Write-Host "Successfully built: $successCount of $($architectures.Count) architectures"
    exit 1
}

# Calculate and display build time
$endTime = Get-Date
if ($startTime) {
    $buildTime = $endTime - $startTime
    Write-Host "Build time: $($buildTime.ToString('mm\:ss'))" -ForegroundColor Cyan
}

Write-Host "Build completed successfully!"
Write-Host ""
Write-Host "Native libraries built to: $OUTPUT_DIR"
Write-Host "You can now build the Android app with Gradle."

# Clean up build artifacts if needed
$headerFile = Join-Path (Join-Path (Join-Path $SCRIPT_DIR "cmd") "jni") "libkinetic.h"
if (Test-Path $headerFile) {
    Remove-Item $headerFile
}

exit 0