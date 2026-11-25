package com.prism.music

import com.prism.music.data.foryou.MyWave
import com.prism.music.data.foryou.LocalRadio
import com.prism.music.data.foryou.RadioTopUp
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.*
import org.junit.Test

class MyWaveTest {
    private fun song(id: String, artist: String, title: String = id) =
        Song(id, title, listOf(ArtistRef(artist)), durationSec = 180)

    @Test fun mixesDiscoveriesAndFavorites() {
        val picks = listOf(song("n1", "A"), song("n2", "B"), song("n3", "C"))
        val favorites = listOf(song("f1", "D"), song("f2", "E"))
        val result = MyWave.start(picks, favorites, emptyList(), { false }, { true }, 42L, 5)
        assertEquals(5, result.size)
        assertTrue(result.first() in picks)
        assertTrue(result[1] in favorites)
        assertEquals(5, result.map { it.id }.distinct().size)
    }

    @Test fun excludesBlockedSongsAndDuplicateUploads() {
        val first = song("a", "Artist", "Title")
        val duplicate = song("b", "Artist", "Title (Remastered)")
        val blocked = song("c", "Other")
        val good = song("d", "Another")
        val result = MyWave.start(listOf(first, blocked, good), listOf(duplicate), emptyList(),
            { false }, { it != blocked }, 1L, 12)
        assertEquals(2, result.size)
        assertFalse(blocked in result)
        assertTrue(good in result)
    }

    @Test fun favorsFreshSongs() {
        val old = song("old", "A")
        val fresh = song("fresh", "B")
        val result = MyWave.start(listOf(old, fresh), emptyList(), listOf(old),
            { it == old }, { true }, 7L, 1)
        assertEquals(listOf(fresh), result)
    }

    @Test fun selectedCharacterChangesFamiliarDiscoveryRatio() {
        val known = (1..12).map { song("f$it", "F$it") }
        val discoveries = (1..12).map { song("n$it", "N$it") }
        fun start(character: MyWave.Character) = MyWave.start(discoveries, known, emptyList(),
            { false }, { true }, 9L, 12, MyWave.Style(character)).count { it in known }
        assertTrue(start(MyWave.Character.FAMILIAR) > start(MyWave.Character.DISCOVERY))
    }

    @Test fun selectedMoodUsesMeasuredEnergyWithoutInventingMissingData() {
        val calm = song("calm", "A")
        val energetic = song("energy", "B")
        val signals = com.prism.music.data.foryou.RadioSignals(energy = { if (it == calm) 0.0 else 1.0 })
        fun first(mood: MyWave.Mood) = MyWave.start(listOf(calm, energetic), emptyList(), emptyList(),
            { false }, { true }, 12L, 1, MyWave.Style(mood = mood), signals).single()
        assertEquals(calm, first(MyWave.Mood.CALM))
        assertEquals(energetic, first(MyWave.Mood.ENERGETIC))
    }

    @Test fun waveStyleSurvivesQueueOriginRoundTrip() {
        val style = MyWave.Style(MyWave.Character.DISCOVERY, MyWave.Mood.ENERGETIC)
        assertTrue(MyWave.isOrigin(style.origin))
        assertEquals(style, MyWave.style(style.origin))
        assertEquals(MyWave.Style(), MyWave.style(MyWave.ORIGIN))
        assertFalse(MyWave.isOrigin("another-playlist"))
    }

    @Test fun agreementBetweenRankedSourcesPromotesTheSameRecording() {
        val first = song("first", "A")
        val consensus = song("consensus", "B", "Recording")
        val alternate = song("alternate", "B", "Recording (Remastered)")
        val second = song("second", "C")
        val fused = MyWave.fuse(listOf(listOf(first, consensus), listOf(second, alternate)))
        assertEquals(consensus, fused.first())
        assertEquals(3, fused.size)
    }

