package com.virtualdap.host.model

enum class AppAudioPath { SYSTEM_PCM, SYSTEM_PCM_WITH_DRM_REQUIREMENTS, LOCAL_HI_RES_PCM }

data class SupportedMusicApp(
    val name: String,
    val packageName: String,
    val audioPath: AppAudioPath,
    val note: String,
)

/** Compatibility targets, not a list of services whose login/DRM/playback has been certified. */
object MusicAppCatalog {
    private fun target(name: String, packageName: String, path: AppAudioPath = AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS) =
        SupportedMusicApp(name, packageName, path, when (path) {
            AppAudioPath.LOCAL_HI_RES_PCM -> "Not yet verified. Select Android/AudioTrack output; the host owns USB output."
            AppAudioPath.SYSTEM_PCM -> "Not yet verified. Installation and PCM playback need an app-specific test."
            AppAudioPath.SYSTEM_PCM_WITH_DRM_REQUIREMENTS -> "Not yet verified. Login, regional availability and protected playback remain provider-controlled."
        })

    val popularApps = listOf(
        target("Apple Music", "com.apple.android.music"),
        target("Spotify", "com.spotify.music"),
        target("YouTube Music", "com.google.android.apps.youtube.music"),
        target("TIDAL", "com.aspiro.tidal"),
        target("Qobuz", "com.qobuz.music"),
        target("Amazon Music", "com.amazon.mp3"),
        target("Deezer", "deezer.android.app"),
        target("SoundCloud", "com.soundcloud.android"),
        target("Plexamp", "tv.plex.labs.plexamp", AppAudioPath.SYSTEM_PCM),
        target("Poweramp", "com.maxmpz.audioplayer", AppAudioPath.LOCAL_HI_RES_PCM),
        target("Neutron Player", "com.neutroncode.mp", AppAudioPath.LOCAL_HI_RES_PCM),
        target("foobar2000", "com.foobar2000.foobar2000", AppAudioPath.LOCAL_HI_RES_PCM),
        target("Bandcamp", "com.bandcamp.android"),
        target("Pandora", "com.pandora.android"),
        target("iHeartRadio", "com.clearchannel.iheartradio.controller"),
        target("TuneIn Radio", "tunein.player", AppAudioPath.SYSTEM_PCM),
        target("Melon", "com.iloen.melon"),
        target("Genie Music", "com.ktmusic.geniemusic"),
        target("Bugs", "com.neowiz.android.bugs"),
        target("FLO", "skplanet.musicmate"),
        target("NAVER VIBE", "com.naver.vibe"),
        target("KKBOX", "com.skysoft.kkbox.android"),
        target("Audiomack", "com.audiomack", AppAudioPath.SYSTEM_PCM),
        target("Anghami", "com.anghami"),
        target("JioSaavn", "com.jio.media.jiobeats"),
    )
}
