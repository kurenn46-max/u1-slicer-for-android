package com.u1.slicer.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.MainActivity
import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NightPrintAndroid16UiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    private fun launch(file: File, useA1Mini: Boolean = false, assertions: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val printer = if (useA1Mini) {
            Printer("qa", "Offline A1 mini fixture", PrinterKind.BAMBU_LAN,
                bambu = BambuConfig("127.0.0.1", "00000000", "QA", BambuModel.A1_MINI))
        } else {
            Printer("qa", "Offline U1 fixture")
        }
        runBlocking { PrintersRepository(app).replace(PrintersConfig(listOf(printer), printer.id)) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW, uri, app, MainActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // API 36 displayed the activity and loaded the model while ActivityScenario's
        // legacy lifecycle monitor stayed at PRE_ON_CREATE. Observe the screen itself.
        app.startActivity(intent)
        assertions()
    }

    private fun asset(name: String): File = File(app.cacheDir, name).also { file ->
        instrumentation.context.assets.open(name).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }

    private fun job(material: String, nozzle: Int, bed: Int): File =
        File(app.cacheDir, "phase3-$material.npjob").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("model.stl"))
                instrumentation.context.assets.open("tetrahedron.stl").use { it.copyTo(zip) }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("settings.txt"))
                zip.write("""
                    NIGHTPRINT-SETTINGS/V1
                    printer=BAMBU_A1_MINI
                    material=$material
                    nozzle_c=$nozzle
                    bed_c=$bed
                    print_mm_s=30
                    travel_mm_s=150
                    first_layer_mm_s=20
                    retract_mm=0.5
                    retract_mm_s=25
                    layer_mm=0.20
                    wall_count=2
                    infill_percent=15
                    infill_mm_s=25
                    support=false
                    brim_mm=0
                    color_hex=#000000
                """.trimIndent().toByteArray())
                zip.closeEntry()
            }
        }

    private fun await(text: String, timeoutMs: Long = 45_000, scrollIntoView: Boolean = false) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        val node = compose.onNodeWithText(text, substring = true)
        if (scrollIntoView) node.performScrollTo()
        node.assertIsDisplayed()
    }

    @Test fun stlOpensIntoPrepareScreen() {
        launch(asset("tetrahedron.stl")) { await("Slice Model") }
    }

    @Test fun threeMfOpensIntoPrepareScreen() {
        launch(asset("calib-cube-10-dual-colour-merged.3mf")) { await("Slice Model") }
    }

    @Test fun tpuJobAppliesSettingsAndCanSliceFromPrepareScreen() {
        launch(job("TPU", 225, 50), useA1Mini = true) {
            await("印刷ジョブを読み込みました")
            await("材料: TPU")
            await("ノズル: 225℃・ベッド: 50℃")
            compose.onNodeWithText("閉じる").performClick()
            await("Slice Model")
            compose.onNodeWithText("Slice Model").performClick()
            await("NIGHT PRINT - 開始温度の確認", 120_000)
            compose.onNodeWithText("温度を確認して承認").performClick()
            await("Save", 120_000)
            await("Share")
            await("Slice Summary", 120_000, scrollIntoView = true)
        }
    }

    @Test fun petgJobShowsPetgAndItsOwnTemperatures() {
        launch(job("PETG", 235, 70), useA1Mini = true) {
            await("印刷ジョブを読み込みました")
            await("材料: PETG")
            await("ノズル: 235℃・ベッド: 70℃")
            compose.onNodeWithText("閉じる").performClick()
            await("Slice Model")
        }
    }

    @Test fun invalidTemperatureShowsJapaneseError() {
        launch(job("TPU", 275, 50), useA1Mini = true) {
            await("印刷ジョブを開けません")
            await("設定の形式・範囲が正しくありません")
            compose.onNodeWithText("閉じる").performClick()
            await("Prepare")
        }
    }

    @Test fun corruptPackageCanBeDismissedWithoutFreezing() {
        val file = File(app.cacheDir, "broken.npjob").apply { writeText("invalid zip") }
        launch(file) {
            await("印刷ジョブを開けません")
            compose.onNodeWithText("閉じる").performClick()
            await("Prepare")
        }
    }

    @Test fun wrongPrinterIsRejectedBeforeModelLoad() {
        launch(job("TPU", 225, 50)) {
            await("印刷ジョブを開けません")
            await("プリンター画面でBambu Lab A1 miniを選択")
            compose.onNodeWithText("閉じる").performClick()
            compose.onNodeWithText("Slice Model").assertDoesNotExist()
        }
    }
}
