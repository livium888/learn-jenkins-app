# Flashcard Reader (Android)

A reading app that turns your own vocabulary into flashcards, and quizzes you
on a word the moment it reappears - in *any* book you open, not just the one
you tagged it in.

Supported formats: **PDF, EPUB, MOBI**. Nothing else by design.

## How it works

1. Upload a PDF, EPUB, or MOBI file via the system file picker - never
   copy/paste.
2. While reading, select a word or phrase and save your own definition for it.
3. That term goes into one **global** flashcard database, not tied to the
   book you found it in.
4. Whenever that term shows up again - in this book or a different one - and
   it's actually *due* for review (per the FSRS spaced-repetition schedule,
   not just "any time it's seen again"), the reader pauses and quizzes you
   before letting you continue.
5. A separate "Due for review" queue catches terms that are due but haven't
   naturally resurfaced in anything you're currently reading.

See the chat history / commit messages for the learning-science rationale
(testing effect, spacing effect, FSRS) behind why it's built this way instead
of naively re-quizzing on every single occurrence.

## Project layout

```
app/src/main/java/com/flashcardreader/app/
  data/
    db/            Room: Term (global flashcard), Source (imported book), Occurrence (log)
    fsrs/           FSRS (Free Spaced Repetition Scheduler) algorithm port
    parser/         EPUB / PDF / MOBI file parsers
    repository/     LibraryRepository (import + cache), TermRepository (flashcards + FSRS)
  reader/           Paginated reader screen, term scanner, flashcard interstitial, review queue
  library/          Book list + file upload
  theme/            Reading typography/theme (light/sepia/dark, font, size) + persisted prefs
  navigation/       Nav graph tying Library -> Reader -> Review together
```

## What's implemented vs. what's next

**Working now:**
- EPUB import via its real spine/manifest (not just globbing zip entries).
- PDF text extraction via PdfBox-Android (works well for single-column,
  novel-like PDFs; see caveat below).
- **MOBI/PalmDOC ("MOBI7") import**, implemented directly against the public
  format spec: PDB container -> PalmDOC header -> LZ77 decompression ->
  Jsoup strips the embedded HTML to plain text. There is no maintained
  Java/Kotlin library that extracts MOBI *text* (the one Maven-published
  option, `lib-mobi`, only reads header metadata) and the one real content
  library, `libmobi`, is C/LGPL - pulling that in means an NDK build and real
  licensing questions for very little payoff, so this format gets a small,
  self-contained, from-spec parser instead. See caveats below.
- Global term database with a real FSRS (v4.5-style DSR model) scheduler, whose
  initial-stability weights are **re-fitted to your own review history** once
  there is enough of it (`data/fsrs/FsrsOptimizer.kt`).
- **Reading checks**: multiple-choice comprehension questions written by Gemini
  from passages you genuinely read, validated against the text before they are
  ever shown, and scheduled like any other card.
- **Verified reading**: an anti-fake tracker that can tell reading from
  scrolling, used to earn Focus Gate time and to decide when to ask a question.
- Free-book catalogues (Project Gutenberg, Standard Ebooks, Wikisource, any
  OPDS feed), backup/restore, and a crash reporter that shows the last stack
  trace on the next launch.
- Paginated reflowable reader with font/size/line-height/theme controls.
- Text selection -> flashcard creation flow (select text, tap system "Copy",
  tap "Add Flashcard" - prefills from the clipboard, editable before saving).
- Global term scanner that gates the flashcard interstitial on FSRS due-ness,
  not on raw occurrence count, so it won't spam you if a word appears 10
  times on one page.
- Standalone review queue for cards due but not yet reappeared in a book.

**Known gaps / good next steps:**
- **MOBI limits**: DRM'd files (AZW/KFX, or DRM'd `.mobi`) can never be
  supported - that's an Amazon licensing wall, not a missing feature. Older
  Huffman/CDIC-compressed MOBI files (compression type 17480, mostly seen in
  older/international titles) aren't supported yet, only PalmDOC-compressed
  (type 2) and uncompressed (type 1). True KF8-only files with no legacy
  MOBI7 fallback rendition inside them also aren't supported. All of these
  fail with a clear error rather than producing garbled text.
- **PDF is fixed-layout**, so we extract-and-reflow rather than preserving
  original layout. Scanned (image-only) PDFs and multi-column academic
  papers will extract poorly or blank - a page-image fallback view is a
  reasonable follow-up, not implemented here.
- **Comprehension questions need a Gemini API key.** Reading checks are written
  by the model from passages you actually read; without a key the reader still
  works, it just never asks anything.
- **PDFs have no chapter structure**, so anything keyed to chapters is absent
  for them.

## Building

Requires Android Studio (Koala+) or a local Android SDK. Open this
`android-flashcard-reader/` directory as a project root in Android Studio -
it will handle the Gradle wrapper JAR automatically. Minimum SDK 26, target/
compile SDK 34, Kotlin 1.9.24, Jetpack Compose.

**The environment this is written in cannot build Android**: it blocks
`dl.google.com`, which is where AGP, AndroidX, Compose and Room are hosted and
how the SDK itself is fetched. So GitHub Actions is the only compiler - see
`.github/workflows/android-flashcard-reader.yml`, which runs the unit tests,
builds a signed release APK, renders the Compose screens to PNG with Paparazzi,
and publishes the APK to a GitHub Release. Pure-Kotlin logic (the credit
tracker, FSRS, the AI response validators, the backup format) is deliberately
kept free of Android imports so it can also be compiled and tested locally with
`kotlinc`, which is how most of it is checked before CI ever sees it.
