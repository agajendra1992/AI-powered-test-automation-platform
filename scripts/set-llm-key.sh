#!/usr/bin/env bash
# Stores an LLM provider API key outside the project, readable only by you, and optionally checks it.
#
#   scripts/set-llm-key.sh openai            # hidden prompt; writes ~/.config/qa-platform/openai.env (mode 600)
#   scripts/set-llm-key.sh openai --check    # asks the provider whether the stored key works (free model list call)
#
# The key is never echoed, never passed on a command line (so never visible in `ps`), and never
# written inside the repository. Use it with:  set -a; . ~/.config/qa-platform/openai.env; set +a
set -euo pipefail

provider="${1:-}"
case "$provider" in
  openai)    var=OPENAI_API_KEY ;;
  anthropic) var=ANTHROPIC_API_KEY ;;
  gemini)    var=GEMINI_API_KEY ;;
  *) echo "usage: $0 openai|anthropic|gemini [--check]" >&2; exit 2 ;;
esac
dir="${QA_PLATFORM_CONFIG_DIR:-$HOME/.config/qa-platform}"
file="$dir/$provider.env"

check() {
  [ -r "$file" ] || { echo "No key stored yet: run $0 $provider" >&2; exit 1; }
  umask 077
  hdr=$(mktemp); trap 'rm -f "$hdr"' EXIT
  # The key goes into a private temp header file, not onto curl's command line.
  key=$(sed -n "s/^[[:space:]]*$var[[:space:]]*=[[:space:]]*//p" "$file" | head -1 | tr -d '\r"'"'"' ')
  [ -n "$key" ] || { echo "$file has no $var value yet: put the key after $var= and save" >&2; exit 1; }
  case "$provider" in
    openai)    printf 'Authorization: Bearer %s\n' "$key" > "$hdr"; url=https://api.openai.com/v1/models ;;
    anthropic) printf 'x-api-key: %s\nanthropic-version: 2023-06-01\n' "$key" > "$hdr"; url=https://api.anthropic.com/v1/models ;;
    gemini)    printf 'x-goog-api-key: %s\n' "$key" > "$hdr"; url=https://generativelanguage.googleapis.com/v1beta/models ;;
  esac
  unset key
  body=$(mktemp); trap 'rm -f "$hdr" "$body"' EXIT
  code=$(curl -sS -o "$body" -w '%{http_code}' --max-time 20 -H @"$hdr" "$url" || echo 000)
  case "$code" in
    200) echo "$provider key works. Models available to it (first 15):"
         python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); ids=sorted(m.get("id") or m.get("name","") for m in d.get("data", d.get("models", []))); print("\n".join("  "+i for i in ids[:15]), f"\n  ({len(ids)} in total)")' "$body" ;;
    401|403) echo "$provider rejected the key (HTTP $code): check it, then run $0 $provider again." >&2; exit 1 ;;
    429) echo "Key accepted but rate-limited or out of quota (HTTP 429): check billing/limits for this key." >&2; exit 1 ;;
    *) echo "Could not verify (HTTP $code; network or provider problem)." >&2; exit 1 ;;
  esac
}

if [ "${2:-}" = "--check" ]; then check; exit 0; fi

[ -t 0 ] || { echo "Run this in a terminal: the key is read from a hidden prompt." >&2; exit 2; }
printf 'Paste your %s and press Enter (input is hidden): ' "$var"
IFS= read -rs key; echo
key="${key//[[:space:]]/}"
[ -n "$key" ] || { echo "Nothing entered; no changes made." >&2; exit 1; }
if [ "$provider" = openai ] && [[ "$key" != sk-* ]]; then
  echo "That does not look like an OpenAI key (they start with 'sk-'); no changes made." >&2; exit 1
fi
umask 077
mkdir -p "$dir" && chmod 700 "$dir"
tmp=$(mktemp "$dir/.tmp.XXXXXX")
printf '%s=%s\n' "$var" "$key" > "$tmp"
unset key
chmod 600 "$tmp" && mv -f "$tmp" "$file"   # replaces any previous key in one step
echo "Saved to $file (only you can read it). The key was not displayed."
echo "Next: $0 $provider --check"
