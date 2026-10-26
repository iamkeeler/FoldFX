package com.keeler.foldfx.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldProgressTest {
    @Test fun settledEndsStaySharp() {
        for (angle in listOf(-10f, 0f, 178f, 180f, 190f)) {
            assertEquals("angle=$angle", 0f, FoldProgress.fromAngle(angle), 0f)
        }
    }
    @Test fun missingOrInvalidSensorReadingsReleaseTheEffect() {
        for (angle in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, FoldProgress.fromAngle(angle), 0f)
        }
    }
    @Test fun firstFiveDegreeReadingIsVisibleBeforeTheCoverHandoff() {
        assertTrue(FoldProgress.fromAngle(5f) > 0.35f)
        assertEquals(0.5f, FoldProgress.fromAngle(6f), 0.0001f)
    }
    @Test fun earlyAndMiddlePosturesStayDiffused() {
        for (angle in 12..90) assertEquals(1f, FoldProgress.fromAngle(angle.toFloat()), 0f)
    }
    @Test fun innerPageGraduallyClearsAsItOpens() {
        var previous = 1f
        for (step in 900..1800) {
            val progress = FoldProgress.fromAngle(step / 10f)
            assertTrue(progress in 0f..1f)
            assertTrue(progress <= previous)
            previous = progress
        }
    }
    @Test fun reversingDirectionRetracesTheSamePostures() {
        val opening = (0..180).map { FoldProgress.fromAngle(it.toFloat()) }
        val closing = (180 downTo 0).map { FoldProgress.fromAngle(it.toFloat()) }.reversed()
        assertEquals(opening, closing)
    }
}
