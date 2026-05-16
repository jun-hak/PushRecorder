package com.example.pushrecorder

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredRefactorsArtifactTest {
    @Test
    fun deferredRefactorsArtifactExistsAtProjectLocalPathAndIsParseable() {
        val artifactPath = findArtifactPath()
        val artifact = String(Files.readAllBytes(artifactPath))

        assertTrue(
            "Deferred refactors artifact should identify its agreed project-local path.",
            artifact.contains("Project-local path: `docs/deferred-refactors.md`")
        )
        assertTrue(
            "Deferred refactors artifact should document phase 1 behavior preservation.",
            artifact.contains("Phase 1 keeps notification capture behavior stable")
        )
        assertTrue(
            "Deferred refactors artifact should document two-row notification lifecycle storage.",
            artifact.contains("A posted callback writes a `POSTED` row") &&
                artifact.contains("matching removed callback writes a later terminal row")
        )
        assertTrue(
            "Deferred refactors artifact should explain high-volume row growth.",
            artifact.contains("10,000 complete notification lifecycles") &&
                artifact.contains("20,000 notification rows")
        )
        assertTrue(
            "Deferred refactors artifact should document the future single-row model tradeoff.",
            artifact.contains("A future single-row lifecycle model") &&
                artifact.contains("compatibility plan for already-recorded POSTED/REMOVED rows")
        )

        val items = artifact.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("- [ ] id:") }
            .map(::parseDeferredRefactorItem)
            .toList()

        assertFalse("Deferred refactors artifact should contain at least one deferred item.", items.isEmpty())
        assertEquals(
            "Deferred refactor IDs should stay unique.",
            items.size,
            items.map(DeferredRefactorItem::id).toSet().size
        )

        val itemsById = items.associateBy(DeferredRefactorItem::id)
        assertEquals(
            "Deferred refactors should contain every intentionally deferred phase-1 follow-up by stable ID.",
            requiredPhaseOneFollowUps.keys,
            itemsById.keys
        )
        requiredPhaseOneFollowUps.forEach { (id, requiredTitle) ->
            assertEquals(
                "Deferred refactor $id should keep its exact required title.",
                requiredTitle,
                itemsById.getValue(id).item
            )
        }

        assertTrue(
            "Deferred refactors should include reconcile policy follow-up work.",
            items.any { it.area == "reconcile-policy" }
        )
        assertTrue(
            "Deferred refactors should include notification event boundary follow-up work.",
            items.any { it.area == "notification-events" }
        )
    }

    private fun findArtifactPath(): Path {
        val candidates = listOf(
            Path.of("docs/deferred-refactors.md"),
            Path.of("../docs/deferred-refactors.md")
        )
        return candidates.firstOrNull(Files::isReadable)
            ?: error("docs/deferred-refactors.md was not found or readable from known Gradle test working directories")
    }

    private fun parseDeferredRefactorItem(line: String): DeferredRefactorItem {
        val fields = line
            .removePrefix("- [ ] ")
            .split("|")
            .map { it.trim() }

        assertEquals("Deferred refactor rows should have id, area, phase, and item fields.", 4, fields.size)

        val values = fields.associate { field ->
            val keyValue = field.split(":", limit = 2)
            assertEquals("Deferred refactor field should use `key: value` format: $field", 2, keyValue.size)
            keyValue[0].trim() to keyValue[1].trim()
        }

        val id = values.getValue("id")
        val area = values.getValue("area")
        val phase = values.getValue("phase")
        val item = values.getValue("item")

        assertTrue("Deferred refactor id should use DR-### format: $id", id.matches(Regex("DR-\\d{3}")))
        assertTrue("Deferred refactor area should be readable: $area", area.matches(Regex("[a-z0-9-]+")))
        assertEquals("Deferred refactor phase should stay explicitly deferred.", "later", phase)
        assertTrue("Deferred refactor item should describe concrete work.", item.length >= 24)

        return DeferredRefactorItem(id = id, area = area, item = item)
    }

    private data class DeferredRefactorItem(
        val id: String,
        val area: String,
        val item: String
    )

    private companion object {
        val requiredPhaseOneFollowUps = linkedMapOf(
            "DR-001" to "Move listener permission and connection state UI signals behind a smaller app-facing status model.",
            "DR-002" to "Make active-notification retry timing configurable after behavior is covered by device-level evidence.",
            "DR-003" to "Replace remaining Android-framework edge adapters with pure command fixtures where unit tests still need framework-shaped data.",
            "DR-004" to "Review package removal and reinstall handling without deleting existing notification history.",
            "DR-005" to "Split grouped notification screen state mapping from Compose rendering while preserving Korean labels and paging behavior."
        )
    }
}
