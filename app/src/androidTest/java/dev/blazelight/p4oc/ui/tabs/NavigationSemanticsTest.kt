package dev.blazelight.p4oc.ui.tabs

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.ui.theme.PocketCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val home = TabInstance.home()
    private val work = TabInstance(TabState(id = "work"))

    @Composable
    private fun tabBarUnderTest(
        onTabClick: (String) -> Unit = {},
        onTabClose: (String) -> Unit = {},
    ) {
        PocketCodeTheme {
            TabBar(
                tabs = listOf(home, work),
                activeTabId = work.id,
                tabTitles = mapOf(home.id to "Home", work.id to "Files"),
                tabIcons = mapOf(home.id to getIconForTab(home), work.id to getIconForTab(work)),
                tabConnectionStates = emptyMap(),
                onTabClick = onTabClick,
                onTabClose = onTabClose,
                onAddClick = {},
            )
        }
    }

    @Test
    fun activeTabExposesSelectedSemantics() {
        composeRule.setContent { tabBarUnderTest() }

        // Root test tags live on the tab indicator root Column.
        composeRule.onNodeWithTag("tab_home").assertExists()
        composeRule.onNodeWithTag("work_tab_work").assertExists()

        // Selected semantics live on each tab's internal selectable row.
        composeRule.onNodeWithTag("tab_home_body").assertIsNotSelected()
        composeRule.onNodeWithTag("work_tab_work_body").assertIsSelected()
        composeRule.onNodeWithTag("tab_home_body")
            .assertContentDescriptionEquals("Home")
        composeRule.onNodeWithTag("work_tab_work_body")
            .assertContentDescriptionEquals("Files")
    }

    @Test
    fun onlyCloseableTabsExposeACloseAction() {
        composeRule.setContent { tabBarUnderTest() }

        composeRule.onNodeWithTag("work_tab_work_close")
            .assertExists()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertContentDescriptionEquals(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_close_tab)
            )
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("work_tab_${home.id}_close").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun closeActionInvokesOnlyClose() {
        val clicked = mutableListOf<String>()
        val closed = mutableListOf<String>()
        composeRule.setContent {
            tabBarUnderTest(onTabClick = { clicked += it }, onTabClose = { closed += it })
        }

        composeRule.onNodeWithTag("work_tab_work_close").performClick()

        assertEquals(listOf("work"), closed)
        assertEquals(emptyList<String>(), clicked)
    }

    @Test
    fun tabBodyInvokesOnlySelection() {
        val clicked = mutableListOf<String>()
        val closed = mutableListOf<String>()
        composeRule.setContent {
            tabBarUnderTest(onTabClick = { clicked += it }, onTabClose = { closed += it })
        }

        composeRule.onNodeWithTag("work_tab_work_body").performClick()

        assertEquals(listOf("work"), clicked)
        assertEquals(emptyList<String>(), closed)
    }
}
