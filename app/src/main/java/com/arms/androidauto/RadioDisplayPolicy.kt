package com.arms.androidauto

// 지상파 라디오의 표시 문자열 정책. 블루투스(AVRCP) 화면은 title/artist만 보여주므로
// "어느 채널의 누구 방송인지"가 title 한 줄에 담겨야 한다: "SBS 파워FM - 황제성의 황제파워".
internal object RadioDisplayPolicy {
    private val placeholders = setOf("정보 없음", "편성 정보를 제공하지 않습니다", "실시간 방송 중")

    // "SBS 파워FM (107.7 MHz)" → "SBS 파워FM". 주파수는 title에 반복할 필요가 없다.
    fun channelShortName(stationName: String): String = stationName.substringBefore(" (").trim()

    fun isMeaningfulProgram(programTitle: String?): Boolean =
        !programTitle.isNullOrBlank() && programTitle !in placeholders && !programTitle.startsWith("편성 정보")

    // 세션/알림/차량용 title. 프로그램을 모르면 채널명만.
    fun sessionTitle(stationName: String, programTitle: String?): String =
        if (isMeaningfulProgram(programTitle)) "${channelShortName(stationName)} - $programTitle" else stationName

    // 폰 미니플레이어 헤드라인(두 줄 중 윗줄, 아랫줄은 채널명). 프로그램을 모르면 기존 문구.
    fun miniPlayerTitle(programTitle: String?, fallback: String): String =
        if (isMeaningfulProgram(programTitle)) programTitle!! else fallback
}
