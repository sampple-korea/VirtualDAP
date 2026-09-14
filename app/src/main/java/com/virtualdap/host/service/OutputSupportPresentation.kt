package com.virtualdap.host.service

import com.virtualdap.host.model.OutputRoute

/** USB enumeration is not an official mixer capability or a direct-USB qualification. */
internal object OutputSupportPresentation {
    fun officialStatus(route: OutputRoute): String = when {
        route.officialQueryFailure != null ->
            "공식 지원 조회 실패 (${route.officialQueryFailure}). 지원 여부를 확인하지 못했습니다."
        route.officialBitPerfectFormats.isNotEmpty() ->
            route.officialBitPerfectFormats.joinToString("\n") { it.shortLabel() }
        route.officialBitPerfectReported ->
            "Android가 비트퍼펙트를 제공하지만, 이 빌드에서 사용할 수 있는 포맷은 없습니다."
        route.isUsb ->
            "USB 오디오가 연결되었습니다. Android가 이 출력의 공식 비트퍼펙트 포맷을 제공하지 않았습니다. " +
                "기본 USB 모드의 지원 여부와는 별개입니다."
        else -> "Android가 이 출력의 공식 비트퍼펙트 포맷을 제공하지 않았습니다."
    }
}
