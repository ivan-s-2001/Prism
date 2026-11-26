package com.prism.music

import org.junit.Assert.assertEquals
import org.junit.Test

class RootTabNavigationTest {
    private val root = "wave"
    private val tabs = setOf(root, "home", "search", "library")

    @Test fun nestedAlbumStaysUnderCollection() {
        assertEquals("library", activeRootTab(listOf("graph", root, "library", "album/{id}"), root, tabs))
    }
    @Test fun rootIsNotMistakenForSelectedSearch() {
        assertEquals("search", activeRootTab(listOf(root, "search"), root, tabs))
    }
    @Test fun directDetailOfRootRemainsUnderRoot() {
        assertEquals(root, activeRootTab(listOf("graph", root, "artist/{id}"), root, tabs))
    }
}
