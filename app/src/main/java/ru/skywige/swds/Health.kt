package ru.skywige.swds

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Health Connect → модуль «Браслет» SWDS.
 *
 * Xiaomi Smart Band 10 Pro → Mi Fitness → Health Connect → (это приложение) → POST /modules/bracelet/api/hc/ingest.
 * Читается всё, что разрешил пользователь: шаги, дистанция, калории, этажи, пульс (поминутно), пульс покоя, HRV,
 * SpO₂, дыхание, сон с фазами, тренировки (с пульсом, калориями и маршрутом GPS), вес, жир, рост, VO₂max,
 * давление, температура, глюкоза, вода.
 *
 * Каждый раз перечитываются последние ~36 часов (браслет досинхронизирует данные с опозданием) и всё, что новее
 * прошлой синхронизации. Первая синхронизация загружает историю за N дней (настройка в SWDS), пачками по неделе.
 * Сводка «сегодня» сохраняется на телефоне — её показывают виджет «Здоровье» и офлайн-экран без связи с сервером.
 */
object Health {
    const val PROVIDER = "com.google.android.apps.healthdata"
    const val PERM_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    const val PERM_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
    const val PERM_ROUTES = "android.permission.health.READ_EXERCISE_ROUTES"
    private const val INGEST = "/modules/bracelet/api/hc/ingest"

    /** Что читаем и как называем для человека. */
    val TYPES: List<Pair<KClass<out Record>, String>> = listOf(
        StepsRecord::class to "Шаги", DistanceRecord::class to "Расстояние",
        ActiveCaloriesBurnedRecord::class to "Активные калории", TotalCaloriesBurnedRecord::class to "Все калории",
        FloorsClimbedRecord::class to "Этажи", HeartRateRecord::class to "Пульс",
        RestingHeartRateRecord::class to "Пульс покоя", HeartRateVariabilityRmssdRecord::class to "Вариабельность пульса (HRV)",
        OxygenSaturationRecord::class to "Кислород в крови (SpO₂)", RespiratoryRateRecord::class to "Частота дыхания",
        SleepSessionRecord::class to "Сон", ExerciseSessionRecord::class to "Тренировки",
        WeightRecord::class to "Вес", BodyFatRecord::class to "Процент жира", HeightRecord::class to "Рост",
        Vo2MaxRecord::class to "VO₂max", BloodPressureRecord::class to "Давление",
        BodyTemperatureRecord::class to "Температура тела", BloodGlucoseRecord::class to "Глюкоза",
        HydrationRecord::class to "Вода"
    )

    val BASE_PERMISSIONS: Set<String> = TYPES.map { HealthPermission.getReadPermission(it.first) }.toSet()
    val EXTRA_PERMISSIONS: Set<String> = setOf(PERM_BACKGROUND, PERM_HISTORY, PERM_ROUTES)

    @Volatile private var running = false

    // ───────────── доступность и разрешения ─────────────
    fun sdkStatus(ctx: Context): Int = try {
        HealthConnectClient.getSdkStatus(ctx, PROVIDER)
    } catch (e: Exception) { HealthConnectClient.SDK_UNAVAILABLE }

    fun statusText(ctx: Context): String = when (sdkStatus(ctx)) {
        HealthConnectClient.SDK_AVAILABLE -> "available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update_required"
        else -> "unavailable"
    }

    fun client(ctx: Context): HealthConnectClient? =
        if (sdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE) runCatching { HealthConnectClient.getOrCreate(ctx) }.getOrNull() else null

    fun granted(ctx: Context): Set<String> {
        val c = client(ctx) ?: return emptySet()
        return try { runBlocking { c.permissionController.getGrantedPermissions() } } catch (e: Exception) { emptySet() }
    }

    fun permissionIntent(ctx: Context, perms: Set<String>): Intent =
        PermissionController.createRequestPermissionResultContract(PROVIDER).createIntent(ctx, perms)

    fun parsePermissionResult(resultCode: Int, data: Intent?): Set<String> = try {
        PermissionController.createRequestPermissionResultContract(PROVIDER).parseResult(resultCode, data)
    } catch (e: Exception) { emptySet() }

    fun settingsIntent(): Intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding")).setPackage("com.android.vending")