    @Test fun relevantContextAndTasteCanPromoteAnOtherwiseLowerRankedSong() {
        val generic = song("generic", "A")
        val fitting = song("fitting", "B")
        val profile = MyWave.Profile(taste = { if (it == fitting) 1.0 else 0.0 },
            context = { if (it == fitting) 1.0 else 0.0 })
        assertEquals(fitting, MyWave.start(listOf(generic, fitting), emptyList(), emptyList(),
            { false }, { true }, 7L, 1, profile = profile).single())
    }

    @Test fun avoidsPreviousRecordingAndArtistAcrossRefillBoundary() {
        val previous = song("previous", "A", "Recording")
        val duplicate = song("upload", "A", "Recording (Remastered)")
        val sameArtist = song("same", "A")
        val different = song("different", "B")
        val result = MyWave.start(listOf(duplicate, sameArtist, different), emptyList(), emptyList(),
            { false }, { true }, 3L, 2, previousSong = previous,
            avoid = setOf(com.prism.music.data.foryou.LocalRadio.sameSong(previous)))
        assertEquals(listOf(different, sameArtist), result)
    }

    @Test fun depthDoesNotInventPopularityAndOldOriginsStillLoad() {
        val hit = song("hit", "A")
        val deep = song("deep", "B")
        val signals = com.prism.music.data.foryou.RadioSignals(onTopList = { it == hit })
        val style = MyWave.Style(depth = MyWave.Depth.DEEP)
        assertEquals(deep, MyWave.start(listOf(hit, deep), emptyList(), emptyList(),
            { false }, { true }, 6L, 1, style, signals).single())
        assertEquals(style, MyWave.style(style.origin))
        assertEquals(MyWave.Depth.ANY, MyWave.style("prism:my-wave:FAMILIAR:CALM").depth)
    }

    @Test fun activityAndExplicitMoodUseOnlyKnownAudioEnergy() {
        val calm = song("calm", "A")
        val loud = song("loud", "B")
        val signals = com.prism.music.data.foryou.RadioSignals(energy = { if (it == calm) 0.0 else 1.0 })
        val workout = MyWave.Style(activity = MyWave.Activity.TRAIN, diversity = 80)
        assertEquals(loud, MyWave.start(listOf(calm, loud), emptyList(), emptyList(), { false }, { true },
            1L, 1, workout, signals).single())
        assertEquals(calm, MyWave.start(listOf(calm, loud), emptyList(), emptyList(), { false }, { true },
            1L, 1, workout.copy(mood = MyWave.Mood.CALM), signals).single())
        assertEquals(workout, MyWave.style(workout.origin))
        assertEquals(MyWave.Activity.ANY, MyWave.style("prism:my-wave:BALANCED:ANY:HITS").activity)
    }

    @Test fun implicitFeedbackIsShrunkAndExplorationIsBounded() {
        val unknown = MyWave.Evidence()
        val positive = MyWave.Evidence(accepted = 12.0, skipped = 1.0)
        val negative = MyWave.Evidence(accepted = 1.0, skipped = 12.0)
        assertEquals(1.0, unknown.weight(0.0), 1e-9)
        assertTrue(unknown.weight(1.0) > 1.0)
        assertTrue(unknown.weight(1.0) <= 1.12 + 1e-9)
        assertTrue(positive.weight(0.0) > 1.0)
        assertTrue(negative.weight(1.0) < 1.0)
        assertTrue(MyWave.Evidence(accepted = 1.0).weight(0.0) < positive.weight(0.0))
    }

    @Test fun varietyDoesNotOverrideExclusions() {
        val repeated = (1..8).map { song("a$it", "A") }
        val others = (1..6).map { song("b$it", "Artist $it") }
        val blocked = others.first()
        val result = MyWave.start(repeated + others, emptyList(), emptyList(), { false }, { it != blocked },
            4L, 6, MyWave.Style(diversity = 100))
        assertFalse(blocked in result)
        assertTrue(result.count { it.primaryArtist == "A" } <= 2)
    }

