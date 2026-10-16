package com.crosspaste.paste.plugin.type

import com.crosspaste.paste.item.PasteItem
import com.crosspaste.platform.Platform

/**
 * Fork patch (RyQuantum/crosspaste-desktop, branch ry-fixes).
 *
 * On macOS, a single image that was captured from a clipboard bitmap is written back as image
 * data only, without the file reference to CrossPaste's own copy of it.
 *
 * JBR writes [java.awt.datatransfer.DataFlavor.javaFileListFlavor] as a second pasteboard item
 * holding `public.file-url`, and declares `NSFilenamesPboardType` and `text/uri-list` on the
 * first item without ever filling them. Clipboard managers such as Alfred then record the
 * paste as a "File: <uuid>.png" entry instead of an image.
 *
 * Images that were copied as files keep the file reference: their identifiers carry the file
 * list flavor, while a clipboard bitmap only carries image identifiers.
 */
object MacosBitmapImageWrite {

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
}
