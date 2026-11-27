# MotoVision Pro – Comprehensive User & Photography Guide
**Application:** MotoVision Pro  
**Optimized for:** Motorola Moto G23 (MediaTek Helio G85)  

---

## 1. Overview & Interface Architecture
MotoVision Pro is engineered specifically to replace the stock camera's aggressive processing pipelines with custom computational photography algorithms designed for the Moto G23's camera hardware.

### Viewfinder Elements
- **Top Bar:**
  - `MOTOVISION PRO`: Brand header.
  - `Profile Selector` (Pill button): Quick toggle between Natural, Balanced, and Vibrant processing profiles.
  - `Info Icon`: Opens live hardware capabilities dialog showing active sensor level, RAW status, and resolution.
- **Top Right:**
  - `Real-time 4-Channel Histogram`: Displays live distribution of Luminance (white), Red, Green, and Blue channels for precise exposure evaluation.
- **Center:**
  - `Focus Peaking Overlay`: High-contrast neon green highlights indicating areas in sharp optical focus.
  - `Touch-to-Focus`: Tap anywhere on the preview to trigger 3A (Auto Focus / Auto Exposure) metering.
- **Lower Control Area:**
  - `Lens Selector`: Dedicated buttons for **0.6x (Ultrawide)**, **1x (Main Rear)**, **Macro (Close-Up)**, and **Front (Selfie)**.
  - `Mode Carousel`: Horizontal swipeable mode selector (PHOTO, NIGHT, HDR, PORTRAIT, PRO, VIDEO).
  - `Shutter Button`: Interactive dual-state shutter button with capture progress ring and video recording indicators.
  - `Gallery & Compare Thumbnail`: Lower-left thumbnail opening the interactive before-and-after comparison view.
  - `Camera Flip`: Lower-right button to quickly swap between rear and front cameras.

---

## 2. Photographic Profiles (Color Science)

MotoVision Pro provides three calibrated color profiles addressing the washed-out and unnatural tint of the stock camera:

1. **NATURAL:**
   - Minimal processing and realistic, organic color rendition.
   - Preserves natural skin texture, skin pores, and soft natural contrast.
   - Recommended for portraits, daylight landscapes, and document scanning.

2. **BALANCED (Default):**
   - Moderate contrast enhancement with intelligent shadow recovery.
   - Calibrates foliage greens and sky blues without oversaturating foliage or human subjects.
   - Recommended for general everyday photography.

3. **VIBRANT:**
   - Rich, punchy colors for architecture, sunsets, and vibrant travel scenes.
   - Employs strict gamut clamping and skin-locus protection so human faces do NOT turn orange or unnatural.

---

## 3. Dedicated Capture Modes

### Photo Mode (Intelligent Auto)
- Automatically evaluates scene luminance, dynamic range, and subject motion.
- Disables the OEM smearing edge filter (`EDGE_MODE_OFF`) and executes MotoVision Pro's custom bilateral edge-preserving filter.

### Night Mode (Multi-Frame Computational Fusion)
- Solves poor night photography on the Moto G23.
- Captures an intelligent burst of short-exposure frames.
- Uses hierarchical cross-correlation alignment to eliminate camera shake.
- Evaluates per-pixel motion confidence to prevent ghosting artifacts on moving subjects.
- Yields a clean, high-SNR photograph with boosted shadow details and protected highlights.

### HDR Mode (High Dynamic Range Exposure Fusion)
- Mertens multi-exposure blending without artificial HDR halo artifacts.
- Preserves deep shadow details and bright sky / cloud textures simultaneously.

### Portrait Mode (Advanced Front & Rear Bokeh)
- Zero default beauty distortion: skin texture, moles, and natural features are strictly preserved.
- Dynamic face-aware exposure compensation lifts backlit faces from shadows.
- Synthesizes smooth, natural depth-of-field background blur.

### Video Mode (High-Bitrate Recording)
- **1080p FHD @ 30fps & 720p HD @ 30fps**.
- **High Bitrate Mode (38 Mbps):** Overcomes the stock camera's low 12–14 Mbps compression, banishing blocky macroblocking on fine grass, trees, and water ripples.
- **Modern HEVC (H.265) & H.264 (AVC) Codecs.**
- **Electronic Image Stabilization (EIS):** Active motion damping vector smoothing.
- **Audio:** High-fidelity 48 kHz stereo AAC recording.

---

## 4. Professional Mode (Full Manual Control)

For enthusiasts and pro photographers, Pro Mode maintains total creative command:

- **ISO Slider:** Manual sensitivity adjustment from ISO 100 up to ISO 3200 (or tap 0 for Auto ISO).
- **Shutter Speed (S):** Manual exposure time from 1/10000s for fast action down to 1s for long exposures.
- **Manual Focus (MF):** Precision diopter slider from infinity ($0.0\text{d}$) to extreme close-up ($10.0\text{d}$).
- **Focus Peaking:** Real-time neon green edge overlay showing exactly what planes are in sharp focus.
- **EV Compensation:** -2.0 EV to +2.0 EV adjustment in fine 1/3 EV increments.
- **White Balance (WB):** Preset modes (Daylight 5500K, Cloudy 6500K, Shade 7500K, Tungsten 3200K, Fluorescent 4000K) or Auto.
- **RAW Toggle:** Enables uncompressed linear sensor data dumping (`.mvraw` / `.dng`) for desktop editing in Lightroom, RawTherapee, or Darktable.

---

## 5. Before & After Interactive Comparison View

After capturing any photo:
1. Tap the gallery thumbnail in the bottom-left corner.
2. The split-screen interactive comparison view opens:
   - **Left:** Original unprocessed capture (bypassing OEM smearing).
   - **Right:** MotoVision Pro computationally enhanced result.
3. Drag the center divider bar horizontally across the screen to inspect fine detail preservation, noise reduction, and color accuracy in real time!
