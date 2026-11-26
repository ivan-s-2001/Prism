package com.prism.music

import com.prism.music.data.SongVersions
import org.junit.Assert.*
import org.junit.Test

class AlternativeCatalogsTest {
    @Test fun russianLocaleStillSearchesForeignCatalogs() {
        assertEquals(listOf("KZ", "US", "DE"), SongVersions.alternativeCatalogs("RU"))
    }
    @Test fun selectedForeignCatalogIsFirstAndNoDuplicates() {
        assertEquals(listOf("US", "KZ", "DE"), SongVersions.alternativeCatalogs("us"))
        assertEquals(listOf("KZ", "US", "DE"), SongVersions.alternativeCatalogs("KZ"))
    }
}
