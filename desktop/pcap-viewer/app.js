const fileInput = document.getElementById("fileInput");
const searchInput = document.getElementById("searchInput");
const clearButton = document.getElementById("clearButton");
const packetRows = document.getElementById("packetRows");
const packetDetail = document.getElementById("packetDetail");
const packetCount = document.getElementById("packetCount");
const byteCount = document.getElementById("byteCount");
const fileFormat = document.getElementById("fileFormat");
const linkTypeEl = document.getElementById("linkType");

let currentPackets = [];

fileInput.addEventListener("change", async (event) => {
  const file = event.target.files && event.target.files[0];
  if (!file) return;

  try {
    const bytes = new Uint8Array(await file.arrayBuffer());
    const parsed = parseCapture(bytes);
    currentPackets = parsed.packets.map((packet, index) => ({
      ...packet,
      index: index + 1,
      decoded: decodePacket(packet.bytes, packet.linkType ?? parsed.linkType)
    }));
    renderSummary(parsed, currentPackets);
    renderPackets(currentPackets);
    packetDetail.textContent = "选择一行查看包内容";
  } catch (error) {
    currentPackets = [];
    renderSummary({ format: "错误", linkTypeName: "-" }, []);
    packetRows.innerHTML = `<tr><td colspan="7" class="empty error">${escapeHtml(error.message)}</td></tr>`;
    packetDetail.textContent = String(error.stack || error.message);
  }
});

searchInput.addEventListener("input", () => {
  const query = searchInput.value.trim().toLowerCase();
  if (!query) {
    renderPackets(currentPackets);
    return;
  }

  renderPackets(currentPackets.filter((packet) => {
    const decoded = packet.decoded;
    return [
      decoded.protocol,
      decoded.source,
      decoded.destination,
      decoded.info,
      String(packet.length)
    ].join(" ").toLowerCase().includes(query);
  }));
});

clearButton.addEventListener("click", () => {
  searchInput.value = "";
  renderPackets(currentPackets);
});

function parseCapture(bytes) {
  if (bytes.length < 4) {
    throw new Error("文件太小，不像 PCAP/PCAPNG。");
  }

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const firstLe = view.getUint32(0, true);
  const firstBe = view.getUint32(0, false);

  if (firstLe === 0xa1b2c3d4 || firstLe === 0xa1b23c4d || firstBe === 0xa1b2c3d4 || firstBe === 0xa1b23c4d) {
    return parsePcap(bytes, view);
  }

  if (firstLe === 0x0a0d0d0a || firstBe === 0x0a0d0d0a) {
    return parsePcapng(bytes, view);
  }

  throw new Error("未识别的抓包格式，只支持 PCAP 和基础 PCAPNG。");
}

function parsePcap(bytes, view) {
  const magicLe = view.getUint32(0, true);
  const magicBe = view.getUint32(0, false);
  const little = magicLe === 0xa1b2c3d4 || magicLe === 0xa1b23c4d;
  const nanos = magicLe === 0xa1b23c4d || magicBe === 0xa1b23c4d;
  const linkType = view.getUint32(20, little);
  const packets = [];
  let offset = 24;

  while (offset + 16 <= bytes.length) {
    const tsSec = view.getUint32(offset, little);
    const tsFrac = view.getUint32(offset + 4, little);
    const inclLen = view.getUint32(offset + 8, little);
    const origLen = view.getUint32(offset + 12, little);
    offset += 16;
    if (inclLen > bytes.length - offset) break;

    packets.push({
      timestamp: tsSec + tsFrac / (nanos ? 1_000_000_000 : 1_000_000),
      length: origLen,
      capturedLength: inclLen,
      linkType,
      bytes: bytes.slice(offset, offset + inclLen)
    });
    offset += inclLen;
  }

  return {
    format: "PCAP",
    linkType,
    linkTypeName: linkTypeName(linkType),
    packets
  };
}

