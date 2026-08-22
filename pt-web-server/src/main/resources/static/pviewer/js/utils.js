/**
 * 通用工具函数
 */

/** 从 DataView 读取定长字符串（按字节，遇 \0 截断） */
export function readCString(dv, offset, len) {
  const bytes = new Uint8Array(dv.buffer, dv.byteOffset + offset, len);
  let end = 0;
  while (end < len && bytes[end] !== 0) end++;
  return new TextDecoder('latin1').decode(bytes.subarray(0, end));
}
