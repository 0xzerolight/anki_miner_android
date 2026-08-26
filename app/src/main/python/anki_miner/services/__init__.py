"""Business logic services for Anki Miner.

Android divergence: ``ValidationService`` is not re-exported. Desktop's
validation service imports ``anki_miner.utils.ytdlp_resolver`` at module top,
and yt-dlp is cut from Android, so the import-closure gate rejects it. Nothing
on Android calls the service — this package re-export was its only path into
the vendored closure, so dropping it keeps ~300 lines of unreachable desktop
code out of the APK instead of shadowing the whole module to delete one import.
``ValidationResult`` itself lives in ``anki_miner.models.processing`` and is
unaffected. Upstream made the export lazy rather than eager, which does not
help here: the closure gate follows function-scoped imports too, so the
``__getattr__`` branch, the ``TYPE_CHECKING`` import and the ``__all__`` entry
are all dropped.
"""

from typing import TYPE_CHECKING

from .definition_service import DefinitionService
from .dictionary.providers import IndexedDictProvider, JishoProvider
from .media_extractor import MediaExtractorService
from .shortcut_service import ShortcutResult, ShortcutService
from .stats_service import StatsService
from .word_filter import WordFilterService

if TYPE_CHECKING:
    from .anki_service import AnkiService
    from .export_service import ExportService
    from .subtitle_parser import SubtitleParserService


def __getattr__(name: str) -> object:
    # AnkiService pulls in `requests` at its own module top; ExportService is
    # lazy alongside it for the same reason. On Android this matters more than
    # on desktop: `import anki_miner.services` from the bridge, the golden
    # runners, or a host test lane no longer drags `requests` in.
    if name == "AnkiService":
        from .anki_service import AnkiService

        return AnkiService
    if name == "ExportService":
        from .export_service import ExportService

        return ExportService
    if name == "SubtitleParserService":
        from .subtitle_parser import SubtitleParserService

        return SubtitleParserService
    raise AttributeError(name)


__all__ = [
    "SubtitleParserService",
    "WordFilterService",
    "MediaExtractorService",
    "DefinitionService",
    "AnkiService",
    "ExportService",
    "StatsService",
    "IndexedDictProvider",
    "JishoProvider",
    "ShortcutService",
    "ShortcutResult",
]
