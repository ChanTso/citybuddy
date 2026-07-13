#!/usr/bin/env bash
set -euo pipefail

version="${GITLEAKS_VERSION:-8.30.1}"
destination=".tools/gitleaks"

if [[ -x "$destination" ]] && "$destination" version | grep -Fq "$version"; then
  exit 0
fi

case "$(uname -s)" in
  Darwin) os="darwin" ;;
  Linux) os="linux" ;;
  *) echo "unsupported operating system: $(uname -s)" >&2; exit 1 ;;
esac

case "$(uname -m)" in
  arm64|aarch64) arch="arm64" ;;
  x86_64|amd64) arch="x64" ;;
  *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

archive="gitleaks_${version}_${os}_${arch}.tar.gz"
checksums="gitleaks_${version}_checksums.txt"
base_url="https://github.com/gitleaks/gitleaks/releases/download/v${version}"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

curl --proto '=https' --tlsv1.2 -fsSLo "$temporary_directory/$archive" "$base_url/$archive"
curl --proto '=https' --tlsv1.2 -fsSLo "$temporary_directory/$checksums" "$base_url/$checksums"

expected="$(awk -v name="$archive" '$2 == name { print $1 }' "$temporary_directory/$checksums")"
if [[ -z "$expected" ]]; then
  echo "checksum not found for $archive" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$temporary_directory/$archive" | awk '{ print $1 }')"
else
  actual="$(shasum -a 256 "$temporary_directory/$archive" | awk '{ print $1 }')"
fi

if [[ "$actual" != "$expected" ]]; then
  echo "checksum mismatch for $archive" >&2
  exit 1
fi

mkdir -p .tools
tar -xzf "$temporary_directory/$archive" -C .tools gitleaks
chmod 0755 "$destination"
"$destination" version
