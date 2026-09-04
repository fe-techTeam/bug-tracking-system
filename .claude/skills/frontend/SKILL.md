---
name: frontend
description: Build or change the Bug Tracking UI - Thymeleaf templates, style.css, app.js. Use whenever the task touches a page, panel, badge, board card, form, colour, icon, layout, theme, or any visible screen. Encodes the design system's tokens, the component vocabulary to reuse instead of inventing, and the no-build-step constraints.
---

# Frontend: Bug Tracking

Server-rendered Thymeleaf. **No build step, no framework, no npm.** Plain CSS with custom
properties, one vanilla-JS file. Everything you write is served as-is from
`src/main/resources/static/`, so there is nothing to compile — but also no bundler to
rescue you from a syntax error.

## Where things live

| | |
|---|---|
| `src/main/resources/templates/layout.html` | the shell: `<head>`, icon sprite, sidebar, top bar. Every page passes through it |
| `src/main/resources/templates/fragments.html` | shared fragments (`bell`) |
| `src/main/resources/templates/*.html`, `bugs/*.html` | one file per page |
| `src/main/resources/static/css/style.css` | the whole design system, ~2100 lines, sectioned |
| `src/main/resources/static/js/app.js` | one IIFE of optional enhancements |

`README.md` §"The interface" documents the visual language for users — keep it true if you
change what a cue means.

## The four rules the UI is built on

Stated at the top of `style.css`. Do not break them; a new element that contradicts them
makes the board unreadable.

1. **Status is a journey** — slate → blue → violet → teal → amber → green.
   Cool = waiting, warm = needs a person, green = done; violet is On Hold, parked
   off to the side of the track.
2. **Severity is heat** — cyan → amber → orange → red.
3. **Severity is also a count** — the 4-bar meter fills 1..4.
4. **Identity is colour** — a hue + initials derived deterministically from the name.

## Tokens, never literals

