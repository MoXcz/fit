# Content format

Exercises, routines, programs, recovery sessions and lessons are Markdown files with a YAML header.
Built-in ones live in [`content/`](../content) and ship in the app; any file in this format can also
be imported on the phone (a `.md` file, or a `.zip` pack with images). This is the format agents
write when they research a routine or a recovery session. Rules for the parser are in
[engineering.md §3](engineering.md#3-content-files-are-the-contract).

> **Status: format 1.** The app reads `exercise`, `routine`, `program`, `recovery` and `lesson` files.

## Files and ids

```
content/
  exercises/<id>.md
  routines/<id>.md
  programs/<id>.md
  recovery/<id>.md
  lessons/<id>.md
  images/<name>.webp        (or .png, .jpg; referenced as images/<name>.webp)
```

- `id` is a lowercase slug (`[a-z0-9-]+`) unique within its kind, and the file name is `<id>.md`.
  Items of different kinds may share an id (the lesson `push-up` teaches the exercise `push-up`). It
  never changes: re-importing a file with the same id updates that item.
- Every file starts with a YAML header between `---` lines, then a Markdown body.
- `format: 1` is required in every header.
- Durations are whole seconds (`45`) or with a unit (`45s`, `2m`, `1m30s`). Weights are numbers in
  the item's unit.
- Unknown keys are an error, not ignored, so typos are caught. So is a key given twice.
- The YAML subset is: strings, numbers, booleans, lists and maps. No anchors, tags or multi-documents.
- Every problem is reported as `file:line: field must be …`, e.g.
  `exercises/plank.md:9: defaults.reps must be a number of reps or a range such as 8-12`.

## Body areas

Recovery sessions and exercises can name the areas they help. The fixed list (the Recover tab groups
by it): `wrists`, `forearms`, `elbows`, `shoulders`, `neck`, `upper-back`, `lower-back`, `hips`,
`knees`, `ankles`, `feet`, `full-body`.

## Exercise

```markdown
---
format: 1
kind: exercise
id: wrist-flexor-stretch
name: Wrist flexor stretch
category: Mobility
tracks: [time]            # any of: weight, reps, time, distance
sides: each               # both (default) or each: guided steps run once per side
areas: [wrists, forearms]
equipment: bodyweight     # see the table below
defaults:
  sets: 1
  reps: 10                # or a range: reps: 8-12
  rest: 0                 # or a range: rest: 90-120
  duration: 30
  weight: 0
units: { weight: kg, distance: km }
video: https://www.youtube.com/watch?v=…
images: [images/wrist-flexor-stretch.webp]
refs:
  - { kind: video, title: "…", url: https://… }
---
Arm straight, palm up. Pull the fingers back with the other hand until you feel a stretch in the
forearm. No pain, no bouncing.
```

The body is the exercise's notes (shown on the exercise page and as the cue in the guided player).

Only `name` is required. The rest, and what each accepts:

| Key | Values | Default |
| --- | --- | --- |
| `category` | any text; the library lists the usual ones (Chest … Mobility, Full body, Other) first | none |
| `tracks` | one or more of `weight`, `reps`, `time`, `distance` | `[weight, reps]` |
| `sides` | `both` or `each` | `both` |
| `areas` | body areas from the list above | none |
| `equipment` | `barbell`, `dumbbell`, `kettlebell`, `cable`, `machine`, `band`, `bodyweight` or `other`; plates per side show only for `barbell` | none |
| `defaults.sets` | 1–20 | 3 |
| `defaults.reps` | 1–1000, or a range `8-12` | 10 |
| `defaults.rest` | 0 to 1 hour, or a range `90-120` / `2m-4m` | 90 |
| `defaults.duration` | 1 s to 2 hours | 45 |
| `defaults.weight` | 0 or more, in `units.weight` | 0 |
| `units.weight` / `units.distance` | `kg`, `lb` / `km`, `mi`, `m` | `kg` / `km` |
| `video`, `refs[].url` | a web address starting with `https://` or `http://` | none |
| `refs[].kind` | `link`, `video`, `blog` or `paper` | `link` |
| `images` | `images/<name>.webp`, `.png` or `.jpg`; the file must exist | none |

A built-in exercise is updated by a new app version only until you edit it in the app; after that,
your version stays.

## Routine

```markdown
---
format: 1
kind: routine
id: upper-dumbbell
name: Upper body — dumbbell
items:
  - { exercise: jumping-jacks, section: Warm-up, duration: 30 }
  - { exercise: incline-dumbbell-press, sets: 3, reps: 8-15, rest: 90, progress: { step: 2, rpe: 8 }, warmup-sets: 2 }
  - { exercise: pull-up, sets: 3, reps: 8-15, rest: 90 }
  - { exercise: wrist-flexor-stretch, section: Stretch }
sources:
  - { title: "…", url: https://… }
---
Optional notes about the routine.
```

`name`, `items` (at least one) and `sources` (at least one) are required; the body is the routine's
notes.

| Item key | Values | Default |
| --- | --- | --- |
| `exercise` | an exercise id (required; the content check fails on an unknown one) | |
| `section` | `Warm-up`, `Main`, `Stretch` or any name | `Main` |
| `sets`, `reps`, `rest`, `duration` | as in the exercise's `defaults` | the exercise's defaults |
| `tracks` | "record as": what this slot records instead of the exercise's own | the exercise's |
| `progress` | `{ step: 2.5, rpe: 8 }`: add `step` (0.25–100, in the exercise's unit) when the last workout hit every target, only if its hardest set was at most `rpe` (5–10 in halves; optional) | none |
| `warmup-sets` | 0–3 | 0 |
| `notes` | text | none |

Sections appear in the order they first appear in the list. A source is `{ title: "…", url: https://… }`;
the `url` is optional (for a book or a coach's handout).

A built-in routine is updated by a new app version only until you change it in the app.

## Program

```markdown
---
format: 1
kind: program
id: upper-lower-4
name: 4 day upper/lower
days: { mon: upper-a, tue: lower-a, thu: upper-b, fri: lower-b }
deload-every: 6
deload-percent: 10
---
Optional notes about the program.
```

`name` and `days` are required; the body is the program's notes.

| Key | Values | Default |
| --- | --- | --- |
| `days` | a map from `mon`, `tue`, `wed`, `thu`, `fri`, `sat`, `sun` to a routine id, at least one day; weekdays left out are rest days (the content check fails on an unknown routine) | |
| `deload-every` | 2–16: every Nth week is a deload week | never |
| `deload-percent` | 5–50: how much a deload week cuts the loads by | 10 |

A built-in program arrives inactive and without a start date; activating it starts week 1 this week
(you can pick another start). It is
updated by a new app version only until you change it in the app.

## Recovery session

A short follow-along session (5–15 min) of timed or counted steps, started from the Recover tab, a
launcher shortcut or a reminder.

```markdown
---
format: 1
kind: recovery
id: wrist-reset
title: Wrist reset
areas: [wrists, forearms]
minutes: 8                # what the list shows; the content check compares it with the steps (±2)
transition: 5             # seconds to get into position before each step and round (default 5)
steps:
  - { exercise: wrist-flexion-rocks, reps: 10 }
  - { exercise: wrist-flexor-stretch, duration: 30 }           # sides: each from the exercise → 2 × 30 s
  - { name: Prayer stretch, duration: 30, cue: "Palms together, lower slowly", image: images/prayer.webp }
  - { name: Shake out, duration: 15 }
sources:
  - { title: "…", url: https://… }
---
When to use it, what to avoid, and when to see someone instead of stretching.
```

`title`, `minutes`, `steps` (at least one) and `sources` (at least one) are required; the body is the
session's notes, in the [Markdown subset](#markdown).

| Key | Values | Default |
| --- | --- | --- |
| `areas` | body areas from the list above; the Recover tab shows a session under each | none |
| `minutes` | 1–120, within 2 of the steps' total (below) | |
| `transition` | 0 s to 2 min | 5 s |

A step names an `exercise` id or gives a `name` inline (an inline step doesn't create a library
exercise), never both. It has a `duration` (1 s to 2 hours) or `reps` (1–1000; counted steps wait
for **Done**), never both. A step that names an exercise gives a `duration` only if the exercise
tracks `time`, and `reps` only if it tracks `reps`; the content check reports any other at the step's
line. Optional:

| Step key | Values | Default |
| --- | --- | --- |
| `sides` | `both` or `each`: an `each` step runs once per side | the exercise's; `both` for an inline step |
| `rounds` | 1–20 | 1 |
| `cue` | text the player shows | the exercise's notes; none for an inline step |
| `image` | `images/<name>.webp`, `.png` or `.jpg`; the file must exist | the exercise's first image |

How a session runs, which is also what `minutes` is checked against:

- Each step runs its rounds in turn, and each round both sides when it has two: L, R, L, R.
- The `transition` comes before every step and every round, the first included, to get into
  position. Between the two sides of a round there's a 5 s "Switch sides" instead, whatever the
  `transition`. A `transition` of 0 means no pause between steps.
- The steps' total adds up every timed step, every counted step at 3 s a rep, and every pause. The
  content check fails when `minutes` is more than 2 away from it.

A new app version replaces its built-in recovery sessions and keeps their pins. A session can't be
edited in the app, only deleted (a deleted built-in one stays deleted) or replaced by
[importing](#importing) a file with its id; an imported one is yours and isn't replaced by app
updates.

## Lesson

Notes for teaching yourself an exercise, with an optional practice drill run in the same guided
player as a recovery session. The app counts how often you practised it.

```markdown
---
format: 1
kind: lesson
id: pistol-squat
title: Pistol squat
exercises: [pistol-squat, box-pistol]
practice:
  transition: 5
  steps:
    - { exercise: box-pistol, reps: 5, sides: each }
    - { name: Ankle rocks, duration: 30 }
sources:
  - { title: "…", url: https://… }
---
## Setup
…
## Cues
- …
## Common mistakes
- …
## Progressions
1. …

![Bottom position](images/pistol-bottom.webp)
```

`title`, `sources` (at least one) and a body are required; the body is the lesson, in the
[Markdown subset](#markdown).

| Key | Values | Default |
| --- | --- | --- |
| `exercises` | exercise ids: the movements the lesson teaches. Each one's page links to the lesson. An exercise used only in the drill isn't listed. | none |
| `practice.transition` | 0 s to 2 min, as a recovery session's | 5 s |
| `practice.steps` | at least one step, with the [recovery session's step keys](#recovery-session) and the same `tracks` rule | |

- **Id:** when the lesson teaches one exercise, use that exercise's id (`push-up`); otherwise any
  slug unique among lessons (`bracing-basics`).
- **Body:** Setup, Cues, Common mistakes and Progressions as `##` sections are recommended, not
  required. Any Markdown in the subset may appear, in any order (a closing **Stop if** paragraph,
  say).
- **Length:** a drill has no `minutes`. The lesson page shows an estimate worked out by the
  recovery session's rules: the transition before every step and round, a 5 s switch between
  sides, and 3 s a rep.
- The content check reports an unknown exercise in `exercises` or a step, a missing image in the body
  or a step, and Markdown outside the subset, each at its line, and an empty body at the header's
  closing `---`.

A practice run is logged when it ends with at least one step done, like a recovery session, and
counts as practice whether it was completed or stopped. It never marks the recovery calendar or
counts toward workouts, volume, records or streaks. A new app version replaces its built-in lessons
and keeps their practice counts. A lesson can't be edited in the app, only deleted (a deleted built-in
one stays deleted) or replaced by [importing](#importing) a file with its id, which keeps its
practice count; an imported one is yours and isn't replaced by app updates.

## Markdown

Lesson bodies and recovery session notes use this subset of Markdown. Anything else is an error in
the content check, reported at its line.

| Construct | Write |
| --- | --- |
| Heading | `## Setup` or `### Feet` (the title is the only top heading) |
| Paragraph | lines of text; a blank line starts the next |
| Bullet list | `- item`, `* item` or `+ item`; a following line, indented or not, continues the item |
| Numbered list | `1. item`; the list starts at its first number |
| Bold, italics | `**bold**` or `__bold__`, `*italics*` or `_italics_`; each closes with the marker that opened it and may sit once inside the other, never inside itself |
| Link | `[text](https://…)`: a web address, opened in the browser |
| Image | `![what it shows](images/name.webp)` on a line of its own; the file must exist |
| Escape | `\*`, `\_`, `\[` … for the character itself |

Not supported: `#` and `####` headings, underlined headings, nested lists, a second paragraph in a
list item, quotes, code (inline, fenced or indented by 4 spaces), tables, horizontal rules,
strikethrough, HTML, and images inside text.

## Importing

More → Import content takes a file from the system's file picker; a file shared with Fit, or opened
with it, lands on the same screen.

- **One `.md` file** of any kind. Its kind comes from its header and its name doesn't matter. Images
  it names must already be in the app (bundled, or from an earlier pack).
- **A `.zip` pack**, laid out like `content/`, at the top of the zip or inside one folder:

  ```
  exercises/<id>.md   routines/<id>.md   programs/<id>.md   recovery/<id>.md   lessons/<id>.md
  images/<name>.webp  (or .png, .jpg)
  ```

  A top-level `README.md`, hidden files and `__MACOSX` are skipped; any other file is a problem. A
  pack holds at most 2,000 files and 32 MB unpacked (and the file itself 32 MB), a Markdown file
  1 MB, an image 8 MB and 4096 px a side. Text is UTF-8, and an image must be one the phone can show.

The app checks the file or pack exactly as `content/` is checked, together with what it already
holds: a routine may name an exercise that's in the library, a wrong exercise id is reported, and so
on. It then shows a **preview**: every item by kind, and whether it's new or which item it replaces,
or every problem as `file:line: …`. **One problem means nothing is imported.**

- An item **replaces the one with its id** within its kind, also when you changed that one in the
  app. An exercise, routine or program with a new id whose name matches one you made in the app
  replaces that one (and gives it the id). A name that another item with a different id already has
  is a problem.
- A pack with only new items is imported on one tap; one that replaces anything asks first.
- Imported items are yours: an app update never overwrites or removes them, also when they replaced
  a built-in item. Your start date and active program, a session's pin, a lesson's practice count
  and your history stay.
- An **image** in a pack with the name of one the app already has replaces it everywhere it's used
  (the preview counts it as replaced); the same bytes are skipped.

## Rules for agents writing content

- Research before writing: prefer physiotherapy and sports-science sources and reputable coaches;
  list them under `sources` (required for `routine`, `recovery` and `lesson`). Link, don't copy text
  or images. Images you add must be your own or clearly licensed for reuse; say which in the commit.
- Keep recovery sessions gentle and short, say in the body when to stop (sharp pain, numbness,
  tingling) and when to see a professional. No diagnosis or treatment claims.
- Reuse exercise ids that exist; add a new exercise file only when the movement is new.
- Run the content check (`./gradlew :shared:jvmTest`) before finishing; it must pass.
- For a pack to import on a phone, keep the folder layout above and zip the folders; ids of
  exercises that ship with the app can be used without including their files.
