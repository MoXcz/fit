// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.lessons.LessonRepository
import app.fit.lessons.RoomLessonRepository
import app.fit.lessons.ui.LessonViewModel
import app.fit.lessons.ui.LessonsViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val lessonModule =
    module {
        singleOf(::RoomLessonRepository) bind LessonRepository::class
        viewModelOf(::LessonsViewModel)
        // The lesson id is a parameter.
        viewModelOf(::LessonViewModel)
    }
