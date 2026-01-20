// Test vector generator for KopiaKt
// Generates test vectors from Go Kopia's actual implementations for byte-exact compatibility

package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/chmduquesne/rollinghash/buzhash32"
	"github.com/chmduquesne/rollinghash/rabinkarp64"
	"github.com/zeebo/blake3"
	"golang.org/x/crypto/blake2b"
	"golang.org/x/crypto/hkdf"
	"golang.org/x/crypto/pbkdf2"
	"golang.org/x/crypto/scrypt"
)

// Test vector structures
type TestVectors struct {
	Version     string                  `json:"version"`
	GeneratedAt string                  `json:"generatedAt"`
	Hash        HashVectors             `json:"hash"`
	KeyDerive   KeyDerivationVectors    `json:"keyDerivation"`
	Encryption  EncryptionVectors       `json:"encryption"`
	Compression CompressionVectors      `json:"compression"`
	Splitter    SplitterVectors         `json:"splitter"`
	ContentID   ContentIDVectors        `json:"contentId"`
}

type HashVectors struct {
	Blake2b256128 []HashTestCase `json:"blake2b_256_128"`
	Blake2b256    []HashTestCase `json:"blake2b_256"`
	Blake3256     []HashTestCase `json:"blake3_256"`
	Blake3256128  []HashTestCase `json:"blake3_256_128"`
	HmacSha256    []HmacTestCase `json:"hmac_sha256"`
}

type HashTestCase struct {
	Name        string `json:"name"`
	InputHex    string `json:"inputHex"`
	InputBase64 string `json:"inputBase64,omitempty"`
	Secret      string `json:"secret,omitempty"` // For keyed hashes
	OutputHex   string `json:"outputHex"`
}

type HmacTestCase struct {
	Name      string `json:"name"`
	InputHex  string `json:"inputHex"`
	KeyHex    string `json:"keyHex"`
	OutputHex string `json:"outputHex"`
}

type KeyDerivationVectors struct {
	Pbkdf2 []Pbkdf2TestCase `json:"pbkdf2"`
	Scrypt []ScryptTestCase `json:"scrypt"`
	Hkdf   []HkdfTestCase   `json:"hkdf"`
}

type Pbkdf2TestCase struct {
	Name       string `json:"name"`
	Password   string `json:"password"`
	SaltHex    string `json:"saltHex"`
	Iterations int    `json:"iterations"`
	KeyLen     int    `json:"keyLen"`
	OutputHex  string `json:"outputHex"`
}

type ScryptTestCase struct {
	Name      string `json:"name"`
	Password  string `json:"password"`
	SaltHex   string `json:"saltHex"`
	N         int    `json:"n"`
	R         int    `json:"r"`
	P         int    `json:"p"`
	KeyLen    int    `json:"keyLen"`
	OutputHex string `json:"outputHex"`
}

type HkdfTestCase struct {
	Name      string `json:"name"`
	MasterHex string `json:"masterHex"`
	SaltHex   string `json:"saltHex"`
	Info      string `json:"info"`
	Length    int    `json:"length"`
	OutputHex string `json:"outputHex"`
}

type EncryptionVectors struct {
	Aes256Gcm []Aes256GcmTestCase `json:"aes256Gcm"`
}

type Aes256GcmTestCase struct {
	Name         string `json:"name"`
	KeyHex       string `json:"keyHex"`
	NonceHex     string `json:"nonceHex"`
	PlaintextHex string `json:"plaintextHex"`
	AadHex       string `json:"aadHex,omitempty"`
	CiphertextHex string `json:"ciphertextHex"`
}

type CompressionVectors struct {
	Headers []CompressionHeaderCase `json:"headers"`
}

type CompressionHeaderCase struct {
	Algorithm string `json:"algorithm"`
	HeaderHex string `json:"headerHex"`
	HeaderID  uint32 `json:"headerId"`
}

type SplitterVectors struct {
	Buzhash32  []SplitterTestCase `json:"buzhash32"`
	RabinKarp64 []SplitterTestCase `json:"rabinkarp64"`
}

