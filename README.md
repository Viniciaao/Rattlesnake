# Rattlesnake

A CLEO mod for the classic PC version of **Grand Theft Auto: San Andreas**. It adds rattlesnakes to the Las Venturas desert. An optional cobra can also be enabled in Los Santos. Snakes react to nearby entities, attack CJ at close range, and can be killed or run over.

## Requirements

- Classic GTA San Andreas for Windows (the original PC release)
- ASI Loader and Mod Loader
- CLEO 4
- CLEO+ 1.2.0 or newer (`LOAD_SPECIAL_MODEL` support is required)
- NewOpcodes

The current [SA] Essentials package provides the usual base setup: <https://www.mixmods.com.br/2019/06/sa-essentials-pack/>

This mod is **not** made for Android/iOS or GTA San Andreas – The Definitive Edition.

## Installation

1. Install the requirements above.
2. Copy the complete `Rattlesnake` folder into the game's `modloader` directory. Keep `cleo`, `ModelsQa`, and `SoundsQa` together.
3. Fully close and reopen the game. Do not rely on a CLEO hot reload after installing or changing the cobra setting.

Do not rename the packaged DFF, TXD, or MP3 files. Their exact paths and letter casing are validated by the scripts and release tooling.

## Configuration

Edit `cleo/Snake.ini`:

```ini
[Settings]
Volume = 0.6
Cobra = 0
```

- `Volume`: audio level from `0.0` to `1.0`. The scripts clamp out-of-range values.
- `Cobra`: `1` enables the optional Los Santos cobra; `0` disables it. It is disabled by default because cobras are not native to North America.

Close and reopen the game after changing `Cobra`.

`cleo/SnakeModels.ini` is an automatically generated, process-local cache for CLEO+ special-model handles. Do not edit it or copy populated values between installations.

## Animation design

Each snake deliberately uses **ten separate static DFF models as animation frames**. CLEO+ attaches these render objects to an invisible base object and changes their visibility. This avoids reserving/replacing a dedicated GTA model ID. The files must not be merged into one animated model or removed as duplicates.

## Troubleshooting

If the game crashes, first verify that all requirements and all files from the mod are present. Useful diagnostics include:

- [SCRLog](https://www.mixmods.com.br/2020/10/iii-vc-sa-scrlog-2020-3-descobrir-crash-em-mod-cleo/)
- [CrashInfo](https://www.mixmods.com.br/2022/09/crashinfo/)

A missing or invalid special model now causes the affected script to stop safely instead of continuing with an invalid render handle.

## Source validation and build

The repository includes a dependency-free validator:

```bash
python3 tools/validate_release.py --sources-only
python3 tools/validate_release.py
```

The first command checks scripts and assets. The second also verifies that each committed `.cs` contains source metadata matching its `.txt` source. `.github/workflows/build-cleo.yml` downloads the official Sanny Builder 4.2.0 archive, verifies its pinned SHA-256, compiles both scripts on Windows, validates the release, and uploads the resulting `.cs` files.

See [`AUDITORIA_TECNICA.md`](AUDITORIA_TECNICA.md) for the technical review, implemented corrections, residual limitations, and in-game test matrix.

## Credits

- Script: ArtemQa146
- Models, animation frames, and sounds: Tarzan_3

Original author links:

- <https://libertycity.net/user/Artem.1.9.9.6/>
- <https://libertycity.ru/user/Artem.1.9.9.6/>
- <https://www.gtainside.com/user/ArtemQa146>
- <https://youtube.com/@ArtemQa146>
