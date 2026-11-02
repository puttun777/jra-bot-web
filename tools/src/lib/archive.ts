/**
 * 検査用の最小限の ZIP（jar）と tar（docker export）の読み取り。書き込みはしない。
 * ZIP: 中央ディレクトリから名前の一覧を取り、STORED/DEFLATE の項目だけ展開できる（ZIP64・暗号化は拒否 = fail closed）。
 * tar: ustar/GNU/PAX の見出しを読み、名前・モード・種類を返す（内容は読み飛ばす）。
 */
import { inflateRawSync } from "node:zlib";

export interface ZipEntry {
  readonly name: string;
  readonly method: number;
  readonly compressedSize: number;
  readonly uncompressedSize: number;
  readonly localHeaderOffset: number;
  readonly flags: number;
}

export class ZipReader {
  readonly entries: readonly ZipEntry[];

  constructor(private readonly buf: Buffer) {
    this.entries = ZipReader.readCentralDirectory(buf);
  }

  get names(): string[] {
    return this.entries.map((e) => e.name);
  }

  private static readCentralDirectory(buf: Buffer): ZipEntry[] {
    const EOCD = 0x06054b50;
    let eocd = -1;
    for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 0xffff); i--) {
      if (buf.readUInt32LE(i) === EOCD) {
        eocd = i;
        break;
      }
    }
    if (eocd < 0) throw new Error("ZIP の終端レコードが無い");
    const count = buf.readUInt16LE(eocd + 10);
    const cdOffset = buf.readUInt32LE(eocd + 16);
    if (count === 0xffff || cdOffset === 0xffffffff) throw new Error("ZIP64 は対象外");
    const out: ZipEntry[] = [];
    let p = cdOffset;
    for (let i = 0; i < count; i++) {
      if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error("ZIP の中央ディレクトリが壊れている");
      const flags = buf.readUInt16LE(p + 8);
      const method = buf.readUInt16LE(p + 10);
      const compressedSize = buf.readUInt32LE(p + 20);
      const uncompressedSize = buf.readUInt32LE(p + 24);
      const nameLen = buf.readUInt16LE(p + 28);
      const extraLen = buf.readUInt16LE(p + 30);
      const commentLen = buf.readUInt16LE(p + 32);
      const localHeaderOffset = buf.readUInt32LE(p + 42);
      const name = buf.subarray(p + 46, p + 46 + nameLen).toString("utf8");
      out.push({ name, method, compressedSize, uncompressedSize, localHeaderOffset, flags });
      p += 46 + nameLen + extraLen + commentLen;
    }
    return out;
  }

  read(name: string): Buffer {
    const e = this.entries.find((x) => x.name === name);
    if (e === undefined) throw new Error(`ZIP に ${name} が無い`);
    if ((e.flags & 0x1) !== 0) throw new Error(`${name}: 暗号化された項目は対象外`);
    const p = e.localHeaderOffset;
    if (this.buf.readUInt32LE(p) !== 0x04034b50) throw new Error(`${name}: ローカルヘッダが壊れている`);
    const start = p + 30 + this.buf.readUInt16LE(p + 26) + this.buf.readUInt16LE(p + 28);
    const data = this.buf.subarray(start, start + e.compressedSize);
    if (e.method === 0) return Buffer.from(data);
    if (e.method === 8) return inflateRawSync(data);
    throw new Error(`${name}: 未対応の圧縮方式 ${e.method}`);
  }
}

export interface TarMember {
  readonly name: string;
  readonly mode: number;
  /** '0'/'\0' = 通常ファイル、'5' = ディレクトリ、'2' = シンボリックリンク など */
  readonly type: string;
  readonly isDir: boolean;
}

function cstr(b: Buffer): string {
  const z = b.indexOf(0);
  return (z >= 0 ? b.subarray(0, z) : b).toString("utf8");
}

function octal(b: Buffer): number {
  const s = cstr(b).trim();
  if (s === "") return 0;
  if (!/^[0-7]+$/.test(s)) throw new Error(`tar の数値フィールドが不正: ${JSON.stringify(s)}`);
  return Number.parseInt(s, 8);
}

function paxPath(body: Buffer): string | undefined {
  let p = 0;
  let found: string | undefined;
  while (p < body.length) {
    const sp = body.indexOf(0x20, p);
    if (sp < 0) break;
    const len = Number.parseInt(body.subarray(p, sp).toString("ascii"), 10);
    if (!Number.isInteger(len) || len <= 0) break;
    const rec = body.subarray(sp + 1, p + len - 1).toString("utf8");
    const eq = rec.indexOf("=");
    if (eq > 0 && rec.slice(0, eq) === "path") found = rec.slice(eq + 1);
    p += len;
  }
  return found;
}

/** 非同期のバイト列（docker export の標準出力）から tar の項目を順に返す。 */
export async function* readTar(stream: AsyncIterable<Buffer>): AsyncGenerator<TarMember> {
  let pending: Buffer = Buffer.alloc(0);
  let skip = 0; // 内容として読み飛ばすバイト数（512 の倍数に切り上げ済み）
  let longName: string | undefined;
  let collect: { kind: "L" | "x"; size: number; padded: number } | undefined;
  for await (const chunk of stream) {
    pending = pending.length === 0 ? chunk : Buffer.concat([pending, chunk]);
    for (;;) {
      if (collect !== undefined) {
        if (pending.length < collect.padded) break;
        const body = pending.subarray(0, collect.size);
        if (collect.kind === "L") longName = cstr(body);
        else longName = paxPath(body) ?? longName;
        pending = pending.subarray(collect.padded);
        collect = undefined;
        continue;
      }
      if (skip > 0) {
        const n = Math.min(skip, pending.length);
        pending = pending.subarray(n);
        skip -= n;
        if (skip > 0) break;
      }
      if (pending.length < 512) break;
      const h = pending.subarray(0, 512);
      pending = pending.subarray(512);
      if (h.every((x) => x === 0)) continue; // 終端ブロック
      const size = octal(h.subarray(124, 136));
      const type = String.fromCharCode(h[156] ?? 0);
      const padded = Math.ceil(size / 512) * 512;
      if (type === "L" || type === "x") {
        collect = { kind: type, size, padded };
        continue;
      }
      if (type === "g") {
        skip = padded;
        continue;
      }
      const prefix = cstr(h.subarray(345, 500));
      const base = cstr(h.subarray(0, 100));
      const name = longName ?? (prefix !== "" ? `${prefix}/${base}` : base);
      longName = undefined;
      // Python の tarfile と同じく、ディレクトリ名の末尾の / は落とす
      const clean = name.replace(/^\.\//, "");
      yield { name: type === "5" ? clean.replace(/\/+$/, "") : clean, mode: octal(h.subarray(100, 108)), type, isDir: type === "5" };
      skip = padded;
    }
  }
}
