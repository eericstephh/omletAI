package com.aicreatorstudio.app.model

import androidx.annotation.RawRes
import com.aicreatorstudio.app.R

/**
 * A bundled, ready-to-use sound effect that can be dropped onto the
 * SFX lane of the timeline.
 */
data class SoundEffect(
    val id: String,
    val displayName: String,
    @RawRes val resId: Int,
    val durationMs: Long
)

/** Famous built-in sound effects shipped with the app. */
object SoundEffects {
    val all: List<SoundEffect> = listOf(
        SoundEffect("whoosh", "Whoosh", R.raw.sfx_whoosh, 1200),
        SoundEffect("ding", "Ding", R.raw.sfx_ding, 1000),
        SoundEffect("pop", "Pop", R.raw.sfx_pop, 180),
        SoundEffect("boing", "Boing", R.raw.sfx_boing, 800),
        SoundEffect("drumroll", "Drum Roll", R.raw.sfx_drumroll, 1400),
        SoundEffect("applause", "Applause", R.raw.sfx_applause, 2000),
        SoundEffect("laser", "Laser", R.raw.sfx_laser, 450),
        SoundEffect("chime", "Notification", R.raw.sfx_chime, 1100),
        SoundEffect("riser", "Riser", R.raw.sfx_riser, 1800),
        SoundEffect("heartbeat", "Heartbeat", R.raw.sfx_heartbeat, 1000)
    )

    fun byId(id: String): SoundEffect? = all.firstOrNull { it.id == id }
}
