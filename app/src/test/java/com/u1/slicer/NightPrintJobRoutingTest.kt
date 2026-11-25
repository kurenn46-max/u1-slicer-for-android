package com.u1.slicer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightPrintJobRoutingTest {
    @Test fun routesNpjobFilesToDedicatedParser() {
        assertTrue(isNightPrintJobFilename("NIGHT_PRINT_AyuRod.npjob"))
        assertTrue(isNightPrintJobFilename("MODEL.NPJOB"))
        assertTrue(isNightPrintJobFilename(" model.npjob "))
    }

    @Test fun normalModelTypesContinueOn3dModelPath() {
        assertFalse(isNightPrintJobFilename("model.3mf"))
        assertFalse(isNightPrintJobFilename("model.stl"))
        assertFalse(isNightPrintJobFilename("model.npjob.stl"))
        assertFalse(isNightPrintJobFilename(null))
        assertFalse(isNightPrintJobFilename(""))
    }
}
