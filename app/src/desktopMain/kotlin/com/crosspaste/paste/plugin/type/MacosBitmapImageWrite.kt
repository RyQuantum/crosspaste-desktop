package com.crosspaste.paste.plugin.type

import com.crosspaste.paste.LazyTransferData
import com.crosspaste.paste.PasteDataFlavor
import com.crosspaste.paste.item.PasteItem
import com.crosspaste.paste.toPasteDataFlavor
import com.crosspaste.platform.Platform
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.io.File

/**
 * Fork patch (RyQuantum/crosspaste-desktop, branch ry-fixes).
 *
 * On macOS, a single image that was captured from a clipboard bitmap is written back as image
 * data only, without the file reference to CrossPaste's own copy of it, and when the stored file
 * is a PNG or JPEG its bytes go to the pasteboard as they are.
 *
 * Two problems with the upstream write, both seen with Alfred as the clipboard manager:
 * - JBR writes [DataFlavor.javaFileListFlavor] as a second pasteboard item holding
 *   `public.file-url`, and declares `NSFilenamesPboardType` and `text/uri-list` on the first item
 *   without ever filling them. The paste is recorded as a "File: <uuid>.png" entry.
 * - [DataFlavor.imageFlavor] makes JBR decode the file and encode PNG, JPEG and TIFF after it has
 *   already declared the types (50-300 ms for a screenshot). A clipboard manager that reads in
 *   that window often records nothing at all. Writing the stored bytes takes a few milliseconds.
 *
 * Images that were copied as files keep the file reference: their identifiers carry the file
 * list flavor, while a clipboard bitmap only carries image identifiers.
 */
object MacosBitmapImageWrite {

    private val logger = KotlinLogging.logger {}

    /** Stored PNG bytes, written under JBR's predefined `PNG` native (`public.png`). */
    val PNG_BYTES_FLAVOR = DataFlavor("image/x-crosspaste-png-bytes;class=\"[B\"")

    /** Stored JPEG bytes, written under JBR's predefined `JFIF` native (`public.jpeg`). */
    val JPEG_BYTES_FLAVOR = DataFlavor("image/x-crosspaste-jpeg-bytes;class=\"[B\"")

    // Write direction only: reading other apps' pasteboards keeps the stock flavor table
    private val nativesRegistered: Boolean by lazy {
        runCatching {
            val flavorMap = SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap
            flavorMap.addUnencodedNativeForFlavor(PNG_BYTES_FLAVOR, "PNG")
            flavorMap.addUnencodedNativeForFlavor(JPEG_BYTES_FLAVOR, "JFIF")
            true
        }.onFailure { e ->
            logger.warn(e) { "Cannot map raw image flavors, falling back to imageFlavor" }
        }.getOrDefault(false)
    }

    fun isBitmapOnly(
        platform: Platform,
        pasteItem: PasteItem,
        fileCount: Int,
        imageIdentifiers: List<String>,
    ): Boolean =
        platform.isMacos() &&
            fileCount == 1 &&
            pasteItem.identifiers.isNotEmpty() &&
            pasteItem.identifiers.all { it in imageIdentifiers }

    fun rawBytesFlavor(fileName: String): DataFlavor? =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> PNG_BYTES_FLAVOR
            "jpg", "jpeg" -> JPEG_BYTES_FLAVOR
            else -> null
        }

    /**
     * Puts [imageFile]'s bytes on [map] when the item is a bitmap on macOS stored as PNG or JPEG.
     * Returns false when the caller should build the transferable as before.
     */
    fun putRawImage(
        platform: Platform,
        pasteItem: PasteItem,
        imageFile: File,
        imageIdentifiers: List<String>,
        map: MutableMap<PasteDataFlavor, Any>,
    ): Boolean {
        if (!isBitmapOnly(platform, pasteItem, 1, imageIdentifiers)) return false
        val flavor = rawBytesFlavor(imageFile.name) ?: return false
        if (!nativesRegistered) return false
        map[flavor.toPasteDataFlavor()] = LazyTransferData { imageFile.readBytes() }
        return true
    }
}
