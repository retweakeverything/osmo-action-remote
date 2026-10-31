// Osmo bridge: a USB-serial to Bluetooth relay for DJI Osmo Action cameras.
//
// The ESP32 does not understand the camera protocol. The host (Mac or phone)
// builds every camera frame; this firmware only scans, connects, moves bytes
// between USB serial and the camera's GATT characteristics, and sends DJI's
// wake advertisement from the ESP32's fixed public address.
//
// Board: ESP32-C3 (also ESP32 / ESP32-S3). Library: NimBLE-Arduino 2.x.
// Serial: 115200 baud, one command per line, all binary as lowercase hex.
//
// Host -> bridge                     Bridge -> host
//   HELLO                              HELLO osmo-bridge <ver> <own-address>
//   SCAN <seconds>                     SCAN_RESULT <addr> <type> <rssi> <mfr-hex|-> <name>
//                                      SCAN_DONE
//   CONNECT <addr> [type]              LINK_UP <addr> <mtu> | ERR connect ...
//   DISCONNECT                         LINK_DOWN <reason>
//   TX <hex>                           OK TX | ERR ...
//                                      RX <hex>     (every notification from the camera)
//   ADDR public|random                 OK ADDR <mode> public=<addr> random=<addr>
//   WAKE <id-hex> [ms] [public|random]                 WAKE_DONE | ERR wake ...
//                                      LOG <text>, ERR <text>, READY (at boot)

#include <NimBLEDevice.h>

static const char* FW_VERSION = "4";
static const NimBLEUUID SERVICE_UUID("fff0");
static const NimBLEUUID NOTIFY_UUID("fff4");
static const NimBLEUUID WRITE_UUID("fff5");

static NimBLEClient* client = nullptr;
static NimBLERemoteCharacteristic* writeChar = nullptr;
static QueueHandle_t rxQueue;      // notifications, handed from the BLE task to loop()
static QueueHandle_t eventQueue;   // short text events from BLE callbacks
static volatile bool scanning = false;
static volatile bool advertising = false;
static volatile uint16_t incomingHandle = BLE_HS_CONN_HANDLE_NONE;  // camera connecting to our wake advert

struct RxChunk { uint16_t len; uint8_t data[512]; };
struct Event { char text[160]; };

static void postEvent(const char* fmt, ...) {
  Event e;
  va_list ap; va_start(ap, fmt);
  vsnprintf(e.text, sizeof(e.text), fmt, ap);
  va_end(ap);
  xQueueSend(eventQueue, &e, 0);
}

static void printHex(const uint8_t* d, size_t n) {
  static const char* h = "0123456789abcdef";
  for (size_t i = 0; i < n; i++) { Serial.write(h[d[i] >> 4]); Serial.write(h[d[i] & 15]); }
}

static int hexToBytes(const String& s, uint8_t* out, int max) {
  int n = 0;
  for (int i = 0; i + 1 < (int)s.length() && n < max; i += 2) {
    char tmp[3] = { s[i], s[i + 1], 0 };
    out[n++] = (uint8_t)strtoul(tmp, nullptr, 16);
  }
  return n;
}

// ---------- scanning ----------

class ScanCb : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice* d) override {
    std::string mfr = d->haveManufacturerData() ? d->getManufacturerData() : std::string();
    bool dji = mfr.size() >= 3 && (uint8_t)mfr[0] == 0xAA && (uint8_t)mfr[1] == 0x08 && (uint8_t)mfr[2] != 0;
    std::string name = d->getName();
    bool osmo = name.find("Osmo") != std::string::npos || name.find("OA5") != std::string::npos;
    if (!dji && !osmo) return;
    char hex[80] = "-";
    if (!mfr.empty()) {
      size_t n = mfr.size() > 38 ? 38 : mfr.size();
      for (size_t i = 0; i < n; i++) sprintf(hex + i * 2, "%02x", (uint8_t)mfr[i]);
    }
    postEvent("SCAN_RESULT %s %u %d %s %s", d->getAddress().toString().c_str(), d->getAddress().getType(),
              d->getRSSI(), hex, name.empty() ? "-" : name.c_str());
  }
  void onScanEnd(const NimBLEScanResults&, int) override { scanning = false; postEvent("SCAN_DONE"); }
};
static ScanCb scanCb;

// ---------- connection ----------

class ClientCb : public NimBLEClientCallbacks {
  void onDisconnect(NimBLEClient*, int reason) override {
    writeChar = nullptr;
    postEvent("LINK_DOWN %d", reason);
  }
};
static ClientCb clientCb;

static void onNotify(NimBLERemoteCharacteristic*, uint8_t* data, size_t len, bool) {
  RxChunk c;
  c.len = len > sizeof(c.data) ? sizeof(c.data) : len;
  memcpy(c.data, data, c.len);
  xQueueSend(rxQueue, &c, 0);
}

