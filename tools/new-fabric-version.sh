#!/usr/bin/env bash
#
# Generates a new Fabric module for one Minecraft version by cloning the
# current reference module (fabric-1.20.6) and parametrising the bits that
# genuinely change between versions.
#
# Usage:
#   tools/new-fabric-version.sh 1.21.4 \
#     yarn_mappings=1.21.4+build.4 loader_version=0.16.12 fabric_version=0.119.9+1.21.4
#
# Only the first argument (the Minecraft version) is required; the version pins
# default to the current reference module and can be overridden with KEY=VALUE
# pairs. The toolchain is derived from the Minecraft version:
#
#   < 1.17  -> Java 8     (legacy modules also need source/API fixes)
#   < 1.18  -> Java 16
#   < 1.19  -> Java 17
#   < 1.20.4 -> Java 17   (mixin compatibility JAVA_17)
#   >= 1.20.4 -> Java 17  (mixin compatibility JAVA_21)
#   >= 22   -> Java 21
#
# NOTE: this produces a *starting point*. The Fabric API and Yarn mappings
# change their method names between major releases, so the mixed-in methods in
# src/main/java/me/majhrs16/cht/fabric/ may need manual adjustments. The script
# prints a checklist of the usual places that drift.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REFERENCE="fabric-1.20.6"

trim() {
    local s="$1"
    s="${s#"${s%%[![:space:]]*}"}"
    s="${s%"${s##*[![:space:]]}"}"
    printf '%s' "$s"
}

# version-level comparison for "X < Y" (1.21 vs 1.21.4).
verlt() {
    local a b i
    IFS=. read -r -a a <<< "$1"
    IFS=. read -r -a b <<< "$2"
    for ((i = 0; i < ${#a[@]}; i++)); do
        local ai="${a[$i]:-0}" bi="${b[$i]:-0}"
        if ((10#$bi > 10#$ai)); then return 0; fi
        if ((10#$bi < 10#$ai)); then return 1; fi
    done
    return 1
}

mc_version="${1:-}"
if [[ -z "$mc_version" ]]; then
    echo "error: missing Minecraft version argument" >&2
    echo "usage: tools/new-fabric-version.sh <mc_version> [key=value ...]" >&2
    exit 1
fi

module="fabric-$mc_version"
target="$ROOT/$module"
if [[ -d "$target" ]]; then
    echo "error: $module already exists" >&2
    exit 1
fi

# resolve build pins (KEY=VALUE args; empty -> fall back to reference defaults)
yarn_mappings=""
loader_version=""
fabric_version=""
for arg in "$@"; do case "$arg" in
    yarn_mappings=*|loader_version=*|fabric_version=*)
        eval "$arg";;
esac; done

if [[ -z "$yarn_mappings" || -z "$loader_version" || -z "$fabric_version" ]]; then
    if [[ -f "$ROOT/$REFERENCE/gradle.properties" ]]; then
        while IFS='=' read -r key value; do
            value="$(trim "$value")"
            case "$key" in
                yarn_mappings)  [[ -z "$yarn_mappings"  ]] && yarn_mappings="$value";;
                loader_version) [[ -z "$loader_version" ]] && loader_version="$value";;
                fabric_version) [[ -z "$fabric_version" ]] && fabric_version="$value";;
            esac
        done < "$ROOT/$REFERENCE/gradle.properties"
    fi
fi
if [[ -z "$yarn_mappings" ]]; then yarn_mappings="$mc_version+build.1"; fi
if [[ -z "$loader_version" ]]; then loader_version="0.16.12"; fi
if [[ -z "$fabric_version" ]]; then fabric_version="0.119.9+$mc_version"; fi

echo "Generating $module"
echo "  yarn_mappings=$yarn_mappings"
echo "  loader_version=$loader_version"
echo "  fabric_version=$fabric_version"

# toolchain by version
java_version="21"
compat="JAVA_21"
minecraft_dep="~$mc_version"
if verlt "$mc_version" "1.17";   then java_version="8";  compat="JAVA_8";   fi
if verlt "$mc_version" "1.18";   then java_version="16"; compat="JAVA_16";   fi
if verlt "$mc_version" "1.19";   then java_version="17"; compat="JAVA_17";   fi
if verlt "$mc_version" "1.21";   then java_version="17"; compat="JAVA_17";   fi

# clone the reference module (sources + resources + gradle)
cp -r "$ROOT/$REFERENCE" "$target"
rm -rf "$target/build"

# per-module gradle.properties (overrides the root ones)
cat > "$target/gradle.properties" <<EOF
minecraft_version=$mc_version
yarn_mappings=$yarn_mappings
loader_version=$loader_version
fabric_version=$fabric_version
EOF

# archives name (tolerates leading indentation)
sed -i "s|^[[:space:]]*archivesName = 'chattranslator-fabric'|archivesName = 'chattranslator-fabric-${mc_version}'|" "$target/build.gradle"
# toolchain
sed -i "s/JavaLanguageVersion.of(21)/JavaLanguageVersion.of($java_version)/" "$target/build.gradle"

# fabric.mod.json: minecraft + java depends
sed -i "s/\"minecraft\": \"~[^\"]*\"/\"minecraft\": \"$minecraft_dep\"/" "$target/src/main/resources/fabric.mod.json"
sed -i "s/\"java\": \">=21\"/\"java\": \">=$java_version\"/" "$target/src/main/resources/fabric.mod.json"

# mixins compatibility level
sed -i "s/\"compatibilityLevel\": \"JAVA_[^\"]*\"/\"compatibilityLevel\": \"$compat\"/" "$target/src/main/resources/chattranslator.mixins.json"

# register in settings.gradle (after the reference module, if not present)
if ! grep -q "^include '$module'$" "$ROOT/settings.gradle"; then
    sed -i "s|^include 'fabric-1.20.6'$|include 'fabric-1.20.6'\ninclude '$module'|" "$ROOT/settings.gradle"
    if ! grep -q "^include '$module'$" "$ROOT/settings.gradle"; then
        echo "" >> "$ROOT/settings.gradle"
        echo "include '$module'" >> "$ROOT/settings.gradle"
    fi
fi

cat <<'CHECKLIST'

Done. Checklist for version-specific drift you must verify before building:

  1. PlayerManagerMixin  -- method name/signature of the chat broadcast
     (broadcast(Text, boolean) exists on 1.16.2-1.20.x; older/newer differ).
  2. ChatEventWiring     -- ServerMessageEvents / SignedMessage / MessageType
     Parameters only exist on 1.19.1+; on older versions wire the chat handler
     from a mixin or the fabric-message-api-v0 equivalent.
  3. FabricChatDisplay   -- Text.Serialization / sound registry accessor names.
  4. Sign handling       -- SignBlockEntity#getFrontText / SignText API name.
  5. ServerPlayerEntityAccessor -- the "language" field name is stable, but
     verify it still exists and is not renamed by the Yarn mappings.
CHECKLIST

echo
echo "Build:  ./gradlew :$module:build -x test"
echo "Run:    ./gradlew :$module:runServer"