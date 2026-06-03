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
    return {
      protocol: "TCP",
      source: `${src}:${sport}`,
      destination: `${dst}:${dport}`,
      info: `${ipVersion} TCP ${tcpFlags(bytes[offset + 13])}`
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
  packetDetail.textContent = [
    `#${packet.index}`,
    `time: ${formatTime(packet.timestamp)}`,
    `protocol: ${d.protocol}`,
    `source: ${d.source}`,
    `destination: ${d.destination}`,
    `length: ${packet.length}`,
    `summary: ${d.info}`,
    "",
    hexDump(packet.bytes.slice(0, 512))
  ].join("\n");
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
