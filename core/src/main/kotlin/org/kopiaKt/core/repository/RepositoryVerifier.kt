package org.kopiaKt.core.repository

import org.kopiaKt.core.content.ObjectId

/**
 * Verifies repository integrity by actually reading objects from storage.
 *
 * Unlike Repository.verifyObject() which only checks index existence,
 * this verifier reads each object, triggering decryption (AEAD) and
 * decompression, which detects corruption, missing blobs, and bit-rot.
 *
 * Go equivalent: repo.VerifyObject with full content verification.
 */
class RepositoryVerifier(private val repo: DirectRepositoryImpl) {

    /**
     * Results of a verification run.
     */
    data class VerificationResult(
        val verifiedCount: Int,
        val failedCount: Int,
        val failedObjectIds: List<ObjectId>,
        val errors: Map<ObjectId, Exception>
    ) {
        val totalCount: Int get() = verifiedCount + failedCount
        val isSuccess: Boolean get() = failedCount == 0
    }

    /**
     * Verify a list of objects by actually reading them from storage.
     * Each read triggers decryption + decompression, detecting any corruption.
     */
    suspend fun verifyObjects(objectIds: List<ObjectId>): VerificationResult {
        var verifiedCount = 0
        var failedCount = 0
        val failedObjectIds = mutableListOf<ObjectId>()
        val errors = mutableMapOf<ObjectId, Exception>()

        for (objectId in objectIds) {
            try {
                repo.readObject(objectId)
                verifiedCount++
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                failedCount++
                failedObjectIds.add(objectId)
                errors[objectId] = e
            }
        }

        return VerificationResult(verifiedCount, failedCount, failedObjectIds, errors)
    }
}
