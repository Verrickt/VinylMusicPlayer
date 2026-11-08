package com.poupa.vinylmusicplayer.service;

import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;

import com.poupa.vinylmusicplayer.App;
import com.poupa.vinylmusicplayer.R;
import com.poupa.vinylmusicplayer.service.playback.Playback;
import com.poupa.vinylmusicplayer.util.OopsHandler;
import com.poupa.vinylmusicplayer.util.PreferenceUtil;
import com.poupa.vinylmusicplayer.util.SafeToast;

import java.io.File;

/**
 * Modern Playback implementation backed by AndroidX Media3 ExoPlayer.
 * Bound to the background playback thread's Looper for zero-lock, non-blocking execution.
 */
public class ExoPlayerPlayback implements Playback {
    public static final String TAG = ExoPlayerPlayback.class.getSimpleName();

    private final Context context;
    private final Looper playbackLooper;
    private final Handler playbackHandler;
    private final ExoPlayer exoPlayer;

    @Nullable
    private Playback.PlaybackCallbacks callbacks;

    @Nullable
    private DynamicsProcessing mDynamicsProcessing;

    private volatile boolean mIsInitialized = false;
    private volatile boolean isPlaying = false;
    private volatile int duration = -1;
    private volatile long lastPositionMs = 0;
    private volatile long lastPositionTimeMs = 0;
    private volatile int audioSessionId = 0;

    private float duckingFactor = 1.0f;
    private float replaygain = Float.NaN;

    public ExoPlayerPlayback(@NonNull final Context context) {
        this(context, Looper.getMainLooper());
    }

    public ExoPlayerPlayback(@NonNull final Context context, @NonNull final Looper playbackLooper) {
        this.context = context;
        this.playbackLooper = playbackLooper;
        this.playbackHandler = new Handler(playbackLooper);

        final DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        final AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build();

        final DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true);

        final DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(context, extractorsFactory);

