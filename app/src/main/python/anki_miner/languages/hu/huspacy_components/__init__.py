"""huspacy's pipeline components for ``hu_core_news_md`` 3.8.0, shipped as APK code (Android only).

The model's pipeline names factories (``hu.lookup_lemmatizer``,
``trainable_lemmatizer_v2``) that its package registers when it is imported.
Android downloads the model as data and loads it by path
(``languages/_spaced/android_models``), so the registering modules ship here
instead and are never downloaded. Importing this package registers them.

The three modules are byte-identical to the ``hu_core_news_md/`` members of the
pinned wheel (``hu_core_news_md-any-py3-none-any.whl``, sha256
``0fd89c6ccf0efe1d7591910065c3bec4eadb1e25313d6ceea551150832b0f861``):

- ``edit_tree_lemmatizer.py`` sha256 ``b5033d77382cadf549748e0b2d96a892b34a9b61df1d76c93771100fe15e065f``
- ``lemma_postprocessing.py`` sha256 ``c9638eb3b67e4f75d03877469df8778f64cafcf2083695a1b8f14cb929bd6dd6``
- ``lookup_lemmatizer.py`` sha256 ``8e1a676133e3328f47be6f11a1565e8f2c5567ad6336c57f8520ebfab678021a``

Their source is huspacy's ``huspacy/components/`` (Apache-2.0, Copyright 2021
György Orosz; text in ``assets/notices/huspacy-LICENSE``); the wheel's metadata
states CC BY-SA 4.0 for the package as a whole. Both are recorded in NOTICE.md.
"""

from . import edit_tree_lemmatizer, lemma_postprocessing, lookup_lemmatizer  # noqa: F401
