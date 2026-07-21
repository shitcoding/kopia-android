package org.kopiaKt.snapshot.upload

import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.repository.RepositoryWriter
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * Helper functions for managing snapshots in the repository.
 *
 * Go type: snapshot (package-level functions)
 */
object SnapshotManager {

    /**
     * Saves a snapshot manifest to the repository.
     *
     * @param repo The repository to save to
     * @param manifest The snapshot manifest to save
     * @return The manifest ID of the saved snapshot
     * @throws IllegalArgumentException if the manifest source is incomplete
     */
    suspend fun saveSnapshot(repo: RepositoryWriter, manifest: SnapshotManifest): ManifestId {
        require(manifest.source.host.isNotEmpty()) { "missing host" }
        require(manifest.source.userName.isNotEmpty()) { "missing username" }
        require(manifest.source.path.isNotEmpty()) { "missing path" }

        val labels = buildLabels(manifest)

        return repo.putManifest(
            labels = labels,
            payload = manifest,
            serializer = SnapshotManifest.serializer(),
        )
    }

    /**
     * Lists all snapshots for a given source.
     *
     * @param repo The repository to search
     * @param source The source to filter by
     * @return List of snapshot manifests matching the source
     */
    suspend fun listSnapshots(repo: RepositoryWriter, source: SourceInfo): List<SnapshotManifest> {
        val labels = mutableMapOf<String, String>()
        labels[ManifestLabels.TYPE] = ManifestLabels.TYPE_SNAPSHOT

        if (source.host.isNotEmpty()) {
            labels[ManifestLabels.HOST] = source.host
        }
        if (source.userName.isNotEmpty()) {
            labels[ManifestLabels.USERNAME] = source.userName
        }
        if (source.path.isNotEmpty()) {
            labels[ManifestLabels.PATH] = source.path
        }

        val entries = repo.findManifests(labels)

        return entries.mapNotNull { metadata ->
            try {
                val (manifest, _) = repo.getManifest(metadata.id, SnapshotManifest.serializer())
                manifest.copy(id = metadata.id.value)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Gets a snapshot by its manifest ID.
     *
     * @param repo The repository to read from
     * @param id The manifest ID
     * @return The snapshot manifest
     */
    suspend fun getSnapshot(repo: RepositoryWriter, id: ManifestId): SnapshotManifest {
        val (manifest, _) = repo.getManifest(id, SnapshotManifest.serializer())
        return manifest.copy(id = id.value)
    }

    /**
     * Deletes a snapshot by its manifest ID.
     *
     * Note: This only deletes the manifest, not the underlying content.
     * Content may be garbage collected later.
     *
     * @param repo The repository to delete from
     * @param id The manifest ID to delete
     */
    suspend fun deleteSnapshot(repo: RepositoryWriter, id: ManifestId) {
        repo.deleteManifest(id)
    }

    /**
     * Builds the labels for a snapshot manifest.
     */
    private fun buildLabels(manifest: SnapshotManifest): Map<String, String> {
        val labels = mutableMapOf<String, String>()

        labels[ManifestLabels.TYPE] = ManifestLabels.TYPE_SNAPSHOT
        labels[ManifestLabels.HOST] = manifest.source.host
        labels[ManifestLabels.USERNAME] = manifest.source.userName
        labels[ManifestLabels.PATH] = manifest.source.path

        return labels
    }
}
