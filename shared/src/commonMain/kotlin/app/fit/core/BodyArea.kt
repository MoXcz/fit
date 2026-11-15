// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.core

// The fixed list in docs/content-format.md; the Recover tab groups by it.
enum class BodyArea(
    val slug: String,
) {
    Wrists("wrists"),
    Forearms("forearms"),
    Elbows("elbows"),
    Shoulders("shoulders"),
    Neck("neck"),
    UpperBack("upper-back"),
    LowerBack("lower-back"),
    Hips("hips"),
    Knees("knees"),
    Ankles("ankles"),
    Feet("feet"),
    FullBody("full-body"),
    ;

    companion object {
        fun fromSlug(slug: String): BodyArea? = entries.firstOrNull { it.slug == slug }
    }
}
