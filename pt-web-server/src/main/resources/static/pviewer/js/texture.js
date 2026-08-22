/**
 * PT 纹理加载：解密 + 解码 TGA/BMP → three.js 纹理
 *
 * 纹理被加密（对照 jpstale ImageDecoder）：
 *  - TGA 加密魔数 0x47 0x38；解密 data[0]=0 data[1]=0，data[i]-=i*i (i=2..17)
 *  - BMP 加密魔数 0x41 0x38；解密 data[0]='B'(0x42) data[1]='M'(0x4D)，data[i]-=i*i (i=2..13)
 *
 * 解码：
 *  - TGA: 未压缩 truecolor（类型2），支持 24/32bit
 *  - BMP: 未压缩 BITMAPINFOHEADER（biCompression=0），支持 24bit（无 alpha）
 */
import * as THREE from 'three';

/** 解密游戏纹理头部（原地修改 ArrayBuffer 前部，返回同一 buffer） */
function decryptHeader(buf) {
  const u8 = new Uint8Array(buf, 0, Math.min(buf.byteLength, 18));
  if (u8[0] === 0x47 && u8[1] === 0x38) {
    // TGA 解密
    u8[0] = 0;
    u8[1] = 0;
    for (let i = 2; i < 18 && i < u8.length; i++) u8[i] = (u8[i] - i * i) & 0xff;
    return 'tga';
  }
  if (u8[0] === 0x41 && u8[1] === 0x38) {
    // BMP 解密
    u8[0] = 0x42;
    u8[1] = 0x4d;
    for (let i = 2; i < 14 && i < u8.length; i++) u8[i] = (u8[i] - i * i) & 0xff;
    return 'bmp';
  }
  // 未加密：按扩展名判断交给调用方
  return null;
}

/** 解析 TGA（未压缩 truecolor）→ { width, height, data(Uint8ClampedArray RGBA), format } */
function decodeTGA(u8) {
  const idLength = u8[0];
  const colorMapType = u8[1];
  const imageType = u8[2];
  // 只支持未压缩 truecolor（类型 2）。带 RLE 的类型 10 也常见
  if (imageType !== 2 && imageType !== 10) {
    throw new Error('unsupported TGA image type: ' + imageType);
  }
  const colorMapSpecStart = 3;
  const colorMapLength = u8[colorMapSpecStart + 2] | (u8[colorMapSpecStart + 3] << 8);
  const colorMapEntrySize = u8[colorMapSpecStart + 5];
  const x0 = u8[8] | (u8[9] << 8);
  const y0 = u8[10] | (u8[11] << 8);
  const width = u8[12] | (u8[13] << 8);
  const height = u8[14] | (u8[15] << 8);
  const bpp = u8[16];
  const descriptor = u8[17];

  const pixelSize = bpp >> 3;
  let offset = 18 + idLength;
  if (colorMapType === 1) {
    offset += colorMapLength * ((colorMapEntrySize + 7) >> 3);
  }

  const data = new Uint8ClampedArray(width * height * 4);
  const flipV = (descriptor & 0x20) === 0; // bit5=1 时顶部优先
  const flipH = (descriptor & 0x10) !== 0;

  function setPixel(px, py, r, g, b, a) {
    if (flipH) px = width - 1 - px;
    let row = flipV ? (height - 1 - py) : py;
    const idx = (row * width + px) * 4;
    data[idx] = r; data[idx + 1] = g; data[idx + 2] = b; data[idx + 3] = a;
  }

  if (imageType === 2) {
    let i = offset;
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        let b, g, r, a = 255;
        if (bpp === 32) { b = u8[i]; g = u8[i + 1]; r = u8[i + 2]; a = u8[i + 3]; i += 4; }
        else if (bpp === 24) { b = u8[i]; g = u8[i + 1]; r = u8[i + 2]; i += 3; }
        else if (bpp === 16) {
          const v = u8[i] | (u8[i + 1] << 8); i += 2;
          r = ((v >> 10) & 0x1f) * 255 / 31;
          g = ((v >> 5) & 0x1f) * 255 / 31;
          b = (v & 0x1f) * 255 / 31;
        } else throw new Error('unsupported TGA bpp: ' + bpp);
        setPixel(x, y, r, g, b, a);
      }
    }
  } else {
    // type 10 RLE
    let i = offset, x = 0, y = 0;
    while (y < height) {
      const packet = u8[i++];
      const count = (packet & 0x7f) + 1;
      let br = 0, bg = 0, bb = 0, ba = 255;
      if ((packet & 0x80) === 0) {
        // raw packet: count 个像素
        for (let k = 0; k < count; k++) {
          if (bpp === 32) { bb = u8[i]; bg = u8[i + 1]; br = u8[i + 2]; ba = u8[i + 3]; i += 4; }
          else if (bpp === 24) { bb = u8[i]; bg = u8[i + 1]; br = u8[i + 2]; i += 3; }
          setPixel(x, y, br, bg, bb, ba);
          if (++x >= width) { x = 0; y++; if (y >= height) break; }
        }
      } else {
        // RLE packet: 重复 1 个像素 count 次
        if (bpp === 32) { bb = u8[i]; bg = u8[i + 1]; br = u8[i + 2]; ba = u8[i + 3]; i += 4; }
        else if (bpp === 24) { bb = u8[i]; bg = u8[i + 1]; br = u8[i + 2]; i += 3; }
        for (let k = 0; k < count; k++) {
          setPixel(x, y, br, bg, bb, ba);
          if (++x >= width) { x = 0; y++; if (y >= height) break; }
        }
      }
    }
  }
  return { width, height, data };
}

