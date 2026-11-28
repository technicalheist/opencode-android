# Translating the app

The app's text lives in Android string resources, so adding a language means adding translated
copies of the XML files. No Kotlin changes are needed.

## Where the text is

Each module has its English text in `src/main/res/values/`:

| File | What it holds |
| --- | --- |
| `core/…/strings_common.xml` | Words used across the app: Cancel, Done, Retry, … |
| `core/…/strings.xml` | Shared text, plus `core_language_tag` (see below) |
| `server/…/strings.xml` | Connection and server error messages |
| `app/…/strings.xml` | The connection screen and server form |
| `feature/<name>/…/strings.xml` | That feature's screens |
| `feature/settings/…/strings_onboarding.xml` | The welcome screens and the What’s New page |
| `feature/settings/…/strings_whatsnew_entries.xml` | Every What’s New entry |

`python3 scripts/translations.py` lists every file.

## Languages

The app is translated into the languages the OpenCode desktop app supports, except the
right-to-left ones. `LANGUAGES` in `scripts/translations.py` lists them with their folder
qualifiers. A few differ from the desktop's codes: `zh-rCN` and `zh-rTW` (desktop `zh`, `zht`),
`pt-rBR` (`br`), `nb` (`no`) and `in` (Indonesian, `id`: Android's folder name is the old code).
Serbian is in Cyrillic, as on desktop.

## Translate with a model

`scripts/translate.py` fills in missing strings with a model through `opencode run`
([install OpenCode](https://opencode.ai)). By default it uses the Anomaly Console models
(`opencode/…`), and deletes each call's session once it has the answer:

```sh
python3 scripts/translate.py all          # every language, only strings with no translation
python3 scripts/translate.py de fr        # some languages
python3 scripts/translate.py --review de  # a second model checks and fixes existing text
python3 scripts/translate.py all --redo session_shell_stop  # translate a changed string again
python3 scripts/translations.py all       # then check every language
```

After adding or changing English text, run `translate.py all` to translate just the new strings;
it also drops translations of strings removed from English. When you change the meaning of an
English string, pass its name with `--redo`, since the old translations would otherwise stay. A
typo fix in English doesn't need `--redo`.

Each request carries the desktop app's translations of the terms it uses and its locale
glossary, so words like session, agent and model read as they do on desktop. The script reads
them from the public [opencode repo](https://github.com/anomalyco/opencode) on GitHub. If GitHub
can't be reached it still runs and prints a note, but the words may then drift from desktop's,
so run it again once you're online. Answers are checked like
`scripts/translations.py` checks the files; a string that keeps failing is left out and shows in
English.

To fix one string by hand, edit the `values-<code>` file directly; the scripts only fill gaps
(and `--review` only touches strings it finds wrong).

### Without Anomaly Console access

The default models need Anomaly Console access. Without it, either:

- pick your own model: any `provider/model` that `opencode run -m` accepts, with `--model` for
  translating and `--review-model` for reviewing (`opencode models` lists yours), for example
  `python3 scripts/translate.py all --model <provider>/<model>`; or
- leave the new strings untranslated and say so in the pull request, and a maintainer will
  translate them.

### CI

`scripts/check.sh` runs `python3 scripts/translations.py all` first, so CI fails when a language is
missing a string, still has a string removed from English, or has placeholders, plurals or
escaping that don't match the English. The failure lists each problem and the command that fixes
it. Text that only looks untranslated is a warning. The check needs only Python 3 and takes a
couple of seconds.

## Add a language

1. For each English file, create the same file name in a `values-<code>` folder next to `values`,
   for example `feature/session/src/main/res/values-de/strings.xml`. Use the language code Android
   uses for folder names: `de`, `fr`, `ja`, `pt-rBR`, `zh-rCN`.
2. Translate the text between the tags. Keep each `name`, every placeholder (`%1$s`, `%2$d`) and
   the escaping (`\'`, `\n`, `&amp;`) as they are. Placeholders may move within the sentence.
   Leave out entries marked `translatable="false"`.
3. In `core/src/main/res/values-<code>/strings.xml`, set `core_language_tag` to the language's
   code (`de`, `pt-BR`). Dates and numbers follow it.
4. Add the code to `appLanguages` in `app/build.gradle.kts` (`"de"`, `"pt-rBR"`).
5. Run `python3 scripts/translations.py <code>`. It lists strings that are missing, strings that
   no longer exist in English, and translations whose placeholders, plurals or escaping are wrong.
6. Run `scripts/check.sh`, then try it: install the app and pick the language under the phone's
   Settings › Apps › OpenCode › Language (Android 13 and up), or switch the phone's language.

At runtime a string with no translation shows in English, but CI requires every listed language
to be complete (see CI above).

## Plurals

Counts use `<plurals>`. English has `one` and `other`; give each language the forms it needs
(`zero`, `one`, `two`, `few`, `many`, `other`). `other` is always required.

## Writing new text

- Put it in the module's `strings.xml`, never in a Kotlin literal.
- In a composable read it with `stringResource` / `pluralStringResource`. Anywhere else (reducers,
  models, enums, errors) use `localized(R.string.x)` / `localizedPlural(R.plurals.x, count, count)`
  from `ai.opencode.core.strings`.
- Call `localized` inside a function or a `get()`, never in a `const val`, a top-level `val` or an
  enum constructor argument: those run once and keep the language the app started in.
- One resource per sentence. Don't build a sentence from translated pieces, and don't compare,
  store or send translated text: use an ID or an enum for that.
- `localized` isn't checked by lint: the number and type of arguments must match the placeholders.
- Format dates and numbers a person reads with `appFormattingLocale()`.

## What stays English

Logs, model prompts and tool descriptions, text sent to a model, exported Markdown, developer-only
screens (Developer Mode, diagnostics, playgrounds) and brand names.

## Known limits

- Text already held in app state when the language changes (an open alert, a loaded list) keeps
  the old language until that state is rebuilt or the app restarts.
- Voice calls transcribe and speak English only.
- Right-to-left layout hasn't been checked.
