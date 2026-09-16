package com.virtualdap.host.model

data class GoogleServiceDependency(val packageName: String, val name: String, val description: String)

/** User-selected, authentic APK dependencies; no Google binaries or account state are bundled. */
object GoogleServiceCatalog {
    const val STORE = "com.android.vending"
    val packages = listOf(
        GoogleServiceDependency("com.google.android.gsf", "Google 서비스 프레임워크", "Google 서비스의 기본 구성요소"),
        GoogleServiceDependency("com.google.android.gms", "Google Play 서비스", "계정 인증과 Google 앱 연동에 사용"),
        GoogleServiceDependency(STORE, "Google Play 스토어", "Google 앱의 서비스 환경 확인에 사용"),
        GoogleServiceDependency("com.google.android.gsf.login", "Google 계정 관리자", "별도로 설치된 기기에서만 사용하는 선택 구성요소"),
    )
    fun contains(packageName: String): Boolean = packages.any { it.packageName == packageName }
}
