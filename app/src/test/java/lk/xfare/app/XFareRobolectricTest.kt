package lk.xfare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import lk.xfare.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XFareRobolectricTest {

    @Test
    fun appNameMatchesXFareLK() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("X FareLK", appName)
    }
}
