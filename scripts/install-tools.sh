#!/usr/bin/env bash
# Downloads pinned kind and kubectl release binaries into .tools/bin (checksums verified).
# No system-wide installation, no sudo. Used locally (Homebrew is not required) and in CI.
set -euo pipefail
cd "$(dirname "$0")/.."
KIND_VERSION="${KIND_VERSION:-v0.33.0}"
KUBECTL_VERSION="${KUBECTL_VERSION:-v1.37.2}"
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$(uname -m)" in x86_64|amd64) ARCH=amd64 ;; arm64|aarch64) ARCH=arm64 ;; *) echo "unsupported arch"; exit 1 ;; esac
BIN=.tools/bin
mkdir -p "$BIN"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

if [ ! -x "$BIN/kind" ] || ! "$BIN/kind" version | grep -q "$KIND_VERSION"; then
  url="https://github.com/kubernetes-sigs/kind/releases/download/$KIND_VERSION/kind-$OS-$ARCH"
  curl -fsSLo "$BIN/kind.tmp" "$url"
  [ "$(sha256 "$BIN/kind.tmp")" = "$(curl -fsSL "$url.sha256sum" | cut -d' ' -f1)" ] || { echo "kind checksum mismatch"; exit 1; }
  mv "$BIN/kind.tmp" "$BIN/kind" && chmod +x "$BIN/kind"
fi
if [ ! -x "$BIN/kubectl" ] || ! "$BIN/kubectl" version --client 2>/dev/null | grep -q "$KUBECTL_VERSION"; then
  url="https://dl.k8s.io/release/$KUBECTL_VERSION/bin/$OS/$ARCH/kubectl"
  curl -fsSLo "$BIN/kubectl.tmp" "$url"
  [ "$(sha256 "$BIN/kubectl.tmp")" = "$(curl -fsSL "$url.sha256")" ] || { echo "kubectl checksum mismatch"; exit 1; }
  mv "$BIN/kubectl.tmp" "$BIN/kubectl" && chmod +x "$BIN/kubectl"
fi
"$BIN/kind" version
"$BIN/kubectl" version --client | head -1
