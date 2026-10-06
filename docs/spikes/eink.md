# Spike c: "E-ink look" display theme

Question: can the visual-only E-ink look from PLAN §9 be built with plain Compose and Material 3, and what does it cost? Scope: emulator only (`Pixel_36`, 1080×2424, density 420), no e-ink hardware.
Result: **works as specified**, with one correction to the plan (touch targets need a `ViewConfiguration` override as well).

## Prototype
`spikes/eink`: one Compose activity (AGP 9.4.1, Kotlin 2.4.20 with the Compose compiler plugin 2.4.20, Compose BOM 2026.09.00 / material3 1.4.0, activity-compose 1.13.0). It has a library grid of 12 synthetic covers, a text reader and a comic reader with synthetic panel pages; tap zones are 30/40/30. It is driven over adb: `am start -n com.c0mpile.grimmreader.spike.eink/.MainActivity --es look light|warm|cool --es screen library|text|comic [--ei flash N] [--ez grain true]`.

The E-ink look is one theme wrapper (`SpikeTheme`):
- Palette: warm paper `#F4F1EA` / ink `#1A1A1A`, cool paper `#EEF0EF` / ink `#161819`; every M3 surface and container role mapped to paper, primary and outline to ink, `surfaceTint` transparent; secondary text = ink @ 80 %.
- Cards: 1 dp ink border, zero elevation.
- Covers and comic pages: `ColorFilter.colorMatrix` with saturation 0 and contrast ×1.15.
- No ripples: `LocalIndication` = a no-op `IndicationNodeFactory` and `LocalRippleConfiguration` = null.
- Touch targets: `LocalMinimumInteractiveComponentSize` = 56 dp **and** `LocalViewConfiguration` with `minimumTouchTargetSize` = 56 dp.
- No animation: page content swaps directly instead of `AnimatedContent` (the Light look slides).
- Refresh flash every N turns: a full-screen ink layer, then a paper layer, each held for two frames.
- Paper grain: one pre-baked 128 px noise tile (64 KB) drawn as a repeated `ImageShader` with `BlendMode.Multiply` over the content.

## Results (`dumpsys gfxinfo`, `uiautomator dump`, screenshots)

| Check | Light | E-ink look |
|---|---|---|
| Frames per page turn, text (10 turns) | 29.0 | **1.0** |
| Frames per page turn, comic (10 turns) | 28.9 | **1.0** |
| Frames per tap on a button that changes nothing (ripple) | 49.6 | **0** |
| Minimum touch bounds of clickables (14 nodes) | 48 dp | **56 dp** |
| Covers and comic pages | colour | grayscale, higher contrast |
| Refresh flash (every 5 turns) | n/a | ink 33 ms, then paper 33 ms; 6 extra frames per flash |
| Paper grain | n/a | still 1 frame per turn; frame time p50 20 ms vs 13 ms, p90 32 vs 16 ms on the emulator's GPU |
| Memory (PSS, library screen) | — | 68–72 MB with or without grain (difference is noise) |

Screenshots were checked by eye (kept outside the repo): both tints read as paper, the grain is visible but subtle and darkens the paper slightly, and the grayscale covers keep enough contrast to tell them apart.

## Findings for the real design system
- **`LocalMinimumInteractiveComponentSize` alone is not enough.** In material3 1.4 it only reserves a 56 dp layout slot; Compose's hit testing and accessibility touch bounds come from `ViewConfiguration.minimumTouchTargetSize` (48 dp). The E-ink theme must provide both (measured: 48 → 56 dp only after the `ViewConfiguration` override).
- Removing animation by swapping content directly gives exactly one frame per turn. In the app, drive this from `LocalEinkLook` (and the system "remove animations" setting) in the reader, Navigation3 transitions, and chrome show/hide; foliate's `animated` attribute is already off in the engine spike.
- The flash at two frames per phase (≈33 ms each at 60 Hz) is visible but short. Keep it as specified, off by default, and make the hold a constant in one place.
- Grain costs about 7 ms extra per frame on the emulator, a single full-screen shader pass. That is harmless in the reader (one frame per turn) but adds up while scrolling the library. Keep it off by default, as planned, and apply it only behind reader pages, or cache it in a `graphicsLayer`.
- The theme wrapper is about 40 lines and needs no new dependencies.

## Not covered here
Navigation3 transitions and reader-chrome show/hide (no navigation library in the spike), semantics-based UI tests (the M0 scaffold adds Robolectric/Compose tests for no-ripple and 56 dp targets), and a real device: emulator frame times are only indicative.
