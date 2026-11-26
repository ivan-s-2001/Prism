# Vibe sphere

The AGSL sphere shader is adapted from [Unison WaveSphere.kt](https://github.com/Vexorter42/Unison/blob/844b796e4adbd26a4b9ff6930f8da22a6aab9edc/app/src/main/java/com/musichub/ui/WaveSphere.kt) by Vexorter42 and Unison contributors, distributed under GPL-3.0. Prism's adaptation and its source are included in this repository; see the root LICENSE and the in-app open-source licenses.

- The renderer uses the existing Prism PCM tap and FFT spectrum, not a microphone or a new audio pipeline.
- RuntimeShader is used only on API 33+ when Performance mode is off; a failed shader compilation and older Android versions use a radial gradient. Reduce motion, pause and previews stop the frame clock and the Vibe visualizer listener. Performance mode uses the static fallback.
- Theme accent/Spectrum/White Light and classic controls determine the colors; the preview supplies its own theme palette. Shader time updates at about 30 Hz, and the drawing area is bounded to a square of at most 460 dp.
- A remote station/cast route without local PCM can still show ambient motion, but must not synthesize an audio beat. The existing spectrum decays to zero when samples are absent.

Device validation: compare light/dark and Spectrum/White Light/Classic themes, change scenes, pause/resume, leave the tab, toggle Reduce motion/Performance mode, test Android 12 fallback and Android 13+ shader, and check D-pad focus on TV.
