package com.reverie.paint.core.brushstore

/**
 * Krita 曲线选项（KisCurveOption）读写规则（W-03）。
 *
 * 依据 KisKritaSensorPack.cpp:113-232 与 KisStandardOptionData：
 * - isChecked = !checkable || Pressure<Id>（缺省 false）
 * - `<Id>Sensor` 是单个 `<params id="pressure">…</params>` 或
 *   `<params id="sensorslist"><ChildSensor id=".."/>…</params>`
 * - `<Id>UseCurve` 缺省 true；`<Id>UseSameCurve` 缺省 true，且 `<Id>commonCurve` 存在时覆盖各传感器曲线
 * - `<Id>Value` 缺省为最大值
 *
 * 写规则（执行文档 W-03 细化）：
 * - 快照里该选项未勾选而用户改了值 → Pressure<Id>=true、<Id>Value、<Id>UseCurve=false（恒定值，不带出旧传感器）
 * - 快照里已勾选 → 只改 <Id>Value，传感器与 UseCurve 原样保留
 * - 抖动类（h/s/v）启用时把 fuzzy 合并进传感器列表，而不是覆盖原传感器
 */
object KppSensors {
    /** NotCheckable 的选项（Opacity / Flow）；其余标准选项都可勾选 */
    val NOT_CHECKABLE = setOf("Opacity", "Flow")

    const val LINEAR = "0,0;1,1;"

    fun isCheckable(id: String): Boolean = id !in NOT_CHECKABLE

    fun pressureKey(id: String): String = "Pressure$id"

    fun isChecked(xml: String, id: String): Boolean =
        !isCheckable(id) || KppXml.getBool(xml, pressureKey(id), false)

    fun useCurve(xml: String, id: String): Boolean = KppXml.getBool(xml, "${id}UseCurve", true)

    fun sensorXml(xml: String, id: String): String? = KppXml.get(xml, "${id}Sensor")?.takeIf { it.isNotBlank() }

    private val topParams = Regex("""<params\b[^>]*\bid="([^"]*)"[^>]*>""")
    private val childSensor = Regex("""<ChildSensor\b[^>]*?\bid="([^"]*)"[^>]*?(?:/>|>.*?</ChildSensor>)""", RegexOption.DOT_MATCHES_ALL)
    private val curveTag = Regex("""<curve>(.*?)</curve>""", RegexOption.DOT_MATCHES_ALL)

    fun topId(sensor: String): String? = topParams.find(sensor)?.groupValues?.get(1)

    /** 传感器 id 列表；sensorslist 展开为子传感器 */
    fun sensorIds(sensor: String?): List<String> {
        if (sensor.isNullOrBlank()) return emptyList()
        val top = topId(sensor) ?: return emptyList()
        return if (top == "sensorslist") childSensor.findAll(sensor).map { it.groupValues[1] }.toList() else listOf(top)
    }

    fun firstCurve(sensor: String?): String? = sensor?.let { curveTag.find(it)?.groupValues?.get(1)?.trim() }

    fun single(id: String, curve: String = LINEAR): String =
        """<!DOCTYPE params><params id="$id"><curve>$curve</curve></params>"""

