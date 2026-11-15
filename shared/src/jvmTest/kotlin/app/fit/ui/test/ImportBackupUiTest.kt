// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.fit.backup.ui.DataRoute
import app.fit.backup.ui.DataViewModel
import app.fit.content.ContentKind
import app.fit.content.FakeBackups
import app.fit.content.FakeContentImporter
import app.fit.content.ImportInbox
import app.fit.content.ImportItem
import app.fit.content.ImportPreview
import app.fit.content.ui.ImportRoute
import app.fit.content.ui.ImportViewModel
import app.fit.core.OpenedDocument
import app.fit.core.SaveResult
import app.fit.guided.FakeDocuments
import app.fit.ui.MoreScreen
import app.fit.workout.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

// Import content and Backup and data, over fakes: every state worth looking at.
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ImportBackupUiTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val pack =
        FakeContentImporter.ready(
            "my-routines.zip",
            ImportItem(ContentKind.Exercise, "goblet-squat", "Goblet squat", null),
            ImportItem(ContentKind.Exercise, "push-up", "Push-up", replaces = "Push-up"),
            ImportItem(ContentKind.Routine, "home-a", "Home A", null),
            ImportItem(ContentKind.Routine, "home-b", "Home B", replaces = "Home B (old)"),
            ImportItem(ContentKind.Program, "home", "Home 3 day", null),
            ImportItem(ContentKind.Recovery, "neck-release", "Neck release", null),
            ImportItem(ContentKind.Lesson, "goblet-squat", "Goblet squat", null),
            newImages = listOf("images/goblet.webp", "images/neck.webp"),
            replacedImages = listOf("images/push-up.webp"),
        )
    private val fresh =
        FakeContentImporter.ready("neck-release.md", ImportItem(ContentKind.Recovery, "neck-release", "Neck release", null))
    private val broken =
        ImportPreview.Invalid(
            "my-routines.zip",
            listOf(
                "routines/home-a.md:9: there is no exercise with the id goblet-sqat",
                "recovery/neck-release.md:6: minutes must be within 2 of the steps' total (about 7.5)",
                "lessons/goblet-squat.md:31: Markdown: tables aren't supported",
                "images/Neck.JPG: an image is images/<name>.webp, .png or .jpg, in lowercase letters, digits, dots and dashes",
            ),
        )

    private class Fixture(
        val importer: FakeContentImporter,
        val documents: FakeDocuments,
        val inbox: ImportInbox,
        val backups: FakeBackups,
    )

    private fun fixture() = Fixture(FakeContentImporter(), FakeDocuments(), ImportInbox(), FakeBackups())

    private fun SkikoComposeUiTest.importScreen(
        x: Fixture,
        scope: TestScope,
        share: String = "",
    ) {
        val vm = ImportViewModel(share, x.importer, x.documents, x.inbox, scope.backgroundScope).clearedAfterTest()
        setScreen { ImportRoute(onBack = {}, viewModel = vm) }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.choose(
        x: Fixture,
        scope: TestScope,
        preview: ImportPreview,
        button: String = "Choose file",
    ) {
        x.importer.previews[preview.file] = preview
        x.documents.next = OpenedDocument.File(preview.file, ByteArray(0))
        onNodeWithText(button).performClick()
        advance(scope, 0.seconds)
    }

    private fun SkikoComposeUiTest.scrollTo(text: String) {
        onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
        waitForIdle()
    }

    @Test
    fun `More lists Import content and Backup and data after the rest`() =
        runPhoneUiTest {
            val opened = mutableListOf<String>()
            setScreen {
                MoreScreen(
                    onOpenHistory = {},
                    onOpenBodyWeight = {},
                    onOpenSettings = {},
                    onOpenImport = { opened += "import" },
                    onOpenData = { opened += "data" },
                    onOpenHelp = {},
                    onOpenAbout = {},
                )
            }
            waitForIdle()
            onNodeWithText("Import content").performClick()
            onNodeWithText("Backup and data").performClick()
            assertEquals(listOf("import", "data"), opened)
            screenshot("more")
        }

    @Test
    fun `Import starts by asking for a file, and a new item imports in one tap`() =
        runTest {
            val x = fixture()
            runPhoneUiTest {
                importScreen(x, this@runTest)
                onNodeWithText("Choose file").assertIsDisplayed()
                screenshot("import-empty")
                choose(x, this@runTest, fresh)
                onNodeWithText("neck-release.md").assertIsDisplayed()
                onNodeWithText("Recovery sessions").assertIsDisplayed()
                onNodeWithText("New").assertIsDisplayed()
                screenshot("import-new")
                onNodeWithText("Import 1 item").performClick()
                advance(this@runTest, 0.seconds)
                assertEquals(listOf("neck-release.md"), x.importer.imported.map { it.file })
                onNodeWithText("Imported 1 item").assertIsDisplayed()
                onNodeWithText("Choose file").assertIsDisplayed()
                screenshot("import-done")
            }
        }

    @Test
    fun `a pack is previewed by kind with what it replaces, and replacing asks first`() =
        runTest {
            val x = fixture()
            runPhoneUiTest {
                importScreen(x, this@runTest)
                choose(x, this@runTest, pack)
                onNodeWithText("Exercises").assertIsDisplayed()
                onNodeWithText("Replaces Push-up").assertIsDisplayed()
                onNodeWithText("Replaces Home B (old)").assertIsDisplayed()
                screenshot("import-pack")
                scrollTo("Import 7 items")
                onNodeWithText("2 new · 1 replaced").assertIsDisplayed()
                screenshot("import-pack-end")
                onNodeWithText("Import 7 items").performClick()
                waitForIdle()
                onNodeWithText("Replace content?").assertIsDisplayed()
                onNodeWithText("3 things in the app are replaced", substring = true).assertIsDisplayed()
                assertEquals(emptyList(), x.importer.imported)
                screenshot("import-confirm")
                onNodeWithText("Cancel").performClick()
                waitForIdle()
                assertEquals(emptyList(), x.importer.imported)
                onNodeWithText("Import 7 items").performClick()
                waitForIdle()
                onNodeWithText("Replace").performClick()
                advance(this@runTest, 0.seconds)
                assertEquals(1, x.importer.imported.size)
                onNodeWithText("Imported 7 items").assertIsDisplayed()
            }
        }

    @Test
    fun `a pack with problems lists each with its file and line and offers no import`() =
        runTest {
            val x = fixture()
            runPhoneUiTest {
                importScreen(x, this@runTest)
                choose(x, this@runTest, broken)
                onNodeWithText("4 problems: nothing can be imported").assertIsDisplayed()
                onNodeWithText("routines/home-a.md:9: there is no exercise with the id goblet-sqat").assertIsDisplayed()
                onNodeWithText("Import 7 items").assertDoesNotExist()
                screenshot("import-problems")
                choose(x, this@runTest, fresh, button = "Choose another file")
                onNodeWithText("Import 1 item").assertIsDisplayed()
            }
        }

    @Test
    fun `a shared file opens the import screen on its preview`() =
        runTest {
            val x = fixture()
            x.importer.previews[pack.file] = pack
            val id = x.inbox.offer(OpenedDocument.File(pack.file, ByteArray(0)))
            runPhoneUiTest {
                importScreen(x, this@runTest, share = id)
                advance(this@runTest, 0.seconds)
                onNodeWithText("my-routines.zip").assertIsDisplayed()
                onNodeWithText("Home A").assertIsDisplayed()
            }
        }

    @Test
    fun `the import preview fits a small phone`() =
        runTest {
            val x = fixture()
            runPhoneUiTest(widthDp = 360, heightDp = 780) {
                importScreen(x, this@runTest)
                choose(x, this@runTest, pack)
                screenshot("import-pack-360")
                scrollTo("Import 7 items")
                onNodeWithText("Import 7 items").assertIsDisplayed()
                screenshot("import-pack-end-360")
            }
        }

    private fun SkikoComposeUiTest.dataScreen(
        x: Fixture,
        scope: TestScope,
    ) {
        val vm =
            DataViewModel(
                x.backups,
                x.documents,
                TestClock(scope.testScheduler),
                TimeZone.UTC,
                scope.backgroundScope,
            ).clearedAfterTest()
        setScreen { DataRoute(onBack = {}, viewModel = vm) }
        waitForIdle()
    }

    @Test
    fun `Backup and data exports through the save dialog and says so`() =
        runTest {
            val x = fixture()
            runPhoneUiTest {
                dataScreen(x, this@runTest)
                onNodeWithText("Everything in the app as one .zip file").assertIsDisplayed()
                onNodeWithText("Workouts, recovery and practice logs, body weight").assertIsDisplayed()
                screenshot("data")
                onNodeWithText("Export backup").performClick()
                advance(this@runTest, 0.seconds)
                assertEquals(listOf("fit-backup-2026-11-09.zip"), x.documents.saved.map { it.first })
                onNodeWithText("Backup saved").assertIsDisplayed()
                screenshot("data-exported")
            }
        }

    @Test
    fun `a backup that couldn't be written says so`() =
        runTest {
            val x = fixture()
            x.documents.saving = SaveResult.Failed
            runPhoneUiTest {
                dataScreen(x, this@runTest)
                onNodeWithText("Export backup").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Couldn't save the backup").assertIsDisplayed()
                onNodeWithText("Backup saved").assertDoesNotExist()
                screenshot("data-export-failed")
            }
        }

    @Test
    fun `Restore reads the picked file, asks with the backup's date, and restores on confirm`() =
        runTest {
            val x = fixture()
            runPhoneUiTest {
                dataScreen(x, this@runTest)
                x.documents.next = OpenedDocument.File("fit-backup.zip", "backup 2026-10-31T08:00:00Z".encodeToByteArray())
                onNodeWithText("Restore backup").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Restore this backup?").assertIsDisplayed()
                onNodeWithText("31 Oct 2026", substring = true).assertIsDisplayed()
                assertEquals(emptyList(), x.backups.calls)
                screenshot("data-restore-confirm")
                onNodeWithText("Restore").performClick()
                advance(this@runTest, 0.seconds)
                assertEquals(listOf("restore 2026-10-31T08:00:00Z"), x.backups.calls)
                onNodeWithText("Backup restored").assertIsDisplayed()
            }
        }

    @Test
    fun `a file that isn't a backup says nothing changed, without a confirmation`() =
        runTest {
            val x = fixture()
            runPhoneUiTest {
                dataScreen(x, this@runTest)
                x.documents.next = OpenedDocument.File("photo.jpg", "jpeg".encodeToByteArray())
                onNodeWithText("Restore backup").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("That file isn't a Fit backup, or it's damaged. Nothing changed.").assertIsDisplayed()
                onNodeWithText("Restore this backup?").assertDoesNotExist()
                screenshot("data-not-a-backup")
            }
        }

    @Test
    fun `each wipe says what it deletes and happens only on confirm`() =
        runTest {
            val x = fixture()
            runPhoneUiTest {
                dataScreen(x, this@runTest)
                onNodeWithText("Wipe history").performClick()
                waitForIdle()
                onNodeWithText("Wipe history?").assertIsDisplayed()
                screenshot("data-wipe-history-confirm")
                onNodeWithText("Cancel").performClick()
                waitForIdle()
                assertEquals(emptyList(), x.backups.calls)

                onNodeWithText("Wipe everything").performClick()
                waitForIdle()
                onNodeWithText("Wipe everything?").assertIsDisplayed()
                onNodeWithText("the app starts again with its built-in content", substring = true).assertIsDisplayed()
                screenshot("data-wipe-everything-confirm")
                onNodeWithText("Wipe").performClick()
                advance(this@runTest, 0.seconds)
                assertEquals(listOf("wipe everything"), x.backups.calls)
                onNodeWithText("Everything wiped").assertIsDisplayed()
            }
        }

    @Test
    fun `Backup and data fits a small phone`() =
        runTest {
            val x = fixture()
            runPhoneUiTest(widthDp = 360, heightDp = 780) {
                dataScreen(x, this@runTest)
                onNodeWithText("Wipe everything").assertIsDisplayed()
                screenshot("data-360")
                onNodeWithText("Wipe everything").performClick()
                waitForIdle()
                screenshot("data-wipe-everything-confirm-360")
            }
        }
}
