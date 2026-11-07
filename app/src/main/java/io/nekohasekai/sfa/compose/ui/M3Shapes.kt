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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Material 3 Expressive's MaterialShapes polygons, ported from androidx
 * (compose/material3/MaterialShapes.kt) because Material3 1.4.0 keeps them
 * behind an internal opt-in. Only the shapes the null-status art uses are
 * exposed; each is normalized to the unit square like the original.
 */
@Suppress("MagicNumber", "PrimitiveInCollection")
internal object M3Shapes {
    val Circle: RoundedPolygon by lazy { RoundedPolygon.circle(numVertices = 10).normalized() }
    val Cookie12Sided: RoundedPolygon by lazy { cookie12().normalized() }
    val Cookie9Sided: RoundedPolygon by lazy { cookie9().normalized() }
    val Sunny: RoundedPolygon by lazy { sunny().normalized() }
    val Cookie7Sided: RoundedPolygon by lazy { cookie7().normalized() }
    val Cookie6Sided: RoundedPolygon by lazy { cookie6().normalized() }
    val Clover8Leaf: RoundedPolygon by lazy { clover8().normalized() }
    val Cookie4Sided: RoundedPolygon by lazy { cookie4().normalized() }
    val SoftBurst: RoundedPolygon by lazy { softBurst().normalized() }
    val Puffy: RoundedPolygon by lazy { puffy().normalized() }
    val Pentagon: RoundedPolygon by lazy { pentagon().normalized() }
    val Gem: RoundedPolygon by lazy { gem().normalized() }
    val ClamShell: RoundedPolygon by lazy { clamShell().normalized() }
    val Clover4Leaf: RoundedPolygon by lazy { clover4().normalized() }
    val VerySunny: RoundedPolygon by lazy { verySunny().normalized() }
    val PuffyDiamond: RoundedPolygon by lazy { puffyDiamond().normalized() }
    val Flower: RoundedPolygon by lazy { flower().normalized() }

        private val cornerRound15 = CornerRounding(radius = .15f)
        private val cornerRound50 = CornerRounding(radius = .5f)

        private val rotateNeg90 = Matrix().apply { rotateZ(-90f) }

        private fun circle(numVertices: Int = 10): RoundedPolygon {
            return RoundedPolygon.circle(numVertices = numVertices)
        }

        private fun clamShell(): RoundedPolygon {
            return customPolygon(
                listOf(
                    PointNRound(Offset(0.171f, 0.841f), CornerRounding(0.159f)),
                    PointNRound(Offset(-0.020f, 0.500f), CornerRounding(0.140f)),
                    PointNRound(Offset(0.170f, 0.159f), CornerRounding(0.159f)),
                ),
                2,
            )
        }

        private fun pentagon(): RoundedPolygon {
            return customPolygon(
                listOf(
                    PointNRound(Offset(0.500f, -0.009f), CornerRounding(0.172f)),
                    PointNRound(Offset(1.030f, 0.365f), CornerRounding(0.164f)),
                    PointNRound(Offset(0.828f, 0.970f), CornerRounding(0.169f)),
                ),
                reps = 1,
                mirroring = true,
            )
        }

        private fun gem(): RoundedPolygon {
            return customPolygon(
                listOf(
                    PointNRound(Offset(0.499f, 1.023f), CornerRounding(0.241f, 0.778f)),
                    PointNRound(Offset(-0.005f, 0.792f), CornerRounding(0.208f)),
                    PointNRound(Offset(0.073f, 0.258f), CornerRounding(0.228f)),
                    PointNRound(Offset(0.433f, -0.000f), CornerRounding(0.491f)),
                ),
                1,
                mirroring = true,
            )
        }

        private fun sunny(): RoundedPolygon {
            return RoundedPolygon.star(
                numVerticesPerRadius = 8,
                innerRadius = .8f,
                rounding = cornerRound15,
            )
        }

        private fun verySunny(): RoundedPolygon {
            return customPolygon(
                listOf(
                    PointNRound(Offset(0.500f, 1.080f), CornerRounding(0.085f)),
                    PointNRound(Offset(0.358f, 0.843f), CornerRounding(0.085f)),
                ),
                8,
            )
        }

        private fun cookie4(): RoundedPolygon {
            return customPolygon(
                listOf(
                    PointNRound(Offset(1.237f, 1.236f), CornerRounding(0.258f)),
                    PointNRound(Offset(0.500f, 0.918f), CornerRounding(0.233f)),
                ),
                4,
            )
        }

        private fun cookie6(): RoundedPolygon {
            // 6-point cookie
            return customPolygon(
                listOf(
                    PointNRound(Offset(0.723f, 0.884f), CornerRounding(0.394f)),
                    PointNRound(Offset(0.500f, 1.099f), CornerRounding(0.398f)),
                ),
                6,
            )
        }

        private fun cookie7(): RoundedPolygon {
            // 7-point cookie
            return RoundedPolygon.star(
                    numVerticesPerRadius = 7,
                    innerRadius = .75f,
                    rounding = cornerRound50,
                )
                .transformed(rotateNeg90)
        }

        private fun cookie9(): RoundedPolygon {
            return RoundedPolygon.star(
                    numVerticesPerRadius = 9,
                    innerRadius = .8f,
                    rounding = cornerRound50,
                )
                .transformed(rotateNeg90)
        }

