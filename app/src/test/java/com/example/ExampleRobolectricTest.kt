package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `verify app name and bundled offline html asset`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("PrintStudio Pro", appName)

    val htmlContent = context.assets.open("pdf-print-studio.html").bufferedReader().use { it.readText() }
    assertTrue("Asset should contain window.receiveAndroidFile bridge", htmlContent.contains("window.receiveAndroidFile"))
  }
}
