// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.shortcut

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.fit.android.MainActivity
import app.fit.android.R
import app.fit.guided.LauncherShortcuts
import app.fit.guided.PinnedShortcut

// Pinned sessions as dynamic launcher shortcuts, the latest pin first, as many as the launcher takes
// beside the static "Start workout".
class AndroidLauncherShortcuts(
    private val context: Context,
) : LauncherShortcuts {
    override fun setPinned(sessions: List<PinnedShortcut>): Boolean {
        val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - STATIC_SHORTCUTS).coerceAtLeast(0)
        val shortcuts =
            sessions.take(room).mapIndexed { rank, session ->
                ShortcutInfoCompat
                    .Builder(context, shortcutId(session.planId.value))
                    .setShortLabel(session.title)
                    .setLongLabel(context.getString(R.string.shortcut_start_session, session.title))
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setRank(rank)
                    .setIntent(
                        Intent(context, MainActivity::class.java)
                            .setAction(MainActivity.ACTION_START_SESSION)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(MainActivity.EXTRA_PLAN_ID, session.planId.value),
                    ).build()
            }
        return ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    companion object {
        // "Start workout" in res/xml/shortcuts.xml.
        private const val STATIC_SHORTCUTS = 1

        fun shortcutId(planId: Long): String = "recovery:$planId"
    }
}
