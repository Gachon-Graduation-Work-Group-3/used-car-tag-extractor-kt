package org.example

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class TagExtractor {

    // OpenAI API 관련 설정 값들
    private val API_KEY = "API KEY 값은 따로 보내드릴게요"
    private val MODEL = "gpt-4o-mini"


    // 각 태그의 정의를 설명하는 문자열을 생성하는 함수
    // GPT 프롬프트에 포함되어 태그 분류 기준이 됨
    private fun createTagDefinitions(): String {
        return """
        - "무사고": 사고나 침수 등이 난 적이 없는 매물입니다. 텍스트에서 사고가 없었다는 점을 강조할 확률이 높습니다. 데이터에는 사고 유무, 정비, 사고 관련 수치가 포함되어 있습니다.
        - "튜닝": 차량에 다양한 추가적인 옵션이 많이 달려있는 매물입니다. 텍스트에서 옵션이 많다는 점을 강조할 확률이 높습니다. 데이터에는 각 옵션 유무가 포함되어 있습니다.
        - "1인사용": 차량의 소유주가 변경된 적이 없는 매물입니다. 텍스트에서 1인소유, 1인사용 등을 강조할 확률이 높습니다. 데이터의 '소유자변경' 컬럼을 참고하면 좋습니다.
        - "출퇴근용": 주로 출퇴근 목적으로 사용된 것으로 보이는 차량입니다. 연간 주행거리가 15,000km~25,000km 범위이며, 주행 패턴이 규칙적이거나, 텍스트에 "출퇴근", "통근", "직장", "회사" 등의 단어가 포함되어 있을 확률이 높습니다.
        - "나들이용": 주로 여가 활동이나 주말 사용 목적으로 운행된 차량입니다. 주행거리가 연간 10,000km 이하이고, 텍스트에 "주말", "나들이", "여행", "캠핑", "레저", "가족" 등의 단어가 포함되어 있을 확률이 높습니다.
        - "연식대비적은마일리지": 차량 연식에 비해 주행거리가 현저히 적은 차량입니다. 자세한 기준은 다음과 같습니다:
            * 5년 미만 차량: 연평균 주행거리 10,000km 이하
            * 5-10년 차량: 연평균 주행거리 8,000km 이하
            * 10년 이상 차량: 연평균 주행거리 5,000km 이하
        - "전문정비": 정기적으로 전문 정비소나 제조사 서비스센터에서 관리되었거나 딜러가 상태가 좋다고 보증한 차량입니다. 텍스트에 "제조사", "서비스센터", "정비소", "직영점", "정비기록", "정식 딜러", "딜러 보증" 등의 단어가 포함되어 있을 확률이 높습니다.
        - "급매물": 시장 평균 가격보다 현저히 낮은 가격에 판매되거나, 빠른 판매를 원하는 판매자의 차량입니다. 가격' 컬럼이 동일 차종/연식/조건 대비 15% 이상 저렴하거나 텍스트에 "급매", "급처", "급히", "빨리", "즉시", "네고가능", "가격조정", "대폭할인" 등의 급한 상황(이사, 이직, 급전 등)을 암시하는 단어가 포함되어 있을 확률이 높습니다.
        """.trimIndent()
    }

    // GPT에게 보낼 프롬프트 메시지를 생성하는 함수
    // 사용자가 분석하려는 데이터와 텍스트 컬럼명을 받아 프롬프트로 변환
    private fun createPrompt(data: String, textColumn: String): String {
        return """
            # 중고차 데이터 태그 분류 작업

            ## 작업 설명
            아래에 주어진 하나의 중고차 데이터를 분석하고, 주요 텍스트 컬럼 '${textColumn}'을 기반으로 해당 차량에 부여할 수 있는 태그들을 선정해주세요.

            ## 태그 정의
            다음 태그 정의에 따라 데이터를 분류해주세요:
            ${createTagDefinitions()}

            ## 분류 규칙
            1. 특별한 특징이 없는 경우, 태그를 부여하지 마세요.
            2. 데이터의 태그는 없을 수도 있고 1개 있을 수도 있고 여러 개 있을 수도 있습니다.
            3. 주 분석 대상은 '${textColumn}' 컬럼이며, 다른 컬럼의 정보도 함께 고려하세요.
            4. 단, 컬럼 값이 null인 경우는 정보 수집 실패로 간주하고 분석에 포함하지 마세요.
            5. 태그는 데이터 전체 맥락에서 명확하고 뚜렷한 특징이 보일 때만 부여해야 합니다.
            6. 태그 선정은 느슨하지 않게, 엄격한 기준으로 판단해주세요.

            ## 입력 데이터
            ${data}

            ## 출력 형식
            해당 데이터에 적용 가능한 태그를 아래와 같은 JSON 배열 형식으로 응답해주세요:
            {"tags": ["태그1", "태그2"]}
            
            특징이 없어서 태그를 부여하지 않을 경우, 빈 배열을 반환해주세요:
            {"tags": []}
        """.trimIndent()
    }

    // GPT API에 요청을 보내고, 응답에서 태그 리스트를 추출하는 내부 비동기 함수
    // 네트워크 요청 및 JSON 파싱 처리 포함
    private suspend fun requestTagsFromGPT(data: JsonObject, textColumn: String = "설명글"): List<String> = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val gson = Gson()
        val dataString = gson.toJson(data)

        val prompt = createPrompt(dataString, textColumn)

        val requestBody = ChatRequest(
            model = MODEL,
            messages = listOf(
                ChatMessage(
                    role = "system",
                    content = "당신은 데이터 분석 전문가입니다. 텍스트를 분석하고 지정된 태그에 따라 특징적인 데이터만 분류합니다."
                ),
                ChatMessage(
                    role = "user",
                    content = prompt
                )
            )
        )
        val jsonBody = gson.toJson(requestBody)
        val mediaType = "application/json".toMediaType()
        val body = jsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw RuntimeException("API 호출 실패: ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: throw RuntimeException("응답 본문이 없습니다.")

        // 응답 본문에서 GPT가 생성한 JSON 형식의 태그 정보를 파싱
        val responseJsonObject = gson.fromJson(responseBody, JsonObject::class.java)
        val content = responseJsonObject
            .getAsJsonArray("choices")[0]
                .asJsonObject
                .getAsJsonObject("message")
                .getAsJsonPrimitive("content")
                .asString

        // {"tags": [...]} 형식으로 파싱하여 리스트로 반환
        val chatResponse = gson.fromJson(content, ChatResponse::class.java)
        return@withContext chatResponse.tags
    }

    // 외부에서 호출할 수 있는 공개 함수
    // JSON 형태의 차량 데이터를 받아 GPT로부터 태그 리스트를 추출
    suspend fun extractTagsFromData(data: JsonObject, textColumn: String = "설명글"): List<String> {
        var tags: List<String> = emptyList()
        try {
            tags = requestTagsFromGPT(data, textColumn)
        } catch (e: Exception) {
            e.printStackTrace()
            println("[TagExtractor] Error: ${e.message}")
        }
        return tags
    }

    // GPT API와의 대화를 위해 사용하는 메시지 포맷 클래스
    private data class ChatMessage(
        val role: String,
        val content: String
    )

    // GPT에 전달할 요청 본문의 구조를 정의한 클래스
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>
    )

    // GPT 응답 형식을 매핑할 데이터 클래스
    data class ChatResponse(
        val tags: List<String>
    )
}

