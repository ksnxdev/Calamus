import uz.ksnxdev.calamus.LegacyDataCleanup
import java.nio.file.Files

fun main() {
    val directory = Files.createTempDirectory("calamus-migration-").toFile()
    try {
        val archives = listOf(
            "calamus_deleted_messages.json", "calamus_deleted_messages.json.bak",
            "calamus_deleted_123.json", "calamus_deleted_456.json.new",
            "calamus_deleted_9876543210.json.bak"
        )
        val unrelated = listOf(
            "messages.db", "calamus_config.json", "calamus_deleted_other.json",
            "calamus_deleted_123.json.backup", "prefix_calamus_deleted_123.json"
        )
        for (name in archives + unrelated) directory.resolve(name).writeText("private fixture")
        val subdirectory = directory.resolve("calamus_deleted_789.json")
        check(subdirectory.mkdir())
        subdirectory.resolve("keep.json").writeText("keep")
        check(LegacyDataCleanup.removeArchives(directory))
        for (name in archives) check(!directory.resolve(name).exists()) { name }
        for (name in unrelated) check(directory.resolve(name).readText() == "private fixture") { name }
        check(subdirectory.resolve("keep.json").readText() == "keep")
        check(LegacyDataCleanup.removeArchives(directory))
        check(LegacyDataCleanup.removeArchives(directory.resolve("missing")))
        check(!LegacyDataCleanup.removeArchives(directory.resolve("messages.db")))
        println("Legacy archive migration: passed")
    } finally {
        directory.deleteRecursively()
    }
}
