package uz.ksnxdev.calamus

import java.io.File

object LegacyDataCleanup {
    private val archiveName = Regex("calamus_deleted_(?:messages|[0-9]+)\\.json(?:\\.(?:bak|new))?")

    @JvmStatic
    fun removeArchives(directory: File): Boolean {
        if (!directory.exists()) return true
        val files = directory.listFiles() ?: return false
        var success = true
        for (file in files) {
            if (file.isFile && archiveName.matches(file.name)) {
                success = file.delete() && success
            }
        }
        return success
    }
}
