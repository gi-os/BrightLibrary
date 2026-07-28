package com.lightfastread.data

import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val title: String,
    val startWordIndex: Int,
)

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String = "",
    val format: String,
    val textFileName: String,
    val totalWords: Int,
    val currentWordIndex: Int = 0,
    val addedAtMs: Long = System.currentTimeMillis(),
    val chapters: List<Chapter> = emptyList(),
    val bookmarks: List<Int> = emptyList(),
)
