package com.c0mpile.grimmreader.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PartialMd5Test {
    private fun sample(n: Int) = ByteArray(n) { ((it * 31 + 7) and 0xFF).toByte() }

    // Reference values from an independent implementation of KOReader's util.partialMD5.
    @Test fun tinyFileHashesNothing() = assertEquals("d41d8cd98f00b204e9800998ecf8427e", PartialMd5.compute(sample(100)))

    @Test fun shortFileUsesPartialFirstSample() = assertEquals("986bdaec8ab16531f7c798a470076823", PartialMd5.compute(sample(300)))

    @Test fun smallFile() = assertEquals("5ed57c7e8b80bdccb94703bbbbe792b7", PartialMd5.compute(sample(5000)))

    @Test fun largerFile() = assertEquals("9176994efdf840ffd6a4fb8d1cd31fb1", PartialMd5.compute(sample(2_000_000)))
}
