package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"

	"golang.org/x/crypto/hkdf"
)

func main() {
	// The masterKey from the test repository
	masterKeyHex := "6ecb2b105ed41953793c236dbc78e1a81991e4d2b28fc39a1a968580d2faac14"
	masterKey, _ := hex.DecodeString(masterKeyHex)

	fmt.Printf("MasterKey: %s\n", masterKeyHex)
	fmt.Printf("MasterKey bytes: %v\n", masterKey)

	// Go Kopia's encryption.deriveKey uses:
	// hkdf.Key(sha256.New, masterKey, purpose, "", length)
	// where purpose = "encryption"
	purpose := []byte("encryption")
	length := 32

	fmt.Printf("\nHKDF parameters:\n")
	fmt.Printf("  secret: %x\n", masterKey)
	fmt.Printf("  salt: %s (%x)\n", string(purpose), purpose)
	fmt.Printf("  info: (empty)\n")
	fmt.Printf("  length: %d\n", length)

	// Method 1: Using hkdf.New directly (mimics what hkdf.Key does)
	// hkdf.New(hash, secret, salt, info)
	hkdfReader := hkdf.New(sha256.New, masterKey, purpose, []byte(""))
	keyDerivationSecret := make([]byte, length)
	io.ReadFull(hkdfReader, keyDerivationSecret)

	fmt.Printf("\nResult from hkdf.New:\n")
	fmt.Printf("  keyDerivationSecret (hex): %x\n", keyDerivationSecret)
	fmt.Printf("  keyDerivationSecret (bytes): %v\n", keyDerivationSecret)

	// Now derive a content key using HMAC-SHA256
	contentIdHex := "6d973734f2357ba933544a047a4c9fc200" // m973734f2357ba933544a047a4c9fc200
	contentIdBytes, _ := hex.DecodeString(contentIdHex)
	fmt.Printf("\nContent ID: %s\n", contentIdHex)
	fmt.Printf("Content ID bytes: %v\n", contentIdBytes)

	// Derive content key using HMAC-SHA256
	mac := hmac.New(sha256.New, keyDerivationSecret)
	mac.Write(contentIdBytes)
	contentKey := mac.Sum(nil)
	fmt.Printf("\nContent key derivation:\n")
	fmt.Printf("  contentKey (hex): %x\n", contentKey)
	fmt.Printf("  contentKey first 8: %v\n", contentKey[:8])

	// The encrypted data starts with nonce (12 bytes)
	encryptedDataFirst16Hex := "5f69c163f4954cf707e5326fc4b20e33"
	encryptedDataFirst16, _ := hex.DecodeString(encryptedDataFirst16Hex)
	nonce := encryptedDataFirst16[:12]
	fmt.Printf("\nEncrypted data analysis:\n")
	fmt.Printf("  First 16 bytes: %v\n", encryptedDataFirst16)
	fmt.Printf("  Nonce (first 12): %v\n", nonce)
	fmt.Printf("  Nonce (hex): %x\n", nonce)

	// AAD = contentIdBytes
	fmt.Printf("\nAAD (contentIdBytes): %v\n", contentIdBytes)
	fmt.Printf("AAD (hex): %x\n", contentIdBytes)
}
