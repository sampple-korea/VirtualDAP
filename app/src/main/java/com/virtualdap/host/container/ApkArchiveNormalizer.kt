package com.virtualdap.host.container

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class BundletoolDeviceProfile(
    val sdkVersion: Int,
    val supportedAbis: List<String>,
    val screenDensityDpi: Int,
    /** Bundletool texture aliases in serving-preference order. */
    val textureCompressionFormats: List<Int>,
)

/**
 * Converts an APK archive to one bounded, flattened APK cluster. A bundletool archive is first
 * reduced to the single variant matching this container process; generic XAPK/APKM-style archives
 * retain every APK and let the manifest-based container planner perform the final split checks.
 */
internal object ApkArchiveNormalizer {
    private const val MAX_TOC_BYTES = 8 * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 8_192
    private const val MAX_ARCHIVE_APKS = 4_096
    private const val MAX_SELECTED_APKS = 256

    /** Returns false when [source] is an ordinary APK rather than an outer APK archive. */
    fun normalizeIfArchive(
        source: File,
        output: File,
        profile: BundletoolDeviceProfile,
        maximumExpandedBytes: Long,
    ): Boolean {
        require(maximumExpandedBytes > 0) { "APK import limit must be positive" }
        require(profile.sdkVersion > 0 && profile.supportedAbis.isNotEmpty()) {
            "Android device profile is incomplete"
        }
        ZipFile(source).use { zip ->
            if (zip.getEntry("AndroidManifest.xml") != null) return false

            val apkEntries = linkedMapOf<String, ZipEntry>()
            var binaryToc: ZipEntry? = null
            var jsonToc: ZipEntry? = null
            var archiveEntries = 0
            for (entry in zip.entries().asSequence()) {
                archiveEntries += 1
                require(archiveEntries <= MAX_ARCHIVE_ENTRIES) { "APK archive contains too many entries" }
                if (!entry.isDirectory && entry.name == "toc.pb") {
                    require(binaryToc == null) { "APK archive contains duplicate toc.pb entries" }
                    binaryToc = entry
                } else if (!entry.isDirectory && entry.name == "toc.json") {
                    require(jsonToc == null) { "APK archive contains duplicate toc.json entries" }
                    jsonToc = entry
                }
                if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true)) continue
                validateEntryPath(entry.name)
                require(apkEntries.put(entry.name, entry) == null) {
                    "APK archive contains duplicate entry paths"
                }
                require(apkEntries.size <= MAX_ARCHIVE_APKS) { "APK archive contains too many variants" }
            }
            require(apkEntries.isNotEmpty()) { "No valid APK set was found" }

            require(binaryToc == null || jsonToc == null) {
                "APK archive contains both toc.pb and toc.json"
            }
            require(binaryToc != null || jsonToc == null) {
                "JSON-only bundletool archives are not supported; export a binary toc.pb APKS"
            }

            val selectedPaths = if (binaryToc != null) {
                val toc = zip.getInputStream(binaryToc).use {
                    readBounded(it, MAX_TOC_BYTES.toLong(), "Bundletool table of contents is too large")
                }
                BundletoolToc.select(toc, profile)
            } else {
                require(apkEntries.size <= MAX_SELECTED_APKS) { "APK set contains too many APKs" }
                apkEntries.keys.toList()
            }
            require(selectedPaths.size in 1..MAX_SELECTED_APKS) {
                "Selected APK set has an invalid APK count"
            }
            require(selectedPaths.toSet().size == selectedPaths.size) {
                "Bundletool table of contents selects an APK more than once"
            }

            FileOutputStream(output).use { fileOutput ->
                ZipOutputStream(fileOutput).use { normalized ->
                    var expanded = 0L
                    selectedPaths.forEachIndexed { index, path ->
                        validateEntryPath(path)
                        val sourceEntry = apkEntries[path]
                            ?: throw IllegalArgumentException("Bundletool APK is missing from the archive: $path")
                        val target = ZipEntry(String.format(Locale.ROOT, "part-%03d.apk", index + 1)).apply {
                            time = 0L
                        }
                        normalized.putNextEntry(target)
                        zip.getInputStream(sourceEntry).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                expanded += count
                                require(expanded <= maximumExpandedBytes) {
                                    "Expanded APK set exceeds the import limit"
                                }
                                normalized.write(buffer, 0, count)
                            }
                        }
                        normalized.closeEntry()
                    }
                    normalized.finish()
                    fileOutput.fd.sync()
                }
            }
        }
        return true
    }

    private fun validateEntryPath(path: String) {
        require(path.length in 1..1_024 && !path.startsWith('/') && '\\' !in path) {
            "APK archive contains an unsafe entry path"
        }
        val parts = path.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) }) {
            "APK archive contains an unsafe entry path"
        }
    }
}

