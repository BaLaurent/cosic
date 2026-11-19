package com.octopusman.cosic.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Marks a control for UI automation: [tag] becomes its resource id (see `testTagsAsResourceId`),
 * and [description] doubles as its content description so uiautomator can find it without ids.
 */
fun Modifier.tagged(tag: String, description: String): Modifier =
    testTag(tag).semantics { contentDescription = description }
