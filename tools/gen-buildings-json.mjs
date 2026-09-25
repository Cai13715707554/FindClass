/*
 * 生成 app/src/main/assets/buildings.json。
 *
 * 为什么用脚本而不是手写 JSON：
 *   室内元素是规则排布的（南北两排 + 走廊），用“局部米坐标 + 矩形”描述比直接手写
 *   几百个经纬度点更不容易出错，也方便以后调整楼栋尺寸。脚本只做一次性的
 *   “米 -> 经纬度”换算，产出的 JSON 是仓库里的静态数据，运行时直接读取，不做任何生成。
 *
 * 注意：坐标基准是 GCJ-02，与定位层（高德/系统 GPS + 手工录入）保持一致。
 * 运行：node tools/gen-buildings-json.mjs
 */

import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..');

// ---------------------------------------------------------------- 坐标换算

const METERS_PER_DEG_LAT = 110574;
const metersPerDegLng = (lat) => 111320 * Math.cos((lat * Math.PI) / 180);

const round7 = (v) => Number(v.toFixed(7));

/** 局部米坐标（x 向东、y 向北）-> 经纬度 */
function ll(origin, xM, yM) {
  return {
    lng: round7(origin.lng + xM / metersPerDegLng(origin.lat)),
    lat: round7(origin.lat + yM / METERS_PER_DEG_LAT),
  };
}

/** 矩形（左下 / 右下 / 右上 / 左上） */
function rect(origin, x1, y1, x2, y2) {
  return [
    ll(origin, x1, y1),
    ll(origin, x2, y1),
    ll(origin, x2, y2),
    ll(origin, x1, y2),
  ];
}

// ---------------------------------------------------------------- 楼栋布局

/*
 * 每层标准布局（局部米坐标，原点在楼栋中心）：
 *
 *       北侧一排教室（y: 1 ~ 9）
 *   ┌──┬──┬──┬──┬──┐
 *   │  │  │  │  │  │
 *   ╞══╧══╧══╧══╧══╡  ← 走廊（y: -1 ~ 1）
 *   │南门│楼梯│教室│教室│教室│电梯│   （y: -9 ~ -1，入口延伸到 y=1）
 *   └───┴───┴───┴───┴───┴───┘
 */

/** 北侧教室：从 x = -20 开始，每间 8 米宽 */
function northRow(origin, names) {
  return names.map((name, i) => {
    const x1 = -20 + i * 8;
    return {
      type: 'room',
      name,
      points: rect(origin, x1, 1, x1 + 8, 9),
    };
  });
}

/** 南侧元素：从 x = -26 依次向右排列，宽度由 width 决定 */
function southRow(origin, items) {
  let x = -26;
  return items.map((item) => {
    const { width } = item;
    const isEntrance = item.type === 'entrance';
    // 入口占满走廊口，其余贴走廊南侧
    const y1 = isEntrance ? 1 : -1;
    const y2 = isEntrance ? -1 : -9;
    const el = {
      type: item.type,
      name: item.name,
      points: rect(origin, x, y2, x + width, y1),
    };
    x += width;
    return el;
  });
}

let elementSeq = 0;

function buildFloor(origin, id, level, relativeHeightM, northNames, southItems) {
  const raw = [...northRow(origin, northNames), ...southRow(origin, southItems)];
  return {
    id,
    level,
    relative_height_m: relativeHeightM,
    elements: raw.map((el) => ({
      id: `${id}_e${String(elementSeq++).padStart(3, '0')}`,
      ...el,
    })),
  };
}

// 两栋楼的原点（GCJ-02，广州某校园的示意坐标）
const A_ORIGIN = { lng: 113.1234, lat: 23.1234 };
const B_ORIGIN = { lng: 113.1244, lat: 23.1234 };

/** 标准南侧配置：入口 + 楼梯 + 若干教室 + 电梯 */
function southLayout(rooms) {
  return [
    { name: '南门', type: 'entrance', width: 4 },
    { name: '东楼梯口', type: 'stair', width: 4 },
    ...rooms.map((name) => ({ name, type: 'room', width: 8 })),
    { name: '电梯口', type: 'elevator', width: 4 },
  ];
}