    @Test fun newLikesCarryMoreWeightButOldLikesRemainPositive() {
        val day = 86_400_000L
        val now = 500L * day
        val today = MyWave.likeWeight(now, now)
        val fortnight = MyWave.likeWeight(now - 14 * day, now)
        val quarter = MyWave.likeWeight(now - 90 * day, now)
        val year = MyWave.likeWeight(now - 365 * day, now)
        assertEquals(1.0, today, 1e-9)
        assertTrue(today > fortnight && fortnight > quarter && quarter > year)
        assertTrue(year >= 0.20)
        assertEquals(0.20, MyWave.likeWeight(0, now), 1e-9)
        assertEquals(0.20, MyWave.likeWeight(now + day, now), 1e-9)
        assertTrue(MyWave.likeSaturation(2.0) > MyWave.likeSaturation(1.0))
        assertTrue(MyWave.likeSaturation(10.0) < 1.0)
    }

    @Test fun freshExplicitLikePromotesSongEvenWithOlderRankedCandidates() {
        val old = song("old", "A")
        val fresh = song("fresh", "B")
        val profile = MyWave.Profile(likedAffinity = { if (it == fresh) 1.0 else 0.20 })
        val result = MyWave.start(emptyList(), listOf(old, fresh), emptyList(), { false }, { true },
            8L, 1, MyWave.Style(character = MyWave.Character.FAMILIAR), profile = profile)
        assertEquals(listOf(fresh), result)
    }

    @Test fun dailyScenariosAreDistinctAndClockChangesOrderingOnly() {
        val scenarios = com.prism.music.data.foryou.WaveScenarios
        assertEquals(20, scenarios.all.size)
        assertEquals(20, scenarios.all.map { it.id }.distinct().size)
        assertEquals(20, scenarios.all.map { it.glyph }.distinct().size)
        assertEquals(20, scenarios.all.map { it.glyph.drawing }.distinct().size)
        assertEquals(20, scenarios.all.map { it.style }.distinct().size)
        assertEquals("wake", scenarios.forDay(7, false).first().id)
        assertEquals("day", scenarios.forDay(15, false).first().id)
        assertEquals("weekend", scenarios.forDay(15, true).first().id)
        assertEquals("evening", scenarios.forDay(20, false).first().id)
        assertEquals("night", scenarios.forDay(2, false).first().id)
        assertEquals(scenarios.forDay(7, false).toSet(), scenarios.forDay(20, true).toSet())
    }

    @Test fun explicitDiscoveryPercentageRoundTripsAndKeepsCumulativeQuota() {
        val style = MyWave.Style(discoveryPercent = 65)
        assertEquals(style, MyWave.style(style.origin))
        assertEquals(70, MyWave.style(MyWave.ORIGIN).discoveryPercent)
        assertEquals(0, MyWave.style("prism:my-wave:FAMILIAR:CALM").discoveryPercent)
        for (percent in 0..100 step 5) {
            assertEquals(percent, (0 until 100).count { MyWave.discoverySlot(it, percent) })
            val first = (0 until 8).count { MyWave.discoverySlot(it, percent) }
            val next = (8 until 16).count { MyWave.discoverySlot(it, percent) }
            assertEquals(kotlin.math.round(16.0 * percent / 100.0 + 0.000001).toInt(), first + next)
        }
    }

    @Test fun strictMixHasRequestedCompositionAndNoDuplicateRecordings() {
        val likes = (1..20).map { song("liked$it", "L$it") }
        val discoveries = (1..30).map { song("new$it", "N$it") }
        val alias = song("alias", "L1", "liked1 (Remastered)")
        val previouslyHeard = song("history", "Other")
        fun mix(percent: Int) = MyWave.mix(discoveries + likes + alias + previouslyHeard, likes,
            isKnown = { it == previouslyHeard }, heard = { false }, allowed = { true }, seed = 3L,
            style = MyWave.Style(discoveryPercent = percent), signals = com.prism.music.data.foryou.RadioSignals(),
            profile = MyWave.Profile(), count = 10)
        assertTrue(mix(0).all { it in likes })
        assertEquals(10, mix(0).size)
        assertTrue(mix(100).all { it in discoveries })
        assertEquals(10, mix(100).size)
        assertEquals(7, mix(70).count { it in discoveries })
        assertEquals(3, mix(70).count { it in likes })
    }

