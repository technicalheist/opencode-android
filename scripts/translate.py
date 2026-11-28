#!/usr/bin/env python3
"""Fills in missing translations with a model, then has a second model review them.

Usage:
  scripts/translate.py de fr          translates what `values-de` and `values-fr` are missing
  scripts/translate.py all            every language in LANGUAGES (scripts/translations.py)
  scripts/translate.py --review de    asks the review model to fix wrong translations
  scripts/translate.py all --redo session_shell_stop
                                      translates these strings again, after their English changed

Only strings with no translation are sent, so a run after new English text is added translates
just that, and strings gone from English are dropped. Each request carries the desktop app's own
translations of the terms it uses and its translators' glossary, read from the public opencode
repo on GitHub (github.com/anomalyco/opencode), plus the app's existing translations, so a word
reads the same everywhere. If GitHub can't be reached it still runs, with a note, but words like
session, agent and model may then differ from the desktop app. Every answer is checked like
scripts/translations.py checks the files; a string that fails is asked for again, and left out
if it still fails.

Models run through `opencode run` (install it from https://opencode.ai). The defaults use the
Anomaly Console provider (`opencode/…`). Without Console access, pass any model `opencode run -m`
accepts: `--model <provider/model>` to translate, `--review-model <provider/model>` to review.
Or leave the strings for a maintainer to translate; CI flags them as missing.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from xml.sax.saxutils import escape

from translations import (
    LANGUAGES,
    ROOT,
    english_files,
    plural_quantities,
    problems,
    read_resources,
    read_strings,
    translated_path,
)

TRANSLATE_MODEL = "opencode/gemini-4-neon#high"
REVIEW_MODEL = "opencode/gemini-4-neon#high"
DESKTOP_URL = "https://raw.githubusercontent.com/anomalyco/opencode/dev"
DESKTOP_FOLDERS = ["packages/app/src/runtime/i18n", "packages/ui/src/i18n"]
CHUNK_SIZE = 60
ATTEMPTS = 3

# Files the shared words come from go first, so later requests can reuse their translations.
FILE_ORDER = [
    "core/src/main/res/values/strings_common.xml",
    "core/src/main/res/values/strings.xml",
    "server/src/main/res/values/strings.xml",
    "app/src/main/res/values/strings.xml",
]

# Terms every request gets the desktop's translation of, whether or not its strings use them.
CORE_TERMS = [
    "Session", "Sessions", "New session", "Server", "Servers", "Agent", "Model", "Models",
    "Project", "Projects", "Workspace", "Tool", "Permission", "Prompt", "Archive", "Settings",
    "Provider", "Subagent", "Message", "File", "Files", "Folder", "Terminal", "Shell", "Command",
    "Share", "Fork", "Undo", "Retry", "Cancel", "Copy", "Delete", "Rename", "Connect",
]

RULES = """You translate the text of OpenCode for Android, a phone app for controlling the
OpenCode coding agent running on a computer, into {name} ({tag}).

Rules:
- Translate the meaning naturally, as a native {name} app would say it; don't translate word
  for word. Keep the tone short, plain and friendly.
- Use the same terms as the OpenCode desktop app: the "Desktop terms" below are its
  translations. Use the "Already translated in this app" strings the same way.
- Keep names untranslated and spelled as written: OpenCode, model and provider names
  (Claude, GPT, Gemini), Git, GitHub, Tailscale, MCP, LSP, SSH, URL, QR, macOS, Linux.
  Keep commands, code, file paths and anything that looks like an identifier exactly as written
  (for example `opencode pair --remote`).
- Technical words keep their computing meaning: a "shell" is a command-line shell running a
  command (use the usual computing term, or keep "shell"), never a seashell or a company.
- Keep every placeholder exactly as written: %1$s, %2$d, %d, %s, %%. You may move them within
  the sentence. Never add or remove one, and never write a bare % (write %%).
- Keep line breaks (\\n) where the English has them.
- English uses Title Case for titles and buttons. Don't copy that: use {name}'s normal
  capitalization for UI text.