    /** 把 [id] 合并进已有传感器（已有则原样返回）；单传感器会升级为 sensorslist */
    fun addSensor(sensor: String?, id: String, curve: String = LINEAR): String {
        if (sensor.isNullOrBlank() || topId(sensor) == null) return single(id, curve)
        val ids = sensorIds(sensor)
        if (id in ids) return sensor
        val top = topId(sensor)!!
        val newChild = """<ChildSensor id="$id"><curve>$curve</curve></ChildSensor>"""
        if (top == "sensorslist") {
            val end = sensor.lastIndexOf("</params>")
            return if (end >= 0) sensor.substring(0, end) + newChild + sensor.substring(end) else sensor
        }
        // 单传感器 → 列表：保留原传感器的属性与内部（曲线等）
        val m = topParams.find(sensor)!!
        val attrs = m.value.removePrefix("<params").removeSuffix(">").replace(Regex("""\s*\bid="[^"]*""""), "")
        val innerStart = m.range.last + 1
        val innerEnd = sensor.lastIndexOf("</params>").takeIf { it >= innerStart } ?: sensor.length
        val inner = sensor.substring(innerStart, innerEnd)
        val oldChild = """<ChildSensor id="$top"$attrs>$inner</ChildSensor>"""
        return """<!DOCTYPE params><params id="sensorslist">$oldChild$newChild</params>"""
    }

    /** 从传感器中去掉 [id]；没有剩余传感器时返回 null */
    fun removeSensor(sensor: String?, id: String): String? {
        if (sensor.isNullOrBlank()) return null
        val top = topId(sensor) ?: return null
        if (top != "sensorslist") return if (top == id) null else sensor
        val out = childSensor.replace(sensor) { if (it.groupValues[1] == id) "" else it.value }
        return if (sensorIds(out).isEmpty()) null else out
    }

    /**
     * 只改 [sensor] 里 id 为 [id] 的那一个传感器的曲线：单传感器改 `<curve>`，
     * sensorslist 只改对应 ChildSensor（自闭合的补上 `<curve>`）。[id] 不存在时返回 null。
     */
    fun setSensorCurve(sensor: String, id: String, curve: String): String? {
        val top = topId(sensor) ?: return null
        if (top != "sensorslist") {
            if (top != id) return null
            return if (curveTag.containsMatchIn(sensor)) {
                curveTag.replaceFirst(sensor, Regex.escapeReplacement("<curve>$curve</curve>"))
            } else {
                val end = sensor.lastIndexOf("</params>")
                if (end < 0) null else sensor.substring(0, end) + "<curve>$curve</curve>" + sensor.substring(end)
            }
        }
        val m = childSensor.findAll(sensor).firstOrNull { it.groupValues[1] == id } ?: return null
        val child = m.value
        val newChild = if (child.trimEnd().endsWith("/>") && !child.contains("</ChildSensor>")) {
            child.trimEnd().removeSuffix("/>").trimEnd() + "><curve>$curve</curve></ChildSensor>"
        } else if (curveTag.containsMatchIn(child)) {
            curveTag.replaceFirst(child, Regex.escapeReplacement("<curve>$curve</curve>"))
        } else {
            child.replace("</ChildSensor>", "<curve>$curve</curve></ChildSensor>")
        }
        return sensor.replaceRange(m.range, newChild)
    }

    /** [sensor] 里 id 为 [id] 的传感器的曲线；自闭合 ChildSensor 视为线性 */
    fun sensorCurve(sensor: String?, id: String): String? {
        if (sensor == null) return null
        val top = topId(sensor) ?: return null
        if (top != "sensorslist") return if (top == id) (firstCurve(sensor) ?: LINEAR) else null
        val m = childSensor.findAll(sensor).firstOrNull { it.groupValues[1] == id } ?: return null
        return curveTag.find(m.value)?.groupValues?.get(1)?.trim() ?: LINEAR
    }

    /** UseSameCurve 且有 commonCurve 时，Krita 实际使用 commonCurve；读回时把各传感器曲线替换成它 */
    fun effectiveSensorXml(xml: String, id: String): String? {
        val s = sensorXml(xml, id) ?: return null
        val common = commonCurve(xml, id) ?: return s
        return curveTag.replace(s) { "<curve>$common</curve>" }
    }

    fun commonCurve(xml: String, id: String): String? {
        if (!KppXml.getBool(xml, "${id}UseSameCurve", true)) return null
        return KppXml.get(xml, "${id}commonCurve")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 只写传感器结构（增删子传感器等）。**不**改 commonCurve：UseSameCurve 时 Krita 对所有传感器用
     * commonCurve，结构变化不应改变它；曲线编辑由 PresetPropertyMap.writeCurve 显式同步。
     */
    fun writeSensor(xml: String, id: String, sensor: String): String = KppXml.set(xml, "${id}Sensor", sensor)

    /** 曲线选项强度读：勾选取 Value（缺省 [max]），未勾选取 [unchecked] */
    fun readStrength(xml: String, id: String, unchecked: Double, max: Double = 1.0): Double =
        if (isChecked(xml, id)) KppXml.getDouble(xml, "${id}Value") ?: max else unchecked

    /**
     * 曲线选项强度写。[enable]=false 时取消勾选（Value 仍写，便于导出可读）；
     * true 时见类注释的两条规则。
     */
    fun writeStrength(xml: String, id: String, value: Double, enable: Boolean): String {
        var x = xml
        if (!isCheckable(id)) return KppXml.set(x, "${id}Value", KppXml.fmt(value))
        if (!enable) {
            x = KppXml.set(x, pressureKey(id), "false")
            return KppXml.set(x, "${id}Value", KppXml.fmt(value))
        }
        val wasChecked = isChecked(x, id)
        x = KppXml.set(x, pressureKey(id), "true")
        x = KppXml.set(x, "${id}Value", KppXml.fmt(value))
        if (!wasChecked) x = KppXml.set(x, "${id}UseCurve", "false")
        return x
    }

    /** 抖动类：启用时勾选 + UseCurve + 合并 fuzzy 传感器 */
    fun writeJitter(xml: String, id: String, value: Double, enable: Boolean): String {
        var x = KppXml.set(xml, "${id}Value", KppXml.fmt(value))
        x = KppXml.set(x, pressureKey(id), enable.toString())
        if (enable) {
            val s = sensorXml(x, id)
            if (!isChecked(xml, id) || "fuzzy" !in sensorIds(s)) {
                x = KppXml.set(x, "${id}UseCurve", "true")
                x = writeSensor(x, id, if (isChecked(xml, id)) addSensor(s, "fuzzy") else single("fuzzy"))
            }
        }
        return x
    }
}
