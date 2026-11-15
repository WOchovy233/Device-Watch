package org.jarsi.devicewatch.data

/**
 * What Android's own battery statistics say about the time since they were last
 * reset, which is when the phone left the charger after a substantial charge.
 */
data class BatteryUsage(
    val capacityMah: Double?,
    val onBatteryMillis: Long,
    val screenOffMillis: Long,
    val dischargeMah: Double?,
    val screenOnDischargeMah: Double?,
    val screenOffDischargeMah: Double?,
    val apps: List<UidPower>,
    val kernelWakeLocks: List<WakeHold>,
    val partialWakeLocks: List<WakeHold>,
    val wakeupReasons: List<WakeHold>,
) {
    val screenOnMillis: Long get() = (onBatteryMillis - screenOffMillis).coerceAtLeast(0L)
    val dischargeMeasured: Boolean get() = (dischargeMah ?: 0.0) > 0.0
    val screenOnMilliamps: Double? get() = milliamps(screenOnDischargeMah, screenOnMillis)
    val screenOffMilliamps: Double? get() = milliamps(screenOffDischargeMah, screenOffMillis)
    val screenOnPercentPerHour: Double? get() = percentPerHour(screenOnMilliamps)
    val screenOffPercentPerHour: Double? get() = percentPerHour(screenOffMilliamps)

    val dischargePercent: Double?
        get() {
            val discharge = dischargeMah?.takeIf { dischargeMeasured } ?: return null
            return capacityMah?.takeIf { it > 0.0 }?.let { discharge / it * 100.0 }
        }

    val idleDrain: IdleDrain?
        get() = screenOffPercentPerHour?.let { rate ->
            when {
                rate < IDLE_LOW_BELOW -> IdleDrain.LOW
                rate <= IDLE_HIGH_ABOVE -> IdleDrain.NORMAL
                else -> IdleDrain.HIGH
            }
        }

    private fun milliamps(mah: Double?, millis: Long): Double? {
        if (mah == null || !dischargeMeasured || millis < MIN_RATE_MILLIS) return null
        return mah / (millis / 3_600_000.0)
    }

    private fun percentPerHour(milliamps: Double?): Double? {
        val capacity = capacityMah ?: return null
        if (milliamps == null || capacity <= 0.0) return null
        return milliamps / capacity * 100.0
    }

    companion object {
        const val MIN_RATE_MILLIS = 10 * 60_000L
        const val IDLE_LOW_BELOW = 1.0
        const val IDLE_HIGH_ABOVE = 2.5
        fun fullBatteryHours(percentPerHour: Double?): Double? =
            percentPerHour?.takeIf { it >= 0.1 }?.let { 100.0 / it }
    }
}

enum class IdleDrain { LOW, NORMAL, HIGH }

data class UidPower(val uid: Int, val mah: Double)

data class WakeHold(val name: String, val millis: Long, val count: Int, val uid: Int? = null)

/** 🎯 新增了 uid 字段，用来在 UI 里查包名 */
data class NamedPower(val label: String, val mah: Double, val share: Double, val uid: Int = -1)

enum class WakeCategory {
    SLEEP_INTERRUPTED, APPS, SCREEN, SENSORS, WIFI, BLUETOOTH, MODEM, ALARM, CHARGING, NFC, INPUT, AUDIO, LOCATION
}

/** 🎯 新增了 uid 字段，用来在 UI 里查包名 */
data class WakeEntry(
    val owner: String?,
    val category: WakeCategory?,
    val names: List<String>,
    val millis: Long,
    val count: Int,
    val uid: Int? = null
)

/** [BatteryUsage] with its uids named and its lists cut and grouped to what a page shows. */
data class BatteryUsageReport(
    val usage: BatteryUsage,
    val apps: List<NamedPower>,
    val appWakeLocks: List<WakeEntry>,
    val kernelWakeLocks: List<WakeEntry>,
    val wakeupReasons: List<WakeEntry>,
) {
    companion object {
        const val APP_LIMIT = 10
        const val WAKE_LIMIT = 5

        fun from(usage: BatteryUsage, label: (Int) -> String): BatteryUsageReport {
            val total = usage.apps.sumOf { it.mah }
            
            // 🎯 核心修改：按名字聚合，但是把代表性的 uid 保留下来
            val apps = usage.apps
                .groupBy { label(it.uid) }
                .map { (name, parts) ->
                    val firstUid = parts.first().uid
                    Triple(name, parts.sumOf { it.mah }, firstUid)
                }
                .filter { (_, mah, _) -> mah > 0.0 }
                .sortedByDescending { (_, mah, _) -> mah }
                .take(APP_LIMIT)
                .map { (name, mah, uid) -> NamedPower(name, mah, if (total > 0.0) mah / total else 0.0, uid) }

            return BatteryUsageReport(
                usage = usage,
                apps = apps,
                appWakeLocks = usage.partialWakeLocks
                    .groupBy { hold -> hold.uid?.let(label) }
                    .flatMap { (owner, holds) ->
                        if (owner == null) holds.map { entry(null, null, listOf(it)) }
                        else listOf(entry(owner, null, holds))
                    }
                    .longest(),
                kernelWakeLocks = usage.kernelWakeLocks.byCategory(),
                wakeupReasons = usage.wakeupReasons.byCategory(),
            )
        }

        private fun List<WakeHold>.byCategory(): List<WakeEntry> =
            groupBy { WakeGlossary.categoryOf(it.name) ?: it.name }
                .map { (key, holds) -> entry(null, key as? WakeCategory, holds) }
                .longest()

        private fun entry(owner: String?, category: WakeCategory?, holds: List<WakeHold>) = WakeEntry(
            owner = owner,
            category = category,
            names = holds.sortedByDescending { it.millis }.map { it.name },
            millis = holds.sumOf { it.millis },
            count = holds.sumOf { it.count },
            uid = holds.firstOrNull()?.uid // 🎯 把 uid 传进去
        )

        private fun List<WakeEntry>.longest(): List<WakeEntry> =
            filter { it.millis >= 1_000L }.sortedByDescending { it.millis }.take(WAKE_LIMIT)
    }
}

