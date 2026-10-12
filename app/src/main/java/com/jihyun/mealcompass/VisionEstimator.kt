package com.jihyun.mealcompass

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession

data class FoodItem(val name: String, val kcal: Int)

/**
 * 폰 안의 멀티모달 모델(Gemma-3n 등)로 음식 사진의 칼로리를 추정한다.
 * 사진은 폰 밖으로 나가지 않는다.
 *
 * 주의: 모델/세션 API 이름은 구글 공식 안내(MediaPipe LLM Inference, 멀티모달)를 따랐지만
 * 아직 직접 컴파일해 보지 못했다. 빌드 오류가 나면 이 파일부터 확인할 것.
 */
object VisionEstimator {

    private const val PROMPT =
        "사진에 보이는 음식을 모두 찾아서, 사진 속 양 기준으로 각각의 대략적인 칼로리를 추정해 줘.\n" +
            "한 줄에 하나씩, 정확히 이 형식으로만 답해: 음식이름 | 칼로리숫자\n" +
            "예: 김치찌개 | 300\n" +
            "음식이 없으면 '없음'이라고만 답해."

    /** 무거운 작업이므로 반드시 백그라운드 스레드에서 호출한다. 끝나면 모델을 바로 해제한다. */
    fun estimate(context: Context, modelPath: String, bitmap: Bitmap): String {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(768)
            .setMaxNumImages(1)
            .build()
        return LlmInference.createFromOptions(context, options).use { engine ->
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.2f)
                .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                .build()
            LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
                session.addQueryChunk(PROMPT)
                session.addImage(BitmapImageBuilder(bitmap).build())
                session.generateResponse()
            }
        }
    }

    /** "음식이름 | 300" 형태의 줄들을 읽는다. 읽지 못한 줄은 버린다. */
    fun parse(text: String): List<FoodItem> {
        if (text.trim().startsWith("없음")) return emptyList()
        val re = Regex("""^[\s\-*•\d.)]*([^|:：\n]{1,30}?)\s*[|:：]\s*(?:약\s*)?(\d{2,4})""")
        val seen = HashSet<String>()
        val out = ArrayList<FoodItem>()
        for (line in text.lines()) {
            val m = re.find(line.trim()) ?: continue
            val name = m.groupValues[1].trim()
            val kcal = m.groupValues[2].toInt()
            if (name.isBlank() || kcal !in 20..2500 || !seen.add(name)) continue
            out.add(FoodItem(name, kcal))
            if (out.size >= 8) break
        }
        return out
    }
}
