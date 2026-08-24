package com.flashcardreader.app.data.db

import org.json.JSONObject

/**
 * Compares the schema Room expects against the SQL the migrations actually run.
 *
 * This exists because of a specific failure. v5's migration created an index the entity never
 * declared; Room compared the two at open time, refused the database, and every phone that
 * upgraded crash-looped on launch. Nothing in the build caught it, and nothing could have: schema
 * comparison happens inside a real SQLite database, a clean install never runs a migration, and
 * there is no emulator in CI.
 *
 * So the comparison is done here instead, on plain strings, by a JVM test. Not a substitute for
 * installing over a real copy - it cannot see what SQLite would actually do - but it catches the
 * class of mistake that has actually broken this app.
 */
object SchemaGuard {

    /**
     * Columns whose migration writes a SQL DEFAULT that the entity does not declare.
     *
     * Room tolerates this - it only compares defaults it was told to expect - so these three are
     * harmless and are left alone. They are named rather than allowed by rule so the pattern
     * cannot spread: anything new must declare the default on both sides.
     */
    private val LEGACY_UNDECLARED_DEFAULTS = setOf(
        "terms.curious",
        "terms.selfNote",
        "terms.hyperMiss",
    )

    private val ADD_COLUMN = Regex(
        """ALTER\s+TABLE\s+(\w+)\s+ADD\s+COLUMN\s+(\w+)\s+(.*)""",
        RegexOption.IGNORE_CASE,
    )
    private val CREATE_TABLE = Regex(
        """CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+`?(\w+)`?\s*\(""",
        RegexOption.IGNORE_CASE,
    )
    private val CREATE_INDEX = Regex(
        """CREATE\s+(?:UNIQUE\s+)?INDEX(?:\s+IF\s+NOT\s+EXISTS)?\s+`?(\w+)`?\s+ON\s+`?(\w+)`?""",
        RegexOption.IGNORE_CASE,
    )
    private val DEFAULT_LITERAL = Regex("""\bDEFAULT\s+(.+?)\s*$""", RegexOption.IGNORE_CASE)

    /**
     * Returns one line per problem found, empty when the migrations and the schema agree.
     *
     * [schemaJson] is the file Room exports to `app/schemas/<database>/<version>.json`.
     */
    fun check(
        schemaJson: String,
        migrationsByFromVersion: Map<Int, List<String>>,
        latestVersion: Int,
    ): List<String> {
        val problems = mutableListOf<String>()
        val database = JSONObject(schemaJson).getJSONObject("database")

        val exportedVersion = database.getInt("version")
        if (exportedVersion != latestVersion) {
            problems += "Room exports version $exportedVersion but MigrationSql goes up to $latestVersion"
        }
        // A gap here means an upgrade from that version has no path and Room throws at open time.
        val expectedSteps = (1 until latestVersion).toSet()
        val actualSteps = migrationsByFromVersion.keys
        if (actualSteps != expectedSteps) {
            problems += "migrations cover from-versions $actualSteps, expected $expectedSteps"
        }

        val schema = schemaTables(database)

        // Replay the migrations, tracking only tables a migration created - the rest were created
        // by Room itself at v1 and there is no SQL here to compare them against.
        val built = LinkedHashMap<String, MutableSet<String>>()
        for (from in migrationsByFromVersion.keys.sorted()) {
            for (statement in migrationsByFromVersion.getValue(from)) {
                val flat = statement.replace('\n', ' ')

                CREATE_TABLE.find(flat)?.let { match ->
                    val table = match.groupValues[1]
                    built[table] = columnsInCreateTable(statement).toMutableSet()
                }

                ADD_COLUMN.find(flat)?.let { match ->
                    val (table, column, rest) = match.destructured
                    built[table]?.add(column)
                    problems += checkDefault(table, column, rest, schema)
                }

                CREATE_INDEX.find(flat)?.let { match ->
                    val name = match.groupValues[1]
                    val table = match.groupValues[2]
                    val declared = schema[table]?.indices ?: emptySet()
                    if (name !in declared) {
                        problems += "migration $from->${from + 1} creates index `$name` on `$table`, " +
                            "which the entity does not declare - this is the v5 crash: Room will " +
                            "refuse to open the database. Add @Index(name = \"$name\", ...) to the entity."
                    }
                }
            }
        }

        for ((table, columns) in built) {
            val expected = schema[table]?.columns
            if (expected == null) {
                problems += "migrations create table `$table`, which is not in the exported schema"
                continue
            }
            (expected - columns).sorted().forEach {
                problems += "`$table`.`$it` is in the entity but no migration adds it"
            }
            (columns - expected).sorted().forEach {
                problems += "migrations add `$table`.`$it`, which the entity does not have"
            }
        }

        return problems
    }

