package gg.grounds.push.manifest

import gg.grounds.push.client.BaseImageCatalog

class BaseImageCatalogValidationException(message: String) : RuntimeException(message)

object BaseImageCatalogValidator {
    fun validate(catalog: BaseImageCatalog, type: String, baseImage: String) {
        val parts = baseImage.split("@")
        if (parts.size > 2 || parts.first().isBlank()) {
            throw BaseImageCatalogValidationException("grounds.yaml: invalid baseImage '$baseImage'")
        }
        val key = parts[0]
        val requestedVersion = parts.getOrNull(1)
        val source = catalog.items.firstOrNull { it.key == key }
            ?: throw BaseImageCatalogValidationException(
                "grounds.yaml: unknown baseImage '$key'. Allowed for $type: ${allowed(catalog, type)}",
            )
        if (source.manifestType != type) {
            throw BaseImageCatalogValidationException(
                "grounds.yaml: baseImage '$key' requires type ${source.manifestType}, got $type. " +
                    "Allowed for $type: ${allowed(catalog, type)}",
            )
        }
        if (requestedVersion != null) {
            source.versions.firstOrNull { it.version == requestedVersion && it.selectable }
                ?: throw BaseImageCatalogValidationException(
                    "grounds.yaml: unknown or unavailable baseImage '$baseImage'. " +
                        "Allowed versions: ${source.versions.filter { it.selectable }.joinToString { it.version }}",
                )
        }
    }

    private fun allowed(catalog: BaseImageCatalog, type: String): String =
        catalog.items.filter { it.manifestType == type }.joinToString { it.key }.ifBlank { "none" }
}
