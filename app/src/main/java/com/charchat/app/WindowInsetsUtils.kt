package com.charchat.app

import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads this view by the system bars insets (status bar, and the 3-button/gesture navigation bar
 * some phones show at the bottom) plus the on-screen keyboard's height when it's open, on top of
 * whatever padding it already has in XML. Needed because targetSdk 35+ enforces edge-to-edge
 * drawing, so content can otherwise end up drawn underneath the navigation bar, or hidden behind
 * the keyboard while typing, instead of the layout shifting to stay visible.
 */
fun View.applySystemBarInsetsAsPadding() {
    val initial = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        view.setPadding(
            initial.left + bars.left,
            initial.top + bars.top,
            initial.right + bars.right,
            initial.bottom + bars.bottom
        )
        insets
    }
}
