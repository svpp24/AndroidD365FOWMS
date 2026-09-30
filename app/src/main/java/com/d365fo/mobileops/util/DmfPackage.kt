package com.d365fo.mobileops.util

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A DMF data package is a zip containing `Manifest.xml`, `PackageHeader.xml`, and one data file
 * per entity in the project. `HSMobileOpsCountingExport`/`HSMobileOpsCountingImport` each carry
 * exactly one entity (`InventInventoryCountingJournalLineEntity`), so [unzip] expects exactly one
 * non-manifest file and treats it as the data file rather than assuming a specific name - the
 * real filename Data Management workspace generates for this entity is one of the open questions
 * for the first live round (see `aos/README.md`'s counting section), and guessing it wrong here
 * should fail loudly rather than silently pick the wrong entry.
 *
 * [rezip] reuses [DmfPackageFiles.manifestXml]/[DmfPackageFiles.packageHeaderXml] byte-for-byte
 * from the package D365FO itself generated on export - this app never constructs DMF package XML
 * from scratch, only edits the data file's own CSV content before re-zipping.
 */
object DmfPackage {

    fun unzip(bytes: ByteArray): DmfPackageFiles {
        var manifest: ByteArray? = null
        var packageHeader: ByteArray? = null
        var dataFileName: String? = null
        var dataFile: ByteArray? = null

        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                val content = zip.readBytes()
                when {
                    name.equals("Manifest.xml", ignoreCase = true) -> manifest = content
                    name.equals("PackageHeader.xml", ignoreCase = true) -> packageHeader = content
                    !entry.isDirectory -> {
                        dataFileName = name
                        dataFile = content
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        return DmfPackageFiles(
            manifestXml = manifest
                ?: error("DMF package has no Manifest.xml - not a real data package download"),
            packageHeaderXml = packageHeader
                ?: error("DMF package has no PackageHeader.xml - not a real data package download"),
            dataFileName = dataFileName
                ?: error("DMF package has no data file alongside its manifest"),
            dataFileBytes = dataFile!!,
        )
    }

    fun rezip(files: DmfPackageFiles): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("Manifest.xml"))
            zip.write(files.manifestXml)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("PackageHeader.xml"))
            zip.write(files.packageHeaderXml)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(files.dataFileName))
            zip.write(files.dataFileBytes)
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}

data class DmfPackageFiles(
    val manifestXml: ByteArray,
    val packageHeaderXml: ByteArray,
    val dataFileName: String,
    val dataFileBytes: ByteArray,
)

/**
 * A minimal, RFC4180-shaped CSV reader/writer for one DMF entity data file - no external
 * dependency, since the only things this app ever needs from a DMF CSV are "read every row as a
 * header-keyed map" and "write the same rows back with one column changed". Handles quoted
 * fields (D365 DMF quotes every string column), embedded commas and doubled-quote escaping;
 * does not handle embedded newlines inside a quoted field differently from Excel/D365's own
 * writer, which is the one thing a hand-rolled parser like this cannot fully guarantee without
 * a real sample to check against - see aos/README.md's counting section.
 */
object DmfCsv {

    fun parse(bytes: ByteArray): DmfCsvTable {
        val text = bytes.toString(Charsets.UTF_8)
        val rows = parseRows(text)
        if (rows.isEmpty()) return DmfCsvTable(emptyList(), emptyList())
        val header = rows.first()
        val dataRows = rows.drop(1).map { row ->
            header.indices.associate { i -> header[i] to row.getOrElse(i) { "" } }
        }
        return DmfCsvTable(header, dataRows)
    }

    fun write(table: DmfCsvTable): ByteArray {
        val sb = StringBuilder()
        sb.append(table.header.joinToString(",") { escape(it) }).append("\r\n")
        table.rows.forEach { row ->
            sb.append(table.header.joinToString(",") { escape(row[it].orEmpty()) }).append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    private fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var field = StringBuilder()
        var row = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"'); i++
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    row.add(field.toString()); field = StringBuilder()
                }
                c == '\r' -> Unit
                c == '\n' -> {
                    row.add(field.toString()); field = StringBuilder()
                    rows.add(row); row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows.filter { it.isNotEmpty() && !(it.size == 1 && it[0].isEmpty()) }
    }
}

data class DmfCsvTable(
    val header: List<String>,
    val rows: List<Map<String, String>>,
)

/**
 * Case-insensitive column lookup. D365's own field-mapping export shows this entity's real
 * staging field names as upper case (`ITEMNUMBER`, `COUNTEDQUANTITY`, ...), while Microsoft's
 * Common Data Model docs for the same entity show them PascalCase (`ItemNumber`,
 * `CountedQuantity`); which casing the exported CSV's own header row actually uses is not
 * confirmed either way, so every read goes through this rather than a plain `row["ItemNumber"]`
 * that would silently return null if the real header disagrees.
 */
fun Map<String, String>.getCI(key: String): String? =
    entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

/** The header's own exact spelling for [name], matched case-insensitively - needed when writing
 *  a new value back into a row map, since [DmfCsv.write] looks values up by the header's exact
 *  casing, not case-insensitively. */
fun List<String>.headerName(name: String): String? =
    firstOrNull { it.equals(name, ignoreCase = true) }
