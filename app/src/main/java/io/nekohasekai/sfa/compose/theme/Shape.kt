package io.nekohasekai.sfa.compose.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** The plain UI's shapes: one 12 dp corner for every surface, round controls stay round. */
object AppShape {
    private val corner = 12.dp

    val none: Shape = RectangleShape

    /** Cards, panels, list groups, dialogs and sheets. */
    val card: Shape = RoundedCornerShape(corner)

    /** Round controls: icon buttons, chips, switches and indicators. */
    val full: Shape = CircleShape

    /** A row of a grouped list: only the group's first and last rows take the corner. */
    fun vertical(top: Boolean, bottom: Boolean): Shape =
        RoundedCornerShape(if (top) corner else 0.dp, if (top) corner else 0.dp, if (bottom) corner else 0.dp, if (bottom) corner else 0.dp)
}
