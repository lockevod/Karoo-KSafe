package com.enderthor.kSafe.extension.managers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function tests for [substituteAlertTokens], the substitution kernel shared by
 * the emergency, ride-start, ride-end and custom-message paths.
 *
 * Covers the AC1 contract:
 *  - `{location}` / `{livetrack}` / `{reason}` are substituted with whatever caller
 *    resolved them to.
 *  - A missing/unavailable location is rendered as the fallback string passed in
 *    (the suspending wrapper sources this from `R.string.location_unavailable`).
 *  - Unknown tokens like `{foo}` are left literal — same convention as
 *    [com.enderthor.kSafe.extension.util.renderAlertText].
 *  - Empty inputs do not crash and yield a clean (trimmed) message — this is what
 *    prevents the literal `"Track me: {livetrack}"` regression riders saw before
 *    AC1 when no Karoo Live key was configured.
 */
class TokenSubstitutionTest {

    private val mapsLink = "https://maps.google.com/?q=41.4,2.2"
    private val liveLink = "https://live.example/abc"
    private val noFix = "Location unavailable"

    @Test
    fun `all three tokens substitute when present`() {
        val out = substituteAlertTokens(
            template = "EMERGENCY at {location} — {reason}. Live: {livetrack}",
            locationLink = mapsLink,
            liveTrackLink = liveLink,
            reasonLabel = "Crash detected",
        )
        assertEquals("EMERGENCY at $mapsLink — Crash detected. Live: $liveLink", out)
    }

    @Test
    fun `custom message with location token gets the maps link`() {
        // Regression for AC1: the literal "I'm at {location} ..." bug.
        val out = substituteAlertTokens(
            template = "I'm at {location} — be back in 30",
            locationLink = mapsLink,
            liveTrackLink = "",
            reasonLabel = "",
        )
        assertEquals("I'm at $mapsLink — be back in 30", out)
    }

    @Test
    fun `missing GPS fix substitutes the fallback string`() {
        val out = substituteAlertTokens(
            template = "Help — I'm near {location}",
            locationLink = noFix,
            liveTrackLink = "",
            reasonLabel = "",
        )
        assertEquals("Help — I'm near $noFix", out)
    }

    @Test
    fun `livetrack token is dropped and trailing whitespace trimmed when no key`() {
        // Mirrors the old inline behaviour of sendRideStartNotification: a template
        // ending in "{livetrack}" must not leave a dangling space when livetrack is
        // empty (the trim() inside substituteAlertTokens guarantees this).
        val out = substituteAlertTokens(
            template = "Ride started! Track me live: {livetrack}",
            locationLink = noFix,
            liveTrackLink = "",
            reasonLabel = "",
        )
        assertEquals("Ride started! Track me live:", out)
    }

    @Test
    fun `livetrack token is replaced when a key is configured`() {
        val out = substituteAlertTokens(
            template = "Ride started! Track me live: {livetrack}",
            locationLink = noFix,
            liveTrackLink = liveLink,
            reasonLabel = "",
        )
        assertEquals("Ride started! Track me live: $liveLink", out)
    }

    @Test
    fun `reason token resolves to empty for non-emergency callers`() {
        // Non-emergency callers (custom / ride end) pass reasonLabel = "". A template
        // that accidentally includes {reason} should not leak the literal token.
        val out = substituteAlertTokens(
            template = "Note: {reason}",
            locationLink = noFix,
            liveTrackLink = "",
            reasonLabel = "",
        )
        assertEquals("Note:", out)
    }

    @Test
    fun `unknown tokens are left literal`() {
        // {foo} is not a token we recognise — must NOT crash and must NOT silently
        // strip. Same convention as renderAlertText so riders get visual feedback
        // when they typo a placeholder.
        val out = substituteAlertTokens(
            template = "Hi {foo} from {location}",
            locationLink = mapsLink,
            liveTrackLink = "",
            reasonLabel = "",
        )
        assertEquals("Hi {foo} from $mapsLink", out)
    }

    @Test
    fun `empty template returns empty string`() {
        val out = substituteAlertTokens(
            template = "",
            locationLink = mapsLink,
            liveTrackLink = liveLink,
            reasonLabel = "Crash detected",
        )
        assertEquals("", out)
    }

    @Test
    fun `multiple occurrences of the same token all substitute`() {
        // Defensive: String.replace replaces all matches, not just the first. Keeps
        // riders who put {location} twice (start + end of their message) honest.
        val out = substituteAlertTokens(
            template = "{location} — also {location}",
            locationLink = mapsLink,
            liveTrackLink = "",
            reasonLabel = "",
        )
        assertEquals("$mapsLink — also $mapsLink", out)
    }

    @Test
    fun `default ride end message passes through untouched`() {
        // The shipped default karooLiveEndMessage has no tokens. It must still flow
        // through the helper without modification (other than trim).
        val out = substituteAlertTokens(
            template = "Ride finished! 🏁",
            locationLink = mapsLink,
            liveTrackLink = liveLink,
            reasonLabel = "Crash detected",
        )
        assertEquals("Ride finished! 🏁", out)
    }
}
