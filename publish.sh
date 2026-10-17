#!/usr/bin/env bash
# GitHub'da public repo açar, projeyi yükler, derlemeyi başlatır.
# Gereken: git ve GitHub CLI (gh). https://cli.github.com
set -euo pipefail
cd "$(dirname "$0")"

REPO_NAME="${1:-hdfcdual-cloudstream}"

command -v git >/dev/null || { echo "HATA: git kurulu değil."; exit 1; }
command -v gh  >/dev/null || { echo "HATA: GitHub CLI (gh) kurulu değil: https://cli.github.com"; exit 1; }

if ! gh auth status >/dev/null 2>&1; then
  echo ">> GitHub girişi gerekiyor..."
  gh auth login --web --git-protocol https --scopes repo,workflow
fi
gh auth setup-git

USER_NAME="$(gh api user --jq .login)"
FULL="$USER_NAME/$REPO_NAME"
echo ">> Hedef repo: $FULL (public)"

# repo.json içindeki yer tutucuyu doldur
sed -i.bak "s#USER/REPO#$FULL#g" repo.json && rm -f repo.json.bak

[ -d .git ] || git init -q
git config user.name  >/dev/null 2>&1 || git config user.name  "$USER_NAME"
git config user.email >/dev/null 2>&1 || git config user.email "$USER_NAME@users.noreply.github.com"
git checkout -q -B main
git add -A
git commit -q -m "İlk sürüm: HDFilmCehennemi Dual" || true

if gh repo view "$FULL" >/dev/null 2>&1; then
  echo ">> Repo zaten var, mevcut repoya yükleniyor."
  git remote remove origin 2>/dev/null || true
  git remote add origin "https://github.com/$FULL.git"
else
  gh repo create "$FULL" --public --description "Cloudstream: HDFilmCehennemi + çift altyazı" --source=. --remote=origin
fi

# İş akışı 'builds' dalını bekler: önce onu boş (orphan) olarak oluştur
TMP="$(mktemp -d)"
git worktree add -q --detach "$TMP" HEAD
( cd "$TMP"
  git checkout -q --orphan builds
  git rm -rf -q . >/dev/null 2>&1 || true
  git commit -q --allow-empty -m "builds dalı"
  git push -q -f origin builds )
git worktree remove --force "$TMP"

git push -u -f origin main

echo
echo "=============================================================="
echo " Yüklendi: https://github.com/$FULL"
echo " Derleme: https://github.com/$FULL/actions  (yaklaşık 3-6 dk)"
echo
echo " Derleme YEŞİL olunca Cloudstream'de:"
echo "  1) Ayarlar > Eklentiler > Depo ekle"
echo "  2) Şu adresi yapıştır:"
echo "     https://raw.githubusercontent.com/$FULL/main/repo.json"
echo "  3) 'HDFilmCehennemi Dual' eklentisini indir"
echo "  4) Eklenti satırındaki dişli ile altyazı ayarlarını aç"
echo "=============================================================="