function parsePcapng(bytes, view) {
  const packets = [];
  let offset = 0;
  let little = true;
  let defaultLinkType = 1;
  const interfaces = [];

  while (offset + 12 <= bytes.length) {
    const blockType = view.getUint32(offset, true);
    if (blockType !== 0x0a0d0d0a && blockType > 0xfffffff0) break;

    if (blockType === 0x0a0d0d0a) {
      const magicLe = view.getUint32(offset + 8, true);
      little = magicLe === 0x1a2b3c4d;
    }

    const blockLength = view.getUint32(offset + 4, little);
    if (blockLength < 12 || offset + blockLength > bytes.length) break;

    if (blockType === 0x00000001 && blockLength >= 20) {
      const linkType = view.getUint16(offset + 8, little);
      interfaces.push({ linkType });
      defaultLinkType = linkType;
    }

    if (blockType === 0x00000006 && blockLength >= 32) {
      const ifaceId = view.getUint32(offset + 8, little);
      const tsHigh = view.getUint32(offset + 12, little);
      const tsLow = view.getUint32(offset + 16, little);
      const capturedLength = view.getUint32(offset + 20, little);
      const originalLength = view.getUint32(offset + 24, little);
      const packetStart = offset + 28;
      const packetEnd = packetStart + capturedLength;
      if (packetEnd <= offset + blockLength - 4) {
        const linkType = interfaces[ifaceId]?.linkType ?? defaultLinkType;
        const timestamp = ((BigInt(tsHigh) << 32n) + BigInt(tsLow));
        packets.push({
          timestamp: Number(timestamp) / 1_000_000,
          length: originalLength,
          capturedLength,
          linkType,
          bytes: bytes.slice(packetStart, packetEnd)
        });
      }
    }

    offset += blockLength;
  }

  return {
    format: "PCAPNG",
    linkType: defaultLinkType,
    linkTypeName: linkTypeName(defaultLinkType),
    packets
  };
}

function decodePacket(bytes, linkType) {
  if (linkType === 1) {
    return decodeEthernet(bytes);
  }
  if (linkType === 101 || linkType === 228 || linkType === 229) {
    return decodeIp(bytes, 0);
  }
  return {
    protocol: `LINK-${linkType}`,
    source: "-",
    destination: "-",
    info: "未解析链路类型"
  };
}

function decodeEthernet(bytes) {
  if (bytes.length < 14) {
    return unknown("Ethernet frame too short");
  }

  let typeOffset = 12;
  let etherType = readU16(bytes, typeOffset);
  if (etherType === 0x8100 && bytes.length >= 18) {
    typeOffset = 16;
    etherType = readU16(bytes, typeOffset);
  }

  if (etherType === 0x0800 || etherType === 0x86dd) {
    return decodeIp(bytes, typeOffset + 2);
  }
  if (etherType === 0x0806) {
    return {
      protocol: "ARP",
      source: mac(bytes.slice(6, 12)),
      destination: mac(bytes.slice(0, 6)),
      info: "ARP"
    };
  }

  return {
    protocol: `ETH 0x${etherType.toString(16).padStart(4, "0")}`,
    source: mac(bytes.slice(6, 12)),
    destination: mac(bytes.slice(0, 6)),
    info: "未解析以太网类型"
  };
}

function decodeIp(bytes, offset) {
  if (bytes.length <= offset) return unknown("IP packet too short");
  const version = bytes[offset] >> 4;
  if (version === 4) return decodeIpv4(bytes, offset);
  if (version === 6) return decodeIpv6(bytes, offset);
  return unknown(`Unknown IP version ${version}`);
}

function decodeIpv4(bytes, offset) {
  if (bytes.length < offset + 20) return unknown("IPv4 packet too short");
  const ihl = (bytes[offset] & 0x0f) * 4;
  const protocol = bytes[offset + 9];
  const src = ipv4(bytes, offset + 12);
  const dst = ipv4(bytes, offset + 16);
  return decodeTransport(bytes, offset + ihl, protocol, src, dst, "IPv4");
}

function decodeIpv6(bytes, offset) {
  if (bytes.length < offset + 40) return unknown("IPv6 packet too short");
  const nextHeader = bytes[offset + 6];
  const src = ipv6(bytes, offset + 8);
  const dst = ipv6(bytes, offset + 24);
  return decodeTransport(bytes, offset + 40, nextHeader, src, dst, "IPv6");
}

