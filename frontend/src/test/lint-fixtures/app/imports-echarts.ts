// VIOLATION — echarts may only be imported by ChartRenderer.tsx (FE-D16).
import * as echarts from 'echarts';

export const leaked = echarts;
