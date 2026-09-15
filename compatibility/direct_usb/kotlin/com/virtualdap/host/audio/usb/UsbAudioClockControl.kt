package com.virtualdap.host.audio.usb

/** One USB control transaction using the descriptor granted by Android's UsbManager. */
fun interface UsbAudioControlPipe {
    fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray): Int
}

class UsbAudioClockControl(
    private val pipe: UsbAudioControlPipe,
    private val validityAttempts: Int = 40,
    private val validityDelayMillis: Long = 25,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {
    init {
        require(validityAttempts in 1..200 && validityDelayMillis in 0..1_000)
    }

    fun supportedRates(profile: UsbAudioStreamingProfile): List<UsbSampleRateRange> {
        if (profile.protocol == 0) return profile.rates
        require(profile.protocol == 0x20) { "Unsupported USB Audio protocol" }
        return supportedClockPaths(profile)
            .flatMap(ClockSupport::ranges)
            .distinct()
            .sortedWith(compareBy(UsbSampleRateRange::minimum, UsbSampleRateRange::maximum, UsbSampleRateRange::resolution))
    }

    private fun readClockRanges(profile: UsbAudioStreamingProfile, source: ClockSource): List<UsbSampleRateRange> {
        val index = clockIndex(profile, source.id)
        val countBytes = ByteArray(2)
        readExact(0xa1, 2, 0x100, index, countBytes)
        val count = countBytes.u16(0)
        require(count in 1..256) { "Invalid number of USB clock ranges" }
        val ranges = ByteArray(2 + count * 12)
        readExact(0xa1, 2, 0x100, index, ranges)
        return UsbAudioDescriptors.parseClockRanges(ranges)
    }

    /** Set a declared rate and read it back. A rejected or different rate is a negotiation error. */
    fun setAndVerify(profile: UsbAudioStreamingProfile, sampleRate: Int) {
        require(sampleRate in 8_000..6_144_000) { "Invalid requested USB audio rate" }
        if (profile.protocol == 0x20) {
            setAndVerifyUac2(profile, sampleRate)
            return
        }
        require(profile.protocol == 0) { "Unsupported USB Audio protocol" }
        require(profile.rates.any { it.contains(sampleRate) }) { "USB interface does not advertise $sampleRate Hz" }
        if (profile.protocol == 0 && !profile.endpointFrequencyControl) {
            require(profile.rates.size == 1 &&
                profile.rates.single().minimum == profile.rates.single().maximum
            ) { "USB interface offers multiple rates but no frequency control" }
            return // The alternate setting itself fixes this single rate.
        }
        val index = profile.endpointAddress
        val value = ByteArray(3) { byte -> (sampleRate ushr (byte * 8)).toByte() }
        val written = pipe.transfer(0x22, 1, 0x100, index, value)
        check(written == value.size) { "USB DAC rejected the requested sample rate" }
        val readback = ByteArray(value.size)
        readExact(0xa2, 0x81, 0x100, index, readback)
        val actual = readback.littleEndian()
        check(actual == sampleRate) { "USB DAC reports $actual Hz after requesting $sampleRate Hz" }
    }

    private fun setAndVerifyUac2(profile: UsbAudioStreamingProfile, sampleRate: Int) {
        val matching = supportedClockPaths(profile).mapNotNull { support ->
            val sourceRate = support.ratio.sourceRate(sampleRate) ?: return@mapNotNull null
            if (support.sourceRanges.none { it.contains(sourceRate) }) return@mapNotNull null
            ClockAttempt(support, sourceRate)
        }
        require(matching.isNotEmpty()) { "USB interface does not advertise $sampleRate Hz" }
        val failures = mutableListOf<String>()
        matching.sortedBy { it.support.selectorChanges }.forEach { attempt ->
            val support = attempt.support
            val restore = try {
                selectPath(profile, support.path)
            } catch (failure: Exception) {
                failures += failure.message ?: failure.javaClass.simpleName
                return@forEach
            }
            try {
                setSourceRate(profile, support.path.source, attempt.sourceRate)
                awaitValidClock(profile, support.path.source)
                return
            } catch (failure: Exception) {
                runCatching(restore)
                failures += failure.message ?: failure.javaClass.simpleName
            }
        }
        throw IllegalStateException(
            "No UAC2 clock path accepted $sampleRate Hz: ${failures.distinct().joinToString("; ")}",
        )
    }

    private fun supportedClockPaths(profile: UsbAudioStreamingProfile): List<ClockSupport> {
        val paths = clockPaths(profile)
        val selectors = paths.flatMap(ClockPath::selectors).map(ClockSelection::selector).distinctBy { it.id }
        val selections = selectors.associate { selector ->
            selector.id to when {
                selector.sourceIds.size == 1 -> 1
                controlReadable(selector.controls, 1) -> runCatching {
                    readSelector(profile, selector)
                }.getOrNull()
                else -> null
            }
        }
        val selectable = paths.mapNotNull { path ->
            var changes = 0
            for (selection in path.selectors) {
                if (selection.selector.sourceIds.size == 1 || selections[selection.selector.id] == selection.pin) continue
                if (selections[selection.selector.id] == null ||
                    !controlReadable(selection.selector.controls, 1) ||
                    !controlWritable(selection.selector.controls, 1)
                ) return@mapNotNull null
                changes++
            }
            path to changes
        }
        check(selectable.isNotEmpty()) { "No readable or selectable UAC2 clock path" }

        val rangeResults = mutableMapOf<Int, Result<List<UsbSampleRateRange>>>()
        val supported = selectable.mapNotNull { (path, changes) ->
            val ratio = runCatching { readMultiplierRatio(profile, path) }.getOrNull()
                ?: return@mapNotNull null
            val result = rangeResults.getOrPut(path.source.id) {
                runCatching { readClockRanges(profile, path.source) }
            }
            result.getOrNull()?.let { sourceRanges ->
                val terminalRanges = sourceRanges.mapNotNull(ratio::terminalRange)
                terminalRanges.takeIf(List<UsbSampleRateRange>::isNotEmpty)?.let {
                    ClockSupport(path, sourceRanges, it, ratio, changes)
                }
            }
        }
        if (supported.isEmpty()) {
            val detail = rangeResults.values.mapNotNull { it.exceptionOrNull()?.message }.distinct().joinToString("; ")
            throw IllegalStateException("No UAC2 clock source exposes sample-rate ranges${detail.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty()}")
        }
        return supported
    }

    private fun clockPaths(profile: UsbAudioStreamingProfile): List<ClockPath> {
        val root = profile.clockEntity ?: throw IllegalArgumentException("USB clock source is missing")
        require(profile.controlInterface in 0..255 && root in 1..255) { "USB clock source is unavailable" }
        val topology = profile.clockTopology ?: return listOf(ClockPath(ClockSource(root, null), emptyList()))
        require(topology.rootEntity == root) { "UAC2 clock topology root does not match the terminal" }
        val result = mutableListOf<ClockPath>()

        fun walk(
            entityId: Int,
            visited: Set<Int>,
            selectors: List<ClockSelection>,
            multipliers: List<UsbAudioClockEntity.Multiplier>,
        ) {
            require(entityId in 1..255) { "Invalid UAC2 clock entity reference" }
            require(entityId !in visited) { "Recursive UAC2 clock topology at entity $entityId" }
            val entity = topology.entities[entityId]
                ?: throw IllegalArgumentException("Missing UAC2 clock entity $entityId")
            val nextVisited = visited + entityId
            when (entity) {
                is UsbAudioClockEntity.Source -> result += ClockPath(
                    ClockSource(entity.id, entity.controls), selectors, multipliers,
                )
                is UsbAudioClockEntity.Selector -> entity.sourceIds.forEachIndexed { index, source ->
                    walk(source, nextVisited, selectors + ClockSelection(entity, index + 1), multipliers)
                }
                is UsbAudioClockEntity.Multiplier -> walk(
                    entity.sourceId, nextVisited, selectors, multipliers + entity,
                )
            }
            require(result.size <= 256) { "UAC2 clock topology exposes too many paths" }
        }

        walk(root, emptySet(), emptyList(), emptyList())
        require(result.isNotEmpty()) { "UAC2 clock topology has no source" }
        return result.distinct()
    }

    /** Select inner clocks first, then expose that path through outer selectors. */
    private fun selectPath(profile: UsbAudioStreamingProfile, path: ClockPath): () -> Unit {
        val changed = mutableListOf<Pair<UsbAudioClockEntity.Selector, Int>>()
        try {
            path.selectors.asReversed().forEach { selection ->
                val selector = selection.selector
                if (selector.sourceIds.size == 1) return@forEach
                require(controlReadable(selector.controls, 1)) {
                    "UAC2 clock selector ${selector.id} cannot be read"
                }
                val current = readSelector(profile, selector)
                if (current == selection.pin) return@forEach
                require(controlWritable(selector.controls, 1)) {
                    "UAC2 clock selector ${selector.id} is read-only on pin $current"
                }
                writeSelector(profile, selector, selection.pin)
                changed += selector to current
            }
        } catch (failure: Exception) {
            changed.asReversed().forEach { (selector, pin) -> runCatching { writeSelector(profile, selector, pin) } }
            throw failure
        }
        return {
            changed.asReversed().forEach { (selector, pin) -> writeSelector(profile, selector, pin) }
        }
    }

    private fun readSelector(profile: UsbAudioStreamingProfile, selector: UsbAudioClockEntity.Selector): Int {
        val value = ByteArray(1)
        readExact(0xa1, 1, 0x100, clockIndex(profile, selector.id), value)
        return (value[0].toInt() and 0xff).also { pin ->
            check(pin in 1..selector.sourceIds.size) {
                "UAC2 clock selector ${selector.id} reports invalid pin $pin"
            }
        }
    }

    private fun writeSelector(
        profile: UsbAudioStreamingProfile,
        selector: UsbAudioClockEntity.Selector,
        pin: Int,
    ) {
        require(pin in 1..selector.sourceIds.size) { "Invalid UAC2 clock selector pin" }
        val value = byteArrayOf(pin.toByte())
        check(pipe.transfer(0x21, 1, 0x100, clockIndex(profile, selector.id), value) == 1) {
            "USB DAC rejected clock selector ${selector.id} pin $pin"
        }
        check(readSelector(profile, selector) == pin) {
            "USB DAC did not retain clock selector ${selector.id} pin $pin"
        }
    }

    private fun setSourceRate(profile: UsbAudioStreamingProfile, source: ClockSource, sampleRate: Int) {
        val index = clockIndex(profile, source.id)
        val before = runCatching { readCurrentRate(index) }.getOrNull()
        if (before != sampleRate) {
            require(source.controls == null || controlWritable(source.controls, 1)) {
                "UAC2 clock source ${source.id} is read-only at ${before ?: "an unknown rate"}"
            }
            val value = ByteArray(4) { byte -> (sampleRate ushr (byte * 8)).toByte() }
            check(pipe.transfer(0x21, 1, 0x100, index, value) == value.size) {
                "USB DAC rejected $sampleRate Hz on clock source ${source.id}"
            }
        }
        val actual = readCurrentRate(index)
        check(actual == sampleRate) {
            "USB DAC clock source ${source.id} reports $actual Hz after requesting $sampleRate Hz"
        }
    }

    private fun readMultiplierRatio(profile: UsbAudioStreamingProfile, path: ClockPath): ClockRatio =
        path.multipliers.fold(ClockRatio.ONE) { ratio, multiplier ->
            require(controlReadable(multiplier.controls, 1) && controlReadable(multiplier.controls, 2)) {
                "UAC2 clock multiplier ${multiplier.id} does not expose its ratio"
            }
            val numerator = readUnsigned16(profile, multiplier.id, 0x100)
            val denominator = readUnsigned16(profile, multiplier.id, 0x200)
            require(numerator > 0 && denominator > 0) {
                "UAC2 clock multiplier ${multiplier.id} reports an invalid ratio"
            }
            ratio.multiply(numerator.toLong(), denominator.toLong())
        }

    private fun readUnsigned16(profile: UsbAudioStreamingProfile, entity: Int, value: Int): Int {
        val data = ByteArray(2)
        readExact(0xa1, 1, value, clockIndex(profile, entity), data)
        return data.u16(0)
    }

    private fun readCurrentRate(index: Int): Int {
        val value = ByteArray(4)
        readExact(0xa1, 1, 0x100, index, value)
        return value.littleEndian()
    }

    private fun awaitValidClock(profile: UsbAudioStreamingProfile, source: ClockSource) {
        val controls = source.controls ?: return
        if (!controlReadable(controls, 2)) return
        repeat(validityAttempts) { attempt ->
            val value = ByteArray(1)
            readExact(0xa1, 1, 0x200, clockIndex(profile, source.id), value)
            if (value[0].toInt() and 0xff != 0) return
            if (attempt + 1 < validityAttempts && validityDelayMillis > 0) sleeper(validityDelayMillis)
        }
        error("UAC2 clock source ${source.id} remained invalid after the rate change")
    }

    private fun clockIndex(profile: UsbAudioStreamingProfile, clock: Int): Int {
        require(profile.controlInterface in 0..255 && clock in 1..255) { "USB clock source is unavailable" }
        return (clock shl 8) or profile.controlInterface
    }

    private fun readExact(type: Int, request: Int, value: Int, index: Int, data: ByteArray) {
        val received = pipe.transfer(type, request, value, index, data)
        val target = "request=0x${request.toString(16)}, value=0x${value.toString(16)}, index=0x${index.toString(16)}"
        check(received >= 0) { "USB clock control transfer failed (libusb=$received; $target; expected=${data.size})" }
        check(received == data.size) { "USB clock returned a truncated response ($received/${data.size} bytes; $target)" }
    }

    private fun ByteArray.u16(offset: Int) = (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.littleEndian(): Int = indices.fold(0) { value, byte ->
        value or ((this[byte].toInt() and 0xff) shl (byte * 8))
    }

    private fun controlReadable(controls: Int, selector: Int): Boolean =
        controls ushr ((selector - 1) * 2) and 1 != 0

    private fun controlWritable(controls: Int, selector: Int): Boolean =
        controls ushr ((selector - 1) * 2) and 2 != 0

    private data class ClockSource(val id: Int, val controls: Int?)
    private data class ClockSelection(val selector: UsbAudioClockEntity.Selector, val pin: Int)
    private data class ClockPath(
        val source: ClockSource,
        val selectors: List<ClockSelection>,
        val multipliers: List<UsbAudioClockEntity.Multiplier> = emptyList(),
    )
    private data class ClockSupport(
        val path: ClockPath,
        val sourceRanges: List<UsbSampleRateRange>,
        val ranges: List<UsbSampleRateRange>,
        val ratio: ClockRatio,
        val selectorChanges: Int,
    )
    private data class ClockAttempt(val support: ClockSupport, val sourceRate: Int)

    private data class ClockRatio(val numerator: Long, val denominator: Long) {
        init {
            require(numerator in 1..MAX_RATIO && denominator in 1..MAX_RATIO) {
                "UAC2 clock multiplier ratio is outside the supported bound"
            }
        }

        fun multiply(otherNumerator: Long, otherDenominator: Long): ClockRatio {
            val leftCancellation = gcd(numerator, otherDenominator)
            val rightCancellation = gcd(otherNumerator, denominator)
            val nextNumerator = Math.multiplyExact(
                numerator / leftCancellation,
                otherNumerator / rightCancellation,
            )
            val nextDenominator = Math.multiplyExact(
                denominator / rightCancellation,
                otherDenominator / leftCancellation,
            )
            return ClockRatio(nextNumerator, nextDenominator)
        }

        fun sourceRate(terminalRate: Int): Int? {
            val scaled = terminalRate.toLong() * denominator
            if (scaled % numerator != 0L) return null
            return (scaled / numerator).takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        }

        /** Convert an arithmetic source-rate domain without claiming rates that the ratio cannot produce. */
        fun terminalRange(source: UsbSampleRateRange): UsbSampleRateRange? {
            val step = if (source.resolution == 0) 1L else source.resolution.toLong()
            val minimum = source.minimum.toLong()
            val maximumSteps = (source.maximum.toLong() - minimum) / step
            val divisor = denominator
            val divisorGcd = gcd(step, divisor)
            if (minimum % divisorGcd != 0L) return null
            val period = divisor / divisorGcd
            val firstStep = if (period == 1L) 0L else {
                val reducedStep = step / divisorGcd
                val target = Math.floorMod(-(minimum / divisorGcd), period)
                Math.floorMod(target * modularInverse(reducedStep, period), period)
            }
            if (firstStep > maximumSteps) return null
            val lastStep = firstStep + (maximumSteps - firstStep) / period * period
            val firstSource = minimum + firstStep * step
            val lastSource = minimum + lastStep * step
            val outputMinimum = Math.multiplyExact(firstSource, numerator) / denominator
            val outputMaximum = Math.multiplyExact(lastSource, numerator) / denominator
            val outputResolution = if (firstStep == lastStep) 0L else
                Math.multiplyExact(step, period) / denominator * numerator
            if (outputMinimum !in 1..Int.MAX_VALUE || outputMaximum !in outputMinimum..Int.MAX_VALUE ||
                outputResolution !in 0..Int.MAX_VALUE
            ) return null
            return UsbSampleRateRange(
                outputMinimum.toInt(), outputMaximum.toInt(), outputResolution.toInt(),
            )
        }

        companion object {
            val ONE = ClockRatio(1, 1)
            private const val MAX_RATIO = 1_000_000_000L

            private fun gcd(left: Long, right: Long): Long {
                var a = kotlin.math.abs(left)
                var b = kotlin.math.abs(right)
                while (b != 0L) {
                    val remainder = a % b
                    a = b
                    b = remainder
                }
                return a
            }

            private fun modularInverse(value: Long, modulus: Long): Long {
                var oldR = value
                var r = modulus
                var oldS = 1L
                var s = 0L
                while (r != 0L) {
                    val quotient = oldR / r
                    val nextR = oldR - quotient * r
                    oldR = r
                    r = nextR
                    val nextS = oldS - quotient * s
                    oldS = s
                    s = nextS
                }
                check(oldR == 1L) { "UAC2 clock ratio is not invertible" }
                return Math.floorMod(oldS, modulus)
            }
        }
    }
}