function decodeTransport(bytes, offset, protocol, src, dst, ipVersion) {
  if (protocol === 6 && bytes.length >= offset + 20) {
    const sport = readU16(bytes, offset);
    const dport = readU16(bytes, offset + 2);
    const tcpHeaderLength = (bytes[offset + 12] >> 4) * 4;
    const payload = bytes.slice(offset + tcpHeaderLength);
    const appInfo = applicationSummary(payload);
    return {
      protocol: "TCP",
      source: `${src}:${sport}`,
      destination: `${dst}:${dport}`,
      info: appInfo || `${ipVersion} TCP ${tcpFlags(bytes[offset + 13])}`
    };
  }

  if (protocol === 17 && bytes.length >= offset + 8) {
    const sport = readU16(bytes, offset);
    const dport = readU16(bytes, offset + 2);
    const payloadOffset = offset + 8;
    const dns = sport === 53 || dport === 53 ? parseDns(bytes, payloadOffset) : "";
    return {
      protocol: "UDP",
      source: `${src}:${sport}`,
      destination: `${dst}:${dport}`,
      info: dns || `${ipVersion} UDP`
    };
  }

  if (protocol === 1 || protocol === 58) {
    return {
      protocol: protocol === 1 ? "ICMP" : "ICMPv6",
      source: src,
      destination: dst,
      info: ipVersion
    };
  }

  return {
    protocol: `${ipVersion}/${protocol}`,
    source: src,
    destination: dst,
    info: "未解析传输层协议"
  };
}

function parseDns(bytes, offset) {
  if (bytes.length < offset + 12) return "";
  const qdCount = readU16(bytes, offset + 4);
  if (qdCount < 1) return "DNS";

  let cursor = offset + 12;
  const labels = [];
  for (let i = 0; i < 30 && cursor < bytes.length; i++) {
    const len = bytes[cursor++];
    if (len === 0) break;
    if ((len & 0xc0) !== 0 || cursor + len > bytes.length) return "DNS";
    labels.push(ascii(bytes.slice(cursor, cursor + len)));
    cursor += len;
  }

  return labels.length ? `DNS query ${labels.join(".")}` : "DNS";
}

function renderSummary(parsed, packets) {
  packetCount.textContent = packets.length.toLocaleString();
  byteCount.textContent = packets.reduce((sum, packet) => sum + packet.length, 0).toLocaleString();
  fileFormat.textContent = parsed.format || "-";
  linkTypeEl.textContent = parsed.linkTypeName || "-";
}

function renderPackets(packets) {
  if (!packets.length) {
    packetRows.innerHTML = `<tr><td colspan="7" class="empty">没有匹配的数据包</td></tr>`;
    return;
  }

  const maxRows = 2000;
  const rows = packets.slice(0, maxRows).map((packet) => {
    const d = packet.decoded;
    return `<tr data-index="${packet.index}">
      <td>${packet.index}</td>
      <td>${formatTime(packet.timestamp)}</td>
      <td>${escapeHtml(d.protocol)}</td>
      <td>${escapeHtml(d.source)}</td>
      <td>${escapeHtml(d.destination)}</td>
      <td>${packet.length}</td>
      <td>${escapeHtml(d.info)}</td>
    </tr>`;
  });

  if (packets.length > maxRows) {
    rows.push(`<tr><td colspan="7" class="empty">仅显示前 ${maxRows} 个匹配包</td></tr>`);
  }

  packetRows.innerHTML = rows.join("");
  packetRows.querySelectorAll("tr[data-index]").forEach((row) => {
    row.addEventListener("click", () => {
      const packet = currentPackets.find((item) => item.index === Number(row.dataset.index));
      if (packet) renderDetail(packet);
    });
  });
}

function renderDetail(packet) {
  const d = packet.decoded;
  const fields = decodePacketFields(packet.bytes, packet.linkType ?? 1);
  packetDetail.textContent = [
    `#${packet.index}`,
    `time: ${formatTime(packet.timestamp)}`,
    `protocol: ${d.protocol}`,
    `source: ${d.source}`,
    `destination: ${d.destination}`,
    `length: ${packet.length}`,
    `summary: ${d.info}`,
    "",
    "字段 / Value",
    ...fields,
    "",
    "HEX / ASCII",
    hexDump(packet.bytes.slice(0, 512))
  ].join("\n");
}

