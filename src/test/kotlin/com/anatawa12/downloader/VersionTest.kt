package com.anatawa12.downloader

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe

class VersionTest : DescribeSpec() {
    init {
        describe("parse") {
            it("major only") {
                Version.parse("0") shouldBe Version(0u)
                Version.parse("1") shouldBe Version(1u)
            }
            it("major.minor") {
                Version.parse("0.0") shouldBe Version(0u, 0u)
                Version.parse("1.0") shouldBe Version(1u, 0u)
                Version.parse("0.1") shouldBe Version(0u, 1u)
                Version.parse("1.1") shouldBe Version(1u, 1u)
            }
            it("major.minor.patch") {
                Version.parse("0.0.0") shouldBe Version(0u, 0u, 0u)
                Version.parse("1.0.0") shouldBe Version(1u, 0u, 0u)
                Version.parse("0.1.0") shouldBe Version(0u, 1u, 0u)
                Version.parse("1.1.0") shouldBe Version(1u, 1u, 0u)
                Version.parse("0.0.8") shouldBe Version(0u, 0u, 8u)
                Version.parse("1.0.8") shouldBe Version(1u, 0u, 8u)
                Version.parse("0.1.8") shouldBe Version(0u, 1u, 8u)
                Version.parse("1.1.8") shouldBe Version(1u, 1u, 8u)
            }
            it("snapshot") {
                Version.parse("1-SNAPSHOT") shouldBe Version(1u, snapshot = true)
                Version.parse("1.1-SNAPSHOT") shouldBe Version(1u, 1u  , snapshot = true)
                Version.parse("1.1.8-SNAPSHOT") shouldBe Version(1u, 1u, 8u, snapshot = true)
            }
        }
        it("current") {
            Version.current.toString() shouldBe Constants.version
        }
        describe("comparing") {
            it("same major") {
                Version(1u, 0u, 0u) shouldBeLessThan Version(1u, 1u, 0u)
                Version(1u, 1u, 0u) shouldBeGreaterThan Version(1u, 0u, 0u)
            }
            it("same minor") {
                Version(1u, 0u, 0u) shouldBeLessThan Version(1u, 0u, 1u)
                Version(1u, 0u, 1u) shouldBeGreaterThan Version(1u, 0u, 0u)
            }
            it("same patch, snapshot only") {
                Version(1u, 0u, 0u, true) shouldBeLessThan Version(1u, 0u, 0u)
                Version(1u, 0u, 0u) shouldBeGreaterThan Version(1u, 0u, 0u, true)
            }
        }
    }
}
