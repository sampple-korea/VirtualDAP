package com.virtualdap.fixture.music;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** No network or storage permission: a deterministic app to validate container and PCM behavior. */
public final class MusicFixtureActivity extends Activity {
    private volatile boolean playing;
    private Thread audioThread;
    private TextView status;

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
        Button pcm16 = new Button(this);
        pcm16.setText("Play 48 kHz / 16-bit");
        pcm16.setOnClickListener(view -> play(48000, false));
        content.addView(pcm16);
        Button pcmFloat = new Button(this);
        pcmFloat.setText("Play 96 kHz / float");
        pcmFloat.setOnClickListener(view -> play(96000, true));
        content.addView(pcmFloat);
        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(view -> playing = false);
        content.addView(stop);
        status = new TextView(this);
        status.setText("Idle");
        content.addView(status);
        setContentView(content);
    }

    private void play(int sampleRate, boolean floating) {
        if (audioThread != null && audioThread.isAlive()) return;
        playing = true;
        audioThread = new Thread(() -> {
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
                track.play();
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
                    frames += chunkFrames;
                    if (frames % sampleRate == 0) {
                        String update = "Submitted " + frames + " frames at " + sampleRate + " Hz";
                        runOnUiThread(() -> status.setText(update));
                    }
                }
                runOnUiThread(() -> status.setText("Playback finished"));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("PLAYBACK ERROR: " + error));
            } finally {
                playing = false;
                if (track != null) track.release();
            }
        }, "VirtualDAP-fixture-PCM");
        audioThread.start();
    }

    @Override public void onDestroy() {
        playing = false;
        super.onDestroy();
    }
}