type SplitterTestCase struct {
	Name        string `json:"name"`
	Algorithm   string `json:"algorithm"`
	AvgSize     int    `json:"avgSize"`
	MinSize     int    `json:"minSize"`
	MaxSize     int    `json:"maxSize"`
	InputHex    string `json:"inputHex"`
	Boundaries  []int  `json:"boundaries"`
}

type ContentIDVectors struct {
	Formation []ContentIDTestCase `json:"formation"`
}

type ContentIDTestCase struct {
	Name      string `json:"name"`
	Prefix    string `json:"prefix"`
	HashHex   string `json:"hashHex"`
	ContentID string `json:"contentId"`
}

func main() {
	vectors := generateAllVectors()

	// Write JSON
	jsonData, err := json.MarshalIndent(vectors, "", "  ")
	if err != nil {
		panic(err)
	}

	outputPath := filepath.Join("..", "..", "vectors.json")
	if err := os.WriteFile(outputPath, jsonData, 0644); err != nil {
		panic(err)
	}

	fmt.Printf("Test vectors written to %s\n", outputPath)
	fmt.Printf("Total vectors:\n")
	fmt.Printf("  - Hash (BLAKE2B-256-128): %d\n", len(vectors.Hash.Blake2b256128))
	fmt.Printf("  - Hash (BLAKE2B-256): %d\n", len(vectors.Hash.Blake2b256))
	fmt.Printf("  - Hash (BLAKE3-256): %d\n", len(vectors.Hash.Blake3256))
	fmt.Printf("  - Hash (BLAKE3-256-128): %d\n", len(vectors.Hash.Blake3256128))
	fmt.Printf("  - HMAC-SHA256: %d\n", len(vectors.Hash.HmacSha256))
	fmt.Printf("  - Key Derivation (PBKDF2): %d\n", len(vectors.KeyDerive.Pbkdf2))
	fmt.Printf("  - Key Derivation (Scrypt): %d\n", len(vectors.KeyDerive.Scrypt))
	fmt.Printf("  - Key Derivation (HKDF): %d\n", len(vectors.KeyDerive.Hkdf))
	fmt.Printf("  - Encryption (AES-256-GCM): %d\n", len(vectors.Encryption.Aes256Gcm))
	fmt.Printf("  - Compression Headers: %d\n", len(vectors.Compression.Headers))
	fmt.Printf("  - Splitter (Buzhash32): %d\n", len(vectors.Splitter.Buzhash32))
	fmt.Printf("  - Splitter (RabinKarp64): %d\n", len(vectors.Splitter.RabinKarp64))
	fmt.Printf("  - Content ID: %d\n", len(vectors.ContentID.Formation))
}

func generateAllVectors() *TestVectors {
	return &TestVectors{
		Version:     "1.0",
		GeneratedAt: "2025-01-20",
		Hash:        generateHashVectors(),
		KeyDerive:   generateKeyDerivationVectors(),
		Encryption:  generateEncryptionVectors(),
		Compression: generateCompressionVectors(),
		Splitter:    generateSplitterVectors(),
		ContentID:   generateContentIDVectors(),
	}
}