function decodePacketFields(bytes, linkType) {
  const lines = [];
  let ipOffset = 0;
  if (linkType === 1) {
    if (bytes.length < 14) return ["ethernet.error: frame too short"];
    let typeOffset = 12;
    let etherType = readU16(bytes, typeOffset);
    if (etherType === 0x8100 && bytes.length >= 18) {
      addField(lines, "eth.vlan", String(readU16(bytes, 14) & 0x0fff));
      typeOffset = 16;
      etherType = readU16(bytes, typeOffset);
    }
    addField(lines, "eth.source", mac(bytes.slice(6, 12)));
    addField(lines, "eth.destination", mac(bytes.slice(0, 6)));
    addField(lines, "eth.type", `0x${etherType.toString(16).padStart(4, "0")}`);
    ipOffset = typeOffset + 2;
  }

  if (![1, 101, 228, 229].includes(linkType)) {
    addField(lines, "link.type", linkTypeName(linkType));
  }
  if (bytes.length <= ipOffset) return lines;

  const version = bytes[ipOffset] >> 4;
  if (version === 4 && bytes.length >= ipOffset + 20) {
    const ihl = (bytes[ipOffset] & 0x0f) * 4;
    const protocol = bytes[ipOffset + 9];
    const src = ipv4(bytes, ipOffset + 12);
    const dst = ipv4(bytes, ipOffset + 16);
    addField(lines, "ip.version", "4");
    addField(lines, "ip.source", src);
    addField(lines, "ip.destination", dst);
    addField(lines, "ip.header_length", String(ihl));
    addField(lines, "ip.total_length", String(readU16(bytes, ipOffset + 2)));
    addField(lines, "ip.ttl", String(bytes[ipOffset + 8]));
    addField(lines, "ip.protocol", protocolName(protocol));
    decodeTransportFields(bytes, ipOffset + ihl, protocol, lines);
  } else if (version === 6 && bytes.length >= ipOffset + 40) {
    const protocol = bytes[ipOffset + 6];
    addField(lines, "ip.version", "6");
    addField(lines, "ip.source", ipv6(bytes, ipOffset + 8));
    addField(lines, "ip.destination", ipv6(bytes, ipOffset + 24));
    addField(lines, "ip.payload_length", String(readU16(bytes, ipOffset + 4)));
    addField(lines, "ip.next_header", protocolName(protocol));
    addField(lines, "ip.hop_limit", String(bytes[ipOffset + 7]));
    decodeTransportFields(bytes, ipOffset + 40, protocol, lines);
  } else {
    addField(lines, "ip.error", `unknown version ${version}`);
  }
  return lines;
}

function decodeTransportFields(bytes, offset, protocol, lines) {
  if (protocol === 6 && bytes.length >= offset + 20) {
    const sport = readU16(bytes, offset);
    const dport = readU16(bytes, offset + 2);
    const headerLength = (bytes[offset + 12] >> 4) * 4;
    const payloadOffset = offset + headerLength;
    const payload = bytes.slice(payloadOffset);
    addField(lines, "tcp.source_port", String(sport));
    addField(lines, "tcp.destination_port", String(dport));
    addField(lines, "tcp.sequence", String(readU32(bytes, offset + 4)));
    addField(lines, "tcp.acknowledgment", String(readU32(bytes, offset + 8)));
    addField(lines, "tcp.header_length", String(headerLength));
    addField(lines, "tcp.flags", tcpFlags(bytes[offset + 13]));
    addField(lines, "tcp.window", String(readU16(bytes, offset + 14)));
    addField(lines, "tcp.payload_length", String(payload.length));
    addApplicationFields(lines, payload, sport, dport);
  } else if (protocol === 17 && bytes.length >= offset + 8) {
    const sport = readU16(bytes, offset);
    const dport = readU16(bytes, offset + 2);
    const payloadOffset = offset + 8;
    addField(lines, "udp.source_port", String(sport));
    addField(lines, "udp.destination_port", String(dport));
    addField(lines, "udp.length", String(readU16(bytes, offset + 4)));
    addField(lines, "udp.checksum", `0x${readU16(bytes, offset + 6).toString(16).padStart(4, "0")}`);
    if (sport === 53 || dport === 53) {
      parseDnsFields(bytes, payloadOffset).forEach(([name, value]) => addField(lines, name, value));
    }
  } else if (protocol === 1 || protocol === 58) {
    addField(lines, "icmp.type", bytes.length > offset ? String(bytes[offset]) : "-");
    addField(lines, "icmp.code", bytes.length > offset + 1 ? String(bytes[offset + 1]) : "-");
  }
}

