<img src="branding/logo-512.png" width="120" align="right" alt="CloudStream Asian logo">

# CloudStream Asian

[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/latrixz)
[![Telegram](https://img.shields.io/badge/Telegram-Join%20csasian-26A5E4?logo=telegram&logoColor=white)](https://t.me/csasian)

A [CloudStream](https://github.com/recloudstream/cloudstream) extension repository with Chinese and Asian drama sources, plus English ones.

## Install

In CloudStream: **Settings → Extensions → Add repository**, then enter the short code:

```
csasian
```

Or the full link:

```
https://raw.githubusercontent.com/Latrixz/cloudstream-asian/main/repo.json
```

Then open the repository and install the sources you want.

## Community

Join the Telegram group, **[t.me/csasian](https://t.me/csasian)**, to:

- request new sources
- hear when a site breaks or a new source is added
- report broken links and get help

## Support

If these sources are useful to you, you can support their upkeep:

<a href="https://buymeacoffee.com/latrixz" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" height="50"></a>

## Sources

| Source | Site | Language |
|---|---|---|
| Olevod | olevod.com | Chinese |
| Kubb | kubb.tv | Chinese |
| UVOD | uvod.tv | Chinese |
| Mvffm (Movieffm) | mvffm.net | Chinese |
| Len | len.tv | Chinese |
| Huavod | huavod.com | Chinese |
| Ddys | ddys.io | Chinese |
| Duboku | dbku.tv | Chinese |
| Xiaobao | xiaobaotv.com | Chinese |
| Huarw (华人影院) | huarw.com | Chinese |
| Nbyy (泥巴影院) | nbyy.cc | Chinese |
| Haotv (华视界) | haotv.app | Chinese |
| Suikan (随便看) | suikan365.vip | Chinese |
| Aigua (爱瓜TV) | aigua9.com | Chinese |
| Trjvod (唐人街) | trjvod.com | Chinese |
| Csxyy (CN影院) | csxyy.com | Chinese (spliced ads removed) |
| Qwfilm (七味网) | qwfilm.com | Chinese |
| Kpkuang (看片狂人) | kpkuang.us | Chinese |
| Nangua (南瓜影视) | nangua1.tv | Chinese (incl. short dramas) |
| Ncat (网飞猫) | ncat30.com | Chinese |
| Kkys (可可影视) | kkys14.com | Chinese |
| Netflixgc (奈飞工厂) | netflixgc.com | Chinese (Netflix and Western titles) |
| Kankanya (看看鸭) | kankanya.me | Chinese |
| Gqc (共青春影院) | gqc7.top | Chinese |
| Darkvod (黑夜影院) | darkvod.com | Chinese |
| Mujizy (母鸡资源) | mujiziyuan.com | Chinese |
| Cilixiong (磁力熊) | cilixiong.org | Chinese (top-rated movies and series, with magnet links) |
| Kissasian | kissasian.ng | English (Asian dramas) |
| Crackle | crackle.beauty | English |
| Flixvision | flixvision.st | English |
| Framextv (frameXTV) | framextv.cc | English |
| Rivestream | rivestream.app | English (movies, TV, K-drama, anime) |
| Rivo | rivo.st | English |
| Movy | movy.sx | English |
| OneTouchTV (OneTouchTV Asia) | onetouchtv.xyz | English (Asian dramas, 20+ subtitle languages) |
| Kisskh (Kisskh Asia) | kisskh.co | English (Asian dramas, movies, anime) |
| Donghuastream (Donghuastream CS) | donghuastream.org | Chinese animation (donghua) |
| AniKoto (AniKoto CS) | anikototv.bz | Anime (sub and dub) |
| AnimeSuge (AnimeSuge CS) | animesuge.cz | Anime (sub and dub) |
| FourKHDHub (4KHDHub CS) | 4khdhub.one | English, Hindi, Korean movies and series |
| Movies321 (321Movies CS) | 321movies.xyz | English (movies, TV, K-drama, anime; plays in CloudStream) |
| Cinejoy (Cinejoy CS) | cinejoy.pk | English (movies and TV, up to 4K HDR) |
| Dm233 (233动漫 Dm233) | 233dm.com | Chinese-subbed anime (日漫, 国漫, movies, tokusatsu) |
| Zxcstream (ZXCStream CS) | zxcstream.com | English (movies and TV, up to 4K, dubbed audio) |
| Reelfren (ReelFren Shorts) | reelfren.net | English short dramas (ReelShort, DramaBox, ShortMax and 40+ apps) |
| Chartdrama (ChartDrama Shorts) | chartdrama.com | English short dramas (GoodShort, StarShort, DramaTV and 30+ apps) |
| Dramatip (DramaTip Shorts) | dramatip.net | English short dramas (YourChannel, ShortWave, MyDramaWave and 70+ apps) |
| Dramafren (DramaFren) | v2.dramafren.org | Chinese short dramas with English subtitles, plus DramaBox |
| LiveTv (直播 Live TV) | iptv-org.github.io | Live TV (free channels) |
| AsianHome (CS Asian 首页) | — | Combined home screen and search across your sources |

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