/** 解析 BMP（BITMAPINFOHEADER，biCompression=0）→ { width, height, data } */
export function decodeBMP(u8) {
  // 'BM' 已由解密保证
  const dataOffset = u8[10] | (u8[11] << 8) | (u8[12] << 16) | (u8[13] << 24);
  const headerSize = u8[14] | (u8[15] << 8) | (u8[16] << 16) | (u8[17] << 24);
  const width = u8[18] | (u8[19] << 8) | (u8[20] << 16) | (u8[21] << 24);
  const heightRaw = u8[22] | (u8[23] << 8) | (u8[24] << 16) | (u8[25] << 24);
  const height = Math.abs(heightRaw);
  const topDown = heightRaw < 0;
  const planes = u8[26] | (u8[27] << 8);
  const bpp = u8[28] | (u8[29] << 8);
  const compression = u8[30] | (u8[31] << 8) | (u8[32] << 16) | (u8[33] << 24);

  if (compression !== 0) throw new Error('unsupported BMP compression: ' + compression);

  const bytesPerPixel = bpp >> 3;
  const rowSize = Math.floor((bpp * width + 31) / 32) * 4;
  const data = new Uint8ClampedArray(width * height * 4);

  for (let y = 0; y < height; y++) {
    const srcRow = topDown ? y : (height - 1 - y);
    const srcOff = dataOffset + srcRow * rowSize;
    for (let x = 0; x < width; x++) {
      const si = srcOff + x * bytesPerPixel;
      const di = (y * width + x) * 4;
      if (bpp === 24) {
        const r = u8[si + 2], g = u8[si + 1], b = u8[si];
        data[di] = r; data[di + 1] = g; data[di + 2] = b;
        // C++ EXETexture.cpp：BMP 用黑色做 color key（d3dcolorkey = ARGB(255,0,0,0)）→ 纯黑像素变透明
        data[di + 3] = (r === 0 && g === 0 && b === 0) ? 0 : 255;
      } else if (bpp === 32) {
        data[di] = u8[si + 2]; data[di + 1] = u8[si + 1]; data[di + 2] = u8[si];
        data[di + 3] = u8[si + 3];
      } else throw new Error('unsupported BMP bpp: ' + bpp);
    }
  }
  return { width, height, data };
}

/**
 * 从 URL 加载纹理（自动解密），失败返回 null
 * @returns {Promise<THREE.Texture|null>}
 */