/** Minimal, forward-rejecting reader for the official bundletool BuildApksResult wire format. */
private object BundletoolToc {
    private const val ABI_ARMEABI = 1
    private const val ABI_ARMEABI_V7A = 2
    private const val ABI_ARM64_V8A = 3
    private const val ABI_X86 = 4
    private const val ABI_X86_64 = 5
    private const val ABI_MIPS = 6
    private const val ABI_MIPS64 = 7
    private const val ABI_RISCV64 = 8

    private val abiNames = mapOf(
        ABI_ARMEABI to "armeabi",
        ABI_ARMEABI_V7A to "armeabi-v7a",
        ABI_ARM64_V8A to "arm64-v8a",
        ABI_X86 to "x86",
        ABI_X86_64 to "x86_64",
        ABI_MIPS to "mips",
        ABI_MIPS64 to "mips64",
        ABI_RISCV64 to "riscv64",
    )
    private val abiAliases = abiNames.entries.associate { (alias, name) -> name to alias }
    private val architectureOrder = mapOf(
        ABI_ARMEABI to 0,
        ABI_ARMEABI_V7A to 1,
        ABI_ARM64_V8A to 2,
        ABI_X86 to 3,
        ABI_X86_64 to 4,
        ABI_MIPS to 5,
        ABI_MIPS64 to 6,
        ABI_RISCV64 to 7,
    )

    private data class Dimension<T>(
        val values: List<T> = emptyList(),
        val alternatives: List<T> = emptyList(),
    )

    private data class VariantTargeting(
        val sdk: Dimension<Int?> = Dimension(),
        val abi: Dimension<Int> = Dimension(),
        val density: Dimension<Int> = Dimension(),
        val multiAbi: Dimension<Set<Int>> = Dimension(),
        val texture: Dimension<Int> = Dimension(),
        val requiresSdkRuntime: Boolean = false,
        val unsupported: Boolean = false,
    )

    private enum class ApkKind { SPLIT, STANDALONE, INSTANT, SYSTEM, ASSET, APEX, ARCHIVED }
    private data class Apk(val path: String, val kind: ApkKind)
    private data class Module(val name: String, val instant: Boolean, val apks: List<Apk>)
    private data class Variant(val targeting: VariantTargeting, val modules: List<Module>)
    private data class Toc(val variants: List<Variant>)

