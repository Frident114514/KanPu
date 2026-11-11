package com.kanpu.app.domain

enum class LayoutMode { AUTO, SINGLE, DOUBLE }

data class VisiblePages(val first: Int, val second: Int?) {
    fun contains(page: Int): Boolean = page == first || page == second
}

object PageLayout {
    fun isDouble(mode: LayoutMode, width: Int, height: Int): Boolean = when (mode) {
        LayoutMode.AUTO -> width > height
        LayoutMode.SINGLE -> false
        LayoutMode.DOUBLE -> true
    }

    fun visible(focus: Int, pageCount: Int, double: Boolean): VisiblePages {
        require(pageCount > 0)
        val page = focus.coerceIn(0, pageCount - 1)
        if (!double) return VisiblePages(page, null)
        val first = page - page % 2
        return VisiblePages(first, (first + 1).takeIf { it < pageCount })
    }

    fun turn(focus: Int, pageCount: Int, double: Boolean, direction: Int): Int {
        val current = visible(focus, pageCount, double)
        val step = if (double) 2 else 1
        val first = current.first + step * direction.sign()
        if (first !in 0 until pageCount) return focus
        return first
    }

    private fun Int.sign(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }
}
