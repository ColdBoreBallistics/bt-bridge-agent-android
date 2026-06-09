// Copyright (C) 2026 Jason M. Schwefel. All Rights Reserved.
package com.coldboreballisticsllc.btbridge.template

/**
 * A single parsed and converted field from a display template.
 * [display] mirrors the template's display flag — false fields are available for
 * expr calculations but are not shown in the UI.
 */
data class RenderedField(
    val id: String,
    val label: String,
    val value: String,
    val unit: String = "",
    val display: Boolean = true,
)

/** Warning attached to a field that could not be fully rendered. */
data class FieldWarning(
    val fieldId: String,
    val message: String,
)

/**
 * The complete output of rendering one BLE notification or read value
 * through a display template view.
 */
data class RenderedFrame(
    val charUuid: String,
    val view: String,
    val fields: List<RenderedField>,
    val warnings: List<FieldWarning>,
)