export async function loadTexture(url) {
  let resp;
  try {
    resp = await fetch(url, { cache: 'no-store' });
    if (!resp.ok) return null;
  } catch (e) { return null; }
  const buf = await resp.arrayBuffer();
  const u8 = new Uint8Array(buf);
  let format = null;
  if (u8[0] === 0x47 && u8[1] === 0x38) format = 'tga';
  else if (u8[0] === 0x41 && u8[1] === 0x38) format = 'bmp';
  else {
    // 未加密：按扩展名判断
    const lower = url.toLowerCase();
    if (lower.endsWith('.tga')) format = 'tga';
    else if (lower.endsWith('.bmp')) format = 'bmp';
    else if (lower.endsWith('.png')) {
      // PNG 直接交给浏览器解码
      const img = await createImageBitmap(new Blob([buf]));
      const tex = new THREE.Texture(img);
      tex.flipY = true;
      tex.userData.format = 'png';
      tex.needsUpdate = true;
      return tex;
    }
    else return null;
  }

  // 解密头部（拷贝一份避免改原 buffer 的 view 副作用）
  const copy = buf.slice(0);
  const decU8 = new Uint8Array(copy);
  if (format === 'tga' && decU8[0] === 0x47 && decU8[1] === 0x38) {
    decU8[0] = 0; decU8[1] = 0;
    for (let i = 2; i < 18; i++) decU8[i] = (decU8[i] - i * i) & 0xff;
  } else if (format === 'bmp' && decU8[0] === 0x41 && decU8[1] === 0x38) {
    decU8[0] = 0x42; decU8[1] = 0x4d;
    for (let i = 2; i < 14; i++) decU8[i] = (decU8[i] - i * i) & 0xff;
  }

  let decoded;
  try {
    if (format === 'tga') decoded = decodeTGA(decU8);
    else decoded = decodeBMP(decU8);
  } catch (e) {
    console.warn('texture decode failed', url, e.message);
    return null;
  }

  const tex = new THREE.DataTexture(decoded.data, decoded.width, decoded.height, THREE.RGBAFormat);
  // 与 jME3 一致：jME3 TextureKey 默认 flipY=true，UV 用 (1-v)。
  // flipY=true 时 UV 1-v 在采样时翻转成 v，等价于直接采样原始 PT UV —— 必须与 Java 保持一致。
  tex.flipY = true;
  tex.userData.format = format; // 材质据此决定 alpha 处理（tga/png 有 alpha，bmp 靠黑色 colorkey）
  tex.needsUpdate = true;
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

/**
 * 从 ArrayBuffer 解码 BMP/TGA 为 RGBA 像素数据（用于图标提取等非 three.js 场景）
 * @param {ArrayBuffer} buf 原始文件数据
 * @param {'bmp'|'tga'} [format] 强制格式，不传则自动检测
 * @returns {{ width: number, height: number, data: Uint8ClampedArray }}
 */
export function decodeImage(buf, format) {
  const u8 = new Uint8Array(buf);
  if (!format) {
    if (u8[0] === 0x47 && u8[1] === 0x38) format = 'tga';
    else if (u8[0] === 0x41 && u8[1] === 0x38) format = 'bmp';
    else if (u8[0] === 0x42 && u8[1] === 0x4d) format = 'bmp'; // plain BMP 'BM'
    else throw new Error('unknown image format');
  }
  const copy = buf.slice(0);
  const dec = new Uint8Array(copy);
  if (format === 'bmp' && u8[0] === 0x41 && u8[1] === 0x38) {
    dec[0] = 0x42; dec[1] = 0x4d;
    for (let i = 2; i < 14; i++) dec[i] = (dec[i] - i * i) & 0xff;
  } else if (format === 'tga' && u8[0] === 0x47 && u8[1] === 0x38) {
    dec[0] = 0; dec[1] = 0;
    for (let i = 2; i < 18; i++) dec[i] = (dec[i] - i * i) & 0xff;
  }
  if (format === 'bmp') return decodeBMP(dec);
  return decodeTGA(dec);
}

/**
 * 将 RGBA 像素数据绘制到 canvas 并返回 dataURL（用于 <img src> 显示）
 */
export function pixelsToDataURL(imgData) {
  const c = new OffscreenCanvas(imgData.width, imgData.height);
  const ctx = c.getContext('2d');
  ctx.putImageData(new ImageData(imgData.data, imgData.width, imgData.height), 0, 0);
  return c.convertToBlob({ type: 'image/png' }).then(blob => URL.createObjectURL(blob));
}
