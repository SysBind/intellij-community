// API_VERSION: 1.3
// WITH_STDLIB
val x: Pair<String, Int> = listOf("a" to 1, "c" to 3, "b" to 2).<caret>sortedBy { it.second }.first()