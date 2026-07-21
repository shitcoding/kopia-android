package org.kopiaKt.core.testutil

import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.repository.DirectRepositoryImpl
import java.security.SecureRandom

/**
 * Factory for creating test repositories with minimal boilerplate.
 * Wraps the existing DirectRepositoryImpl.create() pattern used in DirectRepositoryTest.
 */
object TestRepositoryFactory {

    /**
     * Create an in-memory repository with default config.
     * Returns (repo, storage) pair for test manipulation.
     *
     * Must be called from a suspend context since DirectRepositoryImpl.create() is suspend.
     */
    suspend fun createInMemory(
        password: String = "test-password",
        hash: String = "BLAKE2B-256-128",
        encryption: String = "AES256-GCM-HMAC-SHA256",
        splitter: String = "FIXED-1M",
    ): Pair<DirectRepositoryImpl, InMemoryBlobStorage> {
        val storage = InMemoryBlobStorage()
        val config = createConfig(hash, encryption, splitter)
        val repo = DirectRepositoryImpl.create(storage, password, config)
        return repo to storage
    }

    /**
     * Create a repository pre-populated with objects for read-path tests.
     *
     * Creates a repository, writes each entry as an object via a DirectRepositoryWriter,
     * flushes to persist to storage, and refreshes so the objects are readable.
     *
     * Keys are arbitrary string identifiers; values are the data to store.
     * Returns (repo, storage, objectIds) where objectIds maps input keys to their ObjectIds.
     */
    suspend fun createWithObjects(
        objects: Map<String, ByteArray>,
        password: String = "test-password",
    ): Triple<DirectRepositoryImpl, InMemoryBlobStorage, Map<String, ObjectId>> {
        val (repo, storage) = createInMemory(password = password)
        val objectIds = mutableMapOf<String, ObjectId>()

        val writer = repo.newDirectWriter()
        for ((key, data) in objects) {
            val objectId = writer.writeObject(data)
            objectIds[key] = objectId
        }
        writer.flush()

        repo.refresh()
        return Triple(repo, storage, objectIds)
    }

    /**
     * Create a RepositoryConfig with random secret and masterKey.
     * Can be used independently when you need a config without creating a full repository.
     */
    fun createConfig(
        hash: String = "BLAKE2B-256-128",
        encryption: String = "AES256-GCM-HMAC-SHA256",
        splitter: String = "FIXED-1M",
    ): RepositoryConfig {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val masterKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return RepositoryConfig(
            hash = hash,
            encryption = encryption,
            secret = secret,
            masterKey = masterKey,
            splitter = splitter,
        )
    }
}