    fun select(bytes: ByteArray, profile: BundletoolDeviceProfile): List<String> {
        val toc = parseToc(bytes)
        require(toc.variants.isNotEmpty()) { "Bundletool table of contents contains no variants" }
        val regular = toc.variants.filterNot(::isInstantVariant)
        val matches = regular.filter { matches(it.targeting, profile) }
        require(matches.isNotEmpty()) {
            if (regular.any { it.targeting.unsupported }) {
                "APKS uses a newer unsupported bundletool targeting dimension"
            } else {
                "APKS contains no variant compatible with this Android process"
            }
        }
        require(matches.size == 1) { "APKS contains multiple variants matching this Android process" }

        val selected = matches.single().modules.asSequence()
            .filterNot { it.instant }
            .flatMap { it.apks.asSequence() }
            .filter { it.kind == ApkKind.SPLIT || it.kind == ApkKind.STANDALONE }
            .map { it.path }
            .toList()
        require(selected.isNotEmpty()) { "Matching APKS variant contains no installable APKs" }
        selected.forEach { path ->
            require(path.endsWith(".apk", ignoreCase = true)) {
                "Bundletool table of contents references a non-APK entry"
            }
            require(path.length in 1..1_024 && !path.startsWith('/') && '\\' !in path &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) }
            ) { "Bundletool table of contents references an unsafe APK path" }
        }
        return selected
    }

    private fun isInstantVariant(variant: Variant): Boolean {
        val apks = variant.modules.flatMap { it.apks }
        return apks.isNotEmpty() && apks.all { it.kind == ApkKind.INSTANT }
    }

    private fun matches(targeting: VariantTargeting, profile: BundletoolDeviceProfile): Boolean {
        if (targeting.unsupported || targeting.requiresSdkRuntime) return false
        val deviceAbis = profile.supportedAbis.map { name ->
            abiAliases[name] ?: throw IllegalArgumentException("Android reports an unsupported process ABI: $name")
        }
        return matchesSdk(targeting.sdk, profile.sdkVersion) &&
            matchesOrdered(targeting.abi, deviceAbis) &&
            matchesMultiAbi(targeting.multiAbi, deviceAbis.toSet()) &&
            matchesDensity(targeting.density, profile.screenDensityDpi) &&
            matchesOrdered(targeting.texture, profile.textureCompressionFormats)
    }

    private fun matchesSdk(targeting: Dimension<Int?>, sdk: Int): Boolean {
        require(targeting.values.size <= 1) { "APKS has invalid SDK targeting" }
        val value = targeting.values.singleOrNull()
        val minimum = value ?: 0
        if (minimum > sdk) return false
        return targeting.alternatives.none { alternative ->
            val candidate = alternative ?: 0
            candidate <= sdk && candidate > minimum
        }
    }

    private fun <T> matchesOrdered(
        targeting: Dimension<T>,
        supported: List<T>,
    ): Boolean {
        if (targeting.values.isEmpty() && targeting.alternatives.isEmpty()) return true
        require(targeting.values.toSet().intersect(targeting.alternatives.toSet()).isEmpty()) {
            "APKS targeting values overlap their alternatives"
        }
        for (candidate in supported) {
            if (candidate in targeting.values) return true
            if (candidate in targeting.alternatives) return false
        }
        return targeting.values.isEmpty() && targeting.alternatives.isNotEmpty()
    }

    private fun matchesMultiAbi(targeting: Dimension<Set<Int>>, deviceAbis: Set<Int>): Boolean {
        if (targeting.values.isEmpty() && targeting.alternatives.isEmpty()) return true
        val matchingValues = targeting.values.filter(deviceAbis::containsAll)
        if (matchingValues.isEmpty()) return false
        return targeting.alternatives.none { alternative ->
            deviceAbis.containsAll(alternative) && matchingValues.all { value ->
                compareMultiAbi(alternative, value) > 0
            }
        }
    }

    private fun compareMultiAbi(left: Set<Int>, right: Set<Int>): Int {
        val leftOrdered = left.sortedByDescending { architectureOrder[it] ?: Int.MIN_VALUE }
        val rightOrdered = right.sortedByDescending { architectureOrder[it] ?: Int.MIN_VALUE }
        for (index in 0 until minOf(leftOrdered.size, rightOrdered.size)) {
            val compared = (architectureOrder[leftOrdered[index]] ?: -1)
                .compareTo(architectureOrder[rightOrdered[index]] ?: -1)
            if (compared != 0) return compared
        }
        return leftOrdered.size.compareTo(rightOrdered.size)
    }

    private fun matchesDensity(targeting: Dimension<Int>, desired: Int): Boolean {
        val all = (targeting.values + targeting.alternatives).distinct()
        if (all.isEmpty() || desired == 0) return true
        val best = all.reduce { current, candidate ->
            if (compareDensity(candidate, current, desired) > 0) candidate else current
        }
        return best in targeting.values
    }

    /** Positive means [left] is the better Android resource density. */
    private fun compareDensity(left: Int, right: Int, desired: Int): Int {
        if (left == right) return 0
        if (left == 0xfffe) return 1
        if (right == 0xfffe) return -1
        val lower = minOf(left, right)
        val higher = maxOf(left, right)
        val lowerBetter = when {
            desired >= higher -> false
            desired <= lower -> true
            else -> ((2L * lower - desired) * higher) > desired.toLong() * desired
        }
        val preferred = if (lowerBetter) lower else higher
        return if (left == preferred) 1 else -1
    }

    private fun parseToc(bytes: ByteArray): Toc {
        val reader = ProtoReader(bytes)
        val variants = mutableListOf<Variant>()
        while (true) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> variants += parseVariant(reader.readMessage(field))
                else -> reader.skip(field)
            }
        }
        return Toc(variants)
    }

    private fun parseVariant(bytes: ByteArray): Variant {
        val reader = ProtoReader(bytes)
        var targeting = VariantTargeting()
        val modules = mutableListOf<Module>()
        while (true) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> targeting = parseVariantTargeting(reader.readMessage(field))
                2 -> modules += parseApkSet(reader.readMessage(field))
                else -> reader.skip(field)
            }
        }
        return Variant(targeting, modules)
    }

    private fun parseVariantTargeting(bytes: ByteArray): VariantTargeting {
        val reader = ProtoReader(bytes)
        var sdk = Dimension<Int?>()
        var abi = Dimension<Int>()
        var density = Dimension<Int>()
        var multiAbi = Dimension<Set<Int>>()
        var texture = Dimension<Int>()
        var sdkRuntime = false
        var unsupported = false
        while (true) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> sdk = parseSdkTargeting(reader.readMessage(field))
                2 -> abi = parseScalarTargeting(reader.readMessage(field), ::parseAbiAlias)
                3 -> density = parseScalarTargeting(reader.readMessage(field), ::parseDensity)
                4 -> multiAbi = parseScalarTargeting(reader.readMessage(field), ::parseMultiAbi)
                5 -> texture = parseScalarTargeting(reader.readMessage(field), ::parseTextureAlias)
                6 -> sdkRuntime = parseSdkRuntime(reader.readMessage(field))
                else -> {
                    unsupported = true
                    reader.skip(field)
                }
            }
        }
        return VariantTargeting(sdk, abi, density, multiAbi, texture, sdkRuntime, unsupported)
    }

    private fun parseSdkTargeting(bytes: ByteArray): Dimension<Int?> {
        return parseScalarTargeting(bytes) { sdkBytes ->
            val reader = ProtoReader(sdkBytes)
            var minimum: Int? = null
            while (true) {
                val field = reader.nextField() ?: break
                if (field.number == 1) {
                    val wrapper = ProtoReader(reader.readMessage(field))
                    while (true) {
                        val wrapped = wrapper.nextField() ?: break
                        if (wrapped.number == 1) {
                            minimum = wrapper.readNonNegativeInt32(wrapped, "SDK version")
                        }
                        else wrapper.skip(wrapped)
                    }
                } else reader.skip(field)
            }
            minimum
        }
    }

    private fun <T> parseScalarTargeting(bytes: ByteArray, parse: (ByteArray) -> T): Dimension<T> {
        val reader = ProtoReader(bytes)
        val values = mutableListOf<T>()
        val alternatives = mutableListOf<T>()
        while (true) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> values += parse(reader.readMessage(field))
                2 -> alternatives += parse(reader.readMessage(field))
                else -> reader.skip(field)
            }
        }
        return Dimension(values, alternatives)
    }

    private fun parseAbiAlias(bytes: ByteArray): Int {
        val alias = parseAlias(bytes, "ABI")
        require(alias in abiNames) { "APKS contains an unknown ABI alias" }
        return alias
    }

    private fun parseTextureAlias(bytes: ByteArray): Int {
        val alias = parseAlias(bytes, "texture compression format")
        require(alias in 0..10) { "APKS contains an unknown texture compression format alias" }
        return alias
    }

    private fun parseAlias(bytes: ByteArray, label: String): Int {
        val reader = ProtoReader(bytes)
        var alias: Int? = null
        while (true) {
            val field = reader.nextField() ?: break
            if (field.number == 1) alias = reader.readNonNegativeInt32(field, label) else reader.skip(field)
        }
        return requireNotNull(alias) { "APKS contains an empty $label target" }
    }

    private fun parseMultiAbi(bytes: ByteArray): Set<Int> {
        val reader = ProtoReader(bytes)
        val aliases = linkedSetOf<Int>()
        while (true) {
            val field = reader.nextField() ?: break
            if (field.number == 1) aliases += parseAbiAlias(reader.readMessage(field)) else reader.skip(field)
        }
        require(aliases.isNotEmpty()) { "APKS contains an empty multi-ABI target" }
        return aliases
    }

    private fun parseDensity(bytes: ByteArray): Int {
        val reader = ProtoReader(bytes)
        var density: Int? = null
        while (true) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> density = when (reader.readNonNegativeInt32(field, "density alias")) {
                    0 -> 0
                    1 -> 0xffff
                    2 -> 120
                    3 -> 160
                    4 -> 213
                    5 -> 240
                    6 -> 320
                    7 -> 480
                    8 -> 640
                    else -> throw IllegalArgumentException("APKS contains an unknown density alias")
                }
                2 -> density = reader.readNonNegativeInt32(field, "density DPI")
                else -> reader.skip(field)
            }
        }
        return requireNotNull(density) { "APKS contains an empty density target" }
    }

    private fun parseSdkRuntime(bytes: ByteArray): Boolean {
        val reader = ProtoReader(bytes)
        var required = false
        while (true) {
            val field = reader.nextField() ?: break
            if (field.number == 1) required = reader.readVarint(field) != 0L else reader.skip(field)
        }
        return required
    }

    private fun parseApkSet(bytes: ByteArray): Module {
        val reader = ProtoReader(bytes)
        var name = ""
        var instant = false
        val apks = mutableListOf<Apk>()
        while (true) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> {
                    val metadata = ProtoReader(reader.readMessage(field))
                    while (true) {
                        val item = metadata.nextField() ?: break
                        when (item.number) {
                            1 -> name = metadata.readString(item)
                            3 -> instant = metadata.readVarint(item) != 0L
                            else -> metadata.skip(item)
                        }
                    }
                }
                2 -> apks += parseApk(reader.readMessage(field))
                else -> reader.skip(field)
            }
        }
        require(name.isNotBlank()) { "APKS contains a module without a name" }
        return Module(name, instant, apks)
    }

    private fun parseApk(bytes: ByteArray): Apk {
        val reader = ProtoReader(bytes)
        var path = ""
        var kind: ApkKind? = null
        var kindSeen = false
        while (true) {
            val field = reader.nextField() ?: break
            when (field.number) {
                2 -> path = reader.readString(field)
                in 3..9 -> {
                    require(!kindSeen) { "APKS contains conflicting APK metadata" }
                    kindSeen = true
                    kind = when (field.number) {
                        3 -> ApkKind.SPLIT
                        4 -> ApkKind.STANDALONE
                        5 -> ApkKind.INSTANT
                        6 -> ApkKind.SYSTEM
                        7 -> ApkKind.ASSET
                        8 -> ApkKind.APEX
                        else -> ApkKind.ARCHIVED
                    }
                    reader.skip(field)
                }
                1, 10 -> reader.skip(field)
                else -> throw IllegalArgumentException("APKS uses newer unsupported APK metadata")
            }
        }
        require(path.isNotBlank()) { "APKS contains an APK without a path" }
        return Apk(path, requireNotNull(kind) { "APKS contains an APK without install metadata" })
    }
}