function addApplicationFields(lines, payload, sport, dport) {
  const httpFields = parseHttpFields(payload);
  if (httpFields.length) {
    httpFields.forEach(([name, value]) => addField(lines, name, value));
    return;
  }
  const tlsFields = parseTlsFields(payload);
  if (tlsFields.length) {
    tlsFields.forEach(([name, value]) => addField(lines, name, value));
    return;
  }
  if (sport === 443 || dport === 443) {
    addField(lines, "https.body", "encrypted; PCAP cannot show HTTP field values without MITM/decryption");
  }
}

function applicationSummary(payload) {
  const httpFields = parseHttpFields(payload);
  if (httpFields.length) {
    const map = Object.fromEntries(httpFields);
    if (map["http.method"]) {
      return `HTTP ${map["http.method"]} ${(map["header.Host"] || map["header.host"] || "")}${map["http.target"] || ""}`;
    }
    if (map["http.status_code"]) {
      return `HTTP ${map["http.status_code"]} ${map["http.reason"] || ""}`.trim();
    }
    return "HTTP";
  }
  const tlsFields = parseTlsFields(payload);
  if (tlsFields.length) {
    const map = Object.fromEntries(tlsFields);
    return map["tls.sni"] ? `TLS SNI ${map["tls.sni"]}` : `TLS ${map["tls.record_type"] || ""}`.trim();
  }
  return "";
}

function parseHttpFields(payload) {
  if (!payload.length) return [];
  const text = ascii(payload.slice(0, 8192)).replace(/\r\n/g, "\n");
  if (!looksHttp(text)) return [];
  const splitIndex = text.indexOf("\n\n");
  const headerText = splitIndex >= 0 ? text.slice(0, splitIndex) : text;
  const bodyText = splitIndex >= 0 ? text.slice(splitIndex + 2) : "";
  const headerLines = headerText.split("\n").filter(Boolean);
  if (!headerLines.length) return [];

  const fields = [];
  const headers = {};
  const first = headerLines[0];
  if (/^HTTP\//i.test(first)) {
    const parts = first.split(" ");
    fields.push(["http.version", parts[0] || ""]);
    fields.push(["http.status_code", parts[1] || ""]);
    fields.push(["http.reason", parts.slice(2).join(" ")]);
  } else {
    const parts = first.split(" ");
    fields.push(["http.method", parts[0] || ""]);
    const target = parts[1] || "";
    fields.push(["http.target", target]);
    fields.push(["http.version", parts[2] || ""]);
    const queryIndex = target.indexOf("?");
    if (queryIndex >= 0) {
      fields.push(...parseFormEncoded(target.slice(queryIndex + 1), "query"));
    }
  }

  headerLines.slice(1).forEach((line) => {
    const colon = line.indexOf(":");
    if (colon > 0) {
      const name = line.slice(0, colon).trim();
      const value = line.slice(colon + 1).trim();
      headers[name.toLowerCase()] = value;
      fields.push([`header.${name}`, value]);
    }
  });

  const body = bodyText.trim();
  const contentType = headers["content-type"] || "";
  if (body) {
    if (/application\/x-www-form-urlencoded/i.test(contentType)) {
      fields.push(...parseFormEncoded(body, "form"));
    } else if (/json/i.test(contentType) || body.startsWith("{")) {
      fields.push(...parseJsonFields(body));
    } else {
      fields.push(["body.preview", body.slice(0, 360)]);
    }
  }

  return fields.map(([name, value]) => [name, compactValue(value)]);
}

function looksHttp(text) {
  return /^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) /i.test(text) || /^HTTP\//i.test(text);
}

function parseFormEncoded(value, prefix) {
  return value.split("&")
    .filter(Boolean)
    .slice(0, 80)
    .map((part) => {
      const equals = part.indexOf("=");
      const key = equals >= 0 ? part.slice(0, equals) : part;
      const fieldValue = equals >= 0 ? part.slice(equals + 1) : "";
      return [`${prefix}.${decodeUrl(key)}`, decodeUrl(fieldValue)];
    });
}