static void doConnect(const String& addr, uint8_t type) {
  if (scanning) { NimBLEDevice::getScan()->stop(); scanning = false; }
  if (client && client->isConnected()) client->disconnect();
  if (!client) {
    client = NimBLEDevice::createClient();
    client->setClientCallbacks(&clientCb, false);
    client->setConnectTimeout(10 * 1000);
  }
  NimBLEAddress peer(std::string(addr.c_str()), type);
  if (!client->connect(peer)) { Serial.println("ERR connect failed"); return; }
  NimBLERemoteService* svc = client->getService(SERVICE_UUID);
  NimBLERemoteCharacteristic* notify = svc ? svc->getCharacteristic(NOTIFY_UUID) : nullptr;
  writeChar = svc ? svc->getCharacteristic(WRITE_UUID) : nullptr;
  if (!svc || !notify || !writeChar) { Serial.println("ERR connect service fff0/fff4/fff5 missing"); client->disconnect(); return; }
  if (!notify->subscribe(true, onNotify)) { Serial.println("ERR connect subscribe failed"); client->disconnect(); return; }
  Serial.printf("LINK_UP %s %u\n", addr.c_str(), client->getMTU());
}

static void doTx(const String& hex) {
  if (!writeChar || !client || !client->isConnected()) { Serial.println("ERR TX not connected"); return; }
  static uint8_t buf[1024];
  int n = hexToBytes(hex, buf, sizeof(buf));
  size_t chunk = client->getMTU() > 3 ? client->getMTU() - 3 : 20;
  for (int off = 0; off < n; off += chunk) {
    size_t len = (size_t)(n - off) < chunk ? (size_t)(n - off) : chunk;
    if (!writeChar->writeValue(buf + off, len, false)) { Serial.println("ERR TX write failed"); return; }
  }
  Serial.println("OK TX");
}

// ---------- wake advertisement ----------

static int advEvent(struct ble_gap_event* ev, void*) {
  if (ev->type == BLE_GAP_EVENT_ADV_COMPLETE) {
    advertising = false;
    postEvent("LOG wake adv complete reason=%d", ev->adv_complete.reason);
  }
  // DJI-Remote accepts a connection that arrives on the wake advert (the camera
  // may connect back to the remote as part of waking). Keep it and report it.
  if (ev->type == BLE_GAP_EVENT_CONNECT) {
    if (ev->connect.status != 0) {
      postEvent("LOG incoming connect failed status=%d", ev->connect.status);
    } else {
      ble_gap_conn_desc d;
      if (ble_gap_conn_find(ev->connect.conn_handle, &d) == 0) {
        const uint8_t* a = d.peer_id_addr.val;
        postEvent("LOG incoming connect handle=%u from %02x:%02x:%02x:%02x:%02x:%02x type=%u",
                  ev->connect.conn_handle, a[5], a[4], a[3], a[2], a[1], a[0], d.peer_id_addr.type);
      }
      incomingHandle = ev->connect.conn_handle;
    }
  }
  if (ev->type == BLE_GAP_EVENT_DISCONNECT) {
    postEvent("LOG incoming link down reason=%d", ev->disconnect.reason);
    incomingHandle = BLE_HS_CONN_HANDLE_NONE;
  }
  return 0;
}

// Raw packet exactly as in DJI's demo: 0A FF 'W' 'K' 'P' <id reversed>, no Flags field,
// connectable undirected (ADV_IND), from the public address.
static void doWake(const String& idHex, uint32_t ms, uint8_t ownType) {
  uint8_t id[6];
  if (hexToBytes(idHex, id, 6) != 6) { Serial.println("ERR wake id must be 12 hex chars"); return; }
  if (scanning) { NimBLEDevice::getScan()->stop(); scanning = false; }
  uint8_t adv[11] = { 0x0A, 0xFF, 'W', 'K', 'P' };
  for (int i = 0; i < 6; i++) adv[5 + i] = id[5 - i];
  int rc = ble_gap_adv_set_data(adv, sizeof(adv));
  if (rc != 0) { Serial.printf("ERR wake set_data rc=%d\n", rc); return; }
  ble_gap_adv_params p = {};
  p.conn_mode = BLE_GAP_CONN_MODE_UND;
  p.disc_mode = BLE_GAP_DISC_MODE_GEN;
  p.itvl_min = 0x20;   // 20 ms
  p.itvl_max = 0x40;   // 40 ms
  advertising = true;
  rc = ble_gap_adv_start(ownType, nullptr, ms, &p, advEvent, nullptr);
  if (rc != 0) { advertising = false; Serial.printf("ERR wake adv_start rc=%d\n", rc); return; }
  Serial.print("LOG wake advertising ");
  printHex(adv, sizeof(adv));
  Serial.printf(" for %lu ms from %s address\n", (unsigned long)ms, ownType == BLE_OWN_ADDR_RANDOM ? "random" : "public");
  uint32_t until = millis() + ms + 500;
  while (advertising && millis() < until) delay(20);
  if (advertising) { ble_gap_adv_stop(); advertising = false; }
  Serial.println("WAKE_DONE");
}

