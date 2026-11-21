package com.mobilegh.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChineseModelInstallerTest {
    @Test fun modelArchivePathsStripOneTopLevelDirectory() {
        val paths = ChineseModelInstaller.archivePaths(
            listOf(
                "dict.en_zh_25/translate_enzh/processor_spec.pb",
                "dict.en_zh_25/translate_zhen/processor_spec.pb",
            ),
        )

        assertEquals(
            setOf("translate_enzh/processor_spec.pb", "translate_zhen/processor_spec.pb"),
            paths,
        )
    }

    @Test fun modelArchiveRejectsUnexpectedFiles() {
        assertThrows(IllegalArgumentException::class.java) {
            ChineseModelInstaller.validateArchivePaths(
                actual = setOf("translate_enzh/processor_spec.pb", "unexpected.bin"),
                expected = setOf("translate_enzh/processor_spec.pb"),
            )
        }
    }
}
