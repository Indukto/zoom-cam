package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("ZoomBox Camera", appName)
  }

  @Test
  fun test_viewmodel_initial_states() {
    val viewModel = CameraViewModel()
    assertFalse(viewModel.showGridLines.value)
    assertEquals("4:3", viewModel.aspectRatio.value)
  }

  @Test
  fun test_viewmodel_toggle_grid_lines() {
    val viewModel = CameraViewModel()
    assertFalse(viewModel.showGridLines.value)
    viewModel.toggleGridLines()
    assertTrue(viewModel.showGridLines.value)
    viewModel.toggleGridLines()
    assertFalse(viewModel.showGridLines.value)
  }

  @Test
  fun test_viewmodel_set_aspect_ratio() {
    val viewModel = CameraViewModel()
    assertEquals("4:3", viewModel.aspectRatio.value)
    viewModel.setAspectRatio("1:1")
    assertEquals("1:1", viewModel.aspectRatio.value)
    viewModel.setAspectRatio("invalid")
    assertEquals("1:1", viewModel.aspectRatio.value) // Should ignore invalid value
    viewModel.setAspectRatio("4:3")
    assertEquals("4:3", viewModel.aspectRatio.value)
  }

  @Test
  fun test_ui_settings_menu_interaction() {
    val viewModel = CameraViewModel()

    composeTestRule.setContent {
      CameraActiveScreen(viewModel = viewModel)
    }

    // Verify initial state
    assertFalse(viewModel.showGridLines.value)
    assertEquals("4:3", viewModel.aspectRatio.value)

    // Find and click the settings button to show the menu
    val settingsBtn = composeTestRule.onNodeWithTag("settings_menu_button")
    settingsBtn.assertExists()
    settingsBtn.performClick()

    // Find and click the grid lines item to toggle it
    val gridLinesItem = composeTestRule.onNodeWithTag("menu_item_grid_lines")
    gridLinesItem.assertExists()
    gridLinesItem.performClick()

    // State should now be updated (Grid lines enabled)
    assertTrue(viewModel.showGridLines.value)

    // Open settings menu again
    settingsBtn.performClick()

    // Find and click the aspect ratio item to toggle it to 1:1
    val aspectRatioItem = composeTestRule.onNodeWithTag("menu_item_aspect_ratio")
    aspectRatioItem.assertExists()
    aspectRatioItem.performClick()

    // State should now be updated (Aspect ratio is 1:1)
    assertEquals("1:1", viewModel.aspectRatio.value)
  }
}
