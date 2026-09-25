package com.opencode.android.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opencode.android.R
import com.opencode.android.domain.QuestionItem
import com.opencode.android.domain.QuestionOption
import com.opencode.android.domain.SessionQuestion
import com.opencode.android.ui.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The question reply protocol is `answers: string[][]` — one answer list per
 * question. This guards the multi-question card: selecting one option in each
 * question and submitting must report BOTH answers, in question order.
 */
@RunWith(AndroidJUnit4::class)
class QuestionRequestCardUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val submitLabel: String =
        ApplicationProvider.getApplicationContext<Context>().getString(R.string.submit)

    private val question = SessionQuestion(
        id = "req_1",
        questions = listOf(
            QuestionItem(
                question = "Which colour?",
                options = listOf(QuestionOption("Red"), QuestionOption("Blue")),
            ),
            QuestionItem(
                question = "Which sizes?",
                multiple = true,
                options = listOf(QuestionOption("Small"), QuestionOption("Large")),
            ),
        ),
    )

    @Test
    fun submitsOneAnswerListPerQuestion() {
        var answers: List<List<String>>? = null
        rule.setContent {
            OpenCodeTheme {
                QuestionRequestCard(
                    question = question,
                    onAnswer = { _, a -> answers = a },
                    onReject = {},
                )
            }
        }

        rule.onNodeWithText("Red").performClick()
        rule.onNodeWithText("Small").performClick()
        rule.onNodeWithText("Large").performClick()
        rule.onNodeWithText(submitLabel).performClick()

        assertEquals(listOf(listOf("Red"), listOf("Small", "Large")), answers)
    }
}
