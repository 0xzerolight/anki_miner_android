#!/usr/bin/env bash
set -euo pipefail

export SOURCE_DATE_EPOCH=1704067200
export PYTHONHASHSEED=0
export TZ=UTC
export LC_ALL=C
export LANG=C
umask 022

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/android-env.sh
source "$REPO_ROOT/scripts/android-env.sh"

runtime_error() {
    echo "Runtime wheels: $*" >&2
}

runtime_python() {
    python3.13 "$SCRIPT_DIR/runtime_wheels.py" "$@"
}

runtime_configure_paths() {
    runtime_root="${ANKI_MINER_RUNTIME_WHEELS_ROOT:-$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/runtime-wheels}"
    downloads="${ANKI_MINER_RUNTIME_DOWNLOADS:-$runtime_root/downloads}"
    wheelhouse="${ANKI_MINER_RUNTIME_HOST_WHEELHOUSE:-$runtime_root/host-wheels}"
    build_root="${ANKI_MINER_RUNTIME_BUILD_ROOT:-$runtime_root/build}"
    publication_root="${ANKI_MINER_RUNTIME_OUTPUT_ROOT:-$runtime_root}"
    current_pointer="$SCRIPT_DIR/out/current"
}

runtime_provision_build_python() {
    "$REPO_ROOT/scripts/provision-chaquopy-build-python.sh"
}

runtime_acquire_locks() {
    local lock_root="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.locks"
    mkdir -p "$lock_root"

    exec {build_python_lock_fd}>"$lock_root/chaquopy-build-python.lock"
    flock --shared "$build_python_lock_fd"
    exec {runtime_publication_lock_fd}>"$lock_root/runtime-wheels.lock"
    flock --exclusive "$runtime_publication_lock_fd"
}

