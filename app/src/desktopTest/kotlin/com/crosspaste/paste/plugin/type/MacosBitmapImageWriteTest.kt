package com.crosspaste.paste.plugin.type

import com.crosspaste.image.ImageHandler
import com.crosspaste.paste.DesktopWriteTransferable
import com.crosspaste.paste.DesktopWriteTransferableBuilder
import com.crosspaste.paste.PasteWriteScope
import com.crosspaste.paste.item.ImagesPasteItem
import com.crosspaste.path.UserDataPathProvider
import com.crosspaste.platform.Platform
import com.crosspaste.utils.getJsonUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacosBitmapImageWriteTest {

    // Guard against PasteItem/JsonUtils circular class initialization
    private val jsonUtils = getJsonUtils()

    private val imageHandler: ImageHandler<BufferedImage> =
        mockk {
            coEvery { readImage(any<okio.Path>()) } returns BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        }

    private val userDataPathProvider: UserDataPathProvider =
        mockk {
            every { resolve(any(), any<String>(), any(), any()) } answers {
                firstArg<okio.Path>() / secondArg<String>()
            }
        }

    private val macos = Platform(name = Platform.MACOS, arch = "aarch64", bitMode = 64, version = "27.0")

    private val windows = Platform(name = Platform.WINDOWS, arch = "amd64", bitMode = 64, version = "11")

    private val dir = Files.createTempDirectory("macos-bitmap-write").toFile().apply { deleteOnExit() }

    private fun plugin(platform: Platform) =
        DesktopImageTypePlugin(
            appInfo = mockk(relaxed = true),
            imageHandler = imageHandler,
            platform = platform,
            userDataPathProvider = userDataPathProvider,
        )

    private fun images(
        identifiers: List<String>,
        files: List<String> = listOf("image.png"),
    ): ImagesPasteItem {
        files.forEach { dir.resolve(it).writeBytes("bytes of $it".encodeToByteArray()) }
        return ImagesPasteItem(
            identifiers = identifiers,
            count = files.size.toLong(),
            hash = "image-hash",
            size = 100,
            basePath = dir.toOkioPath().toString(),
            fileInfoTreeMap = emptyMap(),
            relativePathList = files,
        )
    }

    // What a synced screenshot looks like: Windows and macOS senders record the AWT image flavor
    private fun bitmap(file: String = "image.png") = images(listOf(DesktopImageTypePlugin.X_JAVA_IMAGE), listOf(file))

    // What an image copied as a file in Explorer/Finder looks like after FilesToImagesPlugin
    private val imageFile = images(listOf(DesktopFilesTypePlugin.FILE_LIST_ID))

    private suspend fun transferable(
        platform: Platform,
        item: ImagesPasteItem,
        scope: PasteWriteScope = PasteWriteScope.PRIMARY_CATEGORY,
    ): DesktopWriteTransferable =
        DesktopWriteTransferableBuilder()
            .add(plugin(platform), item, scope)
            .build()

    private suspend fun flavors(
        platform: Platform,
        item: ImagesPasteItem,
        scope: PasteWriteScope = PasteWriteScope.PRIMARY_CATEGORY,
    ): List<DataFlavor> = transferable(platform, item, scope).transferDataFlavors.toList()

    @Test
    fun `a png bitmap on macOS is written as its stored bytes only`() =
        runTest {
            for (scope in PasteWriteScope.entries) {
                val transferable = transferable(macos, bitmap(), scope)
                assertEquals(
                    listOf(MacosBitmapImageWrite.PNG_BYTES_FLAVOR),
                    transferable.transferDataFlavors.toList(),
                    "scope $scope",
                )
                assertContentEquals(
                    "bytes of image.png".encodeToByteArray(),
                    transferable.getTransferData(MacosBitmapImageWrite.PNG_BYTES_FLAVOR) as ByteArray,
                )
            }
        }

    @Test
    fun `a jpeg bitmap on macOS is written as its stored bytes only`() =
        runTest {
            for (name in listOf("image.jpg", "image.JPEG")) {
                assertEquals(listOf(MacosBitmapImageWrite.JPEG_BYTES_FLAVOR), flavors(macos, bitmap(name)), name)
            }
        }

    @Test
    fun `the raw flavors map to the PNG and JFIF natives`() =
        runTest {
            transferable(macos, bitmap())
            val flavorMap = SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap
            assertEquals(listOf("PNG"), flavorMap.getNativesForFlavor(MacosBitmapImageWrite.PNG_BYTES_FLAVOR))
            assertEquals(listOf("JFIF"), flavorMap.getNativesForFlavor(MacosBitmapImageWrite.JPEG_BYTES_FLAVOR))
        }

    @Test
    fun `a bitmap in another format on macOS is written as image data only`() =
        runTest {
            assertEquals(listOf(DataFlavor.imageFlavor), flavors(macos, bitmap("image.gif")))
        }

    @Test
    fun `every image identifier counts as a bitmap`() =
        runTest {
            for (id in plugin(macos).getIdentifiers()) {
                assertEquals(listOf(MacosBitmapImageWrite.PNG_BYTES_FLAVOR), flavors(macos, images(listOf(id))), id)
            }
        }

    @Test
    fun `an image copied as a file stays a file on macOS`() =
        runTest {
            val flavors = flavors(macos, imageFile)
            assertTrue(DataFlavor.javaFileListFlavor in flavors)
            assertTrue(DataFlavor.imageFlavor in flavors)
        }

    @Test
    fun `several bitmaps stay files on macOS`() =
        runTest {
            val flavors =
                flavors(macos, images(listOf(DesktopImageTypePlugin.X_JAVA_IMAGE), files = listOf("a.png", "b.png")))
            assertEquals(listOf(DataFlavor.javaFileListFlavor), flavors)
        }

    @Test
    fun `a bitmap off macOS keeps the image flavor and the file reference`() =
        runTest {
            val flavors = flavors(windows, bitmap())
            assertTrue(DataFlavor.imageFlavor in flavors)
            assertTrue(DataFlavor.javaFileListFlavor in flavors)
            assertFalse(MacosBitmapImageWrite.PNG_BYTES_FLAVOR in flavors)
        }

    @Test
    fun `an item without identifiers is not treated as a bitmap`() {
        assertFalse(MacosBitmapImageWrite.isBitmapOnly(macos, images(emptyList()), 1, plugin(macos).getIdentifiers()))
    }
}
