package com.oleg.androidagent

/** Null means a clipped row; an unknown but observed row must retain its adapter index. */
internal class DiaryCoverage<T> {
    val rows = sortedMapOf<Int, T>()
    private var total = -1
    fun add(from: Int, count: Int, values: List<T?>): Boolean {
        require(count in 1..300 && from >= 0 && from + values.size <= count)
        require(total == -1 || total == count) { "Дневник изменился во время чтения." }
        total = count
        values.forEachIndexed { index, row ->
            if (row != null) {
                val position = from + index
                require(rows[position] == null || rows[position] == row) { "Строка дневника изменилась во время чтения." }
                rows[position] = row
            }
        }
        return rows.size == total
    }
    fun missing() = (0 until total).filter { it !in rows }.joinToString(",")
}
