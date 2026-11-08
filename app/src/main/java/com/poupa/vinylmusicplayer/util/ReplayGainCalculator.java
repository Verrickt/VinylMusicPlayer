package com.poupa.vinylmusicplayer.util;

import androidx.annotation.Nullable;

/**
 * Utility class for ReplayGain gain and volume calculations.
 */
public class ReplayGainCalculator {

    /**
     * Calculates the ReplayGain adjustment in decibels (dB).
     *
     * @param mode ReplayGain source mode ("none", "track", "album")
     * @param rgTrack Track gain in dB
     * @param rgAlbum Album gain in dB
     * @param rgpTrack Track peak
     * @param rgpAlbum Album peak
     * @param preampWithTag Preamp to add when a tag is present (in dB)
     * @param preampWithoutTag Preamp to use when no tag is present (in dB)
     * @return Calculated dB gain adjustment, or Float.NaN if mode is "none" or null
     */
    public static float calculateAdjustDb(
            @Nullable String mode,
            float rgTrack,
            float rgAlbum,
            float rgpTrack,
            float rgpAlbum,
            float preampWithTag,
            float preampWithoutTag) {
        if (mode == null || PreferenceUtil.RG_SOURCE_MODE_NONE.equals(mode)) {
            return Float.NaN;
        }

        float adjustDB = 0.0f;
        float peak = 1.0f;

        if (PreferenceUtil.RG_SOURCE_MODE_ALBUM.equals(mode)) {
            adjustDB = (rgTrack == 0.0f ? adjustDB : rgTrack);
            adjustDB = (rgAlbum == 0.0f ? adjustDB : rgAlbum);
            peak = (rgpTrack == 1.0f ? peak : rgpTrack);
            peak = (rgpAlbum == 1.0f ? peak : rgpAlbum);
        } else if (PreferenceUtil.RG_SOURCE_MODE_TRACK.equals(mode)) {
            adjustDB = (rgAlbum == 0.0f ? adjustDB : rgAlbum);
            adjustDB = (rgTrack == 0.0f ? adjustDB : rgTrack);
            peak = (rgpAlbum == 1.0f ? peak : rgpAlbum);
            peak = (rgpTrack == 1.0f ? peak : rgpTrack);
        }

        if (adjustDB == 0.0f) {
            adjustDB = preampWithoutTag;
        } else {
            adjustDB += preampWithTag;

            if (peak > 0.0f) {
                float peakDB = -20.0f * ((float) Math.log10(peak));
                adjustDB = Math.min(adjustDB, peakDB);
            }
        }

        return adjustDB;
    }

    /**
     * Converts a ReplayGain dB value into a linear volume multiplier in range [0.0, 1.0].
     *
     * @param replaygainDb Gain in dB, or Float.NaN for 100% (0 dB)
     * @param duckingFactor Ducking factor between 0.0 and 1.0
     * @return Linear volume clamped to [0.0, 1.0]
     */
    public static float calculateLinearVolume(float replaygainDb, float duckingFactor) {
        float volume = 1.0f;

        if (!Float.isNaN(replaygainDb)) {
            float rgResult = (float) Math.pow(10.0, replaygainDb / 20.0);
            volume = Math.max(0.0f, Math.min(1.0f, rgResult));
        }

        return volume * duckingFactor;
    }
}
