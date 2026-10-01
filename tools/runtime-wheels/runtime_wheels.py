#!/usr/bin/env python3
"""Build and publish the tokenizer-neutral Android Python runtime wheels."""

from __future__ import annotations

import argparse
import base64
import csv
import gzip
import hashlib
import importlib.util
import io
import json
import os
import platform
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
import zlib
from email.parser import BytesParser
from email.policy import compat32
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOL_ROOT = Path(__file__).resolve().parent
SOURCE_LOCK = TOOL_ROOT / "sources.lock"
HOST_LOCK = TOOL_ROOT / "host-wheels.lock"

ABIS = ("arm64-v8a", "x86_64")
API_LEVEL = 26
NDK_VERSION = "28.2.13676358"
PYTHON_TARGET = "3.12.12-0"
TARGET_BUILD_PYTHON_VERSION = "3.12.13"
TARGET_BUILD_PYTHON_ENV = "ANKI_MINER_CHAQUOPY_BUILD_PYTHON"
STAGE_SCHEMA = 1
PUBLICATION_SCHEMA = 2
ATTRIBUTION_SCHEMA = 1
HOST_LOCK_SCHEMA = 2
BUILDER_IDENTITY_SCHEMA = 3
SOURCE_DATE_EPOCH = "1704067200"
WHEEL_DATE_TIME = (2024, 1, 1, 0, 0, 0)

