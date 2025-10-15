package com.kopia.android.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Utility class for file operations between SAF and internal storage
 */
object FileUtils {
    
    /**
     * Copy a file from SAF URI to internal storage
     * 
     * @param context The application context
     * @param sourceUri The source URI (from SAF)
     * @param destFile The destination file in internal storage
     * @return True if copy was successful, false otherwise
     */
    fun copyFromSafToInternal(context: Context, sourceUri: Uri, destFile: File): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
            if (inputStream == null) {
                android.util.Log.e("FileUtils", "Failed to open input stream for URI: $sourceUri")
                return false
            }

            inputStream.use { input ->
                FileOutputStream(destFile).use { outputStream ->
                    val buffer = ByteArray(4 * 1024) // 4KB buffer
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("FileUtils", "Error copying from SAF to internal: ${e.message}")
            false
        }
    }
    
    /**
     * Copy a file from internal storage to SAF URI
     * 
     * @param context The application context
     * @param sourceFile The source file in internal storage
     * @param destUri The destination URI (from SAF)
     * @return True if copy was successful, false otherwise
     */
    fun copyFromInternalToSaf(context: Context, sourceFile: File, destUri: Uri): Boolean {
        return try {
            val outputStream = context.contentResolver.openOutputStream(destUri)
            if (outputStream == null) {
                android.util.Log.e("FileUtils", "Failed to open output stream for URI: $destUri")
                return false
            }

            outputStream.use { output ->
                sourceFile.inputStream().use { inputStream ->
                    val buffer = ByteArray(4 * 1024) // 4KB buffer
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("FileUtils", "Error copying from internal to SAF: ${e.message}")
            false
        }
    }
    
    /**
     * Copy a directory from SAF to internal storage
     * 
     * @param context The application context
     * @param sourceUri The source directory URI (from SAF)
     * @param destDir The destination directory in internal storage
     * @return True if copy was successful, false otherwise
     */
    fun copyDirectoryFromSafToInternal(context: Context, sourceUri: Uri, destDir: File): Boolean {
        try {
            val sourceDoc = DocumentFile.fromTreeUri(context, sourceUri) ?: return false

            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            // Copy all files in the directory
            for (file in sourceDoc.listFiles()) {
                val destFile = File(destDir, file.name ?: continue)

                if (file.isDirectory) {
                    // Recursively copy subdirectories
                    copyDirectoryFromSafToInternal(context, file.uri, destFile)
                } else {
                    // Copy file
                    copyFromSafToInternal(context, file.uri, destFile)
                }
            }

            return true
        } catch (e: Exception) {
            android.util.Log.e("FileUtils", "Error copying directory: ${e.message}")
            return false
        }
    }
    
    /**
     * Copy a directory from internal storage to SAF
     * 
     * @param context The application context
     * @param sourceDir The source directory in internal storage
     * @param destUri The destination directory URI (from SAF)
     * @return True if copy was successful, false otherwise
     */
    fun copyDirectoryFromInternalToSaf(context: Context, sourceDir: File, destUri: Uri): Boolean {
        try {
            val destDoc = DocumentFile.fromTreeUri(context, destUri) ?: return false

            // Copy all files in the directory
            val files = sourceDir.listFiles()
            if (files == null) {
                android.util.Log.e("FileUtils", "Failed to list files in directory: $sourceDir")
                return false
            }

            for (file in files) {
                val destFile = destDoc.createFile("*/*", file.name) ?: continue

                if (file.isDirectory) {
                    // Create subdirectory in destination
                    val newDir = destDoc.createDirectory(file.name) ?: continue
                    // Recursively copy subdirectories
                    copyDirectoryFromInternalToSaf(context, file, newDir.uri)
                } else {
                    // Copy file
                    copyFromInternalToSaf(context, file, destFile.uri)
                }
            }

            return true
        } catch (e: Exception) {
            android.util.Log.e("FileUtils", "Error copying directory to SAF: ${e.message}")
            return false
        }
    }

    /**
     * Try to get a real filesystem path from a content URI
     * This works for some content providers but not all (e.g., SAF tree URIs)
     *
     * @param context The application context
     * @param uri The content URI
     * @return The real path if available, null otherwise
     */
    fun getPathFromUri(context: Context, uri: Uri): String? {
        return try {
            android.util.Log.d("FileUtils", "getPathFromUri: uri=$uri scheme=${uri.scheme} authority=${uri.authority}")

            when {
                // Handle tree URIs (from ACTION_OPEN_DOCUMENT_TREE)
                uri.toString().contains("/tree/") && isExternalStorageDocument(uri) -> {
                    val treeDocumentId = DocumentsContract.getTreeDocumentId(uri)
                    android.util.Log.d("FileUtils", "Tree URI detected, treeDocumentId=$treeDocumentId")
                    val split = treeDocumentId.split(":")
                    val type = split[0]

                    if ("primary".equals(type, ignoreCase = true) && split.size > 1) {
                        val path = "/storage/emulated/0/${split[1]}"
                        android.util.Log.d("FileUtils", "Converted tree URI to path: $path")
                        return path
                    }
                    null
                }
                // DocumentProvider URIs
                DocumentsContract.isDocumentUri(context, uri) -> {
                    when {
                        // ExternalStorageProvider
                        isExternalStorageDocument(uri) -> {
                            val docId = DocumentsContract.getDocumentId(uri)
                            val split = docId.split(":")
                            val type = split[0]

                            if ("primary".equals(type, ignoreCase = true)) {
                                return "/storage/emulated/0/${split[1]}"
                            }
                            // TODO: Handle non-primary volumes if needed
                            null
                        }
                        // DownloadsProvider
                        isDownloadsDocument(uri) -> {
                            val docId = DocumentsContract.getDocumentId(uri)
                            // Handle raw paths in document ID
                            if (docId.startsWith("raw:")) {
                                return docId.removePrefix("raw:")
                            }
                            null
                        }
                        // MediaProvider
                        isMediaDocument(uri) -> {
                            val docId = DocumentsContract.getDocumentId(uri)
                            val split = docId.split(":")
                            val type = split[0]

                            val contentUri = when (type) {
                                "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                                else -> null
                            }

                            contentUri?.let {
                                val selection = "_id=?"
                                val selectionArgs = arrayOf(split[1])
                                getDataColumn(context, it, selection, selectionArgs)
                            }
                        }
                        else -> null
                    }
                }
                // File URIs
                "file".equals(uri.scheme, ignoreCase = true) -> {
                    uri.path
                }
                // Content URIs
                "content".equals(uri.scheme, ignoreCase = true) -> {
                    getDataColumn(context, uri, null, null)
                }
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.e("FileUtils", "Error getting path from URI: ${e.message}", e)
            null
        }
    }

    /**
     * Get the value of the data column for this Uri
     */
    private fun getDataColumn(
        context: Context,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)

        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(columnIndex)
            }
        } catch (e: Exception) {
            android.util.Log.e("FileUtils", "Error querying data column: ${e.message}")
        } finally {
            cursor?.close()
        }
        return null
    }

    /**
     * Check if a URI is from ExternalStorageProvider
     */
    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    /**
     * Check if a URI is from DownloadsProvider
     */
    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    /**
     * Check if a URI is from MediaProvider
     */
    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }
}
