package main

import (
	"encoding/binary"
	"fmt"
	"os"
)

func main() {
	// Read the index blob - need to decrypt it first
	// Let's just use kopia to dump the index
	fmt.Println("Checking index entries with kopia...")

	// For now, let's manually check what Go Kopia sees
	// by examining the pack blob directly
	packData, _ := os.ReadFile("test_repository/q/4e5/4ada559c93439482ed8be56def45d-sc05c6694229ca11e13d.f")
	fmt.Printf("Pack blob size: %d\n", len(packData))

	// Dump the pack blob header (first 32 bytes before the content)
	fmt.Printf("Pack header (first 32 bytes): %x\n", packData[:32])

	// The content at offset 32 with length 675
	content := packData[32 : 32+675]
	fmt.Printf("Content at 32:675 first 32 bytes: %x\n", content[:32])

	// Let me also check if the offset might be 0 instead of 32
	fmt.Printf("\nContent at 0:675 first 32 bytes: %x\n", packData[:32])

	// Check the pack blob format - it starts with a header
	// Pack format: contents are appended sequentially
	// Header format might indicate where contents actually start

	// Read 32 bytes starting at different offsets to find the real content
	for offset := 0; offset <= 100; offset += 10 {
		if offset+20 <= len(packData) {
			fmt.Printf("Offset %3d: %x\n", offset, packData[offset:offset+20])
		}
	}

	// The index says offset 32, packed length 675
	// Let's verify by looking at what's after the 675 bytes
	fmt.Printf("\nNext content after offset 32+675=%d:\n", 32+675)
	if 32+675+32 <= len(packData) {
		fmt.Printf("  Bytes at 707: %x\n", packData[707:707+32])
	}

	// Let me also check the pack blob format
	// In Kopia, pack blobs have no header - contents are just concatenated
	// The index tracks the offset and length of each content within the pack

	// Could the offset be relative to something else?
	// Let's check if offset 32 is correct by seeing if content ID hash matches

	// Actually wait - what if the pack blob prefix 'q' indicates something special?
	fmt.Println("\nPack blob ID: q4e54ada559c93439482ed8be56def45d-sc05c6694229ca11e13d")
	fmt.Println("The 'q' prefix typically indicates a Q-format pack (contents without header)")

	// Let's calculate what Go would see
	fmt.Printf("\nIf offset is 32, first 12 bytes (nonce): %x\n", packData[32:32+12])
	fmt.Printf("If offset is 0, first 12 bytes (nonce): %x\n", packData[0:12])

	// Check if there's a pattern that looks like encrypted content starts somewhere
	// Encrypted content: nonce(12) + ciphertext + tag(16)
	// The plaintext is gzip: starts with 1f 8b

	fmt.Println("\n--- Looking for encryption overhead ---")
	fmt.Printf("Content size according to index: packed=675\n")
	fmt.Printf("AES-GCM overhead: 12 (nonce) + 16 (tag) = 28\n")
	fmt.Printf("Plaintext size would be: %d\n", 675-28)

	// The output from 'kopia content show' showed plaintext starting with 1f8b (gzip)
	// and the output size matches: let's see what kopia reported
	fmt.Println("\nkopia content show m973734f2357ba933544a047a4c9fc200 | wc -c")
	fmt.Println("Expected: 647 bytes (675 - 28)")

	// Let me trace the issue: maybe the offset in the index is wrong?
	// Or maybe the content ID bytes aren't matching what Go uses for AAD

	// Test: Try parsing the V2 index header
	fmt.Println("\n--- Checking index format ---")

	// Read one of the index blobs
	// First we need to decrypt it - skip for now

	// But we can verify the pack blob offset by checking kopia's internal state
	// Actually, let's verify by looking at what content comes at different offsets

	fmt.Println("\n--- Pack blob structure analysis ---")
	fmt.Printf("Total pack size: %d bytes\n", len(packData))

	// The pack has multiple contents - let's see the boundaries
	// Content 1: m973734f2357ba933544a047a4c9fc200 at offset 32, length 675
	// So content 1 ends at 32+675 = 707

	// Let's verify by checking if the encrypted data at offset 32 makes sense
	// The nonce should be random 12 bytes, followed by ciphertext

	// More importantly, let's check if the ORIGINAL offset might be 0, not 32
	fmt.Println("\n--- Theory: offset might be incorrect ---")

	// Looking at test output:
	// DEBUG getContent: packBlobId=q4e54ada559c93439482ed8be56def45d-sc05c6694229ca11e13d, offset=32, packedLen=675

	// But what if the offset in the V2 index is stored differently?
	// Let me check: in V2 format, offset might be absolute or relative

	// Actually, the issue might be in how we're reading the pack offset
	// Let me verify by dumping what the test actually sees

	// Let me check Go's actual encrypted format
	fmt.Println("\n--- Verifying Go's encryption format ---")
	fmt.Println("Go AES-GCM: nonce (12) prepended, tag (16) appended")
	fmt.Println("So: [nonce 12][ciphertext N][tag 16] = N + 28 total")
	fmt.Printf("For packed length 675: plaintext = %d bytes\n", 675-28)

	// The 647 byte plaintext is gzip-compressed manifest JSON
	// This matches what 'kopia content show' outputs

	// So the issue must be in:
	// 1. The offset being wrong (32 instead of 0?)
	// 2. The content ID bytes being wrong for AAD
	// 3. The master key or derived keys being wrong

	// We already verified the keys match Go's output
	// Let me check if offset should be 0

	fmt.Println("\n--- Test: decrypt from offset 0 ---")
	fmt.Printf("Offset 0: nonce = %x\n", packData[0:12])
	fmt.Printf("Offset 0: ciphertext starts = %x\n", packData[12:24])

	// Compare with offset 32
	fmt.Printf("Offset 32: nonce = %x\n", packData[32:44])
	fmt.Printf("Offset 32: ciphertext starts = %x\n", packData[44:56])

	// In kopia pack format, there's usually no pack header
	// The first content starts at offset 0

	// But wait - what is in the first 32 bytes then?
	// Let me decode it
	firstBytes := packData[:32]
	fmt.Printf("\nFirst 32 bytes analysis:\n")
	fmt.Printf("  Hex: %x\n", firstBytes)
	fmt.Printf("  Could this be another content? Let's see...\n")

	// Actually, looking at the hex dump earlier:
	// 00000000: 6c30 6afc d500 c436 1d28 6792 02d0 c779  l0j....6.(g....y
	// The first byte is 0x6c = 'l' - this might be a content ID prefix!

	// Wait, let me re-examine the index entries
	fmt.Println("\n--- Index entries from test output ---")
	fmt.Println("Content IDs found:")
	fmt.Println("  - m973734f2357ba933544a047a4c9fc200 (prefix='m')")
	fmt.Println("  - 0002d28acb17b337b4dcb2f8b91d115278 (prefix='null')")
	fmt.Println("  - 001c31af3ab16e7a4d128b362bbc24dd63 (prefix='null')")
	fmt.Println("  - 00237d2c9632b6dcd944782b7aa3dcb6f9 (prefix='null')")
	fmt.Println("  - kcc04abb06c7a9e4ed52abd53108576fe (prefix='k')")
	fmt.Println("  - kd88c55593b89cfba21b6d2e026a18252 (prefix='k')")
	fmt.Println("  - m324a277c4a25e7421a8f9645b8ce042b (prefix='m')")

	// The first bytes of pack are 6c30 = hex
	// 6c = 108 decimal = 'l' ASCII - but this could just be random nonce data

	// Let me check if offset 0 would work for decryption
	fmt.Println("\nTo test: try decrypting from offset 0 instead of 32")
}
