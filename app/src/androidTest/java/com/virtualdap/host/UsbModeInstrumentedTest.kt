package com.virtualdap.host

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualdap.host.audio.PcmEncoding
import com.virtualdap.host.audio.PcmFormat
import com.virtualdap.host.audio.RoutedAudioSink
import com.virtualdap.host.audio.usb.NativeUsbOutput
import com.virtualdap.host.audio.usb.UsbAudioStreamingProfile
import com.virtualdap.host.audio.usb.UsbHostController
import com.virtualdap.host.audio.usb.UsbSampleRateRange
import com.virtualdap.host.model.OutputMode
import com.virtualdap.host.model.PipelineSnapshot
import com.virtualdap.host.service.PipelineStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsbModeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun missingDirectDeviceNeverOpensAnAndroidOutput() {
        val context = instrumentation.targetContext
        UsbHostController.initialize(context)
        RoutedAudioSink(context).use { sink ->
            sink.selectRoute(-1_999_999)
            sink.setPlaying(true)
            sink.setVolume(0.5f, 0.5f) // Must not call the official unity-only sink before configure.
            assertThrows(IllegalStateException::class.java) { sink.configure(PcmFormat(48000, 2, PcmEncoding.PCM_16)) }
            assertThrows(IllegalArgumentException::class.java) { sink.write(ByteArray(4)) }
            assertFalse(sink.bitPerfectActive())
            assertNull(sink.routedOutput())
        }
    }

    @Test fun packagedUsbJniRejectsAnUngrantedDescriptor() {
        val profile = UsbAudioStreamingProfile(1, 0, 1, 1, 0, 1, null, 1, 192,
            1, 2, 2, 16, true, false, false, null, listOf(UsbSampleRateRange(48000, 48000)), true)
        // Executes the packaged JNI entry point, without a physical USB device or privileged fd.
        assertThrows(IllegalStateException::class.java) { NativeUsbOutput(-1, profile) }
    }

    @Test fun koreanMusicHomeKeepsDiagnosticsAndDsdInTools() {
        PipelineStore.update { PipelineSnapshot() }
        instrumentation.uiAutomation.executeShellCommand("am start -W -n com.virtualdap.host/.MainActivity").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        await("Korean music home") { visible("음악을 시작하세요") }
        assertEquals(OutputMode.USB, PipelineStore.state.value.outputMode)
        assertFalse(visible("로컬 DSD 파일"))
        click("도구")
        await("collapsed tool destinations") { visible("진단") && visible("출력 소리 테스트") && visible("로컬 DSD 파일") }
        click("진단")
        await("diagnostics dialog") { visible("USB 연결 여부와 Android 공식 경로 지원 여부는 서로 다릅니다.") }
        click("닫기")
        click("도구")
        click("로그인 환경")
        await("login dependency setup") { visible("Google 서비스 프레임워크") }
        assertFalse(visible("앱 열기"))
        click("닫기")
        click("도구")
        click("출력 소리 테스트")
        await("missing output has an actionable explanation inside the tool") {
            visible("오디오 출력에서 장치를 먼저 선택해 주세요.") && visible("아직 실행하지 않았습니다.")
        }
        assertFalse(requireNotNull(findText("소리 테스트 시작")).isEnabled)
        click("닫기")
        click("오디오 출력")
        await("USB default and advanced official choices") { visible("USB 오디오 · 기본") && visible("공식 비트퍼펙트 · 고급") }
        click("공식 비트퍼펙트 · 고급")
        await("explicit official mode") { PipelineStore.state.value.outputMode == OutputMode.OFFICIAL_BIT_PERFECT }
        assertNull(PipelineStore.state.value.selectedRouteId)
        click("USB 오디오 · 기본")
        await("return to USB mode") { PipelineStore.state.value.outputMode == OutputMode.USB }
        assertNull(PipelineStore.state.value.selectedRouteId)
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        await("return to music") { visible("음악을 시작하세요") }
    }

    private fun findText(text: String): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add)
        var examined = 0
        // Traverse virtual Compose nodes as UIAutomator does; provider text-search does not
        // necessarily implement findAccessibilityNodeInfosByText for those descendants.
        while (pending.isNotEmpty() && examined++ < 2000) {
            val node = pending.removeFirst()
            if (node.text?.toString() == text && node.isVisibleToUser) return node
            repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
        }
        return null
    }
    private fun visible(text: String): Boolean = findText(text) != null

    private fun click(text: String) = await("click $text") {
        var node = findText(text)
        while (node != null && !node.isClickable) node = node.parent
        node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun await(operation: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("Timed out: $operation")
    }
}
