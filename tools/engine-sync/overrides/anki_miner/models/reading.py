"""Data models for the reading-tab pipeline (manga volumes + novels)."""

from __future__ import annotations

from collections.abc import Callable, Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal


class ReadingUnitLimitExceeded(Exception):
    """Android load context reached an allocation ceiling."""

    def __init__(self, maximum: int, observed: int) -> None:
        super().__init__(f"Reading unit limit exceeded ({observed:,} > {maximum:,})")
        self.maximum = maximum
        self.observed = observed


class ReadingUnitLoadCancelled(Exception):
    """Android load context observed cancellation while emitting units."""


@dataclass(slots=True)
class _ReadingUnitBudget:
    maximum: int
    maximum_unit_text_bytes: int
    cancellation_check: Callable[[], bool] | None
    precount_sentences: bool
    retained: int = 0

    def check(self, additional: int) -> None:
        if self.cancellation_check is not None and self.cancellation_check():
            raise ReadingUnitLoadCancelled
        if self.precount_sentences and self.retained + additional > self.maximum:
            raise ReadingUnitLimitExceeded(self.maximum, self.retained + additional)

    def check_text(self, observed: int) -> None:
        if observed > self.maximum_unit_text_bytes:
            raise ReadingUnitLimitExceeded(self.maximum_unit_text_bytes, observed)

    def reserve(self) -> None:
        if self.cancellation_check is not None and self.cancellation_check():
            raise ReadingUnitLoadCancelled
        observed = self.retained + 1
        if observed > self.maximum:
            raise ReadingUnitLimitExceeded(self.maximum, observed)
        self.retained = observed


_READING_UNIT_BUDGET: ContextVar[_ReadingUnitBudget | None] = ContextVar(
    "anki_miner_reading_unit_budget",
    default=None,
)
_DEFAULT_MAX_UNIT_TEXT_UTF8_BYTES = 64 * 1024


@contextmanager
def reading_unit_budget(
    maximum: int,
    *,
    maximum_unit_text_bytes: int = _DEFAULT_MAX_UNIT_TEXT_UTF8_BYTES,
    cancellation_check: Callable[[], bool] | None = None,
    precount_sentences: bool = False,
) -> Iterator[None]:
    """Scope Android-only unit allocation bounds around one loader call."""

    if maximum <= 0:
        raise ValueError("Reading unit limit must be positive")
    if maximum_unit_text_bytes <= 0:
        raise ValueError("Reading unit text limit must be positive")
    token = _READING_UNIT_BUDGET.set(
        _ReadingUnitBudget(
            maximum=maximum,
            maximum_unit_text_bytes=maximum_unit_text_bytes,
            cancellation_check=cancellation_check,
            precount_sentences=precount_sentences,
        )
    )
    try:
        yield
    finally:
        _READING_UNIT_BUDGET.reset(token)


def check_reading_unit_capacity(additional: int) -> None:
    """Check cancellation and optional sentence fan-out before list growth."""

    if additional < 0:
        raise ValueError("Additional reading units must not be negative")
    budget = _READING_UNIT_BUDGET.get()
    if budget is not None:
        budget.check(additional)


def check_reading_unit_text_capacity(observed: int) -> None:
    """Reject an oversized pending unit before its text buffer grows."""

    if observed < 0:
        raise ValueError("Observed reading unit text bytes must not be negative")
    budget = _READING_UNIT_BUDGET.get()
    if budget is not None:
        budget.check_text(observed)


def _reserve_reading_unit() -> None:
    budget = _READING_UNIT_BUDGET.get()
    if budget is not None:
        budget.reserve()


@dataclass(frozen=True)
class ImageRef:
    """Deferred reference to a page/cover image, materialized in phase3'.

    Frozen + hashable so materialization dedups per unique ref (a page shared
    by many words converts once). Two shapes, told apart by ``entry``:

    * directory/file page — ``ImageRef(image_path)``: ``source`` is the image
      file on disk, ``entry`` is None.
    * archive page/cover — ``ImageRef(archive_path, entry_name)``: ``source``
      is the containing ``.cbz``/``.zip``/``.epub`` archive and ``entry`` is
      the member name. No bytes are extracted at load time.

    The two shapes compare and hash distinctly because ``entry`` is None for
    on-disk pages and a str for archive members.
    """

    source: Path
    entry: str | None = None


@dataclass(frozen=True)
class ReadingUnit:
    """One mining unit: a text span with its document position and image.

    ``index`` is document order and doubles as the dummy card start_time.
    ``location_label`` is a human page/chapter tag ("p.42" / "ch.3"). Frozen
    so a unit's ``image_ref`` can participate in per-ref materialization dedup.

    ``block_box`` is the mokuro block bounding box in original-page pixel
    coords (xmin, ymin, xmax, ymax); None for novels/txt and malformed blocks.
    Sentence-split pieces of one oversized block share the parent block's box.
    """

    text: str
    index: int
    location_label: str
    image_ref: ImageRef | None = None
    block_box: tuple[int, int, int, int] | None = None

    def __post_init__(self) -> None:
        _reserve_reading_unit()


@dataclass(frozen=True)
class ReadingSourceRef:
    """A detected, loadable source: one manga volume, novel file, or pasted text.

    Per-kind population contract:

    * kind="mokuro": the detector fully populates every field from the
      ``.mokuro`` JSON — ``title`` (= series), ``volume`` (= episode), and
      ``image_root`` (archive file Path for .cbz/.zip-backed volumes,
      directory Path for dir-backed, None for text-only). Loaders trust these.
      Two OCR placements: ``ocr_entry`` is None when ``path`` IS the sidecar
      ``.mokuro`` file on disk; for a self-contained archive (Issue #103) the
      ``.mokuro`` JSON lives *inside* the archive — then ``path`` and
      ``image_root`` are both the archive and ``ocr_entry`` names the member.
    * kind in {"epub","txt","subtitle"}: the detector sets ``title`` =
      ``path.stem`` (a provisional label for queue rows only), ``volume`` =
      None and ``image_root`` = None; the loader is authoritative for the
      final ``ReadingDocument`` metadata.
    * kind="text": built directly by the Text sub-tab, never by the detector —
      ``text`` holds the pasted content, ``title`` = "Text", and ``path`` /
      ``image_root`` / ``volume`` are None. Distinct from kind="txt", which is
      a ``.txt`` *file* on disk (aozora loader).

    ``path`` is always set for the file-backed kinds (their loaders assert
    this) and only None for kind="text".
    """

    kind: Literal["mokuro", "epub", "txt", "subtitle", "text"]
    path: Path | None = None
    image_root: Path | None = None
    title: str = ""
    volume: str | None = None
    text: str | None = None
    ocr_entry: str | None = None

    def __post_init__(self) -> None:
        # Every field defaults so kind="text" can be built positionally, but the
        # file-backed kinds must carry a path — their loaders assert it, and that
        # assert is stripped under `python -O`. Enforce the invariant at
        # construction so a malformed ref fails loudly at its source instead.
        if self.kind != "text" and self.path is None:
            raise ValueError(f"ReadingSourceRef(kind={self.kind!r}) requires a path")


@dataclass
class ReadingDocument:
    """A fully loaded document: ordered units plus load-time warnings.

    Not frozen — it carries mutable ``units``/``warnings`` populated during
    loading. ``warnings`` (text-only volume, unmatched pages, gaiji-image
    count, unusable cover, …) are surfaced up front by ``process_reading``.
    """

    title: str
    kind: Literal["manga", "book", "subtitle"]
    series: str
    episode: str
    units: list[ReadingUnit] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