- Keep translations about as long as the English; buttons, labels and titles must stay short.
- "context" says where a string appears. It isn't part of the text.
- A plural lists the quantities {name} needs ({quantities}). Write each form, with the number
  placeholder in every form, following {name}'s plural rules.
- Write {name} in {script} script.

Reply with only one JSON object, no code fences and no commentary: each string's name mapped to
its translation, and each plural's name mapped to an object of quantity -> translation."""

REVIEW_RULES = """You review the {name} ({tag}) translation of OpenCode for Android, a phone
app for controlling the OpenCode coding agent running on a computer.

For each string, compare the English with the translation. Report only real mistakes:
- the meaning is wrong or something is missing
- it is still in English, or in the wrong script (it must be {name} in {script} script)
- it uses a different term than the "Desktop terms" below, which the OpenCode desktop app uses
- it is clearly unnatural or ungrammatical {name}
- a technical word lost its computing meaning (a "shell" is a command-line shell, never a
  seashell)
Don't report style preferences. Names (OpenCode, Claude, GPT, Git, MCP, …), commands, code and
placeholders (%1$s, %d, %%) stay as written; keep every placeholder in your fix.

Reply with only one JSON object, no code fences and no commentary, mapping each wrong string's
name to its corrected translation (for a plural, an object of quantity -> translation). Reply
{{}} if nothing is wrong."""

AGENT = """---
description: Translates the OpenCode Android app's strings
mode: primary
permission:
  "*": deny
---
You translate and review user interface text. Reply with only the JSON the request asks for.
"""

print_lock = threading.Lock()


def log(message):
    with print_lock:
        print(message, flush=True)


def android_to_plain(text):
    """Turns the text as written in strings.xml into the text a person reads."""
    replacements = {"n": "\n", "t": "\t", "'": "'", '"': '"', "@": "@", "?": "?", "\\": "\\"}

    def unescape(match):
        return replacements.get(match.group(1), match.group(1))

    return re.sub(r"\\(.)", unescape, text)


def plain_to_android(text):
    """Turns the text a person reads into the text to write in strings.xml."""
    text = text.replace("\\", "\\\\")
    text = text.replace("'", "\\'")
    text = text.replace('"', '\\"')
    text = text.replace("\n", "\\n")
    text = text.replace("\t", "\\t")
    if text.startswith("@") or text.startswith("?"):
        text = "\\" + text
    return text


def plain_item(item):
    if item["kind"] == "string":
        return android_to_plain(item["text"])
    forms = {}
    for quantity, text in item["items"].items():
        forms[quantity] = android_to_plain(text)
    return forms


def item_from_answer(english, answer):
    """Builds a string or plural in strings.xml form from a model's answer, or returns None."""
    if english["kind"] == "string":
        if not isinstance(answer, str):
            return None
        return {"kind": "string", "name": english["name"], "translatable": True, "text": plain_to_android(answer)}
    if not isinstance(answer, dict):
        return None
    items = {}
    for quantity, text in answer.items():
        if not isinstance(text, str):
            return None
        items[quantity] = plain_to_android(text)
    return {"kind": "plurals", "name": english["name"], "translatable": True, "items": items}


def write_translation(english_path, qualifier, translations):
    """Writes values-<qualifier>/<file> with the English file's order, comments and blank lines."""
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for item in read_resources(english_path):
        if item["kind"] != "comment":
            if not item["translatable"] or item["name"] not in translations:
                continue
        if item["blank_before"] and len(lines) > 2:
            lines.append("")
        if item["kind"] == "comment":
            lines.append(f"    <!--{item['text']}-->")
            continue
        translated = translations[item["name"]]
        if translated["kind"] == "string":
            lines.append(f'    <string name="{item["name"]}">{escape(translated["text"])}</string>')
            continue
        lines.append(f'    <plurals name="{item["name"]}">')
        for quantity in ["zero", "one", "two", "few", "many", "other"]:
            if quantity in translated["items"]:
                text = escape(translated["items"][quantity])
                lines.append(f'        <item quantity="{quantity}">{text}</item>')
        lines.append("    </plurals>")
    while lines[-1].strip().startswith("<!--") or lines[-1] == "":
        lines.pop()
    lines.append("</resources>")
    path = translated_path(english_path, qualifier)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n")


