package com.virtualdap.fixture.music;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** No Internet or storage permission: validates container state and PCM without remote requests. */
public final class MusicFixtureActivity extends Activity {
    static { System.loadLibrary("music_fixture_audio"); }

    private static native String playAaudioNative();
    private static native String playAaudioBlockingNative();
    private static native String playOpenSlNative();
    private static native String rejectUnsupportedNative();

    private volatile boolean playing;
    // Main-thread state: socket closure can precede the worker's final resource release.
    private boolean playbackBusy;
    private final java.util.List<Button> playbackButtons = new java.util.ArrayList<>();
    private volatile AudioTrack activeTrack;
    private volatile boolean stopFirstTrack;
    private TextView status;
    private TextView newIntentStatus;
    private android.media.session.MediaSession controllerSession;
    private android.net.ConnectivityManager.NetworkCallback networkCallback;
    private android.content.BroadcastReceiver privateReceiver;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(40, 60, 40, 40);
        TextView identity = new TextView(this);
        int launches = getPreferences(MODE_PRIVATE).getInt("launches", 0) + 1;
        getPreferences(MODE_PRIVATE).edit().putInt("launches", launches).apply();
        identity.setText("MUSIC FIXTURE READY\nPackage: " + getPackageName() + "\nLaunches: " + launches);
        identity.setTextSize(20);
        content.addView(identity);
        newIntentStatus = new TextView(this);
        newIntentStatus.setText("NEW INTENT: pending");
        content.addView(newIntentStatus);
        TextView mediaRoutes = new TextView(this);
        try {
            android.media.MediaRouter2 router = android.media.MediaRouter2.getInstance(this);
            mediaRoutes.setText("MEDIA ROUTER READY: " + router.getRoutes().size());
        } catch (RuntimeException failure) {
            mediaRoutes.setText("MEDIA ROUTER ERROR: " + failure);
        }
        content.addView(mediaRoutes);
        TextView controllerStatus = new TextView(this);
        controllerStatus.setText("MEDIA CONTROLLER: pending");
        content.addView(controllerStatus);
        TextView networkStatus = new TextView(this);
        networkStatus.setText("NETWORK STATE: pending");
        content.addView(networkStatus);
        try {
            android.net.ConnectivityManager networks = getSystemService(android.net.ConnectivityManager.class);
            android.os.Parcel networkParcel = android.os.Parcel.obtain();
            try {
                networkParcel.writeInt(Integer.MAX_VALUE);
                networkParcel.setDataPosition(0);
                android.net.Network absent = android.net.Network.CREATOR.createFromParcel(networkParcel);
                if (networks.getNetworkCapabilities(absent) != null || networks.getLinkProperties(absent) != null) {
                    throw new IllegalStateException("An absent network must not have invented capabilities or DNS");
                }
            } finally { networkParcel.recycle(); }
            boolean online = networks.getActiveNetwork() != null;
            android.net.ConnectivityManager.NetworkCallback callback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network network) {
                    networkStatus.setText("NETWORK STATE READY: real default callback");
                }
            };
            networks.registerDefaultNetworkCallback(callback, new android.os.Handler(getMainLooper()));
            networkCallback = callback;
            if (!online) networkStatus.setText("NETWORK STATE READY: offline");
        } catch (RuntimeException failure) {
            networkStatus.setText("NETWORK STATE ERROR: " + failure);
        }
        TextView broadcastStatus = new TextView(this);
        broadcastStatus.setText("PRIVATE BROADCAST: pending");
        content.addView(broadcastStatus);
        try {
            String nonce = java.util.UUID.randomUUID().toString();
            String action = "com.virtualdap.fixture.PRIVATE_BROADCAST." + nonce;
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
                    if (!action.equals(intent.getAction()) || !nonce.equals(intent.getStringExtra("nonce"))
                            || !getPackageName().equals(intent.getPackage())) {
                        broadcastStatus.setText("PRIVATE BROADCAST ERROR: payload or destination changed");
                    } else {
                        broadcastStatus.setText("PRIVATE BROADCAST READY: music space only");
                    }
                }
            };
            registerReceiver(receiver, new android.content.IntentFilter(action), RECEIVER_NOT_EXPORTED);
            privateReceiver = receiver;
            android.os.Parcel userParcel = android.os.Parcel.obtain();
            try {
                userParcel.writeInt(-1); // USER_ALL: only this container's private shadow may narrow it.
                userParcel.setDataPosition(0);
                android.os.UserHandle all = android.os.UserHandle.CREATOR.createFromParcel(userParcel);
                sendBroadcastAsUser(new android.content.Intent(action).setPackage(getPackageName())
                        .putExtra("nonce", nonce), all);
            } finally { userParcel.recycle(); }
        } catch (RuntimeException failure) {
            broadcastStatus.setText("PRIVATE BROADCAST ERROR: " + failure);
        }
        try {
            controllerSession = new android.media.session.MediaSession(this, "fixture-controller-attribution");
            String nonce = java.util.UUID.randomUUID().toString();
            java.util.Set<String> deliveries = new java.util.HashSet<>();
            controllerSession.setCallback(new android.media.session.MediaSession.Callback() {
                @Override public void onCustomAction(String action, Bundle extras) {
                    if (!getPackageName().equals(action) || extras == null || !nonce.equals(extras.getString("nonce"))
                            || !"com.virtualdap.host".equals(controllerSession.getCurrentControllerInfo().getPackageName())) {
                        controllerStatus.setText("MEDIA CONTROLLER ERROR: caller or command payload changed");
                        return;
                    }
                    deliveries.add(extras.getString("path"));
                    if (deliveries.contains("local") && deliveries.contains("parcel")) {
                        controllerStatus.setText("MEDIA CONTROLLER READY: local / parcel");
                    }
                }
            }, new android.os.Handler(getMainLooper()));
            controllerSession.setActive(true);
            Bundle command = new Bundle();
            command.putString("nonce", nonce);
            command.putString("path", "local");
            // Use the guest package as payload too: only the caller field may be rewritten.
            controllerSession.getController().getTransportControls().sendCustomAction(getPackageName(), command);
            android.os.Parcel parcel = android.os.Parcel.obtain();
            try {
                android.media.session.MediaSession.Token original = controllerSession.getSessionToken();
                parcel.writeParcelable(original, 0);
                parcel.setDataPosition(0);
                android.media.session.MediaSession.Token restored = parcel.readParcelable(
                        android.media.session.MediaSession.Token.class.getClassLoader(), android.media.session.MediaSession.Token.class);
                if (!original.equals(restored) || original.hashCode() != restored.hashCode()) {
                    throw new IllegalStateException("Media token identity changed during parceling");
                }
                command.putString("path", "parcel");
                new android.media.session.MediaController(this, restored).getTransportControls()
                        .sendCustomAction(getPackageName(), command);
            } finally { parcel.recycle(); }
        } catch (RuntimeException failure) {
            controllerStatus.setText("MEDIA CONTROLLER ERROR: " + failure);
        }
        TextView serviceQuery = new TextView(this);
        try {
            android.content.Intent query = new android.content.Intent("android.media.browse.MediaBrowserService")
                .setPackage(getPackageName());
            java.util.List<android.content.pm.ResolveInfo> services = getPackageManager().queryIntentServices(
                query, android.content.pm.PackageManager.ResolveInfoFlags.of(0));
            if (services.size() != 1 || !services.get(0).serviceInfo.name.equals(FixturePlaybackService.class.getName())) {
                throw new IllegalStateException("Missing declared media service: " + services);
            }
            query.setAction("com.virtualdap.fixture.MISSING_SERVICE");
            if (!getPackageManager().queryIntentServices(query, 0).isEmpty()) {
                throw new IllegalStateException("Undeclared service action returned a fabricated match");
            }
            int authenticatorMatches = 0;
            if (checkSelfPermission(android.Manifest.permission.ACCOUNT_MANAGER)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw new IllegalStateException("A guest must not receive Android's ACCOUNT_MANAGER permission");
            }
            for (android.accounts.AuthenticatorDescription authenticator :
                    android.accounts.AccountManager.get(this).getAuthenticatorTypes()) {
                if ("com.virtualdap.fixture.discovery".equals(authenticator.type)) {
                    if (!getPackageName().equals(authenticator.packageName)) {
                        throw new IllegalStateException("Authenticator returned a different package");
                    }
                    authenticatorMatches++;
                }
            }
            if (authenticatorMatches != 1) {
                throw new IllegalStateException("Declared authenticator must be discoverable without an account: "
                    + authenticatorMatches);
            }
            serviceQuery.setText("MEDIA SERVICE QUERY READY / AUTHENTICATOR DISCOVERY READY");
            final long authStart = android.os.SystemClock.elapsedRealtime();
            android.accounts.AccountManager.get(this).editProperties(
                "com.virtualdap.fixture.discovery", null, future -> {
                    try {
                        future.getResult();
                        serviceQuery.setText("MEDIA SERVICE QUERY ERROR: privileged authenticator unexpectedly accepted");
                    } catch (android.accounts.AuthenticatorException expected) {
                        long elapsed = android.os.SystemClock.elapsedRealtime() - authStart;
                        serviceQuery.setText("timeout".equals(expected.getMessage()) && elapsed >= 25_000 && elapsed < 60_000
                            ? "MEDIA SERVICE QUERY READY / AUTHENTICATOR TIMEOUT REPORTED"
                            : "MEDIA SERVICE QUERY ERROR: unexpected authenticator deadline " + elapsed);
                    } catch (Exception failure) {
                        serviceQuery.setText("MEDIA SERVICE QUERY ERROR: " + failure);
                    }
                }, new android.os.Handler(getMainLooper()));
        } catch (RuntimeException failure) {
            serviceQuery.setText("MEDIA SERVICE QUERY ERROR: " + failure);
        }
        content.addView(serviceQuery);
        TextView feature = new TextView(this);
        try {
            feature.setText((String) Class.forName("com.virtualdap.fixture.feature.FeatureMarker")
                .getMethod("value").invoke(null));
        } catch (ReflectiveOperationException absent) {
            feature.setText("BASE APK ONLY");
        }
        content.addView(feature);
        Button pcm16 = new Button(this);
        pcm16.setText("Play 48 kHz / 16-bit");
        pcm16.setOnClickListener(view -> play(48000, false));
        content.addView(pcm16);
        Button pcmFloat = new Button(this);
        pcmFloat.setText("Play 96 kHz / float");
        pcmFloat.setOnClickListener(view -> play(96000, true));
        content.addView(pcmFloat);
        Button pcmStatic = new Button(this);
        pcmStatic.setText("Play 44.1 kHz / static loop");
        pcmStatic.setOnClickListener(view -> playStatic());
        content.addView(pcmStatic);
        Button aaudio = new Button(this);
        aaudio.setText("Play 88.2 kHz / AAudio callback");
        aaudio.setOnClickListener(view -> playAaudio());
        content.addView(aaudio);
        Button aaudioBlocking = new Button(this);
        aaudioBlocking.setText("Play 96 kHz / AAudio write");
        aaudioBlocking.setOnClickListener(view -> playAaudioBlocking());
        content.addView(aaudioBlocking);
        Button openSl = new Button(this);
        openSl.setText("Play 48 kHz / OpenSL ES");
        openSl.setOnClickListener(view -> playOpenSl());
        content.addView(openSl);
        Button overlap = new Button(this);
        overlap.setText("Overlap two tracks");
        overlap.setOnClickListener(view -> playOverlap());
        content.addView(overlap);
        Button stopFirst = new Button(this);
        stopFirst.setText("Stop first track");
        stopFirst.setOnClickListener(view -> stopFirstTrack = true);
        content.addView(stopFirst);
        Button pause = new Button(this);
        pause.setText("Pause");
        pause.setOnClickListener(view -> {
            AudioTrack current = activeTrack;
            if (current != null) current.pause();
        });
        content.addView(pause);
        Button resume = new Button(this);
        resume.setText("Resume");
        resume.setOnClickListener(view -> {
            AudioTrack current = activeTrack;
            if (current != null) current.play();
        });
        content.addView(resume);
        Button mute = new Button(this);
        mute.setText("Mute");
        mute.setOnClickListener(view -> { if (activeTrack != null) activeTrack.setVolume(0f); });
        content.addView(mute);
        Button unity = new Button(this);
        unity.setText("Unity gain");
        unity.setOnClickListener(view -> { if (activeTrack != null) activeTrack.setVolume(1f); });
        content.addView(unity);
        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(view -> {
            playing = false;
            AudioTrack current = activeTrack;
            if (current != null) current.stop();
        });
        content.addView(stop);
        Button unsupported = new Button(this);
        unsupported.setText("Check unsupported output rejection");
        unsupported.setOnClickListener(view -> checkUnsupportedOutput());
        content.addView(unsupported);
        playbackButtons.addAll(java.util.Arrays.asList(
            pcm16, pcmFloat, pcmStatic, aaudio, aaudioBlocking, openSl, overlap, unsupported));
        status = new TextView(this);
        status.setText("Idle");
        content.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if ("com.virtualdap.fixture.NEW_INTENT".equals(intent.getAction())) {
            newIntentStatus.setText("NEW INTENT: " + intent.getStringExtra("request"));
        }
    }

    private void checkUnsupportedOutput() {
        if (playbackBusy) return;
        try {
            AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_8BIT).build())
                .setBufferSizeInBytes(9600).setTransferMode(AudioTrack.MODE_STREAM).build();
            try {
                // Two silent PCM8 samples; never start the track if this write is accepted.
                boolean writeRejected = false;
                try { track.write(new byte[] {(byte)128, (byte)128}, 0, 2); }
                catch (UnsupportedOperationException expected) { writeRejected = true; }
                if (!writeRejected) throw new IllegalStateException("Unsupported AudioTrack write was accepted");
                try {
                    track.play();
                    throw new IllegalStateException("Unsupported AudioTrack start was accepted");
                } catch (UnsupportedOperationException expected) { }
            } finally { track.release(); }
            String nativeResult = rejectUnsupportedNative();
            if (!"Native unsupported output rejected".equals(nativeResult)) {
                throw new IllegalStateException(nativeResult);
            }
            status.setText("Unsupported output rejected: AudioTrack, AAudio, OpenSL ES");
            android.util.Log.i("VirtualDAP-fixture", status.getText().toString());
        } catch (Throwable error) {
            status.setText("Unsupported output test failed: " + error);
            android.util.Log.e("VirtualDAP-fixture", status.getText().toString(), error);
        }
    }

    private void startAudioWorker(Runnable playback, String name) {
        playbackBusy = true;
        for (Button button : playbackButtons) button.setEnabled(false);
        new Thread(() -> {
            try {
                playback.run();
            } finally {
                // Publish readiness only after the producer's finally block (including release).
                runOnUiThread(() -> {
                    playbackBusy = false;
                    if (!isDestroyed()) {
                        for (Button button : playbackButtons) button.setEnabled(true);
                    }
                });
            }
        }, name).start();
    }

    private void play(int sampleRate, boolean floating) {
        if (playbackBusy) return;
        playing = true;
        startAudioWorker(() -> {
            AudioTrack track = null;
            try {
                int encoding = floating ? AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT;
                int frameBytes = floating ? 8 : 4;
                int bufferBytes = Math.max(
                    AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding),
                    sampleRate / 10 * frameBytes);
                track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(encoding).build())
                    .setBufferSizeInBytes(bufferBytes).setTransferMode(AudioTrack.MODE_STREAM).build();
                track.setStartThresholdInFrames(sampleRate / 100);
                activeTrack = track;
                long frames = 0;
                int chunkFrames = sampleRate / 100;
                ByteBuffer samples = ByteBuffer.allocateDirect(chunkFrames * frameBytes).order(ByteOrder.LITTLE_ENDIAN);
                while (playing && frames < sampleRate * 30L) {
                    samples.clear();
                    for (int frame = 0; frame < chunkFrames; ++frame) {
                        float value = (float) (0.05 * Math.sin(2 * Math.PI * 440 * (frames + frame) / sampleRate));
                        for (int channel = 0; channel < 2; ++channel) {
                            if (floating) samples.putFloat(value);
                            else samples.putShort((short) (value * 32767));
                        }
                    }
                    samples.flip();
                    while (playing && samples.hasRemaining()) {
                        int written = track.write(samples, samples.remaining(), AudioTrack.WRITE_BLOCKING);
                        if (written <= 0) throw new IllegalStateException("AudioTrack write: " + written);
                    }
                    // Common decoder behavior: prebuffer a packet before calling play().
                    if (frames == 0 && playing) track.play();
                    frames += chunkFrames;
                    if (frames % sampleRate == 0) {
                        String update = "Submitted " + frames + " frames at " + sampleRate +
                            " Hz; output head " + Integer.toUnsignedLong(track.getPlaybackHeadPosition());
                        runOnUiThread(() -> status.setText(update));
                    }
                }
                runOnUiThread(() -> status.setText("Playback finished"));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("PLAYBACK ERROR: " + error));
            } finally {
                playing = false;
                activeTrack = null;
                if (track != null) track.release();
            }
        }, "VirtualDAP-fixture-PCM");
    }

    private static AudioTrack stream(int rate, int encoding, int frameBytes) {
        AudioTrack track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(encoding).build())
            .setBufferSizeInBytes(Math.max(rate / 10 * frameBytes,
                AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, encoding)))
            .setTransferMode(AudioTrack.MODE_STREAM).build();
        track.setStartThresholdInFrames(rate / 100);
        return track;
    }

    private void playStatic() {
        if (playbackBusy) return;
        playing = true;
        startAudioWorker(() -> {
            AudioTrack track = null;
            try {
                final int rate = 44100;
                final int bufferFrames = rate / 10;
                final int loopCount = 9;
                short[] samples = new short[bufferFrames * 2];
                for (int frame = 0; frame < bufferFrames; ++frame) {
                    short value = (short) (0.05 * 32767 * Math.sin(2 * Math.PI * 523.25 * frame / rate));
                    samples[frame * 2] = value;
                    samples[frame * 2 + 1] = value;
                }
                track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(samples.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC).build();
                activeTrack = track;
                int written = track.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);
                if (written != samples.length) throw new IllegalStateException("Static write: " + written);
                if (track.reloadStaticData() != AudioTrack.SUCCESS) {
                    throw new IllegalStateException("Static reload rejected");
                }
                if (track.setPlaybackHeadPosition(0) != AudioTrack.SUCCESS) {
                    throw new IllegalStateException("Static position rejected");
                }
                if (track.setLoopPoints(0, bufferFrames, loopCount) != AudioTrack.SUCCESS) {
                    throw new IllegalStateException("Static loop rejected");
                }
                track.play();
                final long expectedFrames = (long) bufferFrames * (loopCount + 1);
                final long deadline = SystemClock.elapsedRealtime() + 5000;
                while (playing && Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < expectedFrames &&
                       SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(10);
                }
                long observed = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
                if (playing && observed < expectedFrames) {
                    throw new IllegalStateException("Static playback head stopped at " + observed);
                }
                SystemClock.sleep(300); // Leave a deterministic observation window for instrumentation.
                if (playing) track.stop();
                runOnUiThread(() -> status.setText("Static loop finished at " + observed + " frames"));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("STATIC ERROR: " + error));
            } finally {
                playing = false;
                activeTrack = null;
                if (track != null) track.release();
            }
        }, "VirtualDAP-fixture-static");
    }

    private void playAaudio() {
        if (playbackBusy) return;
        playing = true;
        startAudioWorker(() -> {
            try {
                String result = playAaudioNative();
                runOnUiThread(() -> status.setText(result));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("AAUDIO ERROR: " + error));
            } finally {
                playing = false;
            }
        }, "VirtualDAP-fixture-AAudio");
    }

    private void playAaudioBlocking() {
        if (playbackBusy) return;
        playing = true;
        startAudioWorker(() -> {
            try {
                String result = playAaudioBlockingNative();
                runOnUiThread(() -> status.setText(result));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("AAUDIO WRITE ERROR: " + error));
            } finally {
                playing = false;
            }
        }, "VirtualDAP-fixture-AAudio-write");
    }

    private void playOpenSl() {
        if (playbackBusy) return;
        playing = true;
        startAudioWorker(() -> {
            try {
                String result = playOpenSlNative();
                runOnUiThread(() -> status.setText(result));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("OPENSL ES ERROR: " + error));
            } finally {
                playing = false;
            }
        }, "VirtualDAP-fixture-OpenSL");
    }

    private void playOverlap() {
        if (playbackBusy) return;
        playing = true;
        stopFirstTrack = false;
        startAudioWorker(() -> {
            AudioTrack first = null, second = null;
            try {
                first = stream(48000, AudioFormat.ENCODING_PCM_16BIT, 4);
                second = stream(96000, AudioFormat.ENCODING_PCM_FLOAT, 8);
                activeTrack = first;
                first.play();
                second.play();
                ByteBuffer a = ByteBuffer.allocateDirect(480 * 4);
                ByteBuffer b = ByteBuffer.allocateDirect(960 * 8);
                for (int packet = 0; playing && packet < 3000; packet++) {
                    if (stopFirstTrack && first != null) {
                        first.release();
                        first = null;
                        activeTrack = second;
                    }
                    if (first != null) {
                        a.clear();
                        while (playing && a.hasRemaining()) {
                            if (first.write(a, a.remaining(), AudioTrack.WRITE_BLOCKING) <= 0) {
                                throw new IllegalStateException("First overlapping stream rejected PCM");
                            }
                        }
                    }
                    b.clear();
                    while (playing && b.hasRemaining()) {
                        if (second.write(b, b.remaining(), AudioTrack.WRITE_BLOCKING) <= 0) {
                            throw new IllegalStateException("Second overlapping stream rejected PCM");
                        }
                    }
                }
                runOnUiThread(() -> status.setText("Overlap playback finished"));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("OVERLAP ERROR: " + error));
            } finally {
                playing = false;
                activeTrack = null;
                if (first != null) first.release();
                if (second != null) second.release();
            }
        }, "VirtualDAP-fixture-overlap");
    }

    @Override public void onDestroy() {
        playing = false;
        if (privateReceiver != null) {
            unregisterReceiver(privateReceiver);
            privateReceiver = null;
        }
        if (networkCallback != null) {
            getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
        if (controllerSession != null) controllerSession.release();
        AudioTrack current = activeTrack;
        if (current != null) current.stop();
        super.onDestroy();
    }
}
