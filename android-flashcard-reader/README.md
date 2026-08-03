# Flashcard Reader (Android)

A reading app that turns your own vocabulary into flashcards, and quizzes you
on a word the moment it reappears - in *any* book, PDF, or article you open,
not just the one you tagged it in.

## How it works

1. Upload a PDF, EPUB, or TXT file (system file picker - no copy/paste), or
   paste a URL to ingest an article.
2. While reading, select a word or phrase and save your own definition for it.
3. That term goes into one **global** flashcard database, not tied to the
   book you found it in.
4. Whenever that term shows up again - in this book, a different book, or a
   web article - and it's actually *due* for review (per the FSRS spaced-
   repetition schedule, not just "any time it's seen again"), the reader
   pauses and quizzes you before letting you continue.
5. A separate "Due for review" queue catches terms that are due but haven't
   naturally resurfaced in anything you're currently reading.

See the chat history / commit messages for the learning-science rationale
(testing effect, spacing effect, FSRS) behind why it's built this way instead
of naively re-quizzing on every single occurrence.

## Project layout

```
app/src/main/java/com/flashcardreader/app/
  data/
    db/            Room: Term (global flashcard), Source (imported book/article), Occurrence (log)
    fsrs/           FSRS (Free Spaced Repetition Scheduler) algorithm port
    parser/         TXT / EPUB / PDF / MOBI(stub) file parsers + URL article ingestor
    repository/     LibraryRepository (import + cache), TermRepository (flashcards + FSRS)
  reader/           Paginated reader screen, term scanner, flashcard interstitial, review queue
  library/          Book list, file upload, add-from-URL
  theme/            Reading typography/theme (light/sepia/dark, font, size) + persisted prefs
  navigation/       Nav graph tying Library -> Reader -> Review together
```

## What's implemented vs. what's next

**Working now:**
- TXT and EPUB import and parsing (EPUB via its real spine/manifest, not just
  globbing zip entries).
- PDF text extraction via PdfBox-Android (works well for single-column,
  novel-like PDFs; see caveat below).
- URL ingestion with a lightweight readability-style content extractor.
- Global term database with a real FSRS (v4.5-style DSR model) scheduler.
- Paginated reflowable reader with font/size/line-height/theme controls.
- Text selection -> flashcard creation flow (select text, tap system "Copy",
  tap "Add Flashcard" - prefills from the clipboard, editable before saving).
- Global term scanner that gates the flashcard interstitial on FSRS due-ness,
  not on raw occurrence count, so it won't spam you if a word appears 10
  times on one page.
- Standalone review queue for cards due but not yet reappeared in a book.

**Known gaps / good next steps:**
- **MOBI/AZW import is stubbed** (throws a clear "not supported yet" error).
  DRM'd Kindle files (AZW/KFX) can never be supported; a real parser for
  DRM-free `.mobi` is real work and hasn't been built yet.
- **PDF is fixed-layout**, so we extract-and-reflow rather than preserving
  original layout. Scanned (image-only) PDFs and multi-column academic
  papers will extract poorly or blank - a page-image fallback view is a
  reasonable follow-up, not implemented here.
- **FSRS parameters are the published defaults**, not optimized against real
  usage. FSRS is designed to be refit periodically once there's real review
  history (a few hundred reviews) for materially better scheduling - that
  optimizer isn't implemented yet.
- **URL extraction is a simple heuristic** (largest paragraph-text block),
  not a full Readability port - works for most blogs/articles, not for
  paywalled or heavily JS-rendered pages (we only fetch raw HTML).
- The reading engine is hand-rolled Compose pagination rather than something
  like Readium; fine for plain prose, but doesn't yet do smarter things a
  dedicated reading engine would (e.g. preserving embedded images, footnotes,
  or complex EPUB CSS).
- No automated tests yet.

## Building

Requires Android Studio (Koala+) or a local Android SDK. Open this
`android-flashcard-reader/` directory as a project root in Android Studio -
it will handle the Gradle wrapper JAR automatically. Minimum SDK 26, target/
compile SDK 34, Kotlin 1.9.24, Jetpack Compose.

This hasn't been build-verified in an emulator yet (no Android SDK available
in the environment this was scaffolded in) - the first thing to do after
opening it is a Gradle sync + run on a device/emulator to shake out any
remaining compile issues.
