package com.kopia.android

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
public open class SimpleRobolectricTest {
    @Test
    public open fun testSanity() {
        assertTrue(true)
    }
}