internal object WakeGlossary {
    private val rules: List<Pair<WakeCategory, List<String>>> = listOf(
        WakeCategory.APPS to listOf("powermanagerservice.wakelocks"),
        WakeCategory.SCREEN to listOf("powermanagerservice.display", "powermanager.suspendlockout", "drmdp", "dsim", "decon"),
        WakeCategory.AUDIO to listOf("audio", "sound"),
        WakeCategory.SENSORS to listOf("sensor", "slpi", "sscrpcd", "aoc", "usf", "chre"),
        WakeCategory.WIFI to listOf("wlan", "wifi", "qcom_rx_wakelock", "dhd", "cnss", "pmo_wow"),
        WakeCategory.BLUETOOTH to listOf("bluetooth", "hci_", "btbcm", "bthal"),
        WakeCategory.MODEM to listOf("modem", "mdm", "rmnet", "ipa_", "radio-interface", "qmi", "mss", "s5100", "cp2ap", "umts", "oem_ipc"),
        WakeCategory.ALARM to listOf("alarm", "rtc"),
        WakeCategory.NFC to listOf("nfc", "ese_spi"),
        WakeCategory.CHARGING to listOf("battery", "charger", "usb", "wpc", "otg", "pogo", "fg_irq"),
        WakeCategory.INPUT to listOf("tsp", "touch", "gpio_keys", "pwrkey", "hall_ic", "gti"),
        WakeCategory.LOCATION to listOf("gnss", "gps"),
    )

    fun categoryOf(name: String): WakeCategory? {
        if (name.startsWith("Abort:")) return WakeCategory.SLEEP_INTERRUPTED
        val lower = name.lowercase()
        return rules.firstOrNull { (_, needles) -> needles.any(lower::contains) }?.first
    }

    fun appWorkOf(tag: String): AppWork? = when {
        tag.startsWith("*job*") -> AppWork.JOB
        tag.startsWith("*alarm*") || tag.startsWith("*walarm*") -> AppWork.ALARM
        tag.startsWith("*sync*") -> AppWork.SYNC
        tag.startsWith("Audio") -> AppWork.AUDIO
        else -> null
    }
}

enum class AppWork { JOB, ALARM, SYNC, AUDIO }

internal object SystemUids {
    private val names = mapOf(
        1001 to "radio", 1002 to "bluetooth", 1010 to "wifi", 1013 to "media",
        1021 to "gps", 1027 to "nfc", 1041 to "audioserver", 1046 to "mediacodec",
        1047 to "cameraserver", 1073 to "network_stack", 1082 to "artd", 2000 to "shell",
    )
    fun name(uid: Int): String? = names[uid]
}

interface BatteryUsageSource {
    fun sinceCharge(): BatteryUsageReport?
}

internal object BatteryStatsDumpParser {
    private const val DURATION = """((?:\d+(?:ms|[dhms])\s*)+)"""
    private val durationPart = Regex("""(\d+)(ms|[dhms])""")
    private val durationOnly = Regex("""^\s*$DURATION$""")
    private val onBattery = Regex("""^\s*Time on battery: $DURATION\(""")
    private val screenOff = Regex("""^\s*Time on battery screen off: $DURATION\(""")
    private val capacity = Regex("""^\s*Estimated battery capacity: ([\d.,]+) mAh""")
    private val discharge = Regex("""^\s*Discharge: ([\d.,]+) mAh""")
    private val screenOffDischarge = Regex("""^\s*Screen off discharge: ([\d.,]+) mAh""")
    private val screenOnDischarge = Regex("""^\s*Screen on discharge: ([\d.,]+) mAh""")
    private val uidPower = Regex("""^\s*U[iI][dD] (\S+): ([\d.,]+)""")
    private val kernelWakeLock = Regex("""^\s*Kernel Wake lock (.+): $DURATION\((\d+) times\)""")
    private val partialWakeLock = Regex("""^\s*Wake lock (\S+) (.+?): $DURATION\((\d+) times\)""")
    private val wakeupReason = Regex("""^\s*Wakeup reason (.+): $DURATION\((\d+) times\)""")
    private val uidLabel = Regex("""^u(\d+)a(\d+)$""")

