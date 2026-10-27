package com.snapnet.ui.page.files

import org.junit.Assert.assertEquals
import org.junit.Test

class FileManagerClassifierTest {
    @Test
    fun classifiesCommonFilesByExtensionAndMimeType() {
        assertEquals(FileManagerCategory.VIDEO, FileManagerClassifier.classify("مقطع.mp4"))
        assertEquals(FileManagerCategory.AUDIO, FileManagerClassifier.classify("lecture.m4a"))
        assertEquals(FileManagerCategory.IMAGE, FileManagerClassifier.classify("cover.webp"))
        assertEquals(FileManagerCategory.DOCUMENT, FileManagerClassifier.classify("report.pdf"))
        assertEquals(FileManagerCategory.ARCHIVE, FileManagerClassifier.classify("backup.tar.gz"))
        assertEquals(FileManagerCategory.VIDEO, FileManagerClassifier.classify("download.bin", "video/mp4"))
        assertEquals(FileManagerCategory.OTHER, FileManagerClassifier.classify("unknown.bin"))
    }
}