private data class ProtoField(val number: Int, val wireType: Int)

private class ProtoReader(private val data: ByteArray) {
    private var position = 0

    fun nextField(): ProtoField? {
        if (position == data.size) return null
        val tag = readRawVarint()
        val number = (tag ushr 3).toInt()
        val wire = (tag and 7).toInt()
        require(number > 0) { "Invalid protobuf field number" }
        return ProtoField(number, wire)
    }

    fun readVarint(field: ProtoField): Long {
        require(field.wireType == 0) { "Unexpected protobuf wire type" }
        return readRawVarint()
    }

    fun readNonNegativeInt32(field: ProtoField, label: String): Int {
        val value = readVarint(field)
        require(value in 0..Int.MAX_VALUE.toLong()) { "APKS contains an invalid $label" }
        return value.toInt()
    }

    fun readMessage(field: ProtoField): ByteArray {
        require(field.wireType == 2) { "Unexpected protobuf wire type" }
        val length = readRawVarint()
        require(length >= 0 && length <= Int.MAX_VALUE && length <= data.size - position) {
            "Truncated protobuf message"
        }
        val end = position + length.toInt()
        return data.copyOfRange(position, end).also { position = end }
    }

    fun readString(field: ProtoField): String {
        val bytes = readMessage(field)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    fun skip(field: ProtoField) {
        when (field.wireType) {
            0 -> readRawVarint()
            1 -> advance(8)
            2 -> {
                val length = readRawVarint()
                require(length >= 0 && length <= Int.MAX_VALUE) { "Oversized protobuf field" }
                advance(length.toInt())
            }
            5 -> advance(4)
            else -> throw IllegalArgumentException("Unsupported protobuf wire type")
        }
    }

    private fun advance(bytes: Int) {
        require(bytes >= 0 && bytes <= data.size - position) { "Truncated protobuf field" }
        position += bytes
    }

    private fun readRawVarint(): Long {
        var value = 0L
        for (shift in 0 until 64 step 7) {
            if (position >= data.size) throw EOFException("Truncated protobuf varint")
            val next = data[position++].toInt() and 0xff
            if (shift == 63 && next > 1) throw IllegalArgumentException("Protobuf varint overflow")
            value = value or ((next and 0x7f).toLong() shl shift)
            if (next and 0x80 == 0) return value
        }
        throw IllegalArgumentException("Protobuf varint overflow")
    }
}

private fun readBounded(input: java.io.InputStream, maximum: Long, message: String): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maximum) { message }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
