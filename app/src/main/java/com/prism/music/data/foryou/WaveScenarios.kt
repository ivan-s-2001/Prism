package com.prism.music.data.foryou

/** Presets combine actual ranking controls. The clock orders suggestions, never switches playback. */
internal object WaveScenarios {
    enum class Group { DAY, ACTIVITY, DISCOVERY }
    enum class Glyph(val drawing: String) {
        WAKE("WaveWake"),
        MORNING("WaveMorning"),
        DAY("WaveDay"),
        EVENING("WaveEvening"),
        NIGHT("WaveNight"),
        WEEKEND("WaveWeekend"),
        COMMUTE("WaveCommute"),
        DRIVE("WaveDrive"),
        FOCUS("WaveFocus"),
        WORK("WaveWork"),
        TRAIN("WaveTrain"),
        WALK("WaveWalk"),
        REST("WaveRest"),
        FAVORITES("WaveFavorites"),
        HITS("WaveHits"),
        NEW("WaveNew"),
        DEEP("WaveDeep"),
        FAMILIAR_DEEP("WaveFamiliarDeep"),
        ENERGY("WaveEnergy"),
        CALM_NEW("WaveCalmNew"),
    }
    data class Scenario(val id: String, val ru: String, val en: String, val group: Group,
        val glyph: Glyph, val style: MyWave.Style)

    private fun preset(id: String, ru: String, en: String, group: Group, glyph: Glyph,
        activity: MyWave.Activity = MyWave.Activity.ANY,
        mood: MyWave.Mood = MyWave.Mood.ANY,
        character: MyWave.Character = MyWave.Character.BALANCED,
        depth: MyWave.Depth = MyWave.Depth.ANY, diversity: Int = 50) =
        Scenario(id, ru, en, group, glyph, MyWave.Style(character, mood, depth, activity, diversity))

    val all = listOf(
        preset("wake", "Мягкое пробуждение", "Gentle morning", Group.DAY, Glyph.WAKE, MyWave.Activity.WAKE, MyWave.Mood.CALM, diversity = 40),
        preset("morning", "Заряд на день", "Morning boost", Group.DAY, Glyph.MORNING, MyWave.Activity.WAKE, MyWave.Mood.ENERGETIC, diversity = 70),
        preset("day", "Дневной ритм", "Daytime rhythm", Group.DAY, Glyph.DAY, diversity = 70),
        preset("evening", "Вечер для себя", "Evening unwind", Group.DAY, Glyph.EVENING, mood = MyWave.Mood.CALM, character = MyWave.Character.FAMILIAR, diversity = 40),
        preset("night", "Перед сном", "Before sleep", Group.DAY, Glyph.NIGHT, MyWave.Activity.SLEEP, MyWave.Mood.CALM, diversity = 30),
        preset("weekend", "Свободный день", "Weekend", Group.DAY, Glyph.WEEKEND, character = MyWave.Character.DISCOVERY, diversity = 80),
        preset("commute", "В дороге", "On the road", Group.ACTIVITY, Glyph.COMMUTE, MyWave.Activity.ROAD, character = MyWave.Character.FAMILIAR, diversity = 60),
        preset("drive", "Дальняя поездка", "Long drive", Group.ACTIVITY, Glyph.DRIVE, MyWave.Activity.ROAD, diversity = 90),
        preset("focus", "Сосредоточиться", "Focus", Group.ACTIVITY, Glyph.FOCUS, MyWave.Activity.WORK, MyWave.Mood.CALM, diversity = 30),
        preset("work", "Рабочий ритм", "Work rhythm", Group.ACTIVITY, Glyph.WORK, MyWave.Activity.WORK, diversity = 60),
        preset("train", "На тренировку", "Workout", Group.ACTIVITY, Glyph.TRAIN, MyWave.Activity.TRAIN, MyWave.Mood.ENERGETIC, diversity = 70),
        preset("walk", "На прогулку", "For a walk", Group.ACTIVITY, Glyph.WALK, MyWave.Activity.ROAD, character = MyWave.Character.DISCOVERY, diversity = 70),
        preset("rest", "Выдохнуть", "Take a break", Group.ACTIVITY, Glyph.REST, mood = MyWave.Mood.CALM, diversity = 60),
        preset("favorites", "Проверенное любимое", "Trusted favorites", Group.DISCOVERY, Glyph.FAVORITES, character = MyWave.Character.FAMILIAR, diversity = 30),
        preset("hits", "Любимые хиты", "Favorite hits", Group.DISCOVERY, Glyph.HITS, character = MyWave.Character.FAMILIAR, depth = MyWave.Depth.HITS, diversity = 60),
        preset("new", "Новые открытия", "New discoveries", Group.DISCOVERY, Glyph.NEW, character = MyWave.Character.DISCOVERY, diversity = 90),
        preset("deep", "Скрытые жемчужины", "Hidden gems", Group.DISCOVERY, Glyph.DEEP, character = MyWave.Character.DISCOVERY, depth = MyWave.Depth.DEEP, diversity = 80),
        preset("familiar_deep", "Редкое у любимых", "Favorite deep cuts", Group.DISCOVERY, Glyph.FAMILIAR_DEEP, character = MyWave.Character.FAMILIAR, depth = MyWave.Depth.DEEP, diversity = 50),
        preset("energy", "На полную", "Full energy", Group.DISCOVERY, Glyph.ENERGY, mood = MyWave.Mood.ENERGETIC, depth = MyWave.Depth.HITS, diversity = 80),
        preset("calm_new", "Тихие открытия", "Quiet discoveries", Group.DISCOVERY, Glyph.CALM_NEW, mood = MyWave.Mood.CALM, character = MyWave.Character.DISCOVERY, diversity = 60),
    )

    fun forDay(hour: Int, weekend: Boolean): List<Scenario> {
        val first = when (hour) {
            in 5..8 -> "wake"
            in 9..11 -> "morning"
            in 12..17 -> if (weekend) "weekend" else "day"
            in 18..22 -> "evening"
            else -> "night"
        }
        return all.filter { it.group == Group.DAY }.sortedBy { when (it.id) {
            first -> 0
            "weekend" -> if (weekend) 1 else 3
            else -> 2
        } }
    }
}
