package gg.grounds.push.manifest

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroundsYamlTest {
    private fun parse(yaml: String) =
        GroundsYamlParser.parse(StringReader(yaml))

    @Test
    fun `parses minimal valid manifest`() {
        val m = parse("""
            name: my-gamemode
            type: gamemode
            baseImage: minestom
        """.trimIndent())
        assertEquals("my-gamemode", m.name)
        assertEquals("gamemode", m.type)
        assertEquals("minestom", m.baseImage)
        assertEquals("build/libs/*.jar", m.jar)     // default
        assertNull(m.target)
        assertNull(m.resources)
    }

    @Test
    fun `parses manifest with resources`() {
        val m = parse("""
            name: svc
            type: service
            baseImage: service
            jar: build/libs/app.jar
            target: staging
            resources:
              cpu: 500m
              memory: 512Mi
        """.trimIndent())
        assertEquals("build/libs/app.jar", m.jar)
        assertEquals("staging", m.target)
        assertEquals("500m", m.resources?.cpu)
        assertEquals("512Mi", m.resources?.memory)
    }

    @Test
    fun `rejects missing required field name`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                type: gamemode
                baseImage: paper
            """.trimIndent())
        }
        assert(e.message!!.contains("name")) { "expected message to mention 'name', got: ${e.message}" }
    }

    @Test
    fun `rejects missing type`() {
        assertThrows<GroundsYamlParseException> {
            parse("""
                name: foo
                baseImage: paper
            """.trimIndent())
        }
    }

    @Test
    fun `rejects missing baseImage`() {
        assertThrows<GroundsYamlParseException> {
            parse("""
                name: foo
                type: gamemode
            """.trimIndent())
        }
    }

    @Test
    fun `rejects non-string field type`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                name: foo
                type: 42
                baseImage: paper
            """.trimIndent())
        }
        val msg = e.message!!
        assert(msg.contains("type")) { msg }
    }

    @Test
    fun `rejects malformed yaml with helpful error`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                name: foo
                type: [
            """.trimIndent())
        }
        val msg = e.message!!
        assert(msg.contains("YAML syntax error")) { msg }
    }

    @Test
    fun `rejects top-level sequence instead of mapping`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                - name: foo
                - type: gamemode
            """.trimIndent())
        }
        val msg = e.message!!
        assert(msg.contains("mapping")) { msg }
    }

    @Test
    fun `rejects empty name`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                name: ""
                type: gamemode
                baseImage: paper
            """.trimIndent())
        }
        val msg = e.message!!
        assert(msg.contains("name")) { msg }
    }

    @Test
    fun `rejects resources as a non-mapping`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                name: foo
                type: gamemode
                baseImage: paper
                resources: cpu=500m
            """.trimIndent())
        }
        val msg = e.message!!
        assert(msg.contains("resources")) { msg }
    }
}
