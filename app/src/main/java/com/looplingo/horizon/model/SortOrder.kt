package com.looplingo.horizon.model

/**
 * Sort order for the video list.
 *
 * This is a presentation-layer concern — the ViewModel owns the current
 * sort order and passes it to the repository when requesting data.
 * The repository does NOT hold mutable sort state.
 */
enum class SortOrder {
    DATE,       // Newest first (default)
    TITLE,      // A-Z alphabetical
    DURATION,   // Longest first
    SIZE        // Largest first
}
