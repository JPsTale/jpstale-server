/**
 * Sin/Cos lookup tables (from smSin.cpp)
 * ANGLE_360 = 4096 entries
 * sdGetSin/sdGetCos → wSinus/wCosinus: scale 32768 (15.16 fixed-point)
 * GetSin/GetCos → sinus/cosinus: scale 65536 (16.16 fixed-point) — not exported here
 */
const ANGLE_360 = 4096;
const sdGetSin = new Float64Array(ANGLE_360 + 1);
const sdGetCos = new Float64Array(ANGLE_360 + 1);

for (let i = 0; i <= ANGLE_360; i++) {
  const rad = (i / ANGLE_360) * Math.PI * 2;
  sdGetSin[i] = Math.sin(rad) * 32768;
  sdGetCos[i] = Math.cos(rad) * 32768;
}

export { sdGetSin, sdGetCos };
