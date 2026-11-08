package com.poupa.vinylmusicplayer.util;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for ReplayGain calculations covering representative samples extracted from the user's library.
 */
@RunWith(JUnit4.class)
public class ReplayGainCalculatorTest {

    private static final float DELTA = 0.001f;

    @Test
    public void testModeNone_returnsNaN() {
        float adjustDb = ReplayGainCalculator.calculateAdjustDb(
                PreferenceUtil.RG_SOURCE_MODE_NONE,
                -6.05f, -5.38f, 0.991f, 1.026f, 0.0f, 0.0f);
        assertThat(adjustDb).isNaN();

        float adjustDbNull = ReplayGainCalculator.calculateAdjustDb(
                null, -6.05f, -5.38f, 0.991f, 1.026f, 0.0f, 0.0f);
        assertThat(adjustDbNull).isNaN();
    }

    /**
     * 样本 1：经典 MP3 / TXXX 负增益 (Autumn's Fall)
     * Track: -6.05 dB, Album: -5.38 dB, TrackPeak: 0.99136, AlbumPeak: 1.026718
     */
    @Test
    public void testSample1_mp3StandardNegativeGain() {
        float rgTrack = -6.05f;
        float rgAlbum = -5.38f;
        float rgpTrack = 0.99136f;
        float rgpAlbum = 1.026718f;

        // 1. Track 模式：选择 TrackGain (-6.05 dB)
        float trackModeDb = ReplayGainCalculator.calculateAdjustDb(
                PreferenceUtil.RG_SOURCE_MODE_TRACK,
                rgTrack, rgAlbum, rgpTrack, rgpAlbum, 0.0f, 0.0f);
        assertThat(trackModeDb).isWithin(DELTA).of(-6.05f);

        // 2. Album 模式：选择 AlbumGain (-5.38 dB)
        float albumModeDb = ReplayGainCalculator.calculateAdjustDb(
                PreferenceUtil.RG_SOURCE_MODE_ALBUM,
                rgTrack, rgAlbum, rgpTrack, rgpAlbum, 0.0f, 0.0f);
        assertThat(albumModeDb).isWithin(DELTA).of(-5.38f);

        // 3. 附加有标签 Preamp (+2.0 dB)
        float trackWithPreamp = ReplayGainCalculator.calculateAdjustDb(
                PreferenceUtil.RG_SOURCE_MODE_TRACK,
                rgTrack, rgAlbum, rgpTrack, rgpAlbum, 2.0f, 0.0f);
        assertThat(trackWithPreamp).isWithin(DELTA).of(-4.05f);
    }

    /**
     * 样本 2：经典 FLAC / Vorbis 负增益且 Peak > 1.0 (only my railgun)
     * Track: -11.98 dB, Album: -12.56 dB, TrackPeak: 1.070143, AlbumPeak: 1.07647
     */
    @Test
    public void testSample2_flacHighPeak() {
        float rgTrack = -11.98f;
        float rgAlbum = -12.56f;
        float rgpTrack = 1.070143f;
        float rgpAlbum = 1.07647f;

        // Peak > 1.0 时 peakDB = -20 * log10(1.070143) = -0.5888 dB
        // adjustDB (-11.98 dB) 本身远小于 -0.5888 dB，所以应保留 -11.98 dB
        float trackModeDb = ReplayGainCalculator.calculateAdjustDb(
                PreferenceUtil.RG_SOURCE_MODE_TRACK,
                rgTrack, rgAlbum, rgpTrack, rgpAlbum, 0.0f, 0.0f);
        assertThat(trackModeDb).isWithin(DELTA).of(-11.98f);

        float albumModeDb = ReplayGainCalculator.calculateAdjustDb(
                PreferenceUtil.RG_SOURCE_MODE_ALBUM,
                rgTrack, rgAlbum, rgpTrack, rgpAlbum, 0.0f, 0.0f);
        assertThat(albumModeDb).isWithin(DELTA).of(-12.56f);
    }

    /**
     * 样本 3：正增益样本 (カンザキイオリ)
     * Track: +1.92 dB, Peak: 0.453084
     */
    @Test
    public void testSample3_positiveGain_underPeakLimit() {
        float rgTrack = 1.92f;
        float rgAlbum = 1.92f;
        float rgpTrack = 0.453084f;
        float rgpAlbum = 0.453084f;

        // peakDB = -20 * log10(0.453084) ≈ +6.876 dB
        // adjustDB (+1.92 dB) < 6.876 dB，不触发削波限制，保持 +1.92 dB
        float adjustDb = ReplayGainCalculator.calculateAdjustDb(
                PreferenceUtil.RG_SOURCE_MODE_TRACK,
                rgTrack, rgAlbum, rgpTrack, rgpAlbum, 0.0f, 0.0f);
        assertThat(adjustDb).isWithin(DELTA).of(1.92f);
    }

    /**
     * 测试边界：正增益过大超过 Peak 限制时，必须被 Math.min 削平防止破音
     */
    @Test
    public void testSample3_positiveGain_exceedingPeakLimit_isClipped() {
        float rgTrack = 8.0f; // 尝试提升 8 dB
        float rgAlbum = 8.0f;
        float rgpTrack = 0.5f; // peak = 0.5 -> peakDB = -20 * log10(0.5) = +6.0206 dB
        float rgpAlbum = 0.5f;

        float adjustDb = ReplayGainCalculator.calculateAdjustDb(
                PreferenceUtil.RG_SOURCE_MODE_TRACK,
                rgTrack, rgAlbum, rgpTrack, rgpAlbum, 0.0f, 0.0f);
        // 应被限制在 +6.0206 dB
        assertThat(adjustDb).isWithin(DELTA).of(6.0206f);
    }

