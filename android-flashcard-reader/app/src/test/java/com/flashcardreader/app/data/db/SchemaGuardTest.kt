package com.flashcardreader.app.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one automated defence against a migration that crashes the app on launch.
 *
 * v5 created an index the entity never declared. Room refused to open the database and the app
 * crash-looped for everyone who upgraded - and no test could have caught it, because a clean
 * install never runs a migration and CI has no emulator. This compares Room's own exported schema
 * against the migration SQL on an ordinary JVM.
 */
class SchemaGuardTest {

    @Test
    fun `the real migrations agree with the schema Room expects`() {
        val schema = findExportedSchema()
        val problems = SchemaGuard.check(
            schemaJson = schema.readText(),
            migrationsByFromVersion = MigrationSql.BY_FROM_VERSION,
            latestVersion = MigrationSql.LATEST_VERSION,
        )
        assertEquals(
            "migration/schema mismatch - this is the bug that crash-loops the app on upgrade:\n" +
                problems.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            problems,
        )
    }

    /**
     * Room writes this during the build. If it is missing the guard is silently doing nothing, so
     * fail loudly rather than passing on an empty check.
     */
    private fun findExportedSchema(): File {
        val relative = "schemas/com.flashcardreader.app.data.db.AppDatabase/${MigrationSql.LATEST_VERSION}.json"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            val underApp = File(dir, "app/$relative")
            if (underApp.isFile) return underApp
            dir = dir.parentFile
        }
        throw AssertionError(
            "No exported schema found at $relative. Room writes it when " +
                "ksp { arg(\"room.schemaLocation\", ...) } is set and exportSchema = true; " +
                "without it this test guards nothing.",
        )
    }

    // ------------------------------------------------------------------ the guard itself

    private val termsSchema = """
        {"formatVersion":1,"database":{"version":2,"entities":[
          {"tableName":"terms",
           "createSql":"CREATE TABLE IF NOT EXISTS `terms` (`id` INTEGER NOT NULL, PRIMARY KEY(`id`))",
           "fields":[
             {"fieldPath":"id","columnName":"id","affinity":"INTEGER","notNull":true},
             {"fieldPath":"note","columnName":"note","affinity":"TEXT","notNull":true,"defaultValue":"''"}
           ],
           "indices":[]}
        ]}}
    """.trimIndent()

    @Test
    fun `an index created by a migration but not declared on the entity is caught`() {
        val schema = """
            {"formatVersion":1,"database":{"version":2,"entities":[
              {"tableName":"logs",
               "createSql":"CREATE TABLE IF NOT EXISTS `logs` (`id` INTEGER NOT NULL, `card` INTEGER NOT NULL, PRIMARY KEY(`id`))",
               "fields":[
                 {"fieldPath":"id","columnName":"id","affinity":"INTEGER","notNull":true},
                 {"fieldPath":"card","columnName":"card","affinity":"INTEGER","notNull":true}
               ],
               "indices":[]}
            ]}}
        """.trimIndent()
        val problems = SchemaGuard.check(
            schema,
            mapOf(
                1 to listOf(
                    "CREATE TABLE IF NOT EXISTS logs (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, card INTEGER NOT NULL)",
                    "CREATE INDEX IF NOT EXISTS index_logs_card ON logs (card)",
                ),
            ),
            latestVersion = 2,
        )
        assertTrue(
            "the v5 crash must be caught, got $problems",
            problems.any { it.contains("index_logs_card") && it.contains("v5 crash") },
        )
    }

    @Test
    fun `a default that differs between the migration and the entity is caught`() {
        val problems = SchemaGuard.check(
            termsSchema,
            mapOf(1 to listOf("ALTER TABLE terms ADD COLUMN note TEXT NOT NULL DEFAULT 'x'")),
            latestVersion = 2,
        )
        assertTrue("$problems", problems.any { it.contains("`terms`.`note`") && it.contains("refuse") })
    }

    @Test
    fun `a declared default the migration forgot is caught`() {
        val problems = SchemaGuard.check(
            termsSchema,
            mapOf(1 to listOf("ALTER TABLE terms ADD COLUMN note TEXT NOT NULL")),
            latestVersion = 2,
        )
        assertTrue("$problems", problems.any { it.contains("no DEFAULT") })
    }

    @Test
    fun `matching default and index raise nothing`() {
        val problems = SchemaGuard.check(
            termsSchema,
            mapOf(1 to listOf("ALTER TABLE terms ADD COLUMN note TEXT NOT NULL DEFAULT ''")),
            latestVersion = 2,
        )
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `a column added to the entity but not to the migration is caught`() {
        val schema = """
            {"formatVersion":1,"database":{"version":2,"entities":[
              {"tableName":"logs",
               "createSql":"CREATE TABLE IF NOT EXISTS `logs` (`id` INTEGER NOT NULL, PRIMARY KEY(`id`))",
               "fields":[
                 {"fieldPath":"id","columnName":"id","affinity":"INTEGER","notNull":true},
                 {"fieldPath":"extra","columnName":"extra","affinity":"TEXT","notNull":true}
               ],
               "indices":[]}
            ]}}
        """.trimIndent()
        val problems = SchemaGuard.check(
            schema,
            mapOf(1 to listOf("CREATE TABLE IF NOT EXISTS logs (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT)")),
            latestVersion = 2,
        )
        assertTrue("$problems", problems.any { it.contains("`logs`.`extra`") })
    }

    @Test
    fun `a version bumped without a migration is caught`() {
        val problems = SchemaGuard.check(
            termsSchema.replace("\"version\":2", "\"version\":3"),
            mapOf(1 to listOf("ALTER TABLE terms ADD COLUMN note TEXT NOT NULL DEFAULT ''")),
            latestVersion = 3,
        )
        assertTrue("$problems", problems.any { it.contains("expected [1, 2]") })
    }
}
