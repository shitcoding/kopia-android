package org.kopiaKt.app.navigation

import kotlinx.serialization.Serializable

sealed interface Destination {
    @Serializable
    data object Welcome : Destination

    @Serializable
    data object RepositoryConnect : Destination

    @Serializable
    data object SnapshotList : Destination

    @Serializable
    data class FileBrowser(
        val snapshotId: String,
        val path: String = ""
    ) : Destination

    @Serializable
    data class Restore(
        val snapshotId: String,
        val path: String
    ) : Destination

    @Serializable
    data object Settings : Destination
}