fun main() {
    // 샘플 데이터로 테스트
    val sampleData = JsonObject().apply {
        addProperty("링크", "https://www.bobaedream.co.kr/mycar/mycar_view.php?no=2239720&gubun=K")
        addProperty("이름", "쉐보레 트레일블레이저 1.3 터보 2WD  RS")
        addProperty("가격", 2045)
        addProperty("신차대비가격", 79)
        addProperty("신차가격", 2573)
        addProperty("차량번호", "141너7726")
        addProperty("최초등록일", "24/12/04")
        addProperty("조회수", 20)
        addProperty("연식", "2021.06")
        addProperty("주행거리", 36427)
        addProperty("연료", "가솔린")
        addProperty("배기량", 1341)
        addProperty("색상", "회색")
        addProperty("보증정보", "60개월/100,000km")
        addProperty("설명글",
            """
                    안녕하세요. 영남 최대규모의 중고차 복합 쇼핑몰 KC월드카프라자 더케어모터스 조진한 대표입니다.
            
                    현재 고객님께서 보고 계신 차량은 100% 실 매물이므로 매장 방문 시 바로 차량 확인이 가능합니다.
            
                    단순 차량 판매가 아닌 사후 관리까지 정직과 신뢰를 바탕으로 최선을 다하겠습니다.
            
                    ▷차량 안내
                    - 차종 : 쉐보레 트레일블레이저 1.3 터보 2WD RS
                    - 연식 : 2022년식 / 2021년 06월 최초 등록
                    - 주행거리 : 36,427km
                    - 사고유무 : 완전무사고
                    - 옵션정보 : 프리미엄 패키지, 셀렉티브 패키지, BOSE 프리미엄 7스피커, 스마트키 등
            
                    ▷특징 설명
                    완전 무사고 차량으로 한분이 계속 소유한 1인신조 차량입니다.
                    풍부한 옵션으로 쾌적한 주행이 가능하며, 관리가 잘 된 컨디션 특A급 차량입니다.
                    전 차주 비흡연 및 세심한 관리로 실내외 매우 깔끔하며 타이어 상태도 양호합니다.
                    경정비 또한 완벽하게 완료하였습니다.
                    시승 꼭 추천드립니다. 감사합니다~
            
                    ▷기타 안내
                    - 성능기록부 교부 및 사고이력 확인해드립니다.
                    - 3개월 / 5,000km 보증해드립니다.
                    - 정비소 확인 가능합니다.
            
                    ▷문의안내
                    연락주시면 친절하고 상세하게 설명을 드리도록 하겠습니다. 전화가 부재중일시 문자를 남겨 주시면 확인 후 연락드리겠습니다.
                    가격도 절충 가능합니다. 현재 타고 계신 차량과 대차도 가능하며, 전액 할부도 가능합니다.
            
                    ▷오시는길
                    - 창원시 마산회원구 무역로 79(양덕동) KC월드카프라자 354호 더케어모터스
                """.trimIndent())
        addProperty("엔진형식", "1.3I3터보가솔린직접분사")
        addProperty("연비", "12.9km/ℓ")
        addProperty("최고출력", "156마력")
        addProperty("최대토크", "24.1kg.m")
        addProperty("차량중량", "1,365kg")
        addProperty("선루프", "무")
        addProperty("파노라마선루프", "무")
        addProperty("열선시트(앞좌석)", "유")
        addProperty("열선시트(뒷좌석)", "유")
        addProperty("동승석에어백", "유")
        addProperty("후측방경보", "유")
        addProperty("후방센서", "유")
        addProperty("전방센서", "무")
        addProperty("후방카메라", "유")
        addProperty("전방카메라", "무")
        addProperty("어라운드뷰", "무")
        addProperty("열선핸들", "유")
        addProperty("오토라이트", "유")
        addProperty("크루즈컨트롤", "무")
        addProperty("자동주차", "유")
        addProperty("네비게이션(순정)", "무")
        addProperty("네비게이션(비순정)", "유")
        addProperty("보험처리수", 2)
        addProperty("소유자변경", "1회")
        addProperty("전손", 0)
        addProperty("침수전손", 0)
        addProperty("침수분손", 0)
        addProperty("도난", 0)
        addProperty("내차피해_횟수", "2회")
        addProperty("내차피해_금액", "2,169,329원")
        addProperty("타차가해_횟수", "2회")
        addProperty("타차가해_금액", "2,169,329원")
        addProperty("판금", 0)
        addProperty("교환", 0)
        addProperty("부식", 0)
        addProperty("사고침수유무", "무")
        addProperty("불법구조변경", "무")
        addProperty("브랜드", "쉐보레/대우")
    }

    runBlocking {
        val tagExtractor = TagExtractor()
        val tags = tagExtractor.extractTagsFromData(sampleData)
        // [무사고, 1인사용, 튜닝, 전문정비]
        println("[TagExtractor] tags = ${tags}")
    }
}