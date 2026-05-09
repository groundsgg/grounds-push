package gg.grounds.push.manifest

import gg.grounds.push.client.BaseImageCatalog
import gg.grounds.push.client.BaseImageSource
import gg.grounds.push.client.BaseImageVersion
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BaseImageCatalogValidatorTest {
    private val catalog = BaseImageCatalog(
        items = listOf(
            BaseImageSource(
                key = "paper",
                displayName = "Paper",
                manifestType = "plugin-paper",
                image = "ghcr.io/groundsgg/paper",
                versions = listOf(BaseImageVersion(version = "0.8.2", selectable = true)),
            ),
            BaseImageSource(
                key = "velocity",
                displayName = "Velocity",
                manifestType = "plugin-velocity",
                image = "ghcr.io/groundsgg/velocity",
            ),
        ),
    )

    @Test
    fun `accepts compatible base image key`() {
        BaseImageCatalogValidator.validate(catalog, type = "plugin-paper", baseImage = "paper")
    }

    @Test
    fun `rejects manifest type mismatch`() {
        assertFailsWith<BaseImageCatalogValidationException> {
            BaseImageCatalogValidator.validate(catalog, type = "plugin-paper", baseImage = "velocity")
        }
    }

    @Test
    fun `rejects unknown explicit version`() {
        assertFailsWith<BaseImageCatalogValidationException> {
            BaseImageCatalogValidator.validate(catalog, type = "plugin-paper", baseImage = "paper@9.9.9")
        }
    }
}