// ---------- command loop ----------

static void handle(String line) {
  line.trim();
  if (line.isEmpty()) return;
  int sp = line.indexOf(' ');
  String cmd = sp < 0 ? line : line.substring(0, sp);
  String rest = sp < 0 ? String() : line.substring(sp + 1);
  cmd.toUpperCase();

  if (cmd == "HELLO") {
    Serial.printf("HELLO osmo-bridge %s %s\n", FW_VERSION, NimBLEDevice::getAddress().toString().c_str());
  } else if (cmd == "SCAN") {
    uint32_t sec = rest.isEmpty() ? 6 : rest.toInt();
    NimBLEScan* s = NimBLEDevice::getScan();
    s->clearResults();
    scanning = s->start(sec * 1000, false, true);
    if (!scanning) Serial.println("ERR scan start failed");
  } else if (cmd == "CONNECT") {
    int sp2 = rest.indexOf(' ');
    String addr = sp2 < 0 ? rest : rest.substring(0, sp2);
    uint8_t type = sp2 < 0 ? BLE_ADDR_PUBLIC : (uint8_t)rest.substring(sp2 + 1).toInt();
    doConnect(addr, type);
  } else if (cmd == "DISCONNECT") {
    if (client && client->isConnected()) client->disconnect(); else Serial.println("LINK_DOWN 0");
  } else if (cmd == "TX") {
    doTx(rest);
  } else if (cmd == "ADDR") {
    // Own address used for scanning/connecting. The wake advert always uses the public address.
    // Avinox (wakes the Action 5 Pro) connects from a random static address and switches to public only to wake.
    rest.toLowerCase();
    uint8_t t = rest == "random" ? BLE_OWN_ADDR_RANDOM : BLE_OWN_ADDR_PUBLIC;
    if (!NimBLEDevice::setOwnAddrType(t)) { Serial.println("ERR addr"); return; }
    ble_addr_t pub{}, rnd{};
    ble_hs_id_copy_addr(BLE_ADDR_PUBLIC, pub.val, nullptr);
    ble_hs_id_copy_addr(BLE_ADDR_RANDOM, rnd.val, nullptr);
    Serial.printf("OK ADDR %s public=%s random=%s\n", t == BLE_OWN_ADDR_RANDOM ? "random" : "public",
                  NimBLEAddress(pub).toString().c_str(), NimBLEAddress(rnd).toString().c_str());
  } else if (cmd == "WAKE") {
    int sp2 = rest.indexOf(' ');
    String id = sp2 < 0 ? rest : rest.substring(0, sp2);
    String tail = sp2 < 0 ? String() : rest.substring(sp2 + 1);
    int sp3 = tail.indexOf(' ');
    uint32_t ms = tail.isEmpty() ? 3000 : (sp3 < 0 ? tail : tail.substring(0, sp3)).toInt();
    String addr = sp3 < 0 ? String() : tail.substring(sp3 + 1);
    addr.trim(); addr.toLowerCase();
    doWake(id, ms ? ms : 3000, addr == "random" ? BLE_OWN_ADDR_RANDOM : BLE_OWN_ADDR_PUBLIC);
  } else {
    Serial.printf("ERR unknown command %s\n", cmd.c_str());
  }
}

void setup() {
  Serial.begin(115200);
  Serial.setTimeout(10);
  rxQueue = xQueueCreate(16, sizeof(RxChunk));
  eventQueue = xQueueCreate(32, sizeof(Event));
  NimBLEDevice::init("osmo-bridge");
  NimBLEDevice::setOwnAddrType(BLE_OWN_ADDR_PUBLIC);
  NimBLEDevice::setMTU(247);
  // Registers the GAP/GATT services; DJI cameras discover them on the remote.
  NimBLEDevice::createServer();
  NimBLEScan* s = NimBLEDevice::getScan();
  s->setScanCallbacks(&scanCb, false);
  s->setActiveScan(true);
  s->setInterval(100);
  s->setWindow(99);
  Serial.printf("READY osmo-bridge %s %s\n", FW_VERSION, NimBLEDevice::getAddress().toString().c_str());
}

void loop() {
  static String line;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') { if (line.length()) { handle(line); line = ""; } }
    else if (line.length() < 2200) line += c;
  }
  RxChunk c;
  while (xQueueReceive(rxQueue, &c, 0) == pdTRUE) {
    Serial.print("RX ");
    printHex(c.data, c.len);
    Serial.println();
  }
  Event e;
  while (xQueueReceive(eventQueue, &e, 0) == pdTRUE) Serial.println(e.text);
  delay(2);
}
