package com.fujikon.app.nikonpc

/**
 * Builds the form that nikonpc.com's own download button submits. Keeping this
 * pure makes the exact field mapping testable without touching the network.
 */
object NikonPcFormBuilder {

    fun build(
        metadata: NikonPcMetadata,
        remote: NikonPcPreset? = null,
        displayName: String = metadata.name
    ): Map<String, String> {
        val body = linkedMapOf(
            "PicCtrlItem" to metadata.picCtrl,
            "reserved02" to metadata.reserved02,
            "fileName" to fileName(metadata, remote),
            "RegistrationName" to displayName,
            "points" to metadata.points.joinToString(",") { "${it.first},${it.second}" },
            "selFilterEffects" to metadata.filterEffect,
            "selToningType" to metadata.toningType
        )

        if (metadata.customCurve) {
            body["customCurveFlag"] = "1"
            body["minInput"] = metadata.minInput
            body["maxInput"] = metadata.maxInput
            body["minOutput"] = metadata.minOutput
            body["maxOutput"] = metadata.maxOutput
            body["gamma"] = metadata.gamma
        } else {
            if (metadata.contrast.equals("auto", ignoreCase = true)) {
                body["autoContrast"] = "1"
                body["contrast"] = "0"
            } else {
                body["contrast"] = metadata.contrast.takeUnless { it.isNullOrBlank() } ?: "0"
            }
            body["brightness"] = metadata.brightness.takeUnless { it.isNullOrBlank() } ?: "0"
        }

        when {
            metadata.sharpening.equals("auto", ignoreCase = true) -> {
                body["autoSharpening"] = "1"
                body["sharpening"] = "0"
            }
            !metadata.sharpening.isNullOrBlank() -> body["sharpening"] = metadata.sharpening
        }

        if (metadata.picCtrl.equals("Monochrome", ignoreCase = true)) {
            if (!metadata.toning.isNullOrBlank() && !metadata.toning.equals("disabled", ignoreCase = true)) {
                body["toning"] = metadata.toning
            }
        } else {
            when {
                metadata.saturation.equals("auto", ignoreCase = true) -> {
                    body["autoSaturation"] = "1"
                    body["saturation"] = "0"
                }
                !metadata.saturation.isNullOrBlank() -> body["saturation"] = metadata.saturation
            }
            if (!metadata.hue.isNullOrBlank()) body["hue"] = metadata.hue
        }

        return body
    }

    private fun fileName(metadata: NikonPcMetadata, remote: NikonPcPreset?): String {
        val candidates = listOf(
            metadata.fileName.filter { it.isDigit() }.take(2),
            remote?.numberOfDigits.orEmpty(),
            remote?.slot.orEmpty().filter { it.isDigit() }.take(2)
        )
        return candidates.firstOrNull { it.isNotBlank() }.orEmpty()
    }
}
