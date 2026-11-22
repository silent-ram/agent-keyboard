package com.agentkeyboard.ime.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/** 拼音解码核心(M1-T5):ni→[你,尼…],nihao→[你好…] */
class PinyinDecoderTest {

    private fun decoder(): PinyinDecoder = PinyinDecoder.load(
        StringReader(
            "ni\t你\nni\t尼\nni\t泥\nnihao\t你好\nnihao\t尼好\nshijian\t时间\nshi\t是\nshi\t时\njian\t见\n"
        ),
        StringReader(
            "ni\t你尼泥\nhao\t好号\nshi\t是时十\njian\t见件\n"
        ),
    )

    @Test
    fun `ni - 候选含你尼且词序在前`() {
        val c = decoder().candidates("ni")
        assertTrue(c.contains("你"))
        assertTrue(c.contains("尼"))
        assertTrue(c.indexOf("你") < c.indexOf("泥"))
    }

    @Test
    fun `nihao - 你好在候选首位`() {
        val c = decoder().candidates("nihao")
        assertEquals("你好", c.first())
        assertTrue(c.contains("尼好"))
    }

    @Test
    fun `同拼音按词频排序`() {
        val d = PinyinDecoder.load(
            StringReader("ni\t你\t10\nni\t尼\t100\n"),
            StringReader("ni\t你尼\n"),
        )

        assertEquals(listOf("尼", "你"), d.candidates("ni").take(2))
    }

    @Test
    fun `简拼 - nh 命中你好且完整拼音仍优先`() {
        val c = decoder().candidates("nh")
        assertEquals("你好", c.first())
    }

    @Test
    fun `模糊音 - s 代 sh 仍能命中时间`() {
        val c = decoder().candidates("sijian")
        assertTrue("sijian 应通过 sh/s 模糊音命中时间: $c", c.contains("时间"))
    }

    @Test
    fun `切分显示 - nihao 显示 ni'hao`() {
        assertEquals("ni'hao", decoder().splitDisplay("nihao"))
        assertEquals("ni", decoder().splitDisplay("ni"))
    }

    @Test
    fun `音节切分 - DP 完全切分`() {
        assertEquals(listOf("ni", "hao"), decoder().bestSplit("nihao"))
        assertEquals(listOf("shi", "jian"), decoder().bestSplit("shijian"))
    }

    @Test
    fun `非法串 - 无候选且切分回退原文`() {
        val d = decoder()
        assertEquals(emptyList<String>(), d.candidates("xyz"))
        assertEquals(listOf("xyz"), d.bestSplit("xyz"))
    }

    @Test
    fun `部分输入 nih - 切分含剩余字母且首音节字候选存在`() {
        val d = decoder()
        val split = d.bestSplit("nih")
        assertEquals(listOf("ni", "h"), split)
        val c = d.candidates("nih")
        assertTrue(c.isNotEmpty())
    }

    @Test
    fun `多音节长串 - shijian 见到组合`() {
        val c = decoder().candidates("shijian")
        assertTrue(c.isNotEmpty())
    }

    @Test
    fun `音节前缀判断 - n 与 ni 均在音节前缀`() {
        val d = decoder()
        assertTrue(d.hasSyllablePrefix("ni"))
        assertTrue(d.hasSyllablePrefix("h"))
        assertTrue(d.hasSyllablePrefix("nih"))
        assertTrue(d.hasSyllablePrefix(""))
        assertFalse(d.hasSyllablePrefix("xyz"))
        assertFalse(d.hasSyllablePrefix("nixyz"))
    }

    // ==== 真实词库覆盖与候选排序 ====

    /** 真实词库加载(app/src/main/assets;Gradle 单测 working dir = 模块目录,双路径兜底) */
    private fun realDecoder(): PinyinDecoder {
        val candidates = listOf(
            java.io.File("src/main/assets"),
            java.io.File("app/src/main/assets"),
        ).first { it.isDirectory }
        return PinyinDecoder.load(
            java.io.File(candidates, "pinyin_dict.txt").reader(Charsets.UTF_8),
            java.io.File(candidates, "pinyin_chars.txt").reader(Charsets.UTF_8),
        )
    }

    @Test
    fun `真实词库新词 - shijian 首选时间`() {
        val c = realDecoder().candidates("shijian")
        assertTrue("shijian 候选非空", c.isNotEmpty())
        assertEquals("时间", c.first())
    }

    @Test
    fun `真实词库新词 - huiyi 命中会议且 xiangmu 命中项目`() {
        val d = realDecoder()
        assertTrue("会议在 huiyi 候选中", d.candidates("huiyi").contains("会议"))
        assertTrue("项目在 xiangmu 候选中", d.candidates("xiangmu").contains("项目"))
    }

    @Test
    fun `升级词库 - 覆盖输入法与知识库专业词`() {
        val d = realDecoder()

        assertEquals("输入法", d.candidates("shurufa").first())
        assertEquals("知识库", d.candidates("zhishiku").first())
        assertEquals("人工智能", d.candidates("rengongzhineng").first())
    }

    @Test
    fun `词频排序 - jieba 高频短语和模糊音优先`() {
        val d = realDecoder()

        assertEquals("今天", d.candidates("jintian").first())
        assertEquals("知道", d.candidates("zhidao").first())
        assertEquals("知道", d.candidates("zidao").first())
    }

    @Test
    fun `九宫格 - 64426 首选你好`() {
        val d = realDecoder()
        assertEquals("你好", d.candidatesForT9("64426").first())
        assertEquals("你好", d.topCandidateForT9("64426"))
    }

    @Test
    fun `词库兼容旧格式并按频次排序且重复词取最高频`() {
        val d = PinyinDecoder.load(
            StringReader(
                "ni\t你\nni\t您\t9\nni\t你\t100\nni\t8\t妮\n",
            ),
            StringReader("ni\t你您妮\n"),
        )

        assertEquals(listOf("你", "您", "妮"), d.candidates("ni").take(3))
    }

    @Test
    fun `长句拼音可以稳定分段`() {
        val d = decoder()

        assertEquals(
            listOf("ni", "hao", "shi", "jian"),
            d.bestSplit("nihaoshijian"),
        )
        assertEquals("ni'hao'shi'jian", d.splitDisplay("nihaoshijian"))
    }
}
