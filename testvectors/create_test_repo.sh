#!/bin/bash
# Create a minimal Kopia test repository with known content
# This repository can be used to test cross-compatibility between Go and Kotlin implementations

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KOPIA_GO_DIR="$SCRIPT_DIR/../../kopia-go"
TEST_REPO_DIR="$SCRIPT_DIR/test_repository"
TEST_DATA_DIR="$SCRIPT_DIR/test_data"

echo "Building Kopia..."
cd "$KOPIA_GO_DIR"
go build -o kopia .

KOPIA="$KOPIA_GO_DIR/kopia"

echo "Creating test data directory..."
rm -rf "$TEST_DATA_DIR" "$TEST_REPO_DIR"
mkdir -p "$TEST_DATA_DIR"

# Create known test files
echo "Creating test files..."

# Empty file
touch "$TEST_DATA_DIR/empty.txt"

# Single byte file
printf '\x42' > "$TEST_DATA_DIR/single_byte.bin"

# Small text file
echo "Hello, World!" > "$TEST_DATA_DIR/hello.txt"

# Binary data file (256 bytes, sequential)
for i in $(seq 0 255); do
    printf "\\x$(printf '%02x' $i)"
done > "$TEST_DATA_DIR/sequential_256.bin"

# 1KB file with repeating pattern
dd if=/dev/zero bs=1024 count=1 2>/dev/null | tr '\0' 'A' > "$TEST_DATA_DIR/1kb_pattern.txt"

# Create directory structure
mkdir -p "$TEST_DATA_DIR/subdir/nested"
echo "File in subdir" > "$TEST_DATA_DIR/subdir/file.txt"
echo "Nested file" > "$TEST_DATA_DIR/subdir/nested/deep.txt"

# Create symlink (if supported)
ln -sf "$TEST_DATA_DIR/hello.txt" "$TEST_DATA_DIR/hello_link.txt" 2>/dev/null || true

echo "Initializing Kopia repository..."
mkdir -p "$TEST_REPO_DIR"

# Initialize with password "test123" and known settings
export KOPIA_PASSWORD="test123"
$KOPIA repository create filesystem \
    --path="$TEST_REPO_DIR" \
    --block-hash=BLAKE2B-256-128 \
    --encryption=AES256-GCM-HMAC-SHA256 \
    --splitter=DYNAMIC-4M-BUZHASH

echo "Creating snapshot..."
$KOPIA snapshot create "$TEST_DATA_DIR" \
    --description="Test snapshot for KopiaKt"

echo "Repository status:"
$KOPIA repository status

echo "Snapshot list:"
$KOPIA snapshot list

echo "Content list (first 20):"
$KOPIA content list --limit=20

# Export repository config info for test verification
echo ""
echo "Repository configuration for test verification:"
$KOPIA repository status --json > "$SCRIPT_DIR/test_repo_config.json"

echo ""
echo "Test repository created at: $TEST_REPO_DIR"
echo "Test data at: $TEST_DATA_DIR"
echo "Password: test123"
echo ""
echo "You can verify this repository with:"
echo "  KOPIA_PASSWORD=test123 kopia repository connect filesystem --path=$TEST_REPO_DIR"