        private fun cookie12(): RoundedPolygon {
            return RoundedPolygon.star(
                    numVerticesPerRadius = 12,
                    innerRadius = .8f,
                    rounding = cornerRound50,
                )
                .transformed(rotateNeg90)
        }

        private fun clover4(): RoundedPolygon {
            return customPolygon(
                listOf(
                    PointNRound(Offset(0.500f, 0.074f)),
                    PointNRound(Offset(0.725f, -0.099f), CornerRounding(0.476f)),
                ),
                reps = 4,
                mirroring = true,
            )
        }

        private fun clover8(): RoundedPolygon {
            return customPolygon(
                listOf(
                    PointNRound(Offset(0.500f, 0.036f)),
                    PointNRound(Offset(0.758f, -0.101f), CornerRounding(0.209f)),
                ),
                reps = 8,
            )
        }

        private fun softBurst(): RoundedPolygon {
            return customPolygon(
                listOf(
                    PointNRound(Offset(0.193f, 0.277f), CornerRounding(0.053f)),
                    PointNRound(Offset(0.176f, 0.055f), CornerRounding(0.053f)),
                ),
                reps = 10,
            )
        }

        private fun flower(): RoundedPolygon {
            return customPolygon(
                listOf(
                    PointNRound(Offset(0.370f, 0.187f)),
                    PointNRound(Offset(0.416f, 0.049f), CornerRounding(0.381f)),
                    PointNRound(Offset(0.479f, 0.001f), CornerRounding(0.095f)),
                ),
                reps = 8,
                mirroring = true,
            )
        }

        private fun puffy(): RoundedPolygon {
            val m = Matrix().apply { scale(1f, 0.742f) }
            return customPolygon(
                    listOf(
                        PointNRound(Offset(0.500f, 0.053f)),
                        PointNRound(Offset(0.545f, -0.040f), CornerRounding(0.405f)),
                        PointNRound(Offset(0.670f, -0.035f), CornerRounding(0.426f)),
                        PointNRound(Offset(0.717f, 0.066f), CornerRounding(0.574f)),
                        PointNRound(Offset(0.722f, 0.128f)),
                        PointNRound(Offset(0.777f, 0.002f), CornerRounding(0.360f)),
                        PointNRound(Offset(0.914f, 0.149f), CornerRounding(0.660f)),
                        PointNRound(Offset(0.926f, 0.289f), CornerRounding(0.660f)),
                        PointNRound(Offset(0.881f, 0.346f)),
                        PointNRound(Offset(0.940f, 0.344f), CornerRounding(0.126f)),
                        PointNRound(Offset(1.003f, 0.437f), CornerRounding(0.255f)),
                    ),
                    reps = 2,
                    mirroring = true,
                )
                .transformed(m)
        }

        private fun puffyDiamond(): RoundedPolygon {
            return customPolygon(
                listOf(
                    PointNRound(Offset(0.870f, 0.130f), CornerRounding(0.146f)),
                    PointNRound(Offset(0.818f, 0.357f)),
                    PointNRound(Offset(1.000f, 0.332f), CornerRounding(0.853f)),
                ),
                reps = 4,
                mirroring = true,
            )
        }

        @Suppress("ListIterator", "PrimitiveInCollection")
        @Suppress("ListIterator", "PrimitiveInCollection")
        private data class PointNRound(
            val o: Offset,
            val r: CornerRounding = CornerRounding.Unrounded,
        )

        @Suppress("PrimitiveInCollection")
        private fun doRepeat(
            points: List<PointNRound>,
            reps: Int,
            center: Offset,
            mirroring: Boolean,
        ) =
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
                                val a =
                                    (sectionAngle * it +
                                            if (it % 2 == 0) angles[i]
                                            else sectionAngle - angles[i] + 2 * angles[0])
                                        .toRadians()
                                val finalPoint = Offset(cos(a), sin(a)) * distances[i] + center
                                add(PointNRound(finalPoint, points[i].r))
                            }
                        }
                    }
                }
            } else {
                points.size.let { np ->
                    (0 until np * reps).map {
                        val point = points[it % np].o.rotateDegrees((it / np) * 360f / reps, center)
                        PointNRound(point, points[it % np].r)
                    }
                }
            }

        private fun Offset.rotateDegrees(angle: Float, center: Offset = Offset.Zero) =
            (angle.toRadians()).let { a ->
                val off = this - center
                Offset(off.x * cos(a) - off.y * sin(a), off.x * sin(a) + off.y * cos(a)) + center
            }

        private fun Float.toRadians(): Float {
            return this / 360f * 2 * PI.toFloat()
        }

        private fun Offset.angleDegrees() = atan2(y, x) * 180f / PI.toFloat()

        private fun customPolygon(
            pnr: List<PointNRound>,
            reps: Int,
            center: Offset = Offset(0.5f, 0.5f),
            mirroring: Boolean = false,
        ): RoundedPolygon {
            val actualPoints = doRepeat(pnr, reps, center, mirroring)
            return RoundedPolygon(
                vertices =
                    FloatArray(actualPoints.size * 2) { ix ->
                        actualPoints[ix / 2].o.let { if (ix % 2 == 0) it.x else it.y }
                    },
                perVertexRounding = buildList { for (p in actualPoints) add(p.r) },
                centerX = center.x,
                centerY = center.y,
            )
        }

    private fun RoundedPolygon.transformed(matrix: Matrix): RoundedPolygon = transformed { x, y ->
        val o = matrix.map(Offset(x, y))
        TransformResult(o.x, o.y)
    }
}
