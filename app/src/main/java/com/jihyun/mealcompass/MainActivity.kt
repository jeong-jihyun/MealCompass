package com.jihyun.mealcompass

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

private const val CAL_NOTICE =
    "※ 사진만으로는 양·재료·조리법을 알 수 없어 실제와 크게 다를 수 있어요. " +
        "대략적인 참고용이며 진단·치료 조언이 아니에요."

/** 건강 요약 (읽지 못한 항목은 null) */
data class Health(val steps: Long?, val sleepMin: Long?, val avgHr: Long?) {
    fun toText(): String {
        val s = steps?.let { "${it}보" } ?: "정보 없음"
        val sl = sleepMin?.let { "${it / 60}시간 ${it % 60}분" } ?: "정보 없음"
        val hr = avgHr?.let { "${it}회/분" } ?: "정보 없음"
        return "걸음 수 $s, 수면 $sl, 평균 심박 $hr"
    }
}

data class Place(val name: String, val category: String, val distance: Int, val url: String)

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("mealcompass", Context.MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var result: TextView
    private var llm: LlmInference? = null

    private val healthPerms = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )

    private val healthLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(healthPerms)) {
            setStatus("건강 데이터 연결 완료")
        } else {
            setStatus("일부 권한이 거부됐어요. 거부된 항목은 '정보 없음'으로 처리하고 추천해요.")
        }
    }

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.any { it }) recommend() else setStatus("위치 권한이 없으면 주변 식당을 찾을 수 없어요.")
    }

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) copyModel(uri) }

    private val visionPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) copyVisionModel(uri) }

    private var pendingPhoto: Uri? = null

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val u = pendingPhoto
        if (ok && u != null) analyze(u) else clearPhotos()
    }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) analyze(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (!prefs.getBoolean("consent", false)) showConsent() else loadModel()
    }

    override fun onDestroy() {
        llm?.close()
        llm = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(24))
        }
        col.addView(TextView(this).apply { text = "MealCompass"; textSize = 24f })
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
            text = "①②③ 설정 후 ④를 눌러요. (②는 없어도 기본 규칙으로 추천해요)\n" +
                "⑤ 칼로리 보기는 ⑥ 모델이 없어도 음식 목록에서 고를 수 있어요."
        }
        col.addView(status)
        addButton(col, "① 건강 데이터 연결") { connectHealth() }
        addButton(col, "② 폰 안 AI 모델 파일 선택 (.task)") { modelPicker.launch(arrayOf("*/*")) }
        addButton(col, "③ 카카오 REST 키 입력") { askKakaoKey() }
        addButton(col, "④ 지금 메뉴 추천받기") { recommend() }
        addButton(col, "⑤ 음식 사진으로 칼로리 보기") { showPhotoMenu() }
        addButton(col, "⑥ 사진 분석용 AI 모델 선택 (Gemma-3n)") { visionPicker.launch(arrayOf("*/*")) }
        addButton(col, "동의 철회 및 저장된 설정 삭제") { withdraw() }
        result = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(16), 0, 0)
            setTextIsSelectable(true)
        }
        col.addView(result)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun addButton(parent: LinearLayout, label: String, onClick: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }

    private fun setStatus(s: String) { status.text = s }

    private fun showConsent() {
        AlertDialog.Builder(this)
            .setTitle("시작하기 전에 확인해 주세요")
            .setMessage(
                "이 앱은 (1) 헬스 커넥트의 걸음 수·수면·심박을 읽고, (2) 현재 위치로 주변 식당을 찾아 메뉴를 추천해요.\n\n" +
                "• 건강 데이터는 이 폰 안에서만 처리되고 밖으로 전송되지 않아요.\n" +
                "• 식당 검색을 위해 현재 위치 좌표가 카카오 서버로 전송돼요.\n" +
                "• 음식 사진은 이 폰 안에서만 분석하고, 분석이 끝나면 앱이 임시로 저장한 사진은 지워요.\n" +
                "• 언제든 '동의 철회' 버튼으로 저장된 설정을 지울 수 있어요.\n" +
                "• 추천은 일반적인 식습관 참고용이며 진단·치료 조언이 아니에요."
            )
            .setCancelable(false)
            .setPositiveButton("동의") { _, _ ->
                prefs.edit().putBoolean("consent", true).apply()
                loadModel()
            }
            .setNegativeButton("종료") { _, _ -> finish() }
            .show()
    }

    private fun withdraw() {
        prefs.edit().clear().apply()
        File(filesDir, "model.task").delete()
        visionModelFile().delete()
        clearPhotos()
        llm?.close()
        llm = null
        Toast.makeText(this, "저장된 설정과 모델 파일을 삭제했어요", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun askKakaoKey() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("kakao", ""))
            hint = "카카오 REST API 키"
        }
        AlertDialog.Builder(this)
            .setTitle("카카오 REST API 키")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                prefs.edit().putString("kakao", input.text.toString().trim()).apply()
                setStatus("카카오 키를 저장했어요")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------------------------------------------------------------- 건강 데이터

    private fun connectHealth() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_UNAVAILABLE ->
                setStatus("이 폰은 헬스 커넥트를 지원하지 않아요.")
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                setStatus("헬스 커넥트 앱 설치/업데이트가 필요해요. 스토어 화면을 열게요.")
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                    )
                } catch (e: Exception) { /* 스토어 앱이 없으면 무시 */ }
            }
            else -> healthLauncher.launch(healthPerms)
        }
    }

    private suspend fun <T> safe(block: suspend () -> T?): T? =
        try { block() } catch (e: Exception) { null }

    private suspend fun readHealth(): Health {
        val client = try {
            HealthConnectClient.getOrCreate(this)
        } catch (e: Exception) {
            return Health(null, null, null)
        }
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val last24h = now.minusSeconds(24 * 3600)
        val last30h = now.minusSeconds(30 * 3600)

        val steps = safe {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, now)
                )
            )[StepsRecord.COUNT_TOTAL]
        }
        val sleepMin = safe {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(last30h, now)
                )
            )[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()
        }
        val hr = safe {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(last24h, now)
                )
            )[HeartRateRecord.BPM_AVG]
        }
        return Health(steps, sleepMin, hr)
    }

    // ---------------------------------------------------------------- 위치 + 식당

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun getLocation() =
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token)
            .await()

    private fun searchPlaces(key: String, lng: Double, lat: Double): List<Place> {
        val url = URL(
            "https://dapi.kakao.com/v2/local/search/category.json" +
                "?category_group_code=FD6&x=$lng&y=$lat&radius=1000&sort=distance&size=15"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "KakaoAK $key")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        if (conn.responseCode != 200) {
            error("카카오 응답 ${conn.responseCode}: 키와 카카오 개발자 설정을 확인해 주세요")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val docs = JSONObject(body).getJSONArray("documents")
        return List(docs.length()) { i ->
            val o = docs.getJSONObject(i)
            Place(
                name = o.getString("place_name"),
                category = o.optString("category_name"),
                distance = o.optString("distance", "0").toIntOrNull() ?: 0,
                url = o.optString("place_url")
            )
        }
    }

    // ---------------------------------------------------------------- 폰 안 AI 모델

    private fun modelFile() = File(filesDir, "model.task")

    private fun loadModel() {
        val f = modelFile()
        if (!f.exists()) return
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(f.absolutePath)
                    .setMaxTokens(1024)
                    .build()
                llm = LlmInference.createFromOptions(this@MainActivity, opts)
                withContext(Dispatchers.Main) { setStatus("폰 안 AI 모델 준비 완료") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("모델을 불러오지 못해 기본 규칙으로 추천해요: ${e.message}")
                }
            }
        }
    }

    private fun copyModel(uri: Uri) {
        lifecycleScope.launch {
            setStatus("모델 파일 복사 중… (수백 MB라 잠시 걸려요)")
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { input ->
                        modelFile().outputStream().use { out -> input.copyTo(out) }
                    }
                }
                llm?.close()
                llm = null
                setStatus("복사 완료. 모델을 불러오는 중…")
                loadModel()
            } catch (e: Exception) {
                setStatus("모델 복사 실패: ${e.message}")
            }
        }
    }

    private fun buildPrompt(h: Health, list: List<Place>): String {
        val lines = list.mapIndexed { i, p ->
            "${i + 1}. ${p.name} (${p.category.substringAfterLast(" > ")})"
        }.joinToString("\n")
        return "<start_of_turn>user\n" +
            "너는 식단 도우미야. 진단이나 치료 이야기는 하지 마.\n" +
            "내 상태: ${h.toText()}\n" +
            "아래 근처 식당 중 오늘 나에게 맞는 곳 3개를 골라 줘. 목록에 없는 곳은 말하지 마.\n" +
            "형식은 한 줄에 하나씩 정확히: 번호: 한 문장 이유\n\n" +
            lines +
            "<end_of_turn>\n<start_of_turn>model\n"
    }

    private fun parsePicks(out: String, list: List<Place>): List<Pair<Place, String>> {
        val re = Regex("""(\d{1,2})\s*[:.)]\s*(.+)""")
        val seen = HashSet<Int>()
        val res = ArrayList<Pair<Place, String>>()
        for (line in out.lines()) {
            val m = re.find(line.trim()) ?: continue
            val n = m.groupValues[1].toInt()
            if (n in 1..list.size && seen.add(n)) {
                res.add(list[n - 1] to m.groupValues[2].trim())
            }
            if (res.size == 3) break
        }
        return res
    }

    /** AI 모델이 없거나 실패했을 때 쓰는 단순 규칙 */
    private fun rulePick(h: Health, places: List<Place>): List<Pair<Place, String>> {
        val tired = (h.sleepMin ?: Long.MAX_VALUE) < 360
        val active = (h.steps ?: 0L) > 10000
        val sedentary = (h.steps ?: Long.MAX_VALUE) < 3000
        val (keywords, why) = when {
            tired || active -> listOf("국밥", "탕", "죽", "찌개", "백반", "한식", "고기") to
                "수면이 짧거나 많이 움직인 날엔 든든한 한식이 잘 맞아요"
            sedentary -> listOf("샐러드", "샌드위치", "쌈", "비빔밥", "샤브", "냉면") to
                "활동이 적은 날엔 가볍고 채소가 많은 메뉴가 잘 맞아요"
            else -> listOf("비빔밥", "백반", "샤브", "쌈", "국수", "한식") to
                "무난하게 균형 잡힌 한식 위주로 골랐어요"
        }
        val scored = places.sortedByDescending { p ->
            keywords.count { p.category.contains(it) || p.name.contains(it) }
        }
        return scored.take(3).map { it to why }
    }

    private suspend fun pick(h: Health, places: List<Place>): Pair<List<Pair<Place, String>>, String> {
        val model = llm
        if (model != null) {
            try {
                val list = places.take(10)
                val out = withContext(Dispatchers.Default) { model.generateResponse(buildPrompt(h, list)) }
                val parsed = parsePicks(out, list)
                if (parsed.isNotEmpty()) return parsed to "폰 안 AI"
            } catch (e: Exception) { /* 아래 기본 규칙으로 */ }
        }
        return rulePick(h, places) to "기본 규칙 (AI 모델 미사용)"
    }

    // ---------------------------------------------------------------- 사진 칼로리

    private fun visionModelFile() = File(filesDir, "vision.model")

    private fun photosDir() = File(cacheDir, "photos").apply { mkdirs() }

    private fun clearPhotos() {
        photosDir().listFiles()?.forEach { it.delete() }
    }

    private fun copyVisionModel(uri: Uri) {
        lifecycleScope.launch {
            setStatus("사진 분석 모델 복사 중… (수 GB라 오래 걸릴 수 있어요)")
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { input ->
                        visionModelFile().outputStream().use { out -> input.copyTo(out) }
                    }
                }
                setStatus("사진 분석 모델 저장 완료. ⑤로 사진을 분석해 보세요.")
            } catch (e: Exception) {
                visionModelFile().delete()
                setStatus("모델 복사 실패: ${e.message}")
            }
        }
    }

    private fun showPhotoMenu() {
        AlertDialog.Builder(this)
            .setTitle("음식 사진으로 칼로리 보기")
            .setItems(arrayOf("카메라로 찍기", "갤러리에서 고르기", "사진 없이 음식 목록에서 고르기")) { _, which ->
                when (which) {
                    0 -> launchCamera()
                    1 -> pickImage.launch("image/*")
                    else -> showFoodList()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun launchCamera() {
        try {
            val file = File(photosDir(), "meal_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            pendingPhoto = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            setStatus("카메라 앱을 열 수 없어요: ${e.message}")
        }
    }

    /** 사진을 한 변 768px 이하로 줄여서 읽고, 찍힌 방향대로 돌려 준다. */
    private fun loadBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val degrees = try {
            contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (e: Exception) { 0 }
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun analyze(uri: Uri) {
        val model = visionModelFile()
        if (!model.exists()) {
            clearPhotos()
            setStatus("사진 분석 모델(⑥)이 없어요. 대신 음식 목록에서 골라 볼게요.")
            showFoodList()
            return
        }
        lifecycleScope.launch {
            result.text = "사진을 분석하는 중… (모델을 불러오느라 1분 가까이 걸릴 수 있어요)"
            try {
                val bmp = withContext(Dispatchers.IO) { loadBitmap(uri) }
                    ?: error("사진을 읽지 못했어요")
                // 메모리가 부족하지 않게 식당 추천용 작은 모델은 잠깐 내려 둔다
                llm?.close()
                llm = null
                val raw = withContext(Dispatchers.Default) {
                    VisionEstimator.estimate(this@MainActivity, model.absolutePath, bmp)
                }
                result.text = renderCalories(VisionEstimator.parse(raw), raw)
            } catch (e: OutOfMemoryError) {
                result.text = "메모리가 부족해요. 다른 앱을 닫고 다시 해 보거나 음식 목록에서 골라 주세요."
            } catch (e: Exception) {
                result.text = "분석 실패: ${e.message}\n음식 목록에서 직접 고를 수도 있어요."
            } finally {
                clearPhotos()
                loadModel()
            }
        }
    }

    private fun renderCalories(items: List<FoodItem>, raw: String): String {
        if (items.isEmpty()) {
            return "음식을 알아보지 못했어요.\n\n모델 응답:\n${raw.take(300)}\n\n" +
                "'사진 없이 음식 목록에서 고르기'를 이용해 보세요."
        }
        val sb = StringBuilder("사진 속 음식 추정 (폰 안 AI)\n\n")
        items.forEach { sb.append("• ${it.name}: 약 ${it.kcal} kcal\n") }
        sb.append("\n합계: 약 ${items.sumOf { it.kcal }} kcal\n\n").append(CAL_NOTICE)
        return sb.toString()
    }

    private fun showFoodList() {
        val foods = FoodTable.foods
        AlertDialog.Builder(this)
            .setTitle("음식을 골라 주세요")
            .setItems(foods.map { it.name }.toTypedArray()) { _, i -> showPortion(foods[i]) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showPortion(f: Food) {
        val labels = arrayOf("적게 (0.7배)", "보통 (1배)", "많이 (1.3배)")
        val ratios = doubleArrayOf(0.7, 1.0, 1.3)
        AlertDialog.Builder(this)
            .setTitle("${f.name}, 양은 어떤가요?")
            .setItems(labels) { _, i ->
                val kcal = (f.kcal * ratios[i] / 10).roundToInt() * 10
                result.text = "${f.name} · ${labels[i]}\n약 $kcal kcal\n\n$CAL_NOTICE"
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------------------------------------------------------------- 추천 실행

    private fun recommend() {
        val key = prefs.getString("kakao", "") ?: ""
        if (key.isBlank()) { setStatus("③에서 카카오 REST 키를 먼저 넣어 주세요."); return }
        if (!hasLocationPermission()) {
            locationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        lifecycleScope.launch {
            result.text = "찾는 중…"
            try {
                val h = readHealth()
                val loc = getLocation()
                if (loc == null) {
                    result.text = "위치를 가져오지 못했어요. 위치 설정을 켜 주세요."
                    return@launch
                }
                val places = withContext(Dispatchers.IO) { searchPlaces(key, loc.longitude, loc.latitude) }
                if (places.isEmpty()) {
                    result.text = "1km 안에 식당을 찾지 못했어요."
                    return@launch
                }
                val (picks, how) = pick(h, places)
                result.text = render(picks, how, h)
            } catch (e: Exception) {
                result.text = "오류: ${e.message}"
            }
        }
    }

    private fun render(picks: List<Pair<Place, String>>, how: String, h: Health): String {
        val sb = StringBuilder("추천 방식: $how\n내 상태: ${h.toText()}\n\n")
        picks.forEachIndexed { i, (p, why) ->
            sb.append("${i + 1}) ${p.name} · ${p.distance}m · ${p.category.substringAfterLast(" > ")}\n")
            sb.append("   $why\n")
            if (p.url.isNotBlank()) sb.append("   ${p.url}\n")
            sb.append("\n")
        }
        sb.append("※ 일반적인 식습관 참고용이며 진단·치료 조언이 아니에요.")
        return sb.toString()
    }
}
