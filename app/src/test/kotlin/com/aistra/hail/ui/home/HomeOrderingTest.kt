package com.aistra.hail.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeOrderingTest {
    private data class Item(val key: String, val name: String)
    private val alphabetical = compareBy<Item> { it.name }

    @Test fun clonedAppsKeepSeparatePositionsAcrossReloads() {
        val primary = Item("0:com.example.app", "App")
        val clone = Item("999:com.example.app", "App")
        val third = Item("0:com.example.third", "Third")
        val dragged = mutableListOf(primary, clone, third)
        assertTrue(dragged.moveHomeItem(1, 0))
        val savedKeys = dragged.map { it.key }
        assertEquals(listOf(clone, primary, third),
            orderHomeItems(listOf(third, primary, clone), savedKeys, Item::key, alphabetical))
    }

    @Test fun newAppsAppendAlphabeticallyAndRemovedAppsDoNotReturn() {
        val last = Item("last", "Zebra")
        val first = Item("first", "Apple")
        val newB = Item("new-b", "Bravo")
        val newA = Item("new-a", "Alpha")
        assertEquals(listOf(last, first, newA, newB), orderHomeItems(
            listOf(newB, first, newA, last), listOf("removed", "last", "first"), Item::key, alphabetical
        ))
    }

    @Test fun independentTagsCanHaveDifferentOrdersForTheSameApps() {
        val a = Item("0:app.a", "A")
        val b = Item("0:app.b", "B")
        val apps = listOf(a, b)
        assertEquals(listOf(b, a), orderHomeItems(apps, listOf(b.key, a.key), Item::key, alphabetical))
        assertEquals(listOf(a, b), orderHomeItems(apps, listOf(a.key, b.key), Item::key, alphabetical))
    }

    @Test fun draggingTagsPreservesDefaultIdAndBothEndPositions() {
        val tags = mutableListOf("Default" to 0, "Games" to 12, "Work" to -3)
        assertTrue(tags.moveHomeItem(0, 2))
        assertEquals(listOf(12, -3, 0), tags.map { it.second })
        assertTrue(tags.moveHomeItem(2, 0))
        assertEquals(listOf(0, 12, -3), tags.map { it.second })
        assertFalse(tags.moveHomeItem(-1, 0))
        assertFalse(tags.moveHomeItem(0, tags.size))
        assertFalse(tags.moveHomeItem(1, 1))
        assertEquals(listOf(0, 12, -3), tags.map { it.second })
    }
}