desktop_files = {}
desktop_lock = threading.Lock()


def fetch_desktop(path):
    """Returns one file of the opencode repo from GitHub, "" if it doesn't exist, or None if the
    fetch failed. Each file is fetched once per run."""
    with desktop_lock:
        if path not in desktop_files:
            try:
                with urllib.request.urlopen(f"{DESKTOP_URL}/{path}", timeout=30) as response:
                    desktop_files[path] = response.read().decode("utf-8")
            except urllib.error.HTTPError as error:
                desktop_files[path] = "" if error.code == 404 else None
            except (urllib.error.URLError, OSError, UnicodeDecodeError):
                desktop_files[path] = None
        return desktop_files[path]


def read_desktop(path):
    """Returns key -> text for one desktop i18n file, or {} when it can't be read."""
    text = fetch_desktop(path)
    if not text:
        return {}
    entries = {}
    for match in re.finditer(r'"([^"\n]+)"\s*:\s*"((?:[^"\\\n]|\\.)*)"', text):
        entries[match.group(1)] = match.group(2).replace('\\"', '"')
    return entries


def desktop_terms(code):
    """Returns English -> the desktop's translations of its short UI text (up to three words)."""
    english = {}
    translated = {}
    for folder in DESKTOP_FOLDERS:
        english.update(read_desktop(f"{folder}/en.ts"))
        translated.update(read_desktop(f"{folder}/{code}.ts"))
    terms = {}
    for key, text in english.items():
        if key not in translated or "{{" in text or len(text.split()) > 3:
            continue
        if translated[key] == text and not text.isupper():
            continue
        counts = terms.setdefault(text, {})
        counts[translated[key]] = counts.get(translated[key], 0) + 1
    result = {}
    for text, counts in terms.items():
        ranked = sorted(counts, key=lambda translation: -counts[translation])
        result[text] = ranked[:2]
    return result


def desktop_guidance(code):
    """The desktop translators' notes for the language (.opencode/glossary), if there are any."""
    names = {"zh": "zh-cn", "zht": "zh-tw"}
    return fetch_desktop(f".opencode/glossary/{names.get(code, code)}.md") or ""


def word_stems(text):
    stems = set()
    for word in re.findall(r"[A-Za-z]{3,}", text.lower()):
        stems.add(word.rstrip("s"))
    return stems


def relevant_terms(terms, chunk_text, limit):
    """The terms whose words appear in the chunk's English, shortest first, plus CORE_TERMS."""
    stems = word_stems(chunk_text)
    picked = []
    for text in sorted(terms, key=len):
        if text in CORE_TERMS or word_stems(text) & stems:
            picked.append(text)
        if len(picked) >= limit:
            break
    return picked


def call_model(model, prompt, workdir):
    """Sends one prompt through `opencode run` and returns the JSON object it answers with."""
    command = ["opencode", "run", "--agent", "translator", "--archived", "--format", "json", "-m", model]
    result = subprocess.run(
        command + ["Follow the instructions below."],
        input=prompt,
        capture_output=True,
        text=True,
        cwd=workdir,
        # OpenCode takes the project directory from PWD, which `cwd` alone doesn't change.
        env={**os.environ, "PWD": workdir},
        timeout=900,
    )
    output = ""
    session = None
    for line in result.stdout.splitlines():
        event = json.loads(line)
        session = event["sessionID"]
        if event["type"] == "text":
            output += event["part"]["text"]
    if session:
        # A one-off call shouldn't stay in the person's session list.
        subprocess.run(["opencode", "session", "delete", session], capture_output=True)
    start = output.find("{")
    end = output.rfind("}")
    if start < 0 or end < start:
        raise ValueError(f"no JSON in the answer: {result.stdout[-300:]!r} {result.stderr[-300:]!r}")
    return json.loads(output[start : end + 1])


