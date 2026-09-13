package com.virtualdap.host.model

enum class AppAudioPath { SYSTEM_PCM, SYSTEM_PCM_WITH_DRM_REQUIREMENTS, LOCAL_HI_RES_PCM }

data class SupportedMusicApp(
    val name: String,
    val packageName: String,
    val audioPath: AppAudioPath,
    val note: String,
)

/** Apps using Android's AudioTrack path are captured globally by the guest audio HAL. */
object MusicAppCatalog {
    val popularApps = listOf(
        SupportedMusicApp("Apple Music", "com.apple.android.music", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "Lossless availability depends on the service account and guest DRM certification."),
        SupportedMusicApp("Spotify", "com.spotify.music", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "Standard and high-quality streams use the guest system PCM path."),
        SupportedMusicApp("YouTube Music", "com.google.android.apps.youtube.music", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "Protected playback requires a guest image accepted by Google services."),
        SupportedMusicApp("TIDAL", "com.aspiro.tidal", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "FLAC playback is captured after application decoding."),
        SupportedMusicApp("Qobuz", "com.qobuz.music", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "Hi-res PCM is preserved when the app opens a matching direct output."),
        SupportedMusicApp("Amazon Music", "com.amazon.mp3", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "HD playback availability is controlled by Amazon and guest certification."),
        SupportedMusicApp("Deezer", "deezer.android.app", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "System PCM playback is supported."),
        SupportedMusicApp("SoundCloud", "com.soundcloud.android", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "System PCM playback is supported."),
        SupportedMusicApp("Plexamp", "tv.plex.labs.plexamp", AppAudioPath.SYSTEM_PCM, "System PCM playback is supported."),
        SupportedMusicApp("Poweramp", "com.maxmpz.audioplayer", AppAudioPath.LOCAL_HI_RES_PCM, "Direct high-resolution PCM is supported; proprietary bypass paths must be disabled."),
        SupportedMusicApp("Neutron Player", "com.neutroncode.mp", AppAudioPath.LOCAL_HI_RES_PCM, "Direct high-resolution PCM is supported; use the AudioTrack output driver."),
        SupportedMusicApp("USB Audio Player PRO", "com.extreamsd.usbaudioplayerpro", AppAudioPath.LOCAL_HI_RES_PCM, "Use Android/AudioTrack output inside the guest; direct USB access belongs to the host."),
        SupportedMusicApp("foobar2000", "com.foobar2000.foobar2000", AppAudioPath.LOCAL_HI_RES_PCM, "System PCM playback is supported."),
        SupportedMusicApp("Bandcamp", "com.bandcamp.android", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "System PCM playback is supported."),
        SupportedMusicApp("Pandora", "com.pandora.android", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "System PCM playback is supported where the service is available."),
        SupportedMusicApp("iHeartRadio", "com.clearchannel.iheartradio.controller", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "Live radio and music use the same guest PCM path."),
        SupportedMusicApp("TuneIn Radio", "tunein.player", AppAudioPath.SYSTEM_PCM, "Live and on-demand playback use the guest system PCM path."),
        SupportedMusicApp("Melon", "com.iloen.melon", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "System PCM playback is supported; account and device policy remain provider-controlled."),
        SupportedMusicApp("Genie Music", "com.ktmusic.geniemusic", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "System PCM playback is supported."),
        SupportedMusicApp("Bugs", "com.neowiz.android.bugs", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "System PCM playback is supported."),
        SupportedMusicApp("FLO", "skplanet.musicmate", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "System PCM playback is supported."),
        SupportedMusicApp("NAVER VIBE", "com.naver.vibe", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "System PCM playback is supported."),
        SupportedMusicApp("KKBOX", "com.skysoft.kkbox.android", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "System PCM playback is supported where the service is available."),
        SupportedMusicApp("Audiomack", "com.audiomack", AppAudioPath.SYSTEM_PCM, "System PCM playback is supported."),
        SupportedMusicApp("Anghami", "com.anghami", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "System PCM playback is supported where the service is available."),
        SupportedMusicApp("JioSaavn", "com.jio.media.jiobeats", AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS, "System PCM playback is supported where the service is available."),
    )
}
