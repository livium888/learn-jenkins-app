package com.flashcardreader.app.data.books

/**
 * Ways in to a catalogue of tens of thousands of books.
 *
 * A search box alone is the wrong tool here: it only works if you already know a title, so browsing
 * turns into guess-and-miss. These are the shelves and names to tap when you don't yet know what
 * you're looking for.
 */
object Discovery {

    /**
     * Subjects rather than keywords. Gutenberg matches these against its bookshelves, so "Adventure"
     * returns the adventure shelf rather than books with the word in the title.
     */
    val topics = listOf(
        "Adventure",
        "Detective",
        "Science fiction",
        "Fantasy",
        "Horror",
        "Romance",
        "Philosophy",
        "Poetry",
        "Short stories",
        "History",
        "Travel",
        "Children",
    )

    /** Reliable starting points: famous, widely available, and well digitised in every catalogue. */
    val authors = listOf(
        "Jane Austen",
        "Charles Dickens",
        "Mark Twain",
        "Arthur Conan Doyle",
        "Mary Shelley",
        "Bram Stoker",
        "H. G. Wells",
        "Jules Verne",
        "Oscar Wilde",
        "Edgar Allan Poe",
        "Leo Tolstoy",
        "Fyodor Dostoyevsky",
        "Virginia Woolf",
        "Herman Melville",
        "Emily Brontë",
    )
}
