package com.aistra.hail.ui.home

/** Saved identities keep their positions; newly added items follow in the usual order. */
internal fun <T> orderHomeItems(
    items: List<T>, savedKeys: List<String>, key: (T) -> String, fallback: Comparator<in T>
): List<T> {
    val positions = savedKeys.withIndex().associate { it.value to it.index }
    return items.sortedWith { first, second ->
        val comparison = (positions[key(first)] ?: Int.MAX_VALUE)
            .compareTo(positions[key(second)] ?: Int.MAX_VALUE)
        if (comparison != 0) comparison else fallback.compare(first, second)
    }
}

internal fun <T> MutableList<T>.moveHomeItem(from: Int, to: Int): Boolean {
    if (from !in indices || to !in indices || from == to) return false
    add(to, removeAt(from))
    return true
}