def language_info(qualifier):
    for language in LANGUAGES:
        if language[0] == qualifier:
            return language
    raise SystemExit(f"{qualifier} isn't in LANGUAGES (scripts/translations.py)")


def ordered_files():
    files = english_files()
    first = [ROOT / path for path in FILE_ORDER if ROOT / path in files]
    rest = [path for path in files if path not in first]
    return first + rest


def contexts(english_path):
    """name -> the comment that heads its group in the English file."""
    result = {}
    current = ""
    for item in read_resources(english_path):
        if item["kind"] == "comment":
            current = item["text"].strip()
        else:
            result[item["name"]] = current
    return result


def existing_translations(qualifier):
    """English text -> this app's translation, for short strings already translated."""
    result = {}
    for english_path in english_files():
        path = translated_path(english_path, qualifier)
        if not path.exists():
            continue
        english = read_strings(english_path)
        translated = read_strings(path)
        for name, item in translated.items():
            if name not in english or item["kind"] != "string":
                continue
            text = android_to_plain(english[name]["text"])
            if len(text.split()) <= 4 and name != "core_language_tag":
                result[text] = android_to_plain(item["text"])
    return result


def build_prompt(rules, language, chunk, english, context, terms, guidance, existing, review_of=None):
    qualifier, code, tag, name, script = language
    quantities = ", ".join(plural_quantities(qualifier))
    parts = [rules.format(name=name, tag=tag, script=script, quantities=quantities)]
    chunk_text = " ".join(json.dumps(plain_item(english[key])) for key in chunk)
    picked = relevant_terms(terms, chunk_text, 150)
    if picked:
        parts.append("Desktop terms (English → desktop translation):")
        for text in picked:
            parts.append(f"- {text} → {' / '.join(terms[text])}")
    if guidance:
        parts.append("Notes from the desktop app's translators:\n" + guidance)
    if existing and review_of is None:
        stems = word_stems(chunk_text)
        shown = 0
        lines = []
        for text in sorted(existing, key=len):
            if word_stems(text) & stems:
                lines.append(f"- {text} → {existing[text]}")
                shown += 1
            if shown >= 120:
                break
        if lines:
            parts.append("Already translated in this app (English → translation):\n" + "\n".join(lines))
    entries = []
    for key in chunk:
        entry = {"name": key}
        if context.get(key):
            entry["context"] = context[key]
        if english[key]["kind"] == "string":
            entry["english"] = plain_item(english[key])
        else:
            entry["english_plural"] = plain_item(english[key])
            entry["quantities"] = plural_quantities(qualifier)
        if review_of is not None:
            entry["translation"] = plain_item(review_of[key])
        entries.append(entry)
    parts.append("Strings:\n" + json.dumps(entries, ensure_ascii=False, indent=1))
    return "\n\n".join(parts)


