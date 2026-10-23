package com.aistra.hail.app

data class TagInfo(
    var name: String,
    val id: Int,
    var workingMode: String = HailData.MODE_DEFAULT
) {
    val first: String get() = name
    val second: Int get() = id
}
