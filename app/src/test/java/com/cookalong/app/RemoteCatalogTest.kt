package com.cookalong.app

import com.cookalong.app.data.Recipe
import com.cookalong.app.data.RemoteCatalog
import com.cookalong.app.data.RowRule
import com.cookalong.app.data.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCatalogTest {

    private fun r(id: String, title: String = id, source: String? = "wikibooks", steps: Int = 1) = Recipe(
        id = id, title = title, source = source,
        steps = List(steps) { Step("s$it", it + 1, "Do it.") },
    )

    @Test fun mergeReplacesChangedAndCountsNew() {
        val (merged, added) = RemoteCatalog.merge(
            listOf(r("a", "Old A"), r("b")),
            listOf(r("a", "New A"), r("c"), r("broken", steps = 0)),
        )
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals("New A", merged.first().title)
        assertEquals(1, added)
    }

    @Test fun bundledWinsOverOnlineDuplicates() {
        val bundled = listOf(r("dal-tadka", "Dal Tadka", source = null))
        val online = listOf(r("dal-tadka", "Dal Tadka", "bundled"), r("dal-fry", "dal tadka"), r("rasam", "Rasam"))
        assertEquals(listOf("dal-tadka", "rasam"), RemoteCatalog.combine(bundled, online).map { it.id })
    }

    @Test fun cacheIsRefreshedInFullOncePerWeek() {
        val day = 24L * 60 * 60 * 1000
        assertTrue(RemoteCatalog.needsFullRefresh(0, 10 * day))
        assertFalse(RemoteCatalog.needsFullRefresh(10 * day, 12 * day))
        assertTrue(RemoteCatalog.needsFullRefresh(10 * day, 18 * day))
    }

    @Test fun onlineRowShowsOnlyDownloadedRecipes() {
        val rule = RowRule("New", anySources = listOf("online"))
        assertTrue(rule.matches(r("x", source = "howtocook")))
        assertFalse(rule.matches(r("y", source = "bundled")))
        assertFalse(rule.matches(r("z", source = null)))
    }
}