function parseJsonFields(body) {
  try {
    const parsed = JSON.parse(body);
    if (Array.isArray(parsed)) {
      return [["json[]", `array length=${parsed.length}`]];
    }
    if (parsed && typeof parsed === "object") {
      return Object.entries(parsed).slice(0, 80).map(([key, value]) => [
        `json.${key}`,
        typeof value === "string" ? value : JSON.stringify(value)
      ]);
    }
  } catch {
    return [["body.json", body.slice(0, 360)]];
  }
  return [];
}

function parseTlsFields(payload) {
  if (payload.length < 5) return [];
  const contentType = payload[0];
  if (payload[1] !== 3 || ![20, 21, 22, 23].includes(contentType)) return [];
  const fields = [
    ["tls.record_type", tlsRecordType(contentType)],
    ["tls.record_version", `0x${payload[1].toString(16)} 0x${payload[2].toString(16)}`],
    ["tls.record_length", String(readU16(payload, 3))]
  ];
  if (contentType === 23) {
    fields.push(["tls.application_data", "encrypted"]);
    return fields;
  }
  if (contentType !== 22 || payload.length < 9) return fields;
  const handshakeType = payload[5];
  fields.push(["tls.handshake_type", tlsHandshakeType(handshakeType)]);
  if (handshakeType !== 1) return fields;

  let cursor = 9;
  if (payload.length < cursor + 34) return fields;
  fields.push(["tls.client_version", `0x${payload[cursor].toString(16)} 0x${payload[cursor + 1].toString(16)}`]);
  cursor += 34;
  if (cursor >= payload.length) return fields;
  cursor += 1 + payload[cursor];
  if (cursor + 2 > payload.length) return fields;
  cursor += 2 + readU16(payload, cursor);
  if (cursor >= payload.length) return fields;
  cursor += 1 + payload[cursor];
  if (cursor + 2 > payload.length) return fields;
  const extensionsEnd = Math.min(payload.length, cursor + 2 + readU16(payload, cursor));
  cursor += 2;
  while (cursor + 4 <= extensionsEnd) {
    const type = readU16(payload, cursor);
    const length = readU16(payload, cursor + 2);
    const start = cursor + 4;
    const end = start + length;
    if (end > extensionsEnd) break;
    if (type === 0) {
      const sni = parseTlsSni(payload, start, end);
      if (sni) fields.push(["tls.sni", sni]);
    }
    if (type === 16) {
      const alpn = parseTlsAlpn(payload, start, end);
      if (alpn.length) fields.push(["tls.alpn", alpn.join(", ")]);
    }
    cursor = end;
  }
  return fields;
}

function parseTlsSni(payload, start, end) {
  let cursor = start + 2;
  while (cursor + 3 <= end) {
    const nameType = payload[cursor++];
    const length = readU16(payload, cursor);
    cursor += 2;
    if (cursor + length > end) return "";
    const name = ascii(payload.slice(cursor, cursor + length));
    if (nameType === 0) return name;
    cursor += length;
  }
  return "";
}

function parseTlsAlpn(payload, start, end) {
  let cursor = start + 2;
  const protocols = [];
  while (cursor < end) {
    const length = payload[cursor++];
    if (cursor + length > end) break;
    protocols.push(ascii(payload.slice(cursor, cursor + length)));
    cursor += length;
  }
  return protocols;
}

function parseDnsFields(bytes, offset) {
  if (bytes.length < offset + 12) return [];
  const fields = [
    ["dns.transaction_id", `0x${readU16(bytes, offset).toString(16).padStart(4, "0")}`],
    ["dns.flags", `0x${readU16(bytes, offset + 2).toString(16).padStart(4, "0")}`],
    ["dns.questions", String(readU16(bytes, offset + 4))]
  ];
  const question = readDnsQuestion(bytes, offset);
  if (question) {
    fields.push(["dns.query.name", question.name]);
    fields.push(["dns.query.type", dnsTypeName(question.type)]);
    fields.push(["dns.query.class", String(question.clazz)]);
  }
  return fields;
}