Every colour, radius, shadow, easing and duration is a `--token` in `:root`
([style.css:17-91](src/main/resources/static/css/style.css#L17-L91)), redefined for dark
([style.css:93](src/main/resources/static/css/style.css#L93)). **Never write a hex value,
a px radius, or a raw `ms` in a rule.** If a token does not exist for what you need, add
it to *both* blocks or you have just shipped a light-only element.

Enum colours are addressed through a single indirection: a class sets `--c`, the component
paints with `var(--c)`.

```html
<span class="badge st-IN_PROGRESS">In progress</span>   <!-- .st-IN_PROGRESS sets --c -->
<span class="badge" style="--c: var(--st-FIXED)">Shown</span>  <!-- one-off, no enum -->
```

The class families are `.st-*` (status), `.sev-*` (severity), `.env-*` (environment), declared at
[style.css:1430-1457](src/main/resources/static/css/style.css#L1430-L1457). A new status or
severity means: a token in both `:root` blocks, plus one line in that mapping block. Nothing
else.

## Adding a page

Every page is the same three lines of contract:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout :: page('Projects', ~{::section})}">
<body>
<section>
    <header class="topbar">
        <h1 class="topbar-title">Projects</h1>
        <span class="topbar-sub">a sentence of context, not a repeat of the title</span>
        <div class="topbar-actions">
            <div th:replace="~{fragments :: bell}"></div>
        </div>
    </header>

    <div class="content">
        <div class="stack">
            <div class="panel">
                <div class="panel-head"><h2>Add a project</h2></div>
                <div class="panel-pad">…</div>
            </div>
        </div>
    </div>
</section>
</body>
</html>
```

The `<section>` is what `~{::section}` extracts — the outer `<html>`/`<body>` are scaffolding
Thymeleaf discards. The bell goes in `topbar-actions` on **every** page, so the unread count
never moves. Use `.stack-narrow` for reading-width pages (forms, detail).

Use `th:href="@{/bugs(project=${name})}"` for URLs — never hand-built strings, they break the
context path and skip escaping.

## Reuse the vocabulary before inventing

There are ~200 classes already. Check `style.css` for one that fits before adding a rule.
The load-bearing ones:

- **Surfaces** `.panel` + `.panel-head` + `.panel-pad`, `.content`, `.stack`, `.two-col`
- **Marks** `.badge` `.badge-lg` `.badge-square`, `.tag`, `.chip`, `.dot`, `.meter`, `.count-pill`
- **Buttons** `.btn` + `.btn-primary` / `.btn-ghost` / `.btn-danger` / `.btn-sm` / `.btn-block`, `.icon-btn`
- **Identity** `.avatar` `.avatar-sm` `.avatar-lg` `.avatar-xl`, `.client-chip`, `.face`
- **Board** `.kanban` `.kcol` `.kcard`, `.bug-table`, `.toolbar`, `.fmenu` (filters)
- **Text** `.muted`, `.hint`, `.num` / `.mono` (tabular figures — use on every count and ID), `.sr-only`

### Behavioural attributes

| Attribute | Does |
|---|---|
| `th:attr="data-avatar=${p.name}"` on `.avatar` | JS paints a deterministic hue + initials. Add `aria-hidden="true"` — the name is always beside it |
| `data-time="<iso>"` | JS rewrites to "3 hours ago", keeps the exact stamp as `title` |
| `th:style="'--i:' + ${rs.index}"` on a repeated row | staggers the entry animation |

## Form fields

The base input rule **enumerates types by name** —
`input[type=text], input[type=search], input[type=email], input[type=password], input[type=date],
select, textarea` — so a field of any type not on that list gets **no theming at all**: browser
border, browser font, browser height, sitting next to controls that have all three. Adding an
`input[type=number]` or `[type=time]` means adding it to that selector *and* to the 16px
coarse-pointer rule at the bottom, or iOS zooms into it and never zooms back out.

`:root` carries **`color-scheme: light`**, flipped to `dark` in both dark blocks. That is what makes
the browser draw its *own* chrome the right way round — the calendar popup out of a date field, a
native scrollbar, the autofill background. Nothing in this stylesheet can reach those, so a new
theme block that forgets it ships a white sheet dropping out of a dark page.

## Icons

One SVG sprite at the top of `layout.html`. Reference, never inline a new path in a page:

```html
<svg class="i" aria-hidden="true"><use href="#i-check"/></svg>
```

A new icon is a new `<symbol id="i-…" viewBox="0 0 24 24">` in that sprite — outline only,
no `fill`, matching the existing Lucide-ish set. `svg.i` supplies `stroke: currentColor` and the
15px default size, so the icon takes the colour of its text and is resized per context
([style.css:2085-2100](src/main/resources/static/css/style.css#L2085-L2100)) — never set stroke or
size on the symbol itself.

## Stacking

Every `z-index` is one of the `--z-*` tokens in `:root` — `--z-toolbar` 20, `--z-topbar` 30,
`--z-stats` 40, `--z-raised` 50, `--z-nav` 60, `--z-menu` 80, `--z-toast` 95. **Never write a bare
number.**

The trap: `.panel`, `.navbar`, `.topbar`, `.toolbar` and `.stats-drawer` all carry `backdrop-filter`,
and **backdrop-filter creates a stacking context**. A menu inside a panel therefore cannot paint over
the *next* panel however high its own `z-index` goes — the panel it lives in is what gets compared.
That is why `.panel:has(details[open])` lifts the whole panel to `--z-raised` while something in it
is open. A new popover that opens inside a new container needs the same treatment, or it will be
invisible under the next card.

## JavaScript rules

`app.js` is one `(function () { "use strict"; … })()`, ES5-style (`var`, `function`) — match it.

- **Progressive enhancement is the contract.** With JS off, every page still renders and every
  action still works: filters are a `<details>` menu plus an Apply button, the board falls back
  to links. Only drag-and-drop and the collapsing chrome need scripting. Never make a control
  the *only* way to do something unless the server route also exists.
- **`localStorage` is always wrapped** — use the existing `store()` / `read()` helpers; private
  mode throws.
- Prefer **one delegated `document.addEventListener("click", …)`** with `e.target.closest("#id")`
  over per-element listeners.
- Anything read before first paint (theme, sidebar state) goes in the small inline script in
  `layout.html` — adding it to `app.js` instead causes a white flash and a snapping sidebar.
- `var calm = window.matchMedia("(prefers-reduced-motion: reduce)").matches;` already exists —
  respect it for anything scripted.

## Non-negotiables

- **Both themes.** Every new colour defined in `:root` *and* the dark block. Check the dark
  screen before saying done.
- **Reduced motion.** The global override at
  [style.css:2115](src/main/resources/static/css/style.css#L2115) kills durations and, critically,
  stagger delays. Never animate `opacity: 0 → 1` without the class also being visible when
  animation is off.
- **Keyboard.** `/` search, `n` raise, `b` sidebar, `s` stats, `p` switcher, `Esc` blur — all
  suppressed while typing. Adding a shortcut means adding it to that guard too.
- **A11y.** Decorative avatars/icons get `aria-hidden="true"`; icon-only buttons get both `title`
  and `aria-label`, and both get updated when the button's meaning flips.
- **Mobile.** One breakpoint at 780px (sidebar → rail), one at 980px (`.topbar-meta` hides).
  Keep new chrome inside them.

## Verify

```bash
mvn spring-boot:run     # http://localhost:8085 — login from application.properties
```

Templates and static files are picked up on refresh; Java changes need a restart. Before
finishing: view the page in **light and dark**, at **narrow width**, and with **JS disabled**.