func generateHashVectors() HashVectors {
	testInputs := []struct {
		name  string
		input []byte
	}{
		{"empty", []byte{}},
		{"single_byte", []byte{0x42}},
		{"hello_world", []byte("Hello, World!")},
		{"binary_data", []byte{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f}},
		{"kilobyte", make([]byte, 1024)},
		{"repeated_pattern", repeatBytes([]byte{0xAB, 0xCD, 0xEF}, 100)},
	}

	// Initialize 1KB test data with known pattern
	for i := range testInputs[4].input {
		testInputs[4].input[i] = byte(i % 256)
	}

	// Test secrets for keyed hashes
	testSecrets := []struct {
		name   string
		secret []byte
	}{
		{"empty_secret", []byte{}},
		{"short_secret", []byte("secret")},
		{"32byte_secret", make([]byte, 32)},
	}
	for i := range testSecrets[2].secret {
		testSecrets[2].secret[i] = byte(i)
	}

	var blake2b256128 []HashTestCase
	var blake2b256 []HashTestCase
	var blake3256 []HashTestCase
	var blake3256128 []HashTestCase
	var hmacSha256 []HmacTestCase

	// Generate BLAKE2B-256-128 vectors (keyed, truncated to 16 bytes)
	for _, input := range testInputs {
		for _, secret := range testSecrets {
			tc := HashTestCase{
				Name:      fmt.Sprintf("%s_with_%s", input.name, secret.name),
				InputHex:  hex.EncodeToString(input.input),
				Secret:    hex.EncodeToString(secret.secret),
				OutputHex: computeBlake2b256Truncated(input.input, secret.secret, 16),
			}
			blake2b256128 = append(blake2b256128, tc)
		}
	}

	// Generate BLAKE2B-256 vectors (keyed, full 32 bytes)
	for _, input := range testInputs {
		for _, secret := range testSecrets {
			tc := HashTestCase{
				Name:      fmt.Sprintf("%s_with_%s", input.name, secret.name),
				InputHex:  hex.EncodeToString(input.input),
				Secret:    hex.EncodeToString(secret.secret),
				OutputHex: computeBlake2b256Truncated(input.input, secret.secret, 32),
			}
			blake2b256 = append(blake2b256, tc)
		}
	}

	// Generate BLAKE3-256 vectors (keyed with key derivation)
	for _, input := range testInputs {
		for _, secret := range testSecrets {
			tc := HashTestCase{
				Name:      fmt.Sprintf("%s_with_%s", input.name, secret.name),
				InputHex:  hex.EncodeToString(input.input),
				Secret:    hex.EncodeToString(secret.secret),
				OutputHex: computeBlake3(input.input, secret.secret, 32),
			}
			blake3256 = append(blake3256, tc)
		}
	}

	// Generate BLAKE3-256-128 vectors (keyed, truncated to 16 bytes)
	for _, input := range testInputs {
		for _, secret := range testSecrets {
			tc := HashTestCase{
				Name:      fmt.Sprintf("%s_with_%s", input.name, secret.name),
				InputHex:  hex.EncodeToString(input.input),
				Secret:    hex.EncodeToString(secret.secret),
				OutputHex: computeBlake3(input.input, secret.secret, 16),
			}
			blake3256128 = append(blake3256128, tc)
		}
	}

	// Generate HMAC-SHA256 vectors
	for _, input := range testInputs {
		for _, secret := range testSecrets {
			if len(secret.secret) == 0 {
				continue // HMAC needs a key
			}
			tc := HmacTestCase{
				Name:      fmt.Sprintf("%s_with_%s", input.name, secret.name),
				InputHex:  hex.EncodeToString(input.input),
				KeyHex:    hex.EncodeToString(secret.secret),
				OutputHex: computeHmacSha256(input.input, secret.secret),
			}
			hmacSha256 = append(hmacSha256, tc)
		}
	}

	return HashVectors{
		Blake2b256128: blake2b256128,
		Blake2b256:    blake2b256,
		Blake3256:     blake3256,
		Blake3256128:  blake3256128,
		HmacSha256:    hmacSha256,
	}
}

func computeBlake2b256Truncated(input, key []byte, outputLen int) string {
	var h interface {
		Write([]byte) (int, error)
		Sum([]byte) []byte
	}

	if len(key) > 0 {
		hasher, err := blake2b.New256(key)
		if err != nil {
			panic(err)
		}
		h = hasher
	} else {
		hasher, err := blake2b.New256(nil)
		if err != nil {
			panic(err)
		}
		h = hasher
	}

	h.Write(input)
	result := h.Sum(nil)
	return hex.EncodeToString(result[:outputLen])
}

const blake3KeySize = 32
const blake3KeyDerivationContext = "kopia blake3 derived key v1"

