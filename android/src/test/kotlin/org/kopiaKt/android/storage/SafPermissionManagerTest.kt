package org.kopiaKt.android.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Unit tests for SafPermissionManager.
 *
 * Note: These tests focus on the permission checking logic.
 * DataStore operations require Robolectric or instrumented tests.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class SafPermissionManagerTest {

    private lateinit var context: Context
    private lateinit var mockContentResolver: ContentResolver
    private lateinit var permissionManager: SafPermissionManager
    private lateinit var testUri: Uri

    @BeforeEach
    fun setup() {
        testUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKopia")

        // Use real Robolectric context but spy on it to mock contentResolver
        context = spyk(RuntimeEnvironment.getApplication())
        mockContentResolver = mockk(relaxed = true)

        every { context.contentResolver } returns mockContentResolver

        permissionManager = SafPermissionManager(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    @DisplayName("hasPermission")
    inner class HasPermissionTests {

        @Test
        fun `returns true when has read and write permission`() {
            val mockPermission = mockk<UriPermission> {
                every { uri } returns testUri
                every { isReadPermission } returns true
                every { isWritePermission } returns true
            }

            every { mockContentResolver.persistedUriPermissions } returns listOf(mockPermission)

            assertThat(permissionManager.hasPermission(testUri, requireWrite = true)).isTrue()
        }

        @Test
        fun `returns true when has read permission and write not required`() {
            val mockPermission = mockk<UriPermission> {
                every { uri } returns testUri
                every { isReadPermission } returns true
                every { isWritePermission } returns false
            }

            every { mockContentResolver.persistedUriPermissions } returns listOf(mockPermission)

            assertThat(permissionManager.hasPermission(testUri, requireWrite = false)).isTrue()
        }

        @Test
        fun `returns false when has only read but write required`() {
            val mockPermission = mockk<UriPermission> {
                every { uri } returns testUri
                every { isReadPermission } returns true
                every { isWritePermission } returns false
            }

            every { mockContentResolver.persistedUriPermissions } returns listOf(mockPermission)

            assertThat(permissionManager.hasPermission(testUri, requireWrite = true)).isFalse()
        }

        @Test
        fun `returns false when no permission for URI`() {
            every { mockContentResolver.persistedUriPermissions } returns emptyList()

            assertThat(permissionManager.hasPermission(testUri)).isFalse()
        }

        @Test
        fun `returns false when permission is for different URI`() {
            val differentUri = Uri.parse("content://com.android.externalstorage.documents/tree/1234-5678%3ABackup")
            val mockPermission = mockk<UriPermission> {
                every { uri } returns differentUri
                every { isReadPermission } returns true
                every { isWritePermission } returns true
            }

            every { mockContentResolver.persistedUriPermissions } returns listOf(mockPermission)

            assertThat(permissionManager.hasPermission(testUri)).isFalse()
        }
    }

    @Nested
    @DisplayName("PersistedStorage")
    inner class PersistedStorageTests {

        @Test
        fun `isUsableForBackup returns true when has both read and write`() {
            val storage = SafPermissionManager.PersistedStorage(
                uri = testUri,
                displayName = "Test",
                hasReadPermission = true,
                hasWritePermission = true,
                persistedTime = System.currentTimeMillis()
            )

            assertThat(storage.isUsableForBackup).isTrue()
        }

        @Test
        fun `isUsableForBackup returns false when missing write`() {
            val storage = SafPermissionManager.PersistedStorage(
                uri = testUri,
                displayName = "Test",
                hasReadPermission = true,
                hasWritePermission = false,
                persistedTime = System.currentTimeMillis()
            )

            assertThat(storage.isUsableForBackup).isFalse()
        }

        @Test
        fun `isUsableForRestore returns true when has read`() {
            val storage = SafPermissionManager.PersistedStorage(
                uri = testUri,
                displayName = "Test",
                hasReadPermission = true,
                hasWritePermission = false,
                persistedTime = System.currentTimeMillis()
            )

            assertThat(storage.isUsableForRestore).isTrue()
        }

        @Test
        fun `isUsableForRestore returns false when missing read`() {
            val storage = SafPermissionManager.PersistedStorage(
                uri = testUri,
                displayName = "Test",
                hasReadPermission = false,
                hasWritePermission = true,
                persistedTime = System.currentTimeMillis()
            )

            assertThat(storage.isUsableForRestore).isFalse()
        }
    }

    @Nested
    @DisplayName("createPickDirectoryIntent")
    inner class CreateIntentTests {

        @Test
        fun `creates intent with correct action`() {
            val intent = SafPermissionManager.createPickDirectoryIntent()

            assertThat(intent.action).isEqualTo(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }

        @Test
        fun `creates intent with persistable flag`() {
            val intent = SafPermissionManager.createPickDirectoryIntent()

            assertThat(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .isNotEqualTo(0)
        }

        @Test
        fun `creates intent with read and write flags`() {
            val intent = SafPermissionManager.createPickDirectoryIntent()

            assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
            assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isNotEqualTo(0)
        }
    }

    @Nested
    @DisplayName("getAllPersistedStorages")
    inner class GetAllPersistedStoragesTests {

        @Test
        fun `returns empty list when no permissions`() = runTest {
            every { mockContentResolver.persistedUriPermissions } returns emptyList()

            val result = permissionManager.getAllPersistedStorages()

            assertThat(result).isEmpty()
        }

        @Test
        fun `returns all persisted storages`() = runTest {
            val uri1 = Uri.parse("content://provider/tree/vol1")
            val uri2 = Uri.parse("content://provider/tree/vol2")
            val time1 = System.currentTimeMillis()
            val time2 = time1 + 1000

            val permission1 = mockk<UriPermission> {
                every { uri } returns uri1
                every { isReadPermission } returns true
                every { isWritePermission } returns true
                every { persistedTime } returns time1
            }
            val permission2 = mockk<UriPermission> {
                every { uri } returns uri2
                every { isReadPermission } returns true
                every { isWritePermission } returns false
                every { persistedTime } returns time2
            }

            every { mockContentResolver.persistedUriPermissions } returns listOf(permission1, permission2)

            val result = permissionManager.getAllPersistedStorages()

            assertThat(result).hasSize(2)

            val storage1 = result.find { it.uri == uri1 }
            assertThat(storage1).isNotNull()
            assertThat(storage1!!.hasReadPermission).isTrue()
            assertThat(storage1.hasWritePermission).isTrue()
            assertThat(storage1.persistedTime).isEqualTo(time1)

            val storage2 = result.find { it.uri == uri2 }
            assertThat(storage2).isNotNull()
            assertThat(storage2!!.hasReadPermission).isTrue()
            assertThat(storage2.hasWritePermission).isFalse()
            assertThat(storage2.persistedTime).isEqualTo(time2)
        }
    }

    @Nested
    @DisplayName("isPermissionStale")
    inner class IsPermissionStaleTests {

        @Test
        fun `returns true when no permission`() {
            every { mockContentResolver.persistedUriPermissions } returns emptyList()

            assertThat(permissionManager.isPermissionStale(testUri)).isTrue()
        }

        @Test
        fun `returns true when query fails`() {
            val mockPermission = mockk<UriPermission> {
                every { uri } returns testUri
                every { isReadPermission } returns true
                every { isWritePermission } returns true
            }

            every { mockContentResolver.persistedUriPermissions } returns listOf(mockPermission)
            every {
                mockContentResolver.query(testUri, any(), any(), any(), any())
            } throws SecurityException("Access denied")

            assertThat(permissionManager.isPermissionStale(testUri)).isTrue()
        }
    }
}
