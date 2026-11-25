# CloudStream Asian

A [CloudStream](https://github.com/recloudstream/cloudstream) extension repository with Chinese and Asian drama sources, plus English ones.

## Install

In CloudStream: **Settings → Extensions → Add repository**, then enter:

```
https://raw.githubusercontent.com/Latrixz/cloudstream-asian/main/repo.json
```

Then open the repository and install the sources you want.

## Sources

| Source | Site | Language |
|---|---|---|
| Olevod | olevod.com | Chinese |
| Kubb | kubb.tv | Chinese |
| Mvffm (Movieffm) | mvffm.net | Chinese |
| Len | len.tv | Chinese |
| Huavod | huavod.com | Chinese |
| Ddys | ddys.io | Chinese |
| Duboku | dbku.tv | Chinese |
| Xiaobao | xiaobaotv.com | Chinese |
| Kissasian | kissasian.ng | English (Asian dramas) |
| Crackle | crackle.beauty | English |
| Flixvision | flixvision.st | English |

## Branches

- `code`: source. Pushing here builds every extension with GitHub Actions.
- `main`: built `.cs3` files, `plugins.json` and `repo.json` (what CloudStream installs from).

## Development

Requires JDK 17 and the Android SDK.

```bash
./gradlew make makePluginsJson
node scripts/<source>-smoke-test.mjs   # live checks against each site
```

These extensions only read what each website already shows publicly. This repository does not host any media.