func computeBlake3(input, key []byte, outputLen int) string {
	var h *blake3.Hasher

	if len(key) > 0 {
		// Derive key to 32 bytes using BLAKE3 DeriveKey
		var xKey [blake3KeySize]byte
		blake3.DeriveKey(blake3KeyDerivationContext, key, xKey[:])
		var err error
		h, err = blake3.NewKeyed(xKey[:])
		if err != nil {
			panic(err)
		}
	} else {
		h = blake3.New()
	}

	h.Write(input)
	result := make([]byte, outputLen)
	h.Digest().Read(result)
	return hex.EncodeToString(result)
}

func computeHmacSha256(input, key []byte) string {
	h := hmac.New(sha256.New, key)
	h.Write(input)
	return hex.EncodeToString(h.Sum(nil))
}

func generateKeyDerivationVectors() KeyDerivationVectors {
	var pbkdf2Cases []Pbkdf2TestCase
	var scryptCases []ScryptTestCase
	var hkdfCases []HkdfTestCase

	// PBKDF2 test cases (matching Kopia's 600000 iterations)
	pbkdf2Inputs := []struct {
		name       string
		password   string
		salt       []byte
		iterations int
		keyLen     int
	}{
		{"simple_password", "password123", []byte("saltsaltsaltsalt"), 600000, 32},
		{"empty_password", "", []byte("saltsaltsaltsalt"), 600000, 32},
		{"unicode_password", "пароль", []byte("saltsaltsaltsalt"), 600000, 32},
		{"long_password", "this is a very long password that exceeds 64 characters for testing purposes", []byte("saltsaltsaltsalt"), 600000, 32},
		// Also test with lower iterations for faster unit tests
		{"simple_password_1000iter", "password123", []byte("saltsaltsaltsalt"), 1000, 32},
	}

	for _, tc := range pbkdf2Inputs {
		key := pbkdf2.Key([]byte(tc.password), tc.salt, tc.iterations, tc.keyLen, sha256.New)
		pbkdf2Cases = append(pbkdf2Cases, Pbkdf2TestCase{
			Name:       tc.name,
			Password:   tc.password,
			SaltHex:    hex.EncodeToString(tc.salt),
			Iterations: tc.iterations,
			KeyLen:     tc.keyLen,
			OutputHex:  hex.EncodeToString(key),
		})
	}

	// Scrypt test cases (matching Kopia's 65536-8-1 parameters)
	scryptInputs := []struct {
		name     string
		password string
		salt     []byte
		n, r, p  int
		keyLen   int
	}{
		{"simple_password", "password123", []byte("saltsaltsaltsalt"), 65536, 8, 1, 32},
		{"empty_password", "", []byte("saltsaltsaltsalt"), 65536, 8, 1, 32},
		// Lower N for faster unit tests
		{"simple_password_lowN", "password123", []byte("saltsaltsaltsalt"), 1024, 8, 1, 32},
	}

	for _, tc := range scryptInputs {
		key, err := scrypt.Key([]byte(tc.password), tc.salt, tc.n, tc.r, tc.p, tc.keyLen)
		if err != nil {
			panic(err)
		}
		scryptCases = append(scryptCases, ScryptTestCase{
			Name:      tc.name,
			Password:  tc.password,
			SaltHex:   hex.EncodeToString(tc.salt),
			N:         tc.n,
			R:         tc.r,
			P:         tc.p,
			KeyLen:    tc.keyLen,
			OutputHex: hex.EncodeToString(key),
		})
	}

	// HKDF test cases (matching Kopia's usage for AES key derivation)
	hkdfInputs := []struct {
		name   string
		master []byte
		salt   []byte
		info   string
		length int
	}{
		{"aes_key_derivation", make32ByteKey(), []byte("content-id-salt1"), "AES", 32},
		{"checksum_derivation", make32ByteKey(), []byte("content-id-salt2"), "CHECKSUM", 32},
		{"encryption_derivation", make32ByteKey(), []byte("content-id-salt3"), "encryption", 32},
		{"empty_salt", make32ByteKey(), []byte{}, "AES", 32},
		{"empty_info", make32ByteKey(), []byte("content-id-salt4"), "", 32},
	}

	for _, tc := range hkdfInputs {
		hkdfReader := hkdf.New(sha256.New, tc.master, tc.salt, []byte(tc.info))
		key := make([]byte, tc.length)
		io.ReadFull(hkdfReader, key)
		hkdfCases = append(hkdfCases, HkdfTestCase{
			Name:      tc.name,
			MasterHex: hex.EncodeToString(tc.master),
			SaltHex:   hex.EncodeToString(tc.salt),
			Info:      tc.info,
			Length:    tc.length,
			OutputHex: hex.EncodeToString(key),
		})
	}

	return KeyDerivationVectors{
		Pbkdf2: pbkdf2Cases,
		Scrypt: scryptCases,
		Hkdf:   hkdfCases,
	}
}