runtime_verify_build_python() {
    : "${ANKI_MINER_CHAQUOPY_BUILD_PYTHON:?android-env.sh did not select the Chaquopy build Python}"
    [[ "$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" == /* ]] || {
        runtime_error "Chaquopy build Python must be an absolute path."
        return 1
    }
    [[ -x "$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" ]] || {
        runtime_error "Chaquopy build Python is not executable: $ANKI_MINER_CHAQUOPY_BUILD_PYTHON"
        return 1
    }
    python3.13 "$REPO_ROOT/scripts/verify_chaquopy_build_python.py" \
        --lock "$REPO_ROOT/scripts/chaquopy-build-python.lock.json" \
        verify \
        --toolchain-root "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT" \
        --python "$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" >/dev/null
    export ANKI_MINER_CHAQUOPY_BUILD_PYTHON
}

runtime_check_prerequisites() {
    "$REPO_ROOT/scripts/android-licenses.sh" check
    [[ -d "$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION" ]] || {
        runtime_error "locked NDK is missing: $ANDROID_HOME/ndk/$ANDROID_NDK_VERSION"
        return 1
    }
}

runtime_compute_keys() {
    recipe_key="$(runtime_python recipe-key)"
    build_key="$(runtime_python build-key)"
}

runtime_verify_target() {
    local target="$1"
    [[ -d "$target" && ! -L "$target" ]] || {
        runtime_error "immutable publication target is not a regular directory: $target"
        return 1
    }
    runtime_python verify-publication --manifest "$target/manifest.json"
}

runtime_activate_target() {
    local target="$1"
    runtime_python activate-publication \
        --manifest "$target/manifest.json" \
        --pointer "$current_pointer" >/dev/null
}

runtime_build_stage() {
    local stage="$1"
    local chaquopy patchelf patchelf_dir builder_env pip_wheel
    chaquopy="$(find "$stage/chaquopy" -mindepth 1 -maxdepth 1 -type d -print -quit)"
    patchelf="$(find "$stage/patchelf" -type f -name patchelf -perm -u+x -print -quit)"
    [[ -n "$chaquopy" && -n "$patchelf" ]] || {
        runtime_error "runtime wheel stage is incomplete: $stage"
        return 1
    }

    builder_env="$stage/builder-env"
    python3.13 -m venv --without-pip "$builder_env"
    pip_wheel="$(find "$wheelhouse" -maxdepth 1 -name 'pip-25.1.1-*.whl' -print -quit)"
    [[ -n "$pip_wheel" ]] || {
        runtime_error "locked pip wheel is missing from $wheelhouse"
        return 1
    }
    PYTHONPATH="$pip_wheel" "$builder_env/bin/python" -m pip install \
        --no-index \
        --only-binary=:all: \
        --find-links "$wheelhouse" \
        "${outer_requirements[@]}"
    "$builder_env/bin/python" -m pip check
    "$builder_env/bin/python" "$SCRIPT_DIR/runtime_wheels.py" validate-recipes \
        --chaquopy-root "$chaquopy"
    patchelf_dir="$(dirname "$patchelf")"

    mkdir -p "$stage/home" "$stage/tmp" "$stage/cache"
    (
        unset ALL_PROXY all_proxy HTTP_PROXY http_proxy HTTPS_PROXY https_proxy NO_PROXY no_proxy
        export ANDROID_HOME
        export ANKI_MINER_CHAQUOPY_BUILD_PYTHON
        export ANKI_MINER_HOST_WHEELHOUSE="$wheelhouse"
        export ANKI_MINER_RUNTIME_STAGE_ROOT="$stage"
        export CARGO_NET_OFFLINE=true
        export HOME="$stage/home"
        export PIP_CONFIG_FILE=/dev/null
        export PIP_DISABLE_PIP_VERSION_CHECK=1
        export PIP_NO_INDEX=1
        export PYTHONNOUSERSITE=1
        export TMPDIR="$stage/tmp"
        export XDG_CACHE_HOME="$stage/cache"
        export PATH="$builder_env/bin:$patchelf_dir:$PATH"
        cd "$chaquopy/server/pypi"
        for abi in arm64-v8a x86_64; do
            "$builder_env/bin/python" build-wheel.py --abi "$abi" --api-level 26 chaquopy-libjpeg
            "$builder_env/bin/python" build-wheel.py --abi "$abi" --api-level 26 chaquopy-freetype
            "$builder_env/bin/python" build-wheel.py --abi "$abi" --api-level 26 chaquopy-libwebp
            "$builder_env/bin/python" build-wheel.py --abi "$abi" --api-level 26 chaquopy-libxml2
            "$builder_env/bin/python" build-wheel.py --abi "$abi" --api-level 26 chaquopy-libxslt
            "$builder_env/bin/python" build-wheel.py --python 3.12 --abi "$abi" --api-level 26 pillow
            "$builder_env/bin/python" build-wheel.py --python 3.12 --abi "$abi" --api-level 26 lxml
        done
    )
}

runtime_build_target() {
    runtime_python fetch \
        --downloads "$downloads" \
        --wheelhouse "$wheelhouse"

    run_root="$(mktemp -d "$build_root/runtime-$build_key-run-XXXXXXXX")"
    stage_a="$(runtime_python stage \
        --downloads "$downloads" \
        --wheelhouse "$wheelhouse" \
        --build-root "$run_root" \
        --stage-id clean-a \
        --expected-recipe-key "$recipe_key" \
        --expected-build-key "$build_key")"
    stage_b="$(runtime_python stage \
        --downloads "$downloads" \
        --wheelhouse "$wheelhouse" \
        --build-root "$run_root" \
        --stage-id clean-b \
        --expected-recipe-key "$recipe_key" \
        --expected-build-key "$build_key")"

    mapfile -t outer_requirements < <(
        runtime_python host-requirements --role outer
    )
    runtime_build_stage "$stage_a"
    runtime_build_stage "$stage_b"

    built_manifest="$("$stage_a/builder-env/bin/python" "$SCRIPT_DIR/runtime_wheels.py" publish \
        --stage-a "$stage_a" \
        --stage-b "$stage_b" \
        --downloads "$downloads" \
        --output-root "$publication_root" \
        --expected-recipe-key "$recipe_key" \
        --expected-build-key "$build_key")"
}

runtime_main() {
    (($# == 0)) || {
        runtime_error "this command takes no arguments"
        return 2
    }
    command -v flock >/dev/null || {
        runtime_error "required command not found: flock"
        return 1
    }
    command -v python3.13 >/dev/null || {
        runtime_error "required command not found: python3.13"
        return 1
    }

    runtime_configure_paths

    # Provisioning takes its own exclusive lock and must finish before this
    # process holds the shared build-Python lock.
    runtime_provision_build_python

    # Lock order is global: build Python first, runtime publication second.
    runtime_acquire_locks
    runtime_verify_build_python
    runtime_check_prerequisites
    runtime_compute_keys

    mkdir -p "$runtime_root" "$build_root" "$publication_root"
    build_root="$(cd "$build_root" && pwd -P)"
    publication_root="$(cd "$publication_root" && pwd -P)"
    target="$publication_root/runtime-wheels-$build_key"

    # An exact target is immutable. Reuse is allowed only after complete
    # verification; an invalid existing target is never rebuilt in place.
    if [[ -e "$target" || -L "$target" ]]; then
        runtime_verify_target "$target"
        runtime_activate_target "$target"
        echo "Reused verified runtime wheel publication: $target/manifest.json"
        return 0
    fi

    run_root=""
    built_manifest=""
    runtime_build_target
    [[ "$built_manifest" == "$target/manifest.json" ]] || {
        runtime_error "publisher returned an unexpected manifest: $built_manifest"
        return 1
    }
    runtime_verify_target "$target"
    runtime_activate_target "$target"

    case "$run_root" in
        "$build_root/runtime-$build_key-run-"*) rm -rf -- "$run_root" ;;
        *)
            runtime_error "refusing to clean unexpected private build root: $run_root"
            return 1
            ;;
    esac
    echo "Built and activated verified runtime wheel publication: $built_manifest"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    runtime_main "$@"
fi
