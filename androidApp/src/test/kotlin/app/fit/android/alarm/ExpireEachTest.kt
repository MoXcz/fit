// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.alarm

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ExpireEachTest {
    @Test
    fun `one expiry failing doesn't skip the other`() =
        runBlocking {
            var ran = 0
            expireEach({ error("storage") }, { ran++ })
            assertEquals(1, ran)
        }

    @Test
    fun `one expiry that never returns doesn't hold up the other`() =
        runBlocking {
            var ran = 0
            withTimeoutOrNull(200.milliseconds) { expireEach({ awaitCancellation() }, { ran++ }) }
            assertEquals(1, ran)
        }
}