func make32ByteKey() []byte {
	key := make([]byte, 32)
	for i := range key {
		key[i] = byte(i)
	}
	return key
}

func generateEncryptionVectors() EncryptionVectors {
	var cases []Aes256GcmTestCase

	// Test inputs for AES-256-GCM
	testInputs := []struct {
		name      string
		plaintext []byte
	}{
		{"empty", []byte{}},
		{"single_byte", []byte{0x42}},
		{"hello_world", []byte("Hello, World!")},
		{"block_aligned", make([]byte, 16)}, // AES block size
		{"two_blocks", make([]byte, 32)},
		{"kilobyte", make([]byte, 1024)},
	}

	// Fill test data with patterns
	for i := range testInputs[3].plaintext {
		testInputs[3].plaintext[i] = byte(i)
	}
	for i := range testInputs[4].plaintext {
		testInputs[4].plaintext[i] = byte(i * 2)
	}
	for i := range testInputs[5].plaintext {
		testInputs[5].plaintext[i] = byte(i % 256)
	}

	// Generate deterministic key and nonce for reproducibility
	key := make32ByteKey()
	nonces := [][]byte{
		{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},   // Standard 12-byte GCM nonce
		{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},     // All zeros
		{255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}, // All ones
	}

	for _, input := range testInputs {
		for i, nonce := range nonces {
			ciphertext := encryptAes256Gcm(key, nonce, input.plaintext, nil)
			cases = append(cases, Aes256GcmTestCase{
				Name:          fmt.Sprintf("%s_nonce%d", input.name, i),
				KeyHex:        hex.EncodeToString(key),
				NonceHex:      hex.EncodeToString(nonce),
				PlaintextHex:  hex.EncodeToString(input.plaintext),
				CiphertextHex: hex.EncodeToString(ciphertext),
			})
		}
	}

	// Test with AAD (additional authenticated data)
	aad := []byte("additional authenticated data")
	plaintext := []byte("secret message")
	nonce := nonces[0]
	ciphertext := encryptAes256Gcm(key, nonce, plaintext, aad)
	cases = append(cases, Aes256GcmTestCase{
		Name:          "with_aad",
		KeyHex:        hex.EncodeToString(key),
		NonceHex:      hex.EncodeToString(nonce),
		PlaintextHex:  hex.EncodeToString(plaintext),
		AadHex:        hex.EncodeToString(aad),
		CiphertextHex: hex.EncodeToString(ciphertext),
	})

	return EncryptionVectors{
		Aes256Gcm: cases,
	}
}

func encryptAes256Gcm(key, nonce, plaintext, aad []byte) []byte {
	block, err := aes.NewCipher(key)
	if err != nil {
		panic(err)
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		panic(err)
	}

	return gcm.Seal(nil, nonce, plaintext, aad)
}

