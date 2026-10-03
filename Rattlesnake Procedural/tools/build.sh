#!/usr/bin/env bash
#
# Compila cleo/Snake_Procedural.sc com o gta3sc (GTA3Script).
#
#   tools/build.sh                 compila em cleo/Snake_Procedural.cs
#   tools/build.sh --verify        compila num arquivo temporario, roda o
#                                  validador e falha se o .cs commitado estiver
#                                  desatualizado (usado pela CI)
#   tools/build.sh --output ARQ    grava o bytecode em ARQ
#
# Variaveis de ambiente:
#   GTA3SC      caminho de um gta3sc ja compilado (pula o download/build)
#   GTA3SC_DIR  diretorio onde o gta3sc sera clonado/compilado
#
set -euo pipefail

# Revisao do gta3sc usada para compilar o script (nao atualize sem testar).
GTA3SC_REVISION="e9b4c3035c77b013f57af8595bc76b777acf73f6"

PACKAGE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$PACKAGE_DIR/cleo/Snake_Procedural.sc"
TARGET="$PACKAGE_DIR/cleo/Snake_Procedural.cs"
WORK_DIR="${GTA3SC_DIR:-${TMPDIR:-/tmp}/gta3sc-$GTA3SC_REVISION}"

OUTPUT="$TARGET"
VERIFY=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --verify) VERIFY=1; shift ;;
        --output) OUTPUT="$2"; shift 2 ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        *) echo "argumento desconhecido: $1" >&2; exit 2 ;;
    esac
done

if [[ -z "${GTA3SC:-}" ]]; then
    GTA3SC="$WORK_DIR/build/gta3sc"
    if [[ ! -x "$GTA3SC" ]]; then
        command -v cmake >/dev/null || { echo "erro: cmake nao encontrado" >&2; exit 1; }
        command -v git   >/dev/null || { echo "erro: git nao encontrado" >&2; exit 1; }
        if [[ ! -d "$WORK_DIR/.git" ]]; then
            echo "==> clonando gta3sc ($GTA3SC_REVISION)"
            git clone --quiet https://github.com/thelink2012/gta3sc "$WORK_DIR"
        fi
        git -C "$WORK_DIR" fetch --quiet --depth 1 origin "$GTA3SC_REVISION" || true
        git -C "$WORK_DIR" checkout --quiet "$GTA3SC_REVISION"
        echo "==> compilando gta3sc"
        cmake -S "$WORK_DIR" -B "$WORK_DIR/build" \
            -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_POLICY_VERSION_MINIMUM=3.5 >/dev/null
        cmake --build "$WORK_DIR/build" --parallel >/dev/null
    fi
fi

if [[ ! -x "$GTA3SC" ]]; then
    echo "erro: compilador gta3sc nao encontrado em $GTA3SC" >&2
    exit 1
fi

if [[ "$VERIFY" -eq 1 && "$OUTPUT" == "$TARGET" ]]; then
    OUTPUT="$(mktemp -t snake-procedural-XXXXXX.cs)"
fi

echo "==> compilando $(basename "$SOURCE")"
# O --add-config comecando por "./" e resolvido a partir do diretorio atual.
cd "$PACKAGE_DIR"
"$GTA3SC" compile "cleo/Snake_Procedural.sc" \
    --config=gtasa \
    --cs \
    --guesser \
    -fno-entity-tracking \
    -fbreak-continue \
    --add-config="./tools/cleo_plus_commands.xml" \
    -o "$OUTPUT"

if [[ "$OUTPUT" != "$TARGET" ]]; then
    echo "==> bytecode em $OUTPUT"
fi

if [[ "$VERIFY" -eq 1 ]]; then
    echo "==> validando pacote"
    python3 "$PACKAGE_DIR/tools/validate_release.py" --compiled "$OUTPUT"
fi
