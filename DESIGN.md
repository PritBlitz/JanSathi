# JanSathi — Design System

> **Status:** v0.2, draft. This is the visual and interaction spec for the JanSathi frontend. It sits alongside the product brief ([docs/PROJECT_BRIEF.md](docs/PROJECT_BRIEF.md)). When this file and a product rule disagree, the product rule wins (see §1).
> **Built so far (2026-10-25):** tokens (§3–§5), the hero with per-locale width fit (§6, abstract collage placeholder until licensed photos exist), rail, tiles, bento "how it works", buttons, chips, result badges, source cards, questionnaire, ask sheet, footer and motion rules, in `web/css/styles.css`. Not built: marquee strip, sticky storytelling, big-number stats, the in-app theme toggle (system theme only). Fonts are self-hosted; an English first load fetches 2 font files (55 KB). Hindi, Bengali, Telugu and Gujarati pages add one script font, about 150–175 KB in total, slightly over the §10 font budget. Lighthouse (mobile, slow 4G, 2026-10-25, English home): performance 97, accessibility 100, best practices 100, SEO 100; LCP 2.1 s, CLS 0.

---

## 1. Design direction

**One sentence:** an editorial, Awwwards-grade, human-first site that feels like a trusted community organisation, not a government portal and not a chatbot.

We take our visual language from two sources:

1. **Hero reference:** [Voices United — Nonprofit Organization Website Design (Dribbble)](https://dribbble.com/shots/26183245-Voices-United-Nonprofit-Organization-Website-Design). Its key ideas are giant condensed type filled with photos of real people, a warm off-white page, one coral accent, pill buttons, and a row of story cards below the fold line. The JanSathi hero follows it closely. See §6.
2. **Everything else:** patterns from [Awwwards](https://www.awwwards.com/) Site of the Day / Honourable Mention work, especially the nonprofit, editorial and public-service categories. Every new screen or component must name the Awwwards pattern it draws on in its PR (see §12).

### Where "Awwwards style" stops

Awwwards sites are often built for fast laptops and fibre broadband. Our users include low-literacy, first-time smartphone users on cheap Android phones with patchy 3G. When an Awwwards pattern (or the hero reference) conflicts with any rule below, **the rule wins**, and we keep the look but drop the cost:

| Rule (non-negotiable) | What it means for the design |
|---|---|
| Voice and icons first | Every section can be read aloud; the main actions are picture tiles, not text links. |
| Accessible | WCAG 2.2 AA, touch targets ≥ 48 px, visible focus, keyboard-only use works, screen-reader labels on everything. |
| Low-end devices | Performance budget in §10. No WebGL, no scroll-jacking, no smooth-scroll libraries, no custom cursor. |
| 10 languages, 9 scripts | No layout may depend on English word length. Uppercase and tight letter-spacing are for English only (§4.3). |
| Every string through i18n | No text baked into images or SVGs. |
| Never promise eligibility | Copy rules from the brief (§5) apply to every headline, label and CTA. |
| Not a government site | No State Emblem, Ashoka Chakra, tricolour treatment or ministry branding. Their use is restricted by law, and it would mislead users about who we are. |

---

## 2. Brand personality

| We are | We are not |
|---|---|
| Warm, calm, neighbourly | Bureaucratic, cold |
| Honest about uncertainty | Salesy, "guaranteed!" |
| Bold editorial type | Dense forms and tables |
| Real people, real places | Clip-art, stock "happy farmer" clichés |
| Quietly crafted motion | Flashy, attention-grabbing animation |

Voice of the UI copy: short sentences, grade-5 reading level, second person, active voice. "Find schemes you may qualify for", never "You are eligible".

---

## 3. Colour

Warm off-white page, near-black ink, one coral accent (from the hero reference), plus a green and a marigold used only for status. The palette deliberately avoids saffron/white/green as a set, so the site does not read as official or political.

### 3.1 Tokens — light (default)

| Token | Hex | Use | Contrast |
|---|---|---|---|
| `--paper` | `#F2F0EA` | Page background | — |
| `--surface` | `#FFFFFF` | Cards, sheets | — |
| `--surface-2` | `#E6E3DA` | Sunken areas, tile backgrounds | — |
| `--ink` | `#1B1A17` | Body and display text, outlines | 15.3 : 1 on paper |
| `--ink-muted` | `#5E5A52` | Secondary text | 6.0 : 1 on paper, 5.4 : 1 on surface-2 |
| `--coral` | `#F26B43` | Primary button fill, logo, decorative | **ink** text on it 5.8 : 1. Never as text colour (2.7 : 1 on paper). |
| `--coral-text` | `#B53D1A` | Coral-coloured small text: card kickers, links | 5.1 : 1 on paper |
| `--leaf` | `#2F6B4F` | "Likely eligible", confirmed labels | white on it 6.3 : 1; as text on paper 5.5 : 1 |
| `--marigold` | `#F2A93B` | "Need more info" badge fill | ink on it 8.7 : 1. Never as text colour. |

**Deviation from the reference:** the Dribbble shot puts white text on its coral button. That is about 3.0 : 1 and fails WCAG AA, so our primary button uses `--ink` text on `--coral`.

### 3.2 Tokens — dark

| Token | Hex |
|---|---|
| `--paper` | `#14130F` |
| `--surface` | `#1E1C18` |
| `--surface-2` | `#2A2722` |
| `--ink` | `#F3EEE6` (16.1 : 1) |
| `--ink-muted` | `#ABA59A` (7.6 : 1) |
| `--coral` | `#FF8A66` (`--paper` text on it 8.0 : 1) |
| `--coral-text` | `#FF8A66` (8.0 : 1) |
| `--leaf` | `#7FC8A0` (9.5 : 1) |
| `--marigold` | `#F2A93B` (9.3 : 1) |

Define tokens on `:root`. Switch them with `@media (prefers-color-scheme: dark)` and also allow a manual override with `:root[data-theme="dark"]` / `[data-theme="light"]`.

### 3.3 Semantic colours (results and trust labels)

Colour is **never** the only signal. Every state also has an icon and a text label, and the label is spoken by text-to-speech.

| Meaning | Colour | Icon | Label key |
|---|---|---|---|
| Likely eligible | `--leaf` | ✓ in circle | `result.likely` |
| Need more info | `--marigold` (bg) + ink text | ? in circle | `result.need_info` |
| Not eligible | `--ink-muted` on `--surface-2` | – in circle | `result.not_eligible` |
| Confirmed rule | `--leaf` outline chip | shield | `trust.confirmed` |
| Official guidance | `--ink` outline chip | document | `trust.guidance` |
| Unverified / may be outdated | `#8A5A00` text on `#FFF4DC` (5.4 : 1) | warning triangle | `trust.unverified` |

"Not eligible" is shown in a neutral style, not alarm red. It is information, not a failure on the user's part.

---

## 4. Typography

### 4.1 Families

| Role | Latin | Indic scripts |
|---|---|---|
| Hero display (knockout word only) | **Anton**, 400 (ultra-condensed, OFL) | Noto Sans <Script>, weight 900 (the brand name in that script; see §6.3) |
| Section titles | Noto Sans, 800 | Noto Sans <Script>, 800 |
| UI and body | Noto Sans, 400–600 | Noto Sans Devanagari / Bengali / Tamil / Telugu / Gujarati / Kannada / Malayalam / Gurmukhi, 400–600 |
| Wordmark | Anton (see §8) | — |

- Anton covers Latin only, so it is used for the English hero word and the wordmark. Every other piece of text is Noto, so all 10 languages look like one product.
- Load only the active script plus Latin. Use `unicode-range` subsets and `font-display: swap`.
- Self-host the font files with the PWA so they work offline.

### 4.2 Scale (fluid, mobile → desktop)

| Token | Size | Line-height (Latin / Indic) |
|---|---|---|
| `--fs-display` | `clamp(5rem, 22vw, 17rem)` | 0.88 / 1.1 |
| `--fs-h1` | `clamp(2rem, 4vw + 1rem, 4rem)` | 1.05 / 1.3 |
| `--fs-h2` | `clamp(1.5rem, 2vw + 1rem, 2.5rem)` | 1.15 / 1.35 |
| `--fs-lead` | `clamp(1.125rem, 0.6vw + 1rem, 1.375rem)` | 1.5 / 1.7 |
| `--fs-body` | `1.125rem` (18 px minimum) | 1.55 / 1.75 |
| `--fs-label` | `0.9375rem` (nav, buttons, kickers) | 1.3 / 1.5 |

Body text is never smaller than 16 px. Default body is 18 px for our audience.

### 4.3 Script rules

- Set `:lang(hi), :lang(bn) …` overrides for line-height. Indic scripts need extra room above and below for vowel signs (matras).
- **Uppercase** (`text-transform: uppercase` + `letter-spacing: 0.04em`) is used, as in the reference, for nav links, button labels and card kickers, **only under `:lang(en)`** and only for labels of three words or fewer. It has no meaning in Indic scripts, and long uppercase text is hard to read.
- Tight or negative letter-spacing is for `:lang(en)` only. Indic glyphs are joined and must keep `letter-spacing: 0`.
- Expect translated strings to be **up to 2× longer** than English (Tamil and Malayalam especially). Headlines use `text-wrap: balance`, and no layout may rely on a fixed number of lines.

---

## 5. Layout, spacing, shape

- **Grid:** 4 columns on mobile, 8 on tablet, 12 on desktop. Max content width 1440 px. Side gutter `clamp(16px, 4vw, 56px)`.
- **Spacing scale (px):** 4, 8, 12, 16, 24, 32, 48, 64, 96, 144. Section padding `clamp(64px, 10vw, 144px)` top and bottom. Generous whitespace is the main Awwwards cue we keep.
- **Radius:** `--r-sm: 12px` (chips, inputs), `--r-md: 20px` (cards, tiles), `--r-lg: 32px` (media, sheets), `--r-pill: 999px` (buttons, tags), `50%` (avatars, icon buttons).
- **Borders:** 1.5 px `--ink` outlines for secondary buttons and icon buttons, as in the reference.
- **Elevation:** flat. Separate surfaces with colour, not shadows. One soft shadow token for floating elements only: `0 12px 40px rgb(27 26 23 / 0.12)`.
- **Touch targets:** at least 48 × 48 px, with at least 8 px between targets.
- No horizontal page scroll at 320 px width.

---

## 6. Hero component

> **Reference:** [Voices United (Dribbble)](https://dribbble.com/shots/26183245-Voices-United-Nonprofit-Organization-Website-Design). The anatomy below matches the shot; differences are listed in §6.7.

### 6.1 Anatomy (desktop, ≥ 1024 px)

```
┌──────────────────────────────────────────────────────────────────────────┐
│ ▌▌▌▌ JANSATHI      SCHEMES   HOW IT WORKS   NEARBY HELP     🌐▾  (🔊 LISTEN) │  ← nav
│                                                                          │
│     ┌──────────────────────────────────────────────────────────────┐     │
│     │                                                              │     │
│     │        JanSathi      (en)                                    │     │  ← one giant word,
│     │        जनसाथी        (hi)                                    │     │    the brand name in the
│     │                                                              │     │    active language's
│     │   letters filled with a photo collage of real people         │     │    script, filled with
│     └──────────────────────────────────────────────────────────────┘     │    photos (knockout)
│                                                                          │
│        Government schemes, explained in your language. Every answer      │  ← lead (added)
│                       links to an official source.                       │
│                                                                          │
│          ( CHECK WHAT I MAY QUALIFY FOR )   ( 🎤 ASK BY VOICE )           │  ← pill CTAs
│                                                                          │
│  (◉) SCHOLARSHIP       ↗   (◉) PENSION          ↗   (◉) FARMING   ░░░░  │  ← card rail,
│      Post-matric scholar-      Old-age pension:         Crop insurance ░░  │    fades at right
│      ship closes 30 Nov…       documents you need…      sign-up is …  ░░  │
└──────────────────────────────────────────────────────────────────────────┘
```

### 6.2 Parts

| # | Part | Spec (reference → JanSathi) |
|---|---|---|
| 1 | **Nav** | Reference: logo left, 4 uppercase links centred, globe dropdown and outlined "DONATE" pill on the right. **Ours:** waveform logo + wordmark left; 3 links centred (`SCHEMES`, `HOW IT WORKS`, `NEARBY HELP`); globe language picker; an outlined pill "🔊 LISTEN" that reads the page aloud. On mobile the links collapse into a full-screen menu; the globe and Listen buttons always stay visible. Height 88 px desktop / 64 px mobile; no background until the page scrolls, then `--paper` with a 1 px bottom border. |
| 2 | **Knockout display word** | Reference: "VOICES / UNITED" in ultra-condensed type, centred on two lines, filled with a collage of faces. **Ours:** the brand name as **one word, written in the active language's script**: `JanSathi` in English, `जनसाथी` in Hindi, and so on (i18n key `hero.display`; full list in §6.3). It sits on one line and is sized to fill the content width (§6.5). Fill with a single collage image using `background-clip: text` and `color: transparent`. See §6.4 for the image and §6.5 for the fallback. |
| 3 | **Lead** *(added)* | The reference shows no copy here; we need one line so the page says what JanSathi does. `--fs-lead`, `--ink-muted`, centred, max 52 ch. It must state the "official source" promise. |
| 4 | **CTAs** | Reference: filled coral pill "DONATE" next to an outlined pill "TELL MY STORY", both about 190 × 62 px. **Ours:** filled `--coral` pill with **ink** text → questionnaire; outlined `--ink` pill with a mic icon → opens the voice ask sheet (the prototype's ask box, moved into a sheet to keep the hero clean). Both min 56 px tall, min 200 px wide, and they stack full-width under 480 px. |
| 5 | **Card rail** | Reference: three cards along the bottom, each with a circular photo, a coral uppercase kicker, a two-line bold headline cut off with an ellipsis, and a circular outlined ↗ button; the row fades out at the right edge. **Ours:** "What's open now" cards. Kicker = scheme category (`--coral-text`); headline = a real, sourced fact such as a deadline or a document tip; ↗ → scheme detail page. Horizontal `scroll-snap` rail with a fade mask on the trailing edge (the left edge in RTL), plus previous/next buttons for keyboard and mouse users. |

### 6.3 Copy (English source strings, i18n keys)

| Key | English |
|---|---|
| `hero.display` | JanSathi |
| `hero.display_label` | JanSathi: find government schemes you may qualify for |
| `hero.lead` | Government schemes, explained simply in your language. Every answer links to an official source. |
| `hero.cta_primary` | Check what I may qualify for |
| `hero.cta_voice` | Ask by voice |
| `nav.listen` | Listen |
| `rail.title` | What's open now |

**`hero.display` per locale** (starting values; transliterations of the brand name, not translations):

| Locale | `hero.display` | Font |
|---|---|---|
| en | JanSathi | Anton |
| hi | जनसाथी | Noto Sans Devanagari 900 |
| mr | जनसाथी | Noto Sans Devanagari 900 |
| bn | জনসাথী | Noto Sans Bengali 900 |
| gu | જનસાથી | Noto Sans Gujarati 900 |
| pa | ਜਨਸਾਥੀ | Noto Sans Gurmukhi 900 |
| te | జనసాథీ | Noto Sans Telugu 900 |
| kn | ಜನಸಾಥಿ | Noto Sans Kannada 900 |
| ml | ജനസാഥി | Noto Sans Malayalam 900 |
| ta | ஜனசாத்தி | Noto Sans Tamil 900 |

Every spelling must be confirmed by a native speaker before launch. **Tamil needs particular care:** Tamil has no aspirated "th", and a close spelling, சாதி (*sāti*), means "caste". The Tamil reviewer must pick a form that can't be read that way, or the Tamil locale should use the Latin `JanSathi`.

Card text comes from scheme data, not locale files. A card is shown **only** if its fact has a published source with `verified_at`; the card's detail page shows that source. If there are no such facts, the rail is hidden. It never shows placeholder or invented stories.

### 6.4 Knockout collage image

- One pre-composed image (not live-tiled photos), cropped so faces fall inside the letter shapes at the desktop breakpoint, with a separate mobile crop.
- People: a student, an older woman, a farmer, a person using a wheelchair, a mother and child, a CSC volunteer. Represent several regions and ages.
- Use only photos with a clear licence (commissioned with signed model releases, or a free licence such as Unsplash/Pexels with no implied endorsement). Record the licence and source in `web/img/CREDITS.md`.
- No photos of real officials, offices with government signage, or ID documents.
- Warm grade to sit on `--paper`. AVIF → WebP → JPEG via `image-set()`; ≤ 140 KB on mobile, ≤ 260 KB on desktop.
- The card-rail avatars are 72 px circles, ≤ 8 KB each.

### 6.5 Accessibility and fallbacks for the knockout word

- The `<h1>` contains the visible word, with `aria-label` from `hero.display_label`, so screen readers announce a meaningful title rather than just the brand name. The label is in the active language too.
- Contrast: the collage is graded dark enough that every letter reads as a solid dark shape against `--paper` (target ≥ 3 : 1 for the letter area on average; check with a blurred screenshot).
- Before the image loads, or if `background-clip: text` is unsupported (`@supports not (background-clip: text)`), the word renders in solid `--ink`. A slow network never shows invisible text.
- `forced-colors: active` (Windows High Contrast): solid `CanvasText`, no image.
- Dark mode: same image, with `--paper` dark behind it. Check that the letters still read as shapes; brighten the grade if they don't.
- **Sizing:** the word is always one line and fills the content width. Lengths differ a lot between scripts (Anton `JanSathi` is narrow; `ஜனசாத்தி` in Noto Sans Tamil is much wider), so each locale gets a width-fit factor: `font-size: calc(var(--fit, 1) * 22cqi)` on a `container-type: inline-size` wrapper, with `--fit` set per `:lang()` and tuned once per locale. No JavaScript text-fitting.
- Indic locales use Noto Sans <Script> at weight 900, with line-height 1.1–1.2 so vowel signs above and below the letters aren't clipped by the image mask. Add top and bottom padding inside the clipped element for the same reason.
- Joined Indic letters (conjuncts) make thinner strokes than Anton. Check that faces stay recognisable in the collage, and use a tighter crop for Indic locales if they don't.

### 6.6 Motion (hero only)

- On load: the two display lines rise 32 px and fade in, staggered 80 ms; the collage drifts very slightly (scale 1.06 → 1.0 over 1.2 s). Lead, CTAs and rail follow at 60 ms steps. Easing `cubic-bezier(0.2, 0.7, 0.2, 1)`.
- Content is **visible without JavaScript**. Animation is added by a class after first paint, so a slow script never hides the headline.
- `prefers-reduced-motion: reduce` → no movement, instant opacity only.

### 6.7 Differences from the reference (and why)

| Reference | JanSathi | Why |
|---|---|---|
| White text on coral button | Ink text on coral | White is 3.0 : 1 (fails AA); ink is 5.8 : 1 |
| No supporting copy | One-line lead under the word | The brand name alone doesn't tell a first-time user what the site does |
| "DONATE" in the nav | "🔊 LISTEN" | Voice-first: reading the page aloud is the most important action for low-literacy users |
| Uppercase everywhere | Uppercase for short English labels only | Uppercase has no meaning in Indic scripts |
| Story cards | Sourced "what's open now" cards | Every scheme fact must link to a verified source |

### 6.8 Mobile (< 600 px)

Nav (logo, globe, Listen, menu) → display word (one line, edge to edge within the gutters) → lead → CTAs stacked full-width → card rail showing 1.2 cards so the next card peeks in. The voice CTA stays above the fold on a 360 × 640 screen.

---

## 7. Component patterns (Awwwards-derived)

Each pattern lists where it comes from and how we adapt it.

| Pattern | Awwwards source idea | JanSathi adaptation |
|---|---|---|
| **Image-filled display type** | The hero reference | Used **once**, in the hero. Not repeated elsewhere. |
| **Oversized section titles** | Large display type with lots of air | `--fs-h1` titles with a coral kicker above. |
| **Bento grid** | Mixed-size cards in a tight grid | "How it works" (4 steps) and the scheme-detail overview: summary, documents, deadline, source. One column on mobile. |
| **Sticky storytelling** | A pinned visual while text steps scroll past | "How JanSathi checks a scheme": rules → sources → explanation. `position: sticky` only, no scroll-jacking. A plain list under reduced motion or on small screens. |
| **Marquee strip** | Infinite horizontal ticker | Scheme categories in all 10 scripts. Pauses on hover/focus, has a pause button, is `aria-hidden` (with the same content available as a list), and stops under reduced motion. |
| **Numbered steps** | Large outline numerals | Questionnaire progress and document checklist. Numerals use the active locale's digits via `Intl.NumberFormat`. |
| **Big-number stats** | Impact figures in display type | Only real figures from our own data (e.g. "30 schemes verified"). No invented or rounded-up numbers. |
| **Card hover lift** | Subtle translate + image zoom | Scheme cards: 4 px lift and border colour change. Same effect on `:focus-visible`. |
| **Full-bleed footer** | Giant wordmark footer | Wordmark, "Report outdated info", privacy, language list, and the disclaimer: *"JanSathi is not a government service. Final decisions are made by the government."* |

### 7.1 Core components

- **Button:** pill, min height 56 px (hero) / 48 px (elsewhere), 24–32 px horizontal padding, icon + label. Variants: primary (`--coral` fill, ink text), secondary (1.5 px `--ink` outline), quiet (text). Every icon-only button has an `aria-label` from i18n.
- **Icon button:** 44 px visual circle with a 1.5 px `--ink` outline (the reference's ↗ button), inside a 48 px hit area.
- **Category tile:** `--surface-2`, `--r-md`, icon 40 px, label below, min 96 × 96 px. Selected state: `--ink` 2 px border and a check badge (not colour alone).
- **Story / scheme card (rail):** 72 px circular photo, `--coral-text` kicker, 2-line headline (`-webkit-line-clamp: 2`, full text in the link's accessible name), ↗ icon button. The whole card is one link.
- **Scheme card (lists):** name, one-line benefit, result badge (§3.3), trust chip, "verified {date}" line, chevron.
- **Result badge:** pill with icon + label, colours from §3.3.
- **Source citation:** small card with publisher, title, `source_url` domain, published date and verified date. An outdated source shows the unverified chip and a warning line.
- **Questionnaire step:** one question per screen, answers as large icon tiles, a mic button, and a "Skip — I'm not sure" option that leads to "Need more info" rather than blocking.
- **Voice ask sheet:** bottom sheet with a 96 px mic button, live transcript, a typing fallback, and the answer view below.
- **Answer view:** answer text, trust label chip, inline numbered citations, "Read aloud" button, disclaimer line. "No official source found" is a styled empty state with a suggestion, never a blank bubble.

---

## 8. Logo, icons, illustration

- **Logo mark:** a cluster of vertical coral bars of varying height, like a sound waveform (the reference uses a similar mark). For JanSathi it stands for voice. Inline SVG, `aria-hidden`; the wordmark text is the accessible name.
- **Wordmark:** "JANSATHI" in Anton, `--ink`. A custom logotype can replace it later.
- **Icons:** emoji today (from the prototype), to be replaced by one line-icon set with a 2 px stroke and rounded joins on a 24 px grid. Icons always come with a label; they never replace it.
- Decorative shapes are inline SVG in `--coral` or `--marigold`, `aria-hidden`.

---

## 9. Motion system

| Token | Value | Use |
|---|---|---|
| `--ease-out` | `cubic-bezier(0.2, 0.7, 0.2, 1)` | Entrances |
| `--ease-in-out` | `cubic-bezier(0.65, 0, 0.35, 1)` | Sheets, menus |
| `--dur-fast` | 150 ms | Hover, press |
| `--dur-base` | 300 ms | Cards, chips |
| `--dur-slow` | 500 ms | Section reveals |

- Scroll reveals use `IntersectionObserver` with a small opacity + 16 px translate, once per element.
- Animate only `transform` and `opacity`.
- Everything respects `prefers-reduced-motion`, plus an in-app "Reduce motion" toggle stored in `localStorage`.
- Banned: scroll-jacking, smooth-scroll libraries, WebGL/canvas backgrounds, custom cursors, autoplaying video with sound, parallax on text.

---

## 10. Performance budget

Measured on a mid-range Android phone (Moto G-class) on throttled "Slow 4G" in Lighthouse.

| Metric | Budget |
|---|---|
| LCP | ≤ 2.5 s (the LCP element is the display word; it renders in solid ink before the image arrives) |
| CLS | ≤ 0.05 |
| INP | ≤ 200 ms |
| JS (gzipped, first load) | ≤ 60 KB |
| CSS (gzipped) | ≤ 25 KB |
| Fonts (first load) | ≤ 3 files, ≤ 150 KB total |
| Hero collage | ≤ 140 KB mobile / ≤ 260 KB desktop |

Animation libraries (e.g. GSAP) are allowed only if lazy-loaded after first paint and only if the budget still holds. Prefer CSS and the Web Animations API.

---

## 11. Accessibility checklist (design side)

- [ ] Text contrast ≥ 4.5 : 1 (≥ 3 : 1 for 24 px+ display text); tokens in §3 already meet this.
- [ ] Visible focus ring: 3 px `--ink` outline, 3 px offset, on every interactive element (coral is not used for focus because it is too light on paper).
- [ ] Touch targets ≥ 48 px.
- [ ] State shown by icon + text + colour, never colour alone.
- [ ] Works at 200% zoom and at 320 px width without horizontal scroll.
- [ ] Every section has a "read aloud" path; every tile speaks its name.
- [ ] Reduced-motion version reviewed for every animated component.
- [ ] Checked in at least English, Hindi, Tamil and Malayalam (longest strings) before merge.

---

## 12. Process

- **New screen or component:** the PR description includes (1) the Awwwards pattern it draws on, with a link to the example site; (2) screenshots in light and dark, English and one Indic language, at 360 px and 1280 px; (3) Lighthouse and axe results.
- **Changing tokens:** change them here first, then in `web/css/styles.css`. Keep the names in this file and the CSS identical.
- **Conflicts:** if an inspiration conflicts with §1's rule table, follow the rule and note the trade-off in the PR.

## 13. Open design questions

1. Wordmark (nav): commission a logotype, or keep "JANSATHI" in Anton for now? Should the nav wordmark also switch script with the language, like the hero word?
2. Hero photography: commission a shoot with consent and releases, or start with licensed stock?

**Decided:** the hero display word is the brand name localised per language (2026-10-24, §6.3).
