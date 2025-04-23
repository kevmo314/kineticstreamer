#!/bin/bash
set -e

# Default values
ANDROID_API=24
OUTPUT_DIR=$1

# Check if OUTPUT_DIR is set
if [ -z "$OUTPUT_DIR" ]; then
  echo "Error: Output directory not specified."
  echo "Usage: $0 <output_directory>"
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

# Change to the Go package directory
cd $(dirname "$0")

# Check if SRT libraries have been built for all the required architectures
for ABI in arm64-v8a armeabi-v7a x86 x86_64; do
  if [ ! -f "third_party/srt/scripts/build-android/$ABI/lib/libsrt.so" ]; then
    echo "Warning: SRT library not found for $ABI. The build might fail."
    echo "Make sure to run the native build with CMake first."
  fi
done

# Ensure we have gomobile
if ! command -v gomobile &> /dev/null; then
  echo "gomobile not found, installing..."
  go install golang.org/x/mobile/cmd/gomobile@latest
  gomobile init
elif ! gomobile version > /dev/null 2>&1; then
  echo "Initializing gomobile..."
  gomobile init
fi

# Build the AAR
echo "Building kinetic AAR for Android API $ANDROID_API..."
export GO111MODULE=on
gomobile bind -target=android -androidapi=$ANDROID_API -o "$OUTPUT_DIR/kinetic.aar" .

echo "Build complete. AAR file at: $OUTPUT_DIR/kinetic.aar"