package com.crosspaste.paste.plugin.type

import com.crosspaste.image.ImageHandler
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
import okio.Path.Companion.toPath
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import kotlin.test.Test
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
    ) = ImagesPasteItem(
        identifiers = identifiers,
        count = files.size.toLong(),
        hash = "image-hash",
        size = 100,
        basePath = System.getProperty("java.io.tmpdir").toPath().toString(),
        fileInfoTreeMap = emptyMap(),
        relativePathList = files,
    )

    // What a synced screenshot looks like: Windows and macOS senders record the AWT image flavor
    private val bitmap = images(listOf(DesktopImageTypePlugin.X_JAVA_IMAGE))

    // What an image copied as a file in Explorer/Finder looks like after FilesToImagesPlugin
    private val imageFile = images(listOf(DesktopFilesTypePlugin.FILE_LIST_ID))

    private suspend fun flavors(
        platform: Platform,
        item: ImagesPasteItem,
        scope: PasteWriteScope,
    ): List<DataFlavor> =
        DesktopWriteTransferableBuilder()
            .add(plugin(platform), item, scope)
            .build()
            .transferDataFlavors
            .toList()

    @Test
    fun `a bitmap on macOS is written as image data only`() =
        runTest {
            for (scope in PasteWriteScope.entries) {
                assertEquals(listOf(DataFlavor.imageFlavor), flavors(macos, bitmap, scope), "scope $scope")
            }
        }

    @Test
    fun `every image identifier counts as a bitmap`() =
        runTest {
            for (id in plugin(macos).getIdentifiers()) {
                assertEquals(
                    listOf(DataFlavor.imageFlavor),
                    flavors(macos, images(listOf(id)), PasteWriteScope.PRIMARY_CATEGORY),
                    id,
                )
            }
        }

    @Test
    fun `an image copied as a file stays a file on macOS`() =
        runTest {
            val flavors = flavors(macos, imageFile, PasteWriteScope.PRIMARY_CATEGORY)
            assertTrue(DataFlavor.javaFileListFlavor in flavors)
            assertTrue(DataFlavor.imageFlavor in flavors)
        }

    @Test
    fun `several bitmaps stay files on macOS`() =
        runTest {
            val flavors =
                flavors(
                    macos,
                    images(listOf(DesktopImageTypePlugin.X_JAVA_IMAGE), files = listOf("a.png", "b.png")),
                    PasteWriteScope.PRIMARY_CATEGORY,
                )
            assertEquals(listOf(DataFlavor.javaFileListFlavor), flavors)
        }

    @Test
    fun `a bitmap off macOS keeps the file reference`() =
        runTest {
            val flavors = flavors(windows, bitmap, PasteWriteScope.PRIMARY_CATEGORY)
            assertTrue(DataFlavor.javaFileListFlavor in flavors)
        }

    @Test
    fun `an item without identifiers is not treated as a bitmap`() {
        assertFalse(MacosBitmapImageWrite.isBitmapOnly(macos, images(emptyList()), 1, plugin(macos).getIdentifiers()))
    }
}