    private enum class Section { GENERAL, POWER, KERNEL_WAKE_LOCKS, PARTIAL_WAKE_LOCKS, WAKEUP_REASONS, OTHER }

    fun parse(dump: String): BatteryUsage? {
        var capacityMah: Double? = null
        var onBatteryMillis: Long? = null
        var screenOffMillis = 0L
        var dischargeMah: Double? = null
        var screenOnMah: Double? = null
        var screenOffMah: Double? = null
        val apps = ArrayList<UidPower>()
        val kernel = ArrayList<WakeHold>()
        val partial = ArrayList<WakeHold>()
        val reasons = ArrayList<WakeHold>()

        var section = Section.GENERAL
        for (line in dump.lineSequence()) {
            val heading = line.trim()
            when {
                heading.startsWith("Estimated power use") -> { section = Section.POWER; continue }
                heading == "All kernel wake locks:" -> { section = Section.KERNEL_WAKE_LOCKS; continue }
                heading == "All partial wake locks:" -> { section = Section.PARTIAL_WAKE_LOCKS; continue }
                heading == "All wakeup reasons:" -> { section = Section.WAKEUP_REASONS; continue }
                heading.startsWith("All ") && heading.endsWith(":") -> { section = Section.OTHER; continue }
                heading.isEmpty() && section != Section.GENERAL -> { section = Section.OTHER; continue }
            }
            when (section) {
                Section.GENERAL -> {
                    onBattery.find(line)?.let { onBatteryMillis = durationMillis(it.groupValues[1]) }
                    screenOff.find(line)?.let { screenOffMillis = durationMillis(it.groupValues[1]) ?: 0L }
                    capacity.find(line)?.let { capacityMah = number(it.groupValues[1]) }
                    discharge.find(line)?.let { dischargeMah = number(it.groupValues[1]) }
                    screenOffDischarge.find(line)?.let { screenOffMah = number(it.groupValues[1]) }
                    screenOnDischarge.find(line)?.let { screenOnMah = number(it.groupValues[1]) }
                }
                Section.POWER -> uidPower.find(line)?.let { match ->
                    val uid = uid(match.groupValues[1])
                    val mah = number(match.groupValues[2])
                    if (uid != null && mah != null) apps += UidPower(uid, mah)
                }
                Section.KERNEL_WAKE_LOCKS -> kernelWakeLock.find(line)?.let { match ->
                    hold(match.groupValues[1], match.groupValues[2], match.groupValues[3])?.let(kernel::add)
                }
                Section.PARTIAL_WAKE_LOCKS -> partialWakeLock.find(line)?.let { match ->
                    hold(match.groupValues[2], match.groupValues[3], match.groupValues[4], uid(match.groupValues[1]))
                        ?.let(partial::add)
                }
                Section.WAKEUP_REASONS -> wakeupReason.find(line)?.let { match ->
                    hold(match.groupValues[1], match.groupValues[2], match.groupValues[3])?.let(reasons::add)
                }
                Section.OTHER -> Unit
            }
        }

        return BatteryUsage(
            capacityMah = capacityMah,
            onBatteryMillis = onBatteryMillis ?: return null,
            screenOffMillis = screenOffMillis,
            dischargeMah = dischargeMah,
            screenOnDischargeMah = screenOnMah,
            screenOffDischargeMah = screenOffMah,
            apps = apps,
            kernelWakeLocks = kernel,
            partialWakeLocks = partial,
            wakeupReasons = reasons,
        )
    }

    private fun hold(name: String, duration: String, count: String, uid: Int? = null): WakeHold? {
        val millis = durationMillis(duration) ?: return null
        return WakeHold(name, millis, count.toIntOrNull() ?: 0, uid)
    }

    fun durationMillis(text: String): Long? {
        if (!durationOnly.matches(text)) return null
        return durationPart.findAll(text).sumOf { part ->
            val amount = part.groupValues[1].toLong()
            when (part.groupValues[2]) {
                "d" -> amount * 86_400_000L
                "h" -> amount * 3_600_000L
                "m" -> amount * 60_000L
                "s" -> amount * 1_000L
                else -> amount
            }
        }
    }

    fun uid(label: String): Int? {
        label.toIntOrNull()?.let { return it }
        val match = uidLabel.find(label) ?: return null
        val user = match.groupValues[1].toIntOrNull() ?: return null
        val app = match.groupValues[2].toIntOrNull() ?: return null
        return user * PER_USER_RANGE + FIRST_APPLICATION_UID + app
    }

    private fun number(text: String): Double? =
        (if ('.' in text) text.replace(",", "") else text.replace(',', '.')).toDoubleOrNull()

    private const val PER_USER_RANGE = 100_000
    private const val FIRST_APPLICATION_UID = 10_000
}