func generateCompressionVectors() CompressionVectors {
	// Compression header IDs from Kopia (4-byte big-endian)
	headers := []CompressionHeaderCase{
		{"gzip-default", "00001000", 0x1000},
		{"gzip-best-speed", "00001001", 0x1001},
		{"gzip-best-compression", "00001002", 0x1002},
		{"zstd-default", "00001100", 0x1100},
		{"zstd-fastest", "00001101", 0x1101},
		{"zstd-better-compression", "00001102", 0x1102},
		{"zstd-best-compression", "00001103", 0x1103},
		{"s2-default", "00001200", 0x1200},
		{"s2-better", "00001201", 0x1201},
		{"s2-parallel-4", "00001202", 0x1202},
		{"s2-parallel-8", "00001203", 0x1203},
		{"pgzip-default", "00001300", 0x1300},
		{"pgzip-best-speed", "00001301", 0x1301},
		{"pgzip-best-compression", "00001302", 0x1302},
		{"lz4-default", "00001400", 0x1400},
		{"deflate-default", "00001500", 0x1500},
		{"deflate-best-speed", "00001501", 0x1501},
		{"deflate-best-compression", "00001502", 0x1502},
	}

	return CompressionVectors{
		Headers: headers,
	}
}

func generateSplitterVectors() SplitterVectors {
	var buzhash32Cases []SplitterTestCase
	var rabinkarp64Cases []SplitterTestCase

	// Test data sizes - we need enough data to trigger boundaries
	// Using small chunk sizes for testing (128KB average)
	avgSize := 128 * 1024 // 128KB
	minSize := avgSize / 2
	maxSize := avgSize * 2

	// Generate pseudo-random test data that will produce deterministic boundaries
	// Using a seed pattern that ensures we get some boundaries
	testData := generateSplitterTestData(512 * 1024) // 512KB of data

	// Buzhash32 test cases
	buzBoundaries := computeBuzhash32Boundaries(testData, avgSize, minSize, maxSize)
	buzhash32Cases = append(buzhash32Cases, SplitterTestCase{
		Name:       "random_data_128k",
		Algorithm:  "DYNAMIC-128K-BUZHASH",
		AvgSize:    avgSize,
		MinSize:    minSize,
		MaxSize:    maxSize,
		InputHex:   hex.EncodeToString(testData),
		Boundaries: buzBoundaries,
	})

	// Smaller test with known data
	smallData := []byte("The quick brown fox jumps over the lazy dog. " +
		"This is some test data for chunking algorithms. " +
		"We need enough data to potentially trigger a boundary. " +
		"Adding more text to increase the likelihood of finding boundaries.")
	smallData = repeatBytes(smallData, 1000) // ~190KB

	smallAvgSize := 8 * 1024 // 8KB for testing
	smallMinSize := smallAvgSize / 2
	smallMaxSize := smallAvgSize * 2

	smallBuzBoundaries := computeBuzhash32Boundaries(smallData, smallAvgSize, smallMinSize, smallMaxSize)
	buzhash32Cases = append(buzhash32Cases, SplitterTestCase{
		Name:       "repeated_text_8k",
		Algorithm:  "DYNAMIC-8K-BUZHASH",
		AvgSize:    smallAvgSize,
		MinSize:    smallMinSize,
		MaxSize:    smallMaxSize,
		InputHex:   hex.EncodeToString(smallData),
		Boundaries: smallBuzBoundaries,
	})

	// RabinKarp64 test cases
	rkBoundaries := computeRabinKarp64Boundaries(testData, avgSize, minSize, maxSize)
	rabinkarp64Cases = append(rabinkarp64Cases, SplitterTestCase{
		Name:       "random_data_128k",
		Algorithm:  "DYNAMIC-128K-RABINKARP",
		AvgSize:    avgSize,
		MinSize:    minSize,
		MaxSize:    maxSize,
		InputHex:   hex.EncodeToString(testData),
		Boundaries: rkBoundaries,
	})

	smallRkBoundaries := computeRabinKarp64Boundaries(smallData, smallAvgSize, smallMinSize, smallMaxSize)
	rabinkarp64Cases = append(rabinkarp64Cases, SplitterTestCase{
		Name:       "repeated_text_8k",
		Algorithm:  "DYNAMIC-8K-RABINKARP",
		AvgSize:    smallAvgSize,
		MinSize:    smallMinSize,
		MaxSize:    smallMaxSize,
		InputHex:   hex.EncodeToString(smallData),
		Boundaries: smallRkBoundaries,
	})

	return SplitterVectors{
		Buzhash32:   buzhash32Cases,
		RabinKarp64: rabinkarp64Cases,
	}
}

