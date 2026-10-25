package com.crosspaste.paste.plugin.type

import com.crosspaste.app.AppFileType
import com.crosspaste.image.DesktopImageHandler
import com.crosspaste.image.ImageHandler
import com.crosspaste.paste.DesktopReadTransferable
import com.crosspaste.paste.PasteCollector
import com.crosspaste.paste.PasteDataFlavor
import com.crosspaste.paste.toPasteDataFlavor
import com.crosspaste.path.UserDataPathProvider
import com.crosspaste.platform.Platform
import com.crosspaste.utils.getJsonUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import okio.Path.Companion.toOkioPath
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacosBitmapImageReadTest {

    // Guard against PasteItem/JsonUtils circular class initialization
    private val jsonUtils = getJsonUtils()

    private val macos = Platform(name = Platform.MACOS, arch = "aarch64", bitMode = 64, version = "27.0")

    private val windows = Platform(name = Platform.WINDOWS, arch = "amd64", bitMode = 64, version = "11")

    private val dir = Files.createTempDirectory("macos-bitmap-read").toOkioPath()

    private val written = mutableListOf<BufferedImage>()

    // Real decoding; the image the plugin would store is recorded instead of written
    private val imageHandler: ImageHandler<BufferedImage> =
        mockk {
            coEvery { readImage(any<Source>()) } coAnswers { DesktopImageHandler.readImage(firstArg<Source>()) }
            coEvery { writeImage(any(), any(), any()) } answers {
                written += firstArg<BufferedImage>()
                false
            }
        }

    private val userDataPathProvider: UserDataPathProvider =
        mockk {
            every { resolve(any(), any<AppFileType>()) } returns dir
            every { resolve(any(), any<String>(), any(), any()) } answers {
                firstArg<okio.Path>() / secondArg<String>()
            }
        }

    private fun plugin(platform: Platform) =
        DesktopImageTypePlugin(
            appInfo = mockk(relaxed = true),
            imageHandler = imageHandler,
            platform = platform,
            userDataPathProvider = userDataPathProvider,
        )

    private fun bitmap(
        width: Int,
        height: Int,
    ) = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
        for (y in 0 until height) {
            for (x in 0 until width) {
                setRGB(
                    x,
                    y,
                    (x * 255 / width shl 16) or (y * 255 / height shl 8),
                )
            }
        }
    }

    private fun encode(
        image: BufferedImage,
        format: String,
    ): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()

    /** What JBR hands out for `imageFlavor` on macOS: the NSImage in points, as its only variant by default. */
    private fun pointImage(
        width: Int,
        height: Int,
        vararg variants: BufferedImage,
    ): Image = BaseMultiResolutionImage(bitmap(width, height), *variants)

    /** A pasteboard snapshot as DesktopTransferableConsumer sees it. */
    private class Snapshot(
        private val data: Map<DataFlavor, Any>,
    ) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = data.keys.toTypedArray()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in data

        override fun getTransferData(flavor: DataFlavor): Any = data[flavor] ?: throw UnsupportedFlavorException(flavor)
    }

    private fun dataFlavorMap(snapshot: Snapshot): Map<String, List<PasteDataFlavor>> =
        snapshot.transferDataFlavors.groupBy({ it.humanPresentableName }, { it.toPasteDataFlavor() })

    /** Runs the plugin on [data] the way the consumer does and returns the size of the image it stores. */
    private suspend fun collected(
        platform: Platform,
        data: Map<DataFlavor, Any>,
    ): Pair<Int, Int>? {
        written.clear()
        val snapshot = Snapshot(data)
        plugin(platform).loadRepresentation(
            pasteId = 1,
            itemIndex = 0,
            identity = DesktopImageTypePlugin.X_JAVA_IMAGE,
            dataFlavor = DataFlavor.imageFlavor.toPasteDataFlavor(),
            dataFlavorMap = dataFlavorMap(snapshot),
            pasteTransferable = DesktopReadTransferable(snapshot),
            pasteCollector = mockk<PasteCollector>(relaxed = true),
        )
        return written.singleOrNull()?.let { it.width to it.height }
    }

    private val png600x400 = encode(bitmap(600, 400), "png")

    @Test
    fun `a 144 dpi png copied on macOS is stored at its pixel size`() =
        runTest {
            val size =
                collected(
                    macos,
                    mapOf(
                        MacosBitmapImageRead.PNG_READ_FLAVOR to png600x400,
                        DataFlavor.imageFlavor to pointImage(300, 200),
                    ),
                )
            assertEquals(600 to 400, size)
        }

    @Test
    fun `a 96 dpi png copied on macOS is stored at its pixel size`() =
        runTest {
            val size =
                collected(
                    macos,
                    mapOf(
                        MacosBitmapImageRead.PNG_READ_FLAVOR to png600x400,
                        DataFlavor.imageFlavor to pointImage(450, 300),
                    ),
                )
            assertEquals(600 to 400, size)
        }

    @Test
    fun `a jpeg copied on macOS is stored at its pixel size`() =
        runTest {
            val size =
                collected(
                    macos,
                    mapOf(
                        MacosBitmapImageRead.JPEG_READ_FLAVOR to encode(bitmap(600, 400), "jpeg"),
                        DataFlavor.imageFlavor to pointImage(300, 200),
                    ),
                )
            assertEquals(600 to 400, size)
        }

    @Test
    fun `without raw bytes the largest resolution variant is stored`() =
        runTest {
            val size =
                collected(
                    macos,
                    mapOf(DataFlavor.imageFlavor to pointImage(300, 200, bitmap(300, 200), bitmap(600, 400))),
                )
            assertEquals(600 to 400, size)
        }

    @Test
    fun `raw bytes of another picture are not used`() =
        runTest {
            for (other in listOf(bitmap(150, 100), bitmap(400, 600), bitmap(600, 300))) {
                val size =
                    collected(
                        macos,
                        mapOf(
                            MacosBitmapImageRead.PNG_READ_FLAVOR to encode(other, "png"),
                            DataFlavor.imageFlavor to pointImage(300, 200),
                        ),
                    )
                assertEquals(300 to 200, size, "raw ${other.width}x${other.height}")
            }
        }

    @Test
    fun `undecodable raw bytes fall back to the image`() =
        runTest {
            val size =
                collected(
                    macos,
                    mapOf(
                        MacosBitmapImageRead.PNG_READ_FLAVOR to "not an image".encodeToByteArray(),
                        DataFlavor.imageFlavor to pointImage(300, 200),
                    ),
                )
            assertEquals(300 to 200, size)
        }

    @Test
    fun `off macOS the image is stored as before`() =
        runTest {
            val size =
                collected(
                    windows,
                    mapOf(
                        MacosBitmapImageRead.PNG_READ_FLAVOR to png600x400,
                        DataFlavor.imageFlavor to pointImage(300, 200, bitmap(300, 200), bitmap(600, 400)),
                    ),
                )
            assertEquals(300 to 200, size)
        }

    @Test
    fun `an image copied as a file is still skipped`() =
        runTest {
            val size =
                collected(
                    macos,
                    mapOf(
                        DataFlavor.javaFileListFlavor to listOf(dir.resolve("a.png").toFile()),
                        MacosBitmapImageRead.PNG_READ_FLAVOR to png600x400,
                        DataFlavor.imageFlavor to pointImage(300, 200),
                    ),
                )
            assertNull(size)
        }

    @Test
    fun `the image plugin maps the PNG and JFIF natives to raw bytes on macOS`() {
        plugin(macos)
        val flavorMap = SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap
        assertTrue(MacosBitmapImageRead.PNG_READ_FLAVOR in flavorMap.getFlavorsForNative("PNG"))
        assertTrue(MacosBitmapImageRead.JPEG_READ_FLAVOR in flavorMap.getFlavorsForNative("JFIF"))
        assertFalse(flavorMap.getFlavorsForNative("TIFF").any { it.representationClass == ByteArray::class.java })
    }

    @Test
    fun `the full size check accepts rounded points and rejects other pictures`() {
        assertTrue(MacosBitmapImageRead.isFullSizeOf(600, 400, 300, 200))
        assertTrue(MacosBitmapImageRead.isFullSizeOf(601, 401, 300, 200))
        assertTrue(MacosBitmapImageRead.isFullSizeOf(600, 400, 600, 400))
        assertFalse(MacosBitmapImageRead.isFullSizeOf(150, 100, 300, 200))
        assertFalse(MacosBitmapImageRead.isFullSizeOf(400, 600, 300, 200))
        assertFalse(MacosBitmapImageRead.isFullSizeOf(0, 0, 300, 200))
    }
}