    @Test fun exhaustedDiscoveryPoolDoesNotSilentlyPlayLikes() {
        val likes = listOf(song("l", "A"))
        val result = MyWave.mix(emptyList(), likes, { false }, { false }, { true }, 1L,
            MyWave.Style(discoveryPercent = 100), com.prism.music.data.foryou.RadioSignals(), MyWave.Profile())
        assertTrue(result.isEmpty())
        val mixed = MyWave.mix(emptyList(), likes, { false }, { false }, { true }, 1L,
            MyWave.Style(discoveryPercent = 70), com.prism.music.data.foryou.RadioSignals(), MyWave.Profile())
        assertTrue(mixed.isEmpty())
    }

    @Test fun refillUsesSameQuotaAndNeverReaddsQueuedSongs() {
        val likes = (1..20).map { song("l$it", "L$it") }
        val fresh = (1..40).map { song("n$it", "N$it") }
        val style = MyWave.Style(discoveryPercent = 70)
        fun batch(offset: Int, previous: List<Song>) = MyWave.mix(fresh, likes, { false }, { false }, { true }, 2L,
            style, com.prism.music.data.foryou.RadioSignals(), MyWave.Profile(), count = 8,
            offset = offset, previousSong = previous.lastOrNull(),
            avoid = previous.mapTo(hashSetOf(), com.prism.music.data.foryou.LocalRadio::sameSong))
        val one = batch(0, emptyList())
        val two = batch(one.size, one)
        assertEquals(16, (one + two).map { it.id }.distinct().size)
        assertEquals(11, (one + two).count { it in fresh })
    }

    @Test fun diversifiesArtistsWhenPossible() {
        val songs = listOf(song("a1", "A"), song("a2", "A"), song("b", "B"), song("c", "C"))
        val result = MyWave.start(songs, emptyList(), emptyList(), { false }, { true }, 13L, 4)
        assertEquals(4, result.size)
        assertFalse(result.zipWithNext().any { it.first.primaryArtist == it.second.primaryArtist })
    }

    @Test fun offlineMixFillsMissingDiscoveryQuotaWithDownloadedMusic() {
        val a = song("a", "A")
        val b = song("b", "B")
        val style = MyWave.Style(discoveryPercent = 100)
        val result = MyWave.offlineMix(listOf(a, b), listOf(a, b), emptyList(), null,
            { true }, style, com.prism.music.data.foryou.RadioSignals(), MyWave.Profile(), 2, 0, 42L)
        assertEquals(setOf(a, b), result.toSet())
    }

    @Test fun offlineMixDoesNotDuplicateUpcomingSongsAndCanRecycle() {
        val a = song("a", "A")
        val b = song("b", "B")
        val c = song("c", "C")
        val result = MyWave.offlineMix(listOf(a, b, c), emptyList(), listOf(b), a,
            { true }, MyWave.Style(), com.prism.music.data.foryou.RadioSignals(), MyWave.Profile(), 8, 3, 42L)
        assertEquals(listOf(c), result)
    }

    @Test fun offlineMixUsesSingleDownloadAfterExhaustion() {
        val a = song("a", "A")
        val result = MyWave.offlineMix(listOf(a), emptyList(), emptyList(), a,
            { true }, MyWave.Style(), com.prism.music.data.foryou.RadioSignals(), MyWave.Profile(), 8, 0, 42L)
        assertEquals(listOf(a), result)
    }

    @Test fun stripOrderIncludesAllScenariosWithoutSelectionDependentOrdering() {
        val scenarios = com.prism.music.data.foryou.WaveScenarios
        val order = scenarios.stripOrder(19, false)
        assertEquals("evening", order.first().id)
        assertEquals(scenarios.all.toSet(), order.toSet())
        assertEquals(scenarios.all.size, order.size)
    }

