package gg.grounds.push.source

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRefParserTest {

    @Test
    fun `parses relative local path`() {
        val r = SourceRefParser.parse("modules/economy/build/libs/economy.jar") as SourceRef.Local
        assertEquals("modules/economy/build/libs/economy.jar", r.path)
        assertTrue(!r.isAbsolute)
    }

    @Test
    fun `parses absolute local path`() {
        val r = SourceRefParser.parse("/opt/jars/legacy.jar") as SourceRef.Local
        assertTrue(r.isAbsolute)
    }

    @Test
    fun `parses gradle project ref`() {
        val r = SourceRefParser.parse(":economy") as SourceRef.GradleProject
        assertEquals(":economy", r.projectPath)
    }

    @Test
    fun `parses nested gradle project ref`() {
        val r = SourceRefParser.parse(":nested:deep:project") as SourceRef.GradleProject
        assertEquals(":nested:deep:project", r.projectPath)
    }

    @Test
    fun `rejects gradle ref with empty segment`() {
        assertThrows<SourceRefParseException> { SourceRefParser.parse("::economy") }
    }

    @Test
    fun `parses github ref to first jar asset`() {
        val r = SourceRefParser.parse("github:groundsgg/plugin-chat@v1.4.2") as SourceRef.GitHubRelease
        assertEquals("groundsgg", r.owner)
        assertEquals("plugin-chat", r.repo)
        assertEquals("v1.4.2", r.tag)
        assertNull(r.asset)
    }

    @Test
    fun `parses github ref with explicit asset`() {
        val r = SourceRefParser.parse("github:groundsgg/plugin-chat@v0.3.0:chat-all.jar") as SourceRef.GitHubRelease
        assertEquals("chat-all.jar", r.asset)
    }

    @Test
    fun `parses github ref with prerelease tag`() {
        val r = SourceRefParser.parse("github:groundsgg/x@v1.0.0-rc.1") as SourceRef.GitHubRelease
        assertEquals("v1.0.0-rc.1", r.tag)
    }

    @Test
    fun `parses github ref with 40-char commit SHA`() {
        val sha = "a".repeat(40)
        val r = SourceRefParser.parse("github:groundsgg/x@$sha") as SourceRef.GitHubRelease
        assertEquals(sha, r.tag)
    }

    @Test
    fun `normalizes owner casing on github ref`() {
        val r = SourceRefParser.parse("github:GroundsGG/x@v1.0.0") as SourceRef.GitHubRelease
        assertEquals("groundsgg", r.owner)
    }

    @Test
    fun `rejects github ref with non-allowlisted owner`() {
        val e = assertThrows<SourceRefParseException> {
            SourceRefParser.parse("github:hendrikbrombeer/x@v1.0.0")
        }
        assertTrue(e.message!!.contains("groundsgg"), e.message!!)
    }

    @Test
    fun `rejects substring-match attack on owner`() {
        // groundsgg-evil shouldn't sneak through a substring check
        assertThrows<SourceRefParseException> {
            SourceRefParser.parse("github:groundsgg-evil/x@v1.0.0")
        }
    }

    @Test
    fun `rejects github ref pinned to branch name`() {
        val e = assertThrows<SourceRefParseException> {
            SourceRefParser.parse("github:groundsgg/x@main")
        }
        assertTrue(e.message!!.contains("SemVer") || e.message!!.contains("commit"), e.message!!)
    }

    @Test
    fun `rejects github ref pinned to latest`() {
        assertThrows<SourceRefParseException> {
            SourceRefParser.parse("github:groundsgg/x@latest")
        }
    }

    @Test
    fun `rejects github ref pinned to short SHA`() {
        assertThrows<SourceRefParseException> {
            SourceRefParser.parse("github:groundsgg/x@abc1234")
        }
    }

    @Test
    fun `rejects github ref with non-jar asset`() {
        val e = assertThrows<SourceRefParseException> {
            SourceRefParser.parse("github:groundsgg/x@v1.0.0:source.tar.gz")
        }
        assertTrue(e.message!!.contains("jar"), e.message!!)
    }

    @Test
    fun `rejects malformed github ref`() {
        assertThrows<SourceRefParseException> { SourceRefParser.parse("github:groundsgg/x") }
    }

    @Test
    fun `rejects blank entry`() {
        assertThrows<SourceRefParseException> { SourceRefParser.parse("") }
        assertThrows<SourceRefParseException> { SourceRefParser.parse("   ") }
    }

    @Test
    fun `parseAll handles a mixed list`() {
        val refs = SourceRefParser.parseAll(
            listOf(
                "modules/economy/build/libs/economy.jar",
                ":chat",
                "github:groundsgg/plugin-teams@v0.3.0:teams.jar",
            ),
        )
        assertEquals(3, refs.size)
        assertTrue(refs[0] is SourceRef.Local)
        assertTrue(refs[1] is SourceRef.GradleProject)
        assertTrue(refs[2] is SourceRef.GitHubRelease)
    }
}
