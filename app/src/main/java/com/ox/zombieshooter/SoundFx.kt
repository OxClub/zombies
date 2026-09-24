package com.ox.zombieshooter

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.math.max
import kotlin.math.min

enum class Sfx(val res: Int) {
    SHOT(R.raw.shot),
    HIT(R.raw.hit),
    ZOMBIE_DEATH(R.raw.zombie_death),
    GROAN1(R.raw.groan1),
    GROAN2(R.raw.groan2),
    GROAN3(R.raw.groan3),
    ROAR(R.raw.roar),
    HURT(R.raw.hurt),
    PICKUP(R.raw.pickup),
    WAVE_START(R.raw.wave_start),
    GAME_OVER(R.raw.game_over)
}

/** Small wrapper around SoundPool. All sounds are short WAVs in res/raw. */
class SoundFx(context: Context) {

    @Volatile
    var enabled = true

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(16)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = IntArray(Sfx.values().size)

    init {
        for (s in Sfx.values()) {
            ids[s.ordinal] = pool.load(context.applicationContext, s.res, 1)
        }
    }

    /**
     * @param volume 0..1
     * @param pan    -1 (left) .. +1 (right)
     * @param rate   playback speed / pitch, 0.5..2.0
     */
    fun play(sfx: Sfx, volume: Float = 1f, pan: Float = 0f, rate: Float = 1f) {
        if (!enabled) return
        val v = volume.coerceIn(0f, 1f)
        val p = pan.coerceIn(-1f, 1f)
        val left = v * (1f - max(0f, p) * 0.7f)
        val right = v * (1f + min(0f, p) * 0.7f)
        pool.play(ids[sfx.ordinal], left, right, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun pause() = pool.autoPause()

    fun resume() = pool.autoResume()

    fun release() = pool.release()
}
