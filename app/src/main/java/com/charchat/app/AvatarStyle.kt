package com.charchat.app

import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * Letter-avatar styling for a character with no photo: a big initial on a colored tile,
 * alternating between two accent hues. Keyed off the character's own id (stable across list
 * reorders/filters) rather than its position in whatever list is currently being shown.
 */
data class AvatarStyle(val letter: String, val backgroundColorRes: Int, val foregroundColorRes: Int)

fun Character.avatarStyle(): AvatarStyle {
    val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val isGreen = (id.hashCode() and 1) == 0
    return if (isGreen) {
        AvatarStyle(letter, R.color.avatar_bg_green, R.color.avatar_fg_green)
    } else {
        AvatarStyle(letter, R.color.avatar_bg_coral, R.color.avatar_fg_coral)
    }
}

/** Paints a circular tile background, for round avatar spots (chat header, message bubbles). */
fun View.setCircularAvatarBackground(colorRes: Int) {
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(context.getColor(colorRes))
    }
}
