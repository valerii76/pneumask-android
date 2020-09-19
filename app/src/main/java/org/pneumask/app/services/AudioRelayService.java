package org.pneumask.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import org.pneumask.app.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioRelayService extends Service {

    public static final String AUDIO_DELAY = "audio_delay";
    public static final String AUDIO_AMP = "audio_amp";
    public static final String AUDIO_AMP_ENABLE = "audio_amp_enable";
    public static final String AUDIO_BUFFER_SIZE = "audio_buffer_size";
    public static final String AUDIO_OUTPUT_DEVICE = "audio_output_device";

    private static AudioRelayService mInstance;

    private static final String TAG = AudioRelayService.class.getCanonicalName();

    public static final String STREAM_KEY = "STREAM";

    private static final String NOTIFICATION_CHANNEL_ID = BuildConfig.class.getPackage().toString()
            + "." + TAG;
    private static final String NOTIFICATION_MESSAGE = "Mic amplification is active.";

    private static final int SAMPLING_RATE_IN_HZ = getMinSupportedSampleRate();

    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;

    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    /**
     * Size of the buffer where the audio data is stored by Android
     */
    private static final int BUFFER_SIZE_MIN = AudioRecord.getMinBufferSize(SAMPLING_RATE_IN_HZ,
            CHANNEL_CONFIG, AUDIO_FORMAT);
    private static int BUFFER_SIZE = BUFFER_SIZE_MIN;

    /**
     * Whether audio is currently being relayed.
     */
    private final AtomicBoolean mRelayingActive = new AtomicBoolean(false);

    private AudioRecord recorder = null;
    private AudioTrack audioTrack = null;
    private AudioTrack audioTrackDelay = null;

    private Thread recordingThread = null;

    private AudioManager audioManager;

    private int streamOutput;

    private float audioGain = 0.2f; // Gain
    private int audioDelayValue = 1; // audio delay between record and play in ms
    private boolean audioGainEnable = true;
    private static int audioOutputDevice = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;

    public static AudioRelayService getInstance() {
        return mInstance;
    }

    public static int currentOutputAudioDevice() { return audioOutputDevice; }

    @Override
    public void onCreate() {
        Log.d("AudioRelayService", "Sampling rate: " + SAMPLING_RATE_IN_HZ + " Hz");
        mInstance = this;
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent.hasExtra(AUDIO_DELAY)) {
            audioDelayValue = intent.getIntExtra(AUDIO_DELAY, 0);
            return Service.START_STICKY;
        } else if (intent.hasExtra(AUDIO_AMP)) {
            audioGain = intent.getFloatExtra(AUDIO_AMP, 1.0f);
            return Service.START_STICKY;
        } else if (intent.hasExtra(AUDIO_AMP_ENABLE)) {
            audioGainEnable = intent.getBooleanExtra(AUDIO_AMP_ENABLE, true);
            return Service.START_STICKY;
        } else if (intent.hasExtra(AUDIO_BUFFER_SIZE)) {
            if (recorder != null)
                stopRelaying();
            int v = intent.getIntExtra(AUDIO_BUFFER_SIZE, BUFFER_SIZE_MIN);
            if (v == 0)
                BUFFER_SIZE = BUFFER_SIZE_MIN;
            else
                BUFFER_SIZE = SAMPLING_RATE_IN_HZ * v;

            if (recorder == null)
                return Service.START_STICKY;
        } else if (intent.hasExtra(AUDIO_OUTPUT_DEVICE)) {
            audioOutputDevice = intent.getIntExtra(AUDIO_OUTPUT_DEVICE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            if (audioTrack != null && audioTrackDelay != null) {
                setPreferredOutputDevice(audioTrack);
                setPreferredOutputDevice(audioTrackDelay);
            }
            return Service.START_STICKY;
        }

        // STREAM_ALARM also works, but STREAM_VOICE_CALL reduces the echo
        streamOutput = intent.getIntExtra(STREAM_KEY, AudioManager.STREAM_VOICE_CALL);

        displayNotification();

        startRelaying();

        return Service.START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void startRelaying() {
        // Depending on the device one might has to change the AudioSource, e.g. to DEFAULT
        // or VOICE_COMMUNICATION

        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLING_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE);
        setPreferredInputDevice(recorder);
        improveRecorder(recorder.getAudioSessionId());
        recorder.startRecording();

        audioTrack = new AudioTrack(streamOutput,
                SAMPLING_RATE_IN_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT,
                BUFFER_SIZE,
                AudioTrack.MODE_STREAM);
        setPreferredOutputDevice(audioTrack);

        audioTrackDelay = new AudioTrack(streamOutput,
                SAMPLING_RATE_IN_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT,
                BUFFER_SIZE,
                AudioTrack.MODE_STREAM);
        setPreferredOutputDevice(audioTrackDelay);

        mRelayingActive.set(true);

        recordingThread = new Thread(new RecordingRunnable(), "Recording Thread");
        recordingThread.start();

    }

    private void improveRecorder(int audioSessionId) {
        // Turn on Android library filter for reducing background noise in recordings
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor ns = NoiseSuppressor.create(audioSessionId);
            ns.setEnabled(true);
        }

        // Android library filter for automatic volume control in recordings
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl agc = AutomaticGainControl.create(audioSessionId);
            agc.setEnabled(true);
        }

        // Android library filter for reducing echo in recordings
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler ac = AcousticEchoCanceler.create(audioSessionId);
            ac.setEnabled(true);
        }
    }

    public void stopRelaying() {
        if (null == recorder) {
            return;
        }
        mRelayingActive.set(false);
        recorder.stop();
        recorder.release();
        recorder = null;

        audioTrack.stop();
        audioTrack.release();
        audioTrack = null;
        audioTrackDelay.stop();
        audioTrackDelay.release();;
        audioTrackDelay = null;

        recordingThread = null;
    }

    @Override
    public void onDestroy() {
        stopRelaying();
    }

    public void shutDown() {
        stopRelaying();
        mInstance = null;
        stopSelf();
    }

    private void displayNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                    "AudioRelayService", NotificationManager.IMPORTANCE_NONE);
            chan.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            assert manager != null;
            manager.createNotificationChannel(chan);

            Notification.Builder notificationBuilder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
            Notification notification = notificationBuilder.setOngoing(true)
                    .setContentTitle(NOTIFICATION_MESSAGE)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .build();
            startForeground(2, notification);
        } else {
            Notification.Builder notification = new Notification.Builder(this)
                    .setContentTitle(NOTIFICATION_MESSAGE);
            startForeground(1, notification.build());
        }
    }

    private class RecordingRunnable implements Runnable {
        private AtomicBoolean audioDone = new AtomicBoolean (true);
        private AtomicBoolean audioDelayDone = new AtomicBoolean (true);

        @Override
        public void run() {

            final short audioData[] = new short[BUFFER_SIZE/2];
            final short speakerData[] = new short[BUFFER_SIZE/2];


            final ByteArrayOutputStream audioDataCollected = new ByteArrayOutputStream();

            audioTrack.play();
            audioTrackDelay.play();

            long startTime = System.currentTimeMillis();
            while (isRelayingActive()) {
                final int result = recorder.read(audioData, 0, BUFFER_SIZE/2);

                for (int i = 0; i < result; ++i) {
                    if (audioGainEnable) {
                        if (audioData[i] >= 0)
                            audioData[i] = (short) Math.min((short) (audioData[i] * audioGain), (short) Short.MAX_VALUE);
                        else
                            audioData[i] = (short) Math.max((short) (audioData[i] * audioGain), (short) Short.MIN_VALUE);
                    }
                    try {
                        byte a = (byte)(audioData[i] & 0xff);
                        byte b = (byte)((audioData[i] & 0xff00) >> 8);
                        audioDataCollected.write(a);
                        audioDataCollected.write(b);
                    } catch (Exception e) {
                        Log.e(TAG, "VVV: cant write to stream: " + e.toString());
                    }
                }

                if (result < 0) {
                    Log.w(TAG, "Reading of buffer failed.");
                } else {
                    if (audioDone.get()) {
                        for (int i = 0; i < result; ++i)
                            speakerData[i] = audioData[i];
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                audioDone.set(false);
                                audioTrack.write(speakerData, 0, result);
                                audioDone.set(true);
                            }
                        }).start();
                    }
                    if (audioDelayValue > 0 && audioDelayDone.get()) {
                        long currentTime = System.currentTimeMillis();
                        if ((currentTime - startTime) >= (audioDelayValue * 1000)) {
                            startTime = currentTime;

                            final byte delayBuffer[] = audioDataCollected.toByteArray();
                            audioDataCollected.reset();

                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    audioDelayDone.set(false);
                                    audioTrackDelay.write(delayBuffer, 0, delayBuffer.length);
                                    audioDelayDone.set(true);
                                }
                            }).start();
                        }
                    }
                }
            }
            audioTrack.stop();
            audioTrackDelay.stop();
        }
    }

    public boolean isRelayingActive() {
        return mRelayingActive.get();
    }

    private static int getMinSupportedSampleRate() {
        final int[] validSampleRates = new int[]{8000, 11025, 16000, 22050,
                32000, 37800, 44056, 44100, 47250, 48000, 50000, 50400, 88200,
                96000, 176400, 192000, 352800, 2822400, 5644800};
        /*
         * Selecting default audio input source for recording since
         * AudioFormat.CHANNEL_CONFIGURATION_DEFAULT is deprecated and selecting
         * default encoding format.
         */
        for (int validSampleRate : validSampleRates) {
            int result = AudioRecord.getMinBufferSize(validSampleRate,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT);
            if (result > 0) {
                // return the minimum supported audio sample rate
                return validSampleRate;
            }
        }
        // If none of the sample rates are supported return -1 and handle it in calling method
        return -1;
    }

    /**
     * Function to set the preferred input device to Bluetooth SCO.
     * Function has no effect on Android version below Android 6.0 Marshmallow
     *
     * @param recorder: AudioRecord instance
     */
    private void setPreferredInputDevice(AudioRecord recorder) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            AudioDeviceInfo[] inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo input : inputs) {
                if (input.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    recorder.setPreferredDevice(input);
                }
            }
        }
    }

    /**
     * Function to set the preferred input device to a connected AUX line (preferably) or
     * the built-in speakers if the AUX line is not connected.
     * Function has no effect on Android version below Android 6.0 Marshmallow
     *
     * @param audio: AudioTrack instance
     */
    private void setPreferredOutputDevice(AudioTrack audio) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            AudioDeviceInfo[] outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo output : outputs) {
                if (output.getType() == audioOutputDevice) {
                    boolean res = audio.setPreferredDevice(output);
                    if (!res)
                        Log.e(TAG, "Failed to set preferred output device: " + output.getType());
                    break;
                }
//                if (output.getType() == AudioDeviceInfo.TYPE_AUX_LINE ||
//                        output.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
//                        output.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
//                    audio.setPreferredDevice(output);
//                    break;
//                } else if (output.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
//                    audio.setPreferredDevice(output);
//                }
            }
        }
    }
}