def translate_file(language, english_path, terms, guidance, model, workdir, review, redo):
    qualifier, code, tag, name, script = language
    english = read_strings(english_path)
    path = translated_path(english_path, qualifier)
    translations = {}
    if path.exists():
        translations = read_strings(path)
    for key in list(translations):
        if key not in english or key in redo:
            del translations[key]
    if "core_language_tag" in english:
        translations["core_language_tag"] = {"kind": "string", "name": "core_language_tag", "translatable": True, "text": tag}
    context = contexts(english_path)
    relative = english_path.relative_to(ROOT)

    if review:
        pending = [key for key in english if key in translations and key != "core_language_tag"]
    else:
        pending = [key for key in english if key not in translations]
    changed = 0
    for attempt in range(ATTEMPTS):
        if not pending:
            break
        failed = []
        for start in range(0, len(pending), CHUNK_SIZE * (2 if review else 1)):
            chunk = pending[start : start + CHUNK_SIZE * (2 if review else 1)]
            existing = existing_translations(qualifier)
            rules = REVIEW_RULES if review else RULES
            review_of = translations if review else None
            prompt = build_prompt(rules, language, chunk, english, context, terms, guidance, existing, review_of)
            try:
                answer = call_model(model, prompt, workdir)
            except Exception as error:
                log(f"{qualifier} {relative}: request failed ({error}); will retry")
                if not review:
                    failed.extend(chunk)
                continue
            for key in chunk:
                if key not in answer:
                    if not review:
                        failed.append(key)
                    continue
                item = item_from_answer(english[key], answer[key])
                if item is None:
                    found = ["isn't a string or plural"]
                else:
                    errors, warnings = problems(english[key], item, qualifier, script)
                    found = errors + warnings
                if found:
                    log(f"{qualifier} {relative}: {key} rejected: {found[0]}")
                    if not review:
                        failed.append(key)
                    continue
                if review and item.get("text") == translations[key].get("text") and item.get("items") == translations[key].get("items"):
                    continue
                translations[key] = item
                changed += 1
            write_translation(english_path, qualifier, translations)
        if review:
            break
        pending = failed
    if pending and not review:
        log(f"{qualifier} {relative}: {len(pending)} still missing")
    write_translation(english_path, qualifier, translations)
    return changed


def has_desktop():
    for folder in DESKTOP_FOLDERS:
        if not fetch_desktop(f"{folder}/en.ts"):
            return False
    return True


def run_language(language, use_desktop, model, workdir, review, redo):
    qualifier, code = language[0], language[1]
    terms = {}
    guidance = ""
    if use_desktop:
        terms = desktop_terms(code)
        guidance = desktop_guidance(code)
        if not terms:
            log(f"{qualifier}: no desktop translations found at {DESKTOP_URL}")
    total = 0
    for english_path in ordered_files():
        total += translate_file(language, english_path, terms, guidance, model, workdir, review, redo)
    log(f"{qualifier}: {'fixed' if review else 'translated'} {total} strings")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("languages", nargs="+", help="folder qualifiers (de, pt-rBR) or all")
    parser.add_argument("--review", action="store_true", help="review existing translations instead")
    parser.add_argument("--model", default=TRANSLATE_MODEL, metavar="PROVIDER/MODEL", help="the model that translates (default: %(default)s)")
    parser.add_argument("--review-model", default=REVIEW_MODEL, metavar="PROVIDER/MODEL", help="the model that reviews (default: %(default)s)")
    parser.add_argument("--jobs", type=int, default=12, help="languages to translate at once")
    parser.add_argument("--redo", action="append", default=[], metavar="NAME", help="translate this string again (repeatable)")
    args = parser.parse_args()

    if args.languages == ["all"]:
        languages = LANGUAGES
    else:
        languages = [language_info(qualifier) for qualifier in args.languages]
    model = args.review_model if args.review else args.model
    if shutil.which("opencode") is None:
        print("opencode isn't installed; get it from https://opencode.ai (docs/translating.md).")
        return 1
    use_desktop = has_desktop()
    if not use_desktop:
        print(f"Note: couldn't read the desktop app's translations from {DESKTOP_URL}, so desktop terms "
              "are skipped and words like session, agent and model may differ from the desktop app. "
              "Check the connection to GitHub and run again; if it keeps failing, the files may have "
              "moved and DESKTOP_URL or DESKTOP_FOLDERS in this script need updating.")

    with tempfile.TemporaryDirectory(prefix="translate-") as workdir:
        agent = Path(workdir) / ".opencode" / "agent" / "translator.md"
        agent.parent.mkdir(parents=True)
        agent.write_text(AGENT)
        # OpenCode reads project agents only inside a repository.
        subprocess.run(["git", "init", "-q", workdir], check=True)
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            futures = []
            for language in languages:
                futures.append(pool.submit(run_language, language, use_desktop, model, workdir, args.review, set(args.redo)))
            for future in futures:
                future.result()
    return 0


if __name__ == "__main__":
    sys.exit(main())