    @Test fun tuningDraftNeverMutatesOriginalSettings() {
        val original = com.prism.music.data.foryou.WaveSettingsDraft(MyWave.Style(), false, emptyList(), null)
        val edited = original.discovery(30).tune(MyWave.Style(russianPercent = 75, discoveryPercent = 30, diversity = 80))
            .copy(themeColors = true).save("Мой поток", "test")
        assertEquals(MyWave.Style(), original.style)
        assertFalse(original.themeColors)
        assertTrue(original.presets.isEmpty())
        assertEquals(30, edited.style.discoveryPercent)
        assertEquals(75, edited.style.russianPercent)
        assertEquals(80, edited.style.diversity)
        assertEquals(edited.style, MyWave.style(edited.presets.single().origin))
        assertEquals("test", edited.presetId)
        assertTrue(edited.delete("test").presets.isEmpty())
    }

    @Test fun offlineRefillUsesFreshFirstThenRecyclesToFillBatch() {
        val fresh = song("fresh", "A")
        val older = song("older", "B")
        val previous = song("previous", "C")
        val result = MyWave.offlineMix(listOf(fresh, older, previous), emptyList(), emptyList(), previous,
            { it != fresh }, MyWave.Style(discoveryPercent = 100),
            com.prism.music.data.foryou.RadioSignals(), MyWave.Profile(), 8, 0, 42L)
        assertEquals(listOf(fresh, older), result)
    }

    @Test fun offlineDownloadsRespectUpcomingAndPreferredLanguage() {
        val russian = song("ru", "A", "Мелодия")
        val foreign = song("en", "B", "Song")
        val result = MyWave.offlineMix(listOf(russian, foreign), emptyList(), emptyList(), null,
            { false }, MyWave.Style(russianPercent = 100), com.prism.music.data.foryou.RadioSignals(),
            MyWave.Profile(), 8, 0, 42L)
        assertEquals(listOf(russian), result)
    }

    @Test fun restorePrefersQueueThenSavedStyleThenLegacyLanguage() {
        val queued = MyWave.Style(diversity = 90)
        val saved = MyWave.Style(russianPercent = 40)
        assertEquals(queued, MyWave.restore(queued.origin, saved.origin, 0))
        assertEquals(saved, MyWave.restore("another-playlist", saved.origin, 0))
        assertEquals(MyWave.Style(russianPercent = 25), MyWave.restore(null, null, 25))
    }

    @Test fun initialPicksAvoidCurrentRecordingExceptSingleOfflineTrack() {
        val current = song("current", "A", "Title")
        val alias = song("other", "A", "Title (Remastered)")
        val next = song("next", "B")
        assertEquals(listOf(next), MyWave.initialPicks(listOf(alias, next), current, false))
        assertTrue(MyWave.initialPicks(listOf(current), current, false).isEmpty())
        assertEquals(listOf(current), MyWave.initialPicks(listOf(current), current, true))
    }

    @Test fun radioAndStreamUseTheSameRecordingIdentity() {
        val queued = song("queued", "A", "Title")
        val alias = song("alias", "A", "Title (Remastered)")
        val own = song("own", "B", "Another")
        val remoteAlias = song("remote", "B", "Another (Remastered)")
        val fresh = song("fresh", "C")
        val rules = RadioTopUp.Rules(setOf(queued.id), emptySet(), { false }, { true },
            queuedRecordings = setOf(LocalRadio.sameSong(queued)))
        val batch = RadioTopUp.batch(listOf(alias, own), listOf(remoteAlias, fresh), true, rules)
        assertEquals(setOf(LocalRadio.sameSong(own), LocalRadio.sameSong(fresh)), batch.map(LocalRadio::sameSong).toSet())
        assertEquals(2, batch.size)
    }

