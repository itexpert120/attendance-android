# Phone-first redesign of the web app

The PWA (`C:\Users\itexp\attendance-pwa`) is the **specification for data and rules**, not for screens. Its UI was a
website shaped like a phone: spreadsheet grids, a print dialog, file downloads, hover and keyboard-era widgets.
This app is a native Android app. Port the *behaviour and business rules*; redesign every interaction below the way
an Android user expects. Follow current Material 3 (Expressive) guidance: edge-to-edge, `LargeTopAppBar`,
`NavigationSuiteScaffold`, `ModalBottomSheet`, `FloatingActionButton`, predictive back, dynamic colour, haptics.

## Rules for every screen

- **One hand, one thumb.** Primary actions sit in the bottom half (FAB, bottom sheet footers, bottom action bars),
  not in the top-right corner. Touch targets are at least 48dp.
- **No web patterns.** No hover states, no tiny icon rows, no horizontal-scrolling tables, no right-aligned forms,
  no "close" X buttons on sheets, no browser-style dialogs for things a sheet or a swipe can do.
- **Lists, not tables.** Anything the web showed as a grid becomes a list of cards or rows, with the grid only
  appearing as an optional overview on large screens.
- **Native controls.** Date picker, Photo Picker / camera, share sheet, system print, Storage Access Framework,
  dial/SMS/WhatsApp intents, haptic feedback (`LocalHapticFeedback`), `Snackbar` with Undo for destructive actions.
- **Forms:** one field per row, the right `KeyboardType`, `ImeAction.Next`/`Done`, and numeric keypads for numbers.
  Validation errors from repositories (`ValidationException.message`) appear inline in the form, not as dialogs.
- **State survives rotation and process death** (ViewModel + `rememberSaveable`); lists use stable keys.
- **Accessible:** content descriptions, 4.5:1 text contrast (use theme roles, never raw colours), no colour-only meaning
  (attendance status also has a letter/icon), `Modifier.semantics` where a custom control isn't self-describing.
- **Adaptive:** a phone layout first. On wide windows (`WindowSizeClass`) use two panes or a grid where it helps.
- **Empty, loading and error states** are designed, not left blank (`EmptyState`, `LoadingIndicator`, `ErrorBanner`).
- **Colour:** dynamic colour is on by default; the register green is the fallback. Present / absent / leave have fixed
  semantic colours that do not follow the wallpaper.

## What changes, concretely

| Web (PWA) | Native app |
|---|---|
| **Attendance grid**: students x 31 days x 2 timings in one scrolling table | **Day-first marking.** A horizontally scrolling date strip picks the day; below it a list of students, each row with two tap targets (first / second timing) that cycle none -> P -> A -> L, or one-tap "mark all present". Swipe a row to mark absent. A bar shows today's counts. A separate **month overview** (per-student calendar heat-strip and totals) replaces the wide grid; the full month grid exists only on tablets/landscape and in the exported PDF. |
| **Fees grid** (students x installments x six fee columns) | **Per-student fee cards.** A list with the student's total and a status chip; tapping opens a bottom sheet with the three installments as tabs and one numeric field per fee category. Installment rate / receiver are edited in a sheet. Month totals sit in a summary header. |
| **Print dialog** (`window.print`, 7 `Print*.svelte`, font preloading) | **PDF export.** Generate a PDF with `android.graphics.pdf.PdfDocument`, then offer the system **Print** (`PrintManager`), **Share** (FileProvider + `ACTION_SEND`) and **Save** (SAF `CreateDocument`) actions. The official register is rendered landscape A4 like the paper register. |
| **Backup = download/upload a JSON file** | **Backup & restore with the Storage Access Framework**: export one `.zip` (database + photos) to Drive/Files/anywhere the user picks, and restore from it. Importing an old PWA Dexie `.json` backup is a separate "Import from web app" action. Also enable Android Auto Backup for the app's private data. |
| **Install banner, service worker, "persistent storage" status** (`PwaStatus`) | Removed. A native app is installed and offline by definition. |
| **`navigator.vibrate`** | `HapticFeedback` on marks, toggles and destructive confirmations. |
| **History-API hack for sheets** (`registerBack`) | `ModalBottomSheet` and `NavDisplay` handle system back and predictive back natively. |
| **`<input type=date>`**, file inputs | Material `DatePicker`; Photo Picker (`PickVisualMedia`) and camera (`TakePicture`) with downscaling off the main thread. |
| **Photos as base64 in the database; canvas resize** | Files in app storage via `PhotoStore`; decode with `ImageDecoder`/`BitmapFactory` sampling and re-encode JPEG at a sane size. |
| **Student list as a long form page** | Searchable list (SearchBar), class filter chips, swipe actions, tap-to-call / message parent, FAB to add. |
| **Test mark entry in a table** | **Rapid entry**: one student at a time or a compact list with a numeric field per row, `Next` IME action jumps to the next student, quick "Absent" chip, progress header. |
| **SMS / WhatsApp via `sms:` and `wa.me` links** | Real intents (`ACTION_SENDTO smsto:`, WhatsApp package intent with fallback, `ACTION_DIAL`) from a "Message absentees" sheet that lists each parent with a send button and marks who has been messaged. |
| **Mouse-first dropdowns/selects** | `ExposedDropdownMenuBox`, segmented buttons, filter chips, or a sheet with radio rows, whichever is quickest by thumb. |
| **Toasts/alerts** | `Snackbar` (with Undo where it makes sense) |
| **Keyboard.ts / kb-hide hacks** | `imePadding`, `WindowInsets`, `adjustResize`. |
| **Global dashboard of stat cards** | Home that answers "what do I do today?": today's register(s) with a big *Take attendance* action, then recent registers. |

## Buttons, sheets and motion (added after on-device review, 2026-10-07)

- **No full-width "big" buttons.** A flow's primary action (Next, Save, Create) is a compact filled `Button` at
  the bottom right, with a low-emphasis `TextButton` (Back, Skip, Delete) at the bottom left: use
  `ui/components/BottomActionBar`. Screen-level create actions are (extended) FABs. Cards may have a compact
  tonal/filled button, never one stretched edge to edge.
- **Sheets settle without overshoot.** Always use `AppSheet`; a custom `ModalBottomSheet` must be wrapped in
  `SheetMotion { … SheetContentMotion { … } }` (the expressive spring made tall sheets wobble).
- **Tapping the selected tab again scrolls it to the top** (`TabReselectEffect`), with a light haptic.
- **Motion explains change, nothing more:** screen transitions, content switches (segments, tabs, periods),
  list insert/remove (`animateItem`), state colours (attendance marks), FABs collapsing on scroll. No animation
  on first appearance of ordinary content, on text, or on every recomposition.

## Not ported (and why)

PWA install/update UI, ripple.ts, theme-colour meta tag, font preloading, drag-to-dismiss sheet code, `PrintFontPreload`,
`storage.persist()` — the platform provides these.

## New native capabilities worth building (after the port)

Daily attendance reminder notification, home-screen shortcut "Take attendance", share a report straight to WhatsApp,
biometric lock for student data. Not part of the first release unless a phase finishes early.
