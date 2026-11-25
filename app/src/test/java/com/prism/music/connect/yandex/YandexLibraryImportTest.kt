package com.prism.music.connect.yandex

import com.prism.music.data.imports.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class YandexLibraryImportTest {
    @Test fun importsMetadataWithoutAudioOrCredentials() {
        val track = YandexLibraryImport.track(Json.parseToJsonElement(
            """{"id":123,"title":"Song","artists":[{"name":"Artist"},{"name":"Guest"}],"albums":[{"title":"Album"}]}"""
        ).jsonObject)
        assertNotNull(track)
        assertEquals("yandex:123", track!!.sourceId)
        assertEquals("Song", track.title)
        assertEquals("Artist, Guest", track.artist)
        assertEquals("Album", track.album)
    }

    @Test fun incompleteMetadataIsNotAnInventedTrack() {
        assertNull(YandexLibraryImport.track(null))
        assertNull(YandexLibraryImport.track(buildJsonObject { put("id", 123); put("title", "Song") }))
    }

    @Test fun repeatedImportKeepsPlaylistIdentityAcrossRenames() {
        val song = LibraryTrack("Song", "Artist", sourceId = "yandex:123")
        val choice = LibraryJob.LibraryChoice()
        val first = ParsedLibrary(liked = listOf(song), playlists = listOf(LibraryPlaylist("First", listOf(song), "yandex:1:4")))
        val second = first.copy(playlists = listOf(LibraryPlaylist("Renamed", listOf(song), "yandex:1:4")))
        val job = LibraryJob().plus(first, choice).plus(second, choice)
        assertEquals(1, job.liked.size)
        assertEquals(1, job.playlists.size)
        assertEquals("Renamed", job.playlists.single().name)
        assertEquals(1, job.playlists.single().keys.size)
    }
}
