package com.crosspaste.paste.plugin.type

import com.crosspaste.image.ImageHandler
import com.crosspaste.paste.PasteDataFlavor
import com.crosspaste.paste.PasteTransferable
import com.crosspaste.platform.Platform
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * Fork patch (RyQuantum/crosspaste-desktop, branch ry-fixes).
 *
 * On macOS, a bitmap copied to the pasteboard is collected at its pixel size instead of its
 * size in points.
 *
 * JBR turns `public.png` / `public.jpeg` / `public.tiff` into an NSImage for
 * [DataFlavor.imageFlavor], and the image's width and height are the NSImage size in points:
 * pixels × 72 / DPI. A 600×400 PNG marked 144 DPI (any Retina screenshot) comes back as
 * 300×200, and its only resolution variant is 300×200 as well, so the upstream redraw at
 * `getWidth(null)` × `getHeight(null)` halves it before it is stored and synced.
 *
 * The PNG and JPEG natives are additionally read as unconverted bytes, which are decoded at
 * their pixel size; when neither is there, the largest resolution variant of the image is used
 * (an NSImage with several representations, e.g. a TIFF with 1x and 2x pages, lists them all).
 * TIFF is not read as bytes: macOS offers a TIFF next to every PNG, and the pasteboard snapshot
 * fetches each mapped native up front, which for a 3024×1964 screenshot is an 18 MB conversion
 * taking about 50 ms on every copy, while ImageIO would only read the first page of it anyway.
 */
object MacosBitmapImageRead {

    private val logger = KotlinLogging.logger {}

    /** `public.png` bytes as they are on the pasteboard (JBR native `PNG`). */
    val PNG_READ_FLAVOR = DataFlavor("image/x-crosspaste-png-read;class=\"[B\"")

    /** `public.jpeg` bytes as they are on the pasteboard (JBR native `JFIF`). */
    val JPEG_READ_FLAVOR = DataFlavor("image/x-crosspaste-jpeg-read;class=\"[B\"")

    private val readFlavors = listOf(PNG_READ_FLAVOR, JPEG_READ_FLAVOR)

    // A pasteboard snapshot lists the flavors mapped when it is taken, so this has to run before
    // the first read; the image plugin does it when it is created.
    private val readFlavorsRegistered: Boolean by lazy {
        runCatching {
            val flavorMap = SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap
            flavorMap.addFlavorForUnencodedNative("PNG", PNG_READ_FLAVOR)
            flavorMap.addFlavorForUnencodedNative("JFIF", JPEG_READ_FLAVOR)
            true
        }.onFailure { e ->
            logger.warn(e) { "Cannot map raw image natives, copied images keep their size in points" }
        }.getOrDefault(false)
    }

    fun registerReadFlavors(platform: Platform) {
        if (platform.isMacos()) readFlavorsRegistered
    }

    /**
     * The pixel-size version of [image] read from the same pasteboard snapshot, or null when the
     * caller should use [image] as before (off macOS, or nothing larger is available).
     */
    suspend fun fullResolution(
        platform: Platform,
        imageHandler: ImageHandler<BufferedImage>,
        image: Image,
        dataFlavorMap: Map<String, List<PasteDataFlavor>>,
        pasteTransferable: PasteTransferable,
    ): Image? {
        if (!platform.isMacos()) return null
        val pointWidth = image.getWidth(null)
        val pointHeight = image.getHeight(null)

        for (flavor in readFlavors) {
            val pasteDataFlavor = dataFlavorMap[flavor.humanPresentableName]?.firstOrNull() ?: continue
            val bytes =
                runCatching { pasteTransferable.getTransferData(pasteDataFlavor) as? ByteArray }
                    .getOrNull() ?: continue
            val decoded = imageHandler.readImage(ByteArrayInputStream(bytes).asSource().buffered()) ?: continue
            if (isFullSizeOf(decoded.width, decoded.height, pointWidth, pointHeight)) {
                logger.debug {
                    "Copied image ${pointWidth}x$pointHeight pt read as ${decoded.width}x${decoded.height} px " +
                        "from ${flavor.humanPresentableName}"
                }
                return decoded
            }
        }

        val variant =
            (image as? MultiResolutionImage)
                ?.resolutionVariants
                ?.maxByOrNull { it.getWidth(null).toLong() * it.getHeight(null) }
                ?: return null
        val variantWidth = variant.getWidth(null)
        val variantHeight = variant.getHeight(null)
        if (variantWidth.toLong() * variantHeight <= pointWidth.toLong() * pointHeight) return null
        if (!isFullSizeOf(variantWidth, variantHeight, pointWidth, pointHeight)) return null
        logger.debug { "Copied image ${pointWidth}x$pointHeight pt read as variant ${variantWidth}x$variantHeight" }
        return variant
    }

    /**
     * Whether a [width]×[height] bitmap is the same picture as the image of [pointWidth]×[pointHeight]
     * points: at least as large and with the same aspect ratio, allowing for the points having been
     * rounded down to whole numbers. Guards against a native holding something else, such as a
     * thumbnail or a JPEG whose EXIF orientation NSImage applied but ImageIO does not.
     */
    fun isFullSizeOf(
        width: Int,
        height: Int,
        pointWidth: Int,
        pointHeight: Int,
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        if (pointWidth <= 0 || pointHeight <= 0) return true
        return width >= pointWidth &&
            height >= pointHeight &&
            abs(width.toLong() * pointHeight - height.toLong() * pointWidth) <= width + height
    }
}
