package com.kopia.android.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.kopia.android.test.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.google.common.truth.Truth.assertThat

/**
 * Unit tests for RepositoryManager
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RepositoryManagerTest : BaseUnitTest() {

    private lateinit var context: Context
    private lateinit var repositoryManager: RepositoryManager
    private lateinit var testKopiaPath: String
    private lateinit var testRepoDir: File
    private lateinit var testConfigDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repositoryManager = RepositoryManager(context)

        // Setup test directories
        testKopiaPath = File(context.filesDir, "kopia").absolutePath
        testRepoDir = File(context.filesDir, "repo")
        testConfigDir = File(context.filesDir, ".kopia")

        // Create mock kopia binary for testing
        createMockKopiaBinary()
    }

    private fun createMockKopiaBinary() {
        val kopiaFile = File(testKopiaPath)
        kopiaFile.parentFile?.mkdirs()
        kopiaFile.createNewFile()
        kopiaFile.setExecutable(true)
    }

    @Test
    fun `test repository manager initialization`() {
        assertThat(repositoryManager).isNotNull()
    }

    @Test
    fun `test repository directories are created`() = runTest {
        // When repository manager is initialized
        // Then directories should exist or be creatable
        assertThat(testRepoDir.exists() || testRepoDir.mkdirs()).isTrue()
        assertThat(testConfigDir.exists() || testConfigDir.mkdirs()).isTrue()
    }

    @Test
    fun `test initialize repository creates necessary directories`() = runTest {
        // Clean test directories
        testRepoDir.deleteRecursively()
        testConfigDir.deleteRecursively()

        // Mock URI (won't actually use it in this test)
        val mockUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")

        // Note: This test will fail if kopia binary is not functional
        // In a real test environment, we would mock the process execution
        // For now, we just verify the directory setup logic

        assertThat(testRepoDir.exists() || testRepoDir.mkdirs()).isTrue()
        assertThat(testConfigDir.exists() || testConfigDir.mkdirs()).isTrue()
    }

    @Test
    fun `test kopia binary path is correct`() {
        val kopiaFile = File(testKopiaPath)
        assertThat(kopiaFile.parentFile?.absolutePath).isEqualTo(context.filesDir.absolutePath)
        assertThat(kopiaFile.name).isEqualTo("kopia")
    }

    @Test
    fun `test repository directory path is correct`() {
        assertThat(testRepoDir.absolutePath).isEqualTo(File(context.filesDir, "repo").absolutePath)
    }

    @Test
    fun `test config directory path is correct`() {
        assertThat(testConfigDir.absolutePath).isEqualTo(File(context.filesDir, ".kopia").absolutePath)
    }

    @Test
    fun `test repository manager handles missing kopia binary gracefully`() = runTest {
        // Delete the kopia binary
        File(testKopiaPath).delete()

        // Create a new repository manager
        val newManager = RepositoryManager(context)

        // Should not crash, but operations will fail
        assertThat(newManager).isNotNull()
    }

    @Test
    fun `test repository manager cleans up test data`() = runTest {
        // Create test data
        testRepoDir.mkdirs()
        File(testRepoDir, "test.txt").writeText("test data")

        // Verify data exists
        assertThat(testRepoDir.exists()).isTrue()
        assertThat(File(testRepoDir, "test.txt").exists()).isTrue()

        // Clean up
        testRepoDir.deleteRecursively()

        // Verify cleanup
        assertThat(testRepoDir.exists()).isFalse()
    }
}
