# R8 / ProGuard rules for the release build.
#
# Most of the app is safe under R8: Room generates concrete code (no reflection on our
# fields), Compose and Coroutines ship their own consumer rules, and our JSON is built by
# hand with org.json (no reflection on model classes). The keeps below cover the few
# libraries that DO use reflection or resource loading, which R8 can't see through.

# --- Tink / androidx.security-crypto (EncryptedSharedPreferences) ---
# Tink resolves key managers reflectively; stripping/renaming them breaks decryption.
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**

# --- PdfBox-Android (PDF text extraction) ---
# Loads embedded font/glyph resources and has reflective code paths.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**

# --- Jsoup (EPUB/MOBI HTML parsing) --- defensive; no reflection, but silence warnings.
-dontwarn org.jsoup.**

# --- Our Room entities --- defensive keep so column/field mapping is never surprised by R8.
-keep class com.flashcardreader.app.data.db.entities.** { *; }