    private fun checkDefault(
        table: String,
        column: String,
        rest: String,
        schema: Map<String, Table>,
    ): List<String> {
        val inSql = DEFAULT_LITERAL.find(rest)?.groupValues?.get(1)?.trim()
        val declared = schema[table]?.defaults?.get(column)
        return when {
            inSql != null && declared != null && inSql != declared ->
                listOf(
                    "`$table`.`$column` defaults to $inSql in the migration but " +
                        "$declared on the entity - Room compares these literally and will refuse " +
                        "to open the database",
                )
            inSql != null && declared == null && "$table.$column" !in LEGACY_UNDECLARED_DEFAULTS ->
                listOf(
                    "`$table`.`$column` has a SQL DEFAULT of $inSql but the entity declares none. " +
                        "Add @ColumnInfo(defaultValue = ...) with the identical literal.",
                )
            inSql == null && declared != null ->
                listOf(
                    "`$table`.`$column` declares @ColumnInfo(defaultValue = $declared) but the " +
                        "migration adds it with no DEFAULT",
                )
            else -> emptyList()
        }
    }

    private class Table(
        val columns: Set<String>,
        val defaults: Map<String, String>,
        val indices: Set<String>,
    )

    private fun schemaTables(database: JSONObject): Map<String, Table> {
        val out = LinkedHashMap<String, Table>()
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val columns = LinkedHashSet<String>()
            val defaults = LinkedHashMap<String, String>()
            val fields = entity.getJSONArray("fields")
            for (f in 0 until fields.length()) {
                val field = fields.getJSONObject(f)
                val name = field.getString("columnName")
                columns += name
                if (field.has("defaultValue")) defaults[name] = field.getString("defaultValue").trim()
            }
            val indices = LinkedHashSet<String>()
            val declared = entity.optJSONArray("indices")
            if (declared != null) {
                for (n in 0 until declared.length()) indices += declared.getJSONObject(n).getString("name")
            }
            out[entity.getString("tableName")] = Table(columns, defaults, indices)
        }
        return out
    }

    /** Column names from a CREATE TABLE body, skipping table-level constraints. */
    private fun columnsInCreateTable(sql: String): Set<String> {
        val open = sql.indexOf('(')
        val close = sql.lastIndexOf(')')
        if (open < 0 || close <= open) return emptySet()
        val body = sql.substring(open + 1, close)
        val parts = mutableListOf<StringBuilder>(StringBuilder())
        var depth = 0
        for (c in body) {
            when {
                c == '(' -> { depth++; parts.last().append(c) }
                c == ')' -> { depth--; parts.last().append(c) }
                c == ',' && depth == 0 -> parts.add(StringBuilder())
                else -> parts.last().append(c)
            }
        }
        return parts
            .map { it.toString().trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { part ->
                val first = part.substringBefore(' ').trim('`')
                if (first.uppercase() in TABLE_CONSTRAINTS) null else first
            }
            .toSet()
    }

    private val TABLE_CONSTRAINTS = setOf("PRIMARY", "FOREIGN", "UNIQUE", "CHECK", "CONSTRAINT")
}
