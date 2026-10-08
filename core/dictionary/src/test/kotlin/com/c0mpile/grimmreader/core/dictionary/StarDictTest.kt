package com.c0mpile.grimmreader.core.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class StarDictTest {
    @get:Rule val tmp = TemporaryFolder()

    private val articles =
        mapOf(
            "apple" to "a round fruit",
            "Polish" to "of Poland",
            "polish" to "to make shiny",
            "run" to "to move fast",
            "zebra" to "a striped animal",
        )

    @Test fun ifoNeedsMagicNameAndCount() {
        val info =
            StarDictInfo.parse(
                "StarDict's dict ifo file\nversion=3.0.0\nbookname=Test\nwordcount=2\nidxoffsetbits=64\nsametypesequence=h\n",
            )
        assertEquals(StarDictInfo("Test", 2, -1, 64, "h"), info)
        assertNull(StarDictInfo.parse("not a dictionary\nbookname=x\nwordcount=1"))
        assertNull(StarDictInfo.parse("StarDict's dict ifo file\nwordcount=1"))
        assertNull(StarDictInfo.parse("StarDict's dict ifo file\nbookname=x\nwordcount=1\nidxoffsetbits=16"))
    }

    @Test fun findsWordsIgnoringAsciiCaseAndThroughSynonyms() {
        val dir = TestDictionary.write(tmp.newFolder("d"), "test", "Test", articles, synonyms = mapOf("ran" to "run", "runs" to "run"))
        StarDictionary.open(dir).use { dict ->
            assertEquals(listOf("a round fruit"), dict.lookup("APPLE").map { it.fields.single().second })
            assertEquals(setOf("of Poland", "to make shiny"), dict.lookup("polish").map { it.fields.single().second }.toSet())
            assertEquals("run", dict.lookup("ran").single().word)
            assertTrue(dict.lookup("pear").isEmpty())
            assertTrue(dict.lookup("").isEmpty())
            assertEquals("zebra", dict.lookup("zebra").single().word)
        }
    }

    @Test fun readsDictZipAcrossChunks() {
        val long = articles.mapValues { (_, v) -> v.repeat(7) }
        val dir = TestDictionary.write(tmp.newFolder("dz"), "test", "Test", long, dictZipChunk = 16)
        assertTrue(DictZip.isDictZip(File(dir, "test.dict.dz")))
        StarDictionary.open(dir).use { dict ->
            for ((word, text) in long) assertTrue(text in dict.lookup(word).map { it.fields.single().second })
        }
    }

    @Test fun plainGzipIsNotDictZip() {
        val file = tmp.newFile("x.dict.dz")
        java.util.zip
            .GZIPOutputStream(file.outputStream())
            .use { it.write("hello".toByteArray()) }
        assertFalse(DictZip.isDictZip(file))
    }

    @Test fun damagedIndexIsRejected() {
        val dir = TestDictionary.write(tmp.newFolder("bad"), "test", "Test", articles)
        File(dir, "test.idx").writeBytes("apple".toByteArray() + 0 + byteArrayOf(0, 0))
        assertTrue(runCatching { StarDictionary.open(dir) }.isFailure)
    }

    @Test fun splitsFieldsWithAndWithoutTypeSequence() {
        val withLetters = ByteArrayOutputStream()
        DataOutputStream(withLetters).use { out ->
            out.write('m'.code)
            out.write("plain".toByteArray())
            out.write(0)
            out.write('W'.code)
            out.writeInt(3)
            out.write(byteArrayOf(1, 2, 3))
            out.write('h'.code)
            out.write("<b>x</b>".toByteArray())
            out.write(0)
        }
        assertEquals(listOf('m' to "plain", 'h' to "<b>x</b>"), StarDictionary.fields(withLetters.toByteArray(), null))
        assertEquals(listOf('t' to "ran", 'm' to "past of run"), StarDictionary.fields("ran\u0000past of run".toByteArray(), "tm"))
    }

    @Test fun definitionsKeepFormattingButDropLinksImagesAndScripts() {
        val html =
            DefinitionHtml.of(
                listOf(
                    'h' to "<b>bold</b> <a href=\"https://x.example\">link</a><img src=x><script>alert(1)</script><style>p{}</style>",
                    'm' to "a < b\nnext",
                    'x' to "<k>word</k> <tr>wɜːd</tr> <ex>an example</ex>",
                ),
            )
        assertEquals("<b>bold</b> link<br>a &lt; b<br>next<br><b>word</b> [wɜːd] <i>an example</i>", html)
    }

    @Test fun wordFormsTryBaseForms() {
        val running = WordForms.of("“Running,”")
        assertEquals(listOf("Running", "running"), running.take(2))
        assertTrue(running.indexOf("run") > running.indexOf("running"))
        assertTrue("study" in WordForms.of("studies"))
        assertTrue("stop" in WordForms.of("stopped"))
        assertTrue("dog" in WordForms.of("dog's"))
        assertTrue("wolf" in WordForms.of("wolves"))
        assertFalse("clas" in WordForms.of("class"))
        assertEquals("don't", WordForms.clean("don’t"))
        assertTrue(WordForms.of("—").isEmpty())
    }
}
