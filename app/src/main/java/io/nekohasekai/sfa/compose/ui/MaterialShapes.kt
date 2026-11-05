/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.transformed
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The M3 Expressive shapes FlClash takes from material_new_shapes (a Dart
 * port of these definitions), from androidx.compose.material3.MaterialShapes;
 * only the ones the UI draws. Normalized to the unit square.
 */
object MaterialShapes {
    private val cornerRound50 = CornerRounding(0.5f)
    private val rotateNeg90 = android.graphics.Matrix().apply { setRotate(-90f) }

    val Circle: RoundedPolygon by lazy { RoundedPolygon.circle(numVertices = 10).normalized() }

    val Cookie6Sided: RoundedPolygon by lazy {
        customPolygon(
            listOf(
                PointNRound(Offset(0.723f, 0.884f), CornerRounding(0.394f)),
                PointNRound(Offset(0.500f, 1.099f), CornerRounding(0.398f)),
            ),
            6,
        ).normalized()
    }

    val Cookie9Sided: RoundedPolygon by lazy {
        RoundedPolygon.star(numVerticesPerRadius = 9, innerRadius = .8f, rounding = cornerRound50)
            .transformed(rotateNeg90).normalized()
    }

    val Cookie12Sided: RoundedPolygon by lazy {
        RoundedPolygon.star(numVerticesPerRadius = 12, innerRadius = .8f, rounding = cornerRound50)
            .transformed(rotateNeg90).normalized()
    }

    val Clover4Leaf: RoundedPolygon by lazy {
        customPolygon(
            listOf(
                PointNRound(Offset(0.500f, 0.074f)),
                PointNRound(Offset(0.725f, -0.099f), CornerRounding(0.476f)),
            ),
            reps = 4,
            mirroring = true,
        ).normalized()
    }

    private data class PointNRound(val o: Offset, val r: CornerRounding = CornerRounding.Unrounded)

    private fun doRepeat(points: List<PointNRound>, reps: Int, center: Offset, mirroring: Boolean): List<PointNRound> =
        if (mirroring) {
            buildList {
                val angles = points.map { (it.o - center).angleDegrees() }
                val distances = points.map { (it.o - center).getDistance() }
                val actualReps = reps * 2
                val sectionAngle = 360f / actualReps
                repeat(actualReps) {
                    points.indices.forEach { index ->
                        val i = if (it % 2 == 0) index else points.lastIndex - index
                        if (i > 0 || it % 2 == 0) {
                            val a = (
                                sectionAngle * it +
                                    if (it % 2 == 0) angles[i] else sectionAngle - angles[i] + 2 * angles[0]
                                ).toRadians()
                            val finalPoint = Offset(cos(a), sin(a)) * distances[i] + center
                            add(PointNRound(finalPoint, points[i].r))
                        }
                    }
                }
            }
        } else {
            val np = points.size
            (0 until np * reps).map {
                val point = points[it % np].o.rotateDegrees((it / np) * 360f / reps, center)
                PointNRound(point, points[it % np].r)
            }
        }

    private fun Offset.rotateDegrees(angle: Float, center: Offset = Offset.Zero): Offset {
        val a = angle.toRadians()
        val off = this - center
        return Offset(off.x * cos(a) - off.y * sin(a), off.x * sin(a) + off.y * cos(a)) + center
    }

    private fun Float.toRadians(): Float = this / 360f * 2 * PI.toFloat()

    private fun Offset.angleDegrees() = atan2(y, x) * 180f / PI.toFloat()

    private fun customPolygon(
        pnr: List<PointNRound>,
        reps: Int,
        center: Offset = Offset(0.5f, 0.5f),
        mirroring: Boolean = false,
    ): RoundedPolygon {
        val actualPoints = doRepeat(pnr, reps, center, mirroring)
        return RoundedPolygon(
            vertices = FloatArray(actualPoints.size * 2) { ix ->
                actualPoints[ix / 2].o.let { if (ix % 2 == 0) it.x else it.y }
            },
            perVertexRounding = actualPoints.map { it.r },
            centerX = center.x,
            centerY = center.y,
        )
    }
}

private val shapePaths = HashMap<RoundedPolygon, Path>()

/** shape_badge.dart's shapePathOf: the unit-square path of a polygon, cached. */
fun shapePathOf(shape: RoundedPolygon): Path = shapePaths.getOrPut(shape) { shape.toPath().asComposePath() }

fun morphPathOf(morph: Morph, progress: Float): Path = morph.toPath(progress, android.graphics.Path()).asComposePath()

/** Paints a unit-square [path] into this scope, turned by [rotation] radians about the center. */
fun DrawScope.drawUnitShape(path: Path, color: Color, rotation: Float = 0f, ring: Color? = null, ringWidth: Float = 0f, ringStandsOff: Boolean = false) {
    withTransform({
        rotate(Math.toDegrees(rotation.toDouble()).toFloat())
        scale(size.width, size.height, pivot = Offset.Zero)
    }) {
        drawPath(path, color)
    }
    if (ring != null && ringWidth > 0f) {
        // shape_badge.dart's strokeUnitShape: scales about the center to place the stroke.
        val strokeAlign = if (ringStandsOff) 1f + 2f * 1f / ringWidth else -1f
        val s = 1 + ringWidth * strokeAlign / size.width
        withTransform({
            rotate(Math.toDegrees(rotation.toDouble()).toFloat())
            scale(s, s)
            scale(size.width, size.height, pivot = Offset.Zero)
        }) {
            drawPath(path, ring, style = Stroke(width = ringWidth / size.width / s))
        }
    }
}

/** shape_badge.dart's ShapeBadge. */
@Composable
fun ShapeBadge(
    shape: RoundedPolygon,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    ring: Color? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val path = shapePathOf(shape)
    Box(modifier.requiredSize(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.requiredSize(size)) { drawUnitShape(path, color, rotation, ring, if (ring != null) 2.dp.toPx() else 0f) }
        content?.invoke()
    }
}
