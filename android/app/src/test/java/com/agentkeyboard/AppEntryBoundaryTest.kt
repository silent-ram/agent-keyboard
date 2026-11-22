package com.agentkeyboard

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.Const

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppEntryBoundaryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `product support links point to the maintained repository`() {
        assertEquals("silent-ram", Const.maintainer)
        val repo = "https://github.com/silent-ram/agent-keyboard"
        assertEquals(repo, Const.githubRepo)
        assertEquals("$repo/issues", Const.issuesUrl)
        assertTrue(Const.privacyPolicyUrl.startsWith("$repo/"))
        assertTrue(Const.faqUrl.startsWith("$repo/"))
    }

    @Test
    fun `manifest registers protected keyboard service and no share receiver`() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_META_DATA,
        )

        val service = packageInfo.services.orEmpty().singleOrNull {
            it.name == "org.fcitx.fcitx5.android.input.FcitxInputMethodService"
        }
        assertNotNull("IME service must be registered", service)
        assertTrue("system must be able to bind the IME", service!!.exported)
        assertEquals("android.permission.BIND_INPUT_METHOD", service.permission)
        assertEquals(R.xml.input_method, service.metaData?.getInt("android.view.im"))

        assertFalse(packageInfo.activities.orEmpty().any { it.name.endsWith("ShareImportActivity") })
    }
}
