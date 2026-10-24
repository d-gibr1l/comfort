package com.comfort.app.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The swipe card's swipe offered to TalkBack as a "Remove" action on the card's row (see
 * [swipeRemoveAction]): what a screen reader user gets instead of the gesture. */
class SwipeRemoveActionTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theRowOffersRemove_andItRemovesLikeTheSwipe() {
        var removed = 0
        compose.setContent {
            SwipeToDeleteCard(onDelete = { removed++ }) {
                Text(
                    "A download",
                    modifier = Modifier.fillMaxWidth().swipeRemoveAction().clickable { }.padding(16.dp),
                )
            }
        }
        val row = compose.onNodeWithText("A download")
        row.assert(SemanticsMatcher("has a Remove action") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Remove" } == true
        })
        // What TalkBack does when "Remove" is picked from its actions menu.
        val action = row.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Remove" }
        compose.runOnUiThread { assertTrue(action.action()) }
        compose.waitForIdle()
        assertEquals(1, removed)
    }

    @Test
    fun aCardThatDoesntSwipe_offersNoRemove() {
        compose.setContent {
            SwipeToDeleteCard(onDelete = { }, enabled = false) {
                Text("The song playing", modifier = Modifier.swipeRemoveAction().clickable { })
            }
        }
        compose.onNodeWithText("The song playing").assert(SemanticsMatcher("has no custom actions") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions).isNullOrEmpty()
        })
    }
}
