package com.keeler.foldfx.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldProgressTest {
    @Test fun settledEndsAndSensorNoiseStaySharp() {
        for (angle in listOf(-10f, 0f, 3f, 6f, 174f, 177f, 180f, 190f)) {
            assertEquals("angle=$angle", 0f, FoldProgress.fromAngle(angle), 0f)
        }
    }

    @Test fun missingOrInvalidSensorReadingsReleaseTheEffect() {
        for (angle in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, FoldProgress.fromAngle(angle), 0f)
        }
    }

    @Test fun diffusionIsAlreadyVisibleEarlyInTheFold() {
        assertTrue(FoldProgress.fromAngle(20f) > 0.15f)
        assertEquals(0.5f, FoldProgress.fromAngle(33f), 0.0001f)
    }

    @Test fun middlePosturesStayFullyDiffused() {
        for (angle in 60..120) assertEquals(1f, FoldProgress.fromAngle(angle.toFloat()), 0f)
    }

    @Test fun openingAndClosingUseTheSameContinuousCurve() {
        var previous = 0f
        for (step in 0..900) {
            val angle = step / 10f
            val progress = FoldProgress.fromAngle(angle)
            assertTrue(progress in 0f..1f)
            assertTrue("Defocus reversed at $angle", progress >= previous)
            assertEquals(progress, FoldProgress.fromAngle(180f - angle), 0.00001f)
            previous = progress
        }
    }

    @Test fun smallSensorStepsDoNotCreateLargeFocusJumps() {
        for (angle in 0 until 180) {
            val delta = FoldProgress.fromAngle(angle + 1f) - FoldProgress.fromAngle(angle.toFloat())
            assertTrue(kotlin.math.abs(delta) < 0.028f)
        }
    }
}