const A = {
  id: 'A',
  name: 'A栋',
  polygon: [
    ll(A_ORIGIN, -32, -12),
    ll(A_ORIGIN, 34, -12),
    ll(A_ORIGIN, 34, 12),
    ll(A_ORIGIN, -32, 12),
  ],
  floors: [
    buildFloor(
      A_ORIGIN,
      'A_1F',
      1,
      0,
      ['语文教研室', '数学教研室', '英语教研室', '历史教研室', '地理教研室'],
      [
        { name: '南门', type: 'entrance', width: 4 },
        { name: '东楼梯口', type: 'stair', width: 4 },
        { name: '计算机房', type: 'room', width: 8 },
        { name: '多媒体教室', type: 'room', width: 8 },
        { name: '语音教室', type: 'room', width: 8 },
        { name: '卫生间', type: 'toilet', width: 4 },
        { name: '电梯口', type: 'elevator', width: 4 },
      ],
    ),
    buildFloor(
      A_ORIGIN,
      'A_2F',
      2,
      4,
      ['物理实验室', '化学实验室', '生物实验室', '科学探究室', '创客空间'],
      southLayout(['音乐教室', '美术教室', '书法教室']),
    ),
    buildFloor(
      A_ORIGIN,
      'A_3F',
      3,
      8,
      ['高一(1)班', '高一(2)班', '高一(3)班', '高一(4)班', '高一(5)班'],
      southLayout(['高一(6)班', '高一(7)班', '教务处']),
    ),
  ],
};

const B = {
  id: 'B',
  name: 'B栋',
  polygon: [
    ll(B_ORIGIN, -32, -12),
    ll(B_ORIGIN, 34, -12),
    ll(B_ORIGIN, 34, 12),
    ll(B_ORIGIN, -32, 12),
  ],
  floors: [
    buildFloor(
      B_ORIGIN,
      'B_1F',
      1,
      0,
      ['报告厅', '接待室', '校史馆'],
      [
        { name: '西门', type: 'entrance', width: 4 },
        { name: '西楼梯口', type: 'stair', width: 4 },
        { name: '值班室', type: 'office', width: 8 },
        { name: '卫生间', type: 'toilet', width: 4 },
        { name: '电梯口', type: 'elevator', width: 4 },
      ],
    ),
    buildFloor(
      B_ORIGIN,
      'B_2F',
      2,
      4,
      ['图书馆', '阅览室', '电子阅览室'],
      [
        { name: '西楼梯口', type: 'stair', width: 4 },
        { name: '自习室', type: 'room', width: 8 },
        { name: '研讨室', type: 'room', width: 8 },
        { name: '教师办公室', type: 'office', width: 8 },
        { name: '电梯口', type: 'elevator', width: 4 },
      ],
    ),
    buildFloor(
      B_ORIGIN,
      'B_3F',
      3,
      8,
      ['机房', '网络中心', '多媒体报告厅'],
      [
        { name: '西楼梯口', type: 'stair', width: 4 },
        { name: '实验室', type: 'room', width: 8 },
        { name: '准备室', type: 'room', width: 8 },
        { name: '器材室', type: 'office', width: 8 },
        { name: '电梯口', type: 'elevator', width: 4 },
      ],
    ),
  ],
};

const OUTPUT = { buildings: [A, B] };

const jsonPath = resolve(repoRoot, 'app/src/main/assets/buildings.json');
mkdirSync(dirname(jsonPath), { recursive: true });
writeFileSync(jsonPath, JSON.stringify(OUTPUT, null, 2) + '\n', 'utf8');

const buildingCount = OUTPUT.buildings.length;
const floorCount = OUTPUT.buildings.reduce((n, b) => n + b.floors.length, 0);
const elementCount = OUTPUT.buildings.reduce(
  (n, b) => n + b.floors.reduce((m, f) => m + f.elements.length, 0),
  0,
);

console.log(`已生成 ${jsonPath}`);
console.log(`楼栋 ${buildingCount} 栋 / 楼层 ${floorCount} 层 / 元素 ${elementCount} 个`);
