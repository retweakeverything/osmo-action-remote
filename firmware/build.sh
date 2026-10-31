#!/usr/bin/env bash
# Builds the osmo-bridge relay for every supported chip and writes one
# ready-to-flash image per chip to firmware/dist/:
#
#   osmo-bridge-esp32.bin     original ESP32 (CP210x / CH34x USB chip)
#   osmo-bridge-esp32c3.bin   ESP32-C3 (built-in USB)
#   osmo-bridge-esp32s3.bin   ESP32-S3 (built-in USB)
#
# Each image contains bootloader + partition table + app and is flashed at
# offset 0x0 with any ESP flasher. Also writes SHA256SUMS.
#
# Needs arduino-cli. Usage: firmware/build.sh
set -euo pipefail

CORE_VERSION="3.3.11"
NIMBLE_VERSION="2.5.1"
CORE_INDEX="https://espressif.github.io/arduino-esp32/package_esp32_index.json"

here="$(cd "$(dirname "$0")" && pwd)"
sketch="$here/osmo-bridge"
dist="$here/dist"
cli="${ARDUINO_CLI:-arduino-cli}"

if [ "${INSTALL_DEPS:-0}" = "1" ]; then
  "$cli" core update-index --additional-urls "$CORE_INDEX"
  "$cli" core install "esp32:esp32@$CORE_VERSION" --additional-urls "$CORE_INDEX"
  "$cli" lib update-index
  "$cli" lib install "NimBLE-Arduino@$NIMBLE_VERSION"
fi

rm -rf "$dist"
mkdir -p "$dist"

# chip     fqbn
targets=(
  "esp32   esp32:esp32:esp32"
  "esp32c3 esp32:esp32:esp32c3:CDCOnBoot=cdc"
  "esp32s3 esp32:esp32:esp32s3:CDCOnBoot=cdc"
)

for t in "${targets[@]}"; do
  read -r chip fqbn <<< "$t"
  out="$(mktemp -d)"
  echo "== $chip ($fqbn)"
  "$cli" compile -b "$fqbn" --output-dir "$out" ${LIBRARIES:+--libraries "$LIBRARIES"} "$sketch" > "$out/build.log" 2>&1 \
    || { cat "$out/build.log"; exit 1; }
  grep "Sketch uses" "$out/build.log" || true
  # The merged image is padded with 0xFF to the full flash size. Drop the
  # padding (erased flash reads 0xFF anyway) to keep downloads small.
  python3 - "$out/osmo-bridge.ino.merged.bin" "$dist/osmo-bridge-$chip.bin" <<'EOF'
import sys
data = open(sys.argv[1], "rb").read()
end = len(data.rstrip(b"\xff"))
end = (end + 4095) // 4096 * 4096
open(sys.argv[2], "wb").write(data[:end])
print(f"   {sys.argv[2].split('/')[-1]}: {end} bytes")
EOF
  rm -rf "$out"
done

(cd "$dist" && shasum -a 256 osmo-bridge-*.bin > SHA256SUMS)
echo "== done: $dist"
