# Morningstar — Lucifer's interface language

Celestial nobility over infernal sovereignty: true-black canvas, glass shards cut at 45°, a crimson
that is only ever used to point at something, and a compass that keeps time.

This document is the contract for the Compose layer. If a screen disagrees with it, the screen is
wrong.

Status: **implemented in the working tree, not compiled by Gradle and not run on a device.** The
geometry, the palette mapping, and the arc math are host-verified; the rendered pixels are not.

## Palette

Only [Morningstar.kt](composeApp/src/androidMain/kotlin/com/lucifer/mobile/ui/theme/Morningstar.kt)
declares colour. Nothing outside `ui/theme` may write a `Color(0x…)` literal.

| Token | Value | Use |
| --- | --- | --- |
| `Void` | `#000000` | OLED canvas, first layer everywhere |
| `Obsidian` | near-black | second layer, sheet bodies |
| `Glass` / `GlassRaised` | `rgba(15,15,15,0.6)` up | glass panels |
| `Well` | darker than glass | inset fields, output panes |
| `Crimson` / `CrimsonDim` / `CrimsonLine` | `#D32F2F` family | interactive, focal, live |
| `Ember` / `Blood` | blood-orange | heat, warnings that burn |
| `Gilded` / `GildedDim` | muted gold | Lucifer's own voice, sigil marks |
| `Bone` / `Ash` / `Smoke` / `Whisper` | warm greys | text hierarchy, brightest first |
| `Edge` / `EdgeQuiet` | white / crimson at low alpha | the 1 px inner hairline |
| `Success` / `Warning` / `Error` / `Info` (+ `…Dim`) | muted | status only |

Rules:

- Crimson is a pointer, not a decoration. If two things on a screen are crimson, one of them is wrong.
- Never put bright text on a bright fill. Glass is always darker than the type on it.
- Status colour never carries meaning alone; the words say it too.

## Type

Bundled fonts, no system fallbacks: **Cinzel** (serif, 400/600) for names and numerals, **Inter**
(light/regular) for body, **JetBrains Mono** (light/regular) for terminal and instrument readouts.
Licences: `assets/fonts/OFL-Cinzel.txt`, `OFL-Inter.txt`, `OFL-JetBrainsMono.txt`.

| Builder | Family | Where |
| --- | --- | --- |
| `Morningstar.title(size)` | Cinzel SemiBold, wide tracking | wordmarks, screen names |
| `Morningstar.overline(size)` | Cinzel, +26 % tracking | section labels, indexes, dock captions |
| `Morningstar.numeral(size)` | Cinzel | the sigil's clock digits |
| `Morningstar.body(size)` | Inter Regular | prose |
| `Morningstar.caption(size)` / `label(size)` | Inter Light | secondary and tertiary text |
| `Morningstar.mono(size)` | JetBrains Mono | terminal lines, log output |

Override the properties on these styles rather than building new `TextStyle`s from scratch.

## Shape

`ShardShape` in [ShardShape.kt](composeApp/src/androidMain/kotlin/com/lucifer/mobile/ui/theme/ShardShape.kt)
is an octagon whose corners are cut at 45°, and it is wired into Material's `Shapes`, so `Card`,
`Dialog`, and every chip inherit the cut automatically. Rounded rectangles are not part of the
language.

```kotlin
Modifier.glassSurface(shard(13.dp), Morningstar.Edge)   // panel
ShardShape(cut = Morningstar.Cut, spec = Chamfer.blade(px))  // sharp top-left, cut bottom-right
```

`Chamfer.outline()` returns four vertices for a plain panel and eight for a shard — never zero-length
edges, never a repeated closing vertex.

## Depth

Compose's backdrop blur needs API 31 and this app ships `minSdk 28`, so glass is *faked* rather than
blurred: a black base, a white-to-transparent vertical gradient at 3–6 %, and a 1 px gradient border.
The result reads as depth at OLED contrast without a shader, a dependency, or an API gate. Do not
reintroduce `Modifier.blur()`.

## Motion

| Effect | Behaviour |
| --- | --- |
| `FallEasing` | heavy, deliberate downward acceleration with a soft floor — the app's only transition curve |
| `FallIn(distance, duration, delay)` | panels and drawers enter from above; nothing pops in |
| `Modifier.pressPulse(pressed, shape, color, maxSpread)` | long-press: a thin crimson outline spreads outward like a low-light energy pulse |
| `Disintegration(trigger)` | cleanup / ledger clear / close: deterministic embers burn up and fade to true black |
| `rememberBreathingAmount(min, max, period)` | status dots breathe; nothing else moves without a reason |
| `glowShadow(color, blur)` | text bloom on the most-used apps only |

Embers are seeded (`EmberField.generate(count, seed)`), so a burn looks identical every launch and
the bounds are testable.

## The sigil

`SigilClock` is the home screen's centrepiece and its only header: an occult compass that also reads
the machine. Three arcs share one 270° ring starting at 135°, each with its own colour —

- battery: crimson, jade while charging, and a crimson wedge in the 250°–290° gap below 16 % unplugged
- memory: gilded
- device load: ember

Telemetry is read on-device only, polls every 20 s while the composable is visible, and needs no
permission and no receiver. Clock digits tick once a second. Tapping the sigil opens chat.

## The home screen

Deliberately not a grid. Top to bottom: masthead (sigil + `LUCIFER` / `MORNINGSTAR SHELL` / status /
`HOLD THE SIGIL TO SPEAK`) → shard command bar with a crimson caret and rotating prompts → collapsible
output panel (96 dp ↔ 250 dp, colour-coded `[ERROR]` / `[INFO]` / `$` / `[exit`) → asymmetric drawer
(30 dp empty gutter with a hairline and an app count, then a text list of apps, favourites carrying a
bloom and a needle) → sovereign dock of five glass circles plus `λ SPEAK`.

The drawer is typography: an index numeral, a name, nothing else. No icons, no tiles, no fourth
column of anything.

## Verification

```bash
./scripts/verify_design_core.sh       # 16 assertions on chamfer + sigil + ember math
./scripts/verify_agents_core.sh       # 50 assertions + adapter type-check
./scripts/verify_resilience_core.sh   # 46 assertions + adapter type-check
```

`tools/design-core/DesignCoreTests.kt` proves the parts that can be proven off-device: cuts clamp to
half the short side and never escape the box, arcs never exceed the ring and a real reading always
draws something, the compass has 24 ticks and four cardinals, clock text is readable in 12- and
24-hour form, embers stay bounded and deterministically fade to zero.

What is **not** verified: anything visual. There is no Android SDK in this workspace, so
`shard()`/`glassSurface()`/`SigilClock` have never been compiled by AGP, never mind measured on a
360×800 screen. Treat the layout as a proposal until someone runs it.