    /** Состояние для страницы настроек. */
    fun state(ctx: Context): JSONObject {
        val p = Prefs(ctx)
        val g = if (statusText(ctx) == "available") granted(ctx) else emptySet()
        val types = JSONArray()
        for ((k, label) in TYPES) types.put(JSONObject().put("label", label).put("granted", HealthPermission.getReadPermission(k) in g))
        return JSONObject().put("status", statusText(ctx)).put("enabled", p.healthEnabled)
            .put("granted_count", g.count { it in BASE_PERMISSIONS }).put("total", BASE_PERMISSIONS.size)
            .put("background", PERM_BACKGROUND in g).put("history", PERM_HISTORY in g).put("routes", PERM_ROUTES in g)
            .put("types", types).put("last_sync", p.healthLastSync).put("last_result", p.healthLastResult)
            .put("today", runCatching { JSONObject(p.healthToday) }.getOrDefault(JSONObject()))
            .put("running", running)
    }

    // ───────────── синхронизация ─────────────
    /**
     * Прочитать новое и отправить на SWDS. Блокирующий вызов (из рабочего потока).
     * manual — запущено руками: ошибки показываем, ограничение «не чаще раза в 20 минут» не действует.
     */
    fun sync(ctx: Context, manual: Boolean = false): JSONObject {
        val p = Prefs(ctx)
        if (!p.healthEnabled && !manual) return JSONObject().put("ok", true).put("skipped", "выключено")
        if (running) return JSONObject().put("ok", true).put("skipped", "уже идёт")
        if (!manual && System.currentTimeMillis() - p.healthLastSync < 20 * 60_000L)
            return JSONObject().put("ok", true).put("skipped", "недавно")
        val c = client(ctx) ?: return JSONObject().put("ok", false).put("error", "Health Connect недоступен на этом телефоне")
        running = true
        try {
            return runBlocking { syncNow(ctx, c) }
        } catch (e: SecurityException) {
            val msg = if (!MainActivity.visible) "Чтение в фоне не разрешено — включите «Читать в фоне» в настройках «Здоровье»"
                      else "Нет разрешения Health Connect: ${e.message}"
            p.healthLastResult = "⚠ $msg"
            return JSONObject().put("ok", false).put("error", msg)
        } catch (e: ApiException) {
            p.healthLastResult = "⚠ ${e.message}"
            throw e
        } catch (e: Exception) {
            p.healthLastResult = "⚠ ${e.javaClass.simpleName}: ${e.message}"
            return JSONObject().put("ok", false).put("error", e.message ?: "ошибка")
        } finally {
            running = false
        }
    }

    private suspend fun syncNow(ctx: Context, c: HealthConnectClient): JSONObject {
        val p = Prefs(ctx)
        val g = c.permissionController.getGrantedPermissions()
        val can = { k: KClass<out Record> -> HealthPermission.getReadPermission(k) in g }
        if (g.none { it in BASE_PERMISSIONS }) return JSONObject().put("ok", false).put("error", "Не выдано ни одного разрешения Health Connect")
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val cfg = runCatching { Api.get(ctx, "/modules/bracelet/api/hc/config?device=" + Api.enc(p.deviceName)) }.getOrNull()
        val historyDays = (cfg?.optInt("history_days", 30) ?: 30).coerceIn(1, 365)
        cfg?.optJSONObject("goals")?.optInt("steps", 0)?.takeIf { it > 0 }?.let { p.stepsGoal = it }
        val last = p.healthLastSync
        var from = if (last <= 0) now.minus(Duration.ofDays(historyDays.toLong()))
                   else Instant.ofEpochMilli(last).minus(Duration.ofHours(36))
        // начало суток — дневные итоги не обрезаются посередине
        from = from.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val totals = HashMap<String, Int>()
        val origins = HashSet<String>()
        var todaySummary: JSONObject? = null
        while (from < now) {
            val to = minOf(now, from.atZone(zone).toLocalDate().plusDays(7).atStartOfDay(zone).toInstant())
            val payload = collect(c, can, from, to, zone, origins)
            if (to == now) {
                // сводку «сегодня» сохраняем сразу — виджет покажет её, даже если сервер сейчас недоступен
                todaySummary = summarize(payload, zone).put("steps_goal", p.stepsGoal)
                runCatching { JSONObject(p.healthToday).optString("insight") }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?.let { todaySummary!!.put("insight", it) }
                p.healthToday = todaySummary.toString()
                HealthWidget.renderAll(ctx)
            }
            payload.put("device", p.deviceName).put("app_version", BuildConfig.VERSION_NAME)
                .put("granted", JSONArray(g.filter { it in BASE_PERMISSIONS }.map { it.substringAfterLast('.') }))
                .put("origins", JSONArray(origins.toList()))
                .put("range", JSONObject().put("from", from.epochSecond).put("to", to.epochSecond))
                .put("sleep_complete", can(SleepSessionRecord::class))
            val r = Api.post(ctx, INGEST, payload, 15000, 120_000)
            r.optJSONObject("counts")?.let { o -> o.keys().forEach { k -> totals[k] = (totals[k] ?: 0) + o.optInt(k) } }
            from = to
        }
        p.healthLastSync = now.toEpochMilli()
        val txt = listOfNotNull(totals["daily"]?.let { "дней $it" }, totals["hr"]?.takeIf { it > 0 }?.let { "пульс $it" },
            totals["sleep"]?.takeIf { it > 0 }?.let { "ночей $it" }, totals["workouts"]?.takeIf { it > 0 }?.let { "тренировок $it" })
            .joinToString(", ")
        p.healthLastResult = "✓ " + txt.ifEmpty { "новых данных нет" }
        return JSONObject().put("ok", true).put("counts", JSONObject(totals as Map<*, *>)).put("message", p.healthLastResult)
    }