    /**
     * 样本 4：单曲模式回退 (I Really Want to Stay At Your House)
     * 仅有 Track: -10.92 dB，无 AlbumGain (0.0f)
     */
    @Test
    public void testSample4_singleTrack_fallbackInAlbumMode() {
        float rgTrack = -10.92f;
        float rgAlbum = 0.0f; // 无专辑增益
        float rgpTrack = 1.027267f;
        float rgpAlbum = 1.0f;

        // 即使处于 Album 模式，缺少专辑增益时也应优雅回退使用单曲增益
        float albumModeDb = ReplayGainCalculator.calculateAdjustDb(
                PreferenceUtil.RG_SOURCE_MODE_ALBUM,
                rgTrack, rgAlbum, rgpTrack, rgpAlbum, 0.0f, 0.0f);
        assertThat(albumModeDb).isWithin(DELTA).of(-10.92f);

        // Track 模式正常使用单曲增益
        float trackModeDb = ReplayGainCalculator.calculateAdjustDb(
                PreferenceUtil.RG_SOURCE_MODE_TRACK,
                rgTrack, rgAlbum, rgpTrack, rgpAlbum, 0.0f, 0.0f);
        assertThat(trackModeDb).isWithin(DELTA).of(-10.92f);
    }

    /**
     * 样本 5：单曲缺失 Track 但有 AlbumGain 时的回退
     */
    @Test
    public void testSample5_albumGainOnly_fallbackInTrackMode() {
        float rgTrack = 0.0f;
        float rgAlbum = -4.50f;
        float rgpTrack = 1.0f;
        float rgpAlbum = 0.95f;

        float trackModeDb = ReplayGainCalculator.calculateAdjustDb(
                PreferenceUtil.RG_SOURCE_MODE_TRACK,
                rgTrack, rgAlbum, rgpTrack, rgpAlbum, 0.0f, 0.0f);
        assertThat(trackModeDb).isWithin(DELTA).of(-4.50f);
    }

    /**
     * 样本 7：完全无 ReplayGain 标签
     * 应使用 preampWithoutTag 预设
     */
    @Test
    public void testSample7_noTags_usesPreampWithoutTag() {
        float rgTrack = 0.0f;
        float rgAlbum = 0.0f;
        float rgpTrack = 1.0f;
        float rgpAlbum = 1.0f;
        float preampWithoutTag = -3.0f;

        float adjustDb = ReplayGainCalculator.calculateAdjustDb(
                PreferenceUtil.RG_SOURCE_MODE_TRACK,
                rgTrack, rgAlbum, rgpTrack, rgpAlbum, 0.0f, preampWithoutTag);
        assertThat(adjustDb).isWithin(DELTA).of(-3.0f);
    }

    /**
     * 核心防回退测试：验证使用 new String() 实例传入时，不会因 == 比较失败而失效
     */
    @Test
    public void testStringComparison_newStringInstance_doesNotFail() {
        String dynamicTrackMode = new String("track");
        String dynamicAlbumMode = new String("album");

        float trackDb = ReplayGainCalculator.calculateAdjustDb(
                dynamicTrackMode, -5.0f, -4.0f, 1.0f, 1.0f, 0.0f, 0.0f);
        assertThat(trackDb).isWithin(DELTA).of(-5.0f);

        float albumDb = ReplayGainCalculator.calculateAdjustDb(
                dynamicAlbumMode, -5.0f, -4.0f, 1.0f, 1.0f, 0.0f, 0.0f);
        assertThat(albumDb).isWithin(DELTA).of(-4.0f);
    }

    /**
     * 线性音量换算测试（MultiPlayer updateVolume 逻辑）
     */
    @Test
    public void testLinearVolumeConversion() {
        // 0 dB -> 1.0f
        assertThat(ReplayGainCalculator.calculateLinearVolume(0.0f, 1.0f)).isWithin(DELTA).of(1.0f);

        // -6.0206 dB -> ~0.5f (半音量)
        assertThat(ReplayGainCalculator.calculateLinearVolume(-6.0206f, 1.0f)).isWithin(DELTA).of(0.5f);

        // -20 dB -> 0.1f
        assertThat(ReplayGainCalculator.calculateLinearVolume(-20.0f, 1.0f)).isWithin(DELTA).of(0.1f);

        // 正增益被上限截断至 1.0f
        assertThat(ReplayGainCalculator.calculateLinearVolume(6.0f, 1.0f)).isWithin(DELTA).of(1.0f);

        // NaN -> 默认 1.0f
        assertThat(ReplayGainCalculator.calculateLinearVolume(Float.NaN, 1.0f)).isWithin(DELTA).of(1.0f);

        // 包含 Ducking 因子 0.2f
        assertThat(ReplayGainCalculator.calculateLinearVolume(-6.0206f, 0.2f)).isWithin(DELTA).of(0.1f);
    }
}