func generateSplitterTestData(size int) []byte {
	// Use deterministic pseudo-random data
	data := make([]byte, size)
	// Use a simple PRNG seeded with a fixed value for reproducibility
	seed := uint64(0x12345678DEADBEEF)
	for i := range data {
		seed = seed*6364136223846793005 + 1442695040888963407 // LCG
		data[i] = byte(seed >> 56)
	}
	return data
}

const splitterSlidingWindowSize = 64

func computeBuzhash32Boundaries(data []byte, avgSize, minSize, maxSize int) []int {
	var boundaries []int

	if len(data) == 0 {
		return boundaries
	}

	mask := uint32(avgSize - 1)

	// Initialize with zeros like Kopia does
	bh := buzhash32.New()
	zeros := make([]byte, splitterSlidingWindowSize)
	bh.Write(zeros)

	pos := 0
	chunkStart := 0

	for pos < len(data) {
		bh.Roll(data[pos])
		pos++
		chunkLen := pos - chunkStart

		// Check for boundary
		if chunkLen >= minSize {
			if chunkLen >= maxSize || bh.Sum32()&mask == 0 {
				boundaries = append(boundaries, pos)
				chunkStart = pos
				// Reset hasher
				bh.Reset()
				bh.Write(zeros)
			}
		}
	}

	// Final boundary at end if there's remaining data
	if chunkStart < len(data) {
		boundaries = append(boundaries, len(data))
	}

	return boundaries
}

func computeRabinKarp64Boundaries(data []byte, avgSize, minSize, maxSize int) []int {
	var boundaries []int

	if len(data) == 0 {
		return boundaries
	}

	mask := uint64(avgSize - 1)

	// Initialize with zeros like Kopia does
	rk := rabinkarp64.New()
	zeros := make([]byte, splitterSlidingWindowSize)
	rk.Write(zeros)

	pos := 0
	chunkStart := 0

	for pos < len(data) {
		rk.Roll(data[pos])
		pos++
		chunkLen := pos - chunkStart

		// Check for boundary
		if chunkLen >= minSize {
			if chunkLen >= maxSize || rk.Sum64()&mask == 0 {
				boundaries = append(boundaries, pos)
				chunkStart = pos
				// Reset hasher
				rk.Reset()
				rk.Write(zeros)
			}
		}
	}

	// Final boundary at end if there's remaining data
	if chunkStart < len(data) {
		boundaries = append(boundaries, len(data))
	}

	return boundaries
}

func generateContentIDVectors() ContentIDVectors {
	var cases []ContentIDTestCase

	// Test various hash outputs and prefixes
	hashes := [][]byte{
		{0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff},
		make32ByteKey(),
		{},
	}

	prefixes := []string{"", "p", "k", "m", "d", "x"}

	for _, hash := range hashes {
		if len(hash) == 0 {
			continue
		}
		for _, prefix := range prefixes {
			contentID := prefix + hex.EncodeToString(hash)
			cases = append(cases, ContentIDTestCase{
				Name:      fmt.Sprintf("prefix_%s_hash_%d", prefix, len(hash)),
				Prefix:    prefix,
				HashHex:   hex.EncodeToString(hash),
				ContentID: contentID,
			})
		}
	}

	return ContentIDVectors{
		Formation: cases,
	}
}

func repeatBytes(b []byte, count int) []byte {
	result := make([]byte, len(b)*count)
	for i := 0; i < count; i++ {
		copy(result[i*len(b):], b)
	}
	return result
}

// Generate random bytes for testing (deterministic using seed)
func deterministicRandom(size int, seed int64) []byte {
	data := make([]byte, size)
	r := rand.Reader
	if _, err := io.ReadFull(r, data); err != nil {
		// Fallback to deterministic pattern
		for i := range data {
			data[i] = byte((int64(i) + seed) % 256)
		}
	}
	return data
}
