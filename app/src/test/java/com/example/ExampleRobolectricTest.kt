package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Cyber Link", appName)
    }

    @Test
    fun `manifest has zero INTERNET and zero WAKE_LOCK and zero broad storage permissions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertFalse("INTERNET must not be declared", Manifest.permission.INTERNET in perms)
        assertFalse("WAKE_LOCK must not be declared", Manifest.permission.WAKE_LOCK in perms)
        assertFalse("READ_EXTERNAL_STORAGE must not be declared", Manifest.permission.READ_EXTERNAL_STORAGE in perms)
        assertFalse("WRITE_EXTERNAL_STORAGE must not be declared", Manifest.permission.WRITE_EXTERNAL_STORAGE in perms)
        assertFalse("READ_MEDIA_IMAGES must not be declared", "android.permission.READ_MEDIA_IMAGES" in perms)
    }
}
