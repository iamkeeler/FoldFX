package com.keeler.foldfx.optics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldOpticsTest {
    @Test fun flatPageHasNoOpticalSpread() {
        assertEquals(0f, FoldOptics.diffusionStrength(180f), 0f)
        assertEquals(0f, FoldOptics.diffusionStrength(200f), 0f)
    }
    @Test fun diffusionIncreasesAsInnerScreenCloses() {
        var previous = 0f
        for (angle in 180 downTo 0 step 5) {
            val current = FoldOptics.diffusionStrength(angle.toFloat())
            assertTrue(current >= previous && current <= 1f)
            previous = current
        }
    }
    @Test fun invalidAngleCannotLeaveOpticsActive() {
        assertEquals(0f, FoldOptics.diffusionStrength(Float.NaN), 0f)
        assertEquals(0f, FoldOptics.diffusionStrength(Float.POSITIVE_INFINITY), 0f)
    }
    @Test fun diffusionIsConcentratedAtTheFreeEdge() {
        assertEquals(0f, FoldOptics.edgeWeight(0f), 0f)
        assertEquals(0.125f, FoldOptics.edgeWeight(0.5f), 0f)
        assertEquals(1f, FoldOptics.edgeWeight(1f), 0f)
    }
    @Test fun panelIdentificationSurvivesRotation() {
        assertEquals(FoldSurface.INNER, FoldSurface.fromSize(2076, 2152))
        assertEquals(FoldSurface.INNER, FoldSurface.fromSize(2152, 2076))
        assertEquals(FoldSurface.COVER, FoldSurface.fromSize(1080, 2364))
        assertEquals(FoldSurface.COVER, FoldSurface.fromSize(2364, 1080))
    }
}
