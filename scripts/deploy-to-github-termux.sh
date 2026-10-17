#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

# Usage:
#   bash scripts/deploy-to-github-termux.sh [folder-project]
# Optional: GITHUB_REPO=OWNER/REPO to override the default repository.
REPO="${GITHUB_REPO:-Plehooo/cinta}"
SOURCE="${1:-$HOME/storage/downloads/GaleriCinta}"

for cmd in git gh; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Tool '$cmd' belum tersedia. Jalankan: pkg install git gh -y" >&2
    exit 1
  fi
done
if [ ! -f "$SOURCE/settings.gradle.kts" ]; then
  echo "Folder project tidak ditemukan: $SOURCE" >&2
  echo "Ekstrak ZIP GaleriCinta ke ~/storage/downloads/GaleriCinta, atau berikan path folder project." >&2
  exit 1
fi
gh auth status >/dev/null 2>&1 || { echo "Login GitHub dulu dengan: gh auth login" >&2; exit 1; }

WORK=$(mktemp -d "$HOME/galeri-cinta-publish.XXXXXX")
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

configure_git() {
  git config user.name "$(gh api user --jq .name 2>/dev/null || gh api user --jq .login)"
  git config user.email "$(gh api user --jq .login)@users.noreply.github.com"
}

if ! gh repo view "$REPO" >/dev/null 2>&1; then
  echo "Repository $REPO belum ada; membuat repository private baru..."
  cp -a "$SOURCE"/. "$WORK"/
  cd "$WORK"
  git init -b main
  configure_git
  git add -A
  git commit -m "Initial Galeri Cinta Android project"
  gh repo create "$REPO" --private --source . --remote origin --push
else
  REPO_SIZE=$(gh api "repos/$REPO" --jq '.size')
  if [ "$REPO_SIZE" = "0" ]; then
    echo "Repository $REPO kosong; membuat commit awal tanpa menghapus riwayat (belum ada)."
    cp -a "$SOURCE"/. "$WORK"/
    cd "$WORK"
    git init -b main
    configure_git
    git add -A
    git commit -m "Initial Galeri Cinta Android project"
    gh auth setup-git
    git remote add origin "https://github.com/$REPO.git"
    git push -u origin main
  else
    echo "Repository $REPO sudah berisi source; overlay file proyek tanpa force-push."
    gh repo clone "$REPO" "$WORK/repo"
    WORK="$WORK/repo"
    cp -a "$SOURCE"/. "$WORK"/
    cd "$WORK"
    configure_git
    git add -A
    if git diff --cached --quiet; then
      echo "Tidak ada perubahan baru untuk dikirim."
      exit 0
    fi
    git commit -m "Build/update Galeri Cinta Android project"
    BRANCH=$(git branch --show-current)
    git pull --rebase origin "$BRANCH"
    git push origin "$BRANCH"
  fi
fi

echo
echo "Source berhasil dikirim ke https://github.com/$REPO"
echo "Buka tab Actions dan tunggu workflow 'Build Galeri Cinta APK' selesai. APK diunggah sebagai artifact: galeri-cinta-debug-apk."
