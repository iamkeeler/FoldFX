package com.keeler.foldfx.optics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldOpticsTest {
    @Test fun flatPageHasIdentityProjection() {
        for (x in 0..1000 step 10) {
            for (left in listOf(true, false)) {
                assertEquals(x.toFloat(), FoldOptics.sampleX(x.toFloat(), 1000f, 180f, left), 0.0001f)
            }
        }
    }
    @Test fun creaseAndStationaryHalfDoNotMove() {
        for (angle in 0..180 step 5) {
            assertEquals(500f, FoldOptics.sampleX(500f, 1000f, angle.toFloat(), true), 0f)
            assertEquals(800f, FoldOptics.sampleX(800f, 1000f, angle.toFloat(), true), 0f)
            assertEquals(200f, FoldOptics.sampleX(200f, 1000f, angle.toFloat(), false), 0f)
        }
    }
    @Test fun movingPaneLooksOntoAFixedImagePlane() {
        // A mark 400px from the crease on a 60° tilted pane projects much
        // closer to the crease in the fixed image, countering physical rotation.
        val projected = FoldOptics.sampleX(100f, 1000f, 120f, true)
        assertTrue(projected > 250f && projected < 300f)
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
