package com.anatawa12.downloader

import com.anatawa12.downloader.ModsConfig.*
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ModsConfigTest : DescribeSpec() {
    init {
        describe("reader") {
            it("simple read") {
                fun Reader1.testPeekReadBackRead(char: Char) {
                    peek().toChar() shouldBe char
                    read().toChar() shouldBe char
                    back()
                    read().toChar() shouldBe char
                }
                val reader = Reader1(" \nhello (world)")
                reader.testPeekReadBackRead(' ')
                reader.testPeekReadBackRead('\n')
                reader.testPeekReadBackRead('h')
                reader.testPeekReadBackRead('e')
                reader.testPeekReadBackRead('l')
                reader.testPeekReadBackRead('l')
                reader.testPeekReadBackRead('o')
                reader.testPeekReadBackRead(' ')
                reader.testPeekReadBackRead('(')
                reader.testPeekReadBackRead('w')
                reader.testPeekReadBackRead('o')
                reader.testPeekReadBackRead('r')
                reader.testPeekReadBackRead('l')
                reader.testPeekReadBackRead('d')
                reader.testPeekReadBackRead(')')
            }
            it("readUntil") {
                val reader = Reader1("hello (world)")
                reader.readUntil { !it.isLetterOrDigit() } shouldBe "hello"
                reader.read().toChar() shouldBe ' '
                reader.read().toChar() shouldBe '('
                reader.readUntil { it == ')' } shouldBe "world"
                reader.read().toChar() shouldBe ')'
            }
        }
        describe("parser") {
            fun test(text: String) = Parser("mods.txt", text).parseModsConfig()
            it("empty") {
                test("").list shouldBe listOf()
                test("\n").list shouldBe listOf()
                test("#").list shouldBe listOf()
                test("#\n").list shouldBe listOf()
                test("  ").list shouldBe listOf()
            }
            it("curse") {
                test("mod fixrtm from curse fixrtm\n" +
                        "    version 3522183 (2.0.20)").list shouldBe listOf(
                    ModInfo("fixrtm", CurseMod("fixrtm"), "3522183", "2.0.20")
                )
            }
            it("url") {
                test("mod preload-newer-kotlin from url \"https://github.com/anatawa12/preload-newer-kotlin/releases/download/v\$version/aaaa-preload-newer-kotlin-\$version.jar\"\n" +
                        "    version 1.6.10").list shouldBe listOf(
                    ModInfo("preload-newer-kotlin", URLPattern("https://github.com/anatawa12/preload-newer-kotlin/releases/download/v\$version/aaaa-preload-newer-kotlin-\$version.jar"), "1.6.10", null)
                )
            }
        }
    }
}
