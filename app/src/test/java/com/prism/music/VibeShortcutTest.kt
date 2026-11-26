package com.prism.music

import com.prism.music.data.foryou.MyWave
import com.prism.music.ui.screens.personalVibe
import org.junit.Assert.assertEquals
import org.junit.Test

class VibeShortcutTest {
    @Test fun personalRestoresDefaultMixButPreservesLanguage() {
        val custom = MyWave.Style(character = MyWave.Character.FAMILIAR, mood = MyWave.Mood.CALM,
            depth = MyWave.Depth.DEEP, activity = MyWave.Activity.SLEEP,
            diversity = 10, discoveryPercent = 15, russianPercent = 80)
        assertEquals(MyWave.Style(russianPercent = 80), personalVibe(custom))
    }
    @Test fun personalKeepsAutomaticLanguage() {
        assertEquals(MyWave.Style(), personalVibe(MyWave.Style()))
    }
}