# Rust recipes build with this toolchain only. rustup provisions it
# (`rustup toolchain install 1.98.0 --profile minimal --target
# aarch64-linux-android --target x86_64-linux-android`) but never runs during a
# build: the builder puts the toolchain's own bin/ on PATH and verifies it.
RUST_TOOLCHAIN = {
    "release": "1.98.0",
    "commit_hash": "88d9e12ae178fab0fb5cc050a94da85685d449ea",
    "host": "x86_64-unknown-linux-gnu",
    "targets": {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"},
}
RUSTUP_HOME_ENV = "ANKI_MINER_RUSTUP_HOME"
NDK_LLVM_BIN = "toolchains/llvm/prebuilt/linux-x86_64/bin"
# Shipped beside the runtime publication by tools/wheels (chaquopy-libcxx);
# C++ extensions here link it, and Chaquopy records the requirement.
EXTERNAL_RUNTIME_LIBS = frozenset({"libc++_shared.so"})

FAMILIES = {
    "base": "tokenizer-neutral runtime: requests, pysubs2, Pillow, lxml",
    "f1": "Turkish (tr): zeyrek over nltk and regex",
    "f2": "spaCy languages, Russian and Ukrainian (pymorphy3)",
    "f3": "Korean (ko): kiwipiepy",
    "f4": "Vietnamese (vi): underthesea; Cantonese (yue): pycantonese over rustling",
}
REPRODUCIBLE_ENV = {
    "SOURCE_DATE_EPOCH": SOURCE_DATE_EPOCH,
    "PYTHONHASHSEED": "0",
    "TZ": "UTC",
    "LC_ALL": "C",
    "LANG": "C",
}

COMMON_SPECS = {
    "requests": ("2.34.2", "requests-2.34.2-py3-none-any.whl"),
    "pysubs2": ("1.8.1", "pysubs2-1.8.1-py3-none-any.whl"),
    "charset-normalizer": (
        "3.4.7",
        "charset_normalizer-3.4.7-py3-none-any.whl",
    ),
    "urllib3": ("2.7.0", "urllib3-2.7.0-py3-none-any.whl"),
    "idna": ("3.18", "idna-3.18-py3-none-any.whl"),
    "certifi": ("2026.6.17", "certifi-2026.6.17-py3-none-any.whl"),
    # F1: zeyrek and its nltk closure (click, joblib, tqdm and colorama are
    # nltk's; cloudpickle is joblib's).
    "click": ("8.1.8", "click-8.1.8-py3-none-any.whl"),
    "cloudpickle": ("3.1.2", "cloudpickle-3.1.2-py3-none-any.whl"),
    "colorama": ("0.4.6", "colorama-0.4.6-py2.py3-none-any.whl"),
    "defusedxml": ("0.7.1", "defusedxml-0.7.1-py2.py3-none-any.whl"),
    "joblib": ("1.6.0", "joblib-1.6.0-py3-none-any.whl"),
    "nltk": ("3.10.3", "nltk-3.10.3-py3-none-any.whl"),
    "tqdm": ("4.68.3", "tqdm-4.68.3-py3-none-any.whl"),
    "zeyrek": ("0.1.3", "zeyrek-0.1.3-py2.py3-none-any.whl"),
    # F2: the spaCy stack's pure closure, including what desktop's base bundle
    # hides (packaging, typing-extensions, rich, pygments and friends).
    "annotated-doc": ("0.0.4", "annotated_doc-0.0.4-py3-none-any.whl"),
    "annotated-types": ("0.8.0", "annotated_types-0.8.0-py3-none-any.whl"),
    "anyio": ("4.14.0", "anyio-4.14.0-py3-none-any.whl"),
    "catalogue": ("2.0.10", "catalogue-2.0.10-py3-none-any.whl"),
    "cloudpathlib": ("0.25.0", "cloudpathlib-0.25.0-py3-none-any.whl"),
    "confection": ("1.3.3", "confection-1.3.3-py3-none-any.whl"),
    "dawg2-python": ("0.9.0", "dawg2_python-0.9.0-py3-none-any.whl"),
    "h11": ("0.16.0", "h11-0.16.0-py3-none-any.whl"),
    "httpcore": ("1.0.9", "httpcore-1.0.9-py3-none-any.whl"),
    "httpx": ("0.28.1", "httpx-0.28.1-py3-none-any.whl"),
    "jinja2": ("3.1.6", "jinja2-3.1.6-py3-none-any.whl"),
    "markdown-it-py": ("4.2.0", "markdown_it_py-4.2.0-py3-none-any.whl"),
    "mdurl": ("0.1.2", "mdurl-0.1.2-py3-none-any.whl"),
    "packaging": ("26.2", "packaging-26.2-py3-none-any.whl"),
    "pydantic": ("2.13.5", "pydantic-2.13.5-py3-none-any.whl"),
    "pygments": ("2.20.0", "pygments-2.20.0-py3-none-any.whl"),
    "pymorphy3": ("2.0.6", "pymorphy3-2.0.6-py3-none-any.whl"),
    "rich": ("15.0.0", "rich-15.0.0-py3-none-any.whl"),
    "shellingham": ("1.5.4", "shellingham-1.5.4-py2.py3-none-any.whl"),
    "smart-open": ("8.0.1", "smart_open-8.0.1-py3-none-any.whl"),
    "spacy-legacy": ("3.0.12", "spacy_legacy-3.0.12-py2.py3-none-any.whl"),
    "spacy-loggers": ("1.0.5", "spacy_loggers-1.0.5-py3-none-any.whl"),
    "typer": ("0.26.7", "typer-0.26.7-py3-none-any.whl"),
    "typing-extensions": ("4.15.0", "typing_extensions-4.15.0-py3-none-any.whl"),
    "typing-inspection": ("0.4.4", "typing_inspection-0.4.4-py3-none-any.whl"),
    "wasabi": ("1.1.3", "wasabi-1.1.3-py3-none-any.whl"),
    "weasel": ("1.0.0", "weasel-1.0.0-py3-none-any.whl"),
    "wrapt": ("2.4.1", "wrapt-2.4.1-py3-none-any.whl"),
    # F4: underthesea's tokenize and tag path imports joblib and cloudpickle.
    "underthesea": ("9.5.0", "underthesea-9.5.0-py3-none-any.whl"),
}

NATIVE_SPECS = {
    "chaquopy-libjpeg": {
        "version": "1.5.3",
        "build": "3",
        "python": "py3",
        "abi": "none",
        "source": "libjpeg",
    },
    "chaquopy-freetype": {
        "version": "2.14.1",
        "build": "0",
        "python": "py3",
        "abi": "none",
        "source": "freetype",
    },
    "chaquopy-libwebp": {
        "version": "1.6.0",
        "build": "0",
        "python": "py3",
        "abi": "none",
        "source": "libwebp",
    },
    "chaquopy-libxml2": {
        "version": "2.14.6",
        "build": "0",
        "python": "py3",
        "abi": "none",
        "source": "libxml2",
    },
    "chaquopy-libxslt": {
        "version": "1.1.43",
        "build": "0",
        "python": "py3",
        "abi": "none",
        "source": "libxslt",
    },
    "pillow": {
        "version": "12.2.0",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "pillow",
    },
    "lxml": {
        "version": "6.1.1",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "lxml",
    },
    "regex": {
        "version": "2026.9.10",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "regex",
    },
    "blis": {
        "version": "1.3.3",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "blis",
    },
    "cymem": {
        "version": "2.0.13",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "cymem",
    },
    "markupsafe": {
        "version": "3.0.3",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "markupsafe",
    },
    "murmurhash": {
        "version": "1.0.15",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "murmurhash",
    },
    "numpy": {
        "version": "2.5.0",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "numpy",
    },
    "preshed": {
        "version": "3.0.13",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "preshed",
    },
    "pydantic-core": {
        "version": "2.46.5",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "pydantic-core",
    },
    "spacy": {
        "version": "3.8.14",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "spacy",
    },
    "srsly": {
        "version": "2.5.3",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "srsly",
    },
    "thinc": {
        "version": "8.3.13",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "thinc",
    },
    "kiwipiepy": {
        "version": "0.23.2",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "kiwipiepy",
    },
    "pycantonese": {
        "version": "5.0.0",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "pycantonese",
    },
    "rustling": {
        "version": "0.9.0",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "rustling",
    },
    "underthesea-core": {
        "version": "3.3.2",
        "build": "0",
        "python": "cp312",
        "abi": "cp312",
        "source": "underthesea-core",
    },
}

SOURCE_SPECS = {
    "certifi": ("prebuilt-wheel", "certifi", "2026.6.17"),
    "chaquopy": ("build-tool-source", None, None),
    "charset-normalizer": ("prebuilt-wheel", "charset-normalizer", "3.4.7"),
    "freetype": ("runtime-source", "chaquopy-freetype", "2.14.1"),
    "idna": ("prebuilt-wheel", "idna", "3.18"),
    "libjpeg": ("runtime-source", "chaquopy-libjpeg", "1.5.3"),
    "libwebp": ("runtime-source", "chaquopy-libwebp", "1.6.0"),
    "libxml2": ("runtime-source", "chaquopy-libxml2", "2.14.6"),
    "libxslt": ("runtime-source", "chaquopy-libxslt", "1.1.43"),
    "lxml": ("runtime-source", "lxml", "6.1.1"),
    "patchelf": ("build-tool-binary", None, None),
    "pillow": ("runtime-source", "pillow", "12.2.0"),
    "pysubs2": ("prebuilt-wheel", "pysubs2", "1.8.1"),
    "python-arm64-v8a": ("python-target", None, None),
    "python-x86_64": ("python-target", None, None),
    "requests": ("prebuilt-wheel", "requests", "2.34.2"),
    "urllib3": ("prebuilt-wheel", "urllib3", "2.7.0"),
    "click": ("prebuilt-wheel", "click", "8.1.8"),
    "cloudpickle": ("prebuilt-wheel", "cloudpickle", "3.1.2"),
    "colorama": ("prebuilt-wheel", "colorama", "0.4.6"),
    "defusedxml": ("prebuilt-wheel", "defusedxml", "0.7.1"),
    "joblib": ("prebuilt-wheel", "joblib", "1.6.0"),
    "nltk": ("prebuilt-wheel", "nltk", "3.10.3"),
    "regex": ("runtime-source", "regex", "2026.9.10"),
    "tqdm": ("prebuilt-wheel", "tqdm", "4.68.3"),
    "zeyrek": ("prebuilt-wheel", "zeyrek", "0.1.3"),
    "annotated-doc": ("prebuilt-wheel", "annotated-doc", "0.0.4"),
    "annotated-types": ("prebuilt-wheel", "annotated-types", "0.8.0"),
    "anyio": ("prebuilt-wheel", "anyio", "4.14.0"),
    "catalogue": ("prebuilt-wheel", "catalogue", "2.0.10"),
    "cloudpathlib": ("prebuilt-wheel", "cloudpathlib", "0.25.0"),
    "confection": ("prebuilt-wheel", "confection", "1.3.3"),
    "dawg2-python": ("prebuilt-wheel", "dawg2-python", "0.9.0"),
    "h11": ("prebuilt-wheel", "h11", "0.16.0"),
    "httpcore": ("prebuilt-wheel", "httpcore", "1.0.9"),
    "httpx": ("prebuilt-wheel", "httpx", "0.28.1"),
    "jinja2": ("prebuilt-wheel", "jinja2", "3.1.6"),
    "markdown-it-py": ("prebuilt-wheel", "markdown-it-py", "4.2.0"),
    "mdurl": ("prebuilt-wheel", "mdurl", "0.1.2"),
    "packaging": ("prebuilt-wheel", "packaging", "26.2"),
    "pydantic": ("prebuilt-wheel", "pydantic", "2.13.5"),
    "pygments": ("prebuilt-wheel", "pygments", "2.20.0"),
    "pymorphy3": ("prebuilt-wheel", "pymorphy3", "2.0.6"),
    "rich": ("prebuilt-wheel", "rich", "15.0.0"),
    "shellingham": ("prebuilt-wheel", "shellingham", "1.5.4"),
    "smart-open": ("prebuilt-wheel", "smart-open", "8.0.1"),
    "spacy-legacy": ("prebuilt-wheel", "spacy-legacy", "3.0.12"),
    "spacy-loggers": ("prebuilt-wheel", "spacy-loggers", "1.0.5"),
    "typer": ("prebuilt-wheel", "typer", "0.26.7"),
    "typing-extensions": ("prebuilt-wheel", "typing-extensions", "4.15.0"),
    "typing-inspection": ("prebuilt-wheel", "typing-inspection", "0.4.4"),
    "wasabi": ("prebuilt-wheel", "wasabi", "1.1.3"),
    "weasel": ("prebuilt-wheel", "weasel", "1.0.0"),
    "wrapt": ("prebuilt-wheel", "wrapt", "2.4.1"),
    "blis": ("runtime-source", "blis", "1.3.3"),
    "cymem": ("runtime-source", "cymem", "2.0.13"),
    "markupsafe": ("runtime-source", "markupsafe", "3.0.3"),
    "murmurhash": ("runtime-source", "murmurhash", "1.0.15"),
    "numpy": ("runtime-source", "numpy", "2.5.0"),
    "preshed": ("runtime-source", "preshed", "3.0.13"),
    "pydantic-core": ("runtime-source", "pydantic-core", "2.46.5"),
    "spacy": ("runtime-source", "spacy", "3.8.14"),
    "srsly": ("runtime-source", "srsly", "2.5.3"),
    "thinc": ("runtime-source", "thinc", "8.3.13"),
    "kiwipiepy": ("runtime-source", "kiwipiepy", "0.23.2"),
    "pycantonese": ("runtime-source", "pycantonese", "5.0.0"),
    "rustling": ("runtime-source", "rustling", "0.9.0"),
    "underthesea": ("prebuilt-wheel", "underthesea", "9.5.0"),
    "underthesea-core": ("runtime-source", "underthesea-core", "3.3.2"),
}

HOST_REQUIREMENTS = {
    "attrs==26.1.0": {"outer"},
    "build==1.2.2.post1": {"outer"},
    "Cython==3.2.4": {"target"},
    "Jinja2==3.1.6": {"outer"},
    "jsonschema==4.23.0": {"outer"},
    "jsonschema-specifications==2025.9.1": {"outer"},
    "MarkupSafe==3.0.3": {"outer"},
    "packaging==26.2": {"outer"},
    "pip==25.1.1": {"outer", "target"},
    "pybind11==3.0.1": {"target"},
    # Rust (maturin), CMake and meson recipes install these in their build env.
    "cmake==4.4.3": {"target"},
    "maturin==1.15.0": {"target"},
    "meson==1.12.1": {"target"},
    "meson-python==0.22.0": {"target"},
    "ninja==1.13.2": {"target"},
    "pyproject-metadata==0.12.1": {"target"},
    # The spaCy stack's Cython builds read these for headers and .pxd files.
    "annotated-types==0.8.0": {"target"},
    "blis==1.3.3": {"target"},
    "catalogue==2.0.10": {"target"},
    "confection==1.3.3": {"target"},
    "cymem==2.0.13": {"target"},
    "murmurhash==1.0.15": {"target"},
    "numpy==2.5.0": {"target"},
    "preshed==3.0.13": {"target"},
    "pydantic-core==2.46.5": {"target"},
    "pydantic==2.13.5": {"target"},
    "srsly==2.5.3": {"target"},
    "thinc==8.3.13": {"target"},
    "typing-extensions==4.15.0": {"target"},
    "typing-inspection==0.4.4": {"target"},
    "wasabi==1.1.3": {"target"},
    "pyelftools==0.32": {"outer"},
    "pyproject-hooks==1.2.0": {"outer"},
    "PyYAML==6.0.2": {"outer"},
    "referencing==0.37.0": {"outer"},
    "rpds-py==2026.6.3": {"outer"},
    "setuptools==78.1.1": {"outer", "target"},
    "wheel==0.45.1": {"outer", "target"},
}

MANDATORY_DEPENDENCIES = {
    "requests": {"charset-normalizer", "idna", "urllib3", "certifi"},
    "pysubs2": set(),
    "charset-normalizer": set(),
    "urllib3": set(),
    "idna": set(),
    "certifi": set(),
    "chaquopy-libjpeg": set(),
    "chaquopy-freetype": set(),
    "chaquopy-libwebp": set(),
    "chaquopy-libxml2": set(),
    "chaquopy-libxslt": {"chaquopy-libxml2"},
    "pillow": {"chaquopy-libjpeg", "chaquopy-freetype", "chaquopy-libwebp"},
    "lxml": {"chaquopy-libxml2", "chaquopy-libxslt"},
    "click": {"colorama", "importlib-metadata"},
    "cloudpickle": set(),
    "colorama": set(),
    "defusedxml": set(),
    "joblib": {"cloudpickle"},
    "nltk": {"click", "defusedxml", "joblib", "regex", "tqdm"},
    "regex": set(),
    "tqdm": {"colorama"},
    "zeyrek": {"nltk"},
    "annotated-doc": set(),
    "annotated-types": set(),
    "anyio": {"exceptiongroup", "idna", "typing-extensions"},
    "catalogue": {"typing-extensions", "zipp"},
    "cloudpathlib": {"typing-extensions"},
    "confection": {"typing-extensions"},
    "dawg2-python": {"typing-extensions"},
    "h11": set(),
    "httpcore": {"certifi", "h11"},
    "httpx": {"anyio", "certifi", "httpcore", "idna"},
    "jinja2": {"markupsafe"},
    "markdown-it-py": {"mdurl"},
    "mdurl": set(),
    "packaging": set(),
    "pydantic": {"annotated-types", "pydantic-core", "typing-extensions", "typing-inspection"},
    "pygments": set(),
    "pymorphy3": {"dawg2-python", "pymorphy3-dicts-ru", "setuptools"},
    "rich": {"markdown-it-py", "pygments"},
    "shellingham": set(),
    "smart-open": {"wrapt"},
    "spacy-legacy": set(),
    "spacy-loggers": set(),
    "typer": {"annotated-doc", "colorama", "rich", "shellingham"},
    "typing-extensions": set(),
    "typing-inspection": {"typing-extensions"},
    "wasabi": {"colorama", "typing-extensions"},
    "weasel": {
        "cloudpathlib",
        "confection",
        "httpx",
        "packaging",
        "pydantic",
        "smart-open",
        "srsly",
        "typer",
        "wasabi",
    },
    "wrapt": set(),
    "blis": {"numpy"},
    "cymem": {"chaquopy-libcxx"},
    "markupsafe": set(),
    "murmurhash": {"chaquopy-libcxx"},
    "numpy": {"chaquopy-libcxx"},
    "preshed": {"chaquopy-libcxx", "cymem", "murmurhash"},
    "pydantic-core": {"typing-extensions"},
    "spacy": {
        "catalogue",
        "chaquopy-libcxx",
        "confection",
        "cymem",
        "jinja2",
        "murmurhash",
        "numpy",
        "packaging",
        "preshed",
        "pydantic",
        "requests",
        "setuptools",
        "spacy-legacy",
        "spacy-loggers",
        "srsly",
        "thinc",
        "tqdm",
        "typer",
        "wasabi",
        "weasel",
    },
    "srsly": {"catalogue", "chaquopy-libcxx"},
    "thinc": {
        "blis",
        "catalogue",
        "chaquopy-libcxx",
        "confection",
        "cymem",
        "murmurhash",
        "numpy",
        "packaging",
        "preshed",
        "pydantic",
        "setuptools",
        "srsly",
        "wasabi",
    },
    # numpy is declared but never imported by Kiwi().tokenize; the model is
    # downloaded data. Chaquopy installs with --no-deps.
    "kiwipiepy": {"chaquopy-libcxx", "dataclasses", "kiwipiepy-model", "numpy", "tqdm"},
    "pycantonese": {"rustling"},
    "rustling": set(),
    # Only underthesea-core and joblib are imported on the tokenize and tag path.
    "underthesea": {"click", "huggingface-hub", "joblib", "pyyaml", "requests", "tqdm", "underthesea-core"},
    "underthesea-core": set(),
}

NATIVE_REQUIRED_PATHS = {
    "chaquopy-libjpeg": {"chaquopy/lib/libjpeg_chaquopy.so"},
    "chaquopy-freetype": {"chaquopy/lib/libfreetype.so"},
    "chaquopy-libwebp": {
        "chaquopy/lib/libwebp.so",
        "chaquopy/lib/libwebpdemux.so",
        "chaquopy/lib/libwebpmux.so",
    },
    "chaquopy-libxml2": {"chaquopy/lib/libxml2.so"},
    "chaquopy-libxslt": {
        "chaquopy/lib/libexslt.so",
        "chaquopy/lib/libxslt.so",
    },
    "pillow": {"PIL/_imaging.so", "PIL/_imagingft.so"},
    "lxml": {"lxml/etree.so", "lxml/objectify.so"},
    "regex": {"regex/_regex.so"},
    "blis": {"blis/cy.so", "blis/py.so"},
    "cymem": {"cymem/cymem.so"},
    "markupsafe": {"markupsafe/_speedups.so"},
    "murmurhash": {"murmurhash/mrmr.so"},
    "numpy": {"numpy/_core/_multiarray_umath.so"},
    "preshed": {"preshed/maps.so"},
    "pydantic-core": {"pydantic_core/_pydantic_core.so"},
    "spacy": {"spacy/tokenizer.so", "spacy/vocab.so"},
    "srsly": {"srsly/ujson/ujson.so"},
    "thinc": {"thinc/backends/numpy_ops.so"},
    "kiwipiepy": {"_kiwipiepy.so"},
    "pycantonese": {"pycantonese/_rust.so"},
    "rustling": {"rustling/_lib_name.so"},
    "underthesea-core": {"underthesea_core/underthesea_core.so"},
}

REQUIRED_NEEDED = {
    "chaquopy-libjpeg": set(),
    "chaquopy-freetype": set(),
    "chaquopy-libwebp": set(),
    "chaquopy-libxml2": {"libz.so"},
    "chaquopy-libxslt": {"libxml2.so", "libxslt.so"},
    "pillow": {
        "libpython3.12.so",
        "libjpeg_chaquopy.so",
        "libfreetype.so",
        "libwebp.so",
        "libz.so",
    },
    "lxml": {
        "libpython3.12.so",
        "libxml2.so",
        "libxslt.so",
        "libexslt.so",
    },
    "regex": {"libpython3.12.so"},
    "blis": {"libpython3.12.so"},
    "cymem": {"libc++_shared.so", "libpython3.12.so"},
    "markupsafe": {"libpython3.12.so"},
    "murmurhash": {"libc++_shared.so", "libpython3.12.so"},
    "numpy": {"libc++_shared.so", "libpython3.12.so"},
    "preshed": {"libc++_shared.so", "libpython3.12.so"},
    "pydantic-core": {"libpython3.12.so"},
    "spacy": {"libc++_shared.so", "libpython3.12.so"},
    "srsly": {"libc++_shared.so", "libpython3.12.so"},
    "thinc": {"libc++_shared.so", "libpython3.12.so"},
    # Kiwi's bundled cpuinfo logs through liblog.
    "kiwipiepy": {"libc++_shared.so", "liblog.so", "libpython3.12.so"},
    "pycantonese": {"libpython3.12.so"},
    "rustling": {"libpython3.12.so"},
    "underthesea-core": {"libpython3.12.so"},
}

ANDROID_SYSTEM_LIBS = {
    "libandroid.so",
    "libc.so",
    "libdl.so",
    "liblog.so",
    "libm.so",
    "libz.so",
}
RUNTIME_NATIVE_LIBS = {
    "libc++_shared.so",
    "libpython3.12.so",
    "libjpeg_chaquopy.so",
    "libfreetype.so",
    "libsharpyuv.so",
    "libwebp.so",
    "libwebpdecoder.so",
    "libwebpdemux.so",
    "libwebpmux.so",
    "libxml2.so",
    "libexslt.so",
    "libxslt.so",
}

# The packages each family's wave vendors into app/wheels. A package may serve
# several families; every published package belongs to at least one.
FAMILY_PACKAGES: dict[str, tuple[str, ...]] = {
    "base": (
        "certifi",
        "chaquopy-freetype",
        "chaquopy-libjpeg",
        "chaquopy-libwebp",
        "chaquopy-libxml2",
        "chaquopy-libxslt",
        "charset-normalizer",
        "idna",
        "lxml",
        "pillow",
        "pysubs2",
        "requests",
        "urllib3",
    ),
    "f1": (
        "click",
        "cloudpickle",
        "colorama",
        "defusedxml",
        "joblib",
        "nltk",
        "regex",
        "tqdm",
        "zeyrek",
    ),
    "f2": (
        "annotated-doc",
        "annotated-types",
        "anyio",
        "blis",
        "catalogue",
        "click",
        "cloudpathlib",
        "confection",
        "cymem",
        "dawg2-python",
        "h11",
        "httpcore",
        "httpx",
        "jinja2",
        "markdown-it-py",
        "markupsafe",
        "mdurl",
        "murmurhash",
        "numpy",
        "packaging",
        "preshed",
        "pydantic",
        "pydantic-core",
        "pygments",
        "pymorphy3",
        "rich",
        "shellingham",
        "smart-open",
        "spacy",
        "spacy-legacy",
        "spacy-loggers",
        "srsly",
        "thinc",
        "tqdm",
        "typer",
        "typing-extensions",
        "typing-inspection",
        "wasabi",
        "weasel",
        "wrapt",
    ),
    "f3": ("kiwipiepy", "tqdm"),
    "f4": ("cloudpickle", "joblib", "pycantonese", "rustling", "underthesea", "underthesea-core"),
}
PACKAGE_FAMILIES: dict[str, tuple[str, ...]] = {
    package: tuple(family for family, packages in FAMILY_PACKAGES.items() if package in packages)
    for package in sorted({package for packages in FAMILY_PACKAGES.values() for package in packages})
}
# Members dropped when a wheel is repacked for publication: (package
# directory, excludes). Excludes follow desktop pack.py: an entry ending in "/"
# drops that subtree, any other entry exactly one file.
REPACKS: dict[str, tuple[str, tuple[str, ...]]] = {
    # nltk's doctest suite: 1.6 MB the tr path never imports.
    "nltk": ("nltk/", ("test/",)),
    # Desktop yue/pack.py: the CTCPC and Common Voice training corpora, the
    # GPL-3 CantoMap data and the training scripts never ship.
    "pycantonese": (
        "pycantonese/",
        (
            "data/ctcpc/",
            "data/cantomap/",
            "data/common_voice/",
            "word_segmentation/train_segmenter.py",
            "pos_tagging/train_tagger.py",
        ),
    ),
    # Desktop vi/pack.py: what the tokenize and tag path never imports.
    "underthesea": (
        "underthesea/",
        (
            "address/",
            "agent/",
            "models/dependency_parser.py",
            "modules/",
            "pipeline/dependency_parse/",
            "pipeline/translate/",
            "pipeline/tts/",
            "trainers/",
            "utils/",
        ),
    ),
}
# Pure packages published only as an sdist: the member prefix that becomes the
# wheel's package directory. The wheel carries the sdist's PKG-INFO as METADATA.
SDIST_WHEELS: dict[str, str] = {}
# Native payload locations for packages with more than their required modules:
# an entry ending in "/" admits any .so below it, any other entry one path.
NATIVE_ROOTS: dict[str, tuple[str, ...]] = {
    "numpy": ("numpy/",),
    "preshed": ("preshed/",),
    "spacy": ("spacy/",),
    "srsly": ("srsly/",),
    "thinc": ("thinc/",),
}
# Members whose bytes are pinned by a recipe-side JSON file:
# {"prefix": "...", "members": {"path": "sha256"}}. The wheel must carry
# exactly these members below the prefix.
PINNED_MEMBER_FILES: dict[str, str] = {}

OPTIONAL_SOURCE_KEYS = {"cargo_manifest", "cargo_vendor", "git_commit"}
LICENSE_PREFIXES = ("LICENSE", "LICENCE", "COPYING", "COPYRIGHT", "NOTICE", "FTL")

FORBIDDEN_PACKAGE_NAMES = {
    "unidic",
    "unidic-lite",
    "pyqt6",
    "gtts",
    "yt-dlp",
}
FORBIDDEN_PAYLOAD_MARKERS = (
    b"unidic-lite",
    b"unidic_lite",
    b"sys.dic",
    b"matrix.bin",
    b"PyQt6",
    b"yt-dlp",
    b"yt_dlp",
    b"gtts",
)
# POSIX build roots only: the builder is Linux, and drive-letter byte pairs
# such as b"C:\\" occur as ordinary x86_64 machine code in Rust extensions.
BUILD_PATH_MARKERS = (b"/home/", b"/tmp/", b"/Users/")

RECIPE_FILES = (
    "build-runtime-wheels.sh",
    "host-wheels.lock",
    "runtime_wheels.py",
    "sources.lock",
)
RECIPE_REPO_FILES = (
    "scripts/android-env.sh",
    "scripts/android-licenses.sh",
    "scripts/chaquopy-build-python.lock.json",
    "scripts/check_native_artifacts.py",
    "scripts/provision-chaquopy-build-python.sh",
    "scripts/verify_chaquopy_build_python.py",
)

KEY_PATTERN = re.compile(r"[0-9a-f]{64}")
STAGE_ID_PATTERN = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?")
NATIVE_WHEEL_NAME = re.compile(
    r"^(?P<package>[A-Za-z0-9_.]+)-(?P<version>[^-]+)-(?P<build>\d+)-"
    r"(?P<python>[^-]+)-(?P<abi_tag>[^-]+)-android_26_"
    r"(?P<platform>arm64_v8a|x86_64)\.whl$",
)
PLATFORM_ABI = {"arm64_v8a": "arm64-v8a", "x86_64": "x86_64"}
MAX_ARCHIVE_MEMBERS = 100_000
MAX_ARCHIVE_MEMBER_SIZE = 1024 * 1024 * 1024
MAX_ARCHIVE_TOTAL_SIZE = 4 * 1024 * 1024 * 1024


class RuntimeWheelError(RuntimeError):
    pass


def normalize_package(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).casefold()


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def canonical_json(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def require_exact_keys(value: object, expected: set[str], label: str) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != expected:
        raise RuntimeWheelError(f"{label} must contain exactly {sorted(expected)}")
    return value


def load_json(path: Path, expected_schema: int) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise RuntimeWheelError(f"invalid JSON document: {path}") from error
    if not isinstance(value, dict) or value.get("schema") != expected_schema:
        raise RuntimeWheelError(f"unsupported schema: {path}")
    return value


def _validate_license(value: object, label: str) -> dict[str, object]:
    """Validate a locked licence: archive members, plus texts the repository carries.

    ``carried`` names licence files committed beside the recipe (natives) or
    under ``licenses/<package>/`` (pure wheels), for texts an upstream archive
    omits. Both maps pin each file's SHA-256.
    """
    keys = {"expression", "members", "carried"} if isinstance(value, dict) and "carried" in value else None
    license_value = require_exact_keys(value, keys or {"expression", "members"}, label)
    expression = license_value.get("expression")
    members = license_value.get("members")
    carried = license_value.get("carried", {})
    if not isinstance(expression, str) or not expression.strip():
        raise RuntimeWheelError(f"{label} has no license expression")
    if not isinstance(members, dict) or not isinstance(carried, dict) or not (members or carried):
        raise RuntimeWheelError(f"{label} has no license members")
    if keys is not None and not carried:
        raise RuntimeWheelError(f"{label} has an empty carried licence map")
    for member, sha256 in members.items():
        if (
            not isinstance(member, str)
            or not _is_safe_archive_name(member)
            or not isinstance(sha256, str)
            or KEY_PATTERN.fullmatch(sha256) is None
        ):
            raise RuntimeWheelError(f"{label} has an unsafe license member")
    for name, sha256 in carried.items():
        if (
            not isinstance(name, str)
            or Path(name).name != name
            or not name.upper().startswith(LICENSE_PREFIXES)
            or not isinstance(sha256, str)
            or KEY_PATTERN.fullmatch(sha256) is None
        ):
            raise RuntimeWheelError(f"{label} has an unsafe carried licence")
    return license_value


def _validate_source_extras(entry: dict[str, object], label: str) -> None:
    """Validate the optional cargo vendor and git commit pins of a source."""
    if "cargo_vendor" in entry or "cargo_manifest" in entry:
        vendor = require_exact_keys(entry.get("cargo_vendor"), {"filename", "sha256"}, f"{label} cargo vendor")
        manifest = entry.get("cargo_manifest")
        filename = vendor.get("filename")
        sha256 = vendor.get("sha256")
        if (
            entry.get("kind") != "runtime-source"
            or not isinstance(manifest, str)
            or not _is_safe_archive_name(manifest)
            or Path(manifest).name != "Cargo.toml"
            or not isinstance(filename, str)
            or Path(filename).name != filename
            or not filename.endswith("-cargo-vendor.tar.gz")
            or not isinstance(sha256, str)
            or KEY_PATTERN.fullmatch(sha256) is None
        ):
            raise RuntimeWheelError(f"{label} has an unsafe cargo vendor pin")
    if "git_commit" in entry:
        commit = entry.get("git_commit")
        if not isinstance(commit, str) or re.fullmatch(r"[0-9a-f]{40}", commit) is None:
            raise RuntimeWheelError(f"{label} has an invalid git commit pin")


def source_entries() -> dict[str, dict[str, object]]:
    document = load_json(SOURCE_LOCK, 1)
    require_exact_keys(document, {"schema", "sources"}, "source lock")
    raw = document.get("sources")
    if not isinstance(raw, dict) or set(raw) != set(SOURCE_SPECS):
        raise RuntimeWheelError("source lock inventory differs from the runtime contract")
    result: dict[str, dict[str, object]] = {}
    for name, expected in SOURCE_SPECS.items():
        kind, package, version = expected
        raw_entry = raw.get(name)
        base_keys = {"filename", "kind", "sha256", "url"}
        if name not in {"patchelf", "python-arm64-v8a", "python-x86_64"}:
            base_keys.add("license")
        if package is not None:
            base_keys.update({"package", "version"})
        if isinstance(raw_entry, dict):
            base_keys.update(set(raw_entry) & OPTIONAL_SOURCE_KEYS)
        entry = require_exact_keys(raw_entry, base_keys, f"source lock entry {name}")
        filename = entry.get("filename")
        sha256 = entry.get("sha256")
        url = entry.get("url")
        if (
            entry.get("kind") != kind
            or not isinstance(filename, str)
            or not filename
            or Path(filename).name != filename
            or not isinstance(sha256, str)
            or KEY_PATTERN.fullmatch(sha256) is None
            or not isinstance(url, str)
            or not url.startswith("https://")
        ):
            raise RuntimeWheelError(f"unsafe source lock entry: {name}")
        if package is not None and (
            normalize_package(str(entry.get("package"))) != normalize_package(package)
            or entry.get("version") != version
        ):
            raise RuntimeWheelError(f"source identity mismatch: {name}")
        if "license" in entry:
            _validate_license(entry.get("license"), f"source license {name}")
        _validate_source_extras(entry, f"source lock entry {name}")
        result[name] = entry
    prebuilt = {
        normalize_package(str(entry["package"])): str(entry["filename"])
        for entry in result.values()
        if entry["kind"] == "prebuilt-wheel"
    }
    prebuilt.update(
        {
            normalize_package(str(entry["package"])): COMMON_SPECS[normalize_package(str(entry["package"]))][1]
            for entry in result.values()
            if entry["kind"] == "pure-sdist" and normalize_package(str(entry["package"])) in COMMON_SPECS
        }
    )
    if prebuilt != {name: filename for name, (_, filename) in COMMON_SPECS.items()}:
        raise RuntimeWheelError("prebuilt common wheel inventory differs from the contract")
    return result


def host_entries() -> list[dict[str, object]]:
    document = load_json(HOST_LOCK, HOST_LOCK_SCHEMA)
    require_exact_keys(document, {"schema", "wheels"}, "host wheel lock")
    raw = document.get("wheels")
    if not isinstance(raw, list) or not raw:
        raise RuntimeWheelError("host wheel lock has no wheels")
    result: list[dict[str, object]] = []
    seen_requirements: set[str] = set()
    seen_filenames: set[str] = set()
    for raw_entry in raw:
        entry = require_exact_keys(
            raw_entry,
            {"filename", "requirement", "roles", "sha256", "url"},
            "host wheel lock entry",
        )
        requirement = entry.get("requirement")
        filename = entry.get("filename")
        roles = entry.get("roles")
        sha256 = entry.get("sha256")
        url = entry.get("url")
        if (
            not isinstance(requirement, str)
            or requirement not in HOST_REQUIREMENTS
            or not isinstance(filename, str)
            or Path(filename).name != filename
            or not filename.endswith(".whl")
            or not isinstance(roles, list)
            or not roles
            or set(roles) != HOST_REQUIREMENTS[requirement]
            or len(roles) != len(set(roles))
            or not isinstance(sha256, str)
            or KEY_PATTERN.fullmatch(sha256) is None
            or not isinstance(url, str)
            or not url.startswith("https://")
        ):
            raise RuntimeWheelError(f"unsafe host wheel lock entry: {requirement}")
        if requirement in seen_requirements or filename in seen_filenames:
            raise RuntimeWheelError("duplicate host wheel lock entry")
        seen_requirements.add(requirement)
        seen_filenames.add(filename)
        result.append(entry)
    if seen_requirements != set(HOST_REQUIREMENTS):
        raise RuntimeWheelError("host wheel lock inventory differs from the contract")
    target_filenames = {str(entry["filename"]) for entry in result if "target" in entry["roles"]}
    if any("cp313" in filename for filename in target_filenames):
        raise RuntimeWheelError("target build wheels must not use the cp313 ABI")
    cython = next(entry for entry in result if entry["requirement"] == "Cython==3.2.4")
    if "cp312-cp312" not in str(cython["filename"]):
        raise RuntimeWheelError("target Cython wheel must use the cp312 ABI")
    return result


def host_requirements(role: str) -> list[str]:
    if role not in {"outer", "target"}:
        raise RuntimeWheelError(f"unknown host wheel role: {role}")
    return [str(entry["requirement"]) for entry in host_entries() if role in entry["roles"]]


def verify_file(path: Path, expected: str) -> None:
    if not path.is_file() or path.is_symlink() or digest(path) != expected:
        raise RuntimeWheelError(f"missing or hash-mismatched input: {path}")


def _download(url: str, target: Path, expected: str) -> None:
    if target.is_file() and digest(target) == expected:
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".partial")
    if temporary.exists():
        temporary.unlink()
    request = urllib.request.Request(url, headers={"User-Agent": "anki-miner-runtime-builder/1"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            if getattr(response, "status", 200) != 200:
                raise RuntimeWheelError(f"download failed: {url}")
            with temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
        verify_file(temporary, expected)
        os.replace(temporary, target)
    finally:
        if temporary.exists():
            temporary.unlink()


def rustup_home() -> Path:
    value = os.environ.get(RUSTUP_HOME_ENV) or os.environ.get("RUSTUP_HOME")
    return Path(value) if value else Path.home() / ".rustup"


def rust_toolchain_bin(home: Path | None = None) -> Path:
    toolchain = f"{RUST_TOOLCHAIN['release']}-{RUST_TOOLCHAIN['host']}"
    return (home or rustup_home()) / "toolchains" / toolchain / "bin"


def validate_rust_identity(value: object) -> dict[str, object]:
    identity = require_exact_keys(value, {"release", "commit_hash", "host", "targets"}, "Rust toolchain identity")
    expected = {
        "release": RUST_TOOLCHAIN["release"],
        "commit_hash": RUST_TOOLCHAIN["commit_hash"],
        "host": RUST_TOOLCHAIN["host"],
        "targets": sorted(RUST_TOOLCHAIN["targets"].values()),
    }
    if identity != expected:
        raise RuntimeWheelError(f"Rust toolchain differs from the pinned toolchain: {identity}")
    return identity


def rust_identity(home: Path | None = None) -> dict[str, object]:
    """Identify the pinned toolchain by rustc's own report, without calling rustup."""
    bin_dir = rust_toolchain_bin(home)
    rustc = bin_dir / "rustc"
    if not rustc.is_file() or not os.access(rustc, os.X_OK):
        raise RuntimeWheelError(
            f"pinned Rust toolchain is missing: {bin_dir}; run `rustup toolchain install "
            f"{RUST_TOOLCHAIN['release']} --profile minimal --target aarch64-linux-android "
            "--target x86_64-linux-android`",
        )
    environment = {**os.environ, "LC_ALL": "C", "LANG": "C"}
    report = subprocess.run([str(rustc), "-vV"], check=True, capture_output=True, text=True, env=environment)
    fields = dict(line.split(": ", 1) for line in report.stdout.splitlines() if ": " in line)
    sysroot = subprocess.run(
        [str(rustc), "--print", "sysroot"],
        check=True,
        capture_output=True,
        text=True,
        env=environment,
    ).stdout.strip()
    return validate_rust_identity(
        {
            "release": fields.get("release"),
            "commit_hash": fields.get("commit-hash"),
            "host": fields.get("host"),
            "targets": sorted(
                triple
                for triple in RUST_TOOLCHAIN["targets"].values()
                if (Path(sysroot) / "lib/rustlib" / triple / "lib").is_dir()
            ),
        },
    )


def deterministic_tar(root: Path, arcname: str, output: Path) -> None:
    """Pack ``root`` as ``arcname/...`` with sorted members and fixed metadata."""
    epoch = int(SOURCE_DATE_EPOCH)
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w", format=tarfile.PAX_FORMAT) as archive:
        paths = sorted(root.rglob("*"), key=lambda path: path.relative_to(root).as_posix())
        for path in [root, *paths]:
            if path.is_symlink():
                raise RuntimeWheelError(f"vendored tree contains a symlink: {path}")
            relative = path.relative_to(root).as_posix()
            info = tarfile.TarInfo(arcname if relative == "." else f"{arcname}/{relative}")
            info.mtime = epoch
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            if path.is_dir():
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                archive.addfile(info)
            else:
                data = path.read_bytes()
                info.size = len(data)
                info.mode = 0o755 if os.access(path, os.X_OK) else 0o644
                archive.addfile(info, io.BytesIO(data))
    with (
        output.open("wb") as raw,
        gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as stream,
    ):
        stream.write(buffer.getvalue())


def cargo_vendor(name: str, entry: dict[str, object], downloads: Path) -> Path:
    """Regenerate a locked ``cargo vendor`` tarball; the only networked Rust step.

    The tarball is a derived input: cargo fetches each crate the sdist's
    Cargo.lock names and re-verifies its checksum, and the packed tree must hash
    to the lock. Builds then run offline against it.
    """
    vendor = entry["cargo_vendor"]
    assert isinstance(vendor, dict)
    sdist = downloads / str(entry["filename"])
    verify_file(sdist, str(entry["sha256"]))
    rust_identity()
    bin_dir = rust_toolchain_bin()
    output = downloads / str(vendor["filename"])
    with tempfile.TemporaryDirectory(prefix=f".{name}-vendor-", dir=downloads) as temporary:
        work = Path(temporary)
        source = safe_extract(sdist, work / "sdist")
        environment = {
            **os.environ,
            "CARGO_HOME": str(work / "cargo-home"),
            "PATH": f"{bin_dir}{os.pathsep}{os.environ.get('PATH', '')}",
            "RUSTUP_HOME": str(rustup_home()),
        }
        subprocess.run(
            [
                str(bin_dir / "cargo"),
                "vendor",
                "--locked",
                "--versioned-dirs",
                "--quiet",
                "--manifest-path",
                str(source / str(entry["cargo_manifest"])),
                str(work / "vendor"),
            ],
            check=True,
            env=environment,
            stdout=subprocess.DEVNULL,
        )
        partial = work / output.name
        deterministic_tar(work / "vendor", "vendor", partial)
        if digest(partial) != vendor["sha256"]:
            raise RuntimeWheelError(f"{name}: regenerated cargo vendor tree differs from the lock")
        os.replace(partial, output)
    return output


def fetch_inputs(downloads: Path, wheelhouse: Path) -> None:
    for name, entry in source_entries().items():
        _download(
            str(entry["url"]),
            downloads / str(entry["filename"]),
            str(entry["sha256"]),
        )
        vendor = entry.get("cargo_vendor")
        if isinstance(vendor, dict):
            vendor_path = downloads / str(vendor["filename"])
            if not vendor_path.is_file() or digest(vendor_path) != vendor["sha256"]:
                cargo_vendor(name, entry, downloads)
    for entry in host_entries():
        _download(
            str(entry["url"]),
            wheelhouse / str(entry["filename"]),
            str(entry["sha256"]),
        )
    verify_inputs(downloads, wheelhouse)


def _normalized_archive_name(name: str) -> str | None:
    if not name or "\x00" in name or "\\" in name:
        return None
    while name.startswith("./"):
        name = name[2:]
    if name in {"", "."}:
        return ""
    normalized = name[:-1] if name.endswith("/") else name
    components = normalized.split("/")
    if not normalized or normalized.startswith("/") or any(component in {"", ".", ".."} for component in components):
        return None
    return normalized


def _is_safe_archive_name(name: str) -> bool:
    return _normalized_archive_name(name) not in {None, ""}


def _validated_zip_members(
    archive: zipfile.ZipFile,
    archive_name: str,
    kind: str,
) -> list[zipfile.ZipInfo]:
    members: list[zipfile.ZipInfo] = []
    seen: dict[str, bool] = {}
    total_size = 0
    infos = archive.infolist()
    if len(infos) > MAX_ARCHIVE_MEMBERS:
        raise RuntimeWheelError(f"{archive_name}: too many {kind} entries")
    for info in infos:
        name = info.filename
        normalized = _normalized_archive_name(name)
        if normalized is None:
            raise RuntimeWheelError(f"{archive_name}: unsafe {kind} entry {name!r}")
        if normalized == "":
            if not info.is_dir():
                raise RuntimeWheelError(f"{archive_name}: unsafe {kind} root entry")
            continue
        components = normalized.split("/")
        mode = info.external_attr >> 16
        file_type = stat.S_IFMT(mode)
        if stat.S_ISLNK(mode) or file_type not in {0, stat.S_IFREG, stat.S_IFDIR}:
            raise RuntimeWheelError(f"{archive_name}: special {kind} entry {name!r}")
        if info.flag_bits & 0x1:
            raise RuntimeWheelError(f"{archive_name}: encrypted {kind} entry {name!r}")
        is_directory = info.is_dir()
        if normalized in seen:
            raise RuntimeWheelError(f"{archive_name}: duplicate {kind} entry {normalized!r}")
        ancestors = ["/".join(components[:index]) for index in range(1, len(components))]
        if any(seen.get(ancestor) is False for ancestor in ancestors):
            raise RuntimeWheelError(f"{archive_name}: file/descendant ambiguity {name!r}")
        if not is_directory and any(existing.startswith(f"{normalized}/") for existing in seen):
            raise RuntimeWheelError(f"{archive_name}: file/descendant ambiguity {name!r}")
        if info.file_size > MAX_ARCHIVE_MEMBER_SIZE:
            raise RuntimeWheelError(f"{archive_name}: oversized {kind} entry {name!r}")
        total_size += info.file_size
        if total_size > MAX_ARCHIVE_TOTAL_SIZE:
            raise RuntimeWheelError(f"{archive_name}: expanded {kind} size is excessive")
        seen[normalized] = is_directory
        members.append(info)
    return members


def _validated_tar_members(
    archive: tarfile.TarFile,
    archive_name: str,
) -> list[tarfile.TarInfo]:
    members = archive.getmembers()
    if len(members) > MAX_ARCHIVE_MEMBERS:
        raise RuntimeWheelError(f"{archive_name}: too many source entries")
    seen: dict[str, bool] = {}
    total_size = 0
    for member in members:
        name = member.name
        normalized = _normalized_archive_name(name)
        if normalized is None:
            raise RuntimeWheelError(f"{archive_name}: unsafe source entry {name!r}")
        if normalized == "":
            if not member.isdir():
                raise RuntimeWheelError(f"{archive_name}: unsafe source root entry")
            continue
        components = normalized.split("/")
        if member.issym() or member.islnk() or member.isdev() or not (member.isdir() or member.isfile()):
            raise RuntimeWheelError(f"{archive_name}: special source entry {name!r}")
        is_directory = member.isdir()
        if normalized in seen:
            raise RuntimeWheelError(f"{archive_name}: duplicate source entry {normalized!r}")
        ancestors = ["/".join(components[:index]) for index in range(1, len(components))]
        if any(seen.get(ancestor) is False for ancestor in ancestors):
            raise RuntimeWheelError(f"{archive_name}: file/descendant ambiguity {name!r}")
        if not is_directory and any(existing.startswith(f"{normalized}/") for existing in seen):
            raise RuntimeWheelError(f"{archive_name}: file/descendant ambiguity {name!r}")
        if member.size > MAX_ARCHIVE_MEMBER_SIZE:
            raise RuntimeWheelError(f"{archive_name}: oversized source entry {name!r}")
        total_size += member.size
        if total_size > MAX_ARCHIVE_TOTAL_SIZE:
            raise RuntimeWheelError(f"{archive_name}: expanded source size is excessive")
        seen[normalized] = is_directory
    return members


def _archive_member_bytes(path: Path, member_name: str) -> bytes:
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            members = _validated_zip_members(archive, path.name, "archive")
            matches = [info for info in members if info.filename == member_name]
            if len(matches) != 1 or matches[0].is_dir():
                raise RuntimeWheelError(f"{path.name}: locked member is missing: {member_name}")
            return archive.read(matches[0])
    if tarfile.is_tarfile(path):
        with tarfile.open(path) as archive:
            members = _validated_tar_members(archive, path.name)
            matches = [member for member in members if member.name == member_name]
            if len(matches) != 1 or not matches[0].isfile():
                raise RuntimeWheelError(f"{path.name}: locked member is missing: {member_name}")
            stream = archive.extractfile(matches[0])
            if stream is None:
                raise RuntimeWheelError(f"{path.name}: cannot read locked member: {member_name}")
            return stream.read()
    raise RuntimeWheelError(f"unsupported archive: {path}")


def carried_license_path(entry: dict[str, object], name: str, tool_root: Path = TOOL_ROOT) -> Path:
    """Where the repository carries a licence text that a source archive omits."""
    package = normalize_package(str(entry["package"]))
    if entry.get("kind") == "runtime-source":
        return tool_root / "recipes" / package / name
    return tool_root / "licenses" / package / name


def verify_git_archive_commit(path: Path, commit: str) -> None:
    """A git-archive tarball names its commit in the pax global header."""
    with tarfile.open(path) as archive:
        _validated_tar_members(archive, path.name)
        if archive.pax_headers.get("comment") != commit:
            raise RuntimeWheelError(f"{path.name}: archive is not git commit {commit}")


def verify_source_archive(path: Path, entry: dict[str, object]) -> None:
    verify_file(path, str(entry["sha256"]))
    license_value = entry.get("license")
    if isinstance(license_value, dict):
        members = license_value.get("members")
        assert isinstance(members, dict)
        for name, expected in members.items():
            data = _archive_member_bytes(path, str(name))
            if hashlib.sha256(data).hexdigest() != expected:
                raise RuntimeWheelError(f"{path.name}: license member hash mismatch: {name}")
        carried = license_value.get("carried", {})
        assert isinstance(carried, dict)
        for name, expected in carried.items():
            verify_file(carried_license_path(entry, str(name)), str(expected))
        if not members:
            _validate_archive(path)
    else:
        _validate_archive(path)
    vendor = entry.get("cargo_vendor")
    if isinstance(vendor, dict):
        vendor_path = path.parent / str(vendor["filename"])
        verify_file(vendor_path, str(vendor["sha256"]))
        _validate_archive(vendor_path)
    commit = entry.get("git_commit")
    if isinstance(commit, str):
        verify_git_archive_commit(path, commit)


def _validate_archive(path: Path) -> None:
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            _validated_zip_members(archive, path.name, "archive")
    elif tarfile.is_tarfile(path):
        with tarfile.open(path) as archive:
            _validated_tar_members(archive, path.name)


def _native_artifact_checker():
    module_name = "_anki_miner_runtime_native_artifact_checker"
    module = sys.modules.get(module_name)
    if module is not None:
        return module
    checker_path = ROOT / "scripts/check_native_artifacts.py"
    spec = importlib.util.spec_from_file_location(module_name, checker_path)
    if spec is None or spec.loader is None:
        raise RuntimeWheelError(f"cannot load native artifact checker: {checker_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    try:
        spec.loader.exec_module(module)
    except Exception:
        sys.modules.pop(module_name, None)
        raise
    return module


def inspect_elf(
    data: bytes,
    logical_name: str,
    abi: str,
    *,
    reject_build_paths: bool = True,
    inspect_dynamic: bool = True,
) -> dict[str, object]:
    if reject_build_paths and any(marker in data for marker in BUILD_PATH_MARKERS):
        raise RuntimeWheelError(f"{logical_name}: absolute build path leaked into ELF")
    for signature, label in (
        (b"mimalloc", "mimalloc"),
        (b"/proc/sys/vm/overcommit_memory", "overcommit mutation"),
    ):
        if signature in data:
            raise RuntimeWheelError(f"{logical_name}: forbidden {label} signature")
    checker = _native_artifact_checker()
    try:
        inspection = checker.Inspection({abi}, ())
        metadata = checker.parse_elf(
            data,
            logical_name,
            inspection,
            require_et_dyn=True,
            inspect_dynamic=inspect_dynamic,
        )
    except checker.ArtifactError as error:
        raise RuntimeWheelError(str(error)) from error
    except Exception as error:
        raise RuntimeWheelError(f"{logical_name}: invalid ELF: {error}") from error
    return {
        "path": logical_name,
        "sha256": hashlib.sha256(data).hexdigest(),
        "abi": abi,
        "soname": metadata.soname,
        "needed": list(metadata.needed),
    }


def verify_python_target_archive(path: Path, abi: str, expected_sha256: str) -> dict[str, object]:
    verify_file(path, expected_sha256)
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile as error:
        raise RuntimeWheelError(f"invalid Python target archive: {path}") from error
    with archive:
        members = _validated_zip_members(archive, path.name, "target archive")
        native_members = [info for info in members if not info.is_dir() and Path(info.filename).name.endswith(".so")]
        libpython_path = f"jniLibs/{abi}/libpython3.12.so"
        if [info.filename for info in native_members].count(libpython_path) != 1:
            raise RuntimeWheelError(f"{path.name}: missing {libpython_path}")
        inspected: list[dict[str, object]] = []
        for info in native_members:
            if abi not in Path(info.filename).parts:
                raise RuntimeWheelError(
                    f"{path.name}: native payload is outside the locked ABI: {info.filename}",
                )
            inspected.append(
                inspect_elf(
                    archive.read(info),
                    info.filename,
                    abi,
                    reject_build_paths=False,
                    inspect_dynamic=info.filename == libpython_path,
                ),
            )
        libpython = next(item for item in inspected if item["path"] == libpython_path)
        if set(libpython["needed"]) != {"libc.so", "libdl.so", "libm.so"}:
            raise RuntimeWheelError(f"{path.name}: unexpected libpython dependencies")
    return {
        "filename": path.name,
        "sha256": expected_sha256,
        "abi": abi,
        "native_count": len(inspected),
        "libpython": libpython,
    }


def verify_locked_sources(downloads: Path) -> dict[str, dict[str, object]]:
    entries = source_entries()
    for entry in entries.values():
        path = downloads / str(entry["filename"])
        if entry["kind"] == "prebuilt-wheel":
            verify_file(path, str(entry["sha256"]))
        else:
            verify_source_archive(path, entry)
    return {
        abi: verify_python_target_archive(
            downloads / str(entries[f"python-{abi}"]["filename"]),
            abi,
            str(entries[f"python-{abi}"]["sha256"]),
        )
        for abi in ABIS
    }


def verify_host_wheels(wheelhouse: Path) -> None:
    expected = {str(entry["filename"]): str(entry["sha256"]) for entry in host_entries()}
    actual = {path.name for path in wheelhouse.glob("*.whl")}
    if actual != set(expected):
        raise RuntimeWheelError(
            f"host wheel set differs: expected={sorted(expected)}, actual={sorted(actual)}",
        )
    for filename, sha256 in expected.items():
        verify_file(wheelhouse / filename, sha256)


def verify_inputs(downloads: Path, wheelhouse: Path) -> None:
    verify_locked_sources(downloads)
    verify_host_wheels(wheelhouse)


def safe_extract(archive_path: Path, destination: Path) -> Path:
    if destination.exists():
        raise RuntimeWheelError(f"extraction destination already exists: {destination}")
    destination.mkdir(parents=True)
    roots: set[str] = set()
    dot_root = False
    if zipfile.is_zipfile(archive_path):
        with zipfile.ZipFile(archive_path) as archive:
            members = _validated_zip_members(archive, archive_path.name, "source")
            for info in members:
                dot_root = dot_root or info.filename.startswith("./")
                normalized = _normalized_archive_name(info.filename)
                if normalized in {None, ""}:
                    continue
                name = normalized
                roots.add(name.split("/", 1)[0])
                target = destination.joinpath(*name.split("/"))
                if info.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(info) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
                mode = info.external_attr >> 16
                target.chmod(0o755 if mode & stat.S_IXUSR else 0o644)
    elif tarfile.is_tarfile(archive_path):
        with tarfile.open(archive_path) as archive:
            members = _validated_tar_members(archive, archive_path.name)
            for member in members:
                dot_root = dot_root or member.name.startswith("./")
                normalized = _normalized_archive_name(member.name)
                if normalized in {None, ""}:
                    continue
                name = normalized
                roots.add(name.split("/", 1)[0])
                target = destination.joinpath(*name.split("/"))
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                source = archive.extractfile(member)
                if source is None:
                    raise RuntimeWheelError(f"cannot extract source member: {member.name}")
                with source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
                target.chmod(0o755 if member.mode & stat.S_IXUSR else 0o644)
    else:
        raise RuntimeWheelError(f"unsupported archive: {archive_path}")
    if dot_root:
        return destination
    if len(roots) != 1:
        raise RuntimeWheelError(f"source archive must contain one root: {archive_path.name}")
    return destination / next(iter(roots))


def normalize_tree_timestamps(root: Path) -> None:
    """Give generated build-system files and their inputs one reproducible timestamp."""
    timestamp = int(SOURCE_DATE_EPOCH)
    paths = [root, *root.rglob("*")]
    for path in paths:
        if path.is_symlink():
            raise RuntimeWheelError(f"staged tree contains a symlink: {path}")
        os.utime(path, (timestamp, timestamp), follow_symlinks=False)


def _replace(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise RuntimeWheelError(f"staged source patch anchor mismatch: {path}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def patch_builder(chaquopy: Path) -> None:
    builder = chaquopy / "server/pypi/build-wheel.py"
    _replace(builder, "import pypi_simple\n", "")
    _replace(builder, 'pip_version = "23.2.1"', 'pip_version = "25.1.1"')
    _replace(
        builder,
        'os.environ["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"',
        "for name in list(os.environ):\n"
        '            if name.startswith("PIP_") or name.casefold().endswith("_proxy"):\n'
        "                os.environ.pop(name, None)\n"
        '        os.environ["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"\n'
        '        os.environ["PIP_CONFIG_FILE"] = os.devnull',
    )
    _replace(
        builder,
        'run(f"{bootstrap_env}/bin/pip install pip=={pip_version}")',
        'run(f"{bootstrap_env}/bin/pip install --no-index --only-binary=:all: "\n'
        "    f\"--find-links={os.environ['ANKI_MINER_HOST_WHEELHOUSE']} \"\n"
        '    f"pip=={pip_version}")',
    )
    _replace(
        builder,
        'f"install " + " ".join(shlex.quote(req) for req in requirements))',
        'f"install --no-index --only-binary=:all: "\n'
        "                f\"--find-links={os.environ['ANKI_MINER_HOST_WHEELHOUSE']} \" +\n"
        '                " ".join(shlex.quote(req) for req in requirements))',
    )
    _replace(
        builder,
        'run(f"python{python_ver} -m venv --without-pip {self.build_env}")',
        'build_python = (os.environ["ANKI_MINER_CHAQUOPY_BUILD_PYTHON"]\n'
        '                        if python_ver == "3.12" else f"python{python_ver}")\n'
        '        run(f"{shlex.quote(build_python)} -m venv --without-pip {self.build_env}")',
    )
    _replace(
        builder,
        'run(f"python{python_ver} -m venv {bootstrap_env}")',
        'build_python = (os.environ["ANKI_MINER_CHAQUOPY_BUILD_PYTHON"]\n'
        '                        if python_ver == "3.12" else f"python{python_ver}")\n'
        '        run(f"{shlex.quote(build_python)} -m venv {bootstrap_env}")',
    )
    source = builder.read_text(encoding="utf-8")
    for method, next_method, parameters in (
        ("download_git", "download_pypi", "self, source"),
        ("download_pypi", "download_url", "self"),
        ("download_url", "apply_patches", "self, url"),
    ):
        first = source.index(f"    def {method}(")
        last = source.index(f"    def {next_method}(", first)
        replacement = (
            f"    def {method}({parameters}):\n"
            '        raise CommandError("network source discovery is disabled")\n\n'
        )
        source = source[:first] + replacement + source[last:]
    builder.write_text(source, encoding="utf-8")

    android_env = chaquopy / "target/android-env.sh"
    _replace(android_env, "ndk_version=27.3.13750724", f"ndk_version={NDK_VERSION}")
    _replace(
        android_env,
        "if ! [ -e $ndk ]; then\n"
        '    log "Installing NDK - this may take several minutes"\n'
        '    yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;$ndk_version"\n'
        "fi",
        'if ! [ -e "$ndk" ]; then\n' '    fail "locked NDK is missing: $ndk"\n' "fi",
    )
    _replace(
        android_env,
        'export CFLAGS="-D__BIONIC_NO_PAGE_SIZE_MACRO"',
        'case "${ANKI_MINER_RUNTIME_STAGE_ROOT:-}" in\n'
        '    /*[[:space:]]*) fail "runtime stage root contains whitespace" ;;\n'
        "    /*) ;;\n"
        '    *) fail "runtime stage root is not absolute" ;;\n'
        "esac\n"
        "runtime_source_prefix=/anki-miner-runtime\n"
        "export ZERO_AR_DATE=1\n"
        'export CFLAGS="-D__BIONIC_NO_PAGE_SIZE_MACRO '
        "-ffile-prefix-map=$ANKI_MINER_RUNTIME_STAGE_ROOT=$runtime_source_prefix "
        "-fdebug-prefix-map=$ANKI_MINER_RUNTIME_STAGE_ROOT=$runtime_source_prefix "
        '-fmacro-prefix-map=$ANKI_MINER_RUNTIME_STAGE_ROOT=$runtime_source_prefix"',
    )


CARGO_SOURCE_REPLACEMENT = """\
[source.crates-io]
replace-with = "vendored-sources"

[source.vendored-sources]
directory = "vendor"
"""


def patch_builder_rust(chaquopy: Path) -> None:
    """Make Chaquopy's Rust support offline, pinned, 16 KiB aligned and path-neutral."""
    builder = chaquopy / "server/pypi/build-wheel.py"
    _replace(
        builder,
        '        run(f"rustup target add {tool_prefix}")\n',
        '        sysroot = run("rustc --print sysroot", capture_output=True).stdout.strip()\n'
        '        if not isdir(f"{sysroot}/lib/rustlib/{tool_prefix}/lib"):\n'
        '            raise CommandError(f"Rust std for {tool_prefix} is not installed")\n'
        # PyO3 links abi3 extensions for Android with -lpython3. Chaquopy's
        # libpython3.12.so has no SONAME, so a symlink would leak libpython3.so
        # into DT_NEEDED; a linker script resolves it to the real library.
        '        libpython3 = f"{self.host_env}/chaquopy/lib/libpython3.so"\n'
        "        if self.needs_python and not os.path.lexists(libpython3):\n"
        '            with open(libpython3, "w") as script:\n'
        '                script.write(f"INPUT(-lpython{self.python})\\n")\n'
        # Version-specific (non-abi3) PyO3 crates cannot cross-compile without
        # the target's sysconfigdata, which Chaquopy's 3.12 target lacks.
        # Synthesise the keys PyO3 and maturin read.
        "        if self.needs_python:\n"
        '            cross_dir = ensure_dir(f"{self.build_dir}/pyo3-cross")\n'
        '            nodot = self.python.replace(".", "")\n'
        "            build_time_vars = {\n"
        '                "ABIFLAGS": "", "EXT_SUFFIX": f".cpython-{nodot}-{tool_prefix}.so",\n'
        '                "LDVERSION": self.python, "LIBDIR": f"{self.host_env}/chaquopy/lib",\n'
        '                "MULTIARCH": tool_prefix, "Py_DEBUG": 0, "Py_ENABLE_SHARED": 1,\n'
        '                "Py_GIL_DISABLED": 0, "SIZEOF_VOID_P": 8,\n'
        '                "SOABI": f"cpython-{nodot}-{tool_prefix}", "VERSION": self.python,\n'
        "            }\n"
        '            with open(f"{cross_dir}/_sysconfigdata__android_{tool_prefix}.py", "w") as f:\n'
        '                f.write(f"build_time_vars = {build_time_vars!r}\\n")\n'
        '            env["PYO3_CROSS_LIB_DIR"] = cross_dir\n',
    )
    # maturin's CycloneDX SBOM lists the statically linked crates (kept for
    # attribution) under absolute paths; `wheel pack` regenerates RECORD.
    _replace(
        builder,
        '        info_dir = assert_isdir(f"{tmp_dir}/{self.name_version}.dist-info")\n',
        '        info_dir = assert_isdir(f"{tmp_dir}/{self.name_version}.dist-info")\n'
        '        sbom_dir = f"{info_dir}/sboms"\n'
        "        if exists(sbom_dir):\n"
        '            stage_root = os.environ["ANKI_MINER_RUNTIME_STAGE_ROOT"]\n'
        "            for name in sorted(os.listdir(sbom_dir)):\n"
        "                sbom = Path(sbom_dir, name)\n"
        '                sbom.write_text(sbom.read_text().replace(stage_root, "/anki-miner-runtime"))\n',
    )
    _replace(
        builder,
        '"RUSTFLAGS": f"-C linker={env[\'CC\']} -L native={self.host_env}/chaquopy/lib",',
        '"RUSTFLAGS": (f"-C linker={env[\'CC\']} -L native={self.host_env}/chaquopy/lib "\n'
        '                          + os.environ["ANKI_MINER_RUSTFLAGS"]),',
    )


def patch_builder_cmake(chaquopy: Path) -> None:
    """Point CMake at the NDK binutils.

    Chaquopy's toolchain file names no strip, ar or ranlib, so CMake falls back
    to the host's /usr/bin/strip, which cannot read Android ELF.
    """
    _replace(
        chaquopy / "server/pypi/build-wheel.py",
        "                set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)\n",
        "                set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)\n"
        "\n"
        "                # Anki Miner: NDK binutils instead of the host's.\n"
        "                set(CMAKE_AR {env['AR']} CACHE FILEPATH \"\")\n"
        "                set(CMAKE_NM {env['NM']} CACHE FILEPATH \"\")\n"
        "                set(CMAKE_RANLIB {env['RANLIB']} CACHE FILEPATH \"\")\n"
        "                set(CMAKE_READELF {env['READELF']} CACHE FILEPATH \"\")\n"
        "                set(CMAKE_STRIP {env['STRIP']} CACHE FILEPATH \"\")\n",
    )


def stage_cargo_vendor(name: str, entry: dict[str, object], downloads: Path, source: Path, scratch: Path) -> None:
    """Unpack a locked vendor tree beside the crate and point cargo at it."""
    vendor = entry["cargo_vendor"]
    assert isinstance(vendor, dict)
    crate_root = (source / str(entry["cargo_manifest"])).parent
    vendored = safe_extract(downloads / str(vendor["filename"]), scratch / f"vendor-{name}")
    if vendored.name != "vendor" or (crate_root / "vendor").exists():
        raise RuntimeWheelError(f"{name}: cargo vendor tree cannot be staged")
    shutil.copytree(vendored, crate_root / "vendor")
    config = crate_root / ".cargo/config.toml"
    config.parent.mkdir(exist_ok=True)
    existing = config.read_text(encoding="utf-8") if config.exists() else ""
    if "[source." in existing:
        raise RuntimeWheelError(f"{name}: sdist already configures cargo sources")
    config.write_text(existing + ("\n" if existing else "") + CARGO_SOURCE_REPLACEMENT, encoding="utf-8")


def recipe_path_entries(
    tool_root: Path = TOOL_ROOT,
    repo_root: Path = ROOT,
) -> list[tuple[str, Path]]:
    entries = [(f"tools/runtime-wheels/{name}", tool_root / name) for name in RECIPE_FILES]
    recipe_tree = tool_root / "recipes"
    if not recipe_tree.is_dir():
        raise RuntimeWheelError(f"recipe tree is missing: {recipe_tree}")
    for tree in (recipe_tree, tool_root / "licenses"):
        entries.extend(
            (
                f"tools/runtime-wheels/{path.relative_to(tool_root).as_posix()}",
                path,
            )
            for path in tree.rglob("*")
            if path.is_file()
        )
    entries.extend((name, repo_root / name) for name in RECIPE_REPO_FILES)
    logical_names = [logical for logical, _ in entries]
    if len(logical_names) != len(set(logical_names)):
        raise RuntimeWheelError("recipe input inventory has duplicate paths")
    for _, path in entries:
        if path.is_symlink() or not path.is_file():
            raise RuntimeWheelError(f"recipe input must be a regular file: {path}")
    return sorted(entries)


def _parameters() -> dict[str, object]:
    return {
        "abis": ABIS,
        "api_level": API_LEVEL,
        "common": COMMON_SPECS,
        "native": NATIVE_SPECS,
        "ndk": NDK_VERSION,
        "python_target": PYTHON_TARGET,
        "target_build_python": TARGET_BUILD_PYTHON_VERSION,
        "reproducible_env": REPRODUCIBLE_ENV,
        "rust": RUST_TOOLCHAIN,
        "families": FAMILIES,
        "family_packages": FAMILY_PACKAGES,
        "repacks": REPACKS,
        "sdist_wheels": SDIST_WHEELS,
        "native_roots": NATIVE_ROOTS,
        "pinned_member_files": PINNED_MEMBER_FILES,
        "external_runtime_libs": sorted(EXTERNAL_RUNTIME_LIBS),
    }


def recipe_inventory(
    tool_root: Path = TOOL_ROOT,
    repo_root: Path = ROOT,
) -> list[dict[str, object]]:
    result: list[dict[str, object]] = []
    for logical, path in recipe_path_entries(tool_root, repo_root):
        data = path.read_bytes()
        result.append(
            {
                "path": logical,
                "mode": stat.S_IMODE(path.stat().st_mode),
                "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            },
        )
    data = canonical_json(_parameters())
    result.append(
        {
            "path": "@parameters.json",
            "mode": 0o644,
            "size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        },
    )
    return result


def source_recipe_key(
    tool_root: Path = TOOL_ROOT,
    repo_root: Path = ROOT,
) -> str:
    value = hashlib.sha256()
    paths = recipe_path_entries(tool_root, repo_root)
    for entry, (_, path) in zip(recipe_inventory(tool_root, repo_root)[:-1], paths, strict=False):
        data = path.read_bytes()
        header = canonical_json(
            {"path": entry["path"], "mode": entry["mode"], "size": entry["size"]},
        )
        value.update(len(header).to_bytes(8, "big"))
        value.update(header)
        value.update(len(data).to_bytes(8, "big"))
        value.update(data)
    parameters = canonical_json(_parameters())
    header = canonical_json(
        {"path": "@parameters.json", "mode": 0o644, "size": len(parameters)},
    )
    value.update(len(header).to_bytes(8, "big"))
    value.update(header)
    value.update(len(parameters).to_bytes(8, "big"))
    value.update(parameters)
    return value.hexdigest()


def _tool_version(name: str, command: list[str], marker: str) -> str:
    executable = shutil.which(command[0])
    if executable is None:
        raise RuntimeWheelError(f"required builder tool is missing: {command[0]}")
    result = subprocess.run(
        [executable, *command[1:]],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        env={**os.environ, "LC_ALL": "C", "LANG": "C", "TZ": "UTC"},
    )
    lines = [" ".join(line.split()) for line in result.stdout.splitlines() if line.strip()]
    if not lines or marker not in lines[0].casefold():
        raise RuntimeWheelError(f"cannot identify {name} version")
    return lines[0]


def target_python_executable() -> str:
    value = os.environ.get(TARGET_BUILD_PYTHON_ENV)
    if not value or value != value.strip():
        raise RuntimeWheelError(f"{TARGET_BUILD_PYTHON_ENV} must name an absolute executable")
    path = Path(value)
    if not path.is_absolute() or not path.is_file() or not os.access(path, os.X_OK):
        raise RuntimeWheelError(f"{TARGET_BUILD_PYTHON_ENV} must name an absolute executable")
    return str(path.resolve(strict=True))


def _interpreter_identity(executable: str) -> dict[str, str]:
    probe = subprocess.run(
        [
            executable,
            "-c",
            "import json,platform;print(json.dumps({"
            "'implementation':platform.python_implementation().casefold(),"
            "'version':platform.python_version()},sort_keys=True))",
        ],
        check=True,
        capture_output=True,
        text=True,
        env={**os.environ, "LC_ALL": "C", "LANG": "C", "TZ": "UTC"},
    )
    try:
        details = json.loads(probe.stdout)
    except json.JSONDecodeError as error:
        raise RuntimeWheelError(f"cannot identify Python interpreter: {executable}") from error
    if not isinstance(details, dict):
        raise RuntimeWheelError(f"cannot identify Python interpreter: {executable}")
    return {
        "implementation": str(details.get("implementation")),
        "version": str(details.get("version")),
        "executable_sha256": digest(Path(executable)),
    }


def _ndk_identity() -> dict[str, str]:
    android_home = os.environ.get("ANDROID_HOME")
    if not android_home:
        raise RuntimeWheelError("ANDROID_HOME is required")
    ndk = Path(android_home) / "ndk" / NDK_VERSION
    source_properties = ndk / "source.properties"
    if not source_properties.is_file():
        raise RuntimeWheelError(f"locked NDK is missing: {ndk}")
    prebuilt = ndk / "toolchains/llvm/prebuilt/linux-x86_64"
    clang = prebuilt / "bin/clang"
    if not clang.is_file():
        raise RuntimeWheelError(f"locked NDK clang is missing: {clang}")
    return {
        "version": NDK_VERSION,
        "source_properties_sha256": digest(source_properties),
        "clang_sha256": digest(clang),
        "clang_version": _tool_version("NDK clang", [str(clang), "--version"], "clang"),
    }


def _validate_interpreter_identity(
    value: object,
    *,
    label: str,
    version_pattern: str,
) -> dict[str, object]:
    identity = require_exact_keys(
        value,
        {"implementation", "version", "executable_sha256"},
        f"{label} interpreter identity",
    )
    version = identity.get("version")
    if (
        identity.get("implementation") != "cpython"
        or not isinstance(version, str)
        or re.fullmatch(version_pattern, version) is None
        or not isinstance(identity.get("executable_sha256"), str)
        or KEY_PATTERN.fullmatch(str(identity["executable_sha256"])) is None
    ):
        raise RuntimeWheelError(f"invalid {label} interpreter identity")
    return identity


def validate_builder_identity(value: object) -> dict[str, object]:
    identity = require_exact_keys(
        value,
        {"schema", "interpreters", "host", "android", "rust", "tools"},
        "builder identity",
    )
    if identity.get("schema") != BUILDER_IDENTITY_SCHEMA:
        raise RuntimeWheelError("invalid builder identity schema")
    validate_rust_identity(identity.get("rust"))
    interpreters = require_exact_keys(
        identity.get("interpreters"),
        {"outer", "target"},
        "builder interpreters",
    )
    _validate_interpreter_identity(
        interpreters.get("outer"),
        label="outer",
        version_pattern=r"3\.13\.\d+",
    )
    _validate_interpreter_identity(
        interpreters.get("target"),
        label="target",
        version_pattern=re.escape(TARGET_BUILD_PYTHON_VERSION),
    )
    host = require_exact_keys(identity.get("host"), {"os", "machine", "libc", "zlib"}, "host")
    libc = require_exact_keys(host.get("libc"), {"name", "version"}, "host libc")
    zlib_value = require_exact_keys(host.get("zlib"), {"compiled", "runtime"}, "host zlib")
    if (
        host.get("os") != "linux"
        or host.get("machine") != "x86_64"
        or libc.get("name") != "glibc"
        or not isinstance(libc.get("version"), str)
        or not libc.get("version")
        or zlib_value.get("compiled") != zlib_value.get("runtime")
    ):
        raise RuntimeWheelError("runtime wheels require Linux x86_64 with identified glibc/zlib")
    android = require_exact_keys(
        identity.get("android"),
        {"version", "source_properties_sha256", "clang_sha256", "clang_version"},
        "Android builder identity",
    )
    if (
        android.get("version") != NDK_VERSION
        or not all(
            isinstance(android.get(name), str) and android.get(name)
            for name in ("source_properties_sha256", "clang_sha256", "clang_version")
        )
        or KEY_PATTERN.fullmatch(str(android["source_properties_sha256"])) is None
        or KEY_PATTERN.fullmatch(str(android["clang_sha256"])) is None
    ):
        raise RuntimeWheelError("invalid locked NDK identity")
    tools = identity.get("tools")
    expected_tools = {
        "bash",
        "coreutils",
        "findutils",
        "git",
        "grep",
        "make",
        "patch",
        "pkg-config",
        "sed",
        "tar",
        "unzip",
    }
    if (
        not isinstance(tools, dict)
        or set(tools) != expected_tools
        or not all(isinstance(item, str) and item for item in tools.values())
    ):
        raise RuntimeWheelError("incomplete builder tool identity")
    return identity


def builder_identity() -> dict[str, object]:
    machine = platform.machine().strip().casefold().replace("-", "_")
    machine = {"amd64": "x86_64", "x64": "x86_64"}.get(machine, machine)
    libc_name, libc_version = platform.libc_ver()
    outer = str(Path(sys.executable).resolve(strict=True))
    identity: dict[str, object] = {
        "schema": BUILDER_IDENTITY_SCHEMA,
        "interpreters": {
            "outer": _interpreter_identity(outer),
            "target": _interpreter_identity(target_python_executable()),
        },
        "host": {
            "os": platform.system().strip().casefold(),
            "machine": machine,
            "libc": {"name": libc_name.strip().casefold(), "version": libc_version.strip()},
            "zlib": {"compiled": zlib.ZLIB_VERSION, "runtime": zlib.ZLIB_RUNTIME_VERSION},
        },
        "android": _ndk_identity(),
        "rust": rust_identity(),
        "tools": {
            "bash": _tool_version("bash", ["bash", "--version"], "bash"),
            "coreutils": _tool_version("coreutils", ["cp", "--version"], "coreutils"),
            "findutils": _tool_version("findutils", ["find", "--version"], "find"),
            "git": _tool_version("git", ["git", "--version"], "git version"),
            "grep": _tool_version("grep", ["grep", "--version"], "grep"),
            "make": _tool_version("make", ["make", "--version"], "make"),
            "patch": _tool_version("patch", ["patch", "--version"], "patch"),
            "pkg-config": _tool_version("pkg-config", ["pkg-config", "--version"], ""),
            "sed": _tool_version("sed", ["sed", "--version"], "sed"),
            "tar": _tool_version("tar", ["tar", "--version"], "tar"),
            "unzip": _tool_version("unzip", ["unzip", "-v"], "unzip"),
        },
    }
    return validate_builder_identity(identity)


def build_key(recipe: str | None = None, identity: object | None = None) -> str:
    recipe_value = recipe or source_recipe_key()
    if KEY_PATTERN.fullmatch(recipe_value) is None:
        raise RuntimeWheelError("invalid recipe key")
    checked = validate_builder_identity(identity or builder_identity())
    return hashlib.sha256(recipe_value.encode("ascii") + b"\n" + canonical_json(checked)).hexdigest()


def enforce_reproducible_environment() -> None:
    mismatches = {
        name: (os.environ.get(name), expected)
        for name, expected in REPRODUCIBLE_ENV.items()
        if os.environ.get(name) != expected
    }
    current_umask = os.umask(0o022)
    os.umask(current_umask)
    if current_umask != 0o022:
        mismatches["umask"] = (oct(current_umask), oct(0o022))
    if mismatches:
        details = ", ".join(
            f"{name}={actual!r} (expected {expected!r})" for name, (actual, expected) in sorted(mismatches.items())
        )
        raise RuntimeWheelError(f"non-reproducible builder environment: {details}")


def validate_expected_keys(
    expected_recipe: str,
    expected_build: str,
) -> tuple[str, str, dict[str, object]]:
    if KEY_PATTERN.fullmatch(expected_recipe) is None or KEY_PATTERN.fullmatch(expected_build) is None:
        raise RuntimeWheelError("expected keys must be lowercase SHA-256 values")
    recipe = source_recipe_key()
    identity = builder_identity()
    build = build_key(recipe, identity)
    if recipe != expected_recipe:
        raise RuntimeWheelError("stale runtime wheel recipe key")
    if build != expected_build:
        raise RuntimeWheelError("stale runtime wheel build key")
    return recipe, build, identity


def validate_recipes(chaquopy_root: Path) -> list[str]:
    try:
        from copy import deepcopy

        import jsonschema
        import yaml
        from jinja2 import StrictUndefined, Template, TemplateError
    except ImportError as error:
        raise RuntimeWheelError("Jinja2, jsonschema and PyYAML are required") from error
    pypi = chaquopy_root.resolve(strict=True) / "server/pypi"
    schema_path = pypi / "meta-schema.yaml"
    try:
        schema = yaml.safe_load(schema_path.read_text(encoding="utf-8"))
        validator_class = jsonschema.Draft4Validator
        validator_class.check_schema(schema)
    except Exception as error:
        raise RuntimeWheelError(f"invalid Chaquopy recipe schema: {schema_path}") from error

    def with_defaults(base):
        def set_defaults(validator, properties, instance, schema_value):
            for name, subschema in properties.items():
                if "default" in subschema:
                    instance.setdefault(name, deepcopy(subschema["default"]))
            yield from base.VALIDATORS["properties"](
                validator,
                properties,
                instance,
                schema_value,
            )

        return jsonschema.validators.extend(base, {"properties": set_defaults})

    validated: list[str] = []
    for package, spec in sorted(NATIVE_SPECS.items()):
        recipe_name = normalize_package(package)
        meta_path = pypi / "packages" / recipe_name / "meta.yaml"
        try:
            rendered = Template(
                meta_path.read_text(encoding="utf-8"),
                undefined=StrictUndefined,
            ).render(PY_VER="3.12")
            metadata = yaml.safe_load(rendered)
            with_defaults(validator_class)(schema).validate(metadata)
        except (OSError, TemplateError, yaml.YAMLError, jsonschema.ValidationError) as error:
            raise RuntimeWheelError(f"invalid staged recipe: {meta_path}") from error
        if (
            normalize_package(str(metadata["package"]["name"])) != package
            or str(metadata["package"]["version"]) != spec["version"]
            or metadata.get("source", {}).get("path") != f"../../sources/{spec['source']}"
        ):
            raise RuntimeWheelError(f"staged recipe contract mismatch: {package}")
        validated.append(package)
    return validated


def _stage_manifest_path(stage_root: Path) -> Path:
    return stage_root / "manifest.json"


def stage(
    downloads: Path,
    wheelhouse: Path,
    build_root: Path,
    stage_id: str,
    expected_recipe: str,
    expected_build: str,
) -> Path:
    enforce_reproducible_environment()
    if STAGE_ID_PATTERN.fullmatch(stage_id) is None:
        raise RuntimeWheelError(f"invalid runtime stage identifier: {stage_id}")
    recipe, build, identity = validate_expected_keys(expected_recipe, expected_build)
    verify_inputs(downloads, wheelhouse)
    entries = source_entries()
    target = build_root / f"runtime-{build}-{stage_id}"
    if target.exists():
        raise RuntimeWheelError(f"immutable runtime stage already exists: {target}")
    temporary = build_root / f".{target.name}.staging"
    if temporary.exists():
        shutil.rmtree(temporary)
    temporary.mkdir(parents=True)
    try:
        chaquopy = safe_extract(
            downloads / str(entries["chaquopy"]["filename"]),
            temporary / "chaquopy",
        )
        patchelf = safe_extract(
            downloads / str(entries["patchelf"]["filename"]),
            temporary / "patchelf",
        )
        source_dir = chaquopy / "server/pypi/sources"
        source_dir.mkdir(parents=True, exist_ok=True)
        for name, entry in sorted(entries.items()):
            if entry["kind"] != "runtime-source":
                continue
            extracted = safe_extract(
                downloads / str(entry["filename"]),
                temporary / f"source-{name}",
            )
            shutil.copytree(extracted, source_dir / name)
            if "cargo_vendor" in entry:
                stage_cargo_vendor(name, entry, downloads, source_dir / name, temporary)
        packages = chaquopy / "server/pypi/packages"
        for recipe_dir in sorted((TOOL_ROOT / "recipes").iterdir()):
            if not recipe_dir.is_dir():
                continue
            destination = packages / recipe_dir.name
            if destination.exists():
                shutil.rmtree(destination)
            shutil.copytree(recipe_dir, destination)
        target_dir = chaquopy / f"maven/com/chaquo/python/target/{PYTHON_TARGET}"
        target_dir.mkdir(parents=True, exist_ok=True)
        for abi in ABIS:
            entry = entries[f"python-{abi}"]
            shutil.copy2(downloads / str(entry["filename"]), target_dir)
        patch_builder(chaquopy)
        patch_builder_rust(chaquopy)
        patch_builder_cmake(chaquopy)
        normalize_tree_timestamps(chaquopy)
        normalize_tree_timestamps(patchelf)
        patchelf_candidates = [
            path for path in patchelf.rglob("patchelf") if path.is_file() and os.access(path, os.X_OK)
        ]
        if len(patchelf_candidates) != 1:
            raise RuntimeWheelError("staged patchelf archive has no unique executable")
        manifest = {
            "schema": STAGE_SCHEMA,
            "stage_id": stage_id,
            "recipe_key": recipe,
            "build_key": build,
            "builder_identity": identity,
            "recipe_inventory": recipe_inventory(),
            "api_level": API_LEVEL,
            "ndk": NDK_VERSION,
            "python_target": PYTHON_TARGET,
            "source_hashes": {name: entry["sha256"] for name, entry in entries.items()},
            "host_wheels": {str(entry["filename"]): entry["sha256"] for entry in host_entries()},
        }
        (_stage_manifest_path(temporary)).write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary, target)
    except Exception:
        if temporary.exists():
            shutil.rmtree(temporary)
        raise
    validate_stage(target, recipe, build, require_dist=False)
    return target


def validate_stage(
    stage_root: Path,
    expected_recipe: str,
    expected_build: str,
    *,
    require_dist: bool,
) -> tuple[dict[str, object], Path]:
    enforce_reproducible_environment()
    recipe, build, identity = validate_expected_keys(expected_recipe, expected_build)
    stage_root = stage_root.resolve(strict=True)
    document = load_json(_stage_manifest_path(stage_root), STAGE_SCHEMA)
    require_exact_keys(
        document,
        {
            "schema",
            "stage_id",
            "recipe_key",
            "build_key",
            "builder_identity",
            "recipe_inventory",
            "api_level",
            "ndk",
            "python_target",
            "source_hashes",
            "host_wheels",
        },
        "runtime stage manifest",
    )
    stage_id = document.get("stage_id")
    if (
        not isinstance(stage_id, str)
        or STAGE_ID_PATTERN.fullmatch(stage_id) is None
        or stage_root.name != f"runtime-{build}-{stage_id}"
    ):
        raise RuntimeWheelError("runtime stage identity mismatch")
    if (
        document.get("recipe_key") != recipe
        or document.get("build_key") != build
        or document.get("builder_identity") != identity
        or document.get("recipe_inventory") != recipe_inventory()
        or document.get("api_level") != API_LEVEL
        or document.get("ndk") != NDK_VERSION
        or document.get("python_target") != PYTHON_TARGET
    ):
        raise RuntimeWheelError("runtime stage differs from active locked inputs")
    if document.get("source_hashes") != {name: entry["sha256"] for name, entry in source_entries().items()}:
        raise RuntimeWheelError("runtime stage source lock differs")
    if document.get("host_wheels") != {str(entry["filename"]): entry["sha256"] for entry in host_entries()}:
        raise RuntimeWheelError("runtime stage host-wheel lock differs")
    roots = [path for path in (stage_root / "chaquopy").iterdir() if path.is_dir()]
    if len(roots) != 1:
        raise RuntimeWheelError("runtime stage has no unique Chaquopy source root")
    dist = roots[0] / "server/pypi/dist"
    if require_dist and not dist.is_dir():
        raise RuntimeWheelError(f"runtime stage has no wheel output: {dist}")
    return document, dist


def _wheel_message(
    archive: zipfile.ZipFile,
    dist_info: str,
    filename: str,
) -> object:
    path = f"{dist_info}/{filename}"
    try:
        data = archive.read(path)
    except KeyError as error:
        raise RuntimeWheelError(f"wheel metadata is missing: {path}") from error
    try:
        return BytesParser(policy=compat32).parsebytes(data)
    except Exception as error:
        raise RuntimeWheelError(f"invalid wheel metadata: {path}") from error


def _validate_record(
    archive: zipfile.ZipFile,
    files: dict[str, bytes],
    dist_info: str,
) -> None:
    record_name = f"{dist_info}/RECORD"
    if record_name not in files:
        raise RuntimeWheelError("wheel RECORD is missing")
    try:
        rows = list(csv.reader(io.StringIO(files[record_name].decode("utf-8"))))
    except (UnicodeError, csv.Error) as error:
        raise RuntimeWheelError("wheel RECORD is invalid") from error
    recorded: set[str] = set()
    for row in rows:
        if len(row) != 3 or not _is_safe_archive_name(row[0]) or row[0] in recorded:
            raise RuntimeWheelError("wheel RECORD contains an invalid row")
        name, hash_value, size_value = row
        if name not in files:
            raise RuntimeWheelError(f"wheel RECORD names a missing member: {name}")
        recorded.add(name)
        if name == record_name:
            if hash_value or size_value:
                raise RuntimeWheelError("wheel RECORD must not hash itself")
            continue
        if not hash_value.startswith("sha256="):
            raise RuntimeWheelError(f"wheel RECORD uses an unsafe hash: {name}")
        expected = base64.urlsafe_b64encode(hashlib.sha256(files[name]).digest()).rstrip(b"=").decode()
        if hash_value != f"sha256={expected}" or size_value != str(len(files[name])):
            raise RuntimeWheelError(f"wheel RECORD mismatch: {name}")
    if recorded != set(files):
        raise RuntimeWheelError("wheel RECORD does not cover every file")


def _requirement_name(value: str) -> tuple[str, bool]:
    match = re.match(r"^\s*([A-Za-z0-9][A-Za-z0-9._-]*)", value)
    if match is None:
        raise RuntimeWheelError(f"unsupported wheel dependency: {value!r}")
    marker = value.split(";", 1)[1].casefold() if ";" in value else ""
    return normalize_package(match.group(1)), bool(re.search(r"\bextra\b", marker))


def _native_identity(path: Path) -> tuple[str, str]:
    match = NATIVE_WHEEL_NAME.fullmatch(path.name)
    if match is None:
        raise RuntimeWheelError(f"unexpected native runtime wheel filename: {path.name}")
    package = normalize_package(match["package"])
    spec = NATIVE_SPECS.get(package)
    if spec is None:
        raise RuntimeWheelError(f"unlisted native runtime package: {package}")
    abi = PLATFORM_ABI[match["platform"]]
    if (
        match["version"] != spec["version"]
        or match["build"] != spec["build"]
        or match["python"] != spec["python"]
        or match["abi_tag"] != spec["abi"]
    ):
        raise RuntimeWheelError(f"native runtime wheel tag/version mismatch: {path.name}")
    return package, abi


def _pure_source_name(package: str, entries: dict[str, dict[str, object]]) -> str:
    return next(
        name
        for name, entry in entries.items()
        if entry.get("kind") in {"prebuilt-wheel", "pure-sdist"}
        and normalize_package(str(entry.get("package"))) == package
    )


def _expected_license_hashes(package: str) -> tuple[str, set[str]]:
    entries = source_entries()
    if package in COMMON_SPECS:
        source_name = _pure_source_name(package, entries)
    else:
        source_name = str(NATIVE_SPECS[package]["source"])
    license_value = entries[source_name]["license"]
    assert isinstance(license_value, dict)
    members = license_value["members"]
    carried = license_value.get("carried", {})
    assert isinstance(members, dict) and isinstance(carried, dict)
    return str(license_value["expression"]), {str(value) for value in (*members.values(), *carried.values())}


def _filename_tags(filename: str) -> list[str]:
    """Expand a wheel filename's compressed tag set (``py2.py3-none-any``)."""
    python, abi, platform_tag = Path(filename).stem.rsplit("-", 3)[1:]
    return sorted(
        f"{interpreter}-{abi_tag}-{platform_value}"
        for interpreter in python.split(".")
        for abi_tag in abi.split(".")
        for platform_value in platform_tag.split(".")
    )


def _repack_excluded(name: str, package: str) -> bool:
    repack = REPACKS.get(package)
    if repack is None:
        return False
    prefix, excludes = repack
    if not name.startswith(prefix):
        return False
    relative = name[len(prefix) :]
    return any(relative.startswith(rule) if rule.endswith("/") else relative == rule for rule in excludes)


def _record(entries: list[tuple[str, bytes, bool]], record_name: str) -> bytes:
    output = io.StringIO()
    writer = csv.writer(output, lineterminator="\n")
    for name, data, _ in entries:
        encoded = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
        writer.writerow((name, f"sha256={encoded}", len(data)))
    writer.writerow((record_name, "", ""))
    return output.getvalue().encode("utf-8")


def _write_wheel(path: Path, entries: list[tuple[str, bytes, bool]], record_name: str) -> None:
    """Write members in order, then RECORD, with fixed times and modes."""
    with zipfile.ZipFile(path, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data, executable in [*entries, (record_name, _record(entries, record_name), False)]:
            info = zipfile.ZipInfo(name, WHEEL_DATE_TIME)
            info.create_system = 3
            info.external_attr = (0o100755 if executable else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data, compresslevel=9)


def repack_wheel(source: Path, output: Path, package: str) -> None:
    """Publish ``source`` without the members ``REPACKS`` excludes for ``package``."""
    with zipfile.ZipFile(source) as archive:
        members = _validated_zip_members(archive, source.name, "wheel")
        files = [
            (info.filename, archive.read(info), bool((info.external_attr >> 16) & stat.S_IXUSR))
            for info in members
            if not info.is_dir()
        ]
    records = [name for name, _, _ in files if name.endswith(".dist-info/RECORD") and name.count("/") == 1]
    if len(records) != 1:
        raise RuntimeWheelError(f"{source.name}: wheel must have one RECORD")
    kept = [entry for entry in files if entry[0] != records[0] and not _repack_excluded(entry[0], package)]
    if len(kept) == len(files) - 1:
        raise RuntimeWheelError(f"{source.name}: repack excludes match no member")
    _write_wheel(output, kept, records[0])


def sdist_wheel(sdist: Path, output: Path, package: str, version: str) -> None:
    """Pack an sdist-only pure package as a ``py3-none-any`` wheel.

    The package directory comes from ``SDIST_WHEELS``, METADATA is the sdist's
    PKG-INFO, and the licence texts are the ones the repository carries.
    """
    prefix = SDIST_WHEELS[package]
    root, _, package_path = prefix.rstrip("/").partition("/")
    target_root = package_path.rsplit("/", 1)[-1]
    entries = source_entries()
    source_entry = entries[_pure_source_name(package, entries)]
    with tarfile.open(sdist) as archive:
        members = sorted(_validated_tar_members(archive, sdist.name), key=lambda member: member.name)

        def read(member: tarfile.TarInfo) -> bytes:
            stream = archive.extractfile(member)
            if stream is None:
                raise RuntimeWheelError(f"{sdist.name}: cannot read {member.name}")
            with stream:
                return stream.read()

        metadata = [read(member) for member in members if member.name == f"{root}/PKG-INFO"]
        files = []
        for member in members:
            if not member.isfile() or not member.name.startswith(prefix):
                continue
            name = f"{target_root}/{member.name[len(prefix):]}"
            if not _repack_excluded(name, package):
                files.append((name, read(member), bool(member.mode & stat.S_IXUSR)))
    if len(metadata) != 1 or not files:
        raise RuntimeWheelError(f"{sdist.name}: sdist has no PKG-INFO or package members")
    dist_info = f"{re.sub(r'[^A-Za-z0-9.]+', '_', package)}-{version}.dist-info"
    license_value = source_entry["license"]
    assert isinstance(license_value, dict)
    carried = license_value.get("carried", {})
    assert isinstance(carried, dict)
    files.extend(
        (f"{dist_info}/{name}", carried_license_path(source_entry, str(name)).read_bytes(), False)
        for name in sorted(carried)
    )
    files.append((f"{dist_info}/METADATA", metadata[0], False))
    files.append(
        (
            f"{dist_info}/WHEEL",
            b"Wheel-Version: 1.0\nGenerator: anki-miner-runtime-wheels\nRoot-Is-Purelib: true\nTag: py3-none-any\n\n",
            False,
        )
    )
    _write_wheel(output, files, f"{dist_info}/RECORD")


def _allowed_native_path(package: str, name: str) -> bool:
    roots = NATIVE_ROOTS.get(package)
    if roots is not None:
        return any(name.startswith(root) if root.endswith("/") else name == root for root in roots)
    if package == "chaquopy-libwebp":
        return (
            name.startswith("chaquopy/lib/lib")
            and name.endswith(".so")
            and Path(name).name
            in {
                "libsharpyuv.so",
                "libwebp.so",
                "libwebpdecoder.so",
                "libwebpdemux.so",
                "libwebpmux.so",
            }
        )
    if package == "pillow":
        return name.startswith("PIL/") and name.endswith(".so") and "/" not in name[4:]
    if package == "lxml":
        return name.startswith("lxml/") and name.endswith(".so")
    return name in NATIVE_REQUIRED_PATHS[package]


def _verify_pinned_members(wheel: str, package: str, files: dict[str, bytes]) -> None:
    """Members a recipe pins byte for byte, such as opencc's generated dictionaries."""
    relative = PINNED_MEMBER_FILES.get(package)
    if relative is None:
        return
    document = load_json(TOOL_ROOT / relative, 1)
    require_exact_keys(document, {"schema", "prefix", "members"}, f"pinned members of {package}")
    prefix = document["prefix"]
    pinned = document["members"]
    if not isinstance(prefix, str) or not prefix.endswith("/") or not isinstance(pinned, dict) or not pinned:
        raise RuntimeWheelError(f"invalid pinned members of {package}")
    actual = {
        name[len(prefix) :]: hashlib.sha256(data).hexdigest() for name, data in files.items() if name.startswith(prefix)
    }
    if actual != pinned:
        differing = sorted(set(actual.items()) ^ set(pinned.items()))
        raise RuntimeWheelError(f"{wheel}: pinned members differ: {differing[:5]}")


def verify_runtime_wheel(path: Path) -> tuple[str, str | None, dict[str, object]]:
    common_package = next(
        (package for package, (_, filename) in COMMON_SPECS.items() if filename == path.name),
        None,
    )
    if common_package is not None:
        package = common_package
        abi: str | None = None
        expected_version = COMMON_SPECS[package][0]
        expected_tag = "-".join(Path(path.name).stem.rsplit("-", 3)[1:])
        expected_tags = _filename_tags(path.name)
    else:
        package, abi = _native_identity(path)
        expected_version = str(NATIVE_SPECS[package]["version"])
        expected_tag = (
            f"{NATIVE_SPECS[package]['python']}-{NATIVE_SPECS[package]['abi']}-"
            f"android_{API_LEVEL}_{abi.replace('-', '_')}"
        )
        expected_tags = [expected_tag]
    if package in FORBIDDEN_PACKAGE_NAMES or "cp313" in path.name:
        raise RuntimeWheelError(f"forbidden runtime package: {path.name}")
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile as error:
        raise RuntimeWheelError(f"invalid runtime wheel: {path}") from error
    with archive:
        members = _validated_zip_members(archive, path.name, "wheel")
        files = {info.filename: archive.read(info) for info in members if not info.is_dir()}
        dist_infos = {
            name.split("/", 1)[0]
            for name in files
            if ".dist-info/" in name and name.split("/", 1)[0].endswith(".dist-info")
        }
        if len(dist_infos) != 1:
            raise RuntimeWheelError(f"{path.name}: wheel must have one dist-info directory")
        dist_info = next(iter(dist_infos))
        _validate_record(archive, files, dist_info)
        metadata = _wheel_message(archive, dist_info, "METADATA")
        metadata_name = normalize_package(str(metadata.get("Name", "")))
        if metadata_name != package or metadata.get("Version") != expected_version:
            raise RuntimeWheelError(f"{path.name}: METADATA identity mismatch")
        wheel_metadata = _wheel_message(archive, dist_info, "WHEEL")
        tags = wheel_metadata.get_all("Tag") or []
        if sorted(str(tag) for tag in tags) != expected_tags or any("cp313" in str(tag) for tag in tags):
            raise RuntimeWheelError(f"{path.name}: WHEEL tag mismatch")

        mandatory: set[str] = set()
        requirements = [str(value) for value in (metadata.get_all("Requires-Dist") or [])]
        for requirement in requirements:
            dependency, optional = _requirement_name(requirement)
            if dependency in FORBIDDEN_PACKAGE_NAMES:
                raise RuntimeWheelError(f"{path.name}: forbidden dependency {dependency}")
            if not optional:
                mandatory.add(dependency)
        if mandatory != MANDATORY_DEPENDENCIES[package]:
            raise RuntimeWheelError(
                f"{path.name}: mandatory dependencies are {sorted(mandatory)}, "
                f"expected {sorted(MANDATORY_DEPENDENCIES[package])}",
            )

        folded_names = "\n".join(files).encode("utf-8", "ignore").lower()
        if any(marker.lower() in folded_names for marker in FORBIDDEN_PAYLOAD_MARKERS):
            raise RuntimeWheelError(f"{path.name}: forbidden payload member")
        excluded = sorted(name for name in files if _repack_excluded(name, package))
        if excluded:
            raise RuntimeWheelError(f"{path.name}: repack-excluded members are present: {excluded[:5]}")
        _verify_pinned_members(path.name, package, files)
        if abi is not None:
            home = os.fsencode(Path.home()) + b"/"
            leaked = sorted(name for name, data in files.items() if not data.startswith(b"\x7fELF") and home in data)
            if leaked:
                raise RuntimeWheelError(f"{path.name}: builder home path leaked into {leaked[:5]}")

        expression, expected_license_hashes = _expected_license_hashes(package)
        license_entries = [
            {"path": name, "sha256": hashlib.sha256(data).hexdigest()}
            for name, data in sorted(files.items())
            if dist_info in Path(name).parts and Path(name).name.upper().startswith(LICENSE_PREFIXES)
        ]
        actual_license_hashes = {str(entry["sha256"]) for entry in license_entries}
        if not expected_license_hashes.issubset(actual_license_hashes):
            raise RuntimeWheelError(f"{path.name}: locked license attribution is missing")

        native_entries: list[dict[str, object]] = []
        for name, data in files.items():
            is_native_name = Path(name).name.endswith(".so") or ".so." in Path(name).name
            is_elf = data.startswith(b"\x7fELF")
            if is_native_name != is_elf:
                raise RuntimeWheelError(f"{path.name}: malformed native member {name}")
            if not is_elf:
                continue
            if abi is None or not _allowed_native_path(package, name):
                raise RuntimeWheelError(f"{path.name}: unexpected native member {name}")
            native_entries.append(inspect_elf(data, name, abi))
        native_paths = {str(entry["path"]) for entry in native_entries}
        if abi is None:
            if native_entries:
                raise RuntimeWheelError(f"{path.name}: common wheel contains native code")
        elif not NATIVE_REQUIRED_PATHS[package].issubset(native_paths):
            raise RuntimeWheelError(
                f"{path.name}: required native payloads are missing: "
                f"{sorted(NATIVE_REQUIRED_PATHS[package] - native_paths)}",
            )
        if abi is not None:
            all_needed = {needed for item in native_entries for needed in item["needed"]}
            if not REQUIRED_NEEDED[package].issubset(all_needed):
                raise RuntimeWheelError(
                    f"{path.name}: required native dependencies are missing: "
                    f"{sorted(REQUIRED_NEEDED[package] - all_needed)}",
                )
            allowed_needed = ANDROID_SYSTEM_LIBS | RUNTIME_NATIVE_LIBS
            if not all_needed.issubset(allowed_needed):
                raise RuntimeWheelError(
                    f"{path.name}: unlisted native dependencies: {sorted(all_needed - allowed_needed)}",
                )
            for item in native_entries:
                name = Path(str(item["path"])).name
                if str(item["path"]).startswith("chaquopy/lib/"):
                    if item["soname"] != name:
                        raise RuntimeWheelError(f"{path.name}: SONAME mismatch for {name}")
                elif item["soname"] is not None:
                    raise RuntimeWheelError(f"{path.name}: Python extension has a SONAME")

    return (
        package,
        abi,
        {
            "package": package,
            "version": expected_version,
            "filename": path.name,
            "sha256": digest(path),
            "size": path.stat().st_size,
            "tag": expected_tag,
            "requires": requirements,
            "license_expression": expression,
            "licenses": license_entries,
            "native": native_entries,
        },
    )


def _native_wheel_set(dist: Path) -> dict[str, Path]:
    wheels = sorted(dist.rglob("*.whl"))
    expected_count = len(ABIS) * len(NATIVE_SPECS)
    if len(wheels) != expected_count:
        raise RuntimeWheelError(f"expected {expected_count} native runtime wheels, found {len(wheels)}")
    result: dict[str, Path] = {}
    identities: set[tuple[str, str]] = set()
    for wheel in wheels:
        package, abi = _native_identity(wheel)
        if wheel.name in result or (package, abi) in identities:
            raise RuntimeWheelError(f"duplicate native runtime wheel: {package}/{abi}")
        identities.add((package, abi))
        result[wheel.name] = wheel
    expected = {(package, abi) for package in NATIVE_SPECS for abi in ABIS}
    if identities != expected:
        raise RuntimeWheelError("native runtime wheel set is incomplete")
    return result


def _attributions(entries: list[dict[str, object]]) -> dict[str, object]:
    grouped: dict[str, list[dict[str, object]]] = {}
    for entry in entries:
        grouped.setdefault(str(entry["package"]), []).append(entry)
    packages: list[dict[str, object]] = []
    for package in sorted(grouped):
        variants = grouped[package]
        first = variants[0]
        canonical_licenses = first["licenses"]
        if any(
            entry["version"] != first["version"]
            or entry["license_expression"] != first["license_expression"]
            or entry["licenses"] != canonical_licenses
            for entry in variants[1:]
        ):
            raise RuntimeWheelError(f"license attribution differs by ABI: {package}")
        packages.append(
            {
                "package": package,
                "version": first["version"],
                "license_expression": first["license_expression"],
                "licenses": canonical_licenses,
            },
        )
    expected_packages = set(COMMON_SPECS) | set(NATIVE_SPECS)
    if set(grouped) != expected_packages:
        raise RuntimeWheelError("runtime attribution package set is incomplete")
    return {"schema": ATTRIBUTION_SCHEMA, "packages": packages}


def _families(wheels: dict[str, list[dict[str, object]]]) -> dict[str, dict[str, list[str]]]:
    """The exact wheel filenames each family's wave vendors, per publication group."""
    return {
        family: {
            group: sorted(
                str(entry["filename"]) for entry in wheels[group] if family in PACKAGE_FAMILIES[str(entry["package"])]
            )
            for group in ("common", *ABIS)
        }
        for family in FAMILIES
    }


def _validate_publication_natives(wheels: dict[str, list[dict[str, object]]]) -> None:
    """Every ABI's natives resolve within Android, libpython and the publication."""
    checker = _native_artifact_checker()
    for abi in ABIS:
        natives = [
            (
                str(native["path"]),
                checker.NativeMetadata(abi, native["soname"], tuple(sorted(native["needed"])), True),
            )
            for entry in wheels[abi]
            for native in entry["native"]
        ]
        try:
            checker.validate_requirement_natives(
                natives,
                f"runtime publication ({abi})",
                external_libraries=EXTERNAL_RUNTIME_LIBS,
            )
        except checker.ArtifactError as error:
            raise RuntimeWheelError(str(error)) from error


def ndk_llvm_objdump() -> Path:
    android_home = os.environ.get("ANDROID_HOME")
    if not android_home:
        raise RuntimeWheelError("ANDROID_HOME is required for the arm64 ISA audit")
    objdump = Path(android_home) / "ndk" / NDK_VERSION / NDK_LLVM_BIN / "llvm-objdump"
    if not objdump.is_file() or not os.access(objdump, os.X_OK):
        raise RuntimeWheelError(f"locked NDK llvm-objdump is missing: {objdump}")
    return objdump


def _isa_audit(directory: Path, wheels: dict[str, list[dict[str, object]]]) -> dict[str, object]:
    """Disassemble every arm64 native; unguarded extension instructions fail the publication."""
    checker = _native_artifact_checker()
    objdump = ndk_llvm_objdump()
    natives: dict[str, object] = {}
    for entry in wheels["arm64-v8a"]:
        with zipfile.ZipFile(directory / str(entry["filename"])) as archive:
            for native in entry["native"]:
                member = str(native["path"])
                key = f"{entry['filename']}!{member}"
                try:
                    natives[key] = checker.audit_arm64_isa(archive.read(member), member, key, objdump)
                except checker.ArtifactError as error:
                    raise RuntimeWheelError(str(error)) from error
    return {"baseline": checker.ARM64_ISA_BASELINE, "natives": natives}


def _validate_isa_audit(value: object, wheels: dict[str, list[dict[str, object]]]) -> None:
    audit = require_exact_keys(value, {"baseline", "natives"}, "publication ISA audit")
    natives = audit.get("natives")
    expected = {f"{entry['filename']}!{native['path']}" for entry in wheels["arm64-v8a"] for native in entry["native"]}
    if (
        audit.get("baseline") != _native_artifact_checker().ARM64_ISA_BASELINE
        or not isinstance(natives, dict)
        or set(natives) != expected
    ):
        raise RuntimeWheelError("publication ISA audit does not cover every arm64 native")
    for report in natives.values():
        if (
            not isinstance(report, dict)
            or not isinstance(report.get("instructions"), int)
            or report["instructions"] <= 0
            or not all(isinstance(count, int) and count > 0 for count in report.values())
        ):
            raise RuntimeWheelError("publication ISA audit report is malformed")


def _materialize_common(package: str, downloads: Path, staging: Path) -> Path:
    """Place one pure wheel in the publication: verbatim, repacked, or packed from its sdist."""
    entries = source_entries()
    entry = entries[_pure_source_name(package, entries)]
    version, filename = COMMON_SPECS[package]
    source = downloads / str(entry["filename"])
    target = staging / filename
    if entry["kind"] == "pure-sdist":
        sdist_wheel(source, target, package, version)
    elif package in REPACKS:
        repack_wheel(source, target, package)
    else:
        shutil.copy2(source, target)
    return target


def publish(
    stage_a: Path,
    stage_b: Path,
    downloads: Path,
    output_root: Path,
    expected_recipe: str,
    expected_build: str,
) -> Path:
    enforce_reproducible_environment()
    recipe, build, identity = validate_expected_keys(expected_recipe, expected_build)
    stage_a = stage_a.resolve(strict=True)
    stage_b = stage_b.resolve(strict=True)
    if stage_a == stage_b:
        raise RuntimeWheelError("reproducibility proof requires two distinct stages")
    document_a, dist_a = validate_stage(stage_a, recipe, build, require_dist=True)
    document_b, dist_b = validate_stage(stage_b, recipe, build, require_dist=True)
    if document_a["stage_id"] == document_b["stage_id"]:
        raise RuntimeWheelError("reproducibility proof requires distinct stage identifiers")
    wheels_a = _native_wheel_set(dist_a)
    wheels_b = _native_wheel_set(dist_b)
    if set(wheels_a) != set(wheels_b):
        raise RuntimeWheelError("clean builds produced different wheel names")
    mismatches = [name for name in sorted(wheels_a) if digest(wheels_a[name]) != digest(wheels_b[name])]
    if mismatches:
        raise RuntimeWheelError(
            "clean runtime builds are not byte-for-byte reproducible: " + ", ".join(mismatches),
        )

    output_root.mkdir(parents=True, exist_ok=True)
    target = output_root / f"runtime-wheels-{build}"
    staging = output_root / f".{target.name}.staging"
    if target.exists() or staging.exists():
        raise RuntimeWheelError(f"immutable runtime publication already exists: {target}")
    published_target = False
    try:
        staging.mkdir()
        entries_by_abi: dict[str, list[dict[str, object]]] = {
            "common": [],
            **{abi: [] for abi in ABIS},
        }
        published: list[dict[str, object]] = []
        for name, wheel in sorted(wheels_a.items()):
            package, _ = _native_identity(wheel)
            if package in REPACKS:
                repack_wheel(wheel, staging / name, package)
            else:
                shutil.copy2(wheel, staging / name)
            _, abi, entry = verify_runtime_wheel(staging / name)
            assert abi is not None
            entries_by_abi[abi].append(entry)
            published.append(entry)
        for package in sorted(COMMON_SPECS):
            wheel = _materialize_common(package, downloads, staging)
            _, abi, entry = verify_runtime_wheel(wheel)
            if abi is not None:
                raise RuntimeWheelError(f"common wheel unexpectedly has an ABI: {wheel.name}")
            entries_by_abi["common"].append(entry)
            published.append(entry)
        for values in entries_by_abi.values():
            values.sort(key=lambda item: str(item["package"]))
        _validate_publication_natives(entries_by_abi)
        isa_audit = _isa_audit(staging, entries_by_abi)

        attribution = _attributions(published)
        attribution_path = staging / "attributions.json"
        attribution_path.write_text(
            json.dumps(attribution, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        manifest = {
            "schema": PUBLICATION_SCHEMA,
            "recipe_key": recipe,
            "build_key": build,
            "builder_identity": identity,
            "builder_identity_sha256": hashlib.sha256(canonical_json(identity)).hexdigest(),
            "api_level": API_LEVEL,
            "ndk": NDK_VERSION,
            "python_target": PYTHON_TARGET,
            "target_build_python": TARGET_BUILD_PYTHON_VERSION,
            "recipe_inventory": recipe_inventory(),
            "sources": source_entries(),
            "host_wheels": {str(entry["filename"]): entry["sha256"] for entry in host_entries()},
            "reproducibility": {
                "stage_manifests": [
                    {
                        "stage_id": document["stage_id"],
                        "sha256": digest(_stage_manifest_path(stage_value)),
                    }
                    for stage_value, document in (
                        (stage_a, document_a),
                        (stage_b, document_b),
                    )
                ],
                "native_wheels_byte_identical": True,
            },
            "wheels": entries_by_abi,
            "families": _families(entries_by_abi),
            "isa_audit": isa_audit,
            "attributions_sha256": digest(attribution_path),
        }
        manifest_path = staging / "manifest.json"
        manifest_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.replace(staging, target)
        published_target = True
        verify_publication(target / "manifest.json")
    except Exception:
        if staging.exists():
            shutil.rmtree(staging)
        if published_target and target.exists():
            shutil.rmtree(target)
        raise
    return target / "manifest.json"


def _publication_regular_files(target: Path) -> set[str]:
    try:
        entries = list(target.iterdir())
    except OSError as error:
        raise RuntimeWheelError(f"cannot inspect runtime publication: {target}") from error
    for entry in entries:
        if entry.is_symlink() or not entry.is_file():
            raise RuntimeWheelError(
                f"publication contains a non-regular entry: {entry.name}",
            )
    return {entry.name for entry in entries}


def _publication_summary(
    recipe: str,
    build: str,
    wheels: dict[str, object],
    families: dict[str, object],
) -> dict[str, object]:
    return {
        "schema": PUBLICATION_SCHEMA,
        "recipe_key": recipe,
        "build_key": build,
        "api_level": API_LEVEL,
        "ndk": NDK_VERSION,
        "python_target": PYTHON_TARGET,
        "groups": {group: sorted(str(entry["filename"]) for entry in wheels[group]) for group in ("common", *ABIS)},
        "families": families,
    }


def verify_publication(
    manifest_path: Path,
    *,
    require_current: bool = True,
) -> dict[str, object]:
    manifest_path = manifest_path.absolute()
    target = manifest_path.parent
    if target.is_symlink() or not target.is_dir():
        raise RuntimeWheelError(f"publication target is not a regular directory: {target}")
    if manifest_path.is_symlink() or not manifest_path.is_file():
        raise RuntimeWheelError(f"publication manifest is not a regular file: {manifest_path}")
    actual_files = _publication_regular_files(target)
    target = target.resolve(strict=True)
    manifest_path = manifest_path.resolve(strict=True)
    if manifest_path != target / "manifest.json":
        raise RuntimeWheelError("publication manifest is outside its immutable target")
    document = load_json(manifest_path, PUBLICATION_SCHEMA)
    require_exact_keys(
        document,
        {
            "schema",
            "recipe_key",
            "build_key",
            "builder_identity",
            "builder_identity_sha256",
            "api_level",
            "ndk",
            "python_target",
            "target_build_python",
            "recipe_inventory",
            "sources",
            "host_wheels",
            "reproducibility",
            "wheels",
            "families",
            "isa_audit",
            "attributions_sha256",
        },
        "runtime publication manifest",
    )
    recipe = document.get("recipe_key")
    build = document.get("build_key")
    if (
        not isinstance(recipe, str)
        or KEY_PATTERN.fullmatch(recipe) is None
        or not isinstance(build, str)
        or KEY_PATTERN.fullmatch(build) is None
    ):
        raise RuntimeWheelError("publication keys are invalid")
    identity = validate_builder_identity(document.get("builder_identity"))
    if (
        document.get("builder_identity_sha256") != hashlib.sha256(canonical_json(identity)).hexdigest()
        or build_key(recipe, identity) != build
    ):
        raise RuntimeWheelError("publication builder identity/key mismatch")
    if require_current:
        current_identity = builder_identity()
        current_recipe = source_recipe_key()
        if identity != current_identity or recipe != current_recipe:
            raise RuntimeWheelError("publication differs from the active builder/recipe")
        if build != build_key(current_recipe, current_identity):
            raise RuntimeWheelError("publication build key is stale")
        if document.get("recipe_inventory") != recipe_inventory():
            raise RuntimeWheelError("publication recipe inventory is stale")
    if (
        manifest_path.name != "manifest.json"
        or manifest_path.parent.name != f"runtime-wheels-{build}"
        or document.get("api_level") != API_LEVEL
        or document.get("ndk") != NDK_VERSION
        or document.get("python_target") != PYTHON_TARGET
        or document.get("target_build_python") != TARGET_BUILD_PYTHON_VERSION
    ):
        raise RuntimeWheelError("publication platform identity mismatch")
    if document.get("sources") != source_entries():
        raise RuntimeWheelError("publication source lock differs")
    if document.get("host_wheels") != {str(entry["filename"]): entry["sha256"] for entry in host_entries()}:
        raise RuntimeWheelError("publication host-wheel lock differs")
    reproducibility = require_exact_keys(
        document.get("reproducibility"),
        {"stage_manifests", "native_wheels_byte_identical"},
        "publication reproducibility proof",
    )
    stages = reproducibility.get("stage_manifests")
    if (
        reproducibility.get("native_wheels_byte_identical") is not True
        or not isinstance(stages, list)
        or len(stages) != 2
        or len({str(stage.get("stage_id")) for stage in stages if isinstance(stage, dict)}) != 2
    ):
        raise RuntimeWheelError("publication reproducibility proof is incomplete")
    for stage_value in stages:
        stage_entry = require_exact_keys(stage_value, {"stage_id", "sha256"}, "stage proof")
        if (
            not isinstance(stage_entry.get("stage_id"), str)
            or STAGE_ID_PATTERN.fullmatch(str(stage_entry["stage_id"])) is None
            or not isinstance(stage_entry.get("sha256"), str)
            or KEY_PATTERN.fullmatch(str(stage_entry["sha256"])) is None
        ):
            raise RuntimeWheelError("invalid stage proof")

    wheels = require_exact_keys(
        document.get("wheels"),
        {"common", *ABIS},
        "publication wheel groups",
    )
    expected_counts = {"common": len(COMMON_SPECS), **{abi: len(NATIVE_SPECS) for abi in ABIS}}
    expected_files: set[str] = set()
    verified_entries: list[dict[str, object]] = []
    for group, count in expected_counts.items():
        entries = wheels.get(group)
        if not isinstance(entries, list) or len(entries) != count:
            raise RuntimeWheelError(f"publication {group} wheel set is incomplete")
        packages: set[str] = set()
        for raw_entry in entries:
            entry = require_exact_keys(
                raw_entry,
                {
                    "package",
                    "version",
                    "filename",
                    "sha256",
                    "size",
                    "tag",
                    "requires",
                    "license_expression",
                    "licenses",
                    "native",
                },
                "publication wheel entry",
            )
            filename = entry.get("filename")
            if not isinstance(filename, str) or Path(filename).name != filename or filename in expected_files:
                raise RuntimeWheelError("publication wheel filename is invalid")
            wheel_path = manifest_path.parent / filename
            if (
                wheel_path.is_symlink()
                or not wheel_path.is_file()
                or wheel_path.resolve().parent != manifest_path.parent
            ):
                raise RuntimeWheelError(f"publication wheel is missing: {filename}")
            package, abi, checked = verify_runtime_wheel(wheel_path)
            expected_group = abi or "common"
            if expected_group != group or checked != entry or package in packages:
                raise RuntimeWheelError(f"publication wheel inventory mismatch: {filename}")
            packages.add(package)
            expected_files.add(filename)
            verified_entries.append(entry)
    expected_packages = {
        "common": set(COMMON_SPECS),
        **{abi: set(NATIVE_SPECS) for abi in ABIS},
    }
    for group, packages in expected_packages.items():
        if {str(entry["package"]) for entry in wheels[group]} != packages:
            raise RuntimeWheelError(f"publication {group} package set differs")
    families = _families(wheels)
    if document.get("families") != families:
        raise RuntimeWheelError("publication family selection differs from the package families")
    _validate_publication_natives(wheels)
    _validate_isa_audit(document.get("isa_audit"), wheels)

    attribution_path = manifest_path.parent / "attributions.json"
    if (
        attribution_path.is_symlink()
        or not attribution_path.is_file()
        or digest(attribution_path) != document.get("attributions_sha256")
    ):
        raise RuntimeWheelError("publication attribution file is missing or changed")
    attribution = load_json(attribution_path, ATTRIBUTION_SCHEMA)
    require_exact_keys(attribution, {"schema", "packages"}, "runtime attribution")
    if attribution != _attributions(verified_entries):
        raise RuntimeWheelError("runtime attribution inventory differs from wheels")
    if (
        actual_files != expected_files | {"manifest.json", "attributions.json"}
        or _publication_regular_files(manifest_path.parent) != actual_files
    ):
        raise RuntimeWheelError("publication contains unmanifested or missing files")
    return _publication_summary(recipe, build, wheels, families)


def activate_publication(manifest_path: Path, pointer: Path) -> dict[str, object]:
    """Verify an immutable publication, then atomically point this checkout at it."""

    verification = verify_publication(manifest_path)
    manifest_path = manifest_path.resolve(strict=True)
    target = manifest_path.parent
    if not target.is_dir():
        raise RuntimeWheelError(f"publication target is not a directory: {target}")

    pointer = pointer.absolute()
    pointer.parent.mkdir(parents=True, exist_ok=True)
    pointer_parent = pointer.parent.resolve(strict=True)
    pointer = pointer_parent / pointer.name
    if pointer.exists() and not pointer.is_symlink():
        raise RuntimeWheelError(f"runtime publication pointer is not a symlink: {pointer}")

    temporary_parent = Path(
        tempfile.mkdtemp(prefix=f".{pointer.name}.activate-", dir=pointer_parent),
    )
    temporary_link = temporary_parent / "target"
    try:
        temporary_link.symlink_to(target, target_is_directory=True)
        os.replace(temporary_link, pointer)
    finally:
        if temporary_link.is_symlink() or temporary_link.exists():
            temporary_link.unlink()
        temporary_parent.rmdir()
    return verification


def build_order() -> list[tuple[str, bool]]:
    """Recipes in build order, each with whether it is a Python package.

    Shared libraries come first because Python extensions link against them
    (pillow against libjpeg, lxml against libxml2).
    """
    shared = [package for package, spec in NATIVE_SPECS.items() if spec["python"] == "py3"]
    extensions = [package for package, spec in NATIVE_SPECS.items() if spec["python"] != "py3"]
    return [(package, False) for package in shared] + [(package, True) for package in extensions]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("recipe-key")
    subparsers.add_parser("build-order")
    subparsers.add_parser("rust-bin")
    vendoring = subparsers.add_parser("vendor")
    vendoring.add_argument("name")
    vendoring.add_argument("--downloads", type=Path, required=True)
    subparsers.add_parser("builder-identity")
    subparsers.add_parser("build-key")
    requirements = subparsers.add_parser("host-requirements")
    requirements.add_argument("--role", choices=("outer", "target"), required=True)
    fetch = subparsers.add_parser("fetch")
    fetch.add_argument("--downloads", type=Path, required=True)
    fetch.add_argument("--wheelhouse", type=Path, required=True)
    verify = subparsers.add_parser("verify-inputs")
    verify.add_argument("--downloads", type=Path, required=True)
    verify.add_argument("--wheelhouse", type=Path, required=True)
    staging = subparsers.add_parser("stage")
    staging.add_argument("--downloads", type=Path, required=True)
    staging.add_argument("--wheelhouse", type=Path, required=True)
    staging.add_argument("--build-root", type=Path, required=True)
    staging.add_argument("--stage-id", required=True)
    staging.add_argument("--expected-recipe-key", required=True)
    staging.add_argument("--expected-build-key", required=True)
    recipes = subparsers.add_parser("validate-recipes")
    recipes.add_argument("--chaquopy-root", type=Path, required=True)
    publishing = subparsers.add_parser("publish")
    publishing.add_argument("--stage-a", type=Path, required=True)
    publishing.add_argument("--stage-b", type=Path, required=True)
    publishing.add_argument("--downloads", type=Path, required=True)
    publishing.add_argument("--output-root", type=Path, required=True)
    publishing.add_argument("--expected-recipe-key", required=True)
    publishing.add_argument("--expected-build-key", required=True)
    publication = subparsers.add_parser("verify-publication")
    publication.add_argument("--manifest", type=Path, required=True)
    activation = subparsers.add_parser("activate-publication")
    activation.add_argument("--manifest", type=Path, required=True)
    activation.add_argument("--pointer", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    try:
        args = parse_args()
        if args.command == "recipe-key":
            print(source_recipe_key())
        elif args.command == "build-order":
            print("\n".join(f"{package}\t{'python' if python else 'native'}" for package, python in build_order()))
        elif args.command == "rust-bin":
            rust_identity()
            print(rust_toolchain_bin())
        elif args.command == "vendor":
            print(cargo_vendor(args.name, source_entries()[args.name], args.downloads))
        elif args.command == "builder-identity":
            print(json.dumps(builder_identity(), sort_keys=True, separators=(",", ":")))
        elif args.command == "build-key":
            print(build_key())
        elif args.command == "host-requirements":
            print("\n".join(host_requirements(args.role)))
        elif args.command == "fetch":
            fetch_inputs(args.downloads, args.wheelhouse)
        elif args.command == "verify-inputs":
            verify_inputs(args.downloads, args.wheelhouse)
        elif args.command == "stage":
            print(
                stage(
                    args.downloads,
                    args.wheelhouse,
                    args.build_root,
                    args.stage_id,
                    args.expected_recipe_key,
                    args.expected_build_key,
                ),
            )
        elif args.command == "validate-recipes":
            print(json.dumps({"schema": 1, "recipes": validate_recipes(args.chaquopy_root)}))
        elif args.command == "publish":
            print(
                publish(
                    args.stage_a,
                    args.stage_b,
                    args.downloads,
                    args.output_root,
                    args.expected_recipe_key,
                    args.expected_build_key,
                ),
            )
        elif args.command == "verify-publication":
            print(json.dumps(verify_publication(args.manifest), sort_keys=True))
        elif args.command == "activate-publication":
            print(
                json.dumps(
                    activate_publication(args.manifest, args.pointer),
                    sort_keys=True,
                ),
            )
        else:
            raise AssertionError(args.command)
    except (RuntimeWheelError, OSError, subprocess.CalledProcessError) as error:
        print(f"runtime-wheels: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
