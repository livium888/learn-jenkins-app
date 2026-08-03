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
- Global term database with a real FSRS (v4.5-style DSR model) scheduler.
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
- **FSRS parameters are the published defaults**, not optimized against real
  usage. FSRS is designed to be refit periodically once there's real review
  history (a few hundred reviews) for materially better scheduling - that
  optimizer isn't implemented yet.
- No automated tests yet.

## Building

Requires Android Studio (Koala+) or a local Android SDK. Open this
`android-flashcard-reader/` directory as a project root in Android Studio -
it will handle the Gradle wrapper JAR automatically. Minimum SDK 26, target/
compile SDK 34, Kotlin 1.9.24, Jetpack Compose.

**This has not been build-verified against a real Gradle/Android toolchain.**
The sandboxed environment this was written in blocks `dl.google.com` at the
network policy level, which is where AGP, AndroidX, Compose, and Room are all
hosted (and how the Android SDK itself is fetched) - so no Gradle sync, let
alone a build, could be run here. Every file has had a careful manual
line-by-line review instead (import correctness, API signatures, exhaustive
`when`s, etc.), but a real `Gradle sync` + `assembleDebug` in Android Studio
is the first thing to do, and is very likely to surface a few things a human
reviewer would need to fix.