function readDnsQuestion(bytes, offset) {
  if (bytes.length < offset + 12 || readU16(bytes, offset + 4) < 1) return null;
  let cursor = offset + 12;
  const labels = [];
  for (let i = 0; i < 40 && cursor < bytes.length; i++) {
    const length = bytes[cursor++];
    if (length === 0) {
      if (cursor + 4 > bytes.length || !labels.length) return null;
      return {
        name: labels.join("."),
        type: readU16(bytes, cursor),
        clazz: readU16(bytes, cursor + 2)
      };
    }
    if ((length & 0xc0) !== 0 || cursor + length > bytes.length) return null;
    labels.push(ascii(bytes.slice(cursor, cursor + length)));
    cursor += length;
  }
  return null;
}

function hexDump(bytes) {
  const lines = [];
  for (let offset = 0; offset < bytes.length; offset += 16) {
    const chunk = bytes.slice(offset, offset + 16);
    const hex = Array.from(chunk).map((b) => b.toString(16).padStart(2, "0")).join(" ").padEnd(47, " ");
    const text = ascii(chunk).replace(/[^\x20-\x7e]/g, ".");
    lines.push(`${offset.toString(16).padStart(4, "0")}  ${hex}  ${text}`);
  }
  return lines.join("\n");
}

function formatTime(timestamp) {
  if (!Number.isFinite(timestamp) || timestamp <= 0) return "-";
  const date = new Date(timestamp * 1000);
  return date.toISOString().replace("T", " ").replace("Z", "");
}

function linkTypeName(value) {
  const names = {
    1: "Ethernet",
    101: "Raw IP",
    228: "IPv4",
    229: "IPv6"
  };
  return names[value] || `LINKTYPE ${value}`;
}

function readU16(bytes, offset) {
  return (bytes[offset] << 8) | bytes[offset + 1];
}

function readU32(bytes, offset) {
  return ((bytes[offset] * 0x1000000) + ((bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3])) >>> 0;
}

function addField(lines, name, value) {
  const clean = compactValue(value);
  if (name && clean) {
    lines.push(`${name}: ${clean}`);
  }
}

function compactValue(value) {
  const clean = String(value ?? "").replace(/[\r\n\t]+/g, " ").trim();
  return clean.length > 360 ? `${clean.slice(0, 360)}...[truncated]` : clean;
}

function decodeUrl(value) {
  try {
    return decodeURIComponent(String(value || "").replace(/\+/g, " "));
  } catch {
    return value;
  }
}

function protocolName(value) {
  const names = {
    1: "ICMP",
    6: "TCP",
    17: "UDP",
    58: "ICMPv6"
  };
  return names[value] || `IP/${value}`;
}

function tlsRecordType(value) {
  const names = {
    20: "change_cipher_spec",
    21: "alert",
    22: "handshake",
    23: "application_data"
  };
  return names[value] || `type_${value}`;
}

function tlsHandshakeType(value) {
  const names = {
    1: "client_hello",
    2: "server_hello",
    11: "certificate",
    20: "finished"
  };
  return names[value] || `handshake_${value}`;
}

function dnsTypeName(value) {
  const names = {
    1: "A",
    2: "NS",
    5: "CNAME",
    15: "MX",
    16: "TXT",
    28: "AAAA",
    33: "SRV",
    65: "HTTPS"
  };
  return names[value] || String(value);
}

function ipv4(bytes, offset) {
  return `${bytes[offset]}.${bytes[offset + 1]}.${bytes[offset + 2]}.${bytes[offset + 3]}`;
}

function ipv6(bytes, offset) {
  const groups = [];
  for (let i = 0; i < 16; i += 2) {
    groups.push(readU16(bytes, offset + i).toString(16));
  }
  return groups.join(":");
}

function mac(bytes) {
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, "0")).join(":");
}

function tcpFlags(value) {
  const flags = [];
  if (value & 0x01) flags.push("FIN");
  if (value & 0x02) flags.push("SYN");
  if (value & 0x04) flags.push("RST");
  if (value & 0x08) flags.push("PSH");
  if (value & 0x10) flags.push("ACK");
  if (value & 0x20) flags.push("URG");
  return flags.join(",") || "-";
}

function ascii(bytes) {
  return Array.from(bytes).map((b) => String.fromCharCode(b)).join("");
}

function unknown(info) {
  return {
    protocol: "UNKNOWN",
    source: "-",
    destination: "-",
    info
  };
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
