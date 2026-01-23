package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"

	"golang.org/x/crypto/hkdf"
)

func tryDecrypt(packData []byte, contentKey []byte, encryptionIV []byte, offset int, length int) bool {
	if offset+length > len(packData) {
		fmt.Printf("  Offset %d + length %d exceeds pack size %d\n", offset, length, len(packData))
		return false
	}

	encryptedData := packData[offset : offset+length]
	fmt.Printf("  Trying offset %d, length %d\n", offset, length)
	fmt.Printf("  First 16 bytes: %x\n", encryptedData[:16])

	nonce := encryptedData[:12]
	ciphertext := encryptedData[12:]

	block, _ := aes.NewCipher(contentKey)
	aesGCM, _ := cipher.NewGCM(block)

	plaintext, err := aesGCM.Open(nil, nonce, ciphertext, encryptionIV)
	if err != nil {
		fmt.Printf("  Decryption failed: %v\n", err)
		return false
	}

	fmt.Printf("  SUCCESS! First bytes: %x\n", plaintext[:min(20, len(plaintext))])
	return true
}

func main() {
	// The masterKey from the test repository
	masterKeyHex := "6ecb2b105ed41953793c236dbc78e1a81991e4d2b28fc39a1a968580d2faac14"
	masterKey, _ := hex.DecodeString(masterKeyHex)

	// Derive keyDerivationSecret using HKDF
	purpose := []byte("encryption")
	hkdfReader := hkdf.New(sha256.New, masterKey, purpose, []byte(""))
	keyDerivationSecret := make([]byte, 32)
	io.ReadFull(hkdfReader, keyDerivationSecret)

	fmt.Printf("keyDerivationSecret: %x\n", keyDerivationSecret)

	// Content ID for manifest: m973734f2357ba933544a047a4c9fc200
	// The hash part (without 'm' prefix) is: 973734f2357ba933544a047a4c9fc200
	// This is 16 bytes, so the encryptionIV is the same as the hash
	hashHex := "973734f2357ba933544a047a4c9fc200"
	hashBytes, _ := hex.DecodeString(hashHex)
	fmt.Printf("hashBytes (no prefix): %x\n", hashBytes)

	// Go's getPackedContentIV returns last 16 bytes of hash
	// Since our hash is already 16 bytes, encryptionIV = hash
	encryptionIV := hashBytes
	if len(hashBytes) > 16 {
		encryptionIV = hashBytes[len(hashBytes)-16:]
	}
	fmt.Printf("encryptionIV (last 16 bytes of hash): %x\n", encryptionIV)

	// Derive content key using HMAC-SHA256 with encryptionIV (NOT full contentID!)
	mac := hmac.New(sha256.New, keyDerivationSecret)
	mac.Write(encryptionIV)
	contentKey := mac.Sum(nil)
	fmt.Printf("contentKey: %x\n", contentKey)

	// Read the pack blob
	packData, err := os.ReadFile("test_repository/q/4e5/4ada559c93439482ed8be56def45d-sc05c6694229ca11e13d.f")
	if err != nil {
		fmt.Printf("Error reading pack: %v\n", err)
		return
	}
	fmt.Printf("packData size: %d\n", len(packData))

	fmt.Println("\n=== Testing with offset 32 (from our index parse) ===")
	tryDecrypt(packData, contentKey, encryptionIV, 32, 675)

	fmt.Println("\n=== Testing with offset 0 ===")
	tryDecrypt(packData, contentKey, encryptionIV, 0, 675)
}
