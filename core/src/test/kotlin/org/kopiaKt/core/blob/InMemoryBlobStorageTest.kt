package org.kopiaKt.core.blob

/**
 * Tests for InMemoryBlobStorage using the contract test suite.
 */
class InMemoryBlobStorageTest : BlobStorageContractTest() {

    override fun createStorage(): BlobStorage = InMemoryBlobStorage("test-storage")
}
