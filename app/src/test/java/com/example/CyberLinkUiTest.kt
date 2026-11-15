package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CyberLinkUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `navigate across all primary tabs including Cyber Map`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ble_scan_toggle_btn").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_chats").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_calls").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_map").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("map_tab_CYBER_MAP").assertIsDisplayed()
        composeTestRule.onNodeWithTag("map_tab_KARTENVERWALTUNG").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("saf_import_map_btn").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_system").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("battery_mode_ULTRA").performScrollTo().assertIsDisplayed()
    }
}
