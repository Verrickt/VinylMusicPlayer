package com.poupa.vinylmusicplayer.service;

import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.net.Uri;
import android.os.Build;
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

import com.poupa.vinylmusicplayer.App;
import com.poupa.vinylmusicplayer.R;
import com.poupa.vinylmusicplayer.service.playback.Playback;
import com.poupa.vinylmusicplayer.util.OopsHandler;
import com.poupa.vinylmusicplayer.util.PreferenceUtil;
import com.poupa.vinylmusicplayer.util.SafeToast;

import java.io.File;

/**
 * Modern Playback implementation backed by AndroidX Media3 ExoPlayer.
 * Provides true gapless playback, broader audio format support, and consistent AudioSession handling.
 */
public class ExoPlayerPlayback implements Playback {
    public static final String TAG = ExoPlayerPlayback.class.getSimpleName();

    private final Context context;
    private final ExoPlayer exoPlayer;

    @Nullable
    private Playback.PlaybackCallbacks callbacks;

    @Nullable
    private DynamicsProcessing mDynamicsProcessing;

    private boolean mIsInitialized = false;
    private float duckingFactor = 1.0f;
    private float replaygain = Float.NaN;

    public ExoPlayerPlayback(@NonNull final Context context) {
        this.context = context;

        final DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        final AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build();

        exoPlayer = new ExoPlayer.Builder(context, renderersFactory)
                .setAudioAttributes(audioAttributes, false)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // Seamless gapless transition: remove completed previous item
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
                if (playbackState == Player.STATE_ENDED) {
                    if (callbacks != null) {
                        callbacks.onTrackEnded();
                    }
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "ExoPlayer error: " + error.getMessage(), error);
                if (context != null) {
                    SafeToast.show(context, context.getResources().getString(R.string.unplayable_file));
                }
                mIsInitialized = false;
                if (callbacks != null) {
                    callbacks.onTrackEnded();
                }
            }
        });
    }

    private Uri getUriForPath(@NonNull String path) {
        if (path.startsWith("content://") || path.startsWith("http://") || path.startsWith("https://") || path.startsWith("file://")) {
            return Uri.parse(path);
        }
        return Uri.fromFile(new File(path));
    }

    @Override
    public boolean setDataSource(@NonNull final String path) {
        mIsInitialized = false;
        try {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();

            final Uri uri = getUriForPath(path);
            final MediaItem mediaItem = MediaItem.fromUri(uri);
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            mIsInitialized = true;

            final Intent intent = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
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
        if (context == null || !mIsInitialized) {
            return;
        }
        try {
            if (path == null || !PreferenceUtil.getInstance().gaplessPlayback()) {
                // Keep only current item, discard queued next items
                while (exoPlayer.getMediaItemCount() > 1) {
                    exoPlayer.removeMediaItem(1);
                }
            } else {
                // Gapless playback enabled: ensure at most 1 queued next item
                while (exoPlayer.getMediaItemCount() > 1) {
                    exoPlayer.removeMediaItem(1);
                }
                final MediaItem nextItem = MediaItem.fromUri(getUriForPath(path));
                exoPlayer.addMediaItem(nextItem);
            }
        } catch (Exception e) {
            Log.e(TAG, "setNextDataSource error", e);
        }
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
        try {
            exoPlayer.play();
        } catch (Exception e) {
            Log.e(TAG, "start error", e);
        }
    }

    @Override
    public void stop() {
        try {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
            mIsInitialized = false;
        } catch (Exception e) {
            Log.e(TAG, "stop error", e);
        }
    }

    @Override
    public void release() {
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
    }

    @Override
    public void pause() {
        try {
            exoPlayer.pause();
        } catch (Exception e) {
            Log.e(TAG, "pause error", e);
        }
    }

    @Override
    public boolean isPlaying() {
        return mIsInitialized && exoPlayer.isPlaying();
    }

    @Override
    public int duration() {
        if (!mIsInitialized) {
            return -1;
        }
        final long durationMs = exoPlayer.getDuration();
        if (durationMs == C.TIME_UNSET) {
            return -1;
        }
        return (int) durationMs;
    }

    @Override
    public int position() {
        if (!mIsInitialized) {
            return -1;
        }
        final long posMs = exoPlayer.getCurrentPosition();
        return (int) posMs;
    }

    @Override
    public void seek(final int whereto) {
        try {
            exoPlayer.seekTo(whereto);
        } catch (Exception e) {
            Log.e(TAG, "seek error", e);
        }
    }

    @Override
    public boolean setAudioSessionId(final int sessionId) {
        // ExoPlayer allocates its own audio session ID in AudioSink
        return false;
    }

    @Override
    public int getAudioSessionId() {
        return exoPlayer.getAudioSessionId();
    }

    @Override
    public void setReplayGain(float replaygain) {
        this.replaygain = replaygain;
        updateVolume();
    }

    @Override
    public void setDuckingFactor(float duckingFactor) {
        this.duckingFactor = duckingFactor;
        updateVolume();
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