    @Test fun radioRefusesUnwantedSongsFromBothSources() {
        val refused = song("refused", "A")
        val blocked = song("blocked", "B")
        val recent = song("recent", "C")
        val fresh = song("fresh", "D")
        val rules = RadioTopUp.Rules(emptySet(), setOf(recent.id), { it.id == refused.id }, { it.id != blocked.id })
        val batch = RadioTopUp.batch(listOf(refused, recent, fresh), listOf(blocked, refused), true, rules)
        assertEquals(listOf(fresh), batch)
        assertTrue(RadioTopUp.comingRound(listOf(refused, blocked), { null }, rules).isEmpty())
    }

    @Test fun tuningRatiosUseLeftShareForDividerAndRoundTripToRightShare() {
        val discovery = com.prism.music.ui.components.WaveTuningScale.DISCOVERY
        val language = com.prism.music.ui.components.WaveTuningScale.LANGUAGE
        val diversity = com.prism.music.ui.components.WaveTuningScale.DIVERSITY
        for (scale in listOf(discovery, language)) {
            assertEquals(70, scale.position(30))
            assertEquals(30, scale.position(70))
            assertEquals(30, scale.fromPosition(70f))
            assertEquals(100, scale.fromPosition(0f))
            assertEquals(0, scale.fromPosition(100f))
            for (share in 0..100 step 5) {
                assertEquals(share, scale.fromPosition(scale.position(share).toFloat()))
            }
        }
        assertEquals(50, language.position(-1))
        assertEquals(70, diversity.position(70))
        assertEquals(70, diversity.fromPosition(70f))
    }

    @Test fun builtInScenarioTuningIsIsolatedAndRestorable() {
        val scenarios = com.prism.music.data.foryou.WaveScenarios
        val first = scenarios.all.first()
        val second = scenarios.all[1]
        val original = com.prism.music.data.foryou.WaveSettingsDraft(first.style, false, emptyList(), first.id)
        val edited = original.discovery(35)
        assertEquals(first.id, edited.presetId)
        assertTrue(original.scenarioStyles.isEmpty())
        assertEquals(edited.style, edited.choose(second).choose(first).style)
        assertEquals(first.style, edited.reset().style)
        assertTrue(edited.reset().scenarioStyles.isEmpty())
        assertEquals(second.style, scenarios.resolve(second, edited.scenarioStyles, -1))
    }

    @Test fun editingSavedScenarioKeepsIdAndStagesNewSettings() {
        val initial = com.prism.music.data.foryou.WaveSettingsDraft(MyWave.Style(), false, emptyList(), null)
            .save("Custom", "custom")
        val edited = initial.discovery(25)
        assertEquals("custom", edited.presetId)
        assertEquals(edited.style.origin, edited.presets.single().origin)
        assertEquals(initial.style.origin, initial.presets.single().origin)
    }

    @Test fun sameSelectorSupportsStrictAndSoftQuotas() {
        val known = listOf(song("a", "A"), song("b", "B"))
        val style = MyWave.Style(discoveryPercent = 100)
        val strict = MyWave.start(emptyList(), known, emptyList(), { false }, { true }, 3L,
            count = 2, style = style, quotaOffset = 0, strictQuota = true)
        val soft = MyWave.start(emptyList(), known, emptyList(), { false }, { true }, 3L,
            count = 2, style = style, quotaOffset = 0)
        assertTrue(strict.isEmpty())
        assertEquals(known.toSet(), soft.toSet())
    }

    @Test fun legacyRadioTuneMigratesIntoStreamStyle() {
        val tune = com.prism.music.data.foryou.RadioTune(newness = -0.5f, depth = 0.7f, energy = 0.8f)
        val style = requireNotNull(MyWave.legacyStyle(tune, 25))
        assertEquals(25, style.discoveryPercent)
        assertEquals(25, style.russianPercent)
        assertEquals(MyWave.Mood.ENERGETIC, style.mood)
        assertEquals(MyWave.Depth.DEEP, style.depth)
        assertNull(MyWave.legacyStyle(com.prism.music.data.foryou.RadioTune(), -1))
    }
}