    /** Всё за отрезок [a; b) — в JSON для сервера. */
    private suspend fun collect(c: HealthConnectClient, can: (KClass<out Record>) -> Boolean, a: Instant, b: Instant,
                                zone: ZoneId, origins: MutableSet<String>): JSONObject {
        val out = JSONObject()
        val range = TimeRangeFilter.between(a, b)

        // ── итоги по дням ──
        val metrics = HashSet<AggregateMetric<*>>()
        if (can(StepsRecord::class)) metrics.add(StepsRecord.COUNT_TOTAL)
        if (can(DistanceRecord::class)) metrics.add(DistanceRecord.DISTANCE_TOTAL)
        if (can(ActiveCaloriesBurnedRecord::class)) metrics.add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
        if (can(TotalCaloriesBurnedRecord::class)) metrics.add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        if (can(FloorsClimbedRecord::class)) metrics.add(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
        if (can(HeartRateRecord::class)) { metrics.add(HeartRateRecord.BPM_AVG); metrics.add(HeartRateRecord.BPM_MIN); metrics.add(HeartRateRecord.BPM_MAX) }
        if (can(RestingHeartRateRecord::class)) metrics.add(RestingHeartRateRecord.BPM_MIN)
        if (can(ExerciseSessionRecord::class)) metrics.add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
        if (can(SleepSessionRecord::class)) metrics.add(SleepSessionRecord.SLEEP_DURATION_TOTAL)
        val daily = JSONArray()
        if (metrics.isNotEmpty()) {
            val d0 = a.atZone(zone).toLocalDateTime()
            val d1 = b.atZone(zone).toLocalDate().let { if (b.atZone(zone).toLocalTime().toSecondOfDay() == 0) it else it.plusDays(1) }
                .atStartOfDay()
            val groups = c.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(metrics = metrics,
                timeRangeFilter = TimeRangeFilter.between(d0, d1), timeRangeSlicer = Period.ofDays(1)))
            for (gr in groups) {
                val r = gr.result
                val o = JSONObject().put("date", gr.startTime.toLocalDate().toString())
                r[StepsRecord.COUNT_TOTAL]?.let { o.put("steps", it) }
                r[DistanceRecord.DISTANCE_TOTAL]?.let { o.put("distance_m", it.inMeters.toLong()) }
                r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let { o.put("active_kcal", it.inKilocalories.toLong()) }
                r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.let { o.put("total_kcal", it.inKilocalories.toLong()) }
                r[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.let { o.put("floors", it) }
                r[HeartRateRecord.BPM_AVG]?.let { o.put("hr_avg", it) }
                r[HeartRateRecord.BPM_MIN]?.let { o.put("hr_min", it) }
                r[HeartRateRecord.BPM_MAX]?.let { o.put("hr_max", it) }
                r[RestingHeartRateRecord.BPM_MIN]?.let { o.put("hr_rest", it) }
                r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.let { o.put("exercise_min", it.toMinutes()) }
                r[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.let { o.put("sleep_min", it.toMinutes()) }
                if (o.length() > 1) daily.put(o)
            }
        }
        out.put("daily", daily)

        // ── шаги и калории по часам (для графика «день»; только последние трое суток) ──
        val hourFrom = maxOf(a, Instant.now().minus(Duration.ofDays(3)))
        if (hourFrom < b && can(StepsRecord::class)) {
            val hrs = c.aggregateGroupByDuration(AggregateGroupByDurationRequest(metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(hourFrom, b), timeRangeSlicer = Duration.ofHours(1)))
            val arr = JSONArray()
            for (h in hrs) h.result[StepsRecord.COUNT_TOTAL]?.takeIf { it > 0 }?.let { arr.put(JSONArray().put(h.startTime.epochSecond).put(it)) }
            out.put("steps_h", arr)
        }

        // ── пульс поминутно ──
        if (can(HeartRateRecord::class)) {
            val perMin = java.util.TreeMap<Long, Long>()
            for (rec in readAll(c, HeartRateRecord::class, range)) {
                origins.add(rec.metadata.dataOrigin.packageName)
                for (s in rec.samples) perMin[s.time.epochSecond / 60 * 60] = s.beatsPerMinute
            }
            out.put("hr", JSONArray(perMin.map { JSONArray().put(it.key).put(it.value) }))
        }

        // ── замеры «в момент времени» ──
        if (can(RestingHeartRateRecord::class))
            out.put("rest_hr", points(readAll(c, RestingHeartRateRecord::class, range)) { it.time to it.beatsPerMinute.toDouble() })
        if (can(HeartRateVariabilityRmssdRecord::class))
            out.put("hrv", points(readAll(c, HeartRateVariabilityRmssdRecord::class, range)) { it.time to it.heartRateVariabilityMillis })
        if (can(OxygenSaturationRecord::class))
            out.put("spo2", points(readAll(c, OxygenSaturationRecord::class, range)) { it.time to it.percentage.value })
        if (can(RespiratoryRateRecord::class))
            out.put("resp", points(readAll(c, RespiratoryRateRecord::class, range)) { it.time to it.rate })
        if (can(Vo2MaxRecord::class))
            out.put("vo2max", points(readAll(c, Vo2MaxRecord::class, range)) { it.time to it.vo2MillilitersPerMinuteKilogram })
        if (can(BodyTemperatureRecord::class))
            out.put("temp", points(readAll(c, BodyTemperatureRecord::class, range)) { it.time to it.temperature.inCelsius })
        if (can(BloodGlucoseRecord::class))
            out.put("glucose", points(readAll(c, BloodGlucoseRecord::class, range)) { it.time to it.level.inMillimolesPerLiter })
        if (can(HydrationRecord::class))
            out.put("hydration", points(readAll(c, HydrationRecord::class, range)) { it.startTime to it.volume.inLiters })
        if (can(HeightRecord::class))
            out.put("height", points(readAll(c, HeightRecord::class, range)) { it.time to it.height.inMeters })
        if (can(BodyFatRecord::class))
            out.put("body_fat", points(readAll(c, BodyFatRecord::class, range)) { it.time to it.percentage.value })
        if (can(BloodPressureRecord::class)) {
            val arr = JSONArray()
            for (r in readAll(c, BloodPressureRecord::class, range))
                arr.put(JSONArray().put(r.time.epochSecond).put(r.systolic.inMillimetersOfMercury).put(r.diastolic.inMillimetersOfMercury))
            out.put("bp", arr)
        }
        if (can(WeightRecord::class)) {
            val fat = if (can(BodyFatRecord::class)) readAll(c, BodyFatRecord::class, range) else emptyList()
            val arr = JSONArray()
            for (r in readAll(c, WeightRecord::class, range)) {
                origins.add(r.metadata.dataOrigin.packageName)
                val bf = fat.firstOrNull { Duration.between(it.time, r.time).abs().toMinutes() < 10 }?.percentage?.value
                arr.put(JSONArray().put(r.time.epochSecond).put(r.weight.inKilograms).apply { if (bf != null) put(bf) })
            }
            out.put("weight", arr)
        }

        // ── сон ──
        if (can(SleepSessionRecord::class)) {
            val arr = JSONArray()
            for (s in readAll(c, SleepSessionRecord::class, range)) {
                origins.add(s.metadata.dataOrigin.packageName)
                val st = JSONArray()
                for (g in s.stages) st.put(JSONArray().put(g.startTime.epochSecond).put(g.endTime.epochSecond).put(g.stage))
                arr.put(JSONObject().put("id", s.metadata.id).put("start", s.startTime.epochSecond).put("end", s.endTime.epochSecond)
                    .put("title", s.title ?: "").put("origin", s.metadata.dataOrigin.packageName).put("stages", st))
            }
            out.put("sleep", arr)
        }

        // ── тренировки ──
        if (can(ExerciseSessionRecord::class)) {
            val arr = JSONArray()
            for (s in readAll(c, ExerciseSessionRecord::class, range)) {
                origins.add(s.metadata.dataOrigin.packageName)
                val o = JSONObject().put("id", s.metadata.id).put("type", s.exerciseType).put("start", s.startTime.epochSecond)
                    .put("end", s.endTime.epochSecond).put("title", s.title ?: "").put("notes", s.notes ?: "")
                    .put("origin", s.metadata.dataOrigin.packageName)
                val m = HashSet<AggregateMetric<*>>()
                if (can(StepsRecord::class)) m.add(StepsRecord.COUNT_TOTAL)
                if (can(DistanceRecord::class)) m.add(DistanceRecord.DISTANCE_TOTAL)
                if (can(TotalCaloriesBurnedRecord::class)) m.add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                if (can(ActiveCaloriesBurnedRecord::class)) m.add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                if (can(HeartRateRecord::class)) { m.add(HeartRateRecord.BPM_AVG); m.add(HeartRateRecord.BPM_MAX); m.add(HeartRateRecord.BPM_MIN) }
                if (m.isNotEmpty() && s.endTime > s.startTime) {
                    val r = runCatching { c.aggregate(AggregateRequest(metrics = m, timeRangeFilter = TimeRangeFilter.between(s.startTime, s.endTime))) }.getOrNull()
                    if (r != null) {
                        r[StepsRecord.COUNT_TOTAL]?.let { o.put("steps", it) }
                        r[DistanceRecord.DISTANCE_TOTAL]?.let { o.put("distance_m", it.inMeters) }
                        (r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL] ?: r[TotalCaloriesBurnedRecord.ENERGY_TOTAL])
                            ?.let { o.put("kcal", it.inKilocalories.toLong()) }
                        r[HeartRateRecord.BPM_AVG]?.let { o.put("hr_avg", it) }
                        r[HeartRateRecord.BPM_MAX]?.let { o.put("hr_max", it) }
                        r[HeartRateRecord.BPM_MIN]?.let { o.put("hr_min", it) }
                    }
                }
                val rr = s.exerciseRouteResult
                if (rr is ExerciseRouteResult.Data) {
                    val pts = rr.exerciseRoute.route
                    val step = maxOf(1, pts.size / 1500)                // не больше ~1500 точек на тренировку
                    val route = JSONArray()
                    var gain = 0.0
                    var prevAlt: Double? = null
                    pts.forEachIndexed { i, l ->
                        val alt = l.altitude?.inMeters
                        if (alt != null && prevAlt != null && alt > prevAlt!!) gain += alt - prevAlt!!
                        if (alt != null) prevAlt = alt
                        if (i % step == 0 || i == pts.size - 1)
                            route.put(JSONArray().put(l.time.epochSecond).put(l.latitude).put(l.longitude).put(alt ?: JSONObject.NULL))
                    }
                    if (route.length() > 1) o.put("route", route)
                    if (gain > 0) o.put("elevation_m", gain)
                }
                arr.put(o)
            }
            out.put("workouts", arr)
        }
        return out
    }

    private suspend fun <T : Record> readAll(c: HealthConnectClient, type: KClass<T>, range: TimeRangeFilter,
                                             max: Int = 50_000): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val r = c.readRecords(ReadRecordsRequest(recordType = type, timeRangeFilter = range, pageSize = 1000, pageToken = token))
            out.addAll(r.records)
            token = r.pageToken?.takeIf { it.isNotEmpty() }
        } while (token != null && out.size < max)
        return out
    }

    private fun <T> points(list: List<T>, f: (T) -> Pair<Instant, Double>): JSONArray {
        val arr = JSONArray()
        for (x in list) { val (t, v) = f(x); arr.put(JSONArray().put(t.epochSecond).put(v)) }
        return arr
    }

    /**
     * Сводка модуля «Браслет» с сервера: цель по шагам и подсказка для виджета; если Health Connect не включён —
     * и сами цифры (браслет через Gadgetbridge и т. п.).
     */
    fun pullServerSummary(ctx: Context) {
        val p = Prefs(ctx)
        val r = Api.get(ctx, "/modules/bracelet/api/summary", 15000)
        r.optInt("steps_goal", 0).takeIf { it > 0 }?.let { p.stepsGoal = it }
        val today = LocalDate.now().toString()
        val local = runCatching { JSONObject(p.healthToday) }.getOrDefault(JSONObject())
        val o = if (!p.healthEnabled || local.optString("date") != today) {
            JSONObject().put("date", r.optString("date", today)).put("ts", System.currentTimeMillis()).put("from_server", true).apply {
                if (r.optLong("steps") > 0) put("steps", r.optLong("steps"))
                if (r.optLong("distance_m") > 0) put("distance_m", r.optLong("distance_m"))
                if (r.optLong("kcal") > 0) put("active_kcal", r.optLong("kcal"))
                if (r.optInt("hr_rest") > 0) put("hr_rest", r.optInt("hr_rest"))
                if (r.optInt("spo2") > 0) put("spo2", r.optInt("spo2"))
                if (r.optInt("sleep_min") > 0) put("sleep_min", r.optInt("sleep_min"))
            }
        } else local
        o.put("steps_goal", p.stepsGoal)
        val ins = r.optJSONArray("insights")
        if (ins != null && ins.length() > 0) ins.optJSONObject(0)?.let { o.put("insight", it.optString("icon") + " " + it.optString("text")) }
        else o.remove("insight")
        o.put("week_workouts", r.optInt("week_workouts"))
        p.healthToday = o.toString()
        HealthWidget.renderAll(ctx)
    }

    /** Сводка «сегодня» для виджета и офлайн-экрана — из того же пакета, что ушёл на сервер. */
    private fun summarize(payload: JSONObject, zone: ZoneId): JSONObject {
        val today = LocalDate.now(zone).toString()
        val o = JSONObject().put("date", today).put("ts", System.currentTimeMillis())
        val daily = payload.optJSONArray("daily") ?: JSONArray()
        for (i in 0 until daily.length()) {
            val d = daily.optJSONObject(i) ?: continue
            if (d.optString("date") == today) {
                for (k in listOf("steps", "distance_m", "active_kcal", "total_kcal", "floors", "hr_rest", "hr_avg", "exercise_min"))
                    if (d.has(k)) o.put(k, d.get(k))
            }
        }
        payload.optJSONArray("hr")?.let { if (it.length() > 0) it.optJSONArray(it.length() - 1)?.let { x -> o.put("hr_last", x.optLong(1)).put("hr_last_ts", x.optLong(0)) } }
        payload.optJSONArray("spo2")?.let { if (it.length() > 0) o.put("spo2", it.optJSONArray(it.length() - 1)?.optDouble(1)) }
        val sleep = payload.optJSONArray("sleep")
        if (sleep != null && sleep.length() > 0) {
            var best: JSONObject? = null
            for (i in 0 until sleep.length()) {
                val s = sleep.optJSONObject(i) ?: continue
                val wake = Instant.ofEpochSecond(s.optLong("end")).atZone(zone).toLocalDate().toString()
                if (wake == today && (best == null || s.optLong("end") - s.optLong("start") > best.optLong("end") - best.optLong("start"))) best = s
            }
            best?.let { s ->
                var asleep = 0L
                val st = s.optJSONArray("stages") ?: JSONArray()
                for (i in 0 until st.length()) {
                    val g = st.optJSONArray(i) ?: continue
                    if (g.optInt(2) in setOf(2, 4, 5, 6)) asleep += g.optLong(1) - g.optLong(0)
                }
                if (asleep == 0L) asleep = s.optLong("end") - s.optLong("start")
                o.put("sleep_min", asleep / 60).put("sleep_start", s.optLong("start")).put("sleep_end", s.optLong("end"))
            }
        }
        val wk = payload.optJSONArray("workouts")
        if (wk != null && wk.length() > 0) wk.optJSONObject(wk.length() - 1)?.let { o.put("last_workout", it.optInt("type")).put("last_workout_ts", it.optLong("start")) }
        return o
    }
}