        exoPlayer = new ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLooper(playbackLooper)
                .setAudioAttributes(audioAttributes, false)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                lastPositionMs = 0;
                lastPositionTimeMs = SystemClock.elapsedRealtime();
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    if (exoPlayer.getMediaItemCount() > 1 && exoPlayer.getCurrentMediaItemIndex() > 0) {
                        exoPlayer.removeMediaItem(0);
                    }
                    if (callbacks != null) {
                        callbacks.onTrackWentToNext();
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    long d = exoPlayer.getDuration();
                    duration = (d == C.TIME_UNSET) ? -1 : (int) d;
                    audioSessionId = exoPlayer.getAudioSessionId();
                } else if (playbackState == Player.STATE_ENDED) {
                    isPlaying = false;
                    if (callbacks != null) {
                        callbacks.onTrackEnded();
                    }
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlayingNow) {
                boolean stateChanged = (isPlaying != isPlayingNow);
                isPlaying = isPlayingNow;
                lastPositionMs = exoPlayer.getCurrentPosition();
                lastPositionTimeMs = SystemClock.elapsedRealtime();
                if (stateChanged && callbacks != null) {
                    callbacks.onPlayStateChanged();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "ExoPlayer error: " + error.getMessage(), error);
                isPlaying = false;
                mIsInitialized = false;
                if (context != null) {
                    SafeToast.show(context, context.getResources().getString(R.string.unplayable_file));
                }
                if (callbacks != null) {
                    callbacks.onTrackEnded();
                }
            }
        });
    }

    private void runOnPlaybackThread(@NonNull Runnable action) {
        if (Looper.myLooper() == playbackLooper) {
            action.run();
        } else {
            playbackHandler.post(action);
        }
    }

    private Uri getUriForPath(@NonNull String path) {
        if (path.startsWith("content://") || path.startsWith("http://") || path.startsWith("https://") || path.startsWith("file://")) {
            return Uri.parse(path);
        }
        return Uri.fromFile(new File(path));
    }

    @Override
    public boolean setDataSource(@NonNull final String path) {
        if (Looper.myLooper() == playbackLooper) {
            return setDataSourceInternal(path);
        } else {
            playbackHandler.post(() -> setDataSourceInternal(path));
            return true;
        }
    }

    private boolean setDataSourceInternal(@NonNull final String path) {
        mIsInitialized = false;
        isPlaying = false;
        try {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();

            final Uri uri = getUriForPath(path);
            final MediaItem mediaItem = MediaItem.fromUri(uri);
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            mIsInitialized = true;
            lastPositionMs = 0;
            lastPositionTimeMs = SystemClock.elapsedRealtime();
            audioSessionId = exoPlayer.getAudioSessionId();

            final Intent intent = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
            context.sendBroadcast(intent);

            setNextDataSource(null);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "setDataSource failed for path: " + path, e);
            return false;
        }
    }

    @Override
    public void setNextDataSource(@Nullable final String path) {
        runOnPlaybackThread(() -> {
            if (context == null || !mIsInitialized) {
                return;
            }
            try {
                if (path == null || !PreferenceUtil.getInstance().gaplessPlayback()) {
                    while (exoPlayer.getMediaItemCount() > 1) {
                        exoPlayer.removeMediaItem(1);
                    }
                } else {
                    while (exoPlayer.getMediaItemCount() > 1) {
                        exoPlayer.removeMediaItem(1);
                    }
                    final MediaItem nextItem = MediaItem.fromUri(getUriForPath(path));
                    exoPlayer.addMediaItem(nextItem);
                }
            } catch (Exception e) {
                Log.e(TAG, "setNextDataSource error", e);
            }
        });
    }

    @Override
    public void setCallbacks(@Nullable Playback.PlaybackCallbacks callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    public boolean isInitialized() {
        return mIsInitialized;
    }

    @Override
    public void start() {
        isPlaying = true;
        lastPositionTimeMs = SystemClock.elapsedRealtime();
        runOnPlaybackThread(() -> {
            try {
                exoPlayer.play();
            } catch (Exception e) {
                Log.e(TAG, "start error", e);
                isPlaying = false;
                if (callbacks != null) {
                    callbacks.onPlayStateChanged();
                }
            }
        });
    }

    @Override
    public void stop() {
        isPlaying = false;
        mIsInitialized = false;
        runOnPlaybackThread(() -> {
            try {
                exoPlayer.stop();
                exoPlayer.clearMediaItems();
            } catch (Exception e) {
                Log.e(TAG, "stop error", e);
            }
        });
    }

    @Override
    public void release() {
        isPlaying = false;
        mIsInitialized = false;
        runOnPlaybackThread(() -> {
            stop();
            if (mDynamicsProcessing != null) {
                mDynamicsProcessing.release();
                mDynamicsProcessing = null;
            }
            try {
                exoPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "release error", e);
            }
        });
    }

    @Override
    public void pause() {
        if (isPlaying) {
            long elapsed = SystemClock.elapsedRealtime() - lastPositionTimeMs;
            lastPositionMs = lastPositionMs + elapsed;
            lastPositionTimeMs = SystemClock.elapsedRealtime();
        }
        isPlaying = false;
        runOnPlaybackThread(() -> {
            try {
                exoPlayer.pause();
                lastPositionMs = exoPlayer.getCurrentPosition();
                lastPositionTimeMs = SystemClock.elapsedRealtime();
            } catch (Exception e) {
                Log.e(TAG, "pause error", e);
            }
        });
    }

    @Override
    public boolean isPlaying() {
        return mIsInitialized && isPlaying;
    }

    @Override
    public int duration() {
        if (!mIsInitialized) {
            return -1;
        }
        return duration;
    }

    @Override
    public int position() {
        if (!mIsInitialized) {
            return -1;
        }
        if (!isPlaying) {
            return (int) lastPositionMs;
        }
        long elapsed = SystemClock.elapsedRealtime() - lastPositionTimeMs;
        long current = lastPositionMs + elapsed;
        return duration > 0 ? (int) Math.min(duration, current) : (int) current;
    }

    @Override
    public void seek(final int whereto) {
        lastPositionMs = whereto;
        lastPositionTimeMs = SystemClock.elapsedRealtime();
        runOnPlaybackThread(() -> {
            try {
                exoPlayer.seekTo(whereto);
            } catch (Exception e) {
                Log.e(TAG, "seek error", e);
            }
        });
    }

    @Override
    public boolean setAudioSessionId(final int sessionId) {
        return false;
    }

    @Override
    public int getAudioSessionId() {
        return audioSessionId;
    }

    @Override
    public void setReplayGain(float replaygain) {
        runOnPlaybackThread(() -> {
            this.replaygain = replaygain;
            updateVolume();
        });
    }

    @Override
    public void setDuckingFactor(float duckingFactor) {
        runOnPlaybackThread(() -> {
            this.duckingFactor = duckingFactor;
            updateVolume();
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    private void applyReplayGainOnDynamicsProcessing() {
        if (Float.isNaN(replaygain)) {
            if (mDynamicsProcessing != null) {
                mDynamicsProcessing.release();
                mDynamicsProcessing = null;
            }
        } else {
            if (mDynamicsProcessing == null) {
                mDynamicsProcessing = new DynamicsProcessing(getAudioSessionId());
                mDynamicsProcessing.setEnabled(true);
            }
            mDynamicsProcessing.setInputGainAllChannelsTo(replaygain);
        }
    }

    private void updateVolume() {
        float volume = 1.0f;

        if (!Float.isNaN(replaygain)) {
            float rgResult = ((float) Math.pow(10.0, (replaygain / 20.0)));
            volume = Math.max(0.0F, Math.min(1.0F, rgResult));
        }

        if (App.DYNAMICS_PROCESSING_AVAILABLE) {
            try {
                applyReplayGainOnDynamicsProcessing();
                volume = 1.0f;
            } catch (final RuntimeException error) {
                OopsHandler.collectStackTrace(error);
            }
        }

        volume *= duckingFactor;

        try {
            exoPlayer.setVolume(volume);
        } catch (final Exception e) {
            OopsHandler.collectStackTrace(e);
        }
    }
}
