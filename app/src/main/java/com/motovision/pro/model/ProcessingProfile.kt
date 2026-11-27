package com.motovision.pro.model

/**
 * Image enhancement and tone-mapping profiles.
 * Controls bilateral filtering strength, unsharp masking, contrast curve, and color saturation.
 */
enum class ProcessingProfile(
    val title: String,
    val description: String,
    val sharpnessWeight: Float,      // 0.0 to 1.5
    val noiseReductionStrength: Float,// 0.0 to 1.0 (bilateral filter spatial sigma)
    val contrastBoost: Float,         // 1.0 = linear / natural
    val saturationScale: Float,       // 1.0 = neutral
    val highlightRolloff: Float,      // highlight preservation factor
    val isMonochrome: Boolean = false
) {
    NATURAL(
        title = "Natural",
        description = "Minimal processing, realistic colors, zero artificial edge enhancement, preserving natural sensor grain.",
        sharpnessWeight = 0.15f,
        noiseReductionStrength = 0.20f,
        contrastBoost = 1.00f,
        saturationScale = 1.00f,
        highlightRolloff = 0.85f,
        isMonochrome = false
    ),
    BALANCED(
        title = "Balanced",
        description = "Moderate dynamic range expansion, gentle edge-aware texture sharpening, and controlled noise reduction.",
        sharpnessWeight = 0.35f,
        noiseReductionStrength = 0.40f,
        contrastBoost = 1.08f,
        saturationScale = 1.05f,
        highlightRolloff = 0.70f,
        isMonochrome = false
    ),
    DETAILED(
        title = "Detailed",
        description = "Maximum fine-detail extraction, high-frequency texture reconstruction without halos, subtle noise filtering.",
        sharpnessWeight = 0.60f,
        noiseReductionStrength = 0.25f,
        contrastBoost = 1.05f,
        saturationScale = 1.02f,
        highlightRolloff = 0.80f,
        isMonochrome = false
    ),
    VIBRANT(
        title = "Vibrant",
        description = "Rich color rendering and deeper contrast, carefully protecting skin tones and preventing color channel clipping.",
        sharpnessWeight = 0.35f,
        noiseReductionStrength = 0.35f,
        contrastBoost = 1.15f,
        saturationScale = 1.15f,
        highlightRolloff = 0.65f,
        isMonochrome = false
    ),
    MONOCHROME(
        title = "B&W Pro",
        description = "Fine-art monochrome with panchromatic film sensitivity, rich tonal gradation, deep shadows, and high micro-contrast.",
        sharpnessWeight = 0.50f,
        noiseReductionStrength = 0.25f,
        contrastBoost = 1.22f,
        saturationScale = 0.00f,
        highlightRolloff = 0.75f,
        isMonochrome = true
    ),
    BW_VINTAGE(
        title = "B&W Vintage",
        description = "Warm sepia-toned classic silver-gelatin monochrome with soft archival shadows and vintage print atmosphere.",
        sharpnessWeight = 0.40f,
        noiseReductionStrength = 0.30f,
        contrastBoost = 1.18f,
        saturationScale = 0.00f,
        highlightRolloff = 0.70f,
        isMonochrome = true
    ),
    WARM_FILM(
        title = "Warm Film",
        description = "Golden-hour Kodachrome film simulation with warm amber highlights, muted cyan, and cinematic nostalgia.",
        sharpnessWeight = 0.35f,
        noiseReductionStrength = 0.35f,
        contrastBoost = 1.12f,
        saturationScale = 1.10f,
        highlightRolloff = 0.70f,
        isMonochrome = false
    ),
    COOL_CINEMA(
        title = "Cool Cinema",
        description = "Hollywood Teal & Orange palette with deep cool shadows, warm skin tones, and punchy cinematic contrast.",
        sharpnessWeight = 0.45f,
        noiseReductionStrength = 0.35f,
        contrastBoost = 1.18f,
        saturationScale = 1.12f,
        highlightRolloff = 0.65f,
        isMonochrome = false
    ),
    PORTRAIT_SOFT(
        title = "Soft Glow",
        description = "Flattering portrait aesthetic with gentle skin texture softening, delicate pastel tones, and smooth highlight glow.",
        sharpnessWeight = 0.20f,
        noiseReductionStrength = 0.45f,
        contrastBoost = 1.04f,
        saturationScale = 1.05f,
        highlightRolloff = 0.80f,
        isMonochrome = false
    );
}
