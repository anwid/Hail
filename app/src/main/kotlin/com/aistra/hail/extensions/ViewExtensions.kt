package com.aistra.hail.extensions

import android.view.View
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.color.MaterialColors

val View.isRtl get() = layoutDirection == View.LAYOUT_DIRECTION_RTL

fun SwipeRefreshLayout.applyMaterialTheme() {
    val bg = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorSurfaceContainerHigh,
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    )
    val fg = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    setProgressBackgroundColorSchemeColor(bg)
    setColorSchemeColors(fg)
}
