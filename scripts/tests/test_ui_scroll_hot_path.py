import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHARED = ROOT / "app/src/main/kotlin/com/ankiminer/android/ui/mining/SharedMiningComponents.kt"
CURATION = ROOT / "app/src/main/kotlin/com/ankiminer/android/ui/mining/CurationComponents.kt"
READING = ROOT / "app/src/main/kotlin/com/ankiminer/android/ui/reading/ReadingMiningScreen.kt"
VIDEO = ROOT / "app/src/main/kotlin/com/ankiminer/android/ui/video/VideoMiningScreen.kt"
SCREENSHOTS = ROOT / "app/src/androidTest/kotlin/com/ankiminer/android/uiaudit/UiAuditScreenshotTest.kt"
NOTICES = ROOT / "app/src/main/kotlin/com/ankiminer/android/ui/attribution/NoticesScreen.kt"


def function_body(source: str, name: str, next_name: str | None) -> str:
    start = source.index(f"fun {name}(")
    end = len(source) if next_name is None else source.index(f"fun {next_name}(", start)
    return source[start:end]


class UiScrollHotPathTest(unittest.TestCase):
    def test_phase_fade_targets_coarse_snapshot_and_skips_size_animation(self) -> None:
        for path in (READING, VIDEO):
            source = path.read_text(encoding="utf-8")
            self.assertIn("val phaseTarget =", source, path)
            self.assertIn("targetState = phaseTarget", source, path)
            self.assertIn("contentKey = { target -> target.key }", source, path)
            # `using null` is the public ContentTransform infix for disabling the
            # size animation; the property setter is internal in Compose 1.11.
            self.assertIn("using null", source, path)
            self.assertNotIn("targetState = state,", source, path)

    def test_passive_candidate_rows_have_no_animation_state_or_text_builds(self) -> None:
        source = CURATION.read_text(encoding="utf-8")
        row = function_body(source, "CurationCandidateRow", "CurationRowActions")

        # Both row lines arrive prebuilt from rememberCurationCandidateRowTexts.
        self.assertNotIn("buildAnnotatedString", row)
        self.assertNotIn("stringResource", row)
        self.assertIn("text: CurationCandidateRowText", row)
        # Focus and inclusion are separate targets: the row opens the detail, the checkbox alone
        # includes or excludes. One whole-row toggleable made inspecting a candidate exclude it.
        self.assertIn("clickable(enabled = enabled, onClick = onFocus)", row)
        self.assertIn("onCheckedChange = onToggle", row)
        self.assertNotIn("onValueChange = onToggle", row)
        self.assertNotRegex(row, re.compile(r"(shadow|tonal)Elevation|\.shadow\("))

    def test_only_the_focused_row_group_allocates_a_colour_animation(self) -> None:
        source = CURATION.read_text(encoding="utf-8")
        resolver = function_body(
            source,
            "curationRowContainerColor",
            "CurationCandidateRow",
        )

        self.assertIn("animateSelection: Boolean", resolver)
        self.assertRegex(
            resolver,
            re.compile(
                r"if \(animateSelection\) \{\s+" r"animateColorAsState\(",
                re.DOTALL,
            ),
        )

    def test_sentence_rows_remember_their_highlight(self) -> None:
        source = CURATION.read_text(encoding="utf-8")
        sentence = function_body(source, "CurationSentenceChoice", None)

        # A substring search plus an AnnotatedString build per frame would defeat the
        # subcomposition reuse the sentence list depends on.
        self.assertNotIn("buildAnnotatedString", sentence)
        self.assertRegex(
            sentence,
            re.compile(
                r"remember\(sentence\.sentence, candidate\.surface\) \{\s+" r"highlightMinedForm\(",
                re.DOTALL,
            ),
        )

    def test_lazy_row_functions_contain_no_content_or_elevation_animation(self) -> None:
        forbidden = re.compile(
            r"AnimatedContent|animate(?:Color|Float|Dp).*AsState|" r"(?:shadow|tonal)Elevation|\.shadow\("
        )
        for path in (READING, VIDEO):
            source = path.read_text(encoding="utf-8")
            rows = function_body(source, "LazyListScope.curationItems", "LazyListScope.terminalItems")
            self.assertNotRegex(rows, forbidden, path)

        source = SHARED.read_text(encoding="utf-8")
        result_rows = function_body(source, "LazyListScope.miningResultItems", "MiningResultSummary")
        self.assertNotRegex(result_rows, forbidden)

    def test_result_metric_grid_does_not_build_collections_during_composition(self) -> None:
        source = SHARED.read_text(encoding="utf-8")
        grid = function_body(source, "ResultMetricGrid", "ResultDetailsCard")
        self.assertNotIn("listOf(", grid)
        self.assertNotIn("chunked(", grid)
        self.assertNotIn(" to stringResource", grid)

    def test_curation_lists_precompute_header_text_and_animate_only_focus(self) -> None:
        for path in (READING, VIDEO):
            source = path.read_text(encoding="utf-8")
            self.assertIn(
                "rememberCurationCandidateRowTexts(visibleCandidates)",
                source,
                path,
            )
            self.assertIn(
                "animateSelection = candidate.candidateId == curation.focusedCandidateId",
                source,
                path,
            )
            self.assertIn('contentType = "candidate"', source, path)
            self.assertRegex(
                source,
                re.compile(r'key = "[^"]*candidate:\$\{candidate\.candidateId\}"'),
                path,
            )

    def test_notices_matrix_waits_for_first_parsed_block(self) -> None:
        source = SCREENSHOTS.read_text(encoding="utf-8")
        notices = NOTICES.read_text(encoding="utf-8")
        self.assertIn('waitForLazyListKey = "block:NOTICE.md:0"', source)
        self.assertNotIn('waitForLazyListKey = "document:NOTICE.md"', source)
        self.assertIn('key = { index -> "block:${document.name}:$index" }', notices)


if __name__ == "__main__":
    unittest.main()
