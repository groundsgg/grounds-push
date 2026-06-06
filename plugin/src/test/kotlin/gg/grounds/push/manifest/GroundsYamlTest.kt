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
    fun `parses plugins as multi-jar bundle`() {
        val m = parse("""
            name: combo
            type: plugin-paper
            baseImage: paper
            plugins:
              - build/libs/foo.jar
              - build/libs/bar.jar
        """.trimIndent())
        assertEquals(listOf("build/libs/foo.jar", "build/libs/bar.jar"), m.plugins?.map { it.source })
    }

    @Test
    fun `parses plugins as legacy strings and structured entries`() {
        val m = parse("""
            name: combo
            type: plugin-paper
            baseImage: paper
            plugins:
              - build/libs/foo.jar
              - id: plugin-chat
                variant: paper
                source: github:groundsgg/plugin-chat@v1.2.3:plugin-chat.jar
        """.trimIndent())

        val plugins = m.plugins ?: error("expected plugins")
        val legacy = plugins[0] as GroundsYaml.PluginEntry.Legacy
        assertEquals("build/libs/foo.jar", legacy.source)

        val structured = plugins[1] as GroundsYaml.PluginEntry.Structured
        assertEquals("github:groundsgg/plugin-chat@v1.2.3:plugin-chat.jar", structured.source)
    }

    @Test
    fun `parses app flavors`() {
        val m = parse("""
            name: plugin-config
            flavors:
              paper:
                type: paper
                baseImage: paper
                jar: paper/build/libs/plugin-config-paper.jar
                resources:
                  memory: 2Gi
              velocity:
                type: velocity
                baseImage: velocity
                plugins:
                  - velocity/build/libs/plugin-config.jar
                  - velocity/build/libs/plugin-config-extra.jar
        """.trimIndent())

        assertNull(m.type)
        assertNull(m.baseImage)
        assertEquals("paper/build/libs/plugin-config-paper.jar", m.flavors?.get("paper")?.jar)
        assertEquals("2Gi", m.flavors?.get("paper")?.resources?.memory)
        assertEquals(
            listOf("velocity/build/libs/plugin-config.jar", "velocity/build/libs/plugin-config-extra.jar"),
            m.flavors?.get("velocity")?.plugins?.map { it.source },
        )
    }

    @Test
    fun `rejects app flavors combined with top-level runtime fields`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                name: plugin-config
                type: paper
                baseImage: paper
                flavors:
                  paper:
                    type: paper
                    baseImage: paper
            """.trimIndent())
        }
        assert(e.message!!.contains("either top-level runtime fields or flavors")) { e.message!! }
    }

    @Test
    fun `rejects invalid app flavor key`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                name: plugin-config
                flavors:
                  Paper:
                    type: paper
                    baseImage: paper
            """.trimIndent())
        }
        assert(e.message!!.contains("flavor key")) { e.message!! }
    }

    @Test
    fun `rejects plugins with fewer than 2 entries`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                name: combo
                type: plugin-paper
                baseImage: paper
                plugins:
                  - build/libs/only.jar
            """.trimIndent())
        }
        assert(e.message!!.contains("at least 2")) { e.message!! }
    }

    @Test
    fun `rejects plugins combined with jar`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                name: combo
                type: plugin-paper
                baseImage: paper
                jar: build/libs/x.jar
                plugins:
                  - build/libs/foo.jar
                  - build/libs/bar.jar
            """.trimIndent())
        }
        assert(e.message!!.contains("mutually exclusive")) { e.message!! }
    }

    @Test
    fun `rejects plugins for type service`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                name: svc
                type: service
                baseImage: service
                plugins:
                  - build/libs/foo.jar
                  - build/libs/bar.jar
            """.trimIndent())
        }
        assert(e.message!!.contains("service")) { e.message!! }
    }

    @Test
    fun `rejects plugins for type minestom`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                name: svc
                type: minestom
                baseImage: minestom
                plugins:
                  - build/libs/foo.jar
                  - build/libs/bar.jar
            """.trimIndent())
        }
        assert(e.message!!.contains("minestom")) { e.message!! }
    }

    @Test
    fun `rejects flavor plugins for type minestom`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                name: svc
                flavors:
                  minestom:
                    type: minestom
                    baseImage: minestom
                    plugins:
                      - build/libs/foo.jar
                      - build/libs/bar.jar
            """.trimIndent())
        }
        assert(e.message!!.contains("flavors.minestom.plugins")) { e.message!! }
        assert(e.message!!.contains("minestom")) { e.message!! }
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

    @Test
    fun `parses services map`() {
        val m = parse("""
            name: my-plugin
            type: plugin-paper
            baseImage: paper
            services:
              leaderboard:
                use: groundsgg.leaderboard.v1.LeaderboardService
                version: v1
              player:
                use: groundsgg.player.v1.PlayerService
                provider: project:abc123/custom-player
        """.trimIndent())

        val services = m.services ?: error("expected services")
        assertEquals("groundsgg.leaderboard.v1.LeaderboardService", services["leaderboard"]?.use)
        assertEquals("v1", services["leaderboard"]?.version)
        assertNull(services["leaderboard"]?.provider)
        assertEquals("groundsgg.player.v1.PlayerService", services["player"]?.use)
        assertEquals("project:abc123/custom-player", services["player"]?.provider)
    }

    @Test
    fun `rejects services as a non-mapping`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                name: foo
                type: plugin-paper
                baseImage: paper
                services:
                  - leaderboard
            """.trimIndent())
        }
        assert(e.message!!.contains("services")) { e.message!! }
    }

    @Test
    fun `rejects service decl as a non-mapping`() {
        val e = assertThrows<GroundsYamlParseException> {
            parse("""
                name: foo
                type: plugin-paper
                baseImage: paper
                services:
                  leaderboard: groundsgg.leaderboard.v1.LeaderboardService
            """.trimIndent())
        }
        assert(e.message!!.contains("services.leaderboard")) { e.message!! }
    }
}
